package io.github.yudigaga.taplusbridge.probe;

import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** UNKNOWN compatibility/entry characterization, not a real microphone or recording-button reproduction. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeInteractionBoundaryAndroidTest {
    @Test public void anUnknownSourceCanStillReachCaptureAfterItsOriginalValidUp() throws Exception {
        try (SystemMenuQueueFixture f = SystemMenuQueueFixture.scopeProbe(true, false)) {
            f.begin();
            f.advanceTo(200); f.drainWorker(); int heldCaptures = f.captureCalls.get();
            f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
            f.advanceTo(500); f.drainWorker(); int beforeDelay = f.captureCalls.get();
            f.advanceTo(502); f.drainWorker(); int afterDelay = f.captureCalls.get();
            f.advanceTo(2_000); f.drainWorker();

            assertEquals(0, heldCaptures); assertEquals(0, beforeDelay);
            assertEquals("UNKNOWN keeps the existing capture path; this is a potential interference entry", 1, afterDelay);
            assertEquals(1, f.captureCalls.get()); assertEquals(0, f.linkTaps.get());
            assertEquals("No added physical UP/CANCEL may be used to classify the interaction", 2, f.proceededInputs.get());
        }
    }

    @Test public void anUnknownSourceKeepsTheExistingMenuBackOnlyAfterValidRelease() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            f.begin(); var callback = f.showMenu(101);
            f.advanceTo(200); f.drainWorker(); int whileHeld = callback.calls;
            f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
            int afterRelease = callback.calls;
            f.advanceTo(2_000); f.drainWorker();

            assertEquals(0, whileHeld);
            assertEquals("UNKNOWN must not silently enable the future conservative setting", 1, afterRelease);
            assertEquals(1, callback.calls); assertEquals(2, f.proceededInputs.get());
            assertEquals(0, f.captureCalls.get()); assertEquals(0, f.linkTaps.get());
        }
    }

    @Test public void aCancelledUnknownSourceCannotReachCaptureOrProbe() throws Exception {
        try (SystemMenuQueueFixture f = SystemMenuQueueFixture.scopeProbe(true, true)) {
            f.begin();
            f.advanceTo(199); f.queue(MotionEvent.ACTION_CANCEL, 199); f.drainInput(); f.drainWorker();
            f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
            f.advanceTo(2_000); f.drainWorker();

            assertEquals(0, f.captureCalls.get()); assertEquals(0, f.linkTaps.get());
            assertEquals(3, f.proceededInputs.get());
            assertSame("The module must not replace the source's focus to diagnose the touch", f.main,
                    f.atms.mWindowManager.mRoot.display.mCurrentFocus);
        }
    }
}
