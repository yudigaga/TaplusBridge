package io.github.yudigaga.taplusbridge.drag;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LongPressDirectionTest {
    private static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3;
    private static final long START = 1_000, HOLD = 500;
    private static final int DEVICE = 7;
    private static final float X = 120, Y = 240, SLOP = 10, THRESHOLD = 48;

    private LongPressDirection gesture() {
        return new LongPressDirection(START, DEVICE, X, Y, HOLD, SLOP, THRESHOLD);
    }

    private void event(LongPressDirection gesture, long elapsed, int action, float x, float y) {
        gesture.event(0, DEVICE, START, START + elapsed, action, 1, x, y, START + elapsed);
    }

    private void rejected(LongPressDirection gesture) {
        assertFalse(gesture.valid()); assertFalse(gesture.released()); assertNull(gesture.direction());
        event(gesture, HOLD + 1_000, UP, X + THRESHOLD, Y);
        assertFalse(gesture.valid()); assertFalse(gesture.released()); assertNull(gesture.direction());
    }

    @Test public void eachDirectionUsesTheOriginalPointAndMayArriveOnlyOnUp() {
        float[][] targets = {{X, Y - THRESHOLD}, {X, Y + THRESHOLD}, {X - THRESHOLD, Y}, {X + THRESHOLD, Y}};
        String[] expected = {"up", "down", "left", "right"};
        for (int i = 0; i < targets.length; i++) {
            LongPressDirection gesture = gesture();
            event(gesture, HOLD, UP, targets[i][0], targets[i][1]);
            assertTrue(gesture.valid()); assertTrue(gesture.released()); assertEquals(expected[i], gesture.direction());
            assertEquals(targets[i][0], gesture.x(), 0); assertEquals(targets[i][1], gesture.y(), 0);
            assertEquals(START, gesture.downAt());
        }
    }

    @Test public void thresholdIsInclusiveAndTheDominantAxisChoosesDirection() {
        LongPressDirection gesture = gesture();
        event(gesture, HOLD, MOVE, X + THRESHOLD - 1, Y); assertNull(gesture.direction());
        event(gesture, HOLD + 1, MOVE, X + THRESHOLD, Y); assertEquals("right", gesture.direction());
        event(gesture, HOLD + 2, MOVE, X + 30, Y - 40); assertEquals("up", gesture.direction());
        event(gesture, HOLD + 3, MOVE, X + 40, Y + 40); assertEquals("down", gesture.direction());
        event(gesture, HOLD + 4, UP, X, Y); assertTrue(gesture.released()); assertNull(gesture.direction());
    }

    @Test public void returningToCenterClearsThePreviewAndUpFreezesItsFinalDirection() {
        LongPressDirection gesture = gesture();
        event(gesture, HOLD, MOVE, X + THRESHOLD, Y); assertEquals("right", gesture.direction());
        event(gesture, HOLD + 1, MOVE, X + 1, Y + 1); assertNull(gesture.direction());
        event(gesture, HOLD + 2, UP, X, Y - THRESHOLD); assertEquals("up", gesture.direction());
        event(gesture, HOLD + 3, MOVE, X + THRESHOLD, Y);
        event(gesture, HOLD + 4, UP, X, Y + THRESHOLD);
        assertTrue(gesture.valid()); assertTrue(gesture.released()); assertEquals("up", gesture.direction());
        assertEquals(X, gesture.x(), 0); assertEquals(Y - THRESHOLD, gesture.y(), 0);
    }

    @Test public void coordinatesFollowOnlyValidEventsWithoutChangingTheDirectionOrigin() {
        LongPressDirection gesture = gesture();
        assertEquals(X, gesture.x(), 0); assertEquals(Y, gesture.y(), 0);
        event(gesture, 100, MOVE, X + 3, Y + 4);
        assertEquals(X + 3, gesture.x(), 0); assertEquals(Y + 4, gesture.y(), 0);
        event(gesture, HOLD, MOVE, X + THRESHOLD, Y);
        assertEquals("right", gesture.direction()); assertEquals(X + THRESHOLD, gesture.x(), 0); assertEquals(Y, gesture.y(), 0);
        event(gesture, HOLD + 1, MOVE, X, Y - THRESHOLD);
        assertEquals("up", gesture.direction()); assertEquals(X, gesture.x(), 0); assertEquals(Y - THRESHOLD, gesture.y(), 0);
        event(gesture, HOLD + 2, MOVE, Float.NaN, Y);
        assertFalse(gesture.valid()); assertEquals(X, gesture.x(), 0); assertEquals(Y - THRESHOLD, gesture.y(), 0);
    }

    @Test public void anOrdinaryLongPressAndAShortTapNeverChooseAnAction() {
        for (long elapsed : new long[]{HOLD - 1, HOLD, HOLD + 500}) {
            LongPressDirection gesture = gesture();
            event(gesture, 100, MOVE, X + SLOP, Y);
            event(gesture, elapsed, UP, X, Y);
            assertTrue(gesture.valid()); assertTrue(gesture.released()); assertNull(gesture.direction());
        }
    }

    @Test public void movementBeforeTheHoldTimeCancelsAndCannotRecover() {
        LongPressDirection gesture = gesture();
        event(gesture, HOLD - 1, MOVE, X + SLOP + 1, Y); rejected(gesture);
        gesture = gesture();
        event(gesture, HOLD - 1, UP, X + THRESHOLD, Y); rejected(gesture);
    }

    @Test public void cancelAdditionalPointersOtherFlowsAndANewDownCancel() {
        for (int invalid = 0; invalid < 7; invalid++) {
            LongPressDirection gesture = gesture();
            if (invalid == 0) event(gesture, HOLD, CANCEL, X, Y);
            if (invalid == 1) gesture.event(0, DEVICE, START, START + HOLD, MOVE, 2, X, Y, START + HOLD);
            if (invalid == 2) gesture.event(1, DEVICE, START, START + HOLD, MOVE, 1, X, Y, START + HOLD);
            if (invalid == 3) gesture.event(0, DEVICE + 1, START, START + HOLD, MOVE, 1, X, Y, START + HOLD);
            if (invalid == 4) gesture.event(0, DEVICE, START + 1, START + HOLD, MOVE, 1, X, Y, START + HOLD);
            if (invalid == 5) event(gesture, HOLD, DOWN, X, Y);
            if (invalid == 6) gesture.event(0, DEVICE, START, START + HOLD, 5, 1, X, Y, START + HOLD);
            rejected(gesture);
        }
    }

    @Test public void backwardsFutureAndStaleEventsCancel() {
        for (int invalid = 0; invalid < 4; invalid++) {
            LongPressDirection gesture = gesture();
            if (invalid == 0) {
                event(gesture, 200, MOVE, X, Y); event(gesture, 199, MOVE, X, Y);
            }
            if (invalid == 1) gesture.event(0, DEVICE, START, START - 1, MOVE, 1, X, Y, START);
            if (invalid == 2) gesture.event(0, DEVICE, START, START + HOLD, MOVE, 1, X, Y, START + HOLD - 1);
            if (invalid == 3) gesture.event(0, DEVICE, START, START + HOLD, MOVE, 1, X, Y, START + HOLD + 301);
            rejected(gesture);
        }
        LongPressDirection boundary = gesture();
        boundary.event(0, DEVICE, START, START + HOLD, UP, 1, X, Y, START + HOLD + 300);
        assertTrue(boundary.valid()); assertTrue(boundary.released());
    }

    @Test public void aGestureCannotOutliveThirtySecondsEvenWhenItsLastEventIsFresh() {
        LongPressDirection boundary = gesture();
        event(boundary, 30_000, UP, X + THRESHOLD, Y); assertTrue(boundary.valid()); assertEquals("right", boundary.direction());
        LongPressDirection expired = gesture();
        event(expired, 30_001, UP, X + THRESHOLD, Y); rejected(expired);
        expired = gesture();
        expired.event(0, DEVICE, START, START + 29_900, UP, 1, X, Y, START + 30_001); rejected(expired);
    }

    @Test public void invalidConstructorValuesAndCoordinatesNeverBecomeValid() {
        for (float invalid : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            rejected(new LongPressDirection(START, DEVICE, X, Y, HOLD, invalid, THRESHOLD));
            rejected(new LongPressDirection(START, DEVICE, X, Y, HOLD, SLOP, invalid));
        }
        rejected(new LongPressDirection(START, DEVICE, X, Y, HOLD, SLOP, SLOP));
        rejected(new LongPressDirection(-1, DEVICE, X, Y, HOLD, SLOP, THRESHOLD));
        rejected(new LongPressDirection(START, -1, X, Y, HOLD, SLOP, THRESHOLD));
        rejected(new LongPressDirection(START, DEVICE, X, Y, 0, SLOP, THRESHOLD));
        rejected(new LongPressDirection(START, DEVICE, X, Y, 30_001, SLOP, THRESHOLD));
        for (float invalid : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY}) {
            rejected(new LongPressDirection(START, DEVICE, invalid, Y, HOLD, SLOP, THRESHOLD));
            LongPressDirection gesture = gesture(); event(gesture, HOLD, MOVE, X, invalid); rejected(gesture);
        }
    }
}
