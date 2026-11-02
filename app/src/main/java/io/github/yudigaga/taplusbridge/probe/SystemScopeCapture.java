package io.github.yudigaga.taplusbridge.probe;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.runtime.RuntimeServer;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import io.github.yudigaga.taplusbridge.runtime.ScopeCaptureStore;
import java.io.ByteArrayOutputStream;
import java.util.function.Supplier;

/** Physical long-press capture with an optional, explicitly enabled original-click link probe. */
@android.annotation.TargetApi(33)
public final class SystemScopeCapture implements RuntimeServer.SourceCapture {
    public static final String ACTION = "io.github.yudigaga.taplusbridge.SCOPE_CAPTURE";
    private final XposedModule module;
    private final Handler worker;
    private final Supplier<Context> context;
    private final Supplier<Object> atms;
    private final Supplier<RuntimeServer> runtime;
    private final ScopeCaptureWindow windows;
    private final PhysicalTouchState physical;
    private final ScopeLongPress gesture = new ScopeLongPress();
    private final ScopeCaptureStore store = new ScopeCaptureStore();
    private ScopeCaptureWindow.Snapshot origin;
    private RuntimeSnapshot configuration;
    private long generation, downElapsed;
    private record NativeSignal(String source, long at) {}
    private volatile NativeSignal nativeSignal;
    private volatile long pendingBegan;
    private boolean gestureActive;
    private int mode = 1;
    private long timeout = 550;
    private volatile float slop = 20;
    private volatile ScopeCaptureWindow.Snapshot pendingTarget;
    private volatile ScopeLinkProbe linkProbe;
    public ScopeLinkProbe linkProbe() { return linkProbe; }
    public void setLinkProbe(ScopeLinkProbe probe) { linkProbe = probe; }
    public void validateLinkTarget(ScopeCaptureWindow.Snapshot target) throws ReflectiveOperationException {
        windows.validate(atms.get(), target);
    }
    @Override public boolean linkReady() { return linkProbe != null; }
    @Override public boolean ownsLinkPick(Bundle request) {
        ScopeLinkProbe link = linkProbe;
        return link != null && request != null && request.get("native_elapsed") instanceof Long at
                && link.ownsNative(request.getString("source"), at);
    }

    public SystemScopeCapture(XposedModule module, Handler worker, Supplier<Context> context,
                              Supplier<Object> atms, Supplier<RuntimeServer> runtime, Class<?> atmsType, PhysicalTouchState physical)
            throws ReflectiveOperationException {
        this.module = module; this.worker = worker; this.context = context; this.atms = atms;
        this.runtime = runtime; this.physical = physical; windows = new ScopeCaptureWindow(atmsType);
    }

    /** Copy only touch primitives before returning to the system input dispatcher. */
    public void pointer(MotionEvent event, int display, boolean exclusiveAtEvent) {
        int action = event.getActionMasked(), count = event.getPointerCount(), device = event.getDeviceId();
        long down = event.getDownTime(), at = event.getEventTime();
        float x = event.getRawX(), y = event.getRawY();
        float x2 = count > 1 ? event.getRawX(1) : Float.NaN, y2 = count > 1 ? event.getRawY(1) : Float.NaN;
        ScopeLinkProbe link = linkProbe;
        if (link != null) link.pointer(action, count, x, y, down, slop);
        worker.post(() -> event(display, device, down, at, action, count, x, y, x2, y2, exclusiveAtEvent));
    }

