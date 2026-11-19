package io.github.yudigaga.taplusbridge.probe;

import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in assertion for the unresolved UP-before-Hook guarantee; the baseline is expected red. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeUpQueueReproTest {
    private void optIn() {
        assumeTrue("Opt in with TAPLUS_RUN_UP_RACE_REPRO=1; this unresolved correctness assertion is expected red",
                "1".equals(System.getenv("TAPLUS_RUN_UP_RACE_REPRO")));
    }

    @Test public void anOriginal199UpNotYetSeenByTheEntireHookMustNotAuthorizeThe201DeadlineCheck() throws Exception {
        optIn();
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin();
            var callback = f.showMenu(101);
            f.advanceTo(199);
            f.queue(MotionEvent.ACTION_UP, 199);
            assertTrue("The real UP is queued before the entire Hook; physical still contains DOWN",
                    f.physical.soleStream(0, 0, f.downUptime));
            f.advanceTo(201);
            f.drainWorker();
            int beforeOriginalUpReachedHook = callback.calls;
            f.drainInput();
            assertEquals(f.beganElapsed + 199, f.physical.releasedElapsedAt());
            f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();
            assertEquals("An upstream UP<T cannot become a held touch just because its Hook is late",
                    0, beforeOriginalUpReachedHook);
            assertEquals("The original early UP cannot revive any old lease", 0, callback.calls);
        }
    }
}
