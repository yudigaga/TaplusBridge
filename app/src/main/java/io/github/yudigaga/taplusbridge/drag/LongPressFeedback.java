package io.github.yudigaga.taplusbridge.drag;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;

public final class LongPressFeedback {
    private LongPressFeedback() { }

    public static boolean recognitionReady(boolean enabled, boolean held, boolean releasedLongPress,
                                           long deadline, long now) {
        return enabled && deadline > 0 && now >= deadline && (held || releasedLongPress);
    }

    public static long[] vibrationPattern(boolean dragReady) {
        return dragReady ? new long[]{0, 40, 80, 40} : new long[]{0, 40};
    }

    public static boolean vibrate(Context context, boolean dragReady) {
        try {
            Vibrator vibrator = context == null ? null : context.getSystemService(Vibrator.class);
            if (vibrator == null || !vibrator.hasVibrator()) return false;
            vibrator.vibrate(VibrationEffect.createWaveform(vibrationPattern(dragReady), -1));
            return true;
        } catch (RuntimeException unavailable) { return false; }
    }
}
