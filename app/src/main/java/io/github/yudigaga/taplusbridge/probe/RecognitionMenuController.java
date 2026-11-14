package io.github.yudigaga.taplusbridge.probe;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Coordinates one physical gesture with its actually displayed recognition entry. */
public final class RecognitionMenuController {
    private static final long MAX_AGE = 30_000, OBSERVATION = 1_500, POLL = 100;

    @FunctionalInterface
    public interface Closer {
        String close(ScopeCaptureWindow.Snapshot target, BooleanSupplier stillCurrent)
                throws ReflectiveOperationException;
    }

    private record Candidate(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config,
                             long began, long nativeAt, long readyAt, long input) { }
    private record Visible(Candidate candidate, long shownElapsed) { }

    private final Handler worker;
    private final LongSupplier inputGeneration;
    private final Supplier<RuntimeSnapshot> configuration;
    private final BooleanSupplier touchValidated;
    private final Closer closer;
    private final Consumer<String> logger;
    // Binder readers only observe this immutable lease; all other state belongs to worker.
    private volatile Candidate current;
    private volatile Visible visible;
    private long observationUntil;
    private final Runnable poll = this::check;

    public RecognitionMenuController(Handler worker, LongSupplier inputGeneration,
                                     Supplier<RuntimeSnapshot> configuration, BooleanSupplier touchValidated,
                                     Closer closer, Consumer<String> logger) {
        this.worker = worker;
        this.inputGeneration = inputGeneration;
        this.configuration = configuration;
        this.touchValidated = touchValidated;
        this.closer = closer;
        this.logger = logger;
    }

    public void begin(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config,
                      long began, long nativeAt, long readyAt, long inputSequence) {
        onWorker(() -> {
            clear();
            long now = SystemClock.elapsedRealtime();
            if (target == null || config == null || began < 0 || began > now
                    || nativeAt < began || readyAt < nativeAt || readyAt - began >= MAX_AGE) return;
            Candidate candidate = new Candidate(target, config, began, nativeAt, readyAt, inputSequence);
            current = candidate;
            if (!stillCurrent(candidate)) { clear(); return; }
            worker.postDelayed(poll, MAX_AGE - (now - began));
        });
    }

    public void cancel() { onWorker(this::clear); }

    /** Authentication stays at the Binder boundary; this only matches its gesture lease. */
    public boolean shown(String source, long shownElapsed) {
        Candidate candidate = current;
        if (candidate == null || !candidate.target.source.equals(source)
                || shownElapsed < candidate.nativeAt || shownElapsed > SystemClock.elapsedRealtime()
                || !stillCurrent(candidate)) return false;
        // Even callers already on worker enqueue so a host UI notification never closes synchronously.
        worker.post(() -> {
            if (current != candidate) return;
            if (!stillCurrent(candidate)) { finish("stale"); return; }
            if (visible != null) return;
            visible = new Visible(candidate, shownElapsed);
            check();
        });
        return true;
    }

    public long shownElapsed(ScopeCaptureWindow.Snapshot target) {
        Candidate candidate = current;
        Visible shown = visible;
        return candidate != null && candidate.target == target && shown != null && shown.candidate == candidate
                ? shown.shownElapsed : -1;
    }

    /** A validated held stream or stable UP may wake the observer before its next poll. */
    public void released() { onWorker(this::check); }

    private void check() {
        worker.removeCallbacks(poll);
        Candidate candidate = current;
        if (candidate == null) return;
        if (!stillCurrent(candidate)) { finish("stale"); return; }
        long now = SystemClock.elapsedRealtime();
        long remaining = MAX_AGE - (now - candidate.began);
        if (visible == null) { worker.postDelayed(poll, remaining); return; }
        // The native card may appear early; it cannot authorize closing a menu before recognition.
        if (now < candidate.readyAt) { worker.postDelayed(poll, candidate.readyAt - now); return; }
        if (!touchValidated.getAsBoolean()) {
            worker.postDelayed(poll, Math.min(POLL, remaining));
            return;
        }
        if (observationUntil == 0) observationUntil = Math.min(now + OBSERVATION, candidate.began + MAX_AGE);
        if (now >= observationUntil) { finish("none"); return; }
        String result;
        try {
            result = closer.close(candidate.target,
                    () -> SystemClock.elapsedRealtime() >= candidate.readyAt
                            && stillCurrent(candidate) && touchValidated.getAsBoolean());
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            result = "unsupported";
        }
        if (!"none".equals(result)) {
            finish(switch (result == null ? "" : result) {
                case "sent", "stale", "unsupported", "ambiguous", "changed" -> result;
                default -> "unsupported";
            });
            return;
        }
        worker.postDelayed(poll, Math.min(POLL, observationUntil - now));
    }

    private boolean stillCurrent(Candidate candidate) {
        if (candidate == null || current != candidate || inputGeneration.getAsLong() != candidate.input
                || SystemClock.elapsedRealtime() - candidate.began >= MAX_AGE) return false;
        RuntimeSnapshot latest = configuration.get();
        return latest != null && latest.usable() && latest.enabled && latest.recognition
                && latest.identity.equals(candidate.config.identity) && latest.sourceScope.allows(candidate.target.source);
    }

    private void finish(String outcome) {
        clear();
        logger.accept(outcome);
    }

    private void clear() {
        current = null;
        visible = null;
        observationUntil = 0;
        worker.removeCallbacks(poll);
    }

    private void onWorker(Runnable action) {
        if (Looper.myLooper() == worker.getLooper()) action.run();
        else worker.post(action);
    }
}
