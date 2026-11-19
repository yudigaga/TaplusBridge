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
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.drag.DragGestureGate;
import io.github.yudigaga.taplusbridge.drag.LongPressFeedback;
import io.github.yudigaga.taplusbridge.drag.LongPressStageSchedule;
import io.github.yudigaga.taplusbridge.runtime.RuntimeServer;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import io.github.yudigaga.taplusbridge.runtime.ScopeCaptureStore;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Physical long-press capture with an optional, explicitly enabled original-click link probe. */
@android.annotation.TargetApi(33)
public final class SystemScopeCapture implements RuntimeServer.SourceCapture {
    public static final String ACTION = "io.github.yudigaga.taplusbridge.SCOPE_CAPTURE";
    public static final String LINK_PREPARE = "io.github.yudigaga.taplusbridge.SCOPE_LINK_PREPARE";
    private static final Executor IMAGE_ENCODER = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "TaplusBridge-png"); thread.setDaemon(true); return thread;
            });
    private static final class LinkWindow {
        final String nonce;
        final RuntimeSnapshot config;
        final ScopeCaptureWindow.Snapshot target;
        final long input, until;
        final RecordingRisk risk;
        final Runnable ready;
        final Runnable failed;
        boolean acknowledged;
        LinkWindow(String nonce, RuntimeSnapshot config, ScopeCaptureWindow.Snapshot target, long input,
                   RecordingRisk risk, Runnable ready, Runnable failed) {
            this.nonce=nonce;this.config=config;this.target=target;this.input=input;this.ready=ready;this.failed=failed;
            this.risk=risk;
            until=SystemClock.elapsedRealtime()+8_000;
        }
    }
    private volatile LinkWindow linkWindow;
    private final XposedModule module;
    private final Handler worker;
    private final Executor imageEncoder;
    private final SourceRecordingState.Source recording;
    private static final long RECORDING_OBSERVATION_MAX_AGE = 250;
    private static final class RecordingRisk {
        final ScopeCaptureWindow.Snapshot target;
        final RuntimeSnapshot config;
        final long input, menu, down, began;
        final int device;
        volatile boolean revoked, scopeYielded, protectedRecording, committed;
        boolean cleanupScheduled;
        RecordingRisk(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long input, long menu,
                      int device, long down, long began) {
            this.target=target;this.config=config;this.input=input;this.menu=menu;
            this.device=device;this.down=down;this.began=began;
        }
    }
    private volatile RecordingRisk recordingRisk;
    // Owned by the pointer worker; a waiting request retains no screenshot pixels.
    private boolean encodingImage;
    private Runnable pendingEncoding;
    private final Supplier<Context> context;
    private final Supplier<Object> atms;
    private final Supplier<RuntimeServer> runtime;
    private final ScopeCaptureWindow windows;
    private final PhysicalTouchState physical;
    private final RecognitionMenuController recognitionMenus;
    private final RecognitionMenuRetention menuRetention;
    private volatile boolean menuNavigationGuardReady;
    private record MenuTouch(ScopeCaptureWindow.Snapshot target, int device, long down, long input,
                             long menuEpoch, long recognitionAt, long requiredDuration, float slop,
                             RecognitionMenuGesture.Lease lease) { }
    private final RecognitionMenuGesture menuGesture = new RecognitionMenuGesture();
    private volatile MenuTouch menuTouch;
    private volatile long invalidMenuInput = -1, validatedMenuRelease = -1;
    private final java.util.concurrent.atomic.AtomicLong menuEpoch = new java.util.concurrent.atomic.AtomicLong();
    private final ScopeLongPress gesture = new ScopeLongPress();
    private final ScopeCaptureStore store = new ScopeCaptureStore();
    private ScopeCaptureWindow.Snapshot origin;
    private RuntimeSnapshot configuration;
    private volatile SystemAssistContent assistContent;
    private ScopeCaptureWindow.Snapshot lastOrigin;
    private boolean sourcePointMoved;
    private long sourceDown = -1;
    private int sourceDevice = -1;
    public void setAssistContent(SystemAssistContent content) { assistContent = content; }
    public boolean sourcePointStable(String source, long pickedAt) {
        return !sourcePointMoved || lastOrigin == null || !lastOrigin.source.equals(source) || pickedAt < downElapsed;
    }
    private long generation, downElapsed, recognitionAt;
    private final java.util.concurrent.atomic.AtomicLong inputGeneration = new java.util.concurrent.atomic.AtomicLong();
    private long gestureInputGeneration;
    private volatile long pendingInputGeneration;
    private record HostLinkOwner(String source, int uid, int user, long until) { }
    private HostLinkOwner hostLinkOwner;
    private boolean recognitionFeedbackSent;
    private record NativeSignal(String source, long at, boolean hasValidUrl) {}
    private volatile NativeSignal nativeSignal;
    private volatile long pendingBegan;
    private boolean gestureActive;
    private int mode = 1;
    private long timeout = 550;
    private volatile float slop = 20;
    private volatile ScopeCaptureWindow.Snapshot pendingTarget;
    private static final class PendingCapture {
        final ScopeCaptureWindow.Snapshot target;
        final RecordingRisk risk;
        final long input;
        final String nonce;
        volatile boolean revoked;
        PendingCapture(ScopeCaptureWindow.Snapshot target,RecordingRisk risk,long input,String nonce) {
            this.target=target;this.risk=risk;this.input=input;this.nonce=nonce;
        }
    }
    // Keep spent contexts as tombstones; production requests must never fall back to the legacy test path.
    private volatile PendingCapture pendingCapture;
    private volatile ScopeLinkProbe linkProbe;
    public ScopeLinkProbe linkProbe() { return linkProbe; }
    public void setLinkProbe(ScopeLinkProbe probe) { linkProbe = probe; }
    public void validateLinkTarget(ScopeCaptureWindow.Snapshot target) throws ReflectiveOperationException {
        RecordingRisk risk = riskFor(target);
        requireRecordingAllowed(risk);
        windows.validate(atms.get(), target);
        requireRecordingAllowed(risk);
    }
    public void validateLinkTargetBehind(ScopeCaptureWindow.Snapshot target, int hostUid) throws ReflectiveOperationException {
        RecordingRisk risk = riskFor(target);
        requireRecordingAllowed(risk);
        if (hostUid >= 10000 && hostUid < 100000) windows.validateBehindHost(atms.get(), target, hostUid);
        else windows.validate(atms.get(), target);
        requireRecordingAllowed(risk);
    }
    @Override public boolean linkReady() { return linkProbe != null; }

    /** Runs on the input worker before host READY, so pending scope callbacks cannot race another tap. */
    public boolean tryYieldLinkToHost(String source, int uid, int user, long pickedAt) {
        long now = SystemClock.elapsedRealtime();
        if (pickedAt < downElapsed || pickedAt > now) return false;
        if (sourcePointMoved && lastOrigin != null && lastOrigin.source.equals(source)
                && lastOrigin.uid == uid && lastOrigin.user == user) return false;
        HostLinkOwner owner = hostLinkOwner;
        if (owner != null && now > owner.until) hostLinkOwner = owner = null;
        if (owner != null && owner.source.equals(source) && owner.uid == uid && owner.user == user) return false;
        ScopeCaptureWindow.Snapshot target = origin;
        ScopeLinkProbe link = linkProbe;
        if (target == null || configuration == null || !configuration.scopeLinkProbe || link == null
                || !target.source.equals(source) || target.uid != uid || target.user != user) return true;
        if (!link.tryYieldToHost(target)) return false;
        // Native callbacks may have different pick times; reserve this physical gesture across UP/cancel.
        hostLinkOwner = new HostLinkOwner(source, uid, user, now + 30_000);
        RecordingRisk risk = riskFor(target);
        if (risk == null) cancel();
        else {
            // Retire only the scope work; native presentation and drag still observe this same touch.
            risk.scopeYielded = true;
            retireRecordingRisk(risk);
        }
        return true;
    }

    @Override public boolean ownsLinkPick(Bundle request) {
        ScopeLinkProbe link = linkProbe;
        return link != null && request != null && request.get("native_elapsed") instanceof Long at
                && link.ownsNative(request.getString("source"), at);
    }

    public SystemScopeCapture(XposedModule module, Handler worker, Supplier<Context> context,
                              Supplier<Object> atms, Supplier<RuntimeServer> runtime, Class<?> atmsType, PhysicalTouchState physical)
            throws ReflectiveOperationException {
        this(module, worker, context, atms, runtime, new ScopeCaptureWindow(atmsType), physical);
    }

    SystemScopeCapture(XposedModule module, Handler worker, Supplier<Context> context,
                       Supplier<Object> atms, Supplier<RuntimeServer> runtime, ScopeCaptureWindow windows,
                       PhysicalTouchState physical) {
        this(module, worker, context, atms, runtime, windows, physical, IMAGE_ENCODER);
    }

    SystemScopeCapture(XposedModule module, Handler worker, Supplier<Context> context,
                       Supplier<Object> atms, Supplier<RuntimeServer> runtime, ScopeCaptureWindow windows,
                       PhysicalTouchState physical, Executor imageEncoder) {
        this(module, worker, context, atms, runtime, windows, physical, imageEncoder, SourceRecordingState.from(context));
    }

    SystemScopeCapture(XposedModule module, Handler worker, Supplier<Context> context,
                       Supplier<Object> atms, Supplier<RuntimeServer> runtime, ScopeCaptureWindow windows,
                       PhysicalTouchState physical, Executor imageEncoder, SourceRecordingState.Source recording) {
        this.module = module; this.worker = worker; this.context = context; this.atms = atms;
        this.runtime = runtime; this.physical = physical; this.windows = windows; this.imageEncoder = imageEncoder;
        this.recording = java.util.Objects.requireNonNull(recording);
        menuRetention = new RecognitionMenuRetention(menuEpoch::get,this::menuConfiguration,this::menuTouchValidated,
                target -> {
                    Context system=context.get();
                    if(!unlocked(system))return false;
                    try {
                        var host=system.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE,0);
                        return host.applicationInfo!=null && host.applicationInfo.enabled
                                && HostPolicy.supportedVersion(host.versionName,host.getLongVersionCode())
                                && windows.recognitionSourceCurrent(atms.get(),target,host.applicationInfo.uid);
                    }catch(PackageManager.NameNotFoundException unavailable){return false;}
                });
        recognitionMenus = new RecognitionMenuController(worker, menuEpoch::get,
                () -> {
                    RuntimeServer server = runtime.get();
                    return server == null ? null : server.sourceCaptureConfiguration();
                }, this::menuTouchValidated,
                this::dismissRecognitionMenu, outcome -> {
                    if (module != null) module.log(4, "TaplusBridge", "recognition menu stage=finished reason=" + outcome);
                });
    }

    private RuntimeSnapshot menuConfiguration() {
        RuntimeServer server=runtime.get();return server==null?null:server.sourceCaptureConfiguration();
    }

    private RecordingRisk riskFor(ScopeCaptureWindow.Snapshot target) {
        RecordingRisk risk=recordingRisk;
        return risk!=null && risk.target==target ? risk : null;
    }

    private boolean recordingCurrent(RecordingRisk risk) {
        return risk==null || recordingRisk==risk && risk.input==inputGeneration.get()
                && !risk.revoked && !risk.scopeYielded && !risk.protectedRecording;
    }

    /** Only call at a fresh boundary outside snapshot, retention and WMS monitors. */
    private boolean recordingAllowed(RecordingRisk risk) {
        if (!recordingCurrent(risk)) {
            if(risk!=null && recordingRisk==risk && (risk.revoked || risk.protectedRecording))retireRecordingRisk(risk);
            return false;
        }
        if (risk==null) return true;
        if (observesRecording(risk)) {
            risk.protectedRecording=true;
            retireRecordingRisk(risk);
            event("recording_protected",risk.committed ? "already_committed" : "before_commit");
        }
        return recordingCurrent(risk);
    }

    private boolean observesRecording(RecordingRisk risk) {
        SourceRecordingState.Observation observed;
        try { observed=recording.observe(risk.target.uid); }
        catch (RuntimeException | LinkageError unavailable) { observed=SourceRecordingState.UNKNOWN; }
        long now=SystemClock.elapsedRealtime();
        return observed!=null && observed.uid()==risk.target.uid && observed.observedAt()>=risk.began
                && observed.observedAt()<=now && now-observed.observedAt()<=RECORDING_OBSERVATION_MAX_AGE;
    }

    private void requireRecordingAllowed(RecordingRisk risk) {
        if(!recordingAllowed(risk))throw new IllegalStateException("scope_recording_veto");
    }

    private void committed(RecordingRisk risk) {
        if(risk==null)return;
        risk.committed=true;
        if(risk.protectedRecording)event("recording_protected","already_committed");
    }

    private void retireRecordingRisk(RecordingRisk risk) {
        synchronized(risk){if(risk.cleanupScheduled)return;risk.cleanupScheduled=true;}
        SystemAssistContent content=assistContent;
        if(content!=null)content.cancelOwner(risk.target,risk.config,risk.device,risk.down);
        ScopeLinkProbe link=linkProbe;
        if(link!=null)link.cancelOwner(risk.target);
        worker.post(()->{
            if(recordingRisk!=risk || risk.input!=inputGeneration.get())return;
            PendingCapture pendingCaptureOwner=pendingCapture;
            if(pendingCaptureOwner!=null && pendingCaptureOwner.risk==risk){
                pendingCaptureOwner.revoked=true;store.clear();
            } else if(pendingCaptureOwner==null)store.clear();
            if(pendingTarget==risk.target)pendingTarget=null;
            LinkWindow pending=linkWindow;
            if(pending!=null && pending.risk==risk)linkWindow=null;
            pendingEncoding=null;
            MenuTouch touch=menuTouch;
            if(touch!=null && touch.target==risk.target){
                menuRetention.cancel();recognitionMenus.cancel();menuTouch=null;
            }
            if(origin==risk.target){
                generation++;gestureActive=false;gesture.cancel();origin=null;configuration=null;
                recognitionFeedbackSent=false;
            }
        });
    }

    private void revokeRecordingRisk(RecordingRisk risk) {
        if(risk!=null){risk.revoked=true;retireRecordingRisk(risk);}
    }

    private String dismissRecognitionMenu(ScopeCaptureWindow.Snapshot target,java.util.function.BooleanSupplier current)
            throws ReflectiveOperationException {
        String outcome="unsupported";
        MenuTouch touch = menuTouch;
        if (touch == null || touch.target != target) return "stale";
        RecordingRisk risk=riskFor(target);
        if(!recordingAllowed(risk))return "stale";
        try {
            Context system=context.get();
            if(!unlocked(system))return "stale";
            var host=system.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE,0);
            if(host.applicationInfo==null || !host.applicationInfo.enabled
                    || !HostPolicy.supportedVersion(host.versionName,host.getLongVersionCode()))return outcome;
            outcome=windows.dismissRecognitionMenu(atms.get(),target,host.applicationInfo.uid,
                    ()->recordingAllowed(risk) && unlocked(context.get()) && current.getAsBoolean(),()->{
                        RuntimeSnapshot config=menuConfiguration();
                        return recordingAllowed(risk) && current.getAsBoolean() && config!=null && menuRetention.arm(target,config.identity,
                                touch.menuEpoch,recognitionMenus.shownElapsed(target),menuNavigationGuardReady);
                    });
            if("sent".equals(outcome))committed(risk);
            return outcome;
        }catch(PackageManager.NameNotFoundException unavailable){return outcome;}
        finally {if(!"sent".equals(outcome))menuRetention.cancel();}
    }

    public void setMenuNavigationGuardReady(boolean ready) {
        menuNavigationGuardReady=ready;
        if(!ready)menuRetention.cancel();
    }

    public void newKeyInput() {
        revokeRecordingRisk(recordingRisk);
        menuGesture.invalidate();
        invalidMenuInput=inputGeneration.get();
        menuRetention.cancel();recognitionMenus.cancel();
    }

    public void menuInputUnknown() {
        revokeRecordingRisk(recordingRisk);
        menuGesture.invalidate();
        invalidMenuInput=inputGeneration.get();
        menuRetention.cancel();recognitionMenus.cancel();
    }

    /** Called for eligible touch pointers before the input Hook can take a transfer early return. */
    public long menuPointerAtHook(boolean realMainInput, boolean touchscreen, int action) {
        if (!realMainInput || !PhysicalTouchState.isTouchAction(action)
                || touchscreen && action != MotionEvent.ACTION_DOWN) return menuEpoch.get();
        long observed = menuEpoch.incrementAndGet();
        revokeRecordingRisk(recordingRisk);
        menuGesture.invalidate();
        menuRetention.cancel();
        recognitionMenus.cancelStale();
        return observed;
    }

    /** Existing touchscreen callers capture their lease before copying touch primitives. */
    public void pointer(MotionEvent event, int display, boolean exclusiveAtEvent) {
        if (physical != null && physical.isInjected(display, event.getDeviceId(),
                event.getDownTime(), SystemClock.uptimeMillis())) return;
        pointer(event, display, exclusiveAtEvent,
                menuPointerAtHook(display == 0, true, event.getActionMasked()));
    }

    /** Copy only touch primitives before returning to the system input dispatcher. */
    public void pointer(MotionEvent event, int display, boolean exclusiveAtEvent, long observedMenuEpoch) {
        int rawMaskedAction = event.getActionMasked();
        int action = rawMaskedAction == MotionEvent.ACTION_POINTER_UP
                && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0
                ? MotionEvent.ACTION_CANCEL : rawMaskedAction;
        int count = event.getPointerCount(), device = event.getDeviceId();
        long down = event.getDownTime(), at = event.getEventTime();
        long receivedUptime = SystemClock.uptimeMillis();
        boolean menuCanceled = (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0;
        RecognitionMenuGesture.Lease observedMenuLease = menuGesture.observe(rawMaskedAction, display, device,
                event.getSource(), count == 1 ? event.getPointerId(0) : -1, count, down, at, receivedUptime,
                menuCanceled, rawMaskedAction == MotionEvent.ACTION_UP
                        ? physical != null && physical.released() : exclusiveAtEvent,
                event.getRawX(), event.getRawY());
        long observedInput = action == MotionEvent.ACTION_DOWN ? inputGeneration.incrementAndGet() : inputGeneration.get();
        if (action == MotionEvent.ACTION_DOWN) {
            revokeRecordingRisk(recordingRisk);
            menuRetention.cancel();
            menuTouch = null; invalidMenuInput = -1; validatedMenuRelease = -1;
            recognitionMenus.cancelStale();
        }
        MenuTouch touch = menuTouch;
        if(action==MotionEvent.ACTION_CANCEL || menuCanceled || display!=0)
            revokeRecordingRisk(recordingRisk);
        if (action == MotionEvent.ACTION_CANCEL || menuCanceled || count != 1 || display != 0
                || touch != null && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                && (device != touch.device || down != touch.down || !Float.isFinite(event.getRawX())
                || !Float.isFinite(event.getRawY())
                || Math.hypot(event.getRawX() - touch.target.x, event.getRawY() - touch.target.y) > touch.slop)) {
            invalidMenuInput = observedInput;
            menuRetention.cancel();
            recognitionMenus.cancel();
        }
        if (DragGestureGate.suppressLongPress()) {
            menuRetention.cancel();
            recognitionMenus.cancel();
            ScopeLinkProbe link = linkProbe; if (link != null) link.cancel();
            RecordingRisk suppressed=recordingRisk;
            worker.post(()->{if(recordingRisk==suppressed && observedInput==inputGeneration.get())cancel();}); return;
        }
        if (action == MotionEvent.ACTION_DOWN) { clearStore(); pendingTarget = null; linkWindow = null; }
        long eventElapsed = SystemClock.elapsedRealtime() - Math.max(0, SystemClock.uptimeMillis() - at);
        float x = event.getRawX(), y = event.getRawY();
        float x2 = count > 1 ? event.getRawX(1) : Float.NaN, y2 = count > 1 ? event.getRawY(1) : Float.NaN;
        ScopeLinkProbe link = linkProbe;
        if (link != null) link.pointer(action, count, x, y, down, at, slop);
        RecordingRisk pointerRisk=recordingRisk;
        worker.post(() -> {
            if(observedInput!=inputGeneration.get()){
                if((action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)
                        && pointerRisk!=null && recordingRisk==pointerRisk
                        && display==0 && device==pointerRisk.device && down==pointerRisk.down
                        && gestureInputGeneration==observedInput)cancel();
                return;
            }
            event(display, device, down, at, eventElapsed, action, count, x, y, x2, y2, exclusiveAtEvent,
                    observedInput, observedMenuEpoch, observedMenuLease);
            if (observedMenuEpoch != menuEpoch.get()) return;
            if (action == MotionEvent.ACTION_UP) {
                MenuTouch current = menuTouch;
                // Delivery-time elapsed offsets must not lengthen the original uptime hold.
                if (current != null && current.input == observedInput && current.down == down && current.device == device
                        && current.menuEpoch == observedMenuEpoch
                        && current.lease == observedMenuLease && invalidMenuInput != observedInput
                        && menuGesture.completed(current.lease, current.requiredDuration, current.slop)
                        && at <= SystemClock.uptimeMillis() && SystemClock.uptimeMillis() - at <= 300) {
                    validatedMenuRelease = observedInput;
                    recognitionMenus.released();
                } else {
                    invalidMenuInput = observedInput;
                    menuRetention.cancel();
                    recognitionMenus.cancel();
                }
            }
        });
    }

    private boolean menuTouchValidated() {
        MenuTouch touch = menuTouch;
        RecordingRisk risk=touch==null ? null : riskFor(touch.target);
        return physical != null && touch != null && touch.input == inputGeneration.get()
                && recordingCurrent(risk) && (risk==null || risk.menu==touch.menuEpoch
                        && risk.device==touch.device && risk.down==touch.down)
                && SystemClock.elapsedRealtime() >= touch.recognitionAt
                && invalidMenuInput != touch.input && validatedMenuRelease == touch.input && physical.released()
                && menuGesture.completed(touch.lease, touch.requiredDuration, touch.slop)
                && touch.menuEpoch == menuEpoch.get();
    }

    private void event(int display, int device, long down, long at, long eventElapsed, int action, int count,
                       float x, float y, float x2, float y2, boolean exclusiveAtEvent, long observedInput,
                       long observedMenuEpoch, RecognitionMenuGesture.Lease observedMenuLease) {
        if (action == MotionEvent.ACTION_DOWN) {
            gestureInputGeneration = observedInput;
            clearStore(); pendingTarget = null;
            downElapsed = eventElapsed - Math.max(0, at - down);
            hostLinkOwner = null;
            sourcePointMoved = false; lastOrigin = null;
            sourceDown = down; sourceDevice = device;
        }
        ScopeCaptureWindow.Snapshot originalPoint = origin == null ? lastOrigin : origin;
        if (originalPoint != null && display == 0 && down == sourceDown && device == sourceDevice
                && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                && Math.hypot(x - originalPoint.x, y - originalPoint.y) > slop) {
            sourcePointMoved = true; lastOrigin = originalPoint;
            if (observedMenuEpoch == menuEpoch.get()) {
                menuRetention.cancel();
                recognitionMenus.cancel();
            }
        }
        try {
            if (DragGestureGate.suppressLongPress()) { cancel(); return; }
            Context system = context.get(); RuntimeServer server = runtime.get();
            if (system == null || server == null || !unlocked(system)) { cancel(); return; }
            if (action == MotionEvent.ACTION_DOWN) {
                if (gestureActive || !exclusiveAtEvent) { cancel(); return; }
                cancel();
                RecordingRisk risk;
                try(RuntimeServer.PolicyOperation operation=server.beginSourceCaptureOperation()){
                    configuration=operation.configuration();
                    if (configuration == null || !configuration.enabled || (!configuration.recognition
                            && !configuration.scopeCapture && !configuration.scopeLinkProbe)
                            || !configuration.sourceScope.managed
                            || Settings.System.getInt(system.getContentResolver(), "content_catcher_network_enabled_content_extension", 0) != 1) return;
                    origin = windows.inspect(atms.get(), display, x, y);
                    lastOrigin = origin;
                    if (!configuration.sourceScope.allows(origin.source)
                            || HostPolicy.HOST_PACKAGE.equals(origin.source) || HostPolicy.MODULE_PACKAGE.equals(origin.source)) {
                        cancel(); return;
                    }
                    risk=new RecordingRisk(origin,configuration,observedInput,observedMenuEpoch,device,down,downElapsed);
                    recordingRisk=risk;
                    if(!operation.isCurrent())risk.revoked=true;
                }
                if(!recordingAllowed(risk)){retireRecordingRisk(risk);return;}
                mode = Settings.System.getInt(system.getContentResolver(), "support_long_press_mode",
                        Settings.Global.getInt(system.getContentResolver(), "support_long_press_mode", 1));
                timeout = Settings.System.getInt(system.getContentResolver(), "support_long_press_time_out",
                        ViewConfiguration.getLongPressTimeout() + 50);
                slop = ViewConfiguration.get(system).getScaledTouchSlop();
                recognitionAt = timeout >= 100 && timeout <= 5_000
                        ? LongPressStageSchedule.fromDown(downElapsed, timeout,
                                configuration.drag.recognitionDelayMillis, configuration.drag.dragDelayMillis).recognitionAt()
                        : Long.MAX_VALUE;
                gestureActive = true;
                recognitionFeedbackSent = false;
                if (mode == 1 && timeout >= 100 && timeout <= 5_000
                        && inputGeneration.get() == observedInput && menuEpoch.get() == observedMenuEpoch) {
                    menuTouch = new MenuTouch(origin,device,down,observedInput,observedMenuEpoch,recognitionAt,
                            recognitionAt - downElapsed,slop,observedMenuLease);
                    recognitionMenus.begin(origin, configuration, downElapsed, downElapsed + timeout, recognitionAt,
                            observedMenuEpoch);
                }
                if (mode == 1) scheduleRecognitionFeedback(generation, configuration, device, down, recognitionAt, risk);
                SystemAssistContent content = assistContent;
                if (mode == 1 && content != null && configuration.recognition && recordingAllowed(risk))
                    content.arm(origin, configuration, down, downElapsed, recognitionAt, device, ()->recordingCurrent(risk));
                ScopeLinkProbe link = linkProbe;
                if (configuration.scopeLinkProbe && link != null && recordingAllowed(risk))
                    link.arm(origin, configuration, down, downElapsed, timeout, mode);
            }
            if (origin == null) return;
            RecordingRisk risk=riskFor(origin);
            if(!recordingCurrent(risk))return;
            if (count > 1 && (!Float.isFinite(x2) || !Float.isFinite(y2)
                    || !origin.bounds.contains((int) x2, (int) y2))) { cancel(); return; }
            ScopeLongPress.Trigger trigger = gesture.event(display, device, down, at, action, count,
                    x, y, x2, y2, mode, timeout, slop, SystemClock.uptimeMillis());
            if (trigger != null) {
                recognitionFeedback(generation, configuration, device, down, recognitionAt, true, eventElapsed, risk);
                if(!recordingCurrent(risk))return;
                gestureActive = false;
                event("gesture", "none");
                long expected = generation;
                var picked = origin; var config = configuration; long began = downElapsed, readyAt = recognitionAt;
                long releasedAt = eventElapsed;
                // Screenshot-only mode lets ROM capture win; link mode owns the original click first.
                worker.postDelayed(() -> capture(expected, picked, config, began, readyAt, releasedAt, risk), 300);
            } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) cancel();
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            cancel(); event("rejected", reasonOf(unavailable));
        }
    }

    private void capture(long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config, long began,
                         long readyAt, long releasedAt) {
        capture(expected,picked,config,began,readyAt,releasedAt,riskFor(picked));
    }

    private void capture(long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config, long began,
                         long readyAt, long releasedAt, RecordingRisk risk) {
        if(!recordingAllowed(risk))return;
        RuntimeServer server = runtime.get();
        RuntimeSnapshot latest = server == null ? null : server.sourceCaptureConfiguration();
        if (DragGestureGate.suppressLongPress() || expected != generation || gestureInputGeneration != inputGeneration.get()
                || latest == null || !latest.identity.equals(config.identity)
                || !unlocked(context.get()) || !physical.released()
                || releasedAt < readyAt
                || nativeUrlSince(picked.source, began)) return;
        ScopeLinkProbe link = linkProbe;
        SystemAssistContent content = assistContent;
        if (content != null && content.pending(picked, latest, began, sourceDown)
                && SystemClock.elapsedRealtime() - releasedAt < 2_500) {
            worker.postDelayed(() -> capture(expected, picked, config, began, readyAt, releasedAt, risk), 50); return;
        }
        SystemAssistContent.Result provided = content == null ? SystemAssistContent.Result.EMPTY : content.read(picked, latest, began, sourceDown);
        if (nativeSince(picked.source, began) && !latest.scopeLinkProbe && provided.deliveryText().isBlank()) return;
        if (!provided.links().isEmpty()) {
            if (link != null) link.cancelOwner(picked);
            deliverCapture(expected, picked, config, began, null, risk); return;
        }
        if (latest.scopeLinkProbe && link != null) {
            prepareLinkWindow(picked, latest, risk, () -> {
                if(!recordingAllowed(risk))return;
                link.confirm(picked, latest, readyAt, releasedAt,
                        url -> { if(!recordingCurrent(risk))return;clearLinkWindow(picked,latest);
                            worker.post(() -> deliverCapture(expected, picked, config, began, url, risk)); });
            }, ()->{if(!recordingCurrent(risk))return;link.cancelOwner(picked);
                deliverCapture(expected,picked,config,began,null,risk);});
        } else deliverCapture(expected, picked, config, began, null, risk);
    }

    public void captureDragLink(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long down,
                                long readyAt, long releasedAt, java.util.function.Consumer<String> complete) {
        ScopeLinkProbe link=linkProbe;
        if(link==null){complete.accept(null);return;}
        RecordingRisk risk=riskFor(target);
        prepareLinkWindow(target,config,risk,()->{
            if(!recordingAllowed(risk))return;
            link.confirmDrag(target,config,down,readyAt,releasedAt,
                    url->{if(!recordingCurrent(risk))return;clearLinkWindow(target,config);complete.accept(url);});
        },()->{if(!recordingCurrent(risk))return;link.cancelOwner(target);complete.accept(null);});
    }

    private void clearLinkWindow(ScopeCaptureWindow.Snapshot target,RuntimeSnapshot config) {
        LinkWindow pending=linkWindow;
        if(pending!=null && pending.target==target && pending.config.identity.equals(config.identity))linkWindow=null;
    }

    private void prepareLinkWindow(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, RecordingRisk risk,
                                   Runnable ready, Runnable failed) {
        if(!recordingAllowed(risk))return;
        try {
            byte[] token=new byte[24];new java.security.SecureRandom().nextBytes(token);
            LinkWindow pending=new LinkWindow(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token),
                    config,target,inputGeneration.get(),risk,ready,failed);
            linkWindow=pending;
            Intent start=new Intent(LINK_PREPARE).setComponent(new ComponentName(HostPolicy.HOST_PACKAGE,
                    "com.miui.contentextension.services.TextContentExtensionService")).putExtra("link_nonce",pending.nonce);
            if(!recordingAllowed(risk)){if(linkWindow==pending)linkWindow=null;return;}
            committed(risk);
            if(context.get().startService(start)==null){if(linkWindow==pending)linkWindow=null;failed.run();return;}
            worker.postDelayed(()->{if(linkWindow==pending && pending.input==inputGeneration.get()
                    && recordingCurrent(pending.risk)){linkWindow=null;pending.failed.run();}},8_000);
        } catch(RuntimeException unavailable){
            LinkWindow pending=linkWindow;
            if(pending!=null && pending.target==target && pending.risk==risk)linkWindow=null;
            if(recordingCurrent(risk)){event("failed","host_window_unavailable");failed.run();}
        }
    }

    @Override public Bundle linkWindow(Bundle request, RuntimeSnapshot config, boolean ready) {
        LinkWindow pending=linkWindow;
        if(pending==null || request==null || request.size()!=1 || !pending.nonce.equals(request.getString("nonce"))
                || !pending.config.identity.equals(config.identity) || pending.input!=inputGeneration.get()
                || SystemClock.elapsedRealtime()>=pending.until || !physical.released())return Bundle.EMPTY;
        if(!recordingAllowed(pending.risk) || linkWindow!=pending || pending.input!=inputGeneration.get())return Bundle.EMPTY;
        Bundle result=new Bundle();result.putBoolean("accepted",true);
        if(ready) synchronized(pending){
            if(!pending.acknowledged){pending.acknowledged=true;worker.post(()->{
                if(linkWindow==pending && pending.input==inputGeneration.get() && recordingAllowed(pending.risk))pending.ready.run();
            });}
        }
        return result;
    }

    private void scheduleRecognitionFeedback(long expected, RuntimeSnapshot config, int device, long down,
                                             long readyAt, RecordingRisk risk) {
        if (readyAt <= 0 || readyAt == Long.MAX_VALUE) return;
        worker.postDelayed(() -> recognitionFeedback(expected, config, device, down, readyAt, false,
                SystemClock.elapsedRealtime(), risk), Math.max(0, readyAt - SystemClock.elapsedRealtime()));
    }

    private void recognitionFeedback(long expected, RuntimeSnapshot config, int device, long down, long readyAt,
                                     boolean releasedLongPress, long now, RecordingRisk risk) {
        if(!recordingAllowed(risk))return;
        RuntimeServer server = runtime.get();
        RuntimeSnapshot latest = server == null ? null : server.sourceCaptureConfiguration();
        boolean enabled = config != null && (config.recognition || config.scopeCapture || config.scopeLinkProbe)
                && latest != null && latest.identity.equals(config.identity);
        boolean held = expected == generation && gestureActive && gesture.isActive()
                && physical.soleStream(0, device, down);
        if (!recordingCurrent(risk) || recognitionFeedbackSent
                || !LongPressFeedback.recognitionReady(enabled, held, releasedLongPress, readyAt, now)) return;
        recognitionFeedbackSent = true;
        try {
            Context system = context.get();
            Vibrator vibrator = system == null ? null : system.getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator())
                vibrator.vibrate(VibrationEffect.createWaveform(LongPressFeedback.vibrationPattern(false), -1));
        } catch (RuntimeException ignored) { }
    }

    private void deliverCapture(long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config,
                                long began, String url) {
        deliverCapture(expected,picked,config,began,url,riskFor(picked));
    }

    private void deliverCapture(long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config,
                                long began, String url, RecordingRisk risk) {
        Bitmap image = null;
        try {
            long input = gestureInputGeneration;
            RuntimeSnapshot latest = captureConfiguration(expected, picked, config, began, input, risk);
            if (latest == null) return;
            if (latest.scopeCapture && encodingImage) {
                pendingEncoding = () -> deliverCapture(expected, picked, config, began, url, risk);
                return;
            }
            Context system = context.get();
            SystemAssistContent content = assistContent;
            String text = content == null ? "" : content.read(picked, latest, began, sourceDown).deliveryText();
            if (nativeSince(picked.source, began) && !config.scopeLinkProbe && text.isBlank()) return;
            var host = system.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS);
            if (!HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode())
                    || host.applicationInfo == null || !host.applicationInfo.enabled) return;
            if (latest.scopeCapture) {
                try {
                    if(!recordingAllowed(risk))return;
                    committed(risk);
                    image = windows.captureBehindHost(atms.get(), picked, host.applicationInfo.uid);
                    if (image == null || image.isRecycled()) throw new IllegalStateException("scope_image_missing");
                    encodeImage(image, expected, picked, config, began, input, host.applicationInfo.uid, text, url, risk);
                    image = null;
                    return;
                } catch (ReflectiveOperationException | RuntimeException unavailable) {
                    if (!latest.scopeLinkProbe) throw unavailable;
                    event("failed", reasonOf(unavailable));
                }
            }
            submitCapture(expected, picked, config, began, input, host.applicationInfo.uid, text, url, null, risk);
        } catch (ReflectiveOperationException | PackageManager.NameNotFoundException | RuntimeException failure) {
            clearStore(); event("failed", reasonOf(failure));
        } finally { if (image != null && !image.isRecycled()) image.recycle(); }
    }

    private RuntimeSnapshot captureConfiguration(long expected, ScopeCaptureWindow.Snapshot picked,
                                                 RuntimeSnapshot config, long began, long input, RecordingRisk risk) {
        if(!recordingAllowed(risk))return null;
        RuntimeServer server = runtime.get();
        RuntimeSnapshot latest = server == null ? null : server.sourceCaptureConfiguration();
        return config != null && expected == generation && input == gestureInputGeneration && input == inputGeneration.get()
                && !DragGestureGate.suppressLongPress() && latest != null && latest.identity.equals(config.identity)
                && recordingCurrent(risk) && unlocked(context.get()) && physical.released()
                && !nativeUrlSince(picked.source, began) ? latest : null;
    }

    private void encodeImage(Bitmap image, long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config,
                             long began, long input, int hostUid, String text, String url, RecordingRisk risk) {
        encodingImage = true;
        try {
            imageEncoder.execute(() -> {
                byte[] png = null;
                RuntimeException failure = null;
                try {
                    if (image.isRecycled()) throw new IllegalStateException("scope_image_missing");
                    var output = new LimitedPng();
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("scope_image_encode");
                    png = output.toByteArray();
                } catch (RuntimeException unavailable) { failure = unavailable; }
                finally { if (!image.isRecycled()) image.recycle(); }
                byte[] encoded = png;
                RuntimeException error = failure;
                worker.post(() -> {
                    encodingImage = false;
                    try {
                        if (captureConfiguration(expected, picked, config, began, input, risk) == null) return;
                        if (error != null) {
                            event("failed", reasonOf(error));
                            if (!config.scopeLinkProbe) return;
                        }
                        submitCapture(expected, picked, config, began, input, hostUid, text, url, encoded, risk);
                    } finally {
                        Runnable pending = pendingEncoding; pendingEncoding = null;
                        if (pending != null) pending.run();
                    }
                });
            });
        } catch (RuntimeException rejected) { encodingImage = false; throw rejected; }
    }

    private void submitCapture(long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config,
                               long began, long input, int hostUid, String text, String url, byte[] png, RecordingRisk risk) {
        try {
            RuntimeSnapshot latest = captureConfiguration(expected, picked, config, began, input, risk);
            if (latest == null || (nativeSince(picked.source, began) && !latest.scopeLinkProbe && text.isBlank())) return;
            Context system = context.get();
            var host = system.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS);
            if (!HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode()) || host.applicationInfo == null
                    || !host.applicationInfo.enabled || host.applicationInfo.uid != hostUid) return;
            if (png != null) windows.validateBehindHost(atms.get(), picked, hostUid);
            else {
                try { windows.validate(atms.get(), picked); }
                catch (IllegalStateException blocked) {
                    if (!"capture_focus".equals(blocked.getMessage()) || (url == null && !latest.scopeLinkProbe)) throw blocked;
                }
            }
            if (png == null && url == null && text.isBlank() && !latest.scopeLinkProbe) return;
            if (captureConfiguration(expected, picked, config, began, input, risk) == null) return;
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
            if (!text.isBlank()) data.putString("text", text);
            pendingBegan = began;
            String nonce = png != null ? store.issueImage(host.applicationInfo.uid, latest, data, png, url)
                    : !text.isBlank() ? store.issueContent(host.applicationInfo.uid, latest, data, text, url)
                    : url != null ? store.issueText(host.applicationInfo.uid, latest, data, url)
                    : store.issueResume(host.applicationInfo.uid, latest, data);
            pendingCapture=new PendingCapture(picked,risk,input,nonce);
            pendingTarget = picked;
            pendingInputGeneration = input;
            Intent start = new Intent(ACTION).setComponent(new ComponentName(HostPolicy.HOST_PACKAGE,
                    "com.miui.contentextension.services.TextContentExtensionService")).putExtra("scope_nonce", nonce);
            if(!recordingAllowed(risk))return;
            committed(risk);
            if (system.startService(start) == null) { clearStore(); throw new IllegalStateException("scope_host_unavailable"); }
            event("submitted", "none");
        } catch (ReflectiveOperationException | PackageManager.NameNotFoundException | RuntimeException failure) {
            clearStore(); event("failed", reasonOf(failure));
        }
    }

    @Override public Bundle take(int owner, Bundle request, RuntimeSnapshot config) {
        PendingCapture captured=pendingCapture;
        return captured==null ? takeLegacy(owner,request,config) : take(owner,request,config,captured);
    }

    @Override public Bundle scopeRecordingRisk(Bundle request, RuntimeSnapshot config,
                                               RuntimeServer.PolicyOperation operation) {
        if (request == null || request.size() != 2 || config == null || operation == null
                || !(request.get("source") instanceof String source)
                || !(request.get("activity") instanceof String activity))
            return hostRiskReply(false, "request_invalid");
        RecordingRisk risk = recordingRisk;
        if (risk == null) return hostRiskReply(false, "no_owner");
        if (!hostRiskMatches(risk, source, activity, config))
            return hostRiskReply(false, "owner_mismatch");
        if (!operation.belongsTo(runtime.get()) || operation.configuration() != config || !operation.isCurrent())
            return hostRiskReply(false, "policy_stale");
        try {
            boolean observed = false;
            if (!risk.protectedRecording) {
                windows.validate(atms.get(), risk.target);
                observed = observesRecording(risk);
            }
            if (!operation.revalidate()) return hostRiskReply(false, "policy_stale");
            if (!hostRiskMatches(risk, source, activity, config))
                return hostRiskReply(false, "owner_stale");
            windows.validate(atms.get(), risk.target);
            if (!operation.isCurrent()) return hostRiskReply(false, "policy_stale");
            if (!hostRiskMatches(risk, source, activity, config))
                return hostRiskReply(false, "owner_stale");
            if (observed) latchLiveRecordingRisk(risk);
            if (!risk.protectedRecording) return hostRiskReply(false, "recording_unknown");
            return hostRiskReply(true, "protected");
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return hostRiskReply(false, "wms_unavailable");
        }
    }

    private boolean hostRiskMatches(RecordingRisk risk, String source, String activity, RuntimeSnapshot config) {
        return risk != null && source.equals(risk.target.source) && activity.equals(risk.target.activity)
                && liveRecordingOwner(risk, config);
    }

    /** The drag observer can share the latch only for the very same WMS target and DOWN. */
    public boolean protectsDrag(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, int device, long down) {
        RecordingRisk risk = recordingRisk;
        if (risk == null || risk.device != device || risk.down != down || !risk.target.sameTarget(target)
                || !liveRecordingOwner(risk, config)) return false;
        if (!risk.protectedRecording && observesRecording(risk) && liveRecordingOwner(risk, config))
            latchLiveRecordingRisk(risk);
        return risk.protectedRecording && liveRecordingOwner(risk, config);
    }

    private boolean liveRecordingOwner(RecordingRisk risk, RuntimeSnapshot config) {
        return config.identity.equals(risk.config.identity)
                && risk.target.user == 0 && risk.target.display == 0
                && risk.target.uid >= 10000 && risk.target.uid < 100000
                && physical != null && physical.soleStream(risk.target.display, risk.device, risk.down)
                && recordingRisk == risk && risk.input == inputGeneration.get()
                && risk.menu == menuEpoch.get() && !risk.revoked;
    }

    private Bundle hostRiskReply(boolean protectedInteraction, String reason) {
        if (module != null) worker.post(() -> {
            try { event("host_recording_risk", reason); }
            catch (RuntimeException ignored) { }
        });
        if (!protectedInteraction) return Bundle.EMPTY;
        Bundle result = new Bundle();
        result.putBoolean("protected", true);
        return result;
    }

    private void latchLiveRecordingRisk(RecordingRisk risk) {
        synchronized (risk) {
            if (risk.protectedRecording) return;
            risk.protectedRecording = true;
        }
        worker.post(() -> {
            if (recordingRisk != risk || risk.input != inputGeneration.get() || risk.revoked) return;
            event("recording_protected", risk.committed ? "already_committed" : "before_commit");
            retireRecordingRisk(risk);
        });
    }

    private Bundle take(int owner,Bundle request,RuntimeSnapshot config,PendingCapture captured) {
        try {
            if(!pendingCurrent(captured) || request==null || !captured.nonce.equals(request.getString("nonce"))
                    || DragGestureGate.suppressLongPress() || !unlocked(context.get()) || !physical.released()
                    || !recordingAllowed(captured.risk))return Bundle.EMPTY;
            windows.validateBehindHost(atms.get(),captured.target,owner);
            if(!recordingAllowed(captured.risk) || !pendingCurrent(captured))return Bundle.EMPTY;
            Bundle result=store.take(owner,request,config);
            if(result.containsKey("fd") || "text".equals(result.getString("kind"))
                    || "content".equals(result.getString("kind")) || "resume".equals(result.getString("kind")))
                clearPendingCapture(captured,false);
            return result;
        }catch(ReflectiveOperationException | RuntimeException changed){
            captured.revoked=true;
            if(captured.risk!=null)captured.risk.revoked=true;
            clearPendingCapture(captured,true);
            return Bundle.EMPTY;
        }
    }

    private boolean pendingCurrent(PendingCapture captured) {
        return captured!=null && pendingCapture==captured && !captured.revoked
                && captured.input==inputGeneration.get() && recordingCurrent(captured.risk);
    }

    private Bundle takeLegacy(int owner, Bundle request, RuntimeSnapshot config) {
        if(pendingCapture!=null)return Bundle.EMPTY;
        ScopeCaptureWindow.Snapshot target = pendingTarget;
        RecordingRisk risk=riskFor(target);
        try {
            if (DragGestureGate.suppressLongPress() || target == null || pendingInputGeneration != inputGeneration.get()
                    || !unlocked(context.get()) || !physical.released()) return Bundle.EMPTY;
            if(!recordingAllowed(risk))return Bundle.EMPTY;
            windows.validateBehindHost(atms.get(), target, owner);
            if(!recordingAllowed(risk) || pendingCapture!=null || pendingTarget!=target
                    || pendingInputGeneration!=inputGeneration.get())return Bundle.EMPTY;
            Bundle result = store.take(owner, request, config);
            if (result.containsKey("fd") || "text".equals(result.getString("kind"))
                    || "content".equals(result.getString("kind"))
                    || "resume".equals(result.getString("kind"))) {
                if(pendingCapture==null && pendingTarget==target)pendingTarget=null;
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException changed) {
            if(pendingCapture==null){store.clear();pendingTarget=null;}
            return Bundle.EMPTY;
        }
    }

    private void clearPendingCapture(PendingCapture captured,boolean clearStore) {
        captured.revoked=true;
        worker.post(()->{
            if(pendingCapture!=captured)return;
            if(clearStore)store.clear();
            if(pendingTarget==captured.target)pendingTarget=null;
        });
    }

    private void clearStore() {
        PendingCapture captured=pendingCapture;
        if(captured!=null)captured.revoked=true;
        store.clear();
    }
    @Override public Bundle recognitionVisible(Bundle request, RuntimeSnapshot config) {
        Bundle result = new Bundle();
        if (request == null || request.size() != 2 || !(request.get("source") instanceof String source)
                || !(request.get("shown_elapsed") instanceof Long shown)) return result;
        result.putBoolean("accepted", recognitionMenus.shown(source, shown));
        if (module != null) module.log(4,"TaplusBridge","recognition menu stage=visible outcome="
                + (result.getBoolean("accepted") ? "accepted" : "rejected"));
        return result;
    }

    @Override public void invalidateRecognitionRetention() {
        menuRetention.cancel();
        RecordingRisk risk=recordingRisk;
        if(risk!=null)risk.revoked=true;
    }

    @Override public Bundle retainRecognition(Bundle request,RuntimeSnapshot config) {
        return retainRecognition(request,config,null);
    }

    @Override public Bundle retainRecognition(Bundle request,RuntimeSnapshot config,RuntimeServer.PolicyOperation operation) {
        Bundle result=new Bundle();
        if(request==null || request.size()!=3 || !(request.get("source") instanceof String source)
                || !(request.get("activity") instanceof String activity)
                || !(request.get("shown_elapsed") instanceof Long shown))return result;
        MenuTouch touch=menuTouch;
        RecordingRisk risk=touch==null ? null : riskFor(touch.target);
        boolean keep=recordingAllowed(risk) && menuNavigationGuardReady && (operation==null ? menuRetention.claim(source,activity,shown)
                : operation.belongsTo(runtime.get()) && config==operation.configuration()
                        && menuRetention.claim(source,activity,shown,config,
                        ()->recordingCurrent(risk) && operation.isCurrent(),
                        ()->operation.revalidate() && recordingAllowed(risk)));
        keep=keep && recordingAllowed(risk);
        result.putBoolean("keep",keep);
        if(module!=null)module.log(4,"TaplusBridge","recognition menu stage=echo outcome="+(keep?"kept":"passed"));
        return result;
    }

    @Override public void nativeResult(Bundle request) {
        RuntimeServer server = runtime.get();
        RuntimeSnapshot latest = server == null ? null : server.sourceCaptureConfiguration();
        if (latest == null || !latest.channels.enabled(io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel.NATIVE)) return;
        String source = request == null ? null : request.getString("source");
        Object raw = request == null ? null : request.get("native_elapsed");
        long now = SystemClock.elapsedRealtime();
        if (!HostPolicy.validPackage(source) || !(raw instanceof Long at) || at < 0 || at > now || now - at > 10_000) return;
        NativeSignal previous = nativeSignal;
        boolean hasValidUrl = request.getBoolean("has_valid_url");
        if (previous == null || at >= previous.at) nativeSignal = new NativeSignal(source, at, hasValidUrl);
        if (at >= pendingBegan) store.nativeResult(source, hasValidUrl);
        ScopeLinkProbe link = linkProbe;
        if (link != null && hasValidUrl) link.nativeResult(source, at, true);
    }
    private boolean nativeSince(String source, long began) {
        NativeSignal signal = nativeSignal; return signal != null && source.equals(signal.source) && signal.at >= began;
    }
    private boolean nativeUrlSince(String source, long began) {
        NativeSignal signal = nativeSignal; return signal != null && signal.hasValidUrl
                && source.equals(signal.source) && signal.at >= began;
    }
    private void cancel() {
        RecordingRisk risk=recordingRisk;
        revokeRecordingRisk(risk);
        SystemAssistContent content = assistContent;
        if (content != null && origin != null) content.cancelPending(origin, configuration, downElapsed, sourceDown);
        generation++; gestureActive = false; origin = null; configuration = null; gesture.cancel(); pendingEncoding = null;
        recognitionFeedbackSent = false;
        ScopeLinkProbe link = linkProbe;
        if (link != null) {if(risk==null)link.cancel();else link.cancelOwner(risk.target);}
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
        if (module != null) module.log(4, "TaplusBridge", "scope capture stage=" + stage + " reason=" + reason);
    }
    private static String reasonOf(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        if (message != null && message.matches("[A-Za-z0-9_]{1,64}")) return type + ":" + message;
        return type;
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
