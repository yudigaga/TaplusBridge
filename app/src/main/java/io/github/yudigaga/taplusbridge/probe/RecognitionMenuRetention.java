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
    private final Object leaseLock = new Object();

    public RecognitionMenuRetention(LongSupplier inputGeneration, Supplier<RuntimeSnapshot> configuration,
                                    BooleanSupplier touchValidated, SourceCheck sourceCheck) {
        this.inputGeneration = inputGeneration;
        this.configuration = configuration;
        this.touchValidated = touchValidated;
        this.sourceCheck = sourceCheck;
    }

    public boolean arm(ScopeCaptureWindow.Snapshot target, String identity, long input,
                       long shownElapsed, boolean navigationGuardReady) {
        final long sequence;
        synchronized (leaseLock) {
            sequence = cancellation.incrementAndGet();
            expected.set(null);
        }
        long now = SystemClock.elapsedRealtime();
        if (!navigationGuardReady || target == null || identity == null || shownElapsed < 0 || shownElapsed > now)
            return false;
        ExpectedClose lease = new ExpectedClose(target, identity, input, shownElapsed, now, sequence);
        if (!valid(lease) || !sameSource(lease) || !valid(lease)) return false;
        synchronized (leaseLock) {
            if (cancellation.get() != sequence) return false;
            expected.set(lease);
        }
        // Navigation may cancel while the external foreground check is still running.
        if (!valid(lease)) { discard(lease); return false; }
        synchronized (leaseLock) {
            return expected.get() == lease && cancellation.get() == sequence;
        }
    }

    public boolean claim(String source, String activity, long shownElapsed) {
        ExpectedClose lease = expected.get();
        if (lease == null) return false;
        if (!valid(lease)) { discard(lease); return false; }
        if (!lease.target.source.equals(source) || !lease.target.activity.equals(activity) || lease.shown != shownElapsed)
            return false;
        // SourceCheck may take the WMS lock; no retention monitor is held around it.
        if (!sameSource(lease) || !valid(lease)) { discard(lease); return false; }
        synchronized (leaseLock) {
            if (expected.get() != lease || cancellation.get() != lease.cancellation) return false;
            expected.set(null);
        }
        // The terminal epoch read authorizes this consume; later input cannot restore the spent lease.
        return valid(lease);
    }

    public boolean claim(String source, String activity, long shownElapsed, RuntimeSnapshot policy,
                         BooleanSupplier notRevoked, BooleanSupplier fresh) {
        ExpectedClose lease = expected.get();
        if (lease == null) return false;
        if (notRevoked == null || fresh == null || !valid(lease, policy, notRevoked)) {
            discard(lease);
            return false;
        }
        if (!lease.target.source.equals(source) || !lease.target.activity.equals(activity) || lease.shown != shownElapsed)
            return false;
        // WMS and the fresh policy read may cancel the lease; neither holds the retention monitor.
        try {
            if (!sameSource(lease) || !fresh.getAsBoolean() || !valid(lease, policy, notRevoked)) {
                discard(lease);
                return false;
            }
        } catch (RuntimeException unavailable) {
            discard(lease);
            return false;
        }
        synchronized (leaseLock) {
            if (cancellation.get() != lease.cancellation || !expected.compareAndSet(lease, null)) return false;
        }
        try { return fresh.getAsBoolean() && valid(lease, policy, notRevoked); }
        catch (RuntimeException unavailable) { return false; }
    }

    public void cancel() {
        synchronized (leaseLock) {
            cancellation.incrementAndGet();
            expected.set(null);
        }
    }

    private void discard(ExpectedClose lease) {
        synchronized (leaseLock) {
            expected.compareAndSet(lease, null);
        }
    }

    private boolean valid(ExpectedClose lease) {
        try {
            long age = SystemClock.elapsedRealtime() - lease.armedAt;
            if (age < 0 || age >= MAX_AGE || cancellation.get() != lease.cancellation) return false;
            RuntimeSnapshot config = configuration.get();
            return config != null && config.usable() && config.enabled && config.recognition
                    && config.user == lease.target.user && lease.target.uid / 100000 == config.user
                    && lease.identity.equals(config.identity) && config.sourceScope.allows(lease.target.source)
                    && touchValidated.getAsBoolean() && inputGeneration.getAsLong() == lease.input
                    && cancellation.get() == lease.cancellation;
        } catch (RuntimeException unavailable) { return false; }
    }

    private boolean valid(ExpectedClose lease, RuntimeSnapshot config, BooleanSupplier notRevoked) {
        try {
            long age = SystemClock.elapsedRealtime() - lease.armedAt;
            if (age < 0 || age >= MAX_AGE || cancellation.get() != lease.cancellation) return false;
            return config != null && config.usable() && config.enabled && config.recognition
                    && config.user == lease.target.user && lease.target.uid / 100000 == config.user
                    && lease.identity.equals(config.identity) && config.sourceScope.allows(lease.target.source)
                    && touchValidated.getAsBoolean() && inputGeneration.getAsLong() == lease.input
                    && notRevoked.getAsBoolean() && cancellation.get() == lease.cancellation;
        } catch (RuntimeException unavailable) { return false; }
    }

    private boolean sameSource(ExpectedClose lease) {
        try { return sourceCheck.valid(lease.target); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }
}
