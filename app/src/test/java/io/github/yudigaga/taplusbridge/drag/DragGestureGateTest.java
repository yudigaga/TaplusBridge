package io.github.yudigaga.taplusbridge.drag;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DragGestureGateTest {
    @Test public void ordinaryLongPressIsUnaffectedBeforeAnyAdmittedDrag() {
        DragGestureGate.State state = new DragGestureGate.State();
        assertFalse(state.suppress(100)); state.end(200); state.nextDown(300);
        assertFalse(state.suppress(900));
    }

    @Test public void admittedDragOwnsTheGestureUntilItEnds() {
        DragGestureGate.State state = new DragGestureGate.State(); state.begin(100);
        assertTrue(state.suppress(100)); assertTrue(state.suppress(10_000));
        state.nextDown(10_001); assertTrue(state.suppress(10_002));
    }

    @Test public void transferredGestureSuppressesOtherStreamsUntilItsOwnPhysicalUp() {
        DragGestureGate.State state = new DragGestureGate.State(); state.beginPhysical(100, 0, 7, 1_000);
        assertTrue(state.suppress(6_000));
        state.physicalDown(0, 8, 2_000, 6_050);
        state.physicalUp(0, 8, 2_000, 6_060);
        assertTrue(state.suppress(6_100));
        state.physicalUp(0, 7, 1_000, 6_100);
        assertFalse(state.suppress(6_100));
    }

    @Test public void genericDragEndKeepsOnlyOneSecondForLateCallbacks() {
        DragGestureGate.State state = new DragGestureGate.State(); state.begin(100);
        state.end(800); assertTrue(state.suppress(1_799));
        state.end(1_700); assertFalse(state.suppress(1_800));
    }

    @Test public void newDownOnTheOwnerDeviceRecoversAMissingPhysicalUp() {
        DragGestureGate.State state = new DragGestureGate.State(); state.beginPhysical(100, 0, 7, 1_000);
        state.physicalDown(0, 7, 2_000, 2_000);
        assertFalse(state.suppress(2_001));
    }

    @Test public void transferredGesturePhysicalUpReleasesImmediately() {
        DragGestureGate.State state = new DragGestureGate.State(); state.beginPhysical(100, 0, 7, 1_000);
        state.physicalUp(0, 7, 1_000, 6_100);
        assertFalse(state.suppress(6_100));
    }

    @Test public void nextPhysicalDownImmediatelyRestoresNormalLongPress() {
        DragGestureGate.State state = new DragGestureGate.State(); state.begin(100); state.end(800);
        state.nextDown(900); assertFalse(state.suppress(901));
    }

    @Test public void aNewDownAfterLostTransferredUpClearsTheOrphanedDragGate() {
        DragGestureGate.State state = new DragGestureGate.State(); state.begin(100);
        assertTrue(state.suppress(500));
        state.end(700); state.nextDown(701);
        assertFalse(state.suppress(702));
    }

    @Test public void missingEndCallbackCannotBlockBeyondSixtySeconds() {
        DragGestureGate.State state = new DragGestureGate.State(); state.begin(100);
        assertTrue(state.suppress(60_099)); assertFalse(state.suppress(60_100));
        state.end(60_101); assertFalse(state.suppress(60_102));
    }

    @Test public void newDragReplacesOldTailAndClockReversalClearsOwnership() {
        DragGestureGate.State state = new DragGestureGate.State(); state.begin(100); state.end(200);
        state.begin(300); assertTrue(state.suppress(5_000));
        assertFalse(state.suppress(299)); assertFalse(state.suppress(301));
    }
}
