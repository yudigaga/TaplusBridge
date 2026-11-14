package io.github.yudigaga.taplusbridge.probe;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.capture.AtmsSignature;
import io.github.yudigaga.taplusbridge.capture.CaptureBroker;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import io.github.yudigaga.taplusbridge.drag.DragGestureGate;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic, physical-UP-gated synthetic-card probe. Consumes a public Binder request before its body creates
 * any ActivityStarter/SafeActivityOptions; it does not fake success inside ActivityStarter.execute.
 * Public launch overloads share the same gate; navigation inside an existing Activity remains outside coverage.
 */
public final class SystemProbe {
    private static final String TAG = "TaplusBridge";
    private static final Uri PROVIDER = Uri.parse("content://" + BridgeContract.AUTHORITY);
    private static final long MAX_RECOGNITION_LEAD_MILLIS = 20_000;
    private final XposedModule module;
    private final Handler worker;
    private final Object lock = new Object();
    private final ProbeGate gate = new ProbeGate();
    private final PhysicalTouchState physical = new PhysicalTouchState();
    private final ProbePointEcho pointEcho = new ProbePointEcho();
    private final AtomicBoolean physicalFailureReported = new AtomicBoolean();
    private final AtomicBoolean physicalReadFailureReported = new AtomicBoolean();
    private volatile Session session;
    private volatile Context system;
    private volatile InputAdapter input;
    private volatile io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag longPressDrag;
    private volatile io.github.yudigaga.taplusbridge.runtime.RuntimeServer runtime;
    private SystemScopeCapture scopeCapture;
    private SystemAssistContent assistContent;
    private volatile Object atmsInstance;
    private Class<?> activityTaskManagerType;
    private SourceWindowAccess windows;
    private SourceWindowAccess.CardPlan cardPlan;
    private int hostUid = -1;
    private Method optionsFromBundle, optionsAbort;
    private boolean armRegistered, tapRegistered, cancelRegistered;
    private boolean linkLaunchReady;

    private record Snapshot(String activity, float x, float y, int left, int top, int right, int bottom,
                            int width, int height, int rotation, int display, long capturedAt) {
        static Snapshot read(Intent command) {
            return new Snapshot(command.getStringExtra("source_activity"), command.getFloatExtra("x", Float.NaN),
                    command.getFloatExtra("y", Float.NaN), command.getIntExtra("left", -1), command.getIntExtra("top", -1),
                    command.getIntExtra("right", -1), command.getIntExtra("bottom", -1), command.getIntExtra("width", -1),
                    command.getIntExtra("height", -1), command.getIntExtra("rotation", -1), command.getIntExtra("display", -1),
                    command.getLongExtra("captured_elapsed", -1));
        }
        boolean valid(String source, long now) {
            return activity != null && activity.length() <= 256 && activity.startsWith(source + ".")
                    && Float.isFinite(x) && Float.isFinite(y) && width > 0 && height > 0 && width <= 10000 && height <= 10000
                    && left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height
                    && x >= left && x < right && y >= top && y < bottom && display == 0 && rotation >= 0 && rotation <= 3
                    && capturedAt >= 0 && now >= capturedAt && now - capturedAt < 30000;
        }
    }
    private record Session(ProbeGate.Ticket ticket, Snapshot snapshot, ResultReceiver callback,
                           boolean autonomous, long recognitionAt) { }
    record Captured(String url, String reason, IntentObservation.Observation metadata, long observedAt, boolean forResult) { }
    record LaunchClaim(ProbeGate.Decision decision, long generation, Captured captured) { }
    private static final class Rejected extends IllegalStateException {
        final String reason;
        Rejected(String reason) { super(reason); this.reason = reason; }
    }

