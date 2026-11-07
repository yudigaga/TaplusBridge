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
        final Runnable ready;
        final Runnable failed;
        boolean acknowledged;
        LinkWindow(String nonce, RuntimeSnapshot config, ScopeCaptureWindow.Snapshot target, long input, Runnable ready, Runnable failed) {
            this.nonce=nonce;this.config=config;this.target=target;this.input=input;this.ready=ready;this.failed=failed;
            until=SystemClock.elapsedRealtime()+8_000;
        }
    }
    private volatile LinkWindow linkWindow;
    private final XposedModule module;
    private final Handler worker;
    private final Executor imageEncoder;
    // Owned by the pointer worker; a waiting request retains no screenshot pixels.
    private boolean encodingImage;
    private Runnable pendingEncoding;
    private final Supplier<Context> context;
    private final Supplier<Object> atms;
    private final Supplier<RuntimeServer> runtime;
    private final ScopeCaptureWindow windows;
    private final PhysicalTouchState physical;
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
    private volatile ScopeLinkProbe linkProbe;
    public ScopeLinkProbe linkProbe() { return linkProbe; }
    public void setLinkProbe(ScopeLinkProbe probe) { linkProbe = probe; }
    public void validateLinkTarget(ScopeCaptureWindow.Snapshot target) throws ReflectiveOperationException {
        windows.validate(atms.get(), target);
    }
    public void validateLinkTargetBehind(ScopeCaptureWindow.Snapshot target, int hostUid) throws ReflectiveOperationException {
        if (hostUid >= 10000 && hostUid < 100000) windows.validateBehindHost(atms.get(), target, hostUid);
        else windows.validate(atms.get(), target);
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
        cancel();
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
        this.module = module; this.worker = worker; this.context = context; this.atms = atms;
        this.runtime = runtime; this.physical = physical; this.windows = windows; this.imageEncoder = imageEncoder;
    }

    /** Copy only touch primitives before returning to the system input dispatcher. */
    public void pointer(MotionEvent event, int display, boolean exclusiveAtEvent) {
        if (DragGestureGate.suppressLongPress()) {
            ScopeLinkProbe link = linkProbe; if (link != null) link.cancel();
            worker.post(this::cancel); return;
        }
        int action = event.getActionMasked(), count = event.getPointerCount(), device = event.getDeviceId();
        long observedInput = action == MotionEvent.ACTION_DOWN ? inputGeneration.incrementAndGet() : inputGeneration.get();
        if (action == MotionEvent.ACTION_DOWN) { store.clear(); pendingTarget = null; linkWindow = null; }
        long down = event.getDownTime(), at = event.getEventTime();
        long eventElapsed = SystemClock.elapsedRealtime() - Math.max(0, SystemClock.uptimeMillis() - at);
        float x = event.getRawX(), y = event.getRawY();
        float x2 = count > 1 ? event.getRawX(1) : Float.NaN, y2 = count > 1 ? event.getRawY(1) : Float.NaN;
        ScopeLinkProbe link = linkProbe;
        if (link != null) link.pointer(action, count, x, y, down, at, slop);
        worker.post(() -> event(display, device, down, at, eventElapsed, action, count, x, y, x2, y2, exclusiveAtEvent, observedInput));
    }

    private void event(int display, int device, long down, long at, long eventElapsed, int action, int count,
                       float x, float y, float x2, float y2, boolean exclusiveAtEvent, long observedInput) {
        if (action == MotionEvent.ACTION_DOWN) {
            gestureInputGeneration = observedInput;
            store.clear(); pendingTarget = null;
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
        }
        try {
            if (DragGestureGate.suppressLongPress()) { cancel(); return; }
            Context system = context.get(); RuntimeServer server = runtime.get();
            if (system == null || server == null || !unlocked(system)) { cancel(); return; }
            if (action == MotionEvent.ACTION_DOWN) {
                if (gestureActive || !exclusiveAtEvent) { cancel(); return; }
                cancel();
                configuration = server.sourceCaptureConfiguration();
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
                if (mode == 1) scheduleRecognitionFeedback(generation, configuration, device, down, recognitionAt);
                SystemAssistContent content = assistContent;
                if (mode == 1 && content != null && configuration.recognition)
                    content.arm(origin, configuration, down, downElapsed, recognitionAt, device);
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
                recognitionFeedback(generation, configuration, device, down, recognitionAt, true, eventElapsed);
                gestureActive = false;
                event("gesture", "none");
                long expected = generation;
                var picked = origin; var config = configuration; long began = downElapsed, readyAt = recognitionAt;
                long releasedAt = eventElapsed;
                // Screenshot-only mode lets ROM capture win; link mode owns the original click first.
                worker.postDelayed(() -> capture(expected, picked, config, began, readyAt, releasedAt), 300);
            } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) cancel();
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            cancel(); event("rejected", reasonOf(unavailable));
        }
    }

    private void capture(long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config, long began,
                         long readyAt, long releasedAt) {
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
            worker.postDelayed(() -> capture(expected, picked, config, began, readyAt, releasedAt), 50); return;
        }
        SystemAssistContent.Result provided = content == null ? SystemAssistContent.Result.EMPTY : content.read(picked, latest, began, sourceDown);
        if (nativeSince(picked.source, began) && !latest.scopeLinkProbe && provided.deliveryText().isBlank()) return;
        if (!provided.links().isEmpty()) {
            if (link != null) link.cancel();
            deliverCapture(expected, picked, config, began, null); return;
        }
        if (latest.scopeLinkProbe && link != null) {
            prepareLinkWindow(picked, latest, () -> link.confirm(picked, latest, readyAt, releasedAt,
                    url -> { clearLinkWindow(picked,latest);worker.post(() -> deliverCapture(expected, picked, config, began, url)); }),
                    ()->{link.cancel();deliverCapture(expected,picked,config,began,null);});
        } else deliverCapture(expected, picked, config, began, null);
    }

    public void captureDragLink(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long down,
                                long readyAt, long releasedAt, java.util.function.Consumer<String> complete) {
        ScopeLinkProbe link=linkProbe;
        if(link==null){complete.accept(null);return;}
        prepareLinkWindow(target,config,()->link.confirmDrag(target,config,down,readyAt,releasedAt,
                url->{clearLinkWindow(target,config);complete.accept(url);}),()->{link.cancel();complete.accept(null);});
    }

    private void clearLinkWindow(ScopeCaptureWindow.Snapshot target,RuntimeSnapshot config) {
        LinkWindow pending=linkWindow;
        if(pending!=null && pending.target==target && pending.config.identity.equals(config.identity))linkWindow=null;
    }

    private void prepareLinkWindow(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, Runnable ready, Runnable failed) {
        try {
            byte[] token=new byte[24];new java.security.SecureRandom().nextBytes(token);
            LinkWindow pending=new LinkWindow(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token),
                    config,target,inputGeneration.get(),ready,failed);
            linkWindow=pending;
            Intent start=new Intent(LINK_PREPARE).setComponent(new ComponentName(HostPolicy.HOST_PACKAGE,
                    "com.miui.contentextension.services.TextContentExtensionService")).putExtra("link_nonce",pending.nonce);
            if(context.get().startService(start)==null){linkWindow=null;failed.run();return;}
            worker.postDelayed(()->{if(linkWindow==pending){linkWindow=null;pending.failed.run();}},8_000);
        } catch(RuntimeException unavailable){linkWindow=null;event("failed","host_window_unavailable");failed.run();}
    }

    @Override public Bundle linkWindow(Bundle request, RuntimeSnapshot config, boolean ready) {
        LinkWindow pending=linkWindow;
        if(pending==null || request==null || request.size()!=1 || !pending.nonce.equals(request.getString("nonce"))
                || !pending.config.identity.equals(config.identity) || pending.input!=inputGeneration.get()
                || SystemClock.elapsedRealtime()>=pending.until || !physical.released())return Bundle.EMPTY;
        Bundle result=new Bundle();result.putBoolean("accepted",true);
        if(ready) synchronized(pending){
            if(!pending.acknowledged){pending.acknowledged=true;worker.post(()->{
                if(linkWindow==pending && pending.input==inputGeneration.get())pending.ready.run();
            });}
        }
        return result;
    }

    private void scheduleRecognitionFeedback(long expected, RuntimeSnapshot config, int device, long down,
                                             long readyAt) {
        if (readyAt <= 0 || readyAt == Long.MAX_VALUE) return;
        worker.postDelayed(() -> recognitionFeedback(expected, config, device, down, readyAt, false,
                SystemClock.elapsedRealtime()), Math.max(0, readyAt - SystemClock.elapsedRealtime()));
    }

    private void recognitionFeedback(long expected, RuntimeSnapshot config, int device, long down, long readyAt,
                                     boolean releasedLongPress, long now) {
        RuntimeServer server = runtime.get();
        RuntimeSnapshot latest = server == null ? null : server.sourceCaptureConfiguration();
        boolean enabled = config != null && (config.recognition || config.scopeCapture || config.scopeLinkProbe)
                && latest != null && latest.identity.equals(config.identity);
        boolean held = expected == generation && gestureActive && gesture.isActive()
                && physical.soleStream(0, device, down);
        if (recognitionFeedbackSent
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
        Bitmap image = null;
        try {
            long input = gestureInputGeneration;
            RuntimeSnapshot latest = captureConfiguration(expected, picked, config, began, input);
            if (latest == null) return;
            if (latest.scopeCapture && encodingImage) {
                pendingEncoding = () -> deliverCapture(expected, picked, config, began, url);
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
                    image = windows.captureBehindHost(atms.get(), picked, host.applicationInfo.uid);
                    if (image == null || image.isRecycled()) throw new IllegalStateException("scope_image_missing");
                    encodeImage(image, expected, picked, config, began, input, host.applicationInfo.uid, text, url);
                    image = null;
                    return;
                } catch (ReflectiveOperationException | RuntimeException unavailable) {
                    if (!latest.scopeLinkProbe) throw unavailable;
                    event("failed", reasonOf(unavailable));
                }
            }
            submitCapture(expected, picked, config, began, input, host.applicationInfo.uid, text, url, null);
        } catch (ReflectiveOperationException | PackageManager.NameNotFoundException | RuntimeException failure) {
            store.clear(); event("failed", reasonOf(failure));
        } finally { if (image != null && !image.isRecycled()) image.recycle(); }
    }

    private RuntimeSnapshot captureConfiguration(long expected, ScopeCaptureWindow.Snapshot picked,
                                                 RuntimeSnapshot config, long began, long input) {
        RuntimeServer server = runtime.get();
        RuntimeSnapshot latest = server == null ? null : server.sourceCaptureConfiguration();
        return config != null && expected == generation && input == gestureInputGeneration && input == inputGeneration.get()
                && !DragGestureGate.suppressLongPress() && latest != null && latest.identity.equals(config.identity)
                && unlocked(context.get()) && physical.released() && !nativeUrlSince(picked.source, began) ? latest : null;
    }

    private void encodeImage(Bitmap image, long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config,
                             long began, long input, int hostUid, String text, String url) {
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
                        if (captureConfiguration(expected, picked, config, began, input) == null) return;
                        if (error != null) {
                            event("failed", reasonOf(error));
                            if (!config.scopeLinkProbe) return;
                        }
                        submitCapture(expected, picked, config, began, input, hostUid, text, url, encoded);
                    } finally {
                        Runnable pending = pendingEncoding; pendingEncoding = null;
                        if (pending != null) pending.run();
                    }
                });
            });
        } catch (RuntimeException rejected) { encodingImage = false; throw rejected; }
    }

    private void submitCapture(long expected, ScopeCaptureWindow.Snapshot picked, RuntimeSnapshot config,
                               long began, long input, int hostUid, String text, String url, byte[] png) {
        try {
            RuntimeSnapshot latest = captureConfiguration(expected, picked, config, began, input);
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
            if (captureConfiguration(expected, picked, config, began, input) == null) return;
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
            pendingTarget = picked;
            pendingInputGeneration = input;
            Intent start = new Intent(ACTION).setComponent(new ComponentName(HostPolicy.HOST_PACKAGE,
                    "com.miui.contentextension.services.TextContentExtensionService")).putExtra("scope_nonce", nonce);
            if (system.startService(start) == null) { store.clear(); throw new IllegalStateException("scope_host_unavailable"); }
            event("submitted", "none");
        } catch (ReflectiveOperationException | PackageManager.NameNotFoundException | RuntimeException failure) {
            store.clear(); event("failed", reasonOf(failure));
        }
    }

    @Override public Bundle take(int owner, Bundle request, RuntimeSnapshot config) {
        try {
            ScopeCaptureWindow.Snapshot target = pendingTarget;
            if (DragGestureGate.suppressLongPress() || target == null || pendingInputGeneration != inputGeneration.get()
                    || !unlocked(context.get()) || !physical.released()) return Bundle.EMPTY;
            windows.validateBehindHost(atms.get(), target, owner);
            Bundle result = store.take(owner, request, config);
            if (result.containsKey("fd") || "text".equals(result.getString("kind"))
                    || "content".equals(result.getString("kind"))
                    || "resume".equals(result.getString("kind"))) pendingTarget = null;
            return result;
        } catch (ReflectiveOperationException | RuntimeException changed) {
            store.clear(); pendingTarget = null; return Bundle.EMPTY;
        }
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
        SystemAssistContent content = assistContent;
        if (content != null && origin != null) content.cancelPending(origin, configuration, downElapsed, sourceDown);
        generation++; gestureActive = false; origin = null; configuration = null; gesture.cancel(); pendingEncoding = null;
        recognitionFeedbackSent = false;
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
