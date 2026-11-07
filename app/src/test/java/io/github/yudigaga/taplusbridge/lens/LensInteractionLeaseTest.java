package io.github.yudigaga.taplusbridge.lens;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LensInteractionLeaseTest {
    private static final int MODULE = 10123, SOURCE = 10456, OTHER = 10789;

    @Test public void onlyExplicitModuleAndBoundedSourceTransitionsAreAllowed() {
        LensInteractionLease lease = new LensInteractionLease(MODULE, SOURCE, 10000);
        assertTrue(lease.foreground(SOURCE, false, 10001));
        assertTrue(lease.foreground(MODULE, true, 10002));
        assertFalse(lease.foreground(OTHER, true, 10003));
        assertTrue(lease.foreground(SOURCE, false, 10000 + LensInteractionLease.LAUNCH_TIMEOUT_MILLIS - 1));
        assertFalse(lease.foreground(SOURCE, false, 10000 + LensInteractionLease.LAUNCH_TIMEOUT_MILLIS + 1));
        assertFalse(lease.foreground(SOURCE, true, 19000));
    }

    @Test public void keyboardExceptionRequiresOwnedActivityActuallyForeground() {
        LensInteractionLease lease = new LensInteractionLease(MODULE, SOURCE, 10000);
        assertFalse(lease.keyboard());
        lease.foreground(MODULE, true, 10001); assertTrue(lease.keyboard());
        lease.foreground(MODULE, false, 10002); assertFalse(lease.keyboard());
    }

    @Test public void endingAllowsOnlyTwoSecondReturnTransitions() {
        LensInteractionLease lease = new LensInteractionLease(MODULE, SOURCE, 10000);
        lease.foreground(MODULE, true, 10001);
        lease.end(30000);
        assertFalse(lease.active()); assertFalse(lease.keyboard());
        assertTrue(lease.foreground(MODULE, false, 30001));
        assertTrue(lease.foreground(SOURCE, true, 30002));
        lease.end(31999);
        assertFalse(lease.foreground(MODULE, true, 30003));
        assertFalse(lease.foreground(SOURCE, false, 30003));
        assertFalse(lease.foreground(OTHER, true, 30004));
        assertFalse(lease.foreground(SOURCE, true, 32001));
    }

    @Test public void activityReturningBeforeResultCanRestoreSameSourceOnly() {
        LensInteractionLease lease = new LensInteractionLease(MODULE, SOURCE, 10000);
        lease.foreground(MODULE, true, 10001);
        assertTrue(lease.foreground(MODULE, false, 50000));
        assertTrue(lease.foreground(SOURCE, true, 50001));
        assertFalse(lease.foreground(SOURCE, true, 52001));
    }

    @Test(expected = IllegalArgumentException.class) public void crossUserIdentitiesAreRejected() {
        new LensInteractionLease(MODULE, SOURCE + 100000, 10000);
    }
}
