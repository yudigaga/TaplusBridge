package io.github.yudigaga.taplusbridge.probe;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/** Read-only same-UID recording risk; absence of visible evidence is always UNKNOWN. */
public final class SourceRecordingState {
    @FunctionalInterface public interface Source {
        Observation observe(int uid);
    }

    public record Observation(int uid, long observedAt) { }

    public static final Observation UNKNOWN = new Observation(-1, -1);
    private static final int MAX_CONFIGURATIONS = 128;

    private SourceRecordingState() { }

    public static Source from(Supplier<Context> context) {
        return uid -> {
            if (uid < 10000 || uid >= 100000) return UNKNOWN;
            long observedAt = SystemClock.elapsedRealtime();
            try {
                Context system = context == null ? null : context.get();
                if (system == null || !(system.getSystemService(Context.AUDIO_SERVICE) instanceof AudioManager audio)) {
                    return UNKNOWN;
                }
                List<?> configurations = audio.getActiveRecordingConfigurations();
                if (configurations == null) return UNKNOWN;
                int count = Math.min(configurations.size(), MAX_CONFIGURATIONS);
                if (count <= 0) return UNKNOWN;
                Method clientUid = AudioRecordingConfiguration.class.getMethod("getClientUid");
                for (int index = 0; index < count; index++) {
                    try {
                        Object configuration = configurations.get(index);
                        if (!(configuration instanceof AudioRecordingConfiguration)) continue;
                        Object value = clientUid.invoke(configuration);
                        if (value instanceof Integer activeUid && activeUid == uid) {
                            return new Observation(uid, observedAt);
                        }
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                        // An anonymous or inaccessible entry does not disprove a later positive match.
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return UNKNOWN;
            }
            return UNKNOWN;
        };
    }
}
