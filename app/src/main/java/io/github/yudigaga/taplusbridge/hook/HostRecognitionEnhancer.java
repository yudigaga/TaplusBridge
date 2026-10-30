package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.TextView;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.recognition.LinkExtractor;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Local links join the host's existing cards; no destination is inferred from a title. */
public final class HostRecognitionEnhancer {
    private static final int MAX_METADATA_NODES = 128;
    private static final long PREVIEW_TTL_NANOS = 1_000_000_000L;
    private final Class<?> modelClass;
    private final Constructor<?> modelConstructor;
    private final Field title;
    private final Field category;
    private final Field detailUrl;
    private final Field packageName;
    private final Field intent;
    private final LinkedHashSet<String> links = new LinkedHashSet<>();
    private final Set<String> probeLinks = new LinkedHashSet<>();
    private final Map<String, CachedPreview> previews = new LinkedHashMap<>();
    private final Map<Object, Drawable> boundIcons = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, String> boundTargets = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<String> reported = new LinkedHashSet<>();
    private long pickStartedNanos;
    private int performanceEvents;
    private volatile Consumer<String> reporter = ignored -> { };
    private static final Set<String> DIAGNOSTIC_STAGES = Set.of("capture_text", "capture_catcher",
            "capture_image", "capture_clipboard", "capture_ocr", "capture_recommendation", "capture_segment",
            "publish_server", "publish_local", "bind_ner", "bind_recommend", "resolve",
            "apply_card", "icon", "icon_guard");
    private static final Set<String> DIAGNOSTIC_OUTCOMES = Set.of("reached", "success", "skipped",
            "unsupported", "empty", "none", "failed", "icon_requested", "icon_failed",
            "icon_displayed", "fields_missing", "captured", "published");
    private static final Set<String> DIAGNOSTIC_REASONS = Set.of("disabled", "recognition_disabled",
            "no_match", "ambiguous", "target_missing", "invalid_request", "unsupported_host",
            "unauthorized", "unknown", "empty_response", "invalid_preview", "exception", "resolved");
    private static final Set<String> PERF_OUTCOMES = Set.of("cache_hit", "success", "none", "failed");

    /** Optional process-local diagnostics: only fixed stages, counts, packages, and exception classes. */
    public void setReporter(Consumer<String> reporter) {
        this.reporter = reporter == null ? ignored -> { } : reporter;
    }

    void diagnostic(String stage, String outcome, String target, int input, int output,
                    Class<?> error, String reason) {
        if (!DIAGNOSTIC_STAGES.contains(stage) || !DIAGNOSTIC_OUTCOMES.contains(outcome)) return;
        String safeTarget = HostPolicy.validTarget(target) ? target : "none";
        int captured = capturedLinks().size();
        String key = stage + '/' + outcome + '/' + safeTarget;
        synchronized (reported) {
            if (reported.size() >= 64 || !reported.add(key)) return;
        }
        StringBuilder message = new StringBuilder("recognition stage=").append(stage)
                .append(" outcome=").append(outcome).append(" target=").append(safeTarget)
                .append(" input=").append(Math.max(0, Math.min(131072, input)))
                .append(" output=").append(Math.max(0, Math.min(131072, output)))
                .append(" captured=").append(captured);
        if (stage.startsWith("publish_")) message.append(" server=").append(Math.max(0, input))
                .append(" local=").append(Math.max(0, output - input)).append(" total=").append(Math.max(0, output));
        if (reason != null) message.append(" reason=")
                .append(DIAGNOSTIC_REASONS.contains(reason) ? reason : "unknown");
        if (error != null) {
            String name = error.getName();
            message.append(Throwable.class.isAssignableFrom(error) ? " exception=" : " class=")
                    .append(name.length() <= 256 && name.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                    ? name : "Throwable");
        }
        try { reporter.accept(message.toString()); }
        catch (RuntimeException ignored) { }
    }

    private void performance(String stage, long startedNanos, String outcome) {
        String safeOutcome = PERF_OUTCOMES.contains(outcome) ? outcome : "failed";
        long elapsedMicros = Math.max(0, (System.nanoTime() - startedNanos) / 1_000L);
        synchronized (reported) {
            if (performanceEvents >= 64) return;
            performanceEvents++;
        }
        try { reporter.accept("recognition perf stage=" + stage + " elapsed_us=" + elapsedMicros + " outcome=" + safeOutcome); }
        catch (RuntimeException ignored) { }
    }

