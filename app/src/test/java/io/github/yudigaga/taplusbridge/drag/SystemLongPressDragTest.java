package io.github.yudigaga.taplusbridge.drag;

import android.view.MotionEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SystemLongPressDragTest {
    @Test public void forwardedDownDoesNotReplaceAnActiveTransferredGesture() {
        assertTrue(SystemLongPressDrag.ignoreTransferredDown(true, false, 7, 7, 1_000, 1_000));
        assertFalse(SystemLongPressDrag.ignoreTransferredDown(false, false, 7, 7, 1_000, 1_000));
        assertFalse(SystemLongPressDrag.ignoreTransferredDown(true, true, 7, 7, 1_000, 1_000));
        assertFalse(SystemLongPressDrag.ignoreTransferredDown(true, false, 7, 8, 1_000, 1_000));
        assertFalse(SystemLongPressDrag.ignoreTransferredDown(true, false, 7, 7, 1_000, 2_000));
    }

    @Test public void transferredStreamIsFilteredUntilUpButANewDownCanStart() {
        assertTrue(SystemLongPressDrag.shouldIgnoreTransferredPointer(true, 0, 7, 7, 1_000, 1_000, false, MotionEvent.ACTION_DOWN));
        assertTrue(SystemLongPressDrag.shouldIgnoreTransferredPointer(true, 0, 7, 7, 1_000, 1_000, true, MotionEvent.ACTION_UP));
        assertFalse(SystemLongPressDrag.shouldIgnoreTransferredPointer(true, 0, 7, 7, 1_000, 1_000, true, MotionEvent.ACTION_DOWN));
        assertFalse(SystemLongPressDrag.shouldIgnoreTransferredPointer(true, 0, 7, 7, 1_000, 2_000, false, MotionEvent.ACTION_DOWN));
        assertFalse(SystemLongPressDrag.shouldIgnoreTransferredPointer(true, 1, 7, 7, 1_000, 1_000, false, MotionEvent.ACTION_MOVE));
        assertFalse(SystemLongPressDrag.shouldIgnoreTransferredPointer(false, 0, 7, 7, 1_000, 1_000, false, MotionEvent.ACTION_MOVE));
    }

    @Test public void unrelatedDownDoesNotResetAnUnreleasedTransferredGesture() {
        assertFalse(SystemLongPressDrag.shouldResetTransferredOnDown(true, 0, 7, 1_000,
                0, 8, 2_000, MotionEvent.ACTION_DOWN));
        assertTrue(SystemLongPressDrag.shouldResetTransferredOnDown(true, 0, 7, 1_000,
                0, 7, 2_000, MotionEvent.ACTION_DOWN));
        assertFalse(SystemLongPressDrag.shouldResetTransferredOnDown(false, 0, 7, 1_000,
                0, 7, 2_000, MotionEvent.ACTION_DOWN));
        assertFalse(SystemLongPressDrag.shouldResetTransferredOnDown(true, 0, 7, 1_000,
                0, 7, 1_000, MotionEvent.ACTION_MOVE));
    }

    @Test public void linkFallbackRequiresReleaseRecognitionDeadlineAndNoNativeUrl() {
        assertFalse(SystemLongPressDrag.linkFallbackReady(false, false, 4_000, 4_000, 3_000));
        assertFalse(SystemLongPressDrag.linkFallbackReady(true, false, 3_999, 4_000, 3_000));
        assertTrue(SystemLongPressDrag.linkFallbackReady(true, false, 4_000, 4_000, 3_000));
        assertFalse(SystemLongPressDrag.linkFallbackReady(true, true, 4_000, 4_000, 3_000));
        assertFalse(SystemLongPressDrag.linkFallbackReady(true, false, 7_000, 4_000, 3_000));
    }

    @Test public void delayedProbeResultIsAcceptedForTheFullProbeTimeoutAfterRelease() {
        assertTrue(SystemLongPressDrag.cardLinkWithinGrace(1_000, 7_000));
        assertTrue(SystemLongPressDrag.cardLinkWithinGrace(1_000, 7_499));
        assertFalse(SystemLongPressDrag.cardLinkWithinGrace(1_000, 7_500));
        assertFalse(SystemLongPressDrag.cardLinkWithinGrace(1_001, 1_000));
    }

    @Test public void dragReadyFeedbackIsSentOnlyAfterTransferAndOnlyOnce() throws Exception {
        assertFalse(SystemLongPressDrag.shouldSendDragFeedback(false, false));
        assertTrue(SystemLongPressDrag.shouldSendDragFeedback(true, false));
        assertFalse(SystemLongPressDrag.shouldSendDragFeedback(true, true));
    }
}