    public static void install(XposedModule module, ClassLoader loader) {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            module.log(5, TAG, "Invisible probe unavailable: Android version"); return;
        }
        SystemProbe probe = null;
        try { probe = new SystemProbe(module); probe.start(loader); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            if (probe != null) probe.worker.getLooper().quitSafely();
            module.log(5, TAG, "Invisible probe unavailable: " + failure.getClass().getSimpleName());
        }
    }
    private SystemProbe(XposedModule module) {
        this.module = module;
        HandlerThread thread = new HandlerThread("TaplusBridge-probe"); thread.start();
        worker = new Handler(thread.getLooper());
    }
    // This entry is installed only in system_server, not under the module application's hidden-API
    // enforcement domain. Resolve every required method before advertising READY; absence disables input.
    @android.annotation.SuppressLint({"SoonBlockedPrivateApi", "BlockedPrivateApi"})
    private void start(ClassLoader loader) throws ReflectiveOperationException {
        Method getDisplay = InputEvent.class.getDeclaredMethod("getDisplayId");
        getDisplay.setAccessible(true);
        Method pointer = loader.loadClass("com.android.server.wm.PointerEventDispatcher")
                .getDeclaredMethod("onInputEvent", InputEvent.class);
        if (pointer.getReturnType() != void.class) throw new NoSuchMethodException("pointer_dispatch");
        pointer.setAccessible(true);
        module.hook(pointer).setId("taplusbridge:physical-pointer-state")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Integer readDevice = null;
                    try {
                        if (chain.getArg(0) instanceof MotionEvent motion
                                && PhysicalTouchState.isTouchPointer(motion.getSource(), motion.getActionMasked())) {
                            readDevice = motion.getDeviceId();
                            int display = (Integer) getDisplay.invoke(motion);
                            if(physical.isInjected(display,readDevice,motion.getDownTime(),SystemClock.uptimeMillis())){
                                String echoed=pointEcho.observe(display,motion.getDownTime(),motion.getActionMasked(),motion.getRawX(),motion.getRawY());
                                if(!"unrelated".equals(echoed))module.log(4,TAG,"probe stage=point_"+echoed);
                            }
                            boolean realMainInput = display == 0 && !physical.isInjected(display, readDevice,
                                    motion.getDownTime(), SystemClock.uptimeMillis());
                            if (realMainInput && motion.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                DragGestureGate.physicalDown(display, readDevice, motion.getDownTime());
                            }
                            if (realMainInput && motion.getActionMasked() == MotionEvent.ACTION_UP)
                                DragGestureGate.physicalUp(display, readDevice, motion.getDownTime());
                            var directional = longPressDrag;
                            boolean transferredStream = directional != null && directional.transferredPointer(display, readDevice,
                                    motion.getDownTime(), motion.getActionMasked());
                            if (transferredStream) {
                                recordPhysicalTouch(physical, display, readDevice, motion.getDownTime(),
                                        motion.getEventTime(), motion.getActionMasked(), SystemClock.elapsedRealtime(),
                                        SystemClock.uptimeMillis());
                                return chain.proceed();
                            }
                            if (realMainInput && motion.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                if (assistContent != null) assistContent.cancel();
                                Session previous = session;
                                if (previous != null) worker.post(() -> fail(previous, "source_changed"));
                            }
                            boolean soleBefore = physical.soleStream(display, readDevice, motion.getDownTime());
                            recordPhysicalTouch(physical, display, readDevice, motion.getDownTime(),
                                    motion.getEventTime(), motion.getActionMasked(), SystemClock.elapsedRealtime(),
                                    SystemClock.uptimeMillis());
                            boolean dragging = DragGestureGate.suppressLongPress();
                            SystemScopeCapture scoped = scopeCapture;
                            if (directional != null && (motion.getSource() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                                    && !physical.isInjected(display, readDevice, motion.getDownTime(), SystemClock.uptimeMillis()))
                                directional.pointer(motion, display, motion.getActionMasked() == MotionEvent.ACTION_UP
                                        ? soleBefore && physical.released()
                                        : physical.soleStream(display, readDevice, motion.getDownTime()));
                            if (scoped != null && (motion.getSource() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                                    && !physical.isInjected(display, readDevice, motion.getDownTime(), SystemClock.uptimeMillis()))
                                scoped.pointer(motion, display, physical.soleStream(display, readDevice, motion.getDownTime()));
                            Session waiting = session;
                            if (dragging && waiting != null) {
                                worker.post(() -> fail(waiting, "dragging"));
                            } else if(physical.unavailable()) {
                                pointerUnknown(waiting);
                            } else if (motion.getActionMasked() == MotionEvent.ACTION_UP && waiting != null
                                    && gate.awaitingRelease(waiting.ticket())) worker.post(() -> release(waiting));
                            if (realMainInput && motion.getActionMasked() == MotionEvent.ACTION_UP
                                    && (directional == null || !directional.transferred()))
                                DragGestureGate.physicalInputUp();
                        }
                    } catch (Throwable unreadable) {
                        if (readDevice == null) physical.unknown();
                        else physical.uncertainDevice(readDevice, SystemClock.uptimeMillis());
                        if (physicalReadFailureReported.compareAndSet(false, true)) {
                            String type = unreadable instanceof java.lang.reflect.InvocationTargetException ? "InvocationTargetException"
                                    : unreadable instanceof ReflectiveOperationException ? "ReflectiveOperationException"
                                    : unreadable instanceof SecurityException ? "SecurityException"
                                    : unreadable instanceof RuntimeException ? "RuntimeException" : "Throwable";
                            module.log(5, TAG, "probe stage=physical_read reason=touch_read_failed exception=" + type
                                    + "; device_known=" + (readDevice != null));
                        }
                        if (physical.unavailable()) pointerUnknown(session);
                    }
                    return chain.proceed();
                });
        Class<?> atms = loader.loadClass("com.android.server.wm.ActivityTaskManagerService");
        activityTaskManagerType = atms;
        try {
            longPressDrag = new io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag(module, worker,
                    () -> system, () -> atmsInstance, () -> runtime, atms, physical);
            longPressDrag.setOnDirectedRelease(() -> {
                if (android.os.Looper.myLooper() == worker.getLooper()) releaseWaitingProbe();
                else worker.postAtFrontOfQueue(this::releaseWaitingProbe);
            });
            module.log(4, TAG, "drag gesture stage=installed");
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(5, TAG, "drag gesture stage=unavailable exception=" + unavailable.getClass().getSimpleName());
        }
        try {
            scopeCapture = new SystemScopeCapture(module, worker, () -> system, () -> atmsInstance, () -> runtime, atms, physical);
            scopeCapture.setMenuNavigationGuardReady(MenuKeyInputHooks.install(module,loader,()->scopeCapture));
            module.log(4, TAG, "Scope capture system hooks installed");
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(5, TAG, "Scope capture system unavailable: " + unavailable.getClass().getSimpleName());
        }
        windows = new SourceWindowAccess(atms);
        try {
            assistContent = new SystemAssistContent(worker, () -> system, () -> atmsInstance,
                    () -> runtime == null ? null : runtime.sourceContentConfiguration(), new ScopeCaptureWindow(atms),
                    message -> module.log(4, TAG, message), physical);
            if (scopeCapture != null) scopeCapture.setAssistContent(assistContent);
            if (longPressDrag != null) longPressDrag.setAssistContent(assistContent);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(5, TAG, "assist capture stage=unavailable");
        }
        linkLaunchReady = ScopeLinkLaunchHooks.install(module, atms,
                () -> scopeCapture == null ? null : scopeCapture.linkProbe(), this::interceptHostLaunch) > 0;
        Method entry = null;
        for (Method method : atms.getDeclaredMethods()) {
            if (!AtmsSignature.matches(method) || Modifier.isSynchronized(method.getModifiers())) continue;
            if (entry != null) throw new NoSuchMethodException("Ambiguous public Binder entry");
            entry = method;
        }
        if (entry == null) throw new NoSuchMethodException("Public Binder entry missing");
        optionsFromBundle = ActivityOptions.class.getDeclaredMethod("fromBundle", Bundle.class);
        optionsAbort = ActivityOptions.class.getDeclaredMethod("abort");
        optionsFromBundle.setAccessible(true); optionsAbort.setAccessible(true); entry.setAccessible(true);
        module.hook(entry).setId("taplusbridge:invisible-probe-binder")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (atmsInstance == null) atmsInstance = chain.getThisObject();
                    int callerUid = Binder.getCallingUid();
                    long identity = Binder.clearCallingIdentity();
                    boolean consumed;
                    try {
                        consumed = interceptHostLaunch(callerUid, (String) chain.getArg(1),
                                (Integer) chain.getArg(11), (Intent) chain.getArg(3),
                                chain.getArg(5) != null && (Integer) chain.getArg(7) >= 0);
                    } finally { Binder.restoreCallingIdentity(identity); }
                    if (!consumed) return chain.proceed();
                    try {
                        Bundle options = (Bundle) chain.getArg(10);
                        if (options != null) {
                            Object decoded = optionsFromBundle.invoke(null, options);
                            if (decoded != null) optionsAbort.invoke(decoded);
                        }
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        module.log(5, TAG, "Probe options cancellation limited: " + failure.getClass().getSimpleName());
                    }
                    // Caller-visible success is valid here: no ActivityStarter or ActivityRecord exists.
                    return 0;
                });
        worker.post(() -> register(0));
        module.log(4, TAG, "Invisible probe public Binder hook installed; source launch consumed");
    }

    static LaunchClaim claimLaunch(ProbeGate gate, int uid, String source, int user, Intent intent, boolean forResult) {
        synchronized (gate) {
            ComponentName component = intent == null ? null : intent.getComponent();
            boolean known = component != null && CaptureLease.expectedDestination(source,
                    component.getPackageName(), component.getClassName());
            String candidate = gate.activeFor(source, uid) ? ScopeLinkProbe.strictHttp(intent, source) : null;
            ProbeGate.RequestedUser requested = gate.requestedUser(source, uid, user);
            long now = SystemClock.elapsedRealtime();
            ProbeGate.Decision decision = gate.claim(source, uid, requested.boundUser(), known, fingerprint(candidate), now);
            if (decision != ProbeGate.Decision.CAPTURE) return new LaunchClaim(decision, -1, null);
            String url = null, reason = "unsupported_request";
            IntentObservation.Observation metadata = null;
            try {
                metadata = IntentObservation.inspect(intent);
                if (requested.supported()) {
                    url = candidate; reason = url == null ? "no_unique_url" : "captured";
                }
            } catch (RuntimeException unreadable) { reason = "unreadable"; }
            return new LaunchClaim(decision, gate.current().generation(), new Captured(url, reason, metadata, now, forResult));
        }
    }

    private boolean interceptHostLaunch(int uid, String source, int user, Intent intent, boolean forResult) {
        LaunchClaim claim;
        Session owner;
        synchronized (lock) {
            claim = claimLaunch(gate, uid, source, user, intent, forResult);
            owner = session != null && session.ticket().generation() == claim.generation() ? session : null;
        }
        if (claim.decision() == ProbeGate.Decision.PASS) return false;
        // Once claimed, never replay the launch if delivery fails.
        try {
            if (owner != null && claim.captured() != null) worker.post(() -> {
                try { complete(owner, claim.captured()); }
                catch (Throwable failure) { try { fail(owner, "capture_unavailable"); } catch (Throwable ignored) { } }
            });
            else module.log(4, TAG, "probe stage=late_blocked");
        } catch (Throwable consumedFailure) {
            if (owner != null) try {
                worker.post(() -> { try { fail(owner, "capture_unavailable"); } catch (Throwable ignored) { } });
            } catch (Throwable ignored) { }
        }
        return true;
    }
    // Runs exclusively in system_server, outside application hidden-API enforcement.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void register(int attempt) {
        try {
            Context context = systemContext();
            if (context == null) throw new IllegalStateException("System context pending");
            system = context; // Read-only capture does not depend on the synthetic input adapter.
            if (atmsInstance == null && activityTaskManagerType != null) try {
                Method service = Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService");
                service.setAccessible(true); Object candidate = service.invoke(null);
                if (activityTaskManagerType.isInstance(candidate)) atmsInstance = candidate;
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
            if(runtime==null)try {
                java.util.function.Consumer<String> runtimeLogger=message->module.log(4,TAG,message);
                var candidate=new io.github.yudigaga.taplusbridge.runtime.RuntimeServer(context,worker,
                        ()->module.getRemotePreferences(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.GROUP),
                        runtimeLogger);
                candidate.setSourceCapture(scopeCapture);
                candidate.setDragGestures(longPressDrag);
                candidate.register();runtime=candidate;
            }catch(RuntimeException unavailable){
                module.log(5,TAG,"System configuration bridge unavailable: "+unavailable.getClass().getSimpleName());
            }
            InputAdapter adapter = new InputAdapter(context, physical, pointEcho);
            if (scopeCapture != null && linkLaunchReady && scopeCapture.linkProbe() == null) {
                scopeCapture.setLinkProbe(new ScopeLinkProbe(worker, physical,
                        () -> runtime == null ? null : runtime.sourceCaptureConfiguration(),
                        target -> {
                            int uid = hostUid();
                            scopeCapture.validateLinkTargetBehind(target, uid);
                            cardPlan = windows.planCardTap(atmsInstance, target, uid);
                        }, (target, action, downAt) -> {
                            if (action == MotionEvent.ACTION_DOWN) {
                                SourceWindowAccess.CardPlan planned = cardPlan;
                                cardPlan = null;
                                if (planned != null && planned.dismiss()) {
                                    if (planned.popupCovered()) throw new IllegalStateException("popup_requires_source_bridge");
                                    long dismissAt = SystemClock.uptimeMillis();
                                    boolean sent = false;
                                    try {
                                        sent = adapter.injectTarget(target.uid, target.display, planned.dismissX(),
                                                planned.dismissY(), MotionEvent.ACTION_DOWN, dismissAt);
                                        if (!sent) throw new IllegalStateException("input_failed");
                                    } finally {
                                        if (sent) try {
                                            adapter.injectTarget(target.uid, target.display, planned.dismissX(),
                                                    planned.dismissY(), MotionEvent.ACTION_CANCEL, dismissAt);
                                        } catch (ReflectiveOperationException | RuntimeException ignored) { }
                                    }
                                    if (windows.inspect(atmsInstance, target.source, target.activity, target.uid,
                                            target.user, target.display, target.x, target.y) != ProbeClickPlan.Window.MAIN)
                                        throw new IllegalStateException("popup_not_dismissed");
                                    scopeCapture.validateLinkTargetBehind(target, hostUid());
                                }
                            }
                            return adapter.injectTarget(target.uid, target.display, target.x, target.y, action, downAt);
                        },
                        message -> module.log(4, TAG, message)));
                if (longPressDrag != null) longPressDrag.setCardLinks(new io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag.CardLinks() {
                    @Override public void capture(ScopeCaptureWindow.Snapshot target,
                                                  io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot config,
                                                  java.util.function.Consumer<String> url) {
                        scopeCapture.captureDragLink(target, config, 0, 0, physical.releasedElapsedAt(), url);
                    }
                    @Override public void captureFromGesture(ScopeCaptureWindow.Snapshot target,
                                                             io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot config,
                                                             long downUptime, long recognitionAt, long releasedAt,
                                                             java.util.function.Consumer<String> url) {
                        scopeCapture.captureDragLink(target, config, downUptime, recognitionAt, releasedAt, url);
                    }
                });
            }
            if (!armRegistered) {
                registerAll(context, new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent command) { arm(command); }
                }, ProbeContract.ARM, "android.permission.SET_ACTIVITY_WATCHER");
                armRegistered = true;
            }
            if (!tapRegistered) {
                registerAll(context, new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent command) { tap(command); }
                }, ProbeContract.TAP, "android.permission.SET_ACTIVITY_WATCHER");
                tapRegistered = true;
            }
            if (!cancelRegistered) {
                registerAll(context, new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent command) { cancel(command); }
                }, CaptureBroker.ACTION_CANCEL, BridgeContract.APP_PACKAGE + ".CANCEL_CAPTURE");
                cancelRegistered = true;
            }
            system = context; input = adapter;
            module.log(4, TAG, "Invisible probe command channel ready; protocol=2; physical_up_required=true");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (attempt < 5) worker.postDelayed(() -> register(attempt + 1), 1000L << attempt);
            else module.log(5, TAG, "Invisible probe channel unavailable: " + failure.getClass().getSimpleName());
        }
    }
    private void registerAll(Context context, BroadcastReceiver receiver, String action, String permission)
            throws ReflectiveOperationException {
        if (android.os.Build.VERSION.SDK_INT < 33) throw new UnsupportedOperationException("Receiver flags unavailable");
        UserHandle all = (UserHandle) UserHandle.class.getDeclaredField("ALL").get(null);
        Method method = Context.class.getDeclaredMethod("registerReceiverAsUser", BroadcastReceiver.class,
                UserHandle.class, IntentFilter.class, String.class, Handler.class, int.class);
        method.setAccessible(true);
        method.invoke(context, receiver, all, new IntentFilter(action), permission, worker, Context.RECEIVER_EXPORTED);
    }

    @SuppressWarnings("deprecation")
    private void arm(Intent command) {
        ResultReceiver callback = null;
        String nonce = null;
        int user = -1;
        Session previous = null;
        try {
            command.setExtrasClassLoader(ResultReceiver.class.getClassLoader());
            callback = command.getParcelableExtra("ack"); nonce = command.getStringExtra("nonce");
            user = command.getIntExtra("user", -1);
            if (callback == null || !CaptureBroker.validNonce(nonce) || user < 0 || user > 10000
                    || command.getIntExtra("protocol", -1) != ProbeContract.VERSION
                    || !ProbeContract.MODE.equals(command.getStringExtra("mode")) || input == null || system == null)
                throw new Rejected("protocol");
            long recognitionAt = command.getLongExtra("recognition_at", 0), now = SystemClock.elapsedRealtime();
            if (recognitionAt < 0 || recognitionAt > now + MAX_RECOGNITION_LEAD_MILLIS)
                throw new Rejected("protocol");
            if (recognitionAt > 0 && physical.released() && physical.releasedElapsedAt() < recognitionAt)
                throw new Rejected("released_before_recognition");
            Snapshot snapshot = Snapshot.read(command);
            Bundle request = new Bundle(); request.putString("nonce", nonce);
            Bundle ticket = call(user, "capture_take", request);
            String source = ticket == null ? null : ticket.getString("source");
            int uid = ticket == null ? -1 : ticket.getInt("source_uid", -1);
            if (ticket == null || !ticket.getBoolean("probe") || ticket.getInt("user", -1) != user || !CaptureLease.allowedSource(source)
                    || uid < 0 || uid / 100000 != user) throw new Rejected("protocol");
            if (!snapshot.valid(source, SystemClock.elapsedRealtime())) throw new Rejected("snapshot");
            validateForeground(source, user, snapshot);
            if ((longPressDrag != null && !longPressDrag.sourcePointStable(source, snapshot.capturedAt()))
                    || (scopeCapture != null && !scopeCapture.sourcePointStable(source, snapshot.capturedAt())))
                throw new Rejected("source_changed");
            if (scopeCapture != null && !scopeCapture.tryYieldLinkToHost(source, uid, user, snapshot.capturedAt()))
                throw new Rejected("busy");
            Session accepted;
            synchronized (lock) {
                previous = session;
                accepted = new Session(gate.arm(nonce, source, uid, user, SystemClock.elapsedRealtime()), snapshot,
                        callback, ticket.getBoolean("system_runtime"), recognitionAt);
                session = accepted;
            }
            if (previous != null) send(previous.callback(), ProbeContract.FAILED,
                    envelope(previous.ticket().nonce(), previous.ticket().user(), false, "replaced"));
            status(accepted, "waiting_release", "waiting_release");
            module.log(4, TAG, "probe stage=waiting_release source=" + source + " user=" + user);
            release(accepted);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            String reason = reason(failure, "window_unavailable");
            send(callback, ProbeContract.FAILED, envelope(nonce, user, false, reason));
            module.log(5, TAG, "probe stage=failed reason=" + reason);
        }
    }
    private void releaseWaitingProbe() {
        Session owner = session;
        if (owner != null) release(owner);
    }
    private void release(Session owner) {
        if (owner.recognitionAt() > 0 && physical.released()
                && physical.releasedElapsedAt() < owner.recognitionAt()) {
            fail(owner, "released_before_recognition"); return;
        }
        boolean dragUp = longPressDrag != null && longPressDrag.fingerReleasedForLink();
        if (DragGestureGate.suppressLongPress() && !dragUp) { fail(owner, "dragging"); return; }
        if(!probeValid(owner)) {
            fail(owner,"cancelled");return;
        }
        if(session==owner && physical.unavailable()) { fail(owner,"touch_unknown");return; }
        boolean up = physical.released() || dragUp;
        if (session != owner || !gate.awaitingRelease(owner.ticket()) || !up) {
            if (dragUp) module.log(4, TAG, "probe stage=drag_release_skipped reason="
                    + (session != owner ? "session" : !gate.awaitingRelease(owner.ticket()) ? "phase" : "touch"));
            return;
        }
        if (dragUp && !physical.released()) module.log(4, TAG, "probe stage=drag_release");
        try {
            validateForeground(owner.ticket().source(), owner.ticket().user(), owner.snapshot());
            synchronized (lock) {
                if (session != owner || !up || !gate.released(owner.ticket(), SystemClock.elapsedRealtime())) return;
            }
            Bundle ready = envelope(owner.ticket().nonce(), owner.ticket().user(), true, "ready");
            ready.putBoolean("release_confirmed", true);
            status(owner, "ready", "ready");
            send(owner.callback(), ProbeContract.READY, ready);
            module.log(4, TAG, "probe stage=ready source=" + owner.ticket().source() + " user=" + owner.ticket().user());
            worker.postDelayed(() -> { if (gate.awaitingTap(owner.ticket())) fail(owner, "ready_timeout"); }, ProbeGate.READY_MILLIS);
        } catch (ReflectiveOperationException | RuntimeException failure) { fail(owner, reason(failure, "source_changed")); }
    }
    private void tap(Intent command) {
        Session owner = session;
        if (owner == null) return;
        if (DragGestureGate.suppressLongPress() && (longPressDrag == null || !longPressDrag.fingerReleasedForLink())) {
            fail(owner, "dragging"); return;
        }
        try {
            command.setExtrasClassLoader(ResultReceiver.class.getClassLoader());
            if (command.getIntExtra("protocol", -1) != ProbeContract.VERSION
                    || !ProbeContract.MODE.equals(command.getStringExtra("mode"))
                    || !owner.ticket().nonce().equals(command.getStringExtra("nonce"))
                    || command.getIntExtra("user", -1) != owner.ticket().user()) return;
            if (longPressDrag != null && longPressDrag.ownsLinkFallback(owner.ticket().source())
                    && !longPressDrag.fingerReleasedForLink()) { fail(owner, "dragging"); return; }
            inspectInputWindow(owner);
            synchronized (lock) {
                if (session != owner || !gate.tap(owner.ticket().nonce(), owner.ticket().user(), SystemClock.elapsedRealtime())) return;
            }
            worker.postDelayed(() -> {
                if (gate.awaitingLaunch(owner.ticket())) fail(owner, "capture_timeout");
            }, ProbeContract.TIMEOUT_MILLIS);
            long[] downAt = {SystemClock.uptimeMillis()};
            ProbeClickPlan.run(() -> inspectInputWindow(owner), () -> gate.inFlight(owner.ticket()),
                    () -> gate.awaitingLaunch(owner.ticket()), action -> {
                        if (action == MotionEvent.ACTION_DOWN) downAt[0] = SystemClock.uptimeMillis();
                        return input.inject(owner, action, downAt[0]);
                    });
            module.log(4, TAG, "probe stage=input_sent");
            status(owner, "input_sent", "input_sent");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            fail(owner, reason(failure, "input_unavailable"));
            module.log(5, TAG, "Probe input rejected: " + failure.getClass().getSimpleName());
        }
    }
    private void cancel(Intent command) {
        try {
            String nonce = command.getStringExtra("nonce"); int user = command.getIntExtra("user", -1);
            Session owner;
            synchronized (lock) {
                owner = session;
                if (owner == null || !gate.cancel(nonce, user)) return;
                session = null;
            }
            send(owner.callback(), ProbeContract.FAILED, envelope(nonce, user, true, "cancelled"));
        } catch (RuntimeException malformed) { }
    }
    private void fail(Session owner, String reason) {
        synchronized (lock) {
            if (session != owner || !gate.finish(owner.ticket())) return;
            session = null;
        }
        status(owner, "failed", reason);
        send(owner.callback(), ProbeContract.FAILED, envelope(owner.ticket().nonce(), owner.ticket().user(), true, reason));
        module.log(4, TAG, "probe stage=failed reason=" + reason);
    }
    private void complete(Session owner, Captured result) {
        synchronized (lock) {
            if (session != owner || !gate.finish(owner.ticket())) return;
            session = null;
        }
        // Metadata only through the app broker. Raw URL travels solely to the waiting host callback.
        status(owner, result.url() == null ? "failed" : "captured", result.reason());
        report(owner, result);
        Bundle response = envelope(owner.ticket().nonce(), owner.ticket().user(), true, result.reason());
        if (result.url() != null) response.putString("url", result.url());
        send(owner.callback(), result.url() == null ? ProbeContract.FAILED : ProbeContract.CAPTURED, response);
        if (result.url() != null && longPressDrag != null) longPressDrag.offerCardLink(result.url());
        module.log(4, TAG, "probe stage=blocked outcome=" + (result.url() == null ? "NO_UNIQUE_URL" : "URL_FOUND"));
    }
    private void report(Session owner, Captured result) {
        IntentObservation.Observation metadata = result.metadata();
        if (metadata == null || !CaptureLease.expectedDestination(owner.ticket().source(), metadata.componentPackage(), metadata.componentClass())) return;
        try {
            Bundle data = new Bundle(); data.putString("nonce", owner.ticket().nonce()); data.putInt("user", owner.ticket().user());
            data.putLong("observed_elapsed", result.observedAt()); data.putString("component_package", metadata.componentPackage());
            data.putString("component_class", metadata.componentClass()); data.putString("outcome", metadata.outcome().name());
            data.putString("origin", metadata.urlOrigin().name()); data.putInt("url_count", metadata.urlCount());
            data.putInt("fields", metadata.fieldPresence()); data.putBoolean("for_result", result.forResult()); data.putInt("launch_result", 0);
            call(owner.ticket().user(), "capture_report", data);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            module.log(5, TAG, "Probe metadata unavailable: " + failure.getClass().getSimpleName());
        }
    }
    private void validateForeground(String source, int user, Snapshot snapshot) throws ReflectiveOperationException {
        Context context = system;
        if (context == null || !snapshot.valid(source, SystemClock.elapsedRealtime())) throw new Rejected("snapshot");
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked())
            throw new Rejected("source_changed");
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        Display display = displays == null ? null : displays.getDisplay(snapshot.display());
        Point size = new Point(); if (display != null) display.getRealSize(size);
        if (display == null || size.x != snapshot.width() || size.y != snapshot.height() || display.getRotation() != snapshot.rotation())
            throw new Rejected("source_changed");
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningTaskInfo> tasks = manager == null ? null : manager.getRunningTasks(1);
        if (tasks == null || tasks.size() != 1) throw new Rejected("source_changed");
        ActivityManager.RunningTaskInfo task = tasks.get(0);
        ComponentName activity = task.topActivity;
        int taskUser = task.getClass().getField("userId").getInt(task);
        int taskDisplay = task.getClass().getField("displayId").getInt(task);
        if (activity == null || !source.equals(activity.getPackageName()) || !snapshot.activity().equals(activity.getClassName())
                || taskUser != user || taskDisplay != snapshot.display()) throw new Rejected("source_changed");
        windows.available(atmsInstance);
    }
    private ProbeClickPlan.Window inspectInputWindow(Session owner) throws ReflectiveOperationException {
        if(!probeValid(owner))throw new Rejected("cancelled");
        if(physical.unavailable())throw new Rejected("touch_unknown");
        if (!physical.released() && (longPressDrag == null || !longPressDrag.fingerReleasedForLink()))
            throw new Rejected("touch_active");
        ProbeGate.Ticket ticket = owner.ticket(); Snapshot snapshot = owner.snapshot();
        if ((longPressDrag != null && !longPressDrag.sourcePointStable(ticket.source(), snapshot.capturedAt()))
                || (scopeCapture != null && !scopeCapture.sourcePointStable(ticket.source(), snapshot.capturedAt())))
            throw new Rejected("source_changed");
        validateForeground(ticket.source(), ticket.user(), snapshot);
        return windows.inspect(atmsInstance, ticket.source(), snapshot.activity(), ticket.uid(), ticket.user(),
                snapshot.display(), snapshot.x(), snapshot.y());
    }
    private void pointerUnknown(Session waiting) {
        if(physicalFailureReported.compareAndSet(false,true))
            module.log(5,TAG,"probe stage=physical_state reason=touch_unknown; input_disabled_until_reload=true");
        if(waiting!=null)worker.post(() -> fail(waiting,"touch_unknown"));
    }
    private void status(Session owner, String stage, String reason) {
        try {
            Bundle data = new Bundle(); data.putString("nonce", owner.ticket().nonce());
            data.putString("stage", stage); data.putString("reason", reason);
            call(owner.ticket().user(), "probe_status", data);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    private static String fingerprint(String url) {
        if (url == null) return null;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : bytes) hex.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { return null; }
    }
    private boolean probeValid(Session owner) {
        if(owner.autonomous())return runtime!=null && runtime.captureValid(owner.ticket().nonce(),owner.ticket().user());
        try {
            Bundle request=new Bundle();request.putString("nonce",owner.ticket().nonce());request.putInt("user",owner.ticket().user());
            Bundle response=providerCall(owner.ticket().user(),"probe_valid",request);
            return response!=null && response.getBoolean("enabled");
        } catch(ReflectiveOperationException|RuntimeException unavailable) { return false; }
    }
    private Bundle call(int user, String method, Bundle data) throws ReflectiveOperationException {
        if(runtime!=null){Bundle local=runtime.systemCall(user,method,data);if(local!=null)return local;}
        return providerCall(user,method,data);
    }
    private Bundle providerCall(int user, String method, Bundle data) throws ReflectiveOperationException {
        Context context = userContext(system, user);
        if(io.github.yudigaga.taplusbridge.runtime.BridgeTransport.moduleUnavailable(context))return Bundle.EMPTY;
        long identity = Binder.clearCallingIdentity();
        try { return context.getContentResolver().call(PROVIDER, method, null, data); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    private static Bundle envelope(String nonce, int user, boolean ready, String reason) {
        Bundle data = new Bundle(); data.putInt("protocol", ProbeContract.VERSION); data.putString("mode", ProbeContract.MODE);
        data.putBoolean("block_ready", ready); data.putString("nonce", nonce); data.putInt("user", user); data.putString("reason", reason);
        return data;
    }
    private int hostUid() {
        if (hostUid >= 10000 && hostUid < 100000) return hostUid;
        Context context = system;
        if (context == null) return -1;
        try {
            hostUid = context.getPackageManager().getApplicationInfo(HostPolicy.HOST_PACKAGE, 0).uid;
        } catch (RuntimeException | android.content.pm.PackageManager.NameNotFoundException ignored) {
            return -1;
        }
        return hostUid;
    }
    private static String reason(Exception failure, String fallback) {
        if (failure instanceof Rejected rejected) return rejected.reason;
        if (failure instanceof ProbeGate.Rejected rejected) return rejected.reason;
        if (failure instanceof SourceWindowAccess.Rejected) return "window_occluded";
        if (failure instanceof SourceWindowAccess.Unavailable) return "window_unavailable";
        return fallback;
    }
    static void recordPhysicalTouch(PhysicalTouchState physical, int display, int device, long downAt,
                                    long eventAt, int action, long nowElapsed, long nowUptime) {
        physical.event(display, device, downAt, eventAt, action, nowUptime);
        // Sample elapsed before uptime so queueing or clock-read delays cannot extend the physical hold.
        if (action == MotionEvent.ACTION_UP && physical.released())
            physical.markReleasedElapsed(nowElapsed - Math.max(0, nowUptime - eventAt));
    }
    private static void send(ResultReceiver receiver, int code, Bundle data) {
        try { if (receiver != null) receiver.send(code, data); } catch (RuntimeException ignored) { }
    }
    private static Context systemContext() throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object current = type.getMethod("currentActivityThread").invoke(null);
        return current == null ? null : (Context) type.getMethod("getSystemContext").invoke(current);
    }
    private static Context userContext(Context context, int user) throws ReflectiveOperationException {
        if (context == null) throw new IllegalStateException("No system context");
        UserHandle handle = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, user);
        return (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class).invoke(context, handle, 0);
    }
    private static final class InputAdapter {
        final Object manager;
        final Method inject, setDisplay;
        final PhysicalTouchState physical;
        final ProbePointEcho pointEcho;
        // Only constructed by the system_server receiver setup. App-target hidden API restrictions do
        // not apply here; an unavailable display-targeting method rejects READY instead of falling back.
        @android.annotation.SuppressLint("BlockedPrivateApi")
        InputAdapter(Context context, PhysicalTouchState physical, ProbePointEcho pointEcho) throws ReflectiveOperationException {
            this.physical = physical;
            this.pointEcho = pointEcho;
            manager = context.getSystemService(Context.INPUT_SERVICE);
            if (manager == null) throw new IllegalStateException("No input service");
            inject = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
            setDisplay = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
            inject.setAccessible(true); setDisplay.setAccessible(true);
        }
        boolean inject(Session session, int action, long downAt) throws ReflectiveOperationException {
            Snapshot point = session.snapshot();
            return injectTarget(session.ticket().uid(), point.display(), point.x(), point.y(), action, downAt);
        }
        boolean injectTarget(int uid, int display, float x, float y, int action, long downAt) throws ReflectiveOperationException {
            if(action==MotionEvent.ACTION_UP && !pointEcho.valid(display,downAt))throw new Rejected("point_mismatch");
            if (action == MotionEvent.ACTION_DOWN) {
                if (!physical.released()) throw new Rejected("touch_active");
                physical.injected(display, 0, downAt, SystemClock.uptimeMillis());
                // InputDispatcher rewrites injected unfiltered events to VIRTUAL_KEYBOARD_ID.
                // Keep both aliases until delivery completes, which may follow this Binder return.
                physical.injected(display, KeyCharacterMap.VIRTUAL_KEYBOARD,
                        downAt, SystemClock.uptimeMillis());
            }
            MotionEvent event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0);
            pointEcho.expect(display,downAt,action,x,y);
            try {
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN); setDisplay.invoke(event, display);
                // WAIT_FOR_FINISHED=2: the popup must process outside DOWN/CANCEL before WMS is
                // checked again. The public launch hook consumes synchronously without waiting for
                // this worker, so a source input handler's Binder call does not form a worker cycle.
                // A different UID receives nothing; no unscoped fallback exists.
                long identity = Binder.clearCallingIdentity();
                try { return Boolean.TRUE.equals(inject.invoke(manager, event, 2, uid)); }
                finally { Binder.restoreCallingIdentity(identity); }
            } finally { event.recycle(); }
        }
    }
}
