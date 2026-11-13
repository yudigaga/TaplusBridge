package io.github.yudigaga.taplusbridge.probe;

import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One short-lived expected source-menu close; never a general Back suppression window. */
public final class RecognitionMenuRetention {
    private static final long MAX_AGE = 250;

    @FunctionalInterface
    public interface SourceCheck {
        boolean valid(ScopeCaptureWindow.Snapshot target) throws ReflectiveOperationException;
    }

    private record ExpectedClose(ScopeCaptureWindow.Snapshot target, String identity,
                                 long input, long shown, long armedAt, long cancellation) { }

    private final LongSupplier inputGeneration;
    private final Supplier<RuntimeSnapshot> configuration;
    private final BooleanSupplier touchValidated;
    private final SourceCheck sourceCheck;
    private final AtomicReference<ExpectedClose> expected = new AtomicReference<>();
    private final AtomicLong cancellation = new AtomicLong();

    public RecognitionMenuRetention(LongSupplier inputGeneration, Supplier<RuntimeSnapshot> configuration,
                                    BooleanSupplier touchValidated, SourceCheck sourceCheck) {
        this.inputGeneration = inputGeneration;
        this.configuration = configuration;
        this.touchValidated = touchValidated;
        this.sourceCheck = sourceCheck;
    }

    public boolean arm(ScopeCaptureWindow.Snapshot target, String identity, long input,
                       long shownElapsed, boolean navigationGuardReady) {
        long sequence = cancellation.incrementAndGet(), now = SystemClock.elapsedRealtime();
        expected.set(null);
        if (!navigationGuardReady || target == null || identity == null || shownElapsed < 0 || shownElapsed > now)
            return false;
        ExpectedClose lease = new ExpectedClose(target, identity, input, shownElapsed, now, sequence);
        if (!valid(lease) || !sameSource(lease) || !valid(lease)) return false;
        expected.set(lease);
        // Navigation may cancel while the external foreground check is still running.
        if (!valid(lease)) { expected.compareAndSet(lease, null); return false; }
        return expected.get() == lease;
    }

    public boolean claim(String source, String activity, long shownElapsed) {
        ExpectedClose lease = expected.get();
        if (lease == null) return false;
        if (!valid(lease)) { expected.compareAndSet(lease, null); return false; }
        if (!lease.target.source.equals(source) || !lease.target.activity.equals(activity) || lease.shown != shownElapsed)
            return false;
        // SourceCheck may take the WMS lock; no retention monitor is held around it.
        if (!sameSource(lease) || !valid(lease)) { expected.compareAndSet(lease, null); return false; }
        return expected.compareAndSet(lease, null) && valid(lease);
    }

    public void cancel() {
        cancellation.incrementAndGet();
        expected.set(null);
    }

    private boolean valid(ExpectedClose lease) {
        try {
            long age = SystemClock.elapsedRealtime() - lease.armedAt;
            if (age < 0 || age >= MAX_AGE || cancellation.get() != lease.cancellation
                    || inputGeneration.getAsLong() != lease.input || !touchValidated.getAsBoolean()) return false;
            RuntimeSnapshot config = configuration.get();
            return config != null && config.usable() && config.enabled && config.recognition
                    && config.user == lease.target.user && lease.target.uid / 100000 == config.user
                    && lease.identity.equals(config.identity) && config.sourceScope.allows(lease.target.source);
        } catch (RuntimeException unavailable) { return false; }
    }

    private boolean sameSource(ExpectedClose lease) {
        try { return sourceCheck.valid(lease.target); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }
}
