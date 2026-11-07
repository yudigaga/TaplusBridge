package io.github.yudigaga.taplusbridge.probe;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import android.view.MotionEvent;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class SystemProbeTimingTest {
    @Test public void transferredUpRemovesTheOriginalStreamFromPhysicalTouchState() {
        PhysicalTouchState physical = new PhysicalTouchState();
        SystemProbe.recordTransferredTouch(physical, 0, 7, 1_000, 1_000,
                MotionEvent.ACTION_DOWN, 1_000, 500);
        assertFalse(physical.released());
        SystemProbe.recordTransferredTouch(physical, 0, 7, 1_000, 2_000,
                MotionEvent.ACTION_UP, 2_000, 1_500);
        assertTrue(physical.released());
        assertEquals(1_500, physical.releasedElapsedAt());
    }
}
