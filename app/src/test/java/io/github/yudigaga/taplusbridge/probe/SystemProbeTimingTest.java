package io.github.yudigaga.taplusbridge.probe;

import android.os.Handler;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class SystemProbeTimingTest {
    @Test public void transferredUpRemovesTheOriginalStreamFromPhysicalTouchState() {
        PhysicalTouchState physical = new PhysicalTouchState();
        SystemProbe.recordPhysicalTouch(physical, 0, 7, 1_000, 1_000,
                MotionEvent.ACTION_DOWN, 500, 1_000);
        assertFalse(physical.released());
        SystemProbe.recordPhysicalTouch(physical, 0, 7, 1_000, 2_000,
                MotionEvent.ACTION_UP, 1_500, 2_000);
        assertTrue(physical.released());
        assertEquals(1_500, physical.releasedElapsedAt());
    }

    @Test public void anUpDeliveredAfterRecognitionKeepsItsActualEarlierReleaseTime() throws Throwable {
        AtomicReference<XposedInterface.Hooker> pointer = new AtomicReference<>();
        XposedModule module = new XposedModule() { };
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (target, method, args) -> {
                    if ("log".equals(method.getName())) return null;
                    if (!"hook".equals(method.getName())) throw new AssertionError(method.getName());
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, operation, values) -> {
                                if ("intercept".equals(operation.getName())) {
                                    pointer.set((XposedInterface.Hooker) values[0]); return null;
                                }
                                return builder;
                            });
                });
        module.attachFramework(framework, () -> { });
        var constructor = SystemProbe.class.getDeclaredConstructor(XposedModule.class);
        constructor.setAccessible(true);
        SystemProbe probe = constructor.newInstance(module);
        try {
            ClassLoader pointerOnly = new ClassLoader(getClass().getClassLoader()) {
                @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                    if (name.equals("com.android.server.wm.PointerEventDispatcher")) return PointerDispatcher.class;
                    if (name.equals("com.android.server.wm.ActivityTaskManagerService"))
                        throw new ClassNotFoundException(name);
                    return super.loadClass(name);
                }
            };
            Method start = SystemProbe.class.getDeclaredMethod("start", ClassLoader.class);
            start.setAccessible(true);
            InvocationTargetException stopped = assertThrows(InvocationTargetException.class, () -> start.invoke(probe, pointerOnly));
            assertEquals("com.android.server.wm.ActivityTaskManagerService", stopped.getCause().getMessage());
            assertNotNull(pointer.get());
            PhysicalTouchState physical = ReflectionHelpers.getField(probe, "physical");
            long down = SystemClock.uptimeMillis(), began = SystemClock.elapsedRealtime();
            long recognitionAt = began + 500;
            dispatch(pointer.get(), down, down, MotionEvent.ACTION_DOWN);
            ShadowSystemClock.advanceBy(Duration.ofMillis(800));
            dispatch(pointer.get(), down, down + 499, MotionEvent.ACTION_UP);
            assertTrue(physical.released());
            assertEquals("Input delivery latency must not extend a completed system-stage hold",
                    began + 499, physical.releasedElapsedAt());
            assertTrue(physical.releasedElapsedAt() < recognitionAt);
        } finally {
            ((Handler) ReflectionHelpers.getField(probe, "worker")).getLooper().quitSafely();
        }
    }

    private static void dispatch(XposedInterface.Hooker pointer, long down, long at, int action) throws Throwable {
        MotionEvent event = MotionEvent.obtain(down, at, action, 50, 50, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (target, method, args) -> switch (method.getName()) {
                        case "getArg" -> event;
                        case "proceed" -> null;
                        default -> throw new AssertionError(method.getName());
                    });
            pointer.intercept(chain);
        } finally { event.recycle(); }
    }

    public static final class PointerDispatcher {
        public void onInputEvent(InputEvent event) { }
    }
}
