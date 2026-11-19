package io.github.yudigaga.taplusbridge.probe;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.drag.LongPressDirection;
import io.github.yudigaga.taplusbridge.drag.LongPressStageSchedule;
import io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag;
import io.github.yudigaga.taplusbridge.runtime.RuntimeServer;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowKeyguardManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowUserManager;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Runs the installed production Pointer Hook and its production worker on separate paused queues. */
final class SystemMenuQueueFixture implements AutoCloseable {
    static final int HOST_UID = 22002;
    final ScopeCaptureWindowAndroidTest.Atms atms = new ScopeCaptureWindowAndroidTest.Atms();
    final ScopeCaptureWindowAndroidTest.Window main = new ScopeCaptureWindowAndroidTest.Window();
    final AtomicReference<Throwable> inputFailure = new AtomicReference<>();
    final AtomicInteger proceededInputs = new AtomicInteger();
    final AtomicInteger captureCalls = new AtomicInteger();
    final AtomicInteger policyReads = new AtomicInteger();
    final AtomicInteger linkTaps = new AtomicInteger();
    volatile Consumer<String> onLog = ignored -> { };
    volatile Runnable beforeWindowAccess = () -> { };
    private final List<MotionEvent> retainedEvents = new ArrayList<>();
    private final AtomicReference<XposedInterface.Hooker> pointerHook = new AtomicReference<>();
    private final AtomicReference<XposedInterface.Hooker> keyHook = new AtomicReference<>();
    private HandlerThread inputThread;
    private Handler input, worker;
    private ShadowLooper inputLooper, workerLooper;
    private RuntimeServer runtime;
    private SystemProbe probe;
    private ScopeCaptureWindow windows;
    PhysicalTouchState physical;
    SystemScopeCapture capture;
    ScopeCaptureWindowAndroidTest.Window menu;
    long downUptime, beganElapsed, shownElapsed;

    SystemMenuQueueFixture() throws Exception {
        this(false, false);
    }

    SystemMenuQueueFixture(boolean scopeCapture, boolean scopeLinkProbe) throws Exception {
        this(scopeCapture, scopeLinkProbe, false);
    }

    static SystemMenuQueueFixture scopeProbe(boolean scopeCapture, boolean scopeLinkProbe) throws Exception {
        return new SystemMenuQueueFixture(scopeCapture, scopeLinkProbe, true);
    }

    static SystemMenuQueueFixture scopeProbe(boolean scopeCapture, boolean scopeLinkProbe,
                                             SourceRecordingState.Source recording) throws Exception {
        return new SystemMenuQueueFixture(scopeCapture, scopeLinkProbe, true, recording);
    }

    private SystemMenuQueueFixture(boolean scopeCapture, boolean scopeLinkProbe, boolean scopeProbeBackend) throws Exception {
        this(scopeCapture, scopeLinkProbe, scopeProbeBackend, null);
    }

