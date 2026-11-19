package io.github.yudigaga.taplusbridge.probe;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Method 1 contracts through the installed pointer Hook; source only until independently executed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = MotionEventDisplayShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeReleaseMenuAndroidTest {
    @Test public void heldTouchNeverClosesOrRetainsEvenAfterTheRecognitionDeadline() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            for (long at : new long[]{199, 200, 201, 2_000}) {
                f.advanceTo(at); f.drainWorker();
                assertEquals(0, callback.calls); assertFalse(f.retain());
            }
            assertTrue(f.physical.soleStream(0, 0, f.downUptime));
        }
    }

    @Test public void delayedEarlyUpCannotBorrowItsDeliveryTimeToReachRecognition() throws Exception {
        assertDelayedRelease(199, 499, 0);
    }

    @Test public void aDelayedExactThresholdUpClosesOnceAtTheFreshnessBoundary() throws Exception {
        assertDelayedRelease(200, 500, 1);
    }

    @Test public void aDelayedAfterThresholdUpClosesOnceInsideTheFreshnessBoundary() throws Exception {
        assertDelayedRelease(201, 500, 1);
    }

    @Test public void theExistingStaleUpSafetyLimitIsNotRemovedByMethodOne() throws Exception {
        assertDelayedRelease(200, 501, 0);
    }

    private void assertDelayedRelease(long eventAt, long deliveredAt, int expected) throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            f.advanceTo(eventAt); f.queue(MotionEvent.ACTION_UP, eventAt);
            f.advanceTo(deliveredAt); f.drainWorker();
            int beforeHook = callback.calls;
            f.drainInput();
            int beforeFinalWorker = callback.calls;
            f.drainWorker(); int afterFinalWorker = callback.calls;
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(0, beforeHook); assertEquals(0, beforeFinalWorker);
            assertEquals(f.downUptime + eventAt, f.physical.releasedAt());
            assertEquals(f.beganElapsed + eventAt, f.physical.releasedElapsedAt());
            assertEquals(expected, afterFinalWorker); assertEquals(expected, callback.calls);
        }
    }

    @Test public void aCompleteDownAndUpObservedBeforeTheWorkerCanStillAuthorizeItsOwnMenu() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.beginInputOnly();
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            var callback = f.showMenu(201);
            int afterShown = callback.calls;
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(1, afterShown); assertEquals(1, callback.calls);
            assertEquals(2, f.proceededInputs.get());
        }
    }

    @Test public void canceledUpFlagsCannotCloseOrBeRevivedAndTheOriginalEventIsUnchanged() throws Exception {
        for (int flags : new int[]{MotionEvent.FLAG_CANCELED, MotionEvent.FLAG_CANCELED | 1}) {
            try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
                f.begin(); var callback = f.showMenu(101);
                f.advanceTo(200); int before = f.proceededInputs.get();
                MotionEvent original = f.queueSinglePointer(MotionEvent.ACTION_UP, f.downUptime + 200,
                        0, f.downUptime, InputDevice.SOURCE_TOUCHSCREEN, 0, 0, flags, 50, 200);
                f.drainInput(); f.drainWorker();
                int afterCanceled = callback.calls;
                boolean retained = f.retain();
                f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
                f.advanceTo(2_000); f.drainWorker();
                assertEquals(0, afterCanceled); assertFalse(retained); assertEquals(0, callback.calls);
                assertEquals(before + 2, f.proceededInputs.get());
                assertEquals(MotionEvent.ACTION_UP, original.getAction()); assertEquals(flags, original.getFlags());
                assertEquals(0, original.getPointerId(0)); assertEquals(50f, original.getRawX(), 0f);
                assertEquals(200f, original.getRawY(), 0f); assertEquals(f.downUptime, original.getDownTime());
                assertEquals(f.downUptime + 200, original.getEventTime());
            }
        }
    }

    @Test public void aDifferentPointerIdCannotCompleteTheOriginalSinglePointer() throws Exception {
        assertInvalidSingleUp(0, 0, 1, 0);
    }

    @Test public void aDifferentDeviceCannotCompleteTheOriginalSinglePointer() throws Exception {
        assertInvalidSingleUp(7, 0, 0, 0);
    }

    @Test public void aSecondaryDisplayUpCannotCompleteTheMainDisplayPointer() throws Exception {
        assertInvalidSingleUp(0, 1, 0, 0);
    }

    @Test public void aDifferentDownTimeCannotCompleteTheOriginalSinglePointer() throws Exception {
        assertInvalidSingleUp(0, 0, 0, 1);
    }

    private void assertInvalidSingleUp(int device, int display, int pointerId, long downOffset) throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            f.advanceTo(200);
            f.queueSinglePointer(MotionEvent.ACTION_UP, f.downUptime + 200, device, f.downUptime + downOffset,
                    InputDevice.SOURCE_TOUCHSCREEN, display, pointerId, 0, 50, 200);
            f.drainInput(); f.drainWorker();
            f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(0, callback.calls); assertFalse(f.retain()); assertFalse(f.visible(f.shownElapsed));
        }
    }

    @Test public void aPointerActionWithOneReportedPointerStillInvalidatesTheSequence() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            f.advanceTo(150); f.queue(MotionEvent.ACTION_POINTER_UP, 150); f.drainInput(); f.drainWorker();
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(0, callback.calls); assertFalse(f.retain());
        }
    }

    @Test public void aFutureUpCannotLaterBecomeAValidRelease() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            f.advanceTo(199); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(0, callback.calls); assertFalse(f.retain());
        }
    }

    @Test public void aReversedMoveCannotBeHiddenByAnOtherwiseValidUp() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            f.advanceTo(170); f.queue(MotionEvent.ACTION_MOVE, 170); f.drainInput(); f.drainWorker();
            f.advanceTo(180); f.queue(MotionEvent.ACTION_MOVE, 160); f.drainInput(); f.drainWorker();
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(0, callback.calls); assertFalse(f.retain());
        }
    }

    @Test public void anUpWithoutADownIsNotACompleteMenuGesture() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.downUptime = SystemClock.uptimeMillis(); f.beganElapsed = SystemClock.elapsedRealtime();
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            var callback = f.installMenu(201); boolean accepted = f.visible(f.shownElapsed);
            f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            assertTrue("The global physical tracker alone may observe an orphan UP", f.physical.released());
            assertFalse(accepted); assertEquals(0, callback.calls); assertFalse(f.retain());
        }
    }

    @Test public void unreadableInputPermanentlyRevokesTheWaitingGestureProof() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101); f.capture.menuInputUnknown();
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(0, callback.calls); assertFalse(f.retain()); assertFalse(f.visible(f.shownElapsed));
        }
    }

    @Test public void anOldCompletedUpQueuedBeforeANewDownCannotCloseEitherHeldMenu() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var old = f.showMenu(101);
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput();
            f.restoreMainWindow(); f.beginInputOnly(); f.drainWorker();
            var next = f.showMenu(101);
            f.advanceTo(200); f.drainWorker(); int heldNext = next.calls;
            f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            int releasedNext = next.calls;
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(0, old.calls); assertEquals(0, heldNext);
            assertEquals(1, releasedNext); assertEquals(1, next.calls);
        }
    }

    @Test public void aDuplicateUpAndRepeatedVisibilityCannotDispatchAgain() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            int first = callback.calls;
            boolean echoedOnce = f.retain(); boolean echoedTwice = f.retain();
            boolean oldVisibility = f.visible(f.shownElapsed);
            f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(1, first); assertTrue(echoedOnce); assertFalse(echoedTwice); assertFalse(oldVisibility);
            assertEquals(1, callback.calls); assertFalse(f.retain());
        }
    }

    @Test public void aConfigurationChangeObservedWhileHeldCannotReviveAfterRestoration() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            publishRecognition(false);
            f.advanceTo(200); f.drainWorker();
            publishRecognition(true);
            f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(0, callback.calls); assertFalse(f.visible(f.shownElapsed)); assertFalse(f.retain());
        }
    }

    @Test public void aConfigurationChangeDuringSecondInspectionStopsAQualifiedRelease() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            AtomicInteger changed = new AtomicInteger();
            f.menu.beforeBackRead = () -> {
                if (f.menu.backReads != 2) return;
                publishRecognition(false); changed.incrementAndGet();
            };
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            int beforeRestore = callback.calls;
            publishRecognition(true);
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(1, changed.get()); assertEquals(0, beforeRestore); assertEquals(0, callback.calls);
            assertFalse(f.visible(f.shownElapsed)); assertFalse(f.retain());
        }
    }

    @Test public void aConfigurationChangeInvalidatesARealModuleEchoPermanently() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            int closed = callback.calls;
            publishRecognition(false); boolean rejected = f.retain();
            publishRecognition(true); boolean revived = f.retain();
            f.advanceTo(2_000); f.drainWorker();
            assertEquals(1, closed); assertFalse(rejected); assertFalse(revived); assertEquals(1, callback.calls);
        }
    }

    private void publishRecognition(boolean enabled) {
        var context = RuntimeEnvironment.getApplication();
        new ConfigStore(context).setRecognitionEnabled(enabled);
        assertTrue(context.getSharedPreferences("menu_queue_test", 0).edit()
                .putString(RuntimeSnapshot.KEY, RuntimeSnapshot.from(context)).commit());
    }
}
