package io.github.yudigaga.taplusbridge.probe;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Installed input Hook regressions with paused queues and the existing WMS fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = MotionEventDisplayShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeStylusQueueAndroidTest {
    private SystemMenuQueueFixture f;

    @After public void close() throws Exception { if (f != null) f.close(); }

    private void begin() throws Exception {
        f = new SystemMenuQueueFixture();
        f.begin();
    }

    private long globalInput() {
        return ReflectionHelpers.<AtomicLong>getField(f.capture, "inputGeneration").get();
    }

    private void stylus(int action, long down) {
        f.queue(action, SystemClock.uptimeMillis(), 7, down, 1, InputDevice.SOURCE_STYLUS);
        f.drainInput();
    }

    @Test public void aCompleteStylusConflictBetweenWorkerTurnsCannotBeRevivedByOldVisibilityOrUp() throws Exception {
        begin();
        var callback = f.showMenu(101);
        long input = globalInput();
        f.advanceTo(199); long secondDown = SystemClock.uptimeMillis();
        stylus(MotionEvent.ACTION_DOWN, secondDown);
        assertFalse(f.physical.soleStream(0, 0, f.downUptime));
        f.advanceTo(201); stylus(MotionEvent.ACTION_UP, secondDown);
        assertTrue(f.physical.soleStream(0, 0, f.downUptime));
        assertEquals("A stylus only revokes menu authorization, not the touchscreen input generation", input, globalInput());
        f.advanceTo(301); f.drainWorker();
        assertEquals(0, callback.calls);
        assertFalse(f.visible(f.shownElapsed));
        f.queue(MotionEvent.ACTION_UP, 301); f.drainInput(); f.drainWorker();
        assertTrue(f.physical.released());
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls);
        assertFalse(f.visible(f.shownElapsed));
        assertFalse(f.retain());
    }

    @Test public void aStylusConflictBeforeTheDownWorkerCreatesMenuTouchCannotAuthorizeThatOldDown() throws Exception {
        f = new SystemMenuQueueFixture();
        f.beginInputOnly();
        assertNull("The original touchscreen DOWN has reached the Hook but not its worker",
                ReflectionHelpers.getField(f.capture, "menuTouch"));
        f.advanceTo(1); long secondDown = SystemClock.uptimeMillis();
        stylus(MotionEvent.ACTION_DOWN, secondDown);
        f.advanceTo(2); stylus(MotionEvent.ACTION_UP, secondDown);
        assertTrue(f.physical.soleStream(0, 0, f.downUptime));
        // Run the original DOWN before installing the menu, keeping it out of the DOWN baseline.
        f.drainWorker();
        var callback = f.installMenu(101);
        assertFalse(f.visible(f.shownElapsed));
        f.advanceTo(199); f.drainWorker(); assertEquals(0, callback.calls);
        f.advanceTo(200); f.drainWorker(); assertEquals(0, callback.calls);
        f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls);
        assertFalse(f.visible(f.shownElapsed));
        assertFalse(f.retain());
    }

    @Test public void aStylusConflictBeforeTheSecondMenuInspectionCannotAuthorizeCallbackOrRetention() throws Exception {
        assertConflictDuringMenuInspection(1);
    }

    @Test public void aStylusConflictDuringTheSecondMenuInspectionCannotAuthorizeCallbackOrRetention() throws Exception {
        assertConflictDuringMenuInspection(2);
    }

    private void assertConflictDuringMenuInspection(int read) throws Exception {
        begin();
        var callback = f.showMenu(101);
        AtomicInteger conflicts = new AtomicInteger();
        f.menu.beforeBackRead = () -> {
            if (f.menu.backReads != read) return;
            conflicts.incrementAndGet();
            long secondDown = SystemClock.uptimeMillis();
            stylus(MotionEvent.ACTION_DOWN, secondDown);
            stylus(MotionEvent.ACTION_UP, secondDown);
            assertTrue(f.physical.released());
        };
        f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
        f.drainInput();
        assertTrue(f.physical.released());
        assertEquals("The chosen real menu inspection must encounter the input conflict", 1, conflicts.get());
        assertEquals(0, callback.calls);
        assertFalse(f.retain());
        f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls);
        assertFalse(f.visible(f.shownElapsed));
        assertFalse(f.retain());
    }

    @Test public void aStylusAfterDismissalCannotBecomeTheModuleEchoAfterItsRelease() throws Exception {
        begin();
        var callback = f.showMenu(201);
        assertEquals(0, callback.calls);
        f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        assertEquals(1, callback.calls);
        f.advanceTo(202); long secondDown = SystemClock.uptimeMillis();
        stylus(MotionEvent.ACTION_DOWN, secondDown);
        f.advanceTo(203); stylus(MotionEvent.ACTION_UP, secondDown);
        assertTrue(f.physical.released());
        // First claim only after the stylus is gone; a failed claim during DOWN could mask revival.
        assertFalse(f.retain());
        assertFalse(f.retain());
        f.drainWorker(); f.advanceTo(2_000); f.drainWorker();
        assertFalse(f.visible(f.shownElapsed));
        assertEquals(1, callback.calls);
    }

    @Test public void aFreshTouchWaitsForItsOwnValidUpAfterBothDevicesRelease() throws Exception {
        begin();
        var oldCallback = f.showMenu(101);
        f.advanceTo(199); long secondDown = SystemClock.uptimeMillis();
        stylus(MotionEvent.ACTION_DOWN, secondDown);
        f.advanceTo(201); stylus(MotionEvent.ACTION_UP, secondDown);
        f.advanceTo(202); f.queue(MotionEvent.ACTION_UP, 202); f.drainInput(); f.drainWorker();
        assertTrue(f.physical.released());
        assertEquals(0, oldCallback.calls);
        f.advanceTo(203); f.restoreMainWindow(); f.begin();
        var callback = f.showMenu(101);
        f.advanceTo(199); f.drainWorker(); assertEquals(0, callback.calls);
        f.advanceTo(200); f.drainWorker(); assertEquals(0, callback.calls);
        f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        assertEquals(1, callback.calls);
        f.advanceTo(2_000); f.drainWorker(); assertEquals(1, callback.calls);
        assertEquals(0, oldCallback.calls);
    }

    @Test public void anInjectedStylusCannotRevokeTheRealTouchscreenMenu() throws Exception {
        assertIgnoredStream(InputDevice.SOURCE_STYLUS, 0, true, false);
    }

    @Test public void anInjectedTouchscreenCannotRevokeTheRealTouchscreenMenu() throws Exception {
        assertIgnoredStream(InputDevice.SOURCE_TOUCHSCREEN, 0, true, false);
    }

    @Test public void aSecondaryDisplayStylusCannotRevokeTheMainDisplayMenu() throws Exception {
        assertIgnoredStream(InputDevice.SOURCE_STYLUS, 1, false, false);
    }

    @Test public void aSecondaryDisplayTouchscreenStillCancelsTheMainDisplayMenu() throws Exception {
        begin();
        var callback = f.showMenu(101);
        long input = globalInput();
        f.advanceTo(199); long secondDown = SystemClock.uptimeMillis();
        int readsBefore = MotionEventDisplayShadow.hookDisplayOneReads.get();
        f.queueOnDisplay(MotionEvent.ACTION_DOWN, secondDown, 7, secondDown, 1,
                InputDevice.SOURCE_TOUCHSCREEN, 1);
        f.queueOnDisplay(MotionEvent.ACTION_UP, secondDown, 7, secondDown, 1,
                InputDevice.SOURCE_TOUCHSCREEN, 1);
        f.drainInput();
        assertTrue("The installed SystemProbe Hook must actually read displayId==1",
                MotionEventDisplayShadow.hookDisplayOneReads.get() > readsBefore);
        assertTrue(f.physical.soleStream(0, 0, f.downUptime));
        assertEquals("A real secondary-display touchscreen DOWN keeps the existing global cancellation", input + 1, globalInput());
        f.drainWorker(); assertEquals(0, callback.calls);
        f.advanceTo(200); f.drainWorker(); assertEquals(0, callback.calls);
        f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        assertEquals(0, callback.calls);
        f.advanceTo(2_000); f.drainWorker(); assertEquals(0, callback.calls);
        assertFalse(f.visible(f.shownElapsed));
        assertFalse(f.retain());
    }

    @Test public void aMouseCannotRevokeTheRealTouchscreenMenu() throws Exception {
        assertIgnoredStream(InputDevice.SOURCE_MOUSE, 0, false, false);
    }

    @Test public void stylusHoverCannotRevokeTheRealTouchscreenMenu() throws Exception {
        assertIgnoredStream(InputDevice.SOURCE_STYLUS, 0, false, true);
    }

    private void assertIgnoredStream(int source, int display, boolean injected, boolean hover) throws Exception {
        begin();
        var callback = f.showMenu(101);
        long input = globalInput();
        f.advanceTo(199); long ignoredDown = SystemClock.uptimeMillis();
        if (injected) f.physical.injected(display, 7, ignoredDown, SystemClock.uptimeMillis());
        int[] actions = hover ? new int[]{MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT}
                : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP};
        int readsBefore = MotionEventDisplayShadow.hookDisplayOneReads.get();
        for (int action : actions) f.queueOnDisplay(action, ignoredDown, 7, ignoredDown, 1, source, display);
        f.drainInput();
        if (display == 1 && !hover && source != InputDevice.SOURCE_MOUSE)
            assertTrue("The installed SystemProbe Hook must actually read displayId==1",
                    MotionEventDisplayShadow.hookDisplayOneReads.get() > readsBefore);
        assertTrue(f.physical.soleStream(0, 0, f.downUptime));
        assertEquals(input, globalInput());
        f.drainWorker(); assertEquals(0, callback.calls);
        f.advanceTo(200); f.drainWorker(); assertEquals(0, callback.calls);
        f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        assertEquals("An ignored stream must leave the original release eligible", 1, callback.calls);
        f.advanceTo(2_000); f.drainWorker(); assertEquals(1, callback.calls);
    }

    @Test public void aRecycledSecondaryDisplayEventCannotContaminateANewMainDisplayTouch() throws Exception {
        MotionEvent secondary = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50, 200, 0);
        try {
            ReflectionHelpers.callInstanceMethod(secondary, "setDisplayId",
                    ReflectionHelpers.ClassParameter.from(int.class, 1));
            assertEquals(1, (int) ReflectionHelpers.<Integer>callInstanceMethod(secondary, "getDisplayId"));
        } finally { secondary.recycle(); }
        MotionEvent main = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50, 200, 0);
        try {
            assertEquals("A newly obtained main-display event cannot retain a recycled display1",
                    0, (int) ReflectionHelpers.<Integer>callInstanceMethod(main, "getDisplayId"));
        } finally { main.recycle(); }
        begin();
        assertTrue(f.physical.soleStream(0, 0, f.downUptime));
    }

    @Test public void aStylusCannotStartScopeCaptureOrLinkEvenWhenBothFeaturesAreEnabled() throws Exception {
        f = new SystemMenuQueueFixture(true, true);
        assertTrue(f.configuration().scopeCapture);
        assertTrue(f.configuration().scopeLinkProbe);
        assertTrue(f.capture.linkReady());
        long input = globalInput();
        long secondDown = SystemClock.uptimeMillis();
        stylus(MotionEvent.ACTION_DOWN, secondDown);
        f.drainWorker();
        assertNoStylusCaptureOrLink();
        ShadowSystemClock.advanceBy(Duration.ofMillis(201));
        stylus(MotionEvent.ACTION_UP, secondDown);
        f.drainWorker();
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_000)); f.drainWorker();
        assertNoStylusCaptureOrLink();
        assertEquals(input, globalInput());
        assertFalse(f.visible(SystemClock.elapsedRealtime()));
        assertFalse(f.retain());
        ShadowApplication application = Shadow.extract(RuntimeEnvironment.getApplication());
        assertNull(application.getNextStartedService());
        f.begin();
        assertNotNull("The same enabled fixture must still arm an ordinary touchscreen capture",
                ReflectionHelpers.getField(f.capture, "origin"));
        assertNotNull("The real link observer must be usable for a touchscreen DOWN",
                ReflectionHelpers.getField(f.capture.linkProbe(), "active"));
    }

    private void assertNoStylusCaptureOrLink() {
        assertNull(ReflectionHelpers.getField(f.capture, "origin"));
        assertNull(ReflectionHelpers.getField(f.capture, "menuTouch"));
        assertNull(ReflectionHelpers.getField(f.capture.linkProbe(), "active"));
        assertEquals(0, f.captureCalls.get());
        assertEquals(0, f.linkTaps.get());
    }

    @Test public void aTransferredStylusStreamRevokesTheOldMenuBeforeThePointerHookReturnsEarly() throws Exception {
        begin();
        var callback = f.showMenu(101);
        f.advanceTo(199); long secondDown = SystemClock.uptimeMillis();
        var drag = f.transferStream(7, secondDown);
        assertTrue("The real directional controller must select its transferred-stream early return",
                drag.transferredPointer(0, 7, secondDown, MotionEvent.ACTION_DOWN));
        int proceeded = f.proceededInputs.get();
        stylus(MotionEvent.ACTION_DOWN, secondDown);
        assertFalse(f.physical.soleStream(0, 0, f.downUptime));
        f.advanceTo(201); stylus(MotionEvent.ACTION_UP, secondDown);
        assertEquals(proceeded + 2, f.proceededInputs.get());
        assertTrue(f.physical.soleStream(0, 0, f.downUptime));
        f.advanceTo(301); f.drainWorker();
        assertEquals(0, callback.calls);
        f.queue(MotionEvent.ACTION_UP, 301); f.drainInput(); f.drainWorker();
        assertTrue(f.physical.released());
        assertEquals(0, callback.calls);
        assertFalse(f.visible(f.shownElapsed));
        assertFalse(f.retain());
    }
}
