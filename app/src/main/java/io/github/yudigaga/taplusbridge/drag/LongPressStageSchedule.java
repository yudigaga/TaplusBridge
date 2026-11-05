package io.github.yudigaga.taplusbridge.drag;

import io.github.yudigaga.taplusbridge.config.DragSettings;

/** Cumulative elapsed-realtime deadlines for one native long-press stream. */
public final class LongPressStageSchedule {
    public record Deadlines(long nativeAt, long recognitionAt, long dragAt) {
        public long remainingRecognition(long now) { return Math.max(0, recognitionAt - now); }
        public long remainingDrag(long now) { return Math.max(0, dragAt - now); }
    }

    private LongPressStageSchedule() { }

    public static boolean isReady(long deadline, long now) { return deadline <= now; }

    public static long resolveRecognitionAt(long nativeDeadline, long pickedAt, int recognitionDelayMillis) {
        if (nativeDeadline > 0) return nativeDeadline;
        if (pickedAt < 0 || recognitionDelayMillis < DragSettings.MIN_STAGE_DELAY
                || recognitionDelayMillis > DragSettings.MAX_STAGE_DELAY) return 0;
        try { return Math.addExact(pickedAt, recognitionDelayMillis); }
        catch (ArithmeticException overflow) { return 0; }
    }

    public static Deadlines fromDown(long downElapsed, long nativeTimeoutMillis,
                                     int recognitionDelayMillis, int dragDelayMillis) {
        if (downElapsed < 0 || nativeTimeoutMillis < 100 || nativeTimeoutMillis > 5_000
                || recognitionDelayMillis < DragSettings.MIN_STAGE_DELAY
                || recognitionDelayMillis > DragSettings.MAX_STAGE_DELAY
                || dragDelayMillis < DragSettings.MIN_STAGE_DELAY
                || dragDelayMillis > DragSettings.MAX_STAGE_DELAY) throw new IllegalArgumentException("gesture_stage_time");
        try {
            long nativeAt = Math.addExact(downElapsed, nativeTimeoutMillis);
            long recognitionAt = Math.addExact(nativeAt, recognitionDelayMillis);
            long dragAt = Math.addExact(recognitionAt, dragDelayMillis);
            return new Deadlines(nativeAt, recognitionAt, dragAt);
        } catch (ArithmeticException overflow) { throw new IllegalArgumentException("gesture_stage_overflow", overflow); }
    }
}
