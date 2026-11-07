package io.github.yudigaga.taplusbridge.drag;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LongPressStageScheduleTest {
    @Test public void deadlinesAccumulateFromOriginalDownAndRomTimeout() {
        LongPressStageSchedule.Deadlines deadlines = LongPressStageSchedule.fromDown(10_000, 2_000, 2_000, 2_000);
        assertEquals(12_000, deadlines.nativeAt());
        assertEquals(14_000, deadlines.recognitionAt());
        assertEquals(16_000, deadlines.dragAt());
    }

    @Test public void customIntervalsKeepMonotonicOrderAndHandleLateHostCallbacks() {
        LongPressStageSchedule.Deadlines deadlines = LongPressStageSchedule.fromDown(10_000, 1_500, 700, 1_200);
        assertEquals(11_500, deadlines.nativeAt());
        assertEquals(12_200, deadlines.recognitionAt());
        assertEquals(13_400, deadlines.dragAt());
        assertEquals(0, deadlines.remainingRecognition(12_500));
        assertEquals(900, deadlines.remainingDrag(12_500));
    }

    @Test public void rejectsInvalidIntervalsAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> LongPressStageSchedule.fromDown(0, 0, 2_000, 2_000));
        assertThrows(IllegalArgumentException.class, () -> LongPressStageSchedule.fromDown(0, 2_000, 0, 2_000));
        assertThrows(IllegalArgumentException.class, () -> LongPressStageSchedule.fromDown(Long.MAX_VALUE - 1, 2_000, 2_000, 2_000));
    }

    @Test public void recognitionStageIsNotReadyBeforeItsAbsoluteDeadline() {
        assertFalse(LongPressStageSchedule.isReady(14_000, 13_999));
        assertTrue(LongPressStageSchedule.isReady(14_000, 14_000));
        assertTrue(LongPressStageSchedule.isReady(14_000, 14_001));
    }

    @Test public void nativeDeadlineWinsAndMissingLeaseFallsBackToConfiguredDelay() {
        assertEquals(14_500, LongPressStageSchedule.resolveRecognitionAt(14_500, 12_000, 2_000));
        assertEquals(14_000, LongPressStageSchedule.resolveRecognitionAt(0, 12_000, 2_000));
    }

    @Test public void missingOrInvalidFallbackNeverLooksReady() {
        assertEquals(0, LongPressStageSchedule.resolveRecognitionAt(0, -1, 2_000));
        assertEquals(0, LongPressStageSchedule.resolveRecognitionAt(0, 12_000, 99));
        assertEquals(0, LongPressStageSchedule.resolveRecognitionAt(0, Long.MAX_VALUE - 1, 2_000));
    }
}
