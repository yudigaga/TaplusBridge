package io.github.yudigaga.taplusbridge.probe;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Ordinary scope cancellation must still observe a real secondary-display touchscreen DOWN. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = MotionEventDisplayShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeSecondaryDisplayAndroidTest {
    @Test public void aSecondaryDisplayDownCancelsAnArmedMainScopeLinkBeforeTheWorkerRuns() throws Exception {
        try (SystemMenuQueueFixture f = SystemMenuQueueFixture.scopeProbe(false, true)) {
            f.begin();
            assertTrue(f.configuration().scopeLinkProbe);
            ScopeLinkProbe link = f.capture.linkProbe();
            assertNotNull(link);
            Object active = ReflectionHelpers.getField(link, "active");
            assertNotNull("The main touchscreen DOWN must arm the real link observer", active);
            assertEquals("ARMED", ReflectionHelpers.getField(active, "phase").toString());
            Object origin = ReflectionHelpers.getField(f.capture, "origin");
            assertNotNull(origin);
            assertTrue(ReflectionHelpers.<Boolean>getField(f.capture, "gestureActive"));
            long input = globalInput(f);
            long epoch = menuEpoch(f);

            f.advanceTo(199);
            secondaryDown(f);
            assertEquals(input + 1, globalInput(f));
            assertEquals("Only the menu Hook channel is limited to the main display", epoch, menuEpoch(f));
            assertNull("ScopeLinkProbe.pointer must cancel new_touch synchronously", ReflectionHelpers.getField(link, "active"));
            assertSame("The scope worker has not run yet", origin, ReflectionHelpers.getField(f.capture, "origin"));
            assertTrue(f.physical.soleStream(0, 0, f.downUptime));
            assertEquals(0, f.linkTaps.get());

            f.drainWorker();
            assertScopeCancelled(f);
            f.advanceTo(201);
            f.queue(MotionEvent.ACTION_UP, 201);
            f.drainInput();
            f.drainWorker();
            assertTrue(f.physical.released());
            f.advanceTo(2_000);
            f.drainWorker();
            assertNull(ReflectionHelpers.getField(link, "active"));
            assertEquals(0, f.linkTaps.get());
            assertEquals(0, f.captureCalls.get());
        }
    }

    @Test public void aSecondaryDisplayDownCancelsAnAlreadyScheduledMainCapture() throws Exception {
        try (SystemMenuQueueFixture f = SystemMenuQueueFixture.scopeProbe(true, false)) {
            releaseMainCapture(f);
            long input = globalInput(f);
            long generation = ReflectionHelpers.getField(f.capture, "generation");
            long epoch = menuEpoch(f);

            f.advanceTo(250);
            secondaryDown(f);
            assertEquals(input + 1, globalInput(f));
            assertEquals(epoch, menuEpoch(f));
            assertTrue("A secondary display must not become a held main-display stream", f.physical.released());
            assertEquals(0, f.captureCalls.get());
            f.drainWorker();
            assertScopeCancelled(f);
            assertTrue(ReflectionHelpers.<Long>getField(f.capture, "generation") > generation);

            f.advanceTo(500);
            f.drainWorker();
            assertEquals(0, f.captureCalls.get());
            f.advanceTo(502);
            f.drainWorker();
            assertEquals("The invalidated pending capture must not enter the backend", 0, f.captureCalls.get());
            f.advanceTo(2_000);
            f.drainWorker();
            assertEquals(0, f.captureCalls.get());
        }
    }

    @Test public void aReleasedMainCaptureReachesTheRecordingBackendWithoutSecondaryInput() throws Exception {
        try (SystemMenuQueueFixture f = SystemMenuQueueFixture.scopeProbe(true, false)) {
            releaseMainCapture(f);
            f.advanceTo(500);
            f.drainWorker();
            assertEquals("The capture is delayed until after the 201ms release plus 300ms", 0, f.captureCalls.get());
            f.advanceTo(502);
            f.drainWorker();
            assertEquals("The same released gesture must really reach the recording backend", 1, f.captureCalls.get());
            f.advanceTo(2_000);
            f.drainWorker();
            assertEquals("The null backend result must be handled without repeated capture", 1, f.captureCalls.get());
            assertEquals(0, f.linkTaps.get());
        }
    }

    private static void releaseMainCapture(SystemMenuQueueFixture f) {
        AtomicInteger triggered = new AtomicInteger();
        f.onLog = message -> {
            if (message.contains("scope capture stage=gesture reason=none")) triggered.incrementAndGet();
        };
        f.begin();
        assertTrue(f.configuration().scopeCapture);
        assertFalse(f.configuration().scopeLinkProbe);
        Object origin = ReflectionHelpers.getField(f.capture, "origin");
        assertNotNull("The ordinary main touchscreen DOWN must select a source", origin);
        assertTrue(ReflectionHelpers.<Boolean>getField(f.capture, "gestureActive"));
        long generation = ReflectionHelpers.getField(f.capture, "generation");

        f.advanceTo(201);
        f.queue(MotionEvent.ACTION_UP, 201);
        f.drainInput();
        f.drainWorker();
        assertEquals("The real scope gesture must schedule the delayed capture", 1, triggered.get());
        assertTrue(f.physical.released());
        assertSame(origin, ReflectionHelpers.getField(f.capture, "origin"));
        assertNotNull(ReflectionHelpers.getField(f.capture, "configuration"));
        assertFalse(ReflectionHelpers.<Boolean>getField(f.capture, "gestureActive"));
        assertFalse(ReflectionHelpers.<ScopeLongPress>getField(f.capture, "gesture").isActive());
        assertEquals(generation, ReflectionHelpers.<Long>getField(f.capture, "generation").longValue());
        assertEquals(globalInput(f), ReflectionHelpers.<Long>getField(f.capture, "gestureInputGeneration").longValue());
        assertEquals(0, f.captureCalls.get());
    }

    private static void secondaryDown(SystemMenuQueueFixture f) {
        int reads = MotionEventDisplayShadow.hookDisplayOneReads.get();
        long down = SystemClock.uptimeMillis();
        f.queueOnDisplay(MotionEvent.ACTION_DOWN, down, 7, down, 1, InputDevice.SOURCE_TOUCHSCREEN, 1);
        f.drainInput();
        assertTrue("The installed SystemProbe Hook must actually read displayId == 1",
                MotionEventDisplayShadow.hookDisplayOneReads.get() > reads);
    }

    private static void assertScopeCancelled(SystemMenuQueueFixture f) {
        assertNull(ReflectionHelpers.getField(f.capture, "origin"));
        assertNull(ReflectionHelpers.getField(f.capture, "configuration"));
        assertFalse(ReflectionHelpers.<Boolean>getField(f.capture, "gestureActive"));
        assertFalse(ReflectionHelpers.<ScopeLongPress>getField(f.capture, "gesture").isActive());
        assertNull(ReflectionHelpers.getField(f.capture, "pendingEncoding"));
    }

    private static long globalInput(SystemMenuQueueFixture f) {
        return ReflectionHelpers.<AtomicLong>getField(f.capture, "inputGeneration").get();
    }

    private static long menuEpoch(SystemMenuQueueFixture f) {
        return ReflectionHelpers.<AtomicLong>getField(f.capture, "menuEpoch").get();
    }
}
