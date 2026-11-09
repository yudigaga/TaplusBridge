package io.github.yudigaga.taplusbridge.drag;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.hook.ScopeCapturePayload;
import io.github.yudigaga.taplusbridge.probe.ScopeCaptureWindow;
import io.github.yudigaga.taplusbridge.probe.PhysicalTouchState;
import io.github.yudigaga.taplusbridge.probe.ProbeContract;
import io.github.yudigaga.taplusbridge.runtime.RuntimeServer;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import io.github.yudigaga.taplusbridge.runtime.CaptureIo;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import java.security.SecureRandom;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One physical direction gesture owns native or fallback content through its final release. */
public final class SystemLongPressDrag implements RuntimeServer.DragGestures {
    public static final String ACTION_PREPARE = "io.github.yudigaga.taplusbridge.PREPARE_LONG_PRESS_DRAG";
    private static final long RELEASE_GRACE_MILLIS = ProbeContract.LINK_RESULT_GRACE_MILLIS + 1_000;
    private final XposedModule module;
    private final Handler worker;
    private final Supplier<Context> context;
    private final Supplier<Object> atms;
    private final Supplier<RuntimeServer> runtime;
    private final ScopeCaptureWindow windows;
    private final PhysicalTouchState physical;
    private final java.util.concurrent.ThreadPoolExecutor writer = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1), task -> {
        Thread thread = new Thread(task, "TaplusBridge-drag-capture"); thread.setDaemon(true); return thread;
    });
    private Run current;
    private record InputState(Run owner, int device, long downAt, boolean released) {}
    private final java.util.concurrent.atomic.AtomicReference<InputState> input = new java.util.concurrent.atomic.AtomicReference<>();

    private void publishInput() {
        Run run = current;
        if (run == null || !run.transferred) { input.set(null); return; }
        InputState previous = input.get();
        InputState next = new InputState(run, run.gesture.device(), run.gesture.downAt(), run.gesture.released());
        if (!run.inputRevoked) {
            input.compareAndSet(previous, next);
            if (run.inputRevoked) input.compareAndSet(next, null);
        }
    }

    private void replaceCurrent(Run replacement) {
        Run previous = current;
        current = replacement;
        if (previous != null && previous != replacement && previous.captureWrite != null) {
            writer.remove(previous.captureWrite); previous.captureWrite.close(); previous.captureWrite = null;
        }
        publishInput();
    }
    private Runnable directedRelease;
    private CardLinks cardLinks;
    private volatile io.github.yudigaga.taplusbridge.probe.SystemAssistContent assistContent;
    public void setAssistContent(io.github.yudigaga.taplusbridge.probe.SystemAssistContent content) { assistContent = content; }
    public void setOnDirectedRelease(Runnable listener) { directedRelease = listener; }

    /** Reads the card URL the same way a stationary long press does, after the finger is up. */
    public interface CardLinks {
        void capture(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, Consumer<String> url);
        default void captureFromGesture(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long downUptime,
                                        long recognitionAt, long releasedAt, Consumer<String> url) {
            capture(target, config, url);
        }
    }

    public void setCardLinks(CardLinks cardLinks) { this.cardLinks = cardLinks; }

    private static final class Run {
        final ScopeCaptureWindow.Snapshot target;
        final RuntimeSnapshot config;
        final LongPressDirection gesture;
        final long began;
        final LongPressStageSchedule.Deadlines stages;
        final float tolerance, touchSlop;
        final float width, height, density;
        String token;
        String lastEdge;
        String cardLink;
        boolean linkRequested;
        boolean linkComplete;
        boolean nativeUrlAvailable;
        boolean consumed;
        boolean transferred;
        volatile boolean inputRevoked;
        CaptureIo.Write captureWrite;
        boolean sourceMovedBeforeTransfer;
        boolean dragFeedbackSent;
        boolean prepareStarted;
        boolean prepared;
        boolean cardClickDue;
        long releasedAt;
        Run(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, LongPressDirection gesture, long began,
            LongPressStageSchedule.Deadlines stages, float tolerance, float touchSlop,
            float width, float height, float density) {
            this.target = target; this.config = config; this.gesture = gesture; this.began = began; this.tolerance = tolerance;
            this.touchSlop = touchSlop;
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
        this(module, worker, context, atms, runtime, atmsType, null);
    }

    public SystemLongPressDrag(XposedModule module, Handler worker, Supplier<Context> context,
                               Supplier<Object> atms, Supplier<RuntimeServer> runtime, Class<?> atmsType,
                               PhysicalTouchState physical) throws ReflectiveOperationException {
        this(module, worker, context, atms, runtime, new ScopeCaptureWindow(atmsType), physical);
    }

    SystemLongPressDrag(XposedModule module, Handler worker, Supplier<Context> context,
                        Supplier<Object> atms, Supplier<RuntimeServer> runtime, ScopeCaptureWindow windows,
                        PhysicalTouchState physical) {
        this.module = module; this.worker = worker; this.context = context; this.atms = atms; this.runtime = runtime;
        this.windows = windows; this.physical = physical;
    }

    /** MotionEvent is owned by the dispatcher; only copy primitives before returning. */
    public void pointer(MotionEvent event, int display, boolean exclusive) {
        int action = event.getActionMasked(), count = event.getPointerCount(), device = event.getDeviceId();
        long down = event.getDownTime(), at = event.getEventTime();
        float x = event.getRawX(), y = event.getRawY();
        worker.post(() -> event(display, device, down, at, action, count, x, y, exclusive));
    }

    public boolean transferredPointer(int display, int device, long downAt, int action) {
        InputState state = input.get();
        if (state == null || state.owner().inputRevoked) return false;
        boolean sameStream = shouldIgnoreTransferredPointer(true, display, state.device(), device,
                state.downAt(), downAt, state.released(), action);
        if (sameStream) return true;
        if (shouldResetTransferredOnDown(true, 0, state.device(), state.downAt(), display, device, downAt, action)) {
            // Revoke the owner even if an update concurrently replaced its immutable input snapshot.
            state.owner().inputRevoked = true;
            input.compareAndSet(state, null);
            worker.post(() -> {
                synchronized (this) {
                    if (current == state.owner()) {
                        replaceCurrent(null);
                        log("orphaned_takeover_reset reason=new_physical_down");
                    }
                }
            });
        }
        return false;
    }

    public boolean transferred() {
        InputState state = input.get(); return state != null && !state.owner().inputRevoked;
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
                    replaceCurrent(null);
                    log("orphaned_takeover_reset reason=new_physical_down");
                }
                replaceCurrent(null);
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
                replaceCurrent(new Run(target, config, new LongPressDirection(down, device, x, y, timeout, slop, threshold),
                        began, stages, Math.max(slop * 2, 24 * metrics.density), slop,
                        metrics.widthPixels, metrics.heightPixels, metrics.density));
                Run pending = current;
                var content = assistContent;
                if (content != null && config.recognition) content.arm(target, config, down, began, stages.recognitionAt(), device);
                worker.postDelayed(() -> startPrepare(pending), Math.max(0, stages.recognitionAt() - nowElapsed));
                worker.postDelayed(() -> expire(pending), 30_001);
                return;
            }
            Run run = current;
            if (run == null || run.transferred || run.gesture.released()) return;
            // Before takeover the source still receives movement and may scroll beneath the saved point.
            if (display == 0 && device == run.gesture.device() && down == run.gesture.downAt()
                    && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                    && Math.hypot(x - run.target.x, y - run.target.y) > run.touchSlop)
                run.sourceMovedBeforeTransfer = true;
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
            replaceCurrent(null);
            // Normal touches on menus or protected windows are not eligible candidates.
        }
        }
        if (kick) notifyDirectedRelease();
    }

    private RuntimeSnapshot configuration() {
        RuntimeServer server = runtime.get(); return server == null ? null : server.dragConfiguration();
    }

    private boolean held(Run run, RuntimeSnapshot config) {
        return current == run && valid(run, config) && !run.gesture.released() && !run.transferred
                && physical != null && physical.soleStream(0, run.gesture.device(), run.gesture.downAt());
    }

    private synchronized void startPrepare(Run run) {
        if (run.prepareStarted || !held(run, configuration())) return;
        if (SystemClock.elapsedRealtime() < run.stages.recognitionAt()) return;
        try {
            Context system = context.get();
            var host = system.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE, 0);
            if (host.applicationInfo == null || !host.applicationInfo.enabled
                    || !HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode())) return;
            windows.validateBehindHost(atms.get(), run.target, host.applicationInfo.uid);
            ensureToken(run);
            run.prepareStarted = true;
            Intent start = new Intent(ACTION_PREPARE).setComponent(new ComponentName(HostPolicy.HOST_PACKAGE,
                    "com.miui.contentextension.services.TextContentExtensionService"));
            start.putExtra("drag_token", run.token);
            if (system.startService(start) == null) { cancelRun(run, "prepare_service_unavailable"); return; }
            log("prepare_requested");
        } catch (android.content.pm.PackageManager.NameNotFoundException | ReflectiveOperationException | RuntimeException unavailable) {
            cancelRun(run, "prepare_unavailable");
        }
    }

    private synchronized void expire(Run run) {
        if (current != run) return;
        long now = SystemClock.elapsedRealtime();
        long until = run.gesture.released() ? run.releasedAt + RELEASE_GRACE_MILLIS : run.began + 30_001;
        if (now >= until) cancelRun(run, "expired");
        else worker.postDelayed(() -> expire(run), until - now);
    }

    private void ensureToken(Run run) {
        if (run.token != null) return;
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        run.token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    @Override public Bundle prepare(Bundle request, RuntimeSnapshot config) {
        final Run run;
        synchronized (this) {
            run = current;
            if (request == null || request.size() != 1 || !(request.get("token") instanceof String token)
                    || run == null || !token.equals(run.token) || run.prepared || !held(run, config)
                    || SystemClock.elapsedRealtime() < run.stages.recognitionAt()) return Bundle.EMPTY;
            run.prepared = true;
        }
        Bitmap image = null;
        ParcelFileDescriptor[] pipe = null;
        try {
            Context system = context.get();
            int hostUid = system.getPackageManager().getApplicationInfo(HostPolicy.HOST_PACKAGE, 0).uid;
            windows.validateBehindHost(atms.get(), run.target, hostUid);
            Bundle capture = captureMetadata(run);
            if (config.scopeCapture) {
                try {
                    image = windows.captureBehindHost(atms.get(), run.target, hostUid);
                    var output = new BoundedPng();
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("capture_encode");
                    byte[] png = output.toByteArray();
                    if (png.length <= 0) throw new IllegalStateException("capture_encode");
                    pipe = ParcelFileDescriptor.createPipe();
                    CaptureIo.Write sending = new CaptureIo.Write(new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]), png);
                    synchronized (this) {
                        if (current != run) { sending.close(); return Bundle.EMPTY; }
                        run.captureWrite = sending;
                        writer.execute(sending);
                    }
                    CaptureIo.expireReadEnd(pipe[0]);
                    capture.putString("kind", "image"); capture.putInt("bytes", png.length);
                    capture.putParcelable("fd", pipe[0]);
                } catch (ReflectiveOperationException | IOException | RuntimeException unavailable) {
                    closePipe(pipe); pipe = null;
                    windows.validateBehindHost(atms.get(), run.target, hostUid);
                    capture = captureMetadata(run);
                    log("prepare_image_unavailable");
                }
            }
            ScopeCapturePayload.validate(capture, SystemClock.elapsedRealtime());
            synchronized (this) {
                if (!held(run, configuration()) || !run.config.identity.equals(config.identity)) return Bundle.EMPTY;
                Bundle result = state(run); result.putBundle("capture", capture);
                pipe = null;
                log("image".equals(capture.getString("kind")) ? "prepare_image" : "prepare_metadata");
                return result;
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException | ReflectiveOperationException | RuntimeException unavailable) {
            synchronized (this) { cancelRun(run, "prepare_capture_unavailable"); }
            return Bundle.EMPTY;
        } finally {
            if (image != null && !image.isRecycled()) image.recycle();
            closePipe(pipe);
        }
    }

    private static void closePipe(ParcelFileDescriptor[] pipe) {
        if (pipe != null) for (ParcelFileDescriptor end : pipe) try { end.close(); } catch (IOException ignored) { }
    }

    private Bundle captureMetadata(Run run) {
        var target = run.target;
        Bundle result = new Bundle();
        result.putString("source", target.source); result.putString("activity", target.activity);
        result.putInt("uid", target.uid); result.putInt("user", target.user); result.putInt("display", target.display);
        result.putFloat("x", target.x); result.putFloat("y", target.y);
        result.putInt("left", target.bounds.left); result.putInt("top", target.bounds.top);
        result.putInt("right", target.bounds.right); result.putInt("bottom", target.bounds.bottom);
        result.putInt("width", Math.round(run.width)); result.putInt("height", Math.round(run.height));
        result.putLong("captured_elapsed", SystemClock.elapsedRealtime());
        result.putString("scope_identity", run.config.identity);
        result.putString("kind", "resume"); result.putInt("bytes", 0);
        return result;
    }

    @Override public synchronized Bundle requestLink(Bundle request, RuntimeSnapshot config) {
        Run run = current;
        if (request == null || request.size() != 1 || !(request.get("token") instanceof String token)
                || run == null || !token.equals(run.token) || !valid(run, config) || !config.scopeLinkProbe
                || !run.gesture.released() || run.direction() == null || run.linkRequested || cardLinks == null
                || run.sourceMovedBeforeTransfer
                || run.nativeUrlAvailable || io.github.yudigaga.taplusbridge.recognition.RouteLink.valid(run.cardLink)
                || physical == null || !physical.released()
                || physical.releasedAt() < run.gesture.downAt()
                || physical.releasedElapsedAt() < run.stages.recognitionAt()
                || !linkFallbackReady(true, false, SystemClock.elapsedRealtime(), run.stages.recognitionAt(), run.releasedAt))
            return linkDeclined();
        try { validateAfterPick(run); }
        catch (ReflectiveOperationException | RuntimeException changed) { return linkDeclined(); }
        requestCardLink(run);
        Bundle result = state(run); result.putBoolean("accepted", true); return result;
    }

    private static Bundle linkDeclined() { Bundle result = new Bundle(); result.putBoolean("accepted", false); return result; }

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
        if (run.inputRevoked) return Bundle.EMPTY;
        if (run.token == null) {
            ensureToken(run);
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
            if (!keepForCardLink(run.direction() != null, io.github.yudigaga.taplusbridge.recognition.RouteLink.valid(run.cardLink), sinceRelease)) {
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
            replaceCurrent(null);
            String reason = "drag_source_changed".equals(unavailable.getMessage())
                    ? "source_changed" : "source_validation_failed";
            logTakeoverFailure(reason); return cancelled();
        } catch (IllegalStateException rejected) {
            replaceCurrent(null); logTakeoverFailure(ScopeCaptureWindow.diagnosticWindowReason(rejected.getMessage()));
            return cancelled();
        } catch (RuntimeException unavailable) {
            replaceCurrent(null); logTakeoverFailure("source_validation_failed"); return cancelled();
        }
        ScopeCaptureWindow.TransferResult transfer;
        try {
            int hostUid = context.get().getPackageManager().getApplicationInfo(HostPolicy.HOST_PACKAGE, 0).uid;
            transfer = windows.transferTouchToMaskDetailed(atms.get(), run.target, hostUid,
                    DragSettings.MASK_WINDOW_PREFIX + token);
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            replaceCurrent(null); logTakeoverFailure("host_package_missing"); return cancelled();
        } catch (RuntimeException unavailable) {
            replaceCurrent(null); logTakeoverFailure("system_runtime_failure"); return cancelled();
        }
        if (!transfer.transferred) {
            replaceCurrent(null); logTakeoverFailure(transfer.reason); return cancelled();
        }
        run.transferred = true;
        publishInput();
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
                || run == null || !token.equals(run.token) || !run.transferred || run.inputRevoked
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
        publishInput();
        if (action == MotionEvent.ACTION_MOVE) {
            run.direction(); // Records a visited edge even when no poll occurs before an inward release.
            result = new Bundle(); result.putString("state", "holding");
        } else result = state(run);
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
                || "none".equals(config.drag.action(direction))
                || SystemClock.elapsedRealtime() - run.releasedAt >= RELEASE_GRACE_MILLIS)
            return Bundle.EMPTY;
        try { validateAfterPick(run); }
        catch (ReflectiveOperationException | RuntimeException changed) { return Bundle.EMPTY; }
        run.consumed = true;
        Bundle result = new Bundle(); result.putBoolean("accepted", true); result.putString("action", config.drag.action(direction));
        log("imported direction=" + direction); return result;
    }

    private boolean valid(Run run, RuntimeSnapshot config) {
        long now = SystemClock.elapsedRealtime();
        return run != null && !run.inputRevoked && config != null && config.enabled && config.drag.enabled && config.user == 0
                && run.config.identity.equals(config.identity) && config.sourceScope.allows(run.target.source)
                && run.gesture.valid() && unlocked(context.get())
                && (!DragGestureGate.suppressLongPress() || run.transferred)
                && now >= run.began && (run.gesture.released()
                    ? now >= run.releasedAt && now - run.releasedAt < RELEASE_GRACE_MILLIS
                    : now - run.began <= 30_000) && !run.inputRevoked;
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
            if (run.inputRevoked) throw new ReflectiveOperationException("drag_source_changed");
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            throw new ReflectiveOperationException(unavailable);
        }
    }

    private Bundle state(Run run) {
        Bundle result = new Bundle(); result.putString("token", run.token); result.putString("source", run.target.source);
        result.putString("identity", run.config.identity); result.putFloat("x", run.gesture.x()); result.putFloat("y", run.gesture.y());
        var content = assistContent;
        if (content != null && !run.sourceMovedBeforeTransfer) {
            String text = content.read(run.target, run.config, run.began, run.gesture.downAt()).deliveryText();
            if (!text.isBlank()) result.putString("source_text", text);
        }
        result.putFloat("origin_x", run.target.x); result.putFloat("origin_y", run.target.y);
        result.putFloat("screen_width", run.width); result.putFloat("screen_height", run.height); result.putFloat("density", run.density);
        for (String side : DragSettings.DIRECTIONS) result.putString("action_" + side, run.config.drag.action(side));
        result.putString("state", run.gesture.released() ? "released" : "holding");
        String direction = run.direction();
        if (direction != null) { result.putString("direction", direction); result.putString("action", run.config.drag.action(direction)); }
        if (run.gesture.direction() != null) result.putBoolean("dragging", true);
        if (io.github.yudigaga.taplusbridge.recognition.RouteLink.valid(run.cardLink)) result.putString("card_link", run.cardLink);
        result.putLong("native_at", run.stages.nativeAt());
        result.putLong("recognition_at", run.stages.recognitionAt());
        result.putLong("drag_at", run.stages.dragAt());
        result.putBoolean("link_probe", run.config.scopeLinkProbe);
        result.putBoolean("link_complete", run.linkComplete);
        return result;
    }

    public synchronized boolean fingerReleasedForLink() {
        Run run = current;
        return run != null && !run.sourceMovedBeforeTransfer && linkFallbackReady(run.gesture.released(), run.nativeUrlAvailable
                || io.github.yudigaga.taplusbridge.recognition.RouteLink.valid(run.cardLink), SystemClock.elapsedRealtime(),
                run.stages.recognitionAt(), run.releasedAt);
    }

    /** Native probes must not reuse source geometry after this gesture could have scrolled it. */
    public synchronized boolean sourcePointStable(String source, long pickedElapsed) {
        Run run = current;
        return run == null || !run.target.source.equals(source) || pickedElapsed < run.began
                || !run.sourceMovedBeforeTransfer;
    }

    public synchronized boolean ownsLinkFallback(String source) {
        Run run = current;
        return run != null && run.cardClickDue && run.target.source.equals(source);
    }

    public synchronized void nativeResult(String source, long occurredElapsed, boolean hasValidUrl) {
        Run run = current; long now = SystemClock.elapsedRealtime();
        if (run == null || !run.config.channels.enabled(io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel.NATIVE)
                || !hasValidUrl || !run.target.source.equals(source) || occurredElapsed < run.began
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
        replaceCurrent(null);
        log(reason);
    }

    public synchronized void offerCardLink(String url) {
        Run run = current;
        if (run == null || !run.gesture.released() || run.direction() == null || !io.github.yudigaga.taplusbridge.recognition.RouteLink.valid(url)) return;
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
            links.captureFromGesture(run.target, run.config, run.gesture.downAt(), run.stages.recognitionAt(), run.releasedAt, url -> {
                synchronized (SystemLongPressDrag.this) {
                    if (current != run || run.linkComplete) return;
                    run.linkComplete = true;
                    if (io.github.yudigaga.taplusbridge.recognition.RouteLink.valid(url)) {
                        run.cardLink = url;
                        log("card_link");
                    }
                }
            });
        } catch (RuntimeException unavailable) { run.linkComplete = true; log("card_link_failed"); }
    }

    private static final class BoundedPng extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            if (count >= ScopeCapturePayload.MAX_BYTES) throw new IllegalStateException("capture_image_size");
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length < 0 || length > ScopeCapturePayload.MAX_BYTES - count) throw new IllegalStateException("capture_image_size");
            super.write(bytes, offset, length);
        }
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
    private void log(String stage) { if (module != null) module.log(4, "TaplusBridge", "drag gesture stage=" + stage); }
    private void logTakeoverFailure(String reason) {
        if (module != null) module.log(5, "TaplusBridge", "drag gesture stage=takeover_failed reason=" + reason);
    }
}
