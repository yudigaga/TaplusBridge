package io.github.yudigaga.taplusbridge.probe;

import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopePolicyOperationAndroidTest {
    @Test public void aRealRetainSharesItsEntryPolicyAndPreservesBothTerminalFreshReads() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
            int closed = callback.calls;
            f.policyReads.set(0);
            boolean kept = f.retain();
            int reads = f.policyReads.get();
            boolean repeated = f.retain();
            f.advanceTo(2_000); f.drainWorker();

            assertEquals("The successful real UP close is a prerequisite", 1, closed);
            assertTrue("The shared policy must preserve the one-shot echo", kept);
            assertEquals("Share entry policy, but retain post-WMS and post-consume fresh authorization", 3, reads);
            assertFalse(repeated); assertEquals(1, callback.calls);
        }
    }
}
