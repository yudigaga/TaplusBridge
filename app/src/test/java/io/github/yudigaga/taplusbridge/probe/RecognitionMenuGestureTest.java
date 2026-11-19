package io.github.yudigaga.taplusbridge.probe;

import org.junit.Test;

import static org.junit.Assert.*;

public final class RecognitionMenuGestureTest {
    private static final long DOWN_AT = 1_000;
    private static final int SOURCE = 0x1002;

    private static RecognitionMenuGesture.Lease event(RecognitionMenuGesture gesture, int action, long at) {
        return gesture.observe(action, 0, 7, SOURCE, 3, 1, DOWN_AT, at, at, false, true, 10, 20);
    }

    private static RecognitionMenuGesture.Lease down(RecognitionMenuGesture gesture) {
        return event(gesture, PhysicalTouchState.DOWN, DOWN_AT);
    }

    private static RecognitionMenuGesture.Lease finished(RecognitionMenuGesture gesture) {
        RecognitionMenuGesture.Lease lease = down(gesture);
        assertSame(lease, event(gesture, PhysicalTouchState.UP, 1_200));
        return lease;
    }

    @Test public void holdingNeverAuthorizesEvenAfterTheRecognitionThreshold() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = down(gesture);
        assertNotNull(lease);
        assertFalse(gesture.completed(lease, 0, 8));
        assertSame(lease, event(gesture, PhysicalTouchState.MOVE, 1_500));
        assertFalse(gesture.completed(lease, 200, 8));
    }

    @Test public void originalUpDurationMustReachTheExactRecognitionThreshold() {
        long[] duration = {199, 200, 201};
        boolean[] expected = {false, true, true};
        for (int i = 0; i < duration.length; i++) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            RecognitionMenuGesture.Lease lease = down(gesture);
            assertSame(lease, event(gesture, PhysicalTouchState.UP, DOWN_AT + duration[i]));
            assertEquals("duration=" + duration[i], expected[i], gesture.completed(lease, 200, 8));
        }
    }

    @Test public void equalTimestampUpCanCompleteOnlyAZeroDurationRequirement() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = down(gesture);
        assertSame(lease, event(gesture, PhysicalTouchState.UP, DOWN_AT));
        assertTrue(gesture.completed(lease, 0, 0));
        assertFalse(gesture.completed(lease, 1, 0));
    }

    @Test public void delayedDeliveryNeverExtendsTheOriginalGestureDuration() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = down(gesture);
        assertSame(lease, gesture.observe(PhysicalTouchState.UP, 0, 7, SOURCE, 3, 1,
                DOWN_AT, 1_199, 1_499, false, true, 10, 20));
        assertFalse(gesture.completed(lease, 200, 8));
    }

    @Test public void upDeliveryAgeOfThreeHundredIsAllowedButThreeHundredOneIsRejected() {
        RecognitionMenuGesture accepted = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = down(accepted);
        assertSame(lease, accepted.observe(PhysicalTouchState.UP, 0, 7, SOURCE, 3, 1,
                DOWN_AT, 1_200, 1_500, false, true, 10, 20));
        assertTrue(accepted.completed(lease, 200, 8));

        RecognitionMenuGesture stale = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease staleLease = down(stale);
        assertNull(stale.observe(PhysicalTouchState.UP, 0, 7, SOURCE, 3, 1,
                DOWN_AT, 1_200, 1_501, false, true, 10, 20));
        assertFalse(stale.completed(staleLease, 200, 8));
    }

    @Test public void downAndMoveAlsoRequireFreshDelivery() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = gesture.observe(PhysicalTouchState.DOWN, 0, 7, SOURCE, 3, 1,
                DOWN_AT, DOWN_AT, 1_300, false, true, 10, 20);
        assertNotNull(lease);
        assertSame(lease, gesture.observe(PhysicalTouchState.MOVE, 0, 7, SOURCE, 3, 1,
                DOWN_AT, 1_100, 1_400, false, true, 10, 20));
        assertNull(gesture.observe(PhysicalTouchState.MOVE, 0, 7, SOURCE, 3, 1,
                DOWN_AT, 1_200, 1_501, false, true, 10, 20));
        assertNull(event(gesture, PhysicalTouchState.UP, 1_300));
        assertFalse(gesture.completed(lease, 200, 8));
        assertNull(gesture.observe(PhysicalTouchState.DOWN, 0, 7, SOURCE, 3, 1,
                DOWN_AT, DOWN_AT, 1_301, false, true, 10, 20));
    }

    @Test public void moveAndUpWithoutTheirOwnDownCannotComplete() {
        for (int action : new int[]{PhysicalTouchState.MOVE, PhysicalTouchState.UP}) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            assertNull(event(gesture, action, 1_200));
            assertFalse(gesture.completed(null, 200, 8));
        }
    }

    @Test public void everyIdentityMustMatchOnBothMoveAndUp() {
        int[][] identity = {{1, 7, SOURCE, 3}, {0, 8, SOURCE, 3},
                {0, 7, SOURCE | 0x10000, 3}, {0, 7, SOURCE, 4}};
        for (int action : new int[]{PhysicalTouchState.MOVE, PhysicalTouchState.UP}) {
            for (int[] changed : identity) {
                RecognitionMenuGesture gesture = new RecognitionMenuGesture();
                RecognitionMenuGesture.Lease lease = down(gesture);
                assertNull(gesture.observe(action, changed[0], changed[1], changed[2], changed[3], 1,
                        DOWN_AT, 1_200, 1_200, false, true, 10, 20));
                assertNull(event(gesture, PhysicalTouchState.UP, 1_201));
                assertFalse(gesture.completed(lease, 200, 8));
            }
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            RecognitionMenuGesture.Lease lease = down(gesture);
            assertNull(gesture.observe(action, 0, 7, SOURCE, 3, 1,
                    DOWN_AT + 1, 1_200, 1_200, false, true, 10, 20));
            assertFalse(gesture.completed(lease, 200, 8));
        }
    }

    @Test public void downRequiresDefaultDisplayAndTouchscreenSourceBits() {
        int[][] invalid = {{-1, SOURCE}, {1, SOURCE}, {0, 0}, {0, 2}, {0, 0x2002}, {0, 0x4002}};
        for (int[] input : invalid) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            RecognitionMenuGesture.Lease prior = finished(gesture);
            assertNull(gesture.observe(PhysicalTouchState.DOWN, input[0], 7, input[1], 3, 1,
                    DOWN_AT, DOWN_AT, DOWN_AT, false, true, 10, 20));
            assertFalse(gesture.completed(prior, 200, 8));
        }
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = gesture.observe(PhysicalTouchState.DOWN, 0, 7,
                SOURCE | 0x10000, 3, 1, DOWN_AT, DOWN_AT, DOWN_AT, false, true, 10, 20);
        assertNotNull(lease);
        assertSame(lease, gesture.observe(PhysicalTouchState.UP, 0, 7, SOURCE | 0x10000, 3, 1,
                DOWN_AT, 1_200, 1_200, false, true, 10, 20));
        assertTrue(gesture.completed(lease, 200, 8));
    }

    @Test public void negativeDevicesCannotBeginOrCompleteAndRevokePriorLeases() {
        for (int device : new int[]{-1, Integer.MIN_VALUE}) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            assertNull(gesture.observe(PhysicalTouchState.DOWN, 0, device, SOURCE, 3, 1,
                    DOWN_AT, DOWN_AT, DOWN_AT, false, true, 10, 20));
            assertNull(gesture.observe(PhysicalTouchState.UP, 0, device, SOURCE, 3, 1,
                    DOWN_AT, 1_200, 1_200, false, true, 10, 20));
            assertFalse(gesture.completed(null, 200, 8));
            for (int action : new int[]{PhysicalTouchState.DOWN, PhysicalTouchState.MOVE, PhysicalTouchState.UP}) {
                RecognitionMenuGesture.Lease held = down(gesture);
                long at = action == PhysicalTouchState.DOWN ? DOWN_AT : 1_200;
                assertNull(gesture.observe(action, 0, device, SOURCE, 3, 1,
                        DOWN_AT, at, at, false, true, 10, 20));
                assertNull(event(gesture, PhysicalTouchState.UP, 1_201));
                assertFalse(gesture.completed(held, 200, 8));

                RecognitionMenuGesture.Lease completed = finished(gesture);
                assertNull(gesture.observe(action, 0, device, SOURCE, 3, 1,
                        DOWN_AT, at, at, false, true, 10, 20));
                assertFalse(gesture.completed(completed, 200, 8));
            }
        }
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = gesture.observe(PhysicalTouchState.DOWN, 0, 0, SOURCE, 3, 1,
                DOWN_AT, DOWN_AT, DOWN_AT, false, true, 10, 20);
        assertNotNull(lease);
        assertSame(lease, gesture.observe(PhysicalTouchState.UP, 0, 0, SOURCE, 3, 1,
                DOWN_AT, 1_200, 1_200, false, true, 10, 20));
        assertTrue(gesture.completed(lease, 200, 8));
    }

    @Test public void pointerIdsMustStayWithinAndroidPointerIdRange() {
        for (int pointerId : new int[]{-1, 32}) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            assertNull(gesture.observe(PhysicalTouchState.DOWN, 0, 7, SOURCE, pointerId, 1,
                    DOWN_AT, DOWN_AT, DOWN_AT, false, true, 10, 20));
        }
        for (int pointerId : new int[]{0, 31}) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            RecognitionMenuGesture.Lease lease = gesture.observe(PhysicalTouchState.DOWN, 0, 7, SOURCE,
                    pointerId, 1, DOWN_AT, DOWN_AT, DOWN_AT, false, true, 10, 20);
            assertNotNull(lease);
            assertSame(lease, gesture.observe(PhysicalTouchState.UP, 0, 7, SOURCE, pointerId, 1,
                    DOWN_AT, 1_200, 1_200, false, true, 10, 20));
            assertTrue(gesture.completed(lease, 200, 8));
        }
    }

    @Test public void everyEventRequiresOnePointerAndExclusivePhysicalOwnership() {
        for (int action : new int[]{PhysicalTouchState.DOWN, PhysicalTouchState.MOVE, PhysicalTouchState.UP}) {
            for (int count : new int[]{0, 2}) {
                RecognitionMenuGesture gesture = new RecognitionMenuGesture();
                RecognitionMenuGesture.Lease lease = down(gesture);
                long at = action == PhysicalTouchState.DOWN ? DOWN_AT : 1_200;
                assertNull(gesture.observe(action, 0, 7, SOURCE, 3, count,
                        DOWN_AT, at, at, false, true, 10, 20));
                assertFalse(gesture.completed(lease, 200, 8));
                assertNull(event(gesture, PhysicalTouchState.UP, 1_201));
            }
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            RecognitionMenuGesture.Lease lease = down(gesture);
            long at = action == PhysicalTouchState.DOWN ? DOWN_AT : 1_200;
            assertNull(gesture.observe(action, 0, 7, SOURCE, 3, 1,
                    DOWN_AT, at, at, false, false, 10, 20));
            assertFalse(gesture.completed(lease, 200, 8));
        }
    }

    @Test public void canceledFlagRejectsDownMoveAndUpAndRevokesTheirLease() {
        for (int action : new int[]{PhysicalTouchState.DOWN, PhysicalTouchState.MOVE, PhysicalTouchState.UP}) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            RecognitionMenuGesture.Lease lease = down(gesture);
            long at = action == PhysicalTouchState.DOWN ? DOWN_AT : 1_200;
            assertNull(gesture.observe(action, 0, 7, SOURCE, 3, 1,
                    DOWN_AT, at, at, true, true, 10, 20));
            assertNull(event(gesture, PhysicalTouchState.UP, 1_201));
            assertFalse(gesture.completed(lease, 200, 8));
        }
    }

    @Test public void cancelPointerTransitionsAndUnknownRawActionsInvalidateTheWholeGesture() {
        for (int action : new int[]{PhysicalTouchState.CANCEL, PhysicalTouchState.POINTER_DOWN,
                PhysicalTouchState.POINTER_UP, 4, 7, 99, 0x102}) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            RecognitionMenuGesture.Lease lease = down(gesture);
            assertNull(event(gesture, action, 1_200));
            assertNull(event(gesture, PhysicalTouchState.UP, 1_201));
            assertFalse(gesture.completed(lease, 200, 8));
            RecognitionMenuGesture.Lease completed = finished(gesture);
            assertNull(event(gesture, action, 1_201));
            assertFalse(gesture.completed(completed, 200, 8));
        }
    }

    @Test public void malformedDownTimesAreRejectedAndRevokePriorCompletion() {
        long[][] times = {{-1, -1, 0}, {1_000, 999, 1_000}, {1_000, 1_001, 1_001},
                {1_000, 1_000, 999}, {1_000, 1_000, -1}};
        for (long[] time : times) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            RecognitionMenuGesture.Lease lease = finished(gesture);
            assertNull(gesture.observe(PhysicalTouchState.DOWN, 0, 7, SOURCE, 3, 1,
                    time[0], time[1], time[2], false, true, 10, 20));
            assertFalse(gesture.completed(lease, 200, 8));
        }
    }

    @Test public void moveAndUpRejectBackwardNegativeOrFutureEventTimes() {
        long[][] times = {{999, 1_200}, {-1, 1_200}, {1_201, 1_200}, {1_200, -1}, {1_200, Long.MIN_VALUE}};
        for (int action : new int[]{PhysicalTouchState.MOVE, PhysicalTouchState.UP}) {
            for (long[] time : times) {
                RecognitionMenuGesture gesture = new RecognitionMenuGesture();
                RecognitionMenuGesture.Lease lease = down(gesture);
                assertNull(gesture.observe(action, 0, 7, SOURCE, 3, 1,
                        DOWN_AT, time[0], time[1], false, true, 10, 20));
                assertFalse(gesture.completed(lease, 200, 8));
            }
        }
    }

    @Test public void eventTimesMustNeverGoBackwardAfterAMove() {
        for (int action : new int[]{PhysicalTouchState.MOVE, PhysicalTouchState.UP}) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            RecognitionMenuGesture.Lease lease = down(gesture);
            assertSame(lease, event(gesture, PhysicalTouchState.MOVE, 1_200));
            assertNull(event(gesture, action, 1_199));
            assertNull(event(gesture, PhysicalTouchState.UP, 1_201));
            assertFalse(gesture.completed(lease, 200, 8));
        }
    }

    @Test public void equalMoveAndUpTimesRemainAValidOrderedStream() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = down(gesture);
        assertSame(lease, event(gesture, PhysicalTouchState.MOVE, 1_200));
        assertSame(lease, event(gesture, PhysicalTouchState.UP, 1_200));
        assertTrue(gesture.completed(lease, 200, 8));
    }

    @Test public void nonFiniteCoordinatesInvalidateAtEveryStage() {
        float[][] coordinates = {{Float.NaN, 20}, {Float.POSITIVE_INFINITY, 20},
                {Float.NEGATIVE_INFINITY, 20}, {10, Float.NaN},
                {10, Float.POSITIVE_INFINITY}, {10, Float.NEGATIVE_INFINITY}};
        for (int action : new int[]{PhysicalTouchState.DOWN, PhysicalTouchState.MOVE, PhysicalTouchState.UP}) {
            for (float[] xy : coordinates) {
                RecognitionMenuGesture gesture = new RecognitionMenuGesture();
                RecognitionMenuGesture.Lease lease = down(gesture);
                long at = action == PhysicalTouchState.DOWN ? DOWN_AT : 1_200;
                assertNull(gesture.observe(action, 0, 7, SOURCE, 3, 1,
                        DOWN_AT, at, at, false, true, xy[0], xy[1]));
                assertNull(event(gesture, PhysicalTouchState.UP, 1_201));
                assertFalse(gesture.completed(lease, 200, 8));
            }
        }
    }

    @Test public void negativeCoordinatesPermanentlyRevokeTheGestureAtEveryStage() {
        float[][] coordinates = {{-1, 0}, {0, -1}, {-Float.MIN_VALUE, 0}, {0, -Float.MIN_VALUE}};
        for (int action : new int[]{PhysicalTouchState.DOWN, PhysicalTouchState.MOVE, PhysicalTouchState.UP}) {
            for (float[] xy : coordinates) {
                RecognitionMenuGesture gesture = new RecognitionMenuGesture();
                RecognitionMenuGesture.Lease held = gesture.observe(PhysicalTouchState.DOWN, 0, 7, SOURCE, 3, 1,
                        DOWN_AT, DOWN_AT, DOWN_AT, false, true, 0, 0);
                assertNotNull(held);
                long at = action == PhysicalTouchState.DOWN ? DOWN_AT : 1_200;
                assertNull(gesture.observe(action, 0, 7, SOURCE, 3, 1,
                        DOWN_AT, at, at, false, true, xy[0], xy[1]));
                assertNull(gesture.observe(PhysicalTouchState.UP, 0, 7, SOURCE, 3, 1,
                        DOWN_AT, 1_201, 1_201, false, true, 0, 0));
                assertFalse(gesture.completed(held, 200, 8));

                RecognitionMenuGesture.Lease completed = finished(gesture);
                assertNull(gesture.observe(action, 0, 7, SOURCE, 3, 1,
                        DOWN_AT, at, at, false, true, xy[0], xy[1]));
                assertFalse(gesture.completed(completed, 200, 8));
            }
        }
    }

    @Test public void excursionCannotBeHiddenByReturningToTheDownPointBeforeUp() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = down(gesture);
        assertSame(lease, gesture.observe(PhysicalTouchState.MOVE, 0, 7, SOURCE, 3, 1,
                DOWN_AT, 1_100, 1_100, false, true, 13, 24));
        assertSame(lease, event(gesture, PhysicalTouchState.UP, 1_200));
        assertFalse(gesture.completed(lease, 200, 4.999f));
        assertTrue(gesture.completed(lease, 200, 5));
    }

    @Test public void finalUpCoordinatesAlsoCountAgainstSlop() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = down(gesture);
        assertSame(lease, gesture.observe(PhysicalTouchState.UP, 0, 7, SOURCE, 3, 1,
                DOWN_AT, 1_200, 1_200, false, true, 16, 28));
        assertFalse(gesture.completed(lease, 200, 9.999f));
        assertTrue(gesture.completed(lease, 200, 10));
    }

    @Test public void largeFiniteCoordinatesDoNotOverflowDisplacementIntoAcceptance() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = gesture.observe(PhysicalTouchState.DOWN, 0, 7, SOURCE, 3, 1,
                DOWN_AT, DOWN_AT, DOWN_AT, false, true, 0, 0);
        assertSame(lease, gesture.observe(PhysicalTouchState.UP, 0, 7, SOURCE, 3, 1,
                DOWN_AT, 1_200, 1_200, false, true, Float.MAX_VALUE, Float.MAX_VALUE));
        assertFalse(gesture.completed(lease, 200, Float.MAX_VALUE));
    }

    @Test public void downAndUpBeforeWorkerBindingCanBeReadRepeatedlyWithoutConsumption() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = finished(gesture);
        for (int read = 0; read < 3; read++) assertTrue(gesture.completed(lease, 200, 0));
    }

    @Test public void invalidCompletionArgumentsAndForeignLeasesDoNotMutateValidProof() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease lease = finished(gesture);
        assertFalse(gesture.completed(null, 200, 8));
        assertFalse(gesture.completed(finished(new RecognitionMenuGesture()), 200, 8));
        assertFalse(gesture.completed(lease, -1, 8));
        for (float slop : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertFalse(gesture.completed(lease, 200, slop));
        }
        assertFalse(gesture.completed(lease, 201, 8));
        assertTrue(gesture.completed(lease, 200, 8));
    }

    @Test public void newDownRevokesTheOldLeaseEvenWhenItsIdentityIsReused() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease old = finished(gesture);
        RecognitionMenuGesture.Lease next = down(gesture);
        assertNotSame(old, next);
        assertFalse(gesture.completed(old, 200, 8));
        assertFalse(gesture.completed(next, 200, 8));
        assertSame(next, event(gesture, PhysicalTouchState.UP, 1_200));
        assertTrue(gesture.completed(next, 200, 8));
        assertFalse(gesture.completed(old, 200, 8));
    }

    @Test public void duplicateUpOrMoveAfterUpRevokesTheCompletedLease() {
        for (int action : new int[]{PhysicalTouchState.UP, PhysicalTouchState.MOVE}) {
            RecognitionMenuGesture gesture = new RecognitionMenuGesture();
            RecognitionMenuGesture.Lease lease = finished(gesture);
            assertNull(event(gesture, action, 1_201));
            assertFalse(gesture.completed(lease, 200, 8));
        }
    }

    @Test public void explicitInvalidationRevokesHeldAndCompletedLeasesUntilANewDown() {
        RecognitionMenuGesture gesture = new RecognitionMenuGesture();
        RecognitionMenuGesture.Lease held = down(gesture);
        gesture.invalidate();
        assertNull(event(gesture, PhysicalTouchState.UP, 1_200));
        assertFalse(gesture.completed(held, 200, 8));
        RecognitionMenuGesture.Lease completed = finished(gesture);
        gesture.invalidate();
        assertFalse(gesture.completed(completed, 200, 8));
        RecognitionMenuGesture.Lease next = finished(gesture);
        assertTrue(gesture.completed(next, 200, 8));
    }
}
