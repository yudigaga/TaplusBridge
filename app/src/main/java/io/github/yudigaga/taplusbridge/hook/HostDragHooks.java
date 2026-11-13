package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.runtime.BridgeTransport;
import io.github.yudigaga.taplusbridge.runtime.RuntimeClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Import an explicitly dropped payload, then use only the resulting native panel's actions. */
public final class HostDragHooks {
    public static final String ACTION = "io.github.yudigaga.taplusbridge.DRAG_ACTION";
    private static final String ROOT = "com.miui.contentextension.";
    private final XposedModule module;
    private final DragHostPayload payloads;
    private final RecognitionHooks recognitionHooks;
    private final Field callback, catcherResult, finished, mainView, attachedView, recognition, adapter, translate, recognize, selectAll, segments, splash;
    private final Method deliver, cancel, getItem, switchMode, isLoading, selectedWords, segmentCount, selectedCount,
            naturalWords, refreshImageRecognition, hasRefreshed, showMain, hideSplash;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TaplusBridge-drag"); t.setDaemon(true); return t;
    });
    private final AtomicLong generation = new AtomicLong();
    private volatile Pending pending;
    private final HostLongPressDrag longPress;
    private static volatile HostDragHooks installed;
    private static final ThreadLocal<NativeDelivery> delivering = new ThreadLocal<>();
    private record NativeDelivery(Object service, Object result) { }
    @FunctionalInterface interface DeliveryCall { void run() throws ReflectiveOperationException; }
    private WeakReference<Object> expanded = new WeakReference<>(null);
    private static final class Pending {
        final long generation, deadline, beganAt = SystemClock.elapsedRealtime();
        final Object result;
        final String token, action;
        final boolean recognition, image, preserveNative;
        long recognitionEpoch = -1;
        String recognizedText = "";
        boolean recommendationWaitLogged;
        Pending(long generation, Object result, String token, String action, boolean recognition,
                boolean image, boolean preserveNative, long deadline) {
            this.generation = generation; this.result = result; this.token = token; this.action = action;
            this.recognition = recognition; this.image = image; this.preserveNative = preserveNative; this.deadline = deadline;
        }
    }

    static boolean delivering(Object service, Object result) {
        NativeDelivery active = delivering.get();
        return active != null && active.service == service && active.result == result;
    }

    static void deliverNative(Object service, Object result, DeliveryCall call) throws ReflectiveOperationException {
        NativeDelivery previous = delivering.get();
        delivering.set(new NativeDelivery(java.util.Objects.requireNonNull(service), java.util.Objects.requireNonNull(result)));
        try { call.run(); }
        finally { if (previous == null) delivering.remove(); else delivering.set(previous); }
    }

    private HostDragHooks(XposedModule module, ClassLoader loader, RecognitionHooks recognitionHooks) throws ReflectiveOperationException {
        this.module = module; this.recognitionHooks = recognitionHooks; payloads = new DragHostPayload(loader);
        Class<?> service = loader.loadClass(ROOT + "services.TextContentExtensionService");
        Class<?> listener = loader.loadClass("miui.contentcatcher.sdk.listener.IContentListenerCallback");
        callback = field(service, "mCallback"); catcherResult = field(service, "mCatcherResult");
        finished = field(service, "sIsTaskFinished"); mainView = field(service, "mMainFloatView");
        splash = field(service, "mSplashFloatView"); showMain = method(service, "showMainFloatView", boolean.class);
        hideSplash = method(loader.loadClass(ROOT + "text.floatview.TaplusSplashFloatView"), "hideSplashFloatView", boolean.class);
        attachedView = field(loader.loadClass(ROOT + "text.floatview.BaseFloatView"), "mMainView");
        recognition = field(loader.loadClass(ROOT + "text.floatview.TaplusMainFloatView"), "mRecognitionCard");
        Class<?> card = loader.loadClass(ROOT + "text.cardview.TaplusRecognitionExpandedCard");
        adapter = field(card, "mRecognitionAdapter");
        refreshImageRecognition = method(card, "doRefreshImageRecognition"); hasRefreshed = method(card, "hasRefreshed");
        Class<?> text = loader.loadClass(ROOT + "text.cardview.TaplusRecognitionExpandedTextCard");
        translate = field(text, "mTranslate"); isLoading = method(text, "isLoading");
        selectedWords = method(text, "getSelectedWords");
        selectAll = field(text, "mSelectAll"); segments = field(text, "mSegmentAdapter");
        segmentCount = method(text, "getSegmentCount"); selectedCount = method(text, "getSelectedNumber");
        naturalWords = method(loader.loadClass(ROOT + "text.adapter.TaplusSegmentAdapter"), "getSelectedWords");
        recognize = field(loader.loadClass(ROOT + "text.cardview.TaplusRecognitionExpandedImageCard"), "mRecognizeObject");
        deliver = method(listener, "onContentReceived", loader.loadClass("miui.contentcatcher.sdk.ClientCatcherResult"));
        cancel = method(service, "cancelTask");
        getItem = method(loader.loadClass(ROOT + "text.adapter.TaplusRecognitionAdapter"), "getItem", int.class);
        switchMode = method(card, "doSwitchRecognition", boolean.class);
        longPress = new HostLongPressDrag(module, loader, (context, target, token, nativeResult) -> {
            long epoch = generation.incrementAndGet(); pending = null;
            worker.execute(() -> take(context, target, token, epoch, nativeResult));
        });
    }

    public static void install(XposedModule module, ClassLoader loader, RecognitionHooks recognitionHooks) {
        try { HostDragHooks hooks = new HostDragHooks(module, loader, recognitionHooks); hooks.install(loader); installed = hooks; }
        catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(5, "TaplusBridge", "Drag host unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    static void observeNative(Context context, Object service, Object result, boolean image) {
        HostDragHooks hooks = installed;
        if (hooks != null) hooks.longPress.hold(context, service, result, image);
    }

    static void observeNativeResolved(Context context, Object service, Object result, Bitmap bitmap) {
        HostDragHooks hooks = installed;
        if (hooks != null) hooks.longPress.hold(context, service, result, true, bitmap);
    }

    static long recognitionDeadline(Object service, Object result) {
        HostDragHooks hooks = installed;
        return hooks == null ? 0 : hooks.longPress.recognitionDeadline(service, result);
    }

    static boolean nativeUrlAvailable(Object service, Object result) {
        HostDragHooks hooks = installed;
        return hooks != null && hooks.recognitionHooks != null
                && hooks.recognitionHooks.nativeUrlAvailable(service, result);
    }

    static void offerRecognizedLinks(Object service, Object result, List<String> links) {
        HostDragHooks hooks = installed;
        if (hooks != null) hooks.longPress.offerRecognizedLinks(service, result, links);
    }

    static void nativeProbeCompleted(Object service, Object result) {
        HostDragHooks hooks = installed;
        if (hooks != null) hooks.longPress.nativeProbeCompleted(service, result);
    }

    static boolean claimNativeProbe(Object service, Object result) {
        HostDragHooks hooks = installed;
        return hooks == null || hooks.longPress.claimNativeProbe(service, result);
    }

    public static boolean dismissPreviewForLinkProbe(Object service, Object result, String source) {
        HostDragHooks hooks = installed;
        return hooks == null || hooks.longPress.dismissPreviewForLinkProbe(service, result, source);
    }

    private void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> service = loader.loadClass(ROOT + "services.TextContentExtensionService");
        module.hook(method(service, "onStartCommand", Intent.class, int.class, int.class))
                .setId("taplusbridge:drag-delivery").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object outcome = chain.proceed();
                    if (chain.getThisObject() instanceof Context context && chain.getArg(0) instanceof Intent intent
                            && (ACTION.equals(intent.getAction())
                            || io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag.ACTION_PREPARE.equals(intent.getAction()))) {
                        String token = intent.getStringExtra("drag_token");
                        if (token != null && token.matches("[A-Za-z0-9_-]{43}")) {
                            Object target = chain.getThisObject();
                            if (io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag.ACTION_PREPARE.equals(intent.getAction()))
                                longPress.prepare(context, target, token);
                            else {
                                long epoch = generation.incrementAndGet(); pending = null;
                                worker.execute(() -> take(context, target, token, epoch));
                            }
                        }
                    }
                    return outcome;
                });
        module.hook(method(service, "onStartPick")).setId("taplusbridge:drag-long-press-coordination")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Pending own = pending;
                    if (own != null && catcherResult.get(null) == own.result) return chain.proceed();
                    pending = null; generation.incrementAndGet();
                    if (chain.getThisObject() instanceof Context context) {
                        Bundle state = RuntimeClient.call(context, "drag_active", Bundle.EMPTY);
                        if (state != null && state.getBoolean("active")) return null;
                    }
                    return chain.proceed();
                });
        for (String name : new String[]{"cancelTask", "onDestroy"}) {
            module.hook(method(service, name)).setId("taplusbridge:drag-" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        pending = null;
                        if ("onDestroy".equals(name)) longPress.close(chain.getThisObject());
                        return chain.proceed();
                    });
        }
        module.log(4, "TaplusBridge", "Drag native content/actions installed");
    }

    private void take(Context context, Object service, String token, long epoch) {
        take(context, service, token, epoch, null);
    }

    private void take(Context context, Object service, String token, long epoch, Object nativeResult) {
        Bitmap image = null;
        try {
            Bundle request = new Bundle(); request.putString("token", token);
            Bundle data = BridgeTransport.call(context, "drag_take", request);
            if (data == null) throw new IllegalArgumentException("drag_expired");
            image = DragHostPayload.image(data);
            if (epoch != generation.get()) { if (image != null) image.recycle(); return; }
            String action = data.getString("action");
            boolean recognitionMode = data.getBoolean("recognize");
            if (!(recognitionMode ? io.github.yudigaga.taplusbridge.config.DragSettings.ACTIONS : List.of("portal", "lens", "translate"))
                    .contains(action) || "none".equals(action)
                    || (!recognitionMode && "lens".equals(action) && image == null)) throw new IllegalArgumentException("drag_action");
            module.log(4, "TaplusBridge", "drag delivery stage=received action=" + action + " recognize=" + recognitionMode);
            long age = SystemClock.elapsedRealtime() - data.getLong("issued_elapsed", -30_000);
            if (age < 0 || age >= 60_000) throw new IllegalArgumentException("drag_expired");
            String source = data.getString("source");
            if (nativeResult != null && !source.equals(HostLongPressDrag.source(nativeResult)))
                throw new IllegalArgumentException("drag_native_source");
            var info = context.getPackageManager().getPackageInfo(source, 0);
            var metrics = context.getResources().getDisplayMetrics();
            Bundle nativeData = new Bundle(data);
            Object acceptedNative = RecognitionPolicy.current(context).disabled(Channel.NATIVE) ? null : nativeResult;
            Object result = acceptedNative == null ? payloads.create(nativeData, token, (int) info.getLongVersionCode(),
                    metrics.widthPixels, metrics.heightPixels, image) : null;
            Bitmap ready = image;
            main.post(() -> {
                if (epoch != generation.get()) { if (ready != null) ready.recycle(); return; }
                try {
                    boolean preserveNative = acceptedNative != null;
                    Object target = result;
                    boolean reuse = false;
                    if (preserveNative) {
                        Object root = mainView.get(service);
                        boolean attached = root != null && attachedView.get(root) instanceof View view && view.isAttachedToWindow();
                        boolean taskFinished = finished.getBoolean(null), sameResult = catcherResult.get(null) == acceptedNative;
                        int mode = nativeTarget(taskFinished, sameResult, attached);
                        module.log(4, "TaplusBridge", "drag delivery stage=native_target action=" + action
                                + " mode=" + mode + " finished=" + taskFinished + " same_result=" + sameResult
                                + " attached=" + attached);
                        if (mode == 0) {
                            module.log(5, "TaplusBridge", "drag delivery stage=native_target outcome=skipped action=" + action);
                            if (ready != null) ready.recycle(); return;
                        }
                        reuse = mode == 1;
                        // The authorized result also owns metadata and extra jobs needed for hidden card links.
                        target = acceptedNative;
                        if (!reuse && ready != null) ready.recycle();
                    } else cancel.invoke(service);
                    Object selected = target;
                    Pending next = new Pending(epoch, selected, token, action, recognitionMode,
                            ready != null, preserveNative, SystemClock.elapsedRealtime() + 20_000);
                    pending = next;
                    if (reuse) {
                        if (ready != null) ready.recycle();
                        perform(context, service, next); return;
                    }
                    worker.execute(() -> {
                        if (epoch != generation.get() || pending != next) { if (ready != null) ready.recycle(); return; }
                        try {
                            // A newer native panel can appear after the main-thread check; never replace it.
                            if (preserveNative && !finished.getBoolean(null)) {
                                if (pending == next) pending = null;
                                if (ready != null) ready.recycle(); return;
                            }
                            deliverNative(service, selected, () -> {
                                if (recognitionHooks != null) recognitionHooks.keepRecognitionVisibleForNextPick();
                                deliver.invoke(callback.get(service), selected);
                            });
                            module.log(4, "TaplusBridge", "drag delivery stage=native_submitted action=" + next.action);
                            main.post(() -> perform(context, service, next));
                        } catch (ReflectiveOperationException | RuntimeException failure) { fail(context, next, failure); }
                    });
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    if (ready != null) ready.recycle();
                    if (epoch == generation.get()) fail(context, null, failure);
                }
            });
        } catch (Exception failure) {
            if (image != null && !image.isRecycled()) image.recycle();
            if (epoch == generation.get()) fail(context, null, failure);
        }
    }

    private void perform(Context context, Object service, Pending next) {
        if (pending != next || generation.get() != next.generation) return;
        try {
            if (catcherResult.get(null) != next.result) { pending = null; return; }
            if (SystemClock.elapsedRealtime() > next.deadline) {
                if (next.recognition) { completeRecognition(context, service, next, next.recognizedText); return; }
                pending = null; Toast.makeText(context, "未能完成拖拽操作，请检查传送门开关或在识别面板重试", Toast.LENGTH_LONG).show(); return;
            }
            Object root = mainView.get(service);
            if (!finished.getBoolean(null) && root != null && attachedView.get(root) instanceof View view
                    && view.isAttachedToWindow()) {
                Object card = recognition.get(root);
                if (next.recognition) {
                    switchMode.invoke(card, true);
                    if (next.image && !Boolean.TRUE.equals(hasRefreshed.invoke(card))) refreshImageRecognition.invoke(card);
                    RecognitionPolicy.State policy = RecognitionPolicy.current(context);
                    if (policy.disabled(Channel.SEGMENT)) {
                        boolean ocrComplete = recognitionHooks != null
                                && recognitionHooks.ocrComplete(service, next.result, next.recognitionEpoch);
                        if (shouldCompleteWithoutSegments(next.image, false, !policy.disabled(Channel.OCR), ocrComplete)) {
                            RecognitionHooks.DragSnapshot captured = dragSnapshot(service, next);
                            if (recognitionHooks != null && captured == null && next.recognitionEpoch >= 0) {
                                pending = null; return;
                            }
                            if (recognitionHooks != null && (captured == null || captured.waiting())) {
                                awaitRecommendation(context, service, next, captured); return;
                            }
                            if (next.image && !policy.disabled(Channel.OCR) && recognitionHooks != null)
                                next.recognizedText = recognitionHooks.ocrText(service, next.result, next.recognitionEpoch);
                            completeRecognition(context, service, next, next.recognizedText); return;
                        }
                        main.postDelayed(() -> perform(context, service, next), 100); return;
                    }
                    Object textCard = getItem.invoke(adapter.get(card), 0);
                    int count = (Integer) segmentCount.invoke(textCard);
                    if (!Boolean.TRUE.equals(isLoading.invoke(textCard))
                            && (count > 0 || (next.image && Boolean.TRUE.equals(hasRefreshed.invoke(card))))) {
                        selectAll(textCard);
                        String words = (String) naturalWords.invoke(segments.get(textCard));
                        next.recognizedText = words == null ? "" : words;
                        RecognitionHooks.DragSnapshot captured = dragSnapshot(service, next);
                        if (recognitionHooks != null && captured == null && next.recognitionEpoch >= 0) {
                            pending = null; return;
                        }
                        if (recognitionHooks != null && (captured == null || captured.waiting())) {
                            awaitRecommendation(context, service, next, captured); return;
                        }
                        module.log(4, "TaplusBridge", "drag delivery stage=recognition_ready action=" + next.action
                                + " chars=" + (words == null ? 0 : words.length()) + " links="
                                + (captured == null ? 0 : captured.links().size()) + " elapsed_ms="
                                + Math.max(0, SystemClock.elapsedRealtime() - next.beganAt));
                        completeRecognition(context, service, next, next.recognizedText); return;
                    }
                    main.postDelayed(() -> perform(context, service, next), 100); return;
                }
                if (expanded.get() != root) {
                    expanded = new WeakReference<>(root);
                    Object compact = splash.get(service);
                    if (compact != null) hideSplash.invoke(compact, false);
                    showMain.invoke(service, "translate".equals(next.action) || ("portal".equals(next.action) && !next.image));
                    main.postDelayed(() -> perform(context, service, next), 100); return;
                }
                if ("portal".equals(next.action)) {
                    if (view.isShown()) pending = null;
                    else main.postDelayed(() -> perform(context, service, next), 100);
                    return;
                }
                boolean text = "translate".equals(next.action);
                switchMode.invoke(card, text);
                Object item = getItem.invoke(adapter.get(card), text ? 0 : 1);
                if (text && !Boolean.TRUE.equals(isLoading.invoke(item))) selectAll(item);
                Object raw = (text ? translate : recognize).get(item);
                boolean ready = !text || (!Boolean.TRUE.equals(isLoading.invoke(item))
                        && selectedWords.invoke(item) instanceof String words && !words.isBlank());
                if (raw instanceof TextView action && action.isAttachedToWindow() && action.isEnabled() && action.isShown()
                        && ready && (text || "识图".contentEquals(action.getText()))) {
                    pending = null; action.performClick(); return;
                }
            }
            main.postDelayed(() -> perform(context, service, next), 100);
        } catch (ReflectiveOperationException | RuntimeException failure) { fail(context, next, failure); }
    }

    private void awaitRecommendation(Context context, Object service, Pending next, RecognitionHooks.DragSnapshot captured) {
        if (!next.recommendationWaitLogged) {
            next.recommendationWaitLogged = true;
            if (module != null) module.log(4, "TaplusBridge", "drag delivery stage=recognition_wait action=" + next.action
                    + " reason=" + (captured == null ? "snapshot_pending" : "recommendation_pending")
                    + " elapsed_ms=" + Math.max(0, SystemClock.elapsedRealtime() - next.beganAt));
        }
        main.postDelayed(() -> perform(context, service, next), 100);
    }

    private void selectAll(Object textCard) throws ReflectiveOperationException {
        int count = (Integer) segmentCount.invoke(textCard);
        if (count > 0 && (Integer) selectedCount.invoke(textCard) != count
                && selectAll.get(textCard) instanceof View button && button.isEnabled()) button.performClick();
    }

    static boolean shouldCompleteWithoutSegments(boolean image, boolean segmentEnabled, boolean ocrEnabled, boolean ocrComplete) {
        return !segmentEnabled && (!image || !ocrEnabled || ocrComplete);
    }

    private void completeRecognition(Context context, Object service, Pending next, String text) {
        if (pending != next || generation.get() != next.generation) return;
        final String acceptedText;
        try { acceptedText = RecognitionPolicy.recognizedText(next.result, next.image, text, RecognitionPolicy.read(context)); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { fail(context, next, unavailable); return; }
        RecognitionHooks.DragSnapshot captured = dragSnapshot(service, next);
        if (recognitionHooks != null && captured == null && next.recognitionEpoch >= 0) {
            pending = null; return;
        }
        List<String> links = captured == null ? List.of() : captured.links();
        longPress.offerRecognizedLinks(service, next.result, links);
        pending = null;
        worker.execute(() -> {
            if (generation.get() != next.generation) return;
            try {
                Bundle request = new Bundle(); request.putString("token", next.token); request.putString("text", acceptedText);
                request.putStringArrayList("links", new ArrayList<>(links));
                Bundle completed = BridgeTransport.call(context, "drag_complete", request);
                if (completed == null || completed.getString("token") == null) throw new IllegalStateException("drag_expired");
                module.log(4, "TaplusBridge", "drag delivery stage=recognition_complete action=" + next.action
                        + " links=" + links.size() + " elapsed_ms=" + Math.max(0, SystemClock.elapsedRealtime() - next.beganAt));
                Bundle choice = new Bundle(); choice.putString("token", completed.getString("token"));
                main.post(() -> {
                    if (generation.get() != next.generation) return;
                    try {
                        if (catcherResult.get(null) != next.result || finished.getBoolean(null)) {
                            module.log(4, "TaplusBridge", "drag delivery stage=choice_launch outcome=abandoned reason=native_panel_changed action=" + next.action);
                            return;
                        }
                        if (!next.preserveNative) cancel.invoke(service);
                        worker.execute(() -> {
                            if (generation.get() != next.generation) return;
                            try {
                                if (catcherResult.get(null) != next.result) return;
                                Bundle opened = RuntimeClient.call(context, "drag_choose", choice);
                                if (opened == null || !opened.getBoolean("accepted")) {
                                    module.log(5, "TaplusBridge", "drag delivery stage=choice_launch outcome=declined action=" + next.action);
                                    fail(context, null, new IllegalStateException("drag_choice"));
                                } else module.log(4, "TaplusBridge", "drag delivery stage=choice_launch outcome=accepted action=" + next.action);
                            } catch (IllegalAccessException unavailable) { fail(context, null, unavailable); }
                        });
                    } catch (ReflectiveOperationException | RuntimeException unavailable) { fail(context, null, unavailable); }
                });
            } catch (RuntimeException unavailable) {
                if (generation.get() == next.generation) fail(context, null, unavailable);
            }
        });
    }

    private RecognitionHooks.DragSnapshot dragSnapshot(Object service, Pending next) {
        if (recognitionHooks == null) return null;
        RecognitionHooks.DragSnapshot snapshot = recognitionHooks.dragSnapshot(service, next.result, next.recognitionEpoch);
        if (snapshot != null && next.recognitionEpoch < 0) next.recognitionEpoch = snapshot.epoch();
        return snapshot;
    }

    private void fail(Context context, Pending next, Throwable failure) {
        if (next != null && pending != next) return;
        pending = null;
        module.log(5, "TaplusBridge", "Drag delivery unavailable: " + failure.getClass().getSimpleName());
        main.post(() -> Toast.makeText(context, "拖拽操作未完成，请在传送门中重试", Toast.LENGTH_LONG).show());
    }

    /** 0 leaves an active native panel alone; 1 reuses it; 2 starts only after natural dismissal. */
    static int nativeTarget(boolean isFinished, boolean sameResult, boolean attached) {
        return isFinished ? 2 : sameResult && attached ? 1 : 0;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static Method method(Class<?> type, String name, Class<?>... arguments) throws NoSuchMethodException {
        Method value = type.getDeclaredMethod(name, arguments); value.setAccessible(true); return value;
    }
}
