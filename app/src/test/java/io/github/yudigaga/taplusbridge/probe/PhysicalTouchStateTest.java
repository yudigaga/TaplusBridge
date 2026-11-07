package io.github.yudigaga.taplusbridge.probe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PhysicalTouchStateTest {
    private void event(PhysicalTouchState state,int device,long down,long at,int action) {
        state.event(0,device,down,at,action,at);
    }
    @Test public void onlyObservedTerminalUpProvesIdle() {
        PhysicalTouchState state=new PhysicalTouchState(); assertFalse(state.released());
        event(state,1,10,10,PhysicalTouchState.DOWN); assertFalse(state.released());
        event(state,1,10,11,PhysicalTouchState.POINTER_UP); assertFalse(state.released());
        event(state,1,10,12,PhysicalTouchState.UP); assertTrue(state.released()); assertEquals(12,state.releasedAt());
    }
    @Test public void elapsedReleaseTimeIsAvailableOnlyForTheCurrentIdleTouch() {
        PhysicalTouchState state = new PhysicalTouchState();
        event(state, 1, 10, 10, PhysicalTouchState.DOWN);
        state.markReleasedElapsed(3_000);
        assertEquals(-1, state.releasedElapsedAt());
        event(state, 1, 10, 12, PhysicalTouchState.UP);
        state.markReleasedElapsed(4_100);
        assertEquals(4_100, state.releasedElapsedAt());
        event(state, 1, 20, 20, PhysicalTouchState.DOWN);
        assertEquals(-1, state.releasedElapsedAt());
    }
    @Test public void aSoleStreamMustBeRememberedBeforeItsTerminalUpRemovesIt() {
        PhysicalTouchState state = new PhysicalTouchState();
        event(state, 1, 10, 10, PhysicalTouchState.DOWN);
        assertTrue(state.soleStream(0, 1, 10));
        assertFalse(state.soleStream(0, 2, 10)); assertFalse(state.soleStream(0, 1, 11));
        boolean soleBefore = state.soleStream(0, 1, 10);
        event(state, 1, 10, 20, PhysicalTouchState.UP);
        assertFalse(state.soleStream(0, 1, 10)); assertTrue(soleBefore && state.released());
    }
    @Test public void anotherActiveOrUncertainStreamCannotSupplyAnExclusiveUp() {
        PhysicalTouchState state = new PhysicalTouchState();
        event(state, 1, 10, 10, PhysicalTouchState.DOWN); event(state, 2, 11, 11, PhysicalTouchState.DOWN);
        boolean soleBefore = state.soleStream(0, 1, 10);
        event(state, 1, 10, 20, PhysicalTouchState.UP);
        assertFalse(soleBefore); assertFalse(state.released());
        state.uncertainDevice(1, 21); soleBefore = state.soleStream(0, 2, 11);
        event(state, 2, 11, 22, PhysicalTouchState.UP);
        assertFalse(soleBefore); assertFalse(state.released());
    }
    @Test public void anUnobservedOrMismatchedUpNeverInheritsTheCurrentStreamsOwnership() {
        PhysicalTouchState state = new PhysicalTouchState();
        assertFalse(state.soleStream(0, 1, 10)); event(state, 1, 10, 11, PhysicalTouchState.UP);
        assertTrue(state.released());
        event(state, 1, 20, 20, PhysicalTouchState.DOWN);
        assertFalse(state.soleStream(0, 1, 10)); event(state, 1, 10, 21, PhysicalTouchState.UP);
        assertFalse(state.released()); assertTrue(state.soleStream(0, 1, 20));
    }
    @Test public void newDownRevokesPriorProofUntilOwnUp() {
        PhysicalTouchState state=new PhysicalTouchState();
        event(state,1,10,12,PhysicalTouchState.UP); assertTrue(state.released());
        event(state,1,20,20,PhysicalTouchState.DOWN); assertFalse(state.released());
        event(state,1,20,22,PhysicalTouchState.UP); assertTrue(state.released());
    }
    @Test public void everyActivePhysicalDeviceMustEnd() {
        PhysicalTouchState state=new PhysicalTouchState();
        event(state,1,10,10,PhysicalTouchState.DOWN); event(state,2,11,11,PhysicalTouchState.DOWN);
        event(state,1,10,12,PhysicalTouchState.UP); assertFalse(state.released());
        event(state,2,11,13,PhysicalTouchState.UP); assertTrue(state.released());
    }
    @Test public void cancelIsUnknownAndAnotherDeviceUpCannotProveRelease() {
        PhysicalTouchState state=new PhysicalTouchState();
        event(state,1,10,10,PhysicalTouchState.DOWN); event(state,1,10,11,PhysicalTouchState.CANCEL);
        event(state,2,12,13,PhysicalTouchState.UP); assertFalse(state.released());
        event(state,1,14,14,PhysicalTouchState.DOWN); event(state,1,14,15,PhysicalTouchState.UP); assertTrue(state.released());
    }
    @Test public void unknownMoveCannotAuthorizeClick() {
        PhysicalTouchState state=new PhysicalTouchState();
        event(state,1,10,11,PhysicalTouchState.MOVE); assertFalse(state.released());
        event(state,2,10,12,PhysicalTouchState.UP); assertFalse(state.released());
        event(state,1,10,13,PhysicalTouchState.UP); assertFalse(state.released());
        event(state,1,14,14,PhysicalTouchState.DOWN);event(state,1,14,15,PhysicalTouchState.UP);
        assertTrue(state.released());
    }
    @Test public void oldUpCannotClearNewerDownOnSameDevice() {
        PhysicalTouchState state=new PhysicalTouchState();
        event(state,1,20,20,PhysicalTouchState.DOWN); event(state,1,10,21,PhysicalTouchState.UP);
        assertFalse(state.released()); event(state,1,20,22,PhysicalTouchState.UP); assertTrue(state.released());
    }
    @Test public void asyncInjectedLifecycleNeverBecomesPhysicalEvidence() {
        PhysicalTouchState state=new PhysicalTouchState(); state.injected(0,0,100,100);
        event(state,0,100,101,PhysicalTouchState.DOWN); event(state,0,100,102,PhysicalTouchState.UP); assertFalse(state.released());
        event(state,3,103,104,PhysicalTouchState.UP); assertTrue(state.released());
        event(state,0,100,105,PhysicalTouchState.DOWN); assertTrue(state.released());
    }
    @Test public void physicalSameDeviceDifferentDownTimeIsNotFiltered() {
        PhysicalTouchState state=new PhysicalTouchState(); event(state,0,10,11,PhysicalTouchState.UP);
        state.injected(0,0,20,20); event(state,0,21,21,PhysicalTouchState.DOWN);
        assertFalse(state.released()); event(state,0,21,22,PhysicalTouchState.UP); assertTrue(state.released());
    }
    @Test public void inputDispatcherVirtualDeviceAliasNeverLeavesPhysicalCancelState() {
        PhysicalTouchState state=new PhysicalTouchState(); event(state,4,10,11,PhysicalTouchState.UP);
        state.injected(0,0,20,20); state.injected(0,-1,20,20);
        event(state,-1,20,21,PhysicalTouchState.DOWN); event(state,-1,20,22,PhysicalTouchState.CANCEL);
        assertTrue(state.released());
        state.injected(0,0,23,23); state.injected(0,-1,23,23);
        event(state,-1,23,24,PhysicalTouchState.DOWN); event(state,-1,23,25,PhysicalTouchState.UP);
        assertTrue(state.released());
        event(state,4,26,26,PhysicalTouchState.DOWN); assertFalse(state.released());
        event(state,4,26,27,PhysicalTouchState.UP); assertTrue(state.released());
    }
    @Test public void unsupportedDisplayAndInvalidTimesCannotSupplyProof() {
        PhysicalTouchState state=new PhysicalTouchState();
        state.event(1,1,10,11,PhysicalTouchState.UP,11); assertFalse(state.released());
        state.event(0,1,20,19,PhysicalTouchState.UP,21); assertFalse(state.released());
        state.event(0,1,20,22,PhysicalTouchState.UP,21); assertFalse(state.released());
    }
    @Test public void observedUpMayPrecedeHostIpcAndUnknownStateDisablesFurtherInjection() {
        PhysicalTouchState state=new PhysicalTouchState(); event(state,1,10,11,PhysicalTouchState.UP);
        assertTrue(state.released()); assertEquals(11,state.releasedAt());
        state.unknown(); assertFalse(state.released()); assertTrue(state.unavailable());
        event(state,1,20,21,PhysicalTouchState.UP); assertFalse(state.released());
    }
    @Test public void unknownCannotDiscardActivePhysicalDeviceAndAuthorizeOnAnotherDeviceUp() {
        PhysicalTouchState state=new PhysicalTouchState();
        event(state,1,10,10,PhysicalTouchState.DOWN); state.unknown();
        event(state,2,20,21,PhysicalTouchState.UP); assertFalse(state.released());
        event(state,1,10,22,PhysicalTouchState.UP); assertFalse(state.released());
        assertTrue(state.unavailable());
    }
    @Test public void futureTimestampPoisonsOnlyItsDeviceUntilItsNewCompleteTouch() {
        PhysicalTouchState state=new PhysicalTouchState();
        state.event(0,1,10,12,PhysicalTouchState.UP,11);
        assertFalse(state.released());
        event(state,2,13,13,PhysicalTouchState.DOWN);event(state,2,13,14,PhysicalTouchState.UP);
        assertFalse(state.released());
        event(state,1,10,15,PhysicalTouchState.UP);assertFalse(state.released());
        event(state,1,20,20,PhysicalTouchState.DOWN);event(state,1,20,21,PhysicalTouchState.UP);
        assertTrue(state.released());assertFalse(state.unavailable());
    }
    @Test public void malformedDeviceTimeDoesNotDiscardAnotherActiveTouch() {
        PhysicalTouchState state=new PhysicalTouchState();
        event(state,2,10,10,PhysicalTouchState.DOWN);
        state.event(0,1,30,19,PhysicalTouchState.UP,20);
        event(state,1,21,21,PhysicalTouchState.DOWN);event(state,1,21,22,PhysicalTouchState.UP);
        assertFalse(state.released());
        event(state,2,10,23,PhysicalTouchState.UP);assertTrue(state.released());
    }
    @Test public void hoverAndButtonActionsAreIgnoredBeforeTheirTimestampCanDamageTouchState() {
        PhysicalTouchState state=new PhysicalTouchState();event(state,1,10,11,PhysicalTouchState.UP);
        for(int action:new int[]{7,8,9,10,11,12,99})state.event(0,2,0,100,action,12);
        assertTrue(state.released());assertFalse(state.unavailable());
        event(state,1,20,20,PhysicalTouchState.DOWN);event(state,1,20,21,PhysicalTouchState.UP);
        assertTrue(state.released());
    }
    @Test public void pointerClassificationExcludesMouseHoverAndButtonButIncludesTouchAndStylus() {
        assertTrue(PhysicalTouchState.isTouchPointer(0x1002,PhysicalTouchState.DOWN));
        assertTrue(PhysicalTouchState.isTouchPointer(0x4002,PhysicalTouchState.UP));
        assertFalse(PhysicalTouchState.isTouchPointer(0x2002,PhysicalTouchState.DOWN));
        assertFalse(PhysicalTouchState.isTouchPointer(0x1002,7));
        assertFalse(PhysicalTouchState.isTouchPointer(0x20004,PhysicalTouchState.MOVE));
    }
    @Test public void knownDeviceReadFailureNeedsItsFreshDownUpAndDoesNotClearAnotherActiveDevice() {
        PhysicalTouchState state=new PhysicalTouchState();event(state,2,10,10,PhysicalTouchState.DOWN);
        state.uncertainDevice(1,11);event(state,2,10,12,PhysicalTouchState.UP);
        assertFalse(state.released());event(state,1,10,13,PhysicalTouchState.UP);assertFalse(state.released());
        event(state,1,14,14,PhysicalTouchState.DOWN);event(state,1,14,15,PhysicalTouchState.UP);
        assertTrue(state.released());assertFalse(state.unavailable());
    }
    @Test public void overSixteenUniquePhysicalDevicesStillDisablesInputPermanently() {
        PhysicalTouchState state=new PhysicalTouchState();
        for(int device=0;device<17;device++)event(state,device,10,10,PhysicalTouchState.DOWN);
        assertTrue(state.unavailable());
        for(int device=0;device<17;device++)event(state,device,10,11,PhysicalTouchState.UP);
        assertFalse(state.released());assertTrue(state.unavailable());
    }
}
