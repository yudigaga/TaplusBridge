package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Toast;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import io.github.yudigaga.taplusbridge.drag.RainbowDragView;
import io.github.yudigaga.taplusbridge.runtime.BridgeTransport;
import io.github.yudigaga.taplusbridge.runtime.RuntimeClient;
import io.github.yudigaga.taplusbridge.probe.ProbeContract;
import io.github.yudigaga.taplusbridge.recognition.LinkExtractor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prepares one drag from native picks or a system gesture while original native picks continue. */
final class HostLongPressDrag {
    interface Delivery { void accept(Context context, Object service, String token, Object nativeResult); }
    private static final long TIMEOUT = 30_000;
    private static final String NUMBER = "(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?";
    private static final Pattern COORDINATE = Pattern.compile("(" + NUMBER + ")-(" + NUMBER + ")");
    private final XposedModule module;
    private final Delivery delivery;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TaplusBridge-long-press-drag"); t.setDaemon(true); return t;
    });
    private volatile Held current;
    private volatile StageDeadline lastStageDeadline;

    static final class StageDeadline {
        private final WeakReference<Object> service, result;
        private final long recognitionAt, expiresAt;
        StageDeadline(Object service, Object result, long recognitionAt, long expiresAt) {
            this.service = new WeakReference<>(service); this.result = new WeakReference<>(result);
            this.recognitionAt = recognitionAt; this.expiresAt = expiresAt;
        }
        long forPick(Object expectedService, Object expectedResult, long now) {
            return now <= expiresAt && service.get() == expectedService && result.get() == expectedResult
                    ? recognitionAt : 0;
        }
    }

    private static final class Held {
        final long began;
        final Context context;
        final Object service;
        Object nativePick;
        final long recognitionAt;
        Object result;
        String text;
        Bitmap image;
        final String source, token, identity;
        RainbowDragView preview;
        WindowManager windows;
        WindowManager.LayoutParams layout;
        boolean previewFailed;
        boolean maskActive;
        boolean transferPending;
        volatile boolean overlayRequested;
        volatile boolean imported;
        volatile boolean linkGraceUsed;
        volatile long linkWaitUntil;
        volatile boolean dataReady;
        volatile boolean systemLinkRequested;
        boolean canonicalPayload;
        boolean nativeProbeAvailable;
        List<String> recognizedLinks = List.of();
        Held(Context context, Object service, Object result, String text, Bitmap image, Lease lease) {
            this.began = SystemClock.elapsedRealtime(); this.context = context;
            this.service = service; this.nativePick = result; this.result = result; this.text = text; this.image = image;
            this.recognitionAt = lease.recognitionAt();
            source = lease.source; token = lease.token; identity = lease.identity;
            canonicalPayload = result == null;
            nativeProbeAvailable = nativeProbeGeometry(result);
        }
    }

    record Lease(String token, String source, String identity, String state, float x, float y,
                 float originX, float originY, String direction, String action,
                 float width, float height, float density, Map<String, String> actions, boolean dragging,
                 String cardLink, long nativeAt, long recognitionAt, long dragAt, boolean linkProbe) {
        boolean recognitionReady(long now) { return now >= recognitionAt; }
        boolean dragReady(long now) { return now >= dragAt; }
    }

    HostLongPressDrag(XposedModule module, ClassLoader loader, Delivery delivery) {
        this.module = module; this.delivery = Objects.requireNonNull(delivery);
    }

    boolean replaying() { return false; }

    /** A system-owned gesture can prepare the host even when ContentCatcher never produces a pick. */
    void prepare(Context context, Object service, String token) {
        if (context == null || service == null || !validToken(token)) return;
        worker.execute(() -> requestPrepared(context, service, token, SystemClock.elapsedRealtime()));
    }

    private void requestPrepared(Context context, Object service, String token, long started) {
        Held held = current;
        if (held != null && held.token.equals(token)
                && (held.image != null || (held.text != null && !held.text.isBlank()))) return;
        Bundle request = new Bundle(); request.putString("token", token);
        Bundle response = RuntimeClient.call(context, "drag_gesture_prepare", request);
        if (response == null) {
            if (SystemClock.elapsedRealtime() - started < 5_000)
                worker.schedule(() -> requestPrepared(context, service, token, started), 100, TimeUnit.MILLISECONDS);
            return;
        }
        acceptPrepared(context, service, response, token);
    }

    synchronized boolean acceptPrepared(Context context, Object service, Bundle response, String expectedToken) {
        Bitmap image = null;
        Bundle capture = null;
        try {
            if (context == null || service == null || response == null || !validToken(expectedToken)) return false;
            Lease lease = lease(response, response.getString("source"), expectedToken, null);
            if (lease == null || !"holding".equals(lease.state())
                    || !lease.recognitionReady(SystemClock.elapsedRealtime())) return false;
            capture = response.getBundle("capture");
            ScopeCapturePayload.Metadata metadata = ScopeCapturePayload.validate(capture, SystemClock.elapsedRealtime());
            if (!lease.source().equals(metadata.source()) || lease.originX() != metadata.x()
                    || lease.originY() != metadata.y() || lease.width() != metadata.width()
                    || lease.height() != metadata.height()) return false;
            if ("image".equals(metadata.kind())) {
                if (!(capture.get("fd") instanceof ParcelFileDescriptor descriptor)) return false;
                try (var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                    image = ScopeCapturePayload.decode(ScopeCapturePayload.read(input, metadata.bytes()));
                }
            } else if (!"resume".equals(metadata.kind())) return false;
            if (current != null && current.token.equals(lease.token())) {
                applySourceText(current, response);
                if (current.image == null && image != null) {
                    current.image = image; image = null; current.canonicalPayload = true;
                }
                return true;
            }
            Held prepared = new Held(context, service, null, null, image, lease);
            applySourceText(prepared, response);
            Held previous = current; current = prepared; image = null;
            if (previous != null) release(previous);
            event("prepared", null);
            worker.execute(() -> process(prepared, lease));
            return true;
        } catch (IOException | RuntimeException invalid) {
            event("prepare_failed", invalid.getClass().getSimpleName()); return false;
        } finally {
            if (image != null && !image.isRecycled()) image.recycle();
            if (capture != null && capture.get("fd") instanceof ParcelFileDescriptor descriptor)
                try { descriptor.close(); } catch (IOException ignored) { }
        }
    }

    /** The boolean is intentionally always false: callers must proceed with the native pick. */
    synchronized boolean hold(Context context, Object service, Object result, boolean image) {
        return hold(context, service, result, image, null);
    }

    synchronized boolean hold(Context context, Object service, Object result, boolean image, Bitmap resolved) {
        if (context == null || result == null) return false;
        Bitmap owned = null;
        try {
            String source = source(result);
            PointF point = point(result);
            if (source == null || control(result)) return false;
            if (point == null) { event("content_skipped", "missing_geometry"); return false; }
            Bundle request = new Bundle(); request.putString("source", source);
            request.putFloat("x", point.x); request.putFloat("y", point.y);
            request.putLong("native_elapsed", SystemClock.elapsedRealtime());
            Lease lease = lease(RuntimeClient.call(context, "drag_gesture_claim", request), source, null, null);
            if (lease == null || "cancelled".equals(lease.state))
                return false;
            lastStageDeadline = new StageDeadline(service, result, lease.recognitionAt(),
                    SystemClock.elapsedRealtime() + TIMEOUT);
            if (current != null && current.token.equals(lease.token)) {
                adoptNativeContent(current, result, resolved);
                return false;
            }
            RecognitionChannels channels = RecognitionPolicy.read(context);
            String text = withRecognizedLinks(RecognitionPolicy.nativeText(result, channels),
                    HostRecognitionEnhancer.catcherLinks(result, channels));
            Bitmap bitmap = nativeBitmap(result);
            if (bitmap == null && resolved != null && !resolved.isRecycled()) bitmap = resolved;
            if (bitmap != null) owned = snapshot(bitmap);
            Held held = new Held(context, service, result, text, owned, lease);
            if (!channels.enabled(Channel.NATIVE)) held.canonicalPayload = true;
            if (owned == null && (text == null || text.isBlank())) held.result = null;
            Held previous = current;
            current = held;
            if (previous != null) release(previous);
            owned = null;
            event("claimed", null);
            worker.execute(() -> process(held, lease));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            event("claim_failed", unavailable.getClass().getSimpleName());
        } catch (OutOfMemoryError unavailable) {
            event("claim_failed", "OutOfMemoryError");
        } finally { if (owned != null) owned.recycle(); }
        return false;
    }

    synchronized void close(Object service) {
        Held held = current;
        if (held == null || held.service != service) return;
        current = null; release(held);
    }

    long recognitionDeadline(Object service, Object result) {
        Held held = current;
        if (held != null && held.service == service && (held.nativePick == result || held.result == result))
            return held.recognitionAt;
        StageDeadline saved = lastStageDeadline;
        return saved == null ? 0 : saved.forPick(service, result, SystemClock.elapsedRealtime());
    }

    private void process(Held held, Lease lease) {
        if (!owns(held)) return;
        if (lease == null || "cancelled".equals(lease.state)) { discard(held); return; }
        if (!held.dataReady) {
            long now = SystemClock.elapsedRealtime();
            if (!lease.dragReady(now)) {
                if ("released".equals(lease.state)) { discard(held); return; }
                worker.schedule(() -> poll(held), Math.max(1, Math.min(40, lease.dragAt() - now)), TimeUnit.MILLISECONDS);
                return;
            }
            held.dataReady = true;
        }
        if ("released".equals(lease.state)) {
            if (lease.direction == null || "none".equals(lease.action)) discard(held);
            else {
                held.text = withCardLink(held.text, lease.cardLink());
                if ((held.nativeProbeAvailable || lease.linkProbe()) && needsCatcherLink(lease.action(), held.text)) {
                    if (held.linkWaitUntil == 0) held.linkWaitUntil = linkWaitDeadline(SystemClock.elapsedRealtime());
                    if (!held.nativeProbeAvailable && lease.linkProbe() && !held.systemLinkRequested) {
                        held.systemLinkRequested = true;
                        main.post(() -> {
                            if (!owns(held)) return;
                            removePreview(held);
                            worker.execute(() -> requestPreparedLink(held));
                        });
                    }
                    if (waitingForCardLink(lease.action(), held.text, held.linkWaitUntil,
                            SystemClock.elapsedRealtime())) {
                        worker.schedule(() -> poll(held), 40, TimeUnit.MILLISECONDS);
                        return;
                    }
                }
                importContent(held, lease);
            }
            return;
        }
        if (SystemClock.elapsedRealtime() - held.began >= TIMEOUT) {
            event("timeout", null); discard(held); return;
        }
        if (!shouldRequestOverlay(lease, held.overlayRequested, SystemClock.elapsedRealtime())) {
            worker.schedule(() -> poll(held), 40, TimeUnit.MILLISECONDS);
            return;
        }
        held.overlayRequested = true;
        event("overlay_requested", null);
        main.post(() -> preview(held, lease));
        worker.schedule(() -> poll(held), 40, TimeUnit.MILLISECONDS);
    }

    /** Arm the mask at the cumulative drag deadline so it can receive the held touch stream. */
    static boolean revealEdges(Lease lease, boolean alreadyShown, long now) {
        return lease != null && (alreadyShown || lease.dragReady(now));
    }

    static boolean shouldRequestOverlay(Lease lease, boolean alreadyRequested, long now) {
        return !alreadyRequested && revealEdges(lease, false, now);
    }

    static boolean canFinishReleasedGesture(Lease lease) {
        return lease != null && "released".equals(lease.state) && lease.direction() != null
                && lease.action() != null && !"none".equals(lease.action());
    }

    /** A title-only callback is not the card link. Rules and browser leave the catcher alone until one arrives. */
    static boolean needsCatcherLink(String action, String text) {
        if ("none".equals(action) || "lens".equals(action)) return false;
        return LinkExtractor.extract(text == null ? "" : text).isEmpty();
    }

    static boolean waitForCardLink(String action, String text, boolean waited) {
        return !waited && needsCatcherLink(action, text);
    }

    static long linkWaitDeadline(long startedAt) {
        if (startedAt < 0) return 0;
        try { return Math.addExact(startedAt, ProbeContract.LINK_RESULT_GRACE_MILLIS); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    static boolean waitingForCardLink(String action, String text, long waitUntil, long now) {
        return now >= 0 && waitUntil > now && needsCatcherLink(action, text);
    }

    static boolean adoptRicherText(String current, String next) {
        if (next == null || next.isBlank()) return false;
        return LinkExtractor.extract(current == null ? "" : current).isEmpty()
                && !LinkExtractor.extract(next).isEmpty();
    }

    static String withCardLink(String text, String cardLink) {
        if (!adoptRicherText(text, cardLink)) return text;
        return text == null || text.isBlank() ? cardLink : text + "\n" + cardLink;
    }

    static String withRecognizedLinks(String text, List<String> links) {
        if (links == null || links.isEmpty()) return text;
        String value = text == null ? "" : text;
        LinkedHashSet<String> existing = new LinkedHashSet<>(LinkExtractor.extract(value));
        StringBuilder result = new StringBuilder(value);
        for (String link : links) {
            if (!IntentObservation.validHttpUrl(link) || !existing.add(link)
                    || result.length() + link.length() + 1 > LinkExtractor.MAX_INPUT_LENGTH) continue;
            if (result.length() > 0) result.append('\n');
            result.append(link);
        }
        return result.length() == 0 ? text : result.toString();
    }

    synchronized void offerRecognizedLinks(Object service, Object result, List<String> links) {
        Held held = current;
        if (held == null || held.imported || held.service != service
                || (held.nativePick != result && held.result != result) || links == null) return;
        LinkedHashSet<String> checked = new LinkedHashSet<>();
        for (String link : links) {
            if (IntentObservation.validHttpUrl(link)) checked.add(link);
            if (checked.size() == LinkExtractor.MAX_RESULTS) break;
        }
        if (checked.isEmpty()) return;
        held.recognizedLinks = List.copyOf(checked);
        held.text = withRecognizedLinks(held.text, held.recognizedLinks);
        if (held.preview != null) held.preview.updateCaption(held.text);
        event("links_attached", null);
    }

    boolean dismissPreviewForLinkProbe(Object service, Object result, String source) {
        if (Looper.myLooper() != Looper.getMainLooper()) return false;
        Held held = current;
        if (held == null) return true;
        if (held.service != service || (held.nativePick != result && held.result != result)
                || !held.source.equals(source)) return false;
        RainbowDragView preview = held.preview;
        if (preview == null) return !held.maskActive && !held.transferPending;
        WindowManager windows = held.windows;
        if (preview.isAttachedToWindow()) {
            if (windows == null) return false;
            try { windows.removeViewImmediate(preview); }
            catch (RuntimeException unavailable) {
                if (preview.isAttachedToWindow()) return false;
            }
        }
        if (preview.isAttachedToWindow()) return false;
        held.maskActive = false; held.transferPending = false;
        preview.dispose();
        held.preview = null; held.windows = null; held.layout = null;
        event("overlay_hidden_for_link_probe", null);
        return true;
    }

    static void prepareMaskForTransfer(WindowManager.LayoutParams layout) {
        layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }

    private synchronized void adoptNativeContent(Held held, Object result, Bitmap resolved) {
        if (held.imported) return;
        try {
            RecognitionChannels channels = RecognitionPolicy.read(held.context);
            String nextText = withRecognizedLinks(RecognitionPolicy.nativeText(result, channels),
                    HostRecognitionEnhancer.catcherLinks(result, channels));
            if (!channels.enabled(Channel.NATIVE)) held.canonicalPayload = true;
            Bitmap bitmap = nativeBitmap(result);
            if (bitmap == null && resolved != null && !resolved.isRecycled()) bitmap = resolved;
            // Preserve the owned image after preparation; late image callbacks can fill an empty slot.
            if (held.image == null && bitmap != null) held.image = snapshot(bitmap);
            if (nextText != null && !nextText.isBlank()) {
                if (held.text == null || held.text.isBlank()) held.text = nextText;
                else held.text = withRecognizedLinks(held.text, LinkExtractor.extract(nextText));
            }
            if (held.nativePick == null) held.nativePick = result;
            held.nativeProbeAvailable = nativeProbeGeometry(result);
            if ((nextText != null && !nextText.isBlank()) || bitmap != null) {
                if (held.result != null && held.result != result) held.canonicalPayload = true;
                held.result = result;
            }
            if (held.preview != null) held.preview.updateCaption(held.text);
            event("content_upgraded", null);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            event("content_upgrade_failed", unavailable.getClass().getSimpleName());
        }
    }

    private void requestPreparedLink(Held held) {
        if (!owns(held) || !needsCatcherLink("portal", held.text)
                || SystemClock.elapsedRealtime() >= held.linkWaitUntil) return;
        Bundle request = new Bundle(); request.putString("token", held.token);
        Bundle response = RuntimeClient.call(held.context, "drag_gesture_link", request);
        if (response == null || !response.getBoolean("accepted"))
            worker.schedule(() -> requestPreparedLink(held), 40, TimeUnit.MILLISECONDS);
    }

    private void poll(Held held) {
        if (!owns(held)) return;
        Bundle request = new Bundle(); request.putString("token", held.token);
        Bundle response = RuntimeClient.call(held.context, "drag_gesture_poll", request);
        if (response == null) {
            if (SystemClock.elapsedRealtime() - held.began >= TIMEOUT) discard(held);
            else worker.schedule(() -> poll(held), 40, TimeUnit.MILLISECONDS);
        } else {
            Lease checked = lease(response, held.source, held.token, held.identity);
            if (checked != null) applySourceText(held, response);
            process(held, checked);
        }
    }

    private synchronized void applySourceText(Held held, Bundle response) {
        if (held.imported || response == null || !(response.get("source_text") instanceof String next)
                || !RecognitionPolicy.read(held.context).enabled(Channel.ASSIST)
                || !ScopeCapturePayload.validContent(next) || Objects.equals(held.text, next)) return;
        if (held.text == null || held.text.isBlank()) held.text = next;
        else {
            String merged = withRecognizedLinks(held.text, LinkExtractor.extract(next));
            if (!merged.contains(next)
                    && merged.length() + next.length() + 1 <= ScopeCapturePayload.MAX_CONTENT_LENGTH)
                merged += "\n" + next;
            held.text = merged;
        }
        held.canonicalPayload = true;
        if (held.preview != null) held.preview.updateCaption(held.text);
    }

    private boolean owns(Held held) { return current == held; }

    private synchronized boolean finish(Held held) {
        if (!owns(held)) return false;
        current = null;
        release(held);
        return true;
    }

    private void discard(Held held) { if (finish(held)) event("cancelled", null); }

    private void release(Held held) {
        main.post(() -> removePreview(held));
        // The same worker serializes encoding and disposal, so native cancellation cannot recycle an in-use snapshot.
        worker.execute(() -> { if (held.image != null && !held.image.isRecycled()) held.image.recycle(); });
    }

    private void importContent(Held held, Lease lease) {
        File image = null;
        try {
            if (!owns(held) || held.imported) return;
            held.imported = true;
            String text = held.text;
            if (held.image != null) image = writeImage(held.context, held.image);
            if (image == null && (text == null || text.isBlank())) throw new IOException("empty_content");
            long retryUntil = SystemClock.elapsedRealtime() + 1_000;
            while (owns(held)) {
                Bundle request = new Bundle(); request.putString("lease", held.token);
                request.putString("source", held.source); request.putString("direction", lease.direction);
                request.putString("text", text == null ? "" : text);
                Bundle response;
                try (ParcelFileDescriptor fd = image == null ? null
                        : ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    if (fd != null) request.putParcelable("fd", fd);
                    response = BridgeTransport.call(held.context, "drag_import", request);
                }
                String token = response == null ? null : response.getString("token");
                if (validToken(token)) {
                    main.post(() -> {
                        synchronized (this) {
                            if (!owns(held)) return;
                            Bundle check = new Bundle(); check.putString("token", held.token);
                            Lease latest = lease(RuntimeClient.call(held.context, "drag_gesture_poll", check),
                                    held.source, held.token, held.identity);
                            if (latest == null || !"released".equals(latest.state)
                                    || !lease.direction.equals(latest.direction) || !lease.action.equals(latest.action)) {
                                discard(held); return;
                            }
                            if (finish(held)) try {
                                delivery.accept(held.context, held.service, token, held.canonicalPayload ? null : held.result);
                            }
                            catch (RuntimeException unavailable) { event("delivery_failed", unavailable.getClass().getSimpleName()); }
                        }
                    });
                    event("imported", null); return;
                }
                if (response == null || !"runtime_unavailable".equals(response.getString("reason"))
                        || SystemClock.elapsedRealtime() >= retryUntil) throw new IOException("import_unavailable");
                // A short Binder reconnect wait stays off the host UI thread.
                Thread.sleep(40);
            }
        } catch (Exception unavailable) {
            if (finish(held)) {
                event("import_failed", unavailable.getClass().getSimpleName());
                main.post(() -> {
                    try { Toast.makeText(held.context, "拖拽操作未完成，请重新长按", Toast.LENGTH_SHORT).show(); }
                    catch (RuntimeException ignored) { }
                });
            }
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
        } catch (OutOfMemoryError unavailable) {
            if (finish(held)) event("import_failed", "OutOfMemoryError");
        } finally { if (image != null && !image.delete()) image.deleteOnExit(); }
    }

    @android.annotation.SuppressLint("RtlHardcoded")
    private void preview(Held held, Lease lease) {
        if (!owns(held) || held.previewFailed) { removePreview(held); return; }
        try {
            if (held.preview == null) {
                held.windows = held.context.getSystemService(WindowManager.class);
                if (held.windows == null) throw new IllegalStateException("window");
                RainbowDragView view = new RainbowDragView(held.context, lease.width, lease.height, lease.density,
                        lease.originX, lease.originY, lease.actions, held.text, held.image);
                view.setOnTouchListener((mask, event) -> { maskTouch(held, event); return true; });
                int edge = Math.max(1, Math.round(200 * lease.density));
                int width = Math.round(lease.width), height = Math.round(lease.height);
                if (android.os.Build.VERSION.SDK_INT >= 29)
                    view.setSystemGestureExclusionRects(List.of(new Rect(0, 0, edge, height),
                            new Rect(Math.max(0, width - edge), 0, width, height), new Rect(0, 0, width, edge),
                            new Rect(0, Math.max(0, height - edge), width, height)));
                held.layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
                held.layout.gravity = Gravity.TOP | Gravity.LEFT;
                if (android.os.Build.VERSION.SDK_INT >= 30) held.layout.setFitInsetsTypes(0);
                held.layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                held.layout.setTitle(DragSettings.MASK_WINDOW_PREFIX + held.token);
                // Keep the mask out of the way until system_server is ready to transfer the active stream.
                held.layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                held.preview = view; held.transferPending = true; updatePreview(held, lease);
                held.windows.addView(view, held.layout);
                awaitMaskFrame(held, view, () -> armMask(held, view));
            } else {
                updatePreview(held, lease);
            }
        } catch (RuntimeException unavailable) {
            held.previewFailed = true; eventReason("overlay_attach_failed", "host_window_attach_failed");
            removePreview(held); discard(held);
        }
    }

    private void awaitMaskFrame(Held held, RainbowDragView mask, Runnable afterFrame) {
        if (!owns(held) || held.preview != mask || !held.transferPending) return;
        if (!mask.isAttachedToWindow()) {
            main.postDelayed(() -> awaitMaskFrame(held, mask, afterFrame), 16); return;
        }
        ViewTreeObserver observer = mask.getViewTreeObserver();
        if (!observer.isAlive()) {
            main.postDelayed(() -> awaitMaskFrame(held, mask, afterFrame), 16); return;
        }
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                if (owns(held) && held.preview == mask && held.transferPending) afterFrame.run();
                return true;
            }
        });
        mask.invalidate();
    }

    private void armMask(Held held, RainbowDragView mask) {
        if (!owns(held) || held.preview != mask || !held.transferPending) return;
        try {
            prepareMaskForTransfer(held.layout);
            held.windows.updateViewLayout(mask, held.layout);
        } catch (RuntimeException unavailable) {
            failTransfer(held, "host_window_update_failed"); return;
        }
        // updateViewLayout schedules a ViewRoot traversal; WMS may still expose the old flags here.
        awaitMaskFrame(held, mask, () -> requestTouchHandoff(held, mask));
    }

    private void requestTouchHandoff(Held held, RainbowDragView mask) {
        if (!owns(held) || held.preview != mask || !held.transferPending) return;
        Bundle request = new Bundle(); request.putString("token", held.token);
        Bundle ready;
        try {
            ready = RuntimeClient.call(held.context, "drag_gesture_overlay_ready", request);
        } catch (RuntimeException unavailable) {
            failTransfer(held, "runtime_unavailable"); return;
        }
        if (ready == null) { failTransfer(held, "runtime_unavailable"); return; }
        if (!ready.getBoolean("accepted")) {
            Bundle latest = RuntimeClient.call(held.context, "drag_gesture_poll", request);
            Lease released = lease(latest, held.source, held.token, held.identity);
            if (owns(held) && canFinishReleasedGesture(released)) {
                held.transferPending = false;
                removePreview(held);
                event("overlay_skipped_after_release", null);
                worker.execute(() -> process(held, released));
                return;
            }
            failTransfer(held, "system_transfer_not_accepted"); return;
        }
        held.transferPending = false; held.maskActive = true;
        event("overlay_transferred", null);
    }

    private void failTransfer(Held held, String reason) {
        held.transferPending = false; held.previewFailed = true;
        eventReason("overlay_transfer_failed", reason);
        removePreview(held); discard(held);
    }

    private static void updatePreview(Held held, Lease lease) {
        // Poll responses may predate local MOVE events; only the transferred stream drives visuals and haptics.
        if (held.preview != null && !held.maskActive) held.preview.update(lease.x, lease.y, false);
    }

    private void maskTouch(Held held, MotionEvent event) {
        int action = event.getActionMasked();
        if (!held.maskActive || !owns(held) || action == MotionEvent.ACTION_DOWN) return;
        if (held.preview != null) held.preview.update(event.getRawX(), event.getRawY(),
                event.getPointerCount() == 1 && action != MotionEvent.ACTION_CANCEL);
        Bundle request = new Bundle(); request.putString("token", held.token); request.putInt("action", action);
        request.putInt("pointers", event.getPointerCount()); request.putFloat("x", event.getRawX());
        request.putFloat("y", event.getRawY());
        worker.execute(() -> { if (owns(held)) RuntimeClient.call(held.context, "drag_gesture_update", request); });
    }

    private static void removePreview(Held held) {
        held.maskActive = false; held.transferPending = false;
        if (held.preview != null && held.windows != null) {
            try { held.windows.removeViewImmediate(held.preview); } catch (RuntimeException ignored) { }
        }
        if (held.preview != null) held.preview.dispose();
        held.preview = null; held.windows = null; held.layout = null;
    }

    static Lease lease(Bundle response, String expectedSource, String expectedToken, String expectedIdentity) {
        if (response == null) return null;
        try {
            String token = response.getString("token"), source = response.getString("source"),
                    identity = response.getString("identity"), state = response.getString("state");
            float originX = response.getFloat("origin_x", response.getFloat("x", Float.NaN));
            float originY = response.getFloat("origin_y", response.getFloat("y", Float.NaN));
            float width = response.getFloat("screen_width", Float.NaN), height = response.getFloat("screen_height", Float.NaN),
                    density = response.getFloat("density", Float.NaN);
            long nativeAt = response.getLong("native_at", -1), recognitionAt = response.getLong("recognition_at", -1),
                    dragAt = response.getLong("drag_at", -1), now = SystemClock.elapsedRealtime();
            Map<String, String> actions = new java.util.HashMap<>();
            for (String side : DragSettings.DIRECTIONS) {
                String configured = response.getString("action_" + side);
                if (!DragSettings.ACTIONS.contains(configured)) return null;
                actions.put(side, configured);
            }
            if (!validToken(token) || !Objects.equals(source, expectedSource) || !HostPolicy.validPackage(source)
                    || identity == null || !identity.matches("[A-Za-z0-9_-]{16,128}")
                    || (expectedToken != null && !expectedToken.equals(token))
                    || (expectedIdentity != null && !expectedIdentity.equals(identity))
                    || !java.util.List.of("holding", "released", "cancelled").contains(state)
                    || !(response.get("x") instanceof Float x) || !(response.get("y") instanceof Float y)
                    || !(response.get("native_at") instanceof Long) || !(response.get("recognition_at") instanceof Long)
                    || !(response.get("drag_at") instanceof Long) || nativeAt < 0 || nativeAt > now
                    || now - nativeAt > TIMEOUT + ("released".equals(state)
                        ? ProbeContract.LINK_RESULT_GRACE_MILLIS + 1_000 : 0)
                    || recognitionAt < nativeAt || dragAt < recognitionAt
                    || !validPoint(x, y) || !validPoint(originX, originY)
                    || !Float.isFinite(width) || !Float.isFinite(height) || !Float.isFinite(density)
                    || width <= 0 || height <= 0 || width > 16384 || height > 16384 || density <= 0 || density > 10
                    || originX > width || originY > height) return null;
            String direction = response.getString("direction"), action = response.getString("action");
            if (direction != null && direction.isEmpty()) direction = null;
            if (direction == null) {
                if (action != null && !action.isEmpty() && !"none".equals(action)) return null;
            } else if (!DragSettings.DIRECTIONS.contains(direction) || !DragSettings.ACTIONS.contains(action)) return null;
            return new Lease(token, source, identity, state, x, y, originX, originY, direction, action,
                    width, height, density, Map.copyOf(actions), response.getBoolean("dragging"),
                    response.getString("card_link"), nativeAt, recognitionAt, dragAt, response.getBoolean("link_probe"));
        } catch (RuntimeException invalid) { return null; }
    }

    static boolean validToken(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
    static boolean validPoint(float x, float y) { return Float.isFinite(x) && Float.isFinite(y) && x >= 0 && y >= 0; }

    static String source(Object result) throws ReflectiveOperationException {
        Object token = result.getClass().getMethod("getInjectorToken").invoke(result);
        Object value = token == null ? null : token.getClass().getMethod("getPkgName").invoke(token);
        return value instanceof String source && HostPolicy.validPackage(source) ? source : null;
    }

    static PointF point(Object result) throws ReflectiveOperationException {
        Object raw = result.getClass().getMethod("getPropertyMap").invoke(result);
        if (raw instanceof Map<?, ?> map && map.get("coordinate") instanceof String coordinate && coordinate.length() <= 96) {
            Matcher match = COORDINATE.matcher(coordinate);
            if (match.matches()) try {
                float x = Float.parseFloat(match.group(1)), y = Float.parseFloat(match.group(2));
                if (validPoint(x, y)) return new PointF(x, y);
            } catch (NumberFormatException ignored) { }
        }
        for (String job : new String[]{"text_pick", "image_pick"}) {
            Bundle content = content(result, job);
            if (content == null) continue;
            var points = content.getParcelableArray("touch_points");
            if (points != null && points.length > 0 && points[0] instanceof PointF point && validPoint(point.x, point.y))
                return new PointF(point.x, point.y);
        }
        return null;
    }

    private static Bundle content(Object result, String job) throws ReflectiveOperationException {
        Object params = result.getClass().getMethod("getParams", String.class).invoke(result, job);
        Object content = params instanceof Map<?, ?> map ? map.get("image_pick".equals(job) ? "image_pick_mode" : "pick_mode") : null;
        return content instanceof Bundle bundle ? bundle : null;
    }

    /** A coordinate alone cannot arm the native original-card probe; it also needs a view rectangle. */
    private static boolean nativeProbeGeometry(Object result) {
        if (result == null) return false;
        try {
            for (String job : new String[]{"text_pick", "image_pick"}) {
                Bundle data = content(result, job);
                if (data != null && !data.getBoolean("taplus_scope_capture") && !data.getBoolean("taplus_drag")
                        && data.getParcelable("view_rect") instanceof Rect rect && !rect.isEmpty()) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return false;
    }

    static Bitmap nativeBitmap(Object result) throws ReflectiveOperationException {
        Bundle image = content(result, "image_pick");
        Object value = image == null ? null : image.get("viewBitmap");
        return value instanceof Bitmap bitmap && !bitmap.isRecycled() ? bitmap : null;
    }

    static boolean control(Object result) throws ReflectiveOperationException {
        Object raw = result.getClass().getMethod("getPropertyMap").invoke(result);
        return raw instanceof Map<?, ?> properties && properties.containsKey("observe_control_event");
    }

    static Bitmap snapshot(Bitmap bitmap) {
        if (bitmap.isRecycled()) throw new IllegalArgumentException("recycled_image");
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        double scale = Math.min(1d, Math.min(2000d / Math.max(width, height), Math.sqrt(3_000_000d / ((long) width * height))));
        Bitmap owned = scale < 1 ? Bitmap.createScaledBitmap(bitmap,
                Math.max(1, (int) Math.floor(width * scale)), Math.max(1, (int) Math.floor(height * scale)), true)
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (owned == null || owned == bitmap) throw new IllegalArgumentException("image_copy");
        return owned;
    }

    static File writeImage(Context context, Bitmap bitmap) throws IOException {
        if (bitmap.isRecycled()) throw new IOException("recycled_image");
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        double scale = Math.min(1d, Math.min(2000d / Math.max(width, height), Math.sqrt(3_000_000d / ((long) width * height))));
        Bitmap resized = scale < 1 ? Bitmap.createScaledBitmap(bitmap,
                Math.max(1, (int) Math.floor(width * scale)), Math.max(1, (int) Math.floor(height * scale)), true) : bitmap;
        File file = null;
        boolean complete = false;
        try {
            file = File.createTempFile("taplus-drag-", ".png", context.getCacheDir());
            try (OutputStream output = new LimitedOutput(new FileOutputStream(file), 8 * 1024 * 1024)) {
                if (!resized.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("image_encode");
                output.flush();
            }
            if (file.length() <= 0 || file.length() > 8 * 1024 * 1024) throw new IOException("image_size");
            complete = true; return file;
        } finally {
            if (resized != bitmap) resized.recycle();
            if (!complete && file != null && !file.delete()) file.deleteOnExit();
        }
    }

    static final class LimitedOutput extends OutputStream {
        final OutputStream output; final long limit; long count;
        LimitedOutput(OutputStream output, long limit) { this.output = output; this.limit = limit; }
        @Override public void write(int b) throws IOException { if (count >= limit) throw new IOException("image_size"); output.write(b); count++; }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (length < 0 || count + length > limit) throw new IOException("image_size");
            output.write(data, offset, length); count += length;
        }
        @Override public void flush() throws IOException { output.flush(); }
        @Override public void close() throws IOException { output.close(); }
    }

    private void event(String stage, String failure) {
        if (module == null) return;
        module.log(failure == null ? 4 : 5, "TaplusBridge", "drag gesture host stage=" + stage
                + (failure == null ? "" : " exception=" + failure));
    }

    private void eventReason(String stage, String reason) {
        if (module != null) module.log(5, "TaplusBridge", "drag gesture host stage=" + stage + " reason=" + reason);
    }
}
