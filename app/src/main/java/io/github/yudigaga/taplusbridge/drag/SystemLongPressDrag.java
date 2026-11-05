package io.github.yudigaga.taplusbridge.drag;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.probe.ScopeCaptureWindow;
import io.github.yudigaga.taplusbridge.probe.ProbeContract;
import io.github.yudigaga.taplusbridge.runtime.RuntimeServer;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A physical direction gesture paired with an actual native pick, without injecting source input. */
public final class SystemLongPressDrag implements RuntimeServer.DragGestures {
    private final XposedModule module;
    private final Handler worker;
    private final Supplier<Context> context;
    private final Supplier<Object> atms;
    private final Supplier<RuntimeServer> runtime;
    private final ScopeCaptureWindow windows;
    private Run current;
    private Runnable directedRelease;
    private CardLinks cardLinks;
    public void setOnDirectedRelease(Runnable listener) { directedRelease = listener; }

    /** Reads the card URL the same way a stationary long press does, after the finger is up. */
    public interface CardLinks {
        void capture(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, Consumer<String> url);
    }

    public void setCardLinks(CardLinks cardLinks) { this.cardLinks = cardLinks; }

    private static final class Run {
        final ScopeCaptureWindow.Snapshot target;
        final RuntimeSnapshot config;
        final LongPressDirection gesture;
        final long began;
        final LongPressStageSchedule.Deadlines stages;
        final float tolerance;
        final float width, height, density;
        String token;
        String lastEdge;
        String cardLink;
        boolean linkRequested;
        boolean nativeUrlAvailable;
        boolean consumed;
        boolean transferred;
        boolean dragFeedbackSent;
        boolean cardClickDue;
        long releasedAt;
        Run(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, LongPressDirection gesture, long began,
            LongPressStageSchedule.Deadlines stages, float tolerance, float width, float height, float density) {
            this.target = target; this.config = config; this.gesture = gesture; this.began = began; this.tolerance = tolerance;
            this.stages = stages;
            this.width = width; this.height = height; this.density = density;
        }
        String direction() {
            if (!gesture.held()) return null;
            String touching = EdgeDragTarget.at(gesture.x(), gesture.y(), target.x, target.y,
                    width, height, density).touching();
            if (touching != null) lastEdge = touching;
            if (!gesture.released()) return touching;
            String travel = gesture.direction();
            return touching != null ? touching : travel != null && travel.equals(lastEdge) ? lastEdge : null;
        }
    }

    public SystemLongPressDrag(XposedModule module, Handler worker, Supplier<Context> context,
                               Supplier<Object> atms, Supplier<RuntimeServer> runtime, Class<?> atmsType)
            throws ReflectiveOperationException {
        this.module = module; this.worker = worker; this.context = context; this.atms = atms; this.runtime = runtime;
        windows = new ScopeCaptureWindow(atmsType);
    }

    /** MotionEvent is owned by the dispatcher; only copy primitives before returning. */
    public void pointer(MotionEvent event, int display, boolean exclusive) {
        int action = event.getActionMasked(), count = event.getPointerCount(), device = event.getDeviceId();
        long down = event.getDownTime(), at = event.getEventTime();
        float x = event.getRawX(), y = event.getRawY();
        worker.post(() -> event(display, device, down, at, action, count, x, y, exclusive));
    }

    public synchronized boolean transferredPointer(int display, int device, long downAt, int action) {
        Run run = current;
        if (run == null || !run.transferred) return false;
        boolean sameStream = shouldIgnoreTransferredPointer(true, display, run.gesture.device(), device,
                run.gesture.downAt(), downAt, run.gesture.released(), action);
        if (sameStream) return true;
        if (shouldResetTransferredOnDown(true, 0, run.gesture.device(), run.gesture.downAt(),
                display, device, downAt, action)) {
            current = null;
            log("orphaned_takeover_reset reason=new_physical_down");
        }
        return false;
    }

    public synchronized boolean transferred() {
        return current != null && current.transferred;
    }

    static boolean shouldIgnoreTransferredPointer(boolean transferred, int display, int gestureDevice,
                                                   int eventDevice, long gestureDownAt, long eventDownAt,
                                                   boolean released, int action) {
        return transferred && display == 0 && gestureDevice == eventDevice && gestureDownAt == eventDownAt
                && (action != MotionEvent.ACTION_DOWN || !released);
    }