    private void event(int display, int device, long down, long at, int action, int count,
                       float x, float y, float x2, float y2, boolean exclusiveAtEvent) {
        try {
            Context system = context.get(); RuntimeServer server = runtime.get();
            if (system == null || server == null || !unlocked(system)) { cancel(); return; }
            if (action == MotionEvent.ACTION_DOWN) {
                if (gestureActive || !exclusiveAtEvent) { cancel(); return; }
                cancel();
                configuration = server.sourceCaptureConfiguration();
                if (configuration == null || !configuration.enabled || (!configuration.scopeCapture && !configuration.scopeLinkProbe)
                        || !configuration.sourceScope.managed
                        || Settings.System.getInt(system.getContentResolver(), "content_catcher_network_enabled_content_extension", 0) != 1) return;
                origin = windows.inspect(atms.get(), display, x, y);
                if (!configuration.sourceScope.allows(origin.source)
                        || HostPolicy.HOST_PACKAGE.equals(origin.source) || HostPolicy.MODULE_PACKAGE.equals(origin.source)) {
                    cancel(); return;
                }
                mode = Settings.System.getInt(system.getContentResolver(), "support_long_press_mode",
                        Settings.Global.getInt(system.getContentResolver(), "support_long_press_mode", 1));
                timeout = Settings.System.getInt(system.getContentResolver(), "support_long_press_time_out",
                        ViewConfiguration.getLongPressTimeout() + 50);
                slop = ViewConfiguration.get(system).getScaledTouchSlop();
                downElapsed = SystemClock.elapsedRealtime();
                gestureActive = true;
                ScopeLinkProbe link = linkProbe;
                if (configuration.scopeLinkProbe && link != null)
                    link.arm(origin, configuration, down, downElapsed, timeout, mode);
            }
            if (origin == null) return;
            if (count > 1 && (!Float.isFinite(x2) || !Float.isFinite(y2)
                    || !origin.bounds.contains((int) x2, (int) y2))) { cancel(); return; }
            ScopeLongPress.Trigger trigger = gesture.event(display, device, down, at, action, count,
                    x, y, x2, y2, mode, timeout, slop, SystemClock.uptimeMillis());
            if (trigger != null) {
                gestureActive = false;
                event("gesture", "none");
                long expected = generation;
                var picked = origin; var config = configuration; long began = downElapsed;
                // Screenshot-only mode lets ROM capture win; link mode owns the original click first.
                worker.postDelayed(() -> capture(expected, picked, config, began), 300);
            } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) cancel();
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            cancel(); event("rejected", unavailable.getClass().getSimpleName());
        }
    }

    private void capture(long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config, long began) {
        RuntimeServer server = runtime.get();
        RuntimeSnapshot latest = server == null ? null : server.sourceCaptureConfiguration();
        if (expected != generation || latest == null || !latest.identity.equals(config.identity)
                || !unlocked(context.get()) || !physical.released() || nativeSince(picked.source, began)) return;
        ScopeLinkProbe link = linkProbe;
        if (latest.scopeLinkProbe && link != null) {
            link.confirm(picked, latest, url -> worker.post(() -> deliverCapture(expected, picked, config, began, url)));
        } else deliverCapture(expected, picked, config, began, null);
    }

    private void deliverCapture(long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config,
                                long began, String url) {
        Bitmap image = null;
        try {
            RuntimeServer server = runtime.get(); Context system = context.get();
            RuntimeSnapshot latest = server == null ? null : server.sourceCaptureConfiguration();
            if (expected != generation || latest == null || !latest.identity.equals(config.identity)
                    || !unlocked(system) || !physical.released() || nativeSince(picked.source, began)) return;
            byte[] png = null;
            if (latest.scopeCapture) {
                try {
                    image = windows.capture(atms.get(), picked);
                    if (image == null || image.isRecycled()) throw new IllegalStateException("scope_image_missing");
                    var output = new LimitedPng();
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("scope_image_encode");
                    png = output.toByteArray();
                } catch (ReflectiveOperationException | RuntimeException unavailable) {
                    if (!latest.scopeLinkProbe) throw unavailable;
                    event("failed", unavailable.getClass().getSimpleName());
                }
            }
            if (png == null) windows.validate(atms.get(), picked);
            if (png == null && url == null && !latest.scopeLinkProbe) return;
            if (!physical.released() || expected != generation || nativeSince(picked.source, began)) return;
            var host = system.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS);
            if (!HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode())
                    || host.applicationInfo == null || !host.applicationInfo.enabled) return;
            Point size = new Point(); Display display = system.getSystemService(DisplayManager.class).getDisplay(0);
            if (display == null) return; display.getRealSize(size);
            Bundle data = new Bundle(); data.putString("source", picked.source); data.putString("activity", picked.activity);
            data.putInt("uid", picked.uid); data.putInt("user", picked.user); data.putInt("display", picked.display);
            data.putFloat("x", picked.x); data.putFloat("y", picked.y);
            data.putInt("left", picked.bounds.left); data.putInt("top", picked.bounds.top);
            data.putInt("right", picked.bounds.right); data.putInt("bottom", picked.bounds.bottom);
            data.putInt("width", size.x); data.putInt("height", size.y);
            data.putLong("captured_elapsed", SystemClock.elapsedRealtime());
            data.putLong("gesture_elapsed", began);
            pendingBegan = began;
            String nonce = png != null ? store.issueImage(host.applicationInfo.uid, latest, data, png, url)
                    : url != null ? store.issueText(host.applicationInfo.uid, latest, data, url)
                    : store.issueResume(host.applicationInfo.uid, latest, data);
            pendingTarget = picked;
            Intent start = new Intent(ACTION).setComponent(new ComponentName(HostPolicy.HOST_PACKAGE,
                    "com.miui.contentextension.services.TextContentExtensionService")).putExtra("scope_nonce", nonce);
            if (system.startService(start) == null) { store.clear(); throw new IllegalStateException("scope_host_unavailable"); }
            event("submitted", "none");
        } catch (ReflectiveOperationException | PackageManager.NameNotFoundException | RuntimeException failure) {
            store.clear(); event("failed", failure.getClass().getSimpleName());
        } finally { if (image != null && !image.isRecycled()) image.recycle(); }
    }

    @Override public Bundle take(int owner, Bundle request, RuntimeSnapshot config) {
        try {
            ScopeCaptureWindow.Snapshot target = pendingTarget;
            if (target == null || !unlocked(context.get()) || !physical.released()) return Bundle.EMPTY;
            windows.validate(atms.get(), target);
            Bundle result = store.take(owner, request, config);
            if (result.containsKey("fd") || "text".equals(result.getString("kind"))
                    || "resume".equals(result.getString("kind"))) pendingTarget = null;
            return result;
        } catch (ReflectiveOperationException | RuntimeException changed) {
            store.clear(); pendingTarget = null; return Bundle.EMPTY;
        }
    }
    @Override public void nativeResult(Bundle request) {
        String source = request == null ? null : request.getString("source");
        Object raw = request == null ? null : request.get("native_elapsed");
        long now = SystemClock.elapsedRealtime();
        if (!HostPolicy.validPackage(source) || !(raw instanceof Long at) || at < 0 || at > now || now - at > 10_000) return;
        RuntimeServer server = runtime.get();
        RuntimeSnapshot latest = server == null ? null : server.sourceCaptureConfiguration();
        if (latest != null && latest.scopeLinkProbe && latest.sourceScope.allows(source)) return;
        NativeSignal previous = nativeSignal;
        if (previous == null || at >= previous.at) nativeSignal = new NativeSignal(source, at);
        if (at >= pendingBegan) store.nativeResult(source);
        ScopeLinkProbe link = linkProbe;
        if (link != null) link.nativeResult(source, at);
    }
    private boolean nativeSince(String source, long began) {
        NativeSignal signal = nativeSignal; return signal != null && source.equals(signal.source) && signal.at >= began;
    }
    private void cancel() {
        generation++; gestureActive = false; origin = null; configuration = null; gesture.cancel();
        ScopeLinkProbe link = linkProbe; if (link != null) link.cancel();
    }
    private static boolean unlocked(Context context) {
        if (context == null) return false;
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        UserManager users = context.getSystemService(UserManager.class);
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked()
                && users != null && users.isUserUnlocked();
    }
    private void event(String stage, String reason) {
        module.log(4, "TaplusBridge", "scope capture stage=" + stage + " reason=" + reason);
    }
    private static final class LimitedPng extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            if (count >= ScopeCaptureStore.MAX_BYTES) throw new IllegalStateException("scope_image_large");
            super.write(value);
        }
        @Override public synchronized void write(byte[] data, int offset, int length) {
            if ((long) count + length > ScopeCaptureStore.MAX_BYTES) throw new IllegalStateException("scope_image_large");
            super.write(data, offset, length);
        }
    }
}
