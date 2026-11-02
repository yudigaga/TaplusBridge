package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.runtime.RuntimeClient;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Accepts only one-use nonces; image data and source identity come from the existing framework Binder. */
public final class HostScopeCaptureHooks {
    public static final String ACTION = "io.github.yudigaga.taplusbridge.SCOPE_CAPTURE";
    private static final String ROOT = "com.miui.contentextension.";
    private final XposedModule module;
    private final ScopeCapturePayload.Factory payloads;
    private final Field callback;
    private final Method deliver, sourcePackage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TaplusBridge-scope-image"); thread.setDaemon(true); return thread;
    });
    private final ScopeCaptureDispatch dispatch = new ScopeCaptureDispatch(worker);
    private final ExecutorService signals = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TaplusBridge-scope-native"); thread.setDaemon(true); return thread;
    });
    private final ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "TaplusBridge-scope-image-timeout"); thread.setDaemon(true); return thread;
    });
    private final ThreadLocal<Boolean> synthetic = ThreadLocal.withInitial(() -> false);
    private final LinkedHashMap<String, Long> nonces = new LinkedHashMap<>();
    private final PendingNative pendingNative = new PendingNative();
    private volatile NativeCapture nativeCapture;
    private record NativeCapture(String source, long at) {}
    private volatile Admission admission;
    private record Admission(String source, long at, boolean synthetic) {}

    private HostScopeCaptureHooks(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        this.module = module; payloads = new ScopeCapturePayload.Factory(loader);
        Class<?> service = loader.loadClass(ROOT + "services.TextContentExtensionService");
        Class<?> result = loader.loadClass("miui.contentcatcher.sdk.ClientCatcherResult");
        Class<?> listener = loader.loadClass("miui.contentcatcher.sdk.listener.IContentListenerCallback");
        callback = service.getDeclaredField("mCallback");
        if (Modifier.isStatic(callback.getModifiers()) || callback.getType() != listener)
            throw new NoSuchFieldException("scope_capture_callback_contract");
        callback.setAccessible(true);
        deliver = method(listener, "onContentReceived", false, void.class, result);
        sourcePackage = method(service, "getInjectorPackage", true, String.class);
    }

    public static void install(XposedModule module, ClassLoader loader) {
        try {
            HostScopeCaptureHooks hooks = new HostScopeCaptureHooks(module, loader); hooks.install(loader);
            module.log(4, "TaplusBridge", "Scope capture host delivery installed");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            module.log(5, "TaplusBridge", "Scope capture host unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> service = loader.loadClass(ROOT + "services.TextContentExtensionService");
        Class<?> result = loader.loadClass("miui.contentcatcher.sdk.ClientCatcherResult");
        Method start = method(service, "onStartCommand", false, int.class, Intent.class, int.class, int.class);
        module.hook(start).setId("taplusbridge:scope-capture-start")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object outcome = chain.proceed();
                    try {
                        if (chain.getThisObject() instanceof Context context && supported(context)
                                && chain.getArg(0) instanceof Intent intent && ACTION.equals(intent.getAction())) {
                            String nonce = intent.getStringExtra("scope_nonce");
                            if (nonce != null && nonce.matches("[A-Za-z0-9_-]{16,128}") && claim(nonce)) {
                                Object target = chain.getThisObject(); long requestedAt = SystemClock.elapsedRealtime();
                                worker.execute(() -> take(context, target, nonce, requestedAt, 0));
                            }
                        }
                    } catch (RuntimeException failure) { report("request", failure); }
                    return outcome;
                });
        for (String name : new String[]{"onPickText", "onPickImage"}) {
            Method pick = method(service, name, false, void.class, result);
            module.hook(pick).setId("taplusbridge:scope-capture-native-" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (chain.getThisObject() instanceof Context context && supported(context)) {
                            try {
                                String source = (String) sourcePackage.invoke(null);
                                if (HostPolicy.validPackage(source)) {
                                    long at = SystemClock.elapsedRealtime();
                                    if (!synthetic.get()) {
                                        String identity = ownedLinkPick(context, source, at);
                                        if (identity != null) {
                                            PendingNative.Held held = pendingNative.hold("onPickImage".equals(name),
                                                    source, at, identity, chain.getArg(0));
                                            WeakReference<PendingNative.Held> reference = new WeakReference<>(held);
                                            timeout.schedule(() -> {
                                                PendingNative.Held current = reference.get();
                                                if (current != null) pendingNative.expire(current);
                                            }, 30, TimeUnit.SECONDS);
                                            admission = null;
                                            module.log(4, "TaplusBridge", "scope link stage=blocked reason=native_pick");
                                            return null;
                                        }
                                    }
                                    admission = new Admission(source, at, synthetic.get());
                                }
                            } catch (ReflectiveOperationException | RuntimeException failure) { report("native", failure); }
                        }
                        return chain.proceed();
                    });
        }
        for (String name : new String[]{"startPickTextTask", "startPickImageTask"}) {
            Method task = method(service, name, false, void.class);
            module.hook(task).setId("taplusbridge:scope-capture-accepted-" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Admission admitted = admission;
                        if (admitted != null && chain.getThisObject() instanceof Context context && supported(context)) {
                            if (admission == admitted) admission = null;
                            if (!admitted.synthetic && SystemClock.elapsedRealtime() - admitted.at <= 10_000) {
                                nativeCapture = new NativeCapture(admitted.source, admitted.at);
                                signals.execute(() -> reportNative(context, admitted.source, admitted.at, 0));
                            }
                        }
                        return chain.proceed();
                    });
        }
    }

    private synchronized boolean claim(String nonce) {
        long now = SystemClock.elapsedRealtime(); nonces.entrySet().removeIf(item -> now - item.getValue() > ScopeCapturePayload.MAX_AGE_MILLIS);
        if (nonces.containsKey(nonce) || nonces.size() >= 8) return false;
        nonces.put(nonce, now); return true;
    }

    private void take(Context context, Object service, String nonce, long requestedAt, int attempt) {
        try {
            if (SystemClock.elapsedRealtime() - requestedAt > 5000) return;
            Bundle request = new Bundle(); request.putString("nonce", nonce);
            Bundle response = RuntimeClient.call(context, "scope_capture_take", request);
            if (response == null) {
                if (attempt < 5) {
                    RuntimeClient.connect(context, null);
                    main.postDelayed(() -> worker.execute(() -> take(context, service, nonce, requestedAt, attempt + 1)), 500);
                }
                return;
            }
            Object descriptor = response.get("fd");
            if ("text".equals(response.get("kind")) || "resume".equals(response.get("kind"))) {
                try {
                    ScopeCapturePayload.Metadata metadata = ScopeCapturePayload.validate(response, SystemClock.elapsedRealtime());
                    String url = response.getString("url");
                    module.log(4, "TaplusBridge", "scope capture stage=host_received reason=none");
                    dispatch.submit(() -> deliver(context, service, nonce, response, metadata, null, url));
                } finally {
                    if (descriptor instanceof ParcelFileDescriptor unexpected) unexpected.close();
                }
                return;
            }
            if (!(descriptor instanceof ParcelFileDescriptor fd)) return;
            Bitmap bitmap;
            ScopeCapturePayload.Metadata metadata;
            try (fd; ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                var deadline = timeout.schedule(() -> { try { fd.close(); } catch (IOException ignored) {} }, 5, TimeUnit.SECONDS);
                try {
                    metadata = ScopeCapturePayload.validate(response, SystemClock.elapsedRealtime());
                    bitmap = ScopeCapturePayload.decode(ScopeCapturePayload.read(stream, metadata.bytes()));
                } finally { deadline.cancel(false); }
            }
            Bitmap ready = bitmap; ScopeCapturePayload.Metadata capture = metadata;
            module.log(4, "TaplusBridge", "scope capture stage=host_received reason=none");
            String url = response.getString("url");
            try { dispatch.submit(() -> deliver(context, service, nonce, response, capture, ready, url)); }
            catch (RuntimeException rejected) { ready.recycle(); throw rejected; }
        } catch (IOException | RuntimeException failure) { report("read", failure); }
    }

    @SuppressWarnings("deprecation")
    private void deliver(Context context, Object service, String nonce, Bundle response,
                         ScopeCapturePayload.Metadata metadata, Bitmap bitmap, String url) {
        boolean bitmapHanded = false;
        try {
            ScopeCapturePayload.validate(response, SystemClock.elapsedRealtime());
            Bundle current = RuntimeClient.call(context, "source_scope", Bundle.EMPTY);
            String identity = response.getString("scope_identity");
            boolean text = "text".equals(metadata.kind());
            boolean linkMode = current != null && current.getBoolean("scope_link_probe_enabled");
            if (current == null || !("image".equals(metadata.kind()) ? current.getBoolean("scope_capture_enabled") || linkMode : linkMode) || identity == null
                    || !identity.equals(current.getString("identity"))) return;
            NativeCapture nativeResult = nativeCapture;
            if (!current.getBoolean("scope_link_probe_enabled") && nativeResult != null && nativeResult.source().equals(metadata.source())
                    && nativeResult.at() >= response.getLong("gesture_elapsed", metadata.capturedElapsed())) return;
            PackageInfo info = context.getPackageManager().getPackageInfo(metadata.source(), 0);
            if (info.applicationInfo == null || info.applicationInfo.uid != metadata.uid()) throw new IllegalStateException("scope_capture_identity");
            PendingNative.Picks originals = pendingNative.take(metadata.source(), identity,
                    response.getLong("gesture_elapsed", metadata.capturedElapsed()), metadata.capturedElapsed(), SystemClock.elapsedRealtime());
            NativeContents contents = nativeContents(originals);
            String originalText = contents.text();
            Object result;
            boolean usesBitmap = false;
            if (contents.image() != null) result = payloads.enrich(contents.image(), url);
            else if (bitmap != null) {
                result = payloads.createImage(metadata, bitmap, url, info.versionCode, nonce);
                usesBitmap = true;
            } else if (originals.text() != null) result = payloads.enrich(originals.text(), url);
            else if (text) result = payloads.createText(metadata, url, info.versionCode, nonce);
            else return;
            attachContent(result, originalText, linkMode && (contents.image() != null || usesBitmap));
            Object listener = callback.get(service);
            if (listener == null) throw new IllegalStateException("scope_capture_callback_missing");
            synthetic.set(true);
            try {
                bitmapHanded = usesBitmap; deliver.invoke(listener, result);
                module.log(4, "TaplusBridge", "scope capture stage=host_submitted reason=none");
            }
            finally { synthetic.remove(); }
        } catch (ReflectiveOperationException | android.content.pm.PackageManager.NameNotFoundException | RuntimeException failure) {
            report("deliver", failure);
        } finally { if (!bitmapHanded && bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
    }

    private void reportNative(Context context, String source, long occurredAt, int attempt) {
        Bundle request = new Bundle(); request.putString("source", source); request.putLong("native_elapsed", occurredAt);
        if (RuntimeClient.call(context, "scope_capture_native", request) == null && attempt < 3) {
            RuntimeClient.connect(context, null);
            main.postDelayed(() -> signals.execute(() -> reportNative(context, source, occurredAt, attempt + 1)), 300);
        }
    }

    private static String ownedLinkPick(Context context, String source, long occurredAt) {
        Bundle request = new Bundle(); request.putString("source", source); request.putLong("native_elapsed", occurredAt);
        Bundle response = RuntimeClient.call(context, "scope_link_probe_owns", request);
        String identity = response == null ? null : response.getString("identity");
        return response != null && response.getBoolean("owned") && identity != null
                && identity.matches("[A-Za-z0-9_-]{16,128}") ? identity : null;
    }

    static String nativeText(Object result) throws ReflectiveOperationException {
        if (result == null) return null;
        Object params = result.getClass().getMethod("getParams", String.class).invoke(result, "text_pick");
        Object value = params instanceof Map<?, ?> map ? map.get("pick_mode") : null;
        String text = value instanceof CharSequence chars ? chars.toString() : null;
        if (value instanceof Bundle bundle) {
            if (bundle.containsKey("viewContent")) text = bundle.getString("viewContent");
            else {
                String[] keys = bundle.getStringArray("apkParamList");
                if (keys != null) for (int i = 0; i < Math.min(keys.length, 128); i++) {
                    String key = keys[i];
                    if (key != null && key.startsWith("miuiCE_") && key.length() > 7)
                        text = bundle.getCharSequence(key.substring(7), "").toString();
                }
            }
        }
        return text == null || text.isEmpty() ? null : text.substring(0, Math.min(text.length(), 131072));
    }
    record NativeContents(Object image, String text) {}
    static NativeContents nativeContents(PendingNative.Picks picks) throws ReflectiveOperationException {
        String text = nativeText(picks.text());
        if (text == null) text = nativeText(picks.image());
        Object image = imageOnly(picks.image());
        if (image == null) image = imageOnly(picks.text());
        return new NativeContents(image, text);
    }
    @SuppressWarnings("unchecked")
    private static Object imageOnly(Object original) throws ReflectiveOperationException {
        if (original == null) return null;
        Class<?> type = original.getClass();
        Object raw = type.getMethod("getParams", String.class).invoke(original, "image_pick");
        if (!(raw instanceof Map<?, ?> image) || image.isEmpty()) return null;
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object result = constructor.newInstance();
        Method token = type.getMethod("getInjectorToken");
        type.getMethod("setInjectorToken", token.getReturnType()).invoke(result, token.invoke(original));
        Object oldProperties = type.getMethod("getPropertyMap").invoke(original);
        Object newProperties = type.getMethod("getPropertyMap").invoke(result);
        if (oldProperties instanceof Map<?, ?> before && newProperties instanceof Map<?, ?> after)
            ((Map<Object, Object>) after).putAll(before);
        Method add = type.getMethod("addContent", String.class, String.class, Object.class);
        for (Map.Entry<?, ?> entry : image.entrySet()) if (entry.getKey() instanceof String key)
            add.invoke(result, "image_pick", key, entry.getValue());
        return result;
    }
    @SuppressWarnings("unchecked")
    private static void attachContent(Object result, String text, boolean image) throws ReflectiveOperationException {
        Object raw = result.getClass().getMethod("getPropertyMap").invoke(result);
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> properties = (Map<String, Object>) map;
            if (text != null) properties.put("taplus_scope_text", text);
            if (image) properties.put("taplus_scope_mixed", true);
        }
    }

    /** At most one original text and image; stale or different-gesture content never joins a completion. */
    static final class PendingNative {
        record Held(String source, long at, String identity, Object result) {}
        record Picks(Object text, Object image) {}
        private Held text, image;
        synchronized Held hold(boolean isImage, String source, long at, String identity, Object result) {
            Held previous = isImage ? text : image;
            if (previous != null && (!previous.source.equals(source) || !previous.identity.equals(identity))) text = image = null;
            Held held = new Held(source, at, identity, result);
            if (isImage) image = held; else text = held;
            return held;
        }
        synchronized void expire(Held held) {
            if (text == held) text = null;
            if (image == held) image = null;
        }
        synchronized Picks take(String source, String identity, long began, long captured, long now) {
            if (text != null && now - text.at >= 30_000) text = null;
            if (image != null && now - image.at >= 30_000) image = null;
            Held pickedText = matches(text, source, identity, began, captured) ? text : null;
            Held pickedImage = matches(image, source, identity, began, captured) ? image : null;
            if (pickedText != null) text = null;
            if (pickedImage != null) image = null;
            return new Picks(pickedText == null ? null : pickedText.result, pickedImage == null ? null : pickedImage.result);
        }
        private boolean matches(Held value, String source, String identity, long began, long captured) {
            return value != null && value.source.equals(source) && value.identity.equals(identity)
                    && value.at >= began && value.at <= captured;
        }
    }

    private static boolean supported(Context context) {
        if (android.os.Process.myUid() / 100000 != 0) return false;
        try {
            PackageInfo host = context.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE, 0);
            return HostPolicy.HOST_PACKAGE.equals(context.getPackageName())
                    && HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode());
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return false; }
    }
    private void report(String stage, Throwable failure) {
        Throwable cause = failure;
        for (int i = 0; i < 8 && cause instanceof java.lang.reflect.InvocationTargetException wrapped; i++) {
            Throwable target = wrapped.getTargetException();
            if (target == null || target == cause) break;
            cause = target;
        }
        module.log(5, "TaplusBridge", "Scope capture stage=" + stage + " failure=" + failure.getClass().getSimpleName()
                + " cause=" + cause.getClass().getSimpleName());
    }
    private static Method method(Class<?> owner, String name, boolean isStatic, Class<?> result, Class<?>... args)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, args);
        if (Modifier.isStatic(method.getModifiers()) != isStatic || method.getReturnType() != result)
            throw new NoSuchMethodException("scope_capture_host_contract");
        method.setAccessible(true); return method;
    }
}