    private void event(int display, int device, long down, long at, int action, int count,
                       float x, float y, boolean exclusive) {
        boolean kick = false;
        synchronized (this) {
        try {
            if (action == MotionEvent.ACTION_DOWN) {
                Run active = current;
                if (active != null && active.transferred) {
                    boolean sameStream = ignoreTransferredDown(true, active.gesture.released(),
                            active.gesture.device(), device, active.gesture.downAt(), down);
                    if (sameStream) return;
                    if (!shouldResetTransferredOnDown(true, 0, active.gesture.device(), active.gesture.downAt(),
                            display, device, down, action)) return;
                    current = null;
                    log("orphaned_takeover_reset reason=new_physical_down");
                }
                current = null;
                RuntimeSnapshot config = configuration(); Context system = context.get();
                if (!exclusive || display != 0 || count != 1 || device < 0 || config == null
                        || !unlocked(system) || DragGestureGate.suppressLongPress()
                        || at != down || at > SystemClock.uptimeMillis() || SystemClock.uptimeMillis() - at > 300
                        || DragSettings.DIRECTIONS.stream().allMatch(d -> "none".equals(config.drag.action(d)))) return;
                // The native component decides its own long-press mode and privacy admission.
                // This independent entry handles a single finger and leaves other streams alone.
                int mode = Settings.System.getInt(system.getContentResolver(), "support_long_press_mode",
                        Settings.Global.getInt(system.getContentResolver(), "support_long_press_mode", 1));
                if (mode != 1) return;
                ScopeCaptureWindow.Snapshot target = windows.inspect(atms.get(), display, x, y);
                if (!HostPolicy.validTarget(target.source) || !config.sourceScope.allows(target.source)) return;
                long timeout = Settings.System.getInt(system.getContentResolver(), "support_long_press_time_out",
                        ViewConfiguration.getLongPressTimeout() + 50);
                if (timeout < 100 || timeout > 5_000) return;
                float slop = ViewConfiguration.get(system).getScaledTouchSlop();
                float threshold = Math.max(slop * 3, 48 * system.getResources().getDisplayMetrics().density);
                var metrics = system.getResources().getDisplayMetrics();
                long nowElapsed = SystemClock.elapsedRealtime(), nowUptime = SystemClock.uptimeMillis();
                long began = nowElapsed - Math.max(0, nowUptime - down);
                var stages = LongPressStageSchedule.fromDown(began, timeout,
                        config.drag.recognitionDelayMillis, config.drag.dragDelayMillis);
                current = new Run(target, config, new LongPressDirection(down, device, x, y, timeout, slop, threshold),
                        began, stages, Math.max(slop * 2, 24 * metrics.density),
                        metrics.widthPixels, metrics.heightPixels, metrics.density);
                return;
            }
            Run run = current;
            if (run == null || run.transferred || run.gesture.released()) return;
            boolean sameFingerUp = action == MotionEvent.ACTION_UP && display == 0
                    && device == run.gesture.device() && down == run.gesture.downAt();
            // The native long-press surface can make this pointer look non-exclusive.
            // That is not a finger-up, and the system drag gate is a different gesture.
            if (!sameFingerUp && (DragGestureGate.suppressLongPress() || !exclusive)) return;
            String previous = run.direction();
            run.gesture.event(display, device, down, at, action, count, x, y, SystemClock.uptimeMillis());
            if (run.direction() != null && !run.direction().equals(previous))
                log("direction native=" + (run.token != null) + " direction=" + run.direction());
            if (run.gesture.released()) {
                run.releasedAt = SystemClock.elapsedRealtime();
                if (run.direction() != null) {
                    run.cardClickDue = true;
                    log("released direction=" + run.direction());
                    kick = true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failed) {
            current = null;
            // Normal touches on menus or protected windows are not eligible candidates.
        }
        }
        if (kick) notifyDirectedRelease();
    }

    private RuntimeSnapshot configuration() {
        RuntimeServer server = runtime.get(); return server == null ? null : server.dragConfiguration();
    }

    @Override public synchronized Bundle claim(Bundle request, RuntimeSnapshot config) {
        Run run = current; long now = SystemClock.elapsedRealtime();
        if (request == null || request.size() != 4 || !(request.get("source") instanceof String source)
                || !(request.get("x") instanceof Float x) || !(request.get("y") instanceof Float y)
                || !(request.get("native_elapsed") instanceof Long at) || at < 0 || at > now || now - at > 2_000
                || !valid(run, config) || run.consumed || !source.equals(run.target.source) || at < run.began
                || !Float.isFinite(x) || !Float.isFinite(y)
                || Math.hypot(x - run.target.x, y - run.target.y) > run.tolerance
                || (run.gesture.released() && now - run.releasedAt > 600)) return Bundle.EMPTY;
        try {
            int hostUid = context.get().getPackageManager().getApplicationInfo(HostPolicy.HOST_PACKAGE, 0).uid;
            windows.validateBehindHost(atms.get(), run.target, hostUid);
        }
        catch (android.content.pm.PackageManager.NameNotFoundException changed) { return Bundle.EMPTY; }
        catch (ReflectiveOperationException | RuntimeException changed) { return Bundle.EMPTY; }
        if (run.token == null) {
            byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
            run.token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            log("armed");
        }
        return state(run);
    }

    @Override public synchronized Bundle poll(Bundle request, RuntimeSnapshot config) {
        Run run = current;
        if (request == null || request.size() != 1 || !(request.get("token") instanceof String token)
                || run == null || !token.equals(run.token)) return cancelled();
        if (!valid(run, config)) { cancelRun(run, "lease_invalid"); return cancelled(); }
        if (run.gesture.released()) {
            long sinceRelease = SystemClock.elapsedRealtime() - run.releasedAt;
            if (!keepForCardLink(run.direction() != null, IntentObservation.validHttpUrl(run.cardLink), sinceRelease)) {
                try { validateAfterPick(run); }
                catch (ReflectiveOperationException | RuntimeException changed) {
                    cancelRun(run, "source_changed"); return cancelled();
                }
            }
        }
        return state(run);
    }

    @Override public synchronized Bundle overlayReady(Bundle request, RuntimeSnapshot config) {
        Run run = current;
        if (request == null || request.size() != 1 || !(request.get("token") instanceof String token)
                || run == null || !token.equals(run.token)) return cancelled();
        if (run.transferred || run.gesture.released()) return cancelled();
        if (!valid(run, config)) { cancelRun(run, "lease_invalid"); return cancelled(); }
        if (SystemClock.elapsedRealtime() < run.stages.dragAt()) return state(run);
        try {
            validateAfterPick(run);
        } catch (ReflectiveOperationException unavailable) {
            current = null;
            String reason = "drag_source_changed".equals(unavailable.getMessage())
                    ? "source_changed" : "source_validation_failed";
            logTakeoverFailure(reason); return cancelled();
        } catch (IllegalStateException rejected) {
            current = null; logTakeoverFailure(ScopeCaptureWindow.diagnosticWindowReason(rejected.getMessage()));
            return cancelled();
        } catch (RuntimeException unavailable) {
            current = null; logTakeoverFailure("source_validation_failed"); return cancelled();
        }
        ScopeCaptureWindow.TransferResult transfer;
        try {
            int hostUid = context.get().getPackageManager().getApplicationInfo(HostPolicy.HOST_PACKAGE, 0).uid;
            transfer = windows.transferTouchToMaskDetailed(atms.get(), run.target, hostUid,
                    DragSettings.MASK_WINDOW_PREFIX + token);
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            current = null; logTakeoverFailure("host_package_missing"); return cancelled();
        } catch (RuntimeException unavailable) {
            current = null; logTakeoverFailure("system_runtime_failure"); return cancelled();
        }
        if (!transfer.transferred) {
            current = null; logTakeoverFailure(transfer.reason); return cancelled();
        }
        run.transferred = true;
        DragGestureGate.beginPhysical(0, run.gesture.device(), run.gesture.downAt());
        if (shouldSendDragFeedback(true, run.dragFeedbackSent)) {
            run.dragFeedbackSent = true;
            if (LongPressFeedback.vibrate(context.get(), true)) log("ready_haptic_requested");
            else log("ready_haptic_unavailable");
        }
        Bundle result = state(run); result.putBoolean("accepted", true);
        log("takeover_succeeded"); return result;
    }

    @Override public Bundle update(Bundle request, RuntimeSnapshot config) {
        Bundle result;
        boolean kick = false;
        synchronized (this) {
        Run run = current; long now = SystemClock.uptimeMillis();
        if (request == null || request.size() != 5 || !(request.get("token") instanceof String token)
                || !(request.get("action") instanceof Integer action) || !(request.get("pointers") instanceof Integer count)
                || !(request.get("x") instanceof Float x) || !(request.get("y") instanceof Float y)
                || run == null || !token.equals(run.token) || !run.transferred
                || run.gesture.released() || !Float.isFinite(x) || !Float.isFinite(y)) return cancelled();
        if (action == MotionEvent.ACTION_UP)
            DragGestureGate.releaseTransferred();
        if (!valid(run, config)) { cancelRun(run, "lease_invalid"); return cancelled(); }
        if (action == MotionEvent.ACTION_DOWN) return state(run);
        run.gesture.event(0, run.gesture.device(), run.gesture.downAt(), now, action, count, x, y, now);
        if (!run.gesture.valid()) {
            cancelRun(run, "takeover_cancelled"); return cancelled();
        }
        if (run.gesture.released()) {
            run.releasedAt = SystemClock.elapsedRealtime();
            DragGestureGate.releaseTransferred();
            if (run.direction() != null) {
                run.cardClickDue = true;
                log("released direction=" + run.direction());
                kick = true;
            }
        }
        result = state(run);
        }
        if (kick) notifyDirectedRelease();
        return result;
    }

    @Override public synchronized Bundle consume(Bundle request, RuntimeSnapshot config) {
        Run run = current;
        if (request == null || request.size() != 3 || !(request.get("token") instanceof String token)
                || !(request.get("source") instanceof String source) || !(request.get("direction") instanceof String direction)
                || run == null || !token.equals(run.token) || !valid(run, config) || run.consumed
                || !run.gesture.released() || !source.equals(run.target.source) || !direction.equals(run.direction())
                || "none".equals(config.drag.action(direction)) || SystemClock.elapsedRealtime() - run.releasedAt > 5_000)
            return Bundle.EMPTY;
        try { validateAfterPick(run); }
        catch (ReflectiveOperationException | RuntimeException changed) { return Bundle.EMPTY; }
        run.consumed = true;
        Bundle result = new Bundle(); result.putBoolean("accepted", true); result.putString("action", config.drag.action(direction));
        log("imported direction=" + direction); return result;
    }

    private boolean valid(Run run, RuntimeSnapshot config) {
        return run != null && config != null && config.enabled && config.drag.enabled && config.user == 0
                && run.config.identity.equals(config.identity) && config.sourceScope.allows(run.target.source)
                && run.gesture.valid() && unlocked(context.get())
                && (!DragGestureGate.suppressLongPress() || run.transferred)
                && SystemClock.elapsedRealtime() >= run.began && SystemClock.elapsedRealtime() - run.began <= 30_000;
    }

    static boolean shouldSendDragFeedback(boolean transferAccepted, boolean alreadySent) {
        return transferAccepted && !alreadySent;
    }

    private void validateAfterPick(Run run) throws ReflectiveOperationException {
        try {
            var tasks = context.get().getSystemService(android.app.ActivityManager.class).getRunningTasks(1);
            if (tasks == null || tasks.size() != 1 || tasks.get(0).topActivity == null
                    || !run.target.source.equals(tasks.get(0).topActivity.getPackageName())
                    || !run.target.activity.equals(tasks.get(0).topActivity.getClassName()))
                throw new ReflectiveOperationException("drag_source_changed");
            int uid = context.get().getPackageManager().getApplicationInfo(HostPolicy.HOST_PACKAGE, 0).uid;
            windows.validateBehindHost(atms.get(), run.target, uid);
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            throw new ReflectiveOperationException(unavailable);
        }
    }

    private Bundle state(Run run) {
        Bundle result = new Bundle(); result.putString("token", run.token); result.putString("source", run.target.source);
        result.putString("identity", run.config.identity); result.putFloat("x", run.gesture.x()); result.putFloat("y", run.gesture.y());
        result.putFloat("origin_x", run.target.x); result.putFloat("origin_y", run.target.y);
        result.putFloat("screen_width", run.width); result.putFloat("screen_height", run.height); result.putFloat("density", run.density);
        for (String side : DragSettings.DIRECTIONS) result.putString("action_" + side, run.config.drag.action(side));
        result.putString("state", run.gesture.released() ? "released" : "holding");
        String direction = run.direction();
        if (direction != null) { result.putString("direction", direction); result.putString("action", run.config.drag.action(direction)); }
        if (run.gesture.direction() != null) result.putBoolean("dragging", true);
        if (IntentObservation.validHttpUrl(run.cardLink)) result.putString("card_link", run.cardLink);
        result.putLong("native_at", run.stages.nativeAt());
        result.putLong("recognition_at", run.stages.recognitionAt());
        result.putLong("drag_at", run.stages.dragAt());
        return result;
    }

    public synchronized boolean fingerReleasedForLink() {
        Run run = current;
        return run != null && linkFallbackReady(run.gesture.released(), run.nativeUrlAvailable
                || IntentObservation.validHttpUrl(run.cardLink), SystemClock.elapsedRealtime(),
                run.stages.recognitionAt(), run.releasedAt);
    }

    public synchronized boolean ownsLinkFallback(String source) {
        Run run = current;
        return run != null && run.cardClickDue && run.target.source.equals(source);
    }

    public synchronized void nativeResult(String source, long occurredElapsed, boolean hasValidUrl) {
        Run run = current; long now = SystemClock.elapsedRealtime();
        if (run == null || !hasValidUrl || !run.target.source.equals(source) || occurredElapsed < run.began
                || occurredElapsed > now || now - occurredElapsed > 10_000) return;
        run.nativeUrlAvailable = true;
    }

    static boolean linkFallbackReady(boolean released, boolean nativeUrlAvailable, long now,
                                     long recognitionAt, long releasedAt) {
        return released && !nativeUrlAvailable && now >= recognitionAt && releasedAt >= 0
                && now >= releasedAt && now - releasedAt < 4_000;
    }

    private void notifyDirectedRelease() {
        Runnable listener = directedRelease;
        if (listener == null) log("card_click_unhooked");
        else listener.run();
    }

    private void cancelRun(Run run, String reason) {
        if (current != run) return;
        current = null;
        log(reason);
    }

    public synchronized void offerCardLink(String url) {
        Run run = current;
        if (run == null || !run.gesture.released() || run.direction() == null || !IntentObservation.validHttpUrl(url)) return;
        if (!cardLinkWithinGrace(run.releasedAt, SystemClock.elapsedRealtime())) return;
        run.cardLink = url;
        log("card_link");
    }

    static boolean cardLinkWithinGrace(long releasedAt, long now) {
        return releasedAt >= 0 && now >= releasedAt
                && now - releasedAt < ProbeContract.LINK_RESULT_GRACE_MILLIS;
    }

    public static boolean keepForCardLink(boolean directed, boolean hasLink, long sinceRelease) {
        return directed && !hasLink && sinceRelease >= 0 && sinceRelease < 4_000;
    }

    private void requestCardLink(Run run) {
        CardLinks links = cardLinks;
        if (run.linkRequested || links == null) return;
        run.linkRequested = true;
        try {
            links.capture(run.target, run.config, url -> {
                synchronized (SystemLongPressDrag.this) {
                    if (current != run || !IntentObservation.validHttpUrl(url)) return;
                    run.cardLink = url;
                    log("card_link");
                }
            });
        } catch (RuntimeException unavailable) { log("card_link_failed"); }
    }
    private static Bundle cancelled() { Bundle result = new Bundle(); result.putString("state", "cancelled"); return result; }
    static boolean ignoreTransferredDown(boolean transferred, boolean released, int gestureDevice, int eventDevice,
                                         long gestureDownAt, long eventDownAt) {
        return transferred && !released && gestureDevice == eventDevice && gestureDownAt == eventDownAt;
    }
    static boolean shouldResetTransferredOnDown(boolean transferred, int gestureDisplay, int gestureDevice,
                                                long gestureDownAt, int display, int eventDevice, long eventDownAt,
                                                int action) {
        return transferred && action == MotionEvent.ACTION_DOWN && display == gestureDisplay
                && eventDevice == gestureDevice && eventDownAt != gestureDownAt;
    }
    private static boolean unlocked(Context context) {
        if (context == null) return false;
        var keyguard = context.getSystemService(KeyguardManager.class); var power = context.getSystemService(PowerManager.class);
        var users = context.getSystemService(UserManager.class);
        return keyguard != null && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked()
                && power != null && power.isInteractive() && users != null && users.isUserUnlocked();
    }
    private void log(String stage) { module.log(4, "TaplusBridge", "drag gesture stage=" + stage); }
    private void logTakeoverFailure(String reason) {
        module.log(5, "TaplusBridge", "drag gesture stage=takeover_failed reason=" + reason);
    }
}