    public HostRecognitionEnhancer(ClassLoader loader) throws ReflectiveOperationException {
        this(loader.loadClass("com.miui.contentextension.data.recognition.TextRecommendationInfo"));
    }

    HostRecognitionEnhancer(Class<?> model) throws ReflectiveOperationException {
        modelClass = model;
        modelConstructor = model.getDeclaredConstructor();
        modelConstructor.setAccessible(true);
        title = stringField(model, "title");
        category = stringField(model, "cat");
        detailUrl = stringField(model, "detailUrl");
        packageName = stringField(model, "packageName");
        intent = stringField(model, "intent");
    }

    public synchronized void clear() {
        links.clear();
        probeLinks.clear();
        synchronized (previews) { previews.clear(); }
        boundIcons.clear();
        boundTargets.clear();
        pickStartedNanos = 0;
        synchronized (reported) {
            reported.clear();
            performanceEvents = 0;
        }
    }

    public synchronized void beginPick() { pickStartedNanos = System.nanoTime(); }

    /** Adds links without retaining the recognized text, message XML, or complete view metadata. */
    public synchronized void captureText(String text) {
        int previousSize = links.size();
        for (String url : LinkExtractor.extract(text)) {
            if (links.size() >= LinkExtractor.MAX_RESULTS) break;
            links.add(url);
        }
        diagnostic("capture_text", links.isEmpty() ? "empty" : "captured", null,
                text == null ? 0 : text.length(), links.size(), null, null);
        if (previousSize == 0 && !links.isEmpty() && pickStartedNanos > 0)
            performance("pick_to_url", pickStartedNanos, "success");
    }

    public synchronized List<String> capturedLinks() { return new ArrayList<>(links); }

    /** Existing server URL cards also end the need to simulate a click on the original source. */
    public synchronized void captureRecommendationLinks(List<?> recommendations) {
        if(recommendations==null)return;
        for(Object info:recommendations)if(modelClass.isInstance(info) && !HostCaptureAction.isAction(info)) {
            captureText(read(detailUrl,info));
            Intent candidate=nativeCandidate(info);
            if(candidate!=null)captureText(candidate.getDataString());
        }
    }

    public synchronized void captureProbeUrl(String url) {
        if (!io.github.yudigaga.taplusbridge.capture.IntentObservation.validHttpUrl(url)) return;
        captureText(url); probeLinks.add(url);
    }
    public synchronized boolean isProbeRecommendation(Object info) {
        return modelClass.isInstance(info) && probeLinks.contains(read(detailUrl, info));
    }

    /** ContentCatcher supplies richer pick_mode metadata on some ROM/app combinations. */
    public void capture(Object catcherResult) {
        if (catcherResult == null) return;
        try {
            Method params = catcherResult.getClass().getMethod("getParams", String.class);
            capturePayload(params.invoke(catcherResult, "text_pick"));
            capturePayload(params.invoke(catcherResult, "image_pick"));
            try {
                Method properties = catcherResult.getClass().getMethod("getPropertyMap");
                capturePayload(properties.invoke(catcherResult));
            } catch (NoSuchMethodException ignored) { }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Missing structured metadata is not a reason to invent a destination.
            diagnostic("capture_catcher", "failed", null, 0, capturedLinks().size(), ignored.getClass(), null);
        }
        // ContentCatcher may return results under a job tag other than text_pick/image_pick.
        // This SDK container is optional; inspect only its bounded Map payload and keep
        // the existing pick-mode capture useful when the SDK method is absent or fails.
        try {
            Method results = catcherResult.getClass().getMethod("getResults");
            Object payload = results.invoke(catcherResult);
            if (payload instanceof Map<?, ?>) capturePayload(payload);
        } catch (NoSuchMethodException ignored) {
            // Keep the existing getParams/getPropertyMap path if this optional method is absent.
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            diagnostic("capture_catcher", "failed", null, 0, capturedLinks().size(), ignored.getClass(), null);
        }
        diagnostic("capture_catcher", "captured", null, 0, capturedLinks().size(), null, null);
    }

