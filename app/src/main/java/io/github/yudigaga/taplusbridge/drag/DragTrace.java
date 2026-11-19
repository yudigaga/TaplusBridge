package io.github.yudigaga.taplusbridge.drag;

import android.os.Bundle;
import android.os.SystemClock;

/** Descriptive timing only. This metadata never grants gesture, window, or token ownership. */
public record DragTrace(int device, long downUptime, long downElapsed) {
    public static DragTrace unavailable() { return new DragTrace(-1, -1, -1); }

    public static DragTrace read(Bundle wire) {
        if (wire == null) return unavailable();
        try {
            if (!(wire.get("diagnostic_device") instanceof Integer device)
                    || !(wire.get("diagnostic_down_uptime") instanceof Long uptime)
                    || !(wire.get("diagnostic_down_elapsed") instanceof Long elapsed)
                    || device < 0 || uptime < 0 || uptime > SystemClock.uptimeMillis()
                    || elapsed < 0 || elapsed > SystemClock.elapsedRealtime()) return unavailable();
            return new DragTrace(device, uptime, elapsed);
        } catch (RuntimeException malformed) { return unavailable(); }
    }

    public void writeTo(Bundle wire) {
        wire.putInt("diagnostic_device", device);
        wire.putLong("diagnostic_down_uptime", downUptime);
        wire.putLong("diagnostic_down_elapsed", downElapsed);
    }

    public String gestureId() { return device < 0 || downUptime < 0 ? "unavailable" : device + "_" + downUptime; }
    public long elapsedAt(long now) { return downElapsed < 0 || now < downElapsed ? -1 : now - downElapsed; }
    public String fields(long now) { return " gesture_id=" + gestureId() + " e2e_ms=" + elapsedAt(now); }
}
