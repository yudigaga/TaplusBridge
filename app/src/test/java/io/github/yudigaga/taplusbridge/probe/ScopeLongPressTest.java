package io.github.yudigaga.taplusbridge.probe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScopeLongPressTest {
    private static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3, POINTER_DOWN = 5, POINTER_UP = 6;
    private static final long TIMEOUT = 500;
    private static final float SLOP = 10;

    private ScopeLongPress.Trigger event(ScopeLongPress press, long down, long at, int action,
                                         int pointers, float x, float y, float secondX, float secondY, int mode) {
        return press.event(0, 7, down, at, action, pointers, x, y, secondX, secondY,
                mode, TIMEOUT, SLOP, at);
    }
    private void startSingle(ScopeLongPress press, long at) {
        assertNull(event(press, at, at, DOWN, 1, 100, 200, Float.NaN, Float.NaN, 1));
    }
    private void startDual(ScopeLongPress press, long at) {
        assertNull(event(press, at, at, DOWN, 1, 100, 200, Float.NaN, Float.NaN, 2));
        assertNull(event(press, at, at + 100, POINTER_DOWN, 2, 100, 200, 200, 300, 2));
    }

    @Test public void singleLongPressOnlyTriggersOnceOnUpAndRetainsTheFirstPoint() {
        ScopeLongPress press = new ScopeLongPress(); startSingle(press, 1000);
        assertTrue(press.isActive());
        assertNull(event(press, 1000, 1500, MOVE, 1, 103, 204, 0, 0, 1));
        ScopeLongPress.Trigger trigger = event(press, 1000, 1510, UP, 1, 104, 204, 0, 0, 1);
        assertFalse(press.isActive());
        assertNotNull(trigger); assertEquals(1000, trigger.downAt()); assertEquals(1510, trigger.upAt());
        assertEquals(7, trigger.device()); assertEquals(0, trigger.display());
        assertEquals(100, trigger.x(), 0); assertEquals(200, trigger.y(), 0);
        assertNull(event(press, 1000, 1510, UP, 1, 104, 204, 0, 0, 1));
    }

    @Test public void shortPressAndAnUnfinishedHeldFingerNeverTrigger() {
        ScopeLongPress press = new ScopeLongPress(); startSingle(press, 1000);
        assertNull(event(press, 1000, 1499, UP, 1, 100, 200, 0, 0, 1));
        startSingle(press, 2000);
        assertNull(event(press, 2000, 3000, MOVE, 1, 100, 200, 0, 0, 1));
        press.cancel();
        assertNull(event(press, 2000, 3100, UP, 1, 100, 200, 0, 0, 1));
    }

    @Test public void singleMovementBeyondSlopOrAnExtraPointerCancelsTheWholeGesture() {
        ScopeLongPress press = new ScopeLongPress(); startSingle(press, 1000);
        assertNull(event(press, 1000, 1200, MOVE, 1, 111, 200, 0, 0, 1));
        assertFalse(press.isActive());
        assertNull(event(press, 1000, 1600, UP, 1, 100, 200, 0, 0, 1));
        startSingle(press, 2000);
        assertNull(event(press, 2000, 2100, POINTER_DOWN, 2, 100, 200, 200, 300, 1));
        assertNull(event(press, 2000, 2600, UP, 1, 100, 200, 0, 0, 1));
    }

    @Test public void dualRequiresTheSecondPointerToParticipateForTheFullTimeout() {
        ScopeLongPress press = new ScopeLongPress(); startDual(press, 1000);
        assertNull(event(press, 1000, 1500, POINTER_UP, 2, 100, 200, 200, 300, 2));
        assertNull(event(press, 1000, 1600, UP, 1, 100, 200, 0, 0, 2));
        startDual(press, 2000);
        assertNull(event(press, 2000, 2600, POINTER_UP, 2, 100, 200, 200, 300, 2));
        assertNotNull(event(press, 2000, 2610, UP, 1, 100, 200, 0, 0, 2));
    }

    @Test public void dualSecondPointerMustJoinWithinTwoHundredMilliseconds() {
        ScopeLongPress press = new ScopeLongPress();
        assertNull(event(press, 1000, 1000, DOWN, 1, 100, 200, 0, 0, 2));
        assertNull(event(press, 1000, 1200, POINTER_DOWN, 2, 100, 200, 200, 300, 2));
        assertNull(event(press, 1000, 1700, POINTER_UP, 2, 100, 200, 200, 300, 2));
        assertNotNull(event(press, 1000, 1701, UP, 1, 100, 200, 0, 0, 2));
        assertNull(event(press, 2000, 2000, DOWN, 1, 100, 200, 0, 0, 2));
        assertNull(event(press, 2000, 2201, POINTER_DOWN, 2, 100, 200, 200, 300, 2));
        assertNull(event(press, 2000, 2800, POINTER_UP, 2, 100, 200, 200, 300, 2));
        assertNull(event(press, 2000, 2801, UP, 1, 100, 200, 0, 0, 2));
    }

    @Test public void dualCanLiftEitherPointerFirstButOnlyFinalUpTriggers() {
        for (boolean firstRemains : new boolean[]{true, false}) {
            ScopeLongPress press = new ScopeLongPress(); startDual(press, 1000);
            assertNull(event(press, 1000, 1600, POINTER_UP, 2, 100, 200, 200, 300, 2));
            float x = firstRemains ? 102 : 202, y = firstRemains ? 202 : 302;
            assertNull(event(press, 1000, 1605, MOVE, 1, x, y, Float.NaN, Float.NaN, 2));
            ScopeLongPress.Trigger trigger = event(press, 1000, 1610, UP, 1, x, y, Float.NaN, Float.NaN, 2);
            assertNotNull(trigger); assertEquals(100, trigger.x(), 0); assertEquals(200, trigger.y(), 0);
            assertNull(event(press, 1000, 1611, UP, 1, x, y, 0, 0, 2));
        }
    }

    @Test public void dualCancelsOnEitherPointerDraggingAThirdPointerOrPostReleaseDragging() {
        for (int invalid : new int[]{0, 1, 2, 3}) {
            ScopeLongPress press = new ScopeLongPress(); startDual(press, 1000);
            if (invalid == 0) assertNull(event(press, 1000, 1400, MOVE, 2, 111, 200, 200, 300, 2));
            if (invalid == 1) assertNull(event(press, 1000, 1400, MOVE, 2, 100, 200, 211, 300, 2));
            if (invalid == 2) assertNull(event(press, 1000, 1400, POINTER_DOWN, 3, 100, 200, 200, 300, 2));
            if (invalid == 3) {
                assertNull(event(press, 1000, 1600, POINTER_UP, 2, 100, 200, 200, 300, 2));
                assertNull(event(press, 1000, 1601, MOVE, 1, 150, 250, 0, 0, 2));
            }
            assertNull(event(press, 1000, 1700, UP, 1, 100, 200, 0, 0, 2));
        }
    }

    @Test public void dualNeedsACompleteReleaseSequenceAndCannotReplaceTheRemainingPointer() {
        ScopeLongPress press = new ScopeLongPress(); startDual(press, 1000);
        assertNull(event(press, 1000, 1600, UP, 1, 100, 200, 0, 0, 2));
        startDual(press, 2000);
        assertNull(event(press, 2000, 2600, POINTER_UP, 2, 100, 200, 200, 300, 2));
        assertNull(event(press, 2000, 2601, POINTER_DOWN, 2, 100, 200, 200, 300, 2));
        assertNull(event(press, 2000, 2700, UP, 1, 100, 200, 0, 0, 2));
        startDual(press, 3000);
        assertNull(event(press, 3000, 3600, POINTER_UP, 2, 100, 200, 200, 300, 2));
        assertNull(event(press, 3000, 3601, MOVE, 1, 100, 200, 0, 0, 2));
        assertNull(event(press, 3000, 3610, UP, 1, 200, 300, 0, 0, 2));
    }

    @Test public void unknownModeCancellationAndConfigurationChangesCannotProduceATrigger() {
        for (int invalidMode : new int[]{0, 2, 3}) {
            ScopeLongPress press = new ScopeLongPress(); startSingle(press, 1000);
            assertNull(event(press, 1000, 1600, UP, 1, 100, 200, 0, 0, invalidMode));
        }
        ScopeLongPress press = new ScopeLongPress(); startSingle(press, 1000);
        assertNull(event(press, 1000, 1200, CANCEL, 0, 0, 0, 0, 0, 1));
        assertNull(event(press, 1000, 1600, UP, 1, 100, 200, 0, 0, 1));
        startSingle(press, 2000);
        assertNull(press.event(0, 7, 2000, 2600, UP, 1, 100, 200, 0, 0, 1, 400, SLOP, 2600));
    }

    @Test public void otherDevicesNonDefaultDisplaysAndDuplicateDownCancelRatherThanTransferOwnership() {
        ScopeLongPress press = new ScopeLongPress(); startSingle(press, 1000);
        assertNull(press.event(0, 8, 1000, 1200, MOVE, 1, 100, 200, 0, 0, 1, TIMEOUT, SLOP, 1200));
        assertNull(event(press, 1000, 1600, UP, 1, 100, 200, 0, 0, 1));
        assertNull(press.event(1, 7, 2000, 2000, DOWN, 1, 100, 200, 0, 0, 1, TIMEOUT, SLOP, 2000));
        assertNull(event(press, 2000, 2600, UP, 1, 100, 200, 0, 0, 1));
        startSingle(press, 3000);
        assertNull(event(press, 3100, 3100, DOWN, 1, 100, 200, 0, 0, 1));
        assertNull(event(press, 3100, 3700, UP, 1, 100, 200, 0, 0, 1));
        startSingle(press, 4000); assertNotNull(event(press, 4000, 4500, UP, 1, 100, 200, 0, 0, 1));
    }

    @Test public void inconsistentDownTimesBackwardsEventsStaleAndFutureEventsAreRejected() {
        ScopeLongPress press = new ScopeLongPress();
        assertNull(event(press, 1000, 1001, DOWN, 1, 100, 200, 0, 0, 1));
        assertNull(event(press, 1000, 1600, UP, 1, 100, 200, 0, 0, 1));
        startSingle(press, 2000);
        assertNull(event(press, 2000, 2200, MOVE, 1, 100, 200, 0, 0, 1));
        assertNull(event(press, 2000, 2199, MOVE, 1, 100, 200, 0, 0, 1));
        assertNull(event(press, 2000, 2600, UP, 1, 100, 200, 0, 0, 1));
        startSingle(press, 3000);
        assertNull(event(press, 3001, 3600, UP, 1, 100, 200, 0, 0, 1));
        for (long delta : new long[]{-1, 301}) {
            startSingle(press, 4000);
            assertNull(press.event(0, 7, 4000, 4500, UP, 1, 100, 200, 0, 0, 1, TIMEOUT, SLOP, 4500 + delta));
        }
        startSingle(press, 5000);
        assertNotNull(press.event(0, 7, 5000, 5500, UP, 1, 100, 200, 0, 0, 1, TIMEOUT, SLOP, 5800));
    }

    @Test public void invalidCoordinatesPointerCountsDeviceAndThresholdsAreNeverAccepted() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1}) {
            ScopeLongPress press = new ScopeLongPress();
            assertNull(event(press, 1000, 1000, DOWN, 1, invalid, 200, 0, 0, 1));
            assertNull(event(press, 1000, 1600, UP, 1, 100, 200, 0, 0, 1));
            assertNull(event(press, 2000, 2000, DOWN, 1, 100, 200, 0, 0, 2));
            assertNull(event(press, 2000, 2100, POINTER_DOWN, 2, 100, 200, 200, invalid, 2));
            assertNull(event(press, 2000, 2700, UP, 1, 100, 200, 0, 0, 2));
        }
        ScopeLongPress press = new ScopeLongPress();
        assertNull(press.event(0, -1, 1000, 1000, DOWN, 1, 100, 200, 0, 0, 1, TIMEOUT, SLOP, 1000));
        assertNull(press.event(0, 7, 1000, 1000, DOWN, 1, 100, 200, 0, 0, 1, 0, SLOP, 1000));
        assertNull(press.event(0, 7, 1000, 1000, DOWN, 1, 100, 200, 0, 0, 1, TIMEOUT, Float.NaN, 1000));
        assertNull(event(press, 1000, 1000, DOWN, 0, 100, 200, 0, 0, 1));
        assertNull(event(press, 1000, 1600, UP, 1, 100, 200, 0, 0, 1));
    }
}