    /** Only scalar strings and bounded containers are inspected; arbitrary message objects are not walked. */
    public void capturePayload(Object payload) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] budget = {MAX_METADATA_NODES};
        capturePayload(payload, visited, budget, 0);
    }

    @SuppressWarnings("deprecation")
    private void capturePayload(Object payload, Set<Object> visited, int[] budget, int depth) {
        if (payload == null || depth > 5 || budget[0]-- <= 0) return;
        if (payload instanceof CharSequence text) {
            if (payload instanceof Spanned spanned) {
                URLSpan[] spans = spanned.getSpans(0, spanned.length(), URLSpan.class);
                for (int i = 0; i < Math.min(spans.length, MAX_METADATA_NODES); i++)
                    captureText(spans[i].getURL());
            }
            captureText(text.toString());
            return;
        }
        if (!visited.add(payload)) return;
        if (payload instanceof Uri uri) {
            captureText(uri.toString());
        } else if (payload instanceof Intent capturedIntent) {
            capturePayload(capturedIntent.getData(), visited, budget, depth + 1);
            capturePayload(capturedIntent.getExtras(), visited, budget, depth + 1);
        } else if (payload instanceof Bundle bundle) {
            for (String key : bundle.keySet()) {
                if (budget[0] <= 0) break;
                // An image thumbnail URL is not the shared card's landing page.
                if (!imageMetadata(key)) {
                    try { capturePayload(bundle.get(key), visited, budget, depth + 1); }
                    catch (RuntimeException ignored) { }
                }
            }
        } else if (payload instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (budget[0] <= 0) break;
                if (!imageMetadata(String.valueOf(entry.getKey())))
                    capturePayload(entry.getValue(), visited, budget, depth + 1);
            }
        } else if (payload instanceof Iterable<?> values) {
            for (Object value : values) {
                if (budget[0] <= 0) break;
                capturePayload(value, visited, budget, depth + 1);
            }
        } else if (payload.getClass().isArray() && !payload.getClass().getComponentType().isPrimitive()) {
            int size = Math.min(Array.getLength(payload), MAX_METADATA_NODES);
            for (int i = 0; i < size && budget[0] > 0; i++)
                capturePayload(Array.get(payload, i), visited, budget, depth + 1);
        }
    }

    private static boolean imageMetadata(String key) {
        if (key == null) return false;
        return switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "imgurl", "viewbitmap", "thumburl", "thumbnail", "thumbnailurl", "icon", "iconurl",
                    "sourceicon", "coverurl" -> true;
            default -> false;
        };
    }

    /** Returns a new list, leaving the host's cached server result untouched. */
    public ArrayList<Object> merge(Context context, ArrayList<?> originals, String source) {
        ArrayList<Object> result = new ArrayList<>();
        Set<String> existing = new LinkedHashSet<>();
        if (originals != null) for (Object info : originals) {
            if (modelClass.isInstance(info)) {
                String url = read(detailUrl, info);
                if (url != null) existing.addAll(LinkExtractor.extract(url));
            }
        }
        if (context != null) for (String url : capturedLinks()) {
            if (existing.contains(url)) continue;
            Preview resolved = resolve(context, url, null, source, true);
            if (resolved == null) continue;
            try {
                result.add(newModel(url, resolved.target(), resolved.label()));
                existing.add(url);
            } catch (ReflectiveOperationException ignored) { }
        }
        if (originals != null) for (Object original : originals) {
            if (HostCaptureAction.isAction(original)) continue;
            result.add(original);
        }
        return result;
    }

    Object newModel(String url, String target, String label) throws ReflectiveOperationException {
        if (!HostPolicy.validTarget(target) || label == null || label.isBlank()
                || !LinkExtractor.extract(url).contains(url)) throw new IllegalArgumentException("Invalid card preview");
        Object model = modelConstructor.newInstance();
        title.set(model, "在" + label + "打开");
        category.set(model, "URL");
        detailUrl.set(model, url);
        packageName.set(model, target);
        // The host builds its stock fallback; the bridge rule decides the actual destination on click.
        intent.set(model, null);
        return model;
    }

    /** Run before bindRecommendInfo/bindNerInfo/bindDeviceInfo so recycled cards lose their old icon. */
    public void beforeBind(Object card) {
        Object icon = value(card, "mRecommendIcon");
        if (icon != null) {
            boundIcons.remove(icon);
            boundTargets.remove(icon);
        }
    }

    public void bindRecommendation(Object card, Object info, String source) {
        diagnostic("bind_recommend", "reached", null, 0, capturedLinks().size(), null, null);
        beforeBind(card);
        if (HostCaptureAction.isAction(info)) { HostCaptureAction.bind(card); return; }
        if (!(card instanceof View view) || !modelClass.isInstance(info)
                || HostPolicy.isTranslation(read(category, info))) {
            diagnostic("bind_recommend", "skipped", null, 0, 0, null, null);
            return;
        }
        Intent candidate = nativeCandidate(info);
        String rawIntent = read(intent, info);
        if (rawIntent != null && !rawIntent.isEmpty() && candidate == null) {
            diagnostic("bind_recommend", "unsupported", null, 0, 0, null, null);
            return;
        }
        Preview resolved = resolve(view.getContext(), read(detailUrl, info), candidate, source);
        if (resolved != null) applyCard(card, resolved);
        diagnostic("bind_recommend", resolved == null ? "none" : "success",
                resolved == null ? null : resolved.target(), 0, 0, null, null);
    }

    public void bindNer(Object card, Object entity, String ignoredSource) {
        diagnostic("bind_ner", "reached", null, 0, capturedLinks().size(), null, null);
        beforeBind(card);
        if (!(card instanceof View view) || entity == null) {
            diagnostic("bind_ner", "skipped", null, 0, 0, null, null);
            return;
        }
        try {
            Method type = entity.getClass().getMethod("getEntityClass");
            if (!"URL".equals(type.invoke(entity))) {
                diagnostic("bind_ner", "unsupported", null, 0, 0, null, null);
                return;
            }
            String url = (String) entity.getClass().getMethod("getEntityStr").invoke(entity);
            List<String> extracted = LinkExtractor.extract(url);
            if (extracted.isEmpty()) {
                diagnostic("bind_ner", "empty", null, 0, 0, null, null);
                return;
            }
            Preview resolved = resolve(view.getContext(), extracted.get(0), null, null);
            if (resolved != null) applyCard(card, resolved);
            diagnostic("bind_ner", resolved == null ? "none" : "success",
                    resolved == null ? null : resolved.target(), extracted.size(), 0, null, null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            diagnostic("bind_ner", "failed", null, 0, 0, ignored.getClass(), null);
        }
    }

    /** Used by a narrow SourceIcon hook to prevent late stock async jobs restoring the browser icon. */
    public Drawable iconFor(Object sourceIcon) { return boundIcons.get(sourceIcon); }

    public void iconDisplayed(Object sourceIcon) {
        String target = boundTargets.get(sourceIcon);
        if (target != null) diagnostic("icon_guard", "icon_displayed", target, 0, 0, null, null);
    }

    private Intent nativeCandidate(Object info) {
        String raw = read(intent, info);
        if (raw == null || raw.isEmpty()) {
            String target = read(packageName, info);
            String url = read(detailUrl, info);
            // Mirror the clicked builder's launcher recovery when the stock host has no deep link.
            if (HostPolicy.validTarget(target) && (HostPolicy.hostUsesLauncher(target)
                    || url == null || url.isEmpty())) return new Intent(Intent.ACTION_MAIN)
                            .setPackage(target).addCategory(Intent.CATEGORY_LAUNCHER);
            return null;
        }
        if (raw.length() > 32768) return null;
        try {
            Intent candidate = Intent.parseUri(raw, 0);
            if (!Intent.ACTION_VIEW.equals(candidate.getAction()) || candidate.getSelector() != null
                    || candidate.getClipData() != null) return null;
            String target = read(packageName, info);
            if (candidate.getPackage() == null && candidate.getComponent() == null
                    && HostPolicy.validTarget(target)) candidate.setPackage(target);
            return candidate;
        } catch (java.net.URISyntaxException ignored) { return null; }
    }

    private Preview resolve(Context context, String url, Intent candidate, String source) {
        return resolve(context, url, candidate, source, false);
    }

    private Preview resolve(Context context, String url, Intent candidate, String source, boolean local) {
        if (context == null || (url == null && candidate == null)) {
            diagnostic("resolve", "skipped", null, 0, 0, null, "invalid_request");
            return null;
        }
        long startedNanos = System.nanoTime();
        // Native-only recommendations include the candidate in the key; no content is written to logs.
        String key = local + "\n" + String.valueOf(source) + '\n' + String.valueOf(url) + '\n'
                + (candidate == null ? "" : candidate.toUri(0));
        long now = startedNanos;
        CachedPreview cachedPreview = null;
        synchronized (previews) {
            CachedPreview cached = previews.get(key);
            if (cached != null && now - cached.at() >= 0 && now - cached.at() < PREVIEW_TTL_NANOS)
                cachedPreview = cached;
        }
        if (cachedPreview != null) {
            performance("resolve", startedNanos, "cache_hit");
            return cachedPreview.preview();
        }
        Preview resolved = null;
        String perfOutcome = "none";
        try {
            Bundle request = new Bundle();
            request.putString("url", url);
            request.putString("source", source);
            request.putBoolean("local_recognition", local);
            request.putParcelable("candidate", candidate);
            Bundle response = io.github.yudigaga.taplusbridge.runtime.BridgeTransport.call(context,"resolve",request);
            String target = response == null ? null : response.getString("target");
            String label = response == null ? null : response.getString("title");
            if (HostPolicy.validTarget(target) && label != null && !label.isBlank() && label.length() <= 256) {
                @SuppressWarnings("deprecation") Bitmap icon = response.getParcelable("icon");
                resolved = new Preview(target, label, icon);
                perfOutcome = "success";
                diagnostic("resolve", "success", target, 0, icon == null ? 0 : 1, null, null);
            } else {
                String reason = response == null ? "empty_response" : response.getString("reason", "unknown");
                diagnostic("resolve", "none", null, 0, 0, null, reason);
            }
        } catch (RuntimeException ignored) {
            perfOutcome = "failed";
            diagnostic("resolve", "failed", null, 0, 0, ignored.getClass(), "exception");
        }
        synchronized (previews) {
            if (previews.size() >= 32) previews.clear();
            previews.put(key, new CachedPreview(now, resolved));
        }
        performance("resolve", startedNanos, perfOutcome);
        return resolved;
    }

    private void applyCard(Object card, Preview preview) {
        long startedNanos = System.nanoTime();
        try {
            Object titleView = value(card, "mRecommendTitle");
            Object subtitleView = value(card, "mRecommendAbstract");
            Object sourceIcon = value(card, "mRecommendIcon");
            diagnostic("apply_card", titleView instanceof TextView && subtitleView instanceof TextView
                    && sourceIcon != null ? "reached" : "fields_missing", preview.target(), 0, 0, null, null);
            if (titleView instanceof TextView text) text.setText("在" + preview.label() + "打开");
            if (subtitleView instanceof TextView text) {
                text.setText(preview.label());
                text.setVisibility(View.VISIBLE);
            }
            if (!(card instanceof View view) || sourceIcon == null) {
                diagnostic("icon", "icon_failed", preview.target(), 0, 0, null, null);
                return;
            }
            Drawable drawable = preview.icon() == null ? null
                    : new BitmapDrawable(view.getResources(), preview.icon());
            if (drawable == null) {
                try { drawable = view.getContext().getPackageManager().getApplicationIcon(preview.target()); }
                catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                    diagnostic("icon", "icon_failed", preview.target(), 0, 0, ignored.getClass(), null);
                }
            }
            if (drawable != null) {
                boundIcons.put(sourceIcon, drawable);
                boundTargets.put(sourceIcon, preview.target());
                diagnostic("icon", "icon_requested", preview.target(), 0, 0, sourceIcon.getClass(), null);
                sourceIcon.getClass().getMethod("setIcon", Drawable.class).invoke(sourceIcon, drawable);
            } else {
                diagnostic("icon", "icon_failed", preview.target(), 0, 0, null, null);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            diagnostic("icon", "icon_failed", preview.target(), 0, 0, ignored.getClass(), null);
        } finally {
            performance("apply_card", startedNanos, "success");
        }
    }

    static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Field stringField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = findField(type, name);
        if (field.getType() != String.class) throw new NoSuchFieldException("Unexpected model field");
        return field;
    }

    private static Object value(Object object, String field) {
        if (object == null) return null;
        try { return findField(object.getClass(), field).get(object); }
        catch (ReflectiveOperationException ignored) { return null; }
    }

    private static String read(Field field, Object object) {
        try { return (String) field.get(object); }
        catch (IllegalAccessException | IllegalArgumentException ignored) { return null; }
    }

    private record Preview(String target, String label, Bitmap icon) { }
    private record CachedPreview(long at, Preview preview) { }
}