    private SystemMenuQueueFixture(boolean scopeCapture, boolean scopeLinkProbe, boolean scopeProbeBackend,
                                   SourceRecordingState.Source recording) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", 0).edit().clear().commit();
        ConfigStore settings = new ConfigStore(context);
        settings.setEnabled(true);
        settings.setRecognitionEnabled(true);
        settings.setDragSettings(new DragSettings(false, "portal", "share", "lens", "translate", false,
                List.of(), 100, 100));
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), scopeCapture, scopeLinkProbe));
        var stored = context.getSharedPreferences("menu_queue_test", 0);
        stored.edit().clear().putString(RuntimeSnapshot.KEY, RuntimeSnapshot.from(context)).commit();
        ((ShadowPowerManager) Shadow.extract(context.getSystemService(PowerManager.class))).setIsInteractive(true);
        ((ShadowKeyguardManager) Shadow.extract(context.getSystemService(KeyguardManager.class))).setKeyguardLocked(false);
        ((ShadowUserManager) Shadow.extract(context.getSystemService(UserManager.class))).setUserUnlocked(true);
        Settings.System.putInt(context.getContentResolver(), "content_catcher_network_enabled_content_extension", 1);
        Settings.System.putInt(context.getContentResolver(), "support_long_press_mode", 1);
        Settings.System.putInt(context.getContentResolver(), "support_long_press_time_out", 100);
        install(context, BridgeContract.HOST_PACKAGE, HOST_UID, "5.1.5", 50015);
        install(context, BridgeContract.APP_PACKAGE, 22003, "1", 1);

        XposedModule module = new XposedModule() { };
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (owner, method, args) -> {
                    if ("log".equals(method.getName())) { onLog.accept((String) args[2]); return null; }
                    if (!"hook".equals(method.getName())) throw new AssertionError(method.getName());
                    Method hooked = (Method) args[0];
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, operation, values) -> {
                                if ("intercept".equals(operation.getName())) {
                                    (hooked.getDeclaringClass() == PointerDispatcher.class ? pointerHook : keyHook)
                                            .set((XposedInterface.Hooker) values[0]);
                                    return null;
                                }
                                return builder;
                            });
                });
        module.attachFramework(framework, () -> { });
        var constructor = SystemProbe.class.getDeclaredConstructor(XposedModule.class);
        constructor.setAccessible(true);
        probe = constructor.newInstance(module);
        worker = ReflectionHelpers.getField(probe, "worker");
        try {
            workerLooper = Shadow.extract(worker.getLooper());
            workerLooper.pause();
            inputThread = new HandlerThread("TaplusBridge-test-raw-input");
            inputThread.start();
            input = new Handler(inputThread.getLooper());
            inputLooper = Shadow.extract(input.getLooper());
            inputLooper.pause();
            assertNotSame(input.getLooper(), worker.getLooper());

            ClassLoader pointerOnly = new ClassLoader(getClass().getClassLoader()) {
                @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                    if (name.equals("com.android.server.wm.PointerEventDispatcher")) return PointerDispatcher.class;
                    if (name.equals("com.android.server.wm.ActivityTaskManagerService")) throw new ClassNotFoundException(name);
                    if (name.equals("com.android.server.input.InputManagerService")) return InputManager.class;
                    return super.loadClass(name);
                }
            };
            Method start = SystemProbe.class.getDeclaredMethod("start", ClassLoader.class);
            start.setAccessible(true);
            InvocationTargetException stopped = assertThrows(InvocationTargetException.class,
                    () -> start.invoke(probe, pointerOnly));
            assertEquals("com.android.server.wm.ActivityTaskManagerService", stopped.getCause().getMessage());
            assertNotNull(pointerHook.get());
            physical = ReflectionHelpers.getField(probe, "physical");
            ReflectionHelpers.setField(probe, "system", context);
            runtime = new RuntimeServer(context, worker, () -> {
                policyReads.incrementAndGet();
                return stored;
            }, ignored -> { });
            main.mActivityRecord.main = main;
            atms.mWindowManager.mRoot.display.hit = main;
            atms.mWindowManager.mRoot.display.mCurrentFocus = main;
            windows = new ScopeCaptureWindow(ScopeCaptureWindowAndroidTest.Atms.class,
                    new ScopeCaptureWindow.CaptureBackend() {
                        public Class<?> surfaceType() { return Object.class; }
                        public Bitmap capture(Object layer, Object[] excluded, int uid, Rect crop, float scale) {
                            captureCalls.incrementAndGet();
                            if (scopeProbeBackend) return null;
                            throw new AssertionError("Menu cleanup must not capture pixels");
                        }
                    });
            capture = recording == null
                    ? new SystemScopeCapture(module, worker, () -> context,
                            () -> { beforeWindowAccess.run(); return atms; }, () -> runtime, windows, physical)
                    : new SystemScopeCapture(module, worker, () -> context,
                            () -> { beforeWindowAccess.run(); return atms; }, () -> runtime,
                            windows, physical, Runnable::run, recording);
            if (scopeLinkProbe) capture.setLinkProbe(new ScopeLinkProbe(worker, physical,
                    runtime::sourceCaptureConfiguration, target -> windows.validate(atms, target),
                    (target, action, at) -> { linkTaps.incrementAndGet(); return true; }, ignored -> { }));
            runtime.setSourceCapture(capture);
            ReflectionHelpers.setField(probe, "scopeCapture", capture);
            assertTrue(MenuKeyInputHooks.install(module, pointerOnly, () -> capture));
            assertNotNull(keyHook.get());
            capture.setMenuNavigationGuardReady(true);
        } catch (Exception | Error failed) {
            close();
            throw failed;
        }
    }

    void begin() {
        beginInputOnly();
        drainWorker();
        assertTrue(physical.soleStream(0, 0, downUptime));
    }

    void beginInputOnly() {
        downUptime = SystemClock.uptimeMillis();
        beganElapsed = SystemClock.elapsedRealtime();
        queue(MotionEvent.ACTION_DOWN, 0);
        drainInput();
    }

    void advanceTo(long elapsedFromDown) {
        long delta = beganElapsed + elapsedFromDown - SystemClock.elapsedRealtime();
        assertTrue("The test clock cannot move backwards", delta >= 0);
        ShadowSystemClock.advanceBy(Duration.ofMillis(delta));
    }

    ScopeCaptureWindowAndroidTest.FakeBackCallback showMenu(long elapsedFromDown) {
        var callback = installMenu(elapsedFromDown);
        assertTrue(visible(shownElapsed));
        drainWorker();
        return callback;
    }

    ScopeCaptureWindowAndroidTest.FakeBackCallback installMenu(long elapsedFromDown) {
        advanceTo(elapsedFromDown);
        menu = new ScopeCaptureWindowAndroidTest.Window();
        menu.mAttrs.type = WindowManager.LayoutParams.TYPE_APPLICATION;
        menu.mActivityRecord = main.mActivityRecord;
        menu.bounds = new Rect(10, 100, 200, 300);
        var callback = new ScopeCaptureWindowAndroidTest.FakeBackCallback(atms.mWindowManager);
        menu.backInfo = new ScopeCaptureWindowAndroidTest.FakeBackInfo(callback);
        var display = atms.mWindowManager.mRoot.display;
        display.additionalWindows.add(main);
        display.hit = menu;
        display.mCurrentFocus = menu;
        shownElapsed = SystemClock.elapsedRealtime();
        return callback;
    }

    void restoreMainWindow() {
        var display = atms.mWindowManager.mRoot.display;
        display.hit = main;
        display.mCurrentFocus = main;
        display.additionalWindows.clear();
    }

    RuntimeSnapshot configuration() { return runtime.sourceCaptureConfiguration(); }

    SystemLongPressDrag transferStream(int device, long down) throws Exception {
        var constructor = SystemLongPressDrag.class.getDeclaredConstructor(XposedModule.class, Handler.class,
                Supplier.class, Supplier.class, Supplier.class, ScopeCaptureWindow.class, PhysicalTouchState.class);
        constructor.setAccessible(true);
        var drag = constructor.newInstance(null, worker, (Supplier<Context>) RuntimeEnvironment::getApplication,
                (Supplier<Object>) () -> atms, (Supplier<RuntimeServer>) () -> runtime, windows, physical);
        var target = ReflectionHelpers.<ScopeCaptureWindow.Snapshot>getField(capture, "origin");
        assertNotNull(target);
        var gesture = new LongPressDirection(down, device, target.x, target.y, 100, 8, 200);
        Class<?> runType = Class.forName(SystemLongPressDrag.class.getName() + "$Run");
        var runConstructor = runType.getDeclaredConstructor(ScopeCaptureWindow.Snapshot.class, RuntimeSnapshot.class,
                LongPressDirection.class, long.class, LongPressStageSchedule.Deadlines.class,
                float.class, float.class, float.class, float.class, float.class);
        runConstructor.setAccessible(true);
        Object run = runConstructor.newInstance(target, configuration(), gesture, beganElapsed,
                LongPressStageSchedule.fromDown(beganElapsed, 100, 100, 100), 8f, 8f,
                (float) target.bounds.width(), (float) target.bounds.height(), 1f);
        // Prepare an already transferred owner; its incoming events still use the installed Hook.
        ReflectionHelpers.setField(run, "transferred", true);
        ReflectionHelpers.setField(drag, "current", run);
        ReflectionHelpers.callInstanceMethod(drag, "publishInput");
        ReflectionHelpers.setField(probe, "longPressDrag", drag);
        assertTrue(drag.transferred());
        return drag;
    }

    boolean visible(long shown) {
        Bundle request = new Bundle();
        request.putString("source", main.mAttrs.packageName);
        request.putLong("shown_elapsed", shown);
        return runtime.handle(HOST_UID, "recognition_visible", request).getBoolean("accepted");
    }

    Bundle callRuntime(int callerUid, String method, Bundle request) {
        return runtime.handle(callerUid, method, request);
    }

    boolean retain() {
        Bundle request = new Bundle();
        request.putString("source", main.mAttrs.packageName);
        request.putString("activity", main.mActivityRecord.mActivityComponent.getClassName());
        request.putLong("shown_elapsed", shownElapsed);
        return runtime.handle(HOST_UID, "recognition_menu_retain", request).getBoolean("keep");
    }

    void queue(int action, long originalEventOffset) {
        queue(action, downUptime + originalEventOffset, 0, downUptime, 1, InputDevice.SOURCE_TOUCHSCREEN);
    }

    void queueAt(int action, long originalEventOffset, float x, float y) {
        queue(action, downUptime + originalEventOffset, 0, downUptime, 1, InputDevice.SOURCE_TOUCHSCREEN, x, y);
    }

    void queue(int action, long eventUptime, int device, long gestureDown, int count, int source) {
        queue(action, eventUptime, device, gestureDown, count, source, 50, 200);
    }

    private void queue(int action, long eventUptime, int device, long gestureDown, int count, int source, float x, float y) {
        queueOnDisplay(action, eventUptime, device, gestureDown, count, source, 0, x, y);
    }

    void queueOnDisplay(int action, long eventUptime, int device, long gestureDown, int count, int source, int display) {
        queueOnDisplay(action, eventUptime, device, gestureDown, count, source, display, 50, 200);
    }

    private void queueOnDisplay(int action, long eventUptime, int device, long gestureDown, int count, int source,
                                int display, float x, float y) {
        queueOnDisplay(action, eventUptime, device, gestureDown, count, source, display, x, y, 0, 0);
    }

    MotionEvent queueSinglePointer(int action, long eventUptime, int device, long gestureDown, int source,
                                   int display, int pointerId, int flags, float x, float y) {
        return queueOnDisplay(action, eventUptime, device, gestureDown, 1, source, display, x, y, pointerId, flags);
    }

    private MotionEvent queueOnDisplay(int action, long eventUptime, int device, long gestureDown, int count, int source,
                                       int display, float x, float y, int firstPointerId, int flags) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = firstPointerId + i;
            properties[i].toolType = source == InputDevice.SOURCE_STYLUS
                    ? MotionEvent.TOOL_TYPE_STYLUS : source == InputDevice.SOURCE_MOUSE
                    ? MotionEvent.TOOL_TYPE_MOUSE : MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = x + i * 10;
            coordinates[i].y = y;
            coordinates[i].pressure = 1;
            coordinates[i].size = 1;
        }
        MotionEvent original = MotionEvent.obtain(gestureDown, eventUptime, action, count, properties, coordinates,
                0, 0, 1, 1, device, 0, source, flags);
        retainedEvents.add(original);
        if (display != 0) ReflectionHelpers.callInstanceMethod(original, "setDisplayId",
                ReflectionHelpers.ClassParameter.from(int.class, display));
        assertEquals("The queued event must expose its requested display before entering the Hook", display,
                (int) ReflectionHelpers.<Integer>callInstanceMethod(original, "getDisplayId"));
        // Ownership stays with this queue. No physical state is changed until the installed Hook runs.
        assertTrue(input.post(() -> intercept(pointerHook.get(), original)));
        return original;
    }

    void queueBack() {
        KeyEvent original = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
        assertTrue(input.post(() -> intercept(keyHook.get(), original)));
    }

    private void intercept(XposedInterface.Hooker hook, InputEvent original) {
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (owner, method, args) -> switch (method.getName()) {
                    case "getArg" -> original;
                    case "proceed" -> {
                        proceededInputs.incrementAndGet(); yield original instanceof KeyEvent ? 0 : null;
                    }
                    default -> throw new AssertionError(method.getName());
                });
        try { hook.intercept(chain); }
        catch (Throwable failed) { inputFailure.compareAndSet(null, failed); }
    }

    void drainInput() {
        inputLooper.idle();
        Throwable failed = inputFailure.get();
        if (failed != null) throw new AssertionError("The installed input Hook failed", failed);
    }

    void drainWorker() { workerLooper.idle(); }

    @Override public void close() throws InterruptedException {
        if (input != null) input.removeCallbacksAndMessages(null);
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (inputLooper != null) inputLooper.unPause();
        if (workerLooper != null) workerLooper.unPause();
        if (input != null) input.getLooper().quitSafely();
        if (worker != null) worker.getLooper().quitSafely();
        if (inputThread != null) inputThread.join(2_000);
        if (worker != null) worker.getLooper().getThread().join(2_000);
        for (MotionEvent retained : retainedEvents) retained.recycle();
        retainedEvents.clear();
        if (inputThread != null) assertFalse("The input fixture thread must stop", inputThread.isAlive());
        if (worker != null) assertFalse("The probe fixture worker must stop", worker.getLooper().getThread().isAlive());
    }

    private static void install(Context context, String name, int uid, String version, int code) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.versionName = version;
        info.setLongVersionCode(code);
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = name;
        info.applicationInfo.uid = uid;
        info.applicationInfo.enabled = true;
        info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).installPackage(info);
    }

    public static final class PointerDispatcher { public void onInputEvent(InputEvent event) { } }
    public static final class InputManager { private int interceptKeyBeforeQueueing(KeyEvent event, int flags) { return 0; } }
}
