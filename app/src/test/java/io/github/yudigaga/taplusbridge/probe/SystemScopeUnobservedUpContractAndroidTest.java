package io.github.yudigaga.taplusbridge.probe;

import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/**
 * Release-only contracts before an original UP reaches the installed Hook.
 * Neither a held DOWN nor a queued early UP can authorize menu dismissal.
 * These test definitions do not represent executed or passing validation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeUnobservedUpContractAndroidTest {
    @Test public void aHeldStreamWithOnlyDownCannotCloseWhenTheDeadlineWorkerFirstRunsAt201() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin();
            var callback = f.showMenu(101);
            // No input or deadline-worker drain occurs between the shown menu and 201.
            f.advanceTo(201);
            f.drainWorker();
            int callsAtFirstDeadlineCheck = callback.calls;
            f.advanceTo(2_000); f.drainWorker();

            assertEquals("A late deadline worker cannot authorize a held stream without its final UP", 0,
                    callsAtFirstDeadlineCheck);
            assertEquals("A stationary held stream must keep its source menu", 0, callback.calls);
            assertEquals("Only the original DOWN may have entered the installed Hook", 1,
                    f.proceededInputs.get());
            assertTrue(f.physical.soleStream(0, 0, f.downUptime));
            assertFalse(f.physical.released());
            assertFalse("No dispatch may arm an expected-close echo", f.retain());
        }
    }

    @Test public void anOriginal199UpOutsideTheEntireHookMustNotAuthorizeThe200DeadlineCheck() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin();
            var callback = f.showMenu(101);
            int proceededBeforeUp = f.proceededInputs.get();
            f.advanceTo(199);
            f.queue(MotionEvent.ACTION_UP, 199);
            boolean heldBeforeOriginalUpReachedHook = f.physical.soleStream(0, 0, f.downUptime);
            f.advanceTo(200);
            f.drainWorker();
            int beforeOriginalUpReachedHook = callback.calls;
            int proceededBeforeOriginalUpReachedHook = f.proceededInputs.get();
            // Deliver the original event and finish observing even when the deadline already dispatched.
            f.drainInput();
            f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            boolean oldVisibilityAccepted = f.visible(f.shownElapsed);
            boolean oldRetentionAccepted = f.retain();
            f.drainWorker();

            assertTrue("The queued original UP must remain outside the complete Hook until explicitly delivered",
                    heldBeforeOriginalUpReachedHook);
            assertEquals(proceededBeforeUp, proceededBeforeOriginalUpReachedHook);
            assertEquals("The original UP must eventually proceed exactly once", proceededBeforeUp + 1,
                    f.proceededInputs.get());
            assertEquals(f.downUptime + 199, f.physical.releasedAt());
            assertEquals(f.beganElapsed + 199, f.physical.releasedElapsedAt());
            assertFalse(oldVisibilityAccepted);
            assertFalse(oldRetentionAccepted);
            assertEquals("An upstream UP199 must not authorize the T200 callback", 0,
                    beforeOriginalUpReachedHook);
            assertEquals("The original early UP cannot revive the old menu after full observation", 0, callback.calls);
        }
    }

    @Test public void anOriginal199UpCannotStartMenuInspectionBeforeOrAfterItsFullHookDelivery() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin();
            var callback = f.showMenu(101);
            int proceededBeforeUp = f.proceededInputs.get();
            f.advanceTo(199);
            f.queue(MotionEvent.ACTION_UP, 199);
            f.advanceTo(200);
            f.drainWorker();
            int callsBeforeObservedUp = callback.calls;
            assertEquals("A held stream cannot begin a WMS menu inspection", 0, f.menu.backReads);
            assertEquals("The original queued UP must still be outside the entire Hook", proceededBeforeUp,
                    f.proceededInputs.get());
            f.drainInput();
            f.drainWorker();
            int callsAfterObservedUp = callback.calls;
            boolean immediateVisibilityAccepted = f.visible(f.shownElapsed);
            boolean immediateRetentionAccepted = f.retain();
            boolean repeatedRetentionAccepted = f.retain();
            f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            boolean lateVisibilityAccepted = f.visible(f.shownElapsed);
            boolean lateRetentionAccepted = f.retain();
            f.drainWorker();

            assertEquals("The observed original UP must proceed exactly once", proceededBeforeUp + 1,
                    f.proceededInputs.get());
            assertTrue(f.physical.released());
            assertEquals(f.downUptime + 199, f.physical.releasedAt());
            assertEquals(f.beganElapsed + 199, f.physical.releasedElapsedAt());
            assertEquals("An unobserved UP cannot authorize dismissal", 0, callsBeforeObservedUp);
            assertEquals("The final guard must block an early UP already observed by the Hook", 0, callsAfterObservedUp);
            assertEquals("The early UP must not allow a late menu callback", 0, callback.calls);
            assertEquals("An early UP cannot authorize any later WMS menu inspection", 0, f.menu.backReads);
            assertFalse(immediateVisibilityAccepted);
            assertFalse(immediateRetentionAccepted);
            assertFalse(repeatedRetentionAccepted);
            assertFalse(lateVisibilityAccepted);
            assertFalse(lateRetentionAccepted);
        }
    }
}
