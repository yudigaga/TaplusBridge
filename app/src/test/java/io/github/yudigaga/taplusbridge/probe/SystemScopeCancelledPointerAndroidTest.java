package io.github.yudigaga.taplusbridge.probe;

import android.os.Handler;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import io.github.libxposed.api.XposedInterface;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Installed Pointer Hook regressions for ordinary and canceled two-pointer releases. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = MotionEventDisplayShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeCancelledPointerAndroidTest {
    private static final int COMBINED_CANCELED_FLAGS = MotionEvent.FLAG_CANCELED | MotionEvent.FLAG_WINDOW_IS_OBSCURED;

    @Test public void ordinaryPointerZeroReleaseCapturesOnce() throws Exception {
        run("normal-index0", false, 0, 0, 1, false);
    }

    @Test public void ordinaryPointerOneReleaseCapturesOnce() throws Exception {
        run("normal-index1", false, 1, 0, 1, false);
    }

    @Test public void canceledPointerZeroCannotCapture() throws Exception {
        run("canceled-index0", false, 0, MotionEvent.FLAG_CANCELED, 0, false);
    }

    @Test public void canceledPointerOneCannotCapture() throws Exception {
        run("canceled-index1", false, 1, MotionEvent.FLAG_CANCELED, 0, false);
    }

    @Test public void actionCancelWithoutFlagCannotCapture() throws Exception {
        run("cancel-flags0", true, 0, 0, 0, false);
    }

    @Test public void actionCancelWithFlagCannotCapture() throws Exception {
        run("cancel-flags32", true, 0, MotionEvent.FLAG_CANCELED, 0, false);
    }

    @Test public void canceledPointerZeroWithOtherFlagsCannotCapture() throws Exception {
        run("combined-flags-index0", false, 0, COMBINED_CANCELED_FLAGS, 0, false);
    }

    @Test public void canceledPointerOneWithOtherFlagsCannotCapture() throws Exception {
        run("combined-flags-index1", false, 1, COMBINED_CANCELED_FLAGS, 0, false);
    }

    @Test public void aFreshDownAfterCanceledPointerZeroCanCapture() throws Exception {
        run("recover-index0", false, 0, MotionEvent.FLAG_CANCELED, 0, true);
    }

    @Test public void aFreshDownAfterCanceledPointerOneCanCapture() throws Exception {
        run("recover-index1", false, 1, MotionEvent.FLAG_CANCELED, 0, true);
    }

    private void run(String label, boolean cancelAll, int liftedIndex, int flags, int expected, boolean recover)
            throws Exception {
        assertEquals(32, MotionEvent.FLAG_CANCELED);
        try (SystemMenuQueueFixture f = SystemMenuQueueFixture.scopeProbe(true, false)) {
            Settings.System.putInt(RuntimeEnvironment.getApplication().getContentResolver(), "support_long_press_mode", 2);
            AtomicInteger triggered = new AtomicInteger();
            f.onLog = line -> {
                if (line.contains("scope capture stage=gesture reason=none")) triggered.incrementAndGet();
            };
            begin(f);
            Observation first = observe(f, label, cancelAll, liftedIndex, flags, triggered);
            assertOutcome(label, first, expected, !cancelAll);
            if (recover) {
                f.advanceTo(2_001);
                begin(f);
                Observation recovered = observe(f, label + "-fresh-down", false, liftedIndex, 0, triggered);
                assertOutcome(label + "-fresh-down", recovered, 1, true);
            }
        }
    }

    private void begin(SystemMenuQueueFixture f) {
        f.begin();
        assertEquals(2, ReflectionHelpers.<Integer>getField(f.capture, "mode").intValue());
        assertEquals(100L, ReflectionHelpers.<Long>getField(f.capture, "timeout").longValue());
        assertTrue(f.configuration().scopeCapture);
        assertFalse(f.configuration().scopeLinkProbe);
        assertNotNull(ReflectionHelpers.getField(f.capture, "origin"));
        ScopeLongPress gesture = ReflectionHelpers.getField(f.capture, "gesture");
        assertEquals(2, ReflectionHelpers.<Integer>getField(gesture, "state").intValue());
    }

    private Observation observe(SystemMenuQueueFixture f, String label, boolean cancelAll, int liftedIndex, int flags,
                                AtomicInteger triggered) {
        ScopeLongPress gesture = ReflectionHelpers.getField(f.capture, "gesture");
        f.advanceTo(10);
        enqueue(f, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                10, new int[]{0, 1}, 0);
        f.drainWorker();
        assertEquals(label + ": the real two-pointer gesture must enter DUAL", 3,
                ReflectionHelpers.<Integer>getField(gesture, "state").intValue());
        f.advanceTo(210);
        int terminal = cancelAll ? MotionEvent.ACTION_CANCEL
                : MotionEvent.ACTION_POINTER_UP | (liftedIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        enqueue(f, terminal, 210, new int[]{0, 1}, flags);
        f.drainWorker();
        int stateAfterPartial = ReflectionHelpers.<Integer>getField(gesture, "state");
        int callsAfterPartial = f.captureCalls.get();
        int remaining = cancelAll ? 0 : 1 - liftedIndex;
        f.advanceTo(211);
        enqueue(f, MotionEvent.ACTION_UP, 211, new int[]{remaining}, 0);
        f.drainWorker();
        boolean released = f.physical.released();
        int callsAfterRelease = f.captureCalls.get();
        // Observe the complete window before any cancellation or backend outcome assertion.
        f.advanceTo(510);
        f.drainWorker();
        int callsAt510 = f.captureCalls.get();
        f.advanceTo(512);
        f.drainWorker();
        int callsAt512 = f.captureCalls.get();
        f.advanceTo(2_000);
        f.drainWorker();
        return new Observation(stateAfterPartial, released, callsAfterPartial, callsAfterRelease,
                callsAt510, callsAt512, f.captureCalls.get(), triggered.get(), f.linkTaps.get());
    }

    private void assertOutcome(String label, Observation observed, int expected, boolean released) {
        assertEquals(label + ": no capture at the first release", 0, observed.callsAfterPartial());
        assertEquals(label + ": no capture before the delay", 0, observed.callsAfterRelease());
        assertEquals(label + ": no capture at 510ms", 0, observed.callsAt510());
        assertEquals(label + ": backend count at 512ms", expected, observed.callsAt512());
        assertEquals(label + ": backend count at 2000ms", expected, observed.callsAt2000());
        assertEquals(label + ": gesture authorization count", expected, observed.gestureTriggers());
        assertEquals(label + ": no link fallback tap", 0, observed.linkTaps());
        assertEquals(label + ": physical release evidence", released, observed.released());
        assertEquals(label + ": only ordinary pointer release may enter DUAL_READY",
                expected == 1 ? 4 : 0, observed.stateAfterPartial());
    }

    private void enqueue(SystemMenuQueueFixture f, int action, long offset, int[] ids, int flags) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = 50 + ids[i] * 10;
            coordinates[i].y = 200;
            coordinates[i].pressure = 1;
            coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(f.downUptime, f.downUptime + offset, action, ids.length,
                properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags);
        ReflectionHelpers.<List<MotionEvent>>getField(f, "retainedEvents").add(event);
        assertEvent(event, action, ids, flags);
        Handler input = ReflectionHelpers.getField(f, "input");
        XposedInterface.Hooker hook = ReflectionHelpers.<AtomicReference<XposedInterface.Hooker>>getField(f, "pointerHook").get();
        assertNotNull(hook);
        int before = f.proceededInputs.get();
        AtomicInteger entered = new AtomicInteger();
        AtomicInteger hookFlags = new AtomicInteger(-1);
        AtomicInteger hookAction = new AtomicInteger(-1);
        assertTrue(input.post(() -> {
            hookFlags.set(event.getFlags());
            hookAction.set(event.getAction());
            entered.incrementAndGet();
            ReflectionHelpers.callInstanceMethod(f, "intercept",
                    ReflectionHelpers.ClassParameter.from(XposedInterface.Hooker.class, hook),
                    ReflectionHelpers.ClassParameter.from(InputEvent.class, event));
        }));
        f.drainInput();
        assertNull(f.inputFailure.get());
        assertEquals(1, entered.get());
        assertEquals(flags, hookFlags.get());
        assertEquals(action, hookAction.get());
        assertEquals(before + 1, f.proceededInputs.get());
        assertEvent(event, action, ids, flags);
    }

    private void assertEvent(MotionEvent event, int action, int[] ids, int flags) {
        assertEquals(flags, event.getFlags());
        assertEquals(action, event.getAction());
        assertEquals(action & MotionEvent.ACTION_MASK, event.getActionMasked());
        assertEquals(ids.length, event.getPointerCount());
        assertEquals(0, ReflectionHelpers.<Integer>callInstanceMethod(event, "getDisplayId").intValue());
        int index = (action & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        assertEquals(index, event.getActionIndex());
        assertTrue(index >= 0 && index < ids.length);
        for (int i = 0; i < ids.length; i++) {
            assertEquals(ids[i], event.getPointerId(i));
            assertEquals(50 + ids[i] * 10, event.getRawX(i), 0f);
            assertEquals(200, event.getRawY(i), 0f);
        }
    }

    private record Observation(int stateAfterPartial, boolean released, int callsAfterPartial, int callsAfterRelease,
                               int callsAt510, int callsAt512, int callsAt2000, int gestureTriggers, int linkTaps) { }
}
