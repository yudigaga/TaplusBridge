package io.github.yudigaga.taplusbridge.probe;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Explicit operation policy keeps repeated lease checks in memory, with fresh checks across consume. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RecognitionMenuRetentionPolicyAndroidTest {
    private final AtomicLong input = new AtomicLong(1);
    private final AtomicLong observedPolicyEpoch = new AtomicLong(1);
    private final AtomicBoolean touchValidated = new AtomicBoolean(true);
    private final AtomicBoolean sameSource = new AtomicBoolean(true);
    private final AtomicReference<RuntimeSnapshot> actualConfiguration = new AtomicReference<>();
    private final AtomicInteger legacyReads = new AtomicInteger();
    private final AtomicInteger freshReads = new AtomicInteger();
    private final AtomicInteger freshCalls = new AtomicInteger();
    private final AtomicInteger sourceChecks = new AtomicInteger();
    private ConfigStore settings;
    private ScopeCaptureWindow.Snapshot target;
    private RecognitionMenuRetention retention;
    private RuntimeSnapshot policy;
    private long shown;
    private BooleanSupplier notRevoked;
    private BooleanSupplier fresh;
    private Runnable duringSourceCheck = () -> {};
    private Runnable duringTouchCheck = () -> {};
    private final Supplier<RuntimeSnapshot> latest = () -> {
        freshReads.incrementAndGet();
        return actualConfiguration.get();
    };

    @Before public void setup() throws Exception {
        var application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("config", 0).edit().clear().commit();
        settings = new ConfigStore(application);
        settings.setEnabled(true);
        settings.setRecognitionEnabled(true);
        publish();
        var atms = new ScopeCaptureWindowAndroidTest.Atms();
        var main = new ScopeCaptureWindowAndroidTest.Window();
        main.mActivityRecord.main = main;
        atms.mWindowManager.mRoot.display.hit = main;
        atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        var windows = new ScopeCaptureWindow(ScopeCaptureWindowAndroidTest.Atms.class,
                new ScopeCaptureWindow.CaptureBackend() {
                    public Class<?> surfaceType() { return Object.class; }
                    public Bitmap capture(Object layer, Object[] excluded, int uid, Rect crop, float scale) {
                        throw new AssertionError("Retention must not capture pixels");
                    }
                });
        target = windows.inspect(atms, 0, 50, 200);
        retention = new RecognitionMenuRetention(input::get, () -> {
            legacyReads.incrementAndGet();
            return actualConfiguration.get();
        }, () -> {
            duringTouchCheck.run();
            return touchValidated.get();
        }, checked -> {
            assertSame(target, checked);
            sourceChecks.incrementAndGet();
            duringSourceCheck.run();
            return sameSource.get();
        });
        shown = SystemClock.elapsedRealtime();
    }

    private void publish() {
        actualConfiguration.set(RuntimeSnapshot.parse(RuntimeSnapshot.from(RuntimeEnvironment.getApplication())));
    }

    private void arm() {
        policy = actualConfiguration.get();
        long openedPolicyEpoch = observedPolicyEpoch.get();
        notRevoked = () -> observedPolicyEpoch.get() == openedPolicyEpoch;
        fresh = this::refresh;
        assertTrue(retention.arm(target, policy.identity, input.get(), shown, true));
        legacyReads.set(0);
        freshReads.set(0);
        freshCalls.set(0);
        sourceChecks.set(0);
    }

    private boolean refresh() {
        freshCalls.incrementAndGet();
        RuntimeSnapshot current = latest.get();
        return current != null && current.usable() && policy.identity.equals(current.identity)
                && notRevoked.getAsBoolean();
    }

    private boolean claim() {
        return claim(target.source, target.activity, shown);
    }

    private boolean claim(String source, String activity, long shownElapsed) {
        return retention.claim(source, activity, shownElapsed, policy, notRevoked, fresh);
    }

    @Test public void stablePolicyUsesNoLegacyReadAndTwoFreshReadsAcrossOneConsume() {
        arm();
        assertTrue(claim());
        assertEquals(0, legacyReads.get());
        assertEquals(2, freshCalls.get());
        assertEquals(2, freshReads.get());
        assertFalse(claim());
        assertEquals(2, freshCalls.get());
        assertEquals(2, freshReads.get());
    }

    @Test public void anActualConfigurationChangeDuringWmsIsRejectedByTheFreshSupplierRead() {
        arm();
        RuntimeSnapshot original = policy;
        duringSourceCheck = () -> {
            settings.setCaptureEnabled(false);
            publish();
        };
        assertFalse(claim());
        assertEquals(0, legacyReads.get());
        assertEquals(1, freshCalls.get());
        assertEquals(1, freshReads.get());
        actualConfiguration.set(original);
        duringSourceCheck = () -> {};
        assertFalse("Restoring the original raw configuration cannot restore the discarded lease", claim());
    }

    @Test public void anObservedDifferentPolicyAndReturnToTheSameRawCannotReuseTheOldEpoch() {
        arm();
        RuntimeSnapshot original = policy;
        duringSourceCheck = () -> {
            settings.setCaptureEnabled(false);
            publish();
            assertNotEquals(original.identity, latest.get().identity);
            observedPolicyEpoch.incrementAndGet();
            actualConfiguration.set(original);
        };
        assertFalse(claim());
        assertSame(original, actualConfiguration.get());
        assertEquals(1, freshCalls.get());
        duringSourceCheck = () -> {};
        assertFalse(claim());
    }

    @Test public void anUnavailableFreshReadDiscardsTheLeaseEvenIfTheSamePolicyReturns() {
        arm();
        RuntimeSnapshot original = policy;
        actualConfiguration.set(null);
        assertFalse(claim());
        assertEquals(1, freshReads.get());
        actualConfiguration.set(original);
        assertFalse(claim());
        assertEquals(1, freshReads.get());
    }

    @Test public void aPolicyChangeAfterTheFirstFreshReadIsObservedAfterConsumeAndCannotRevive() {
        arm();
        RuntimeSnapshot original = policy;
        fresh = () -> {
            boolean current = refresh();
            if (freshCalls.get() == 1) {
                settings.setCaptureEnabled(false);
                publish();
            }
            return current;
        };
        assertFalse(claim());
        assertNotEquals(original.identity, actualConfiguration.get().identity);
        assertEquals(2, freshCalls.get());
        assertEquals(2, freshReads.get());
        assertEquals(0, legacyReads.get());
        actualConfiguration.set(original);
        fresh = this::refresh;
        assertFalse(claim());
        assertEquals(2, freshReads.get());
    }

    @Test public void thePostConsumeFreshReadObservesAConfigurationChangeDuringThatCallback() {
        arm();
        RuntimeSnapshot original = policy;
        AtomicInteger refreshRequests = new AtomicInteger();
        fresh = () -> {
            if (refreshRequests.incrementAndGet() == 2) {
                settings.setRecognitionEnabled(false);
                publish();
            }
            return refresh();
        };
        assertFalse(claim());
        assertNotEquals(original.identity, actualConfiguration.get().identity);
        assertEquals(2, freshCalls.get());
        assertEquals(2, freshReads.get());
        actualConfiguration.set(original);
        fresh = this::refresh;
        assertFalse(claim());
        assertEquals(2, freshReads.get());
    }

    @Test public void aThrowingFreshCheckFailsClosedAndCannotRestoreTheLease() {
        arm();
        fresh = () -> { throw new IllegalStateException("configuration unavailable"); };
        assertFalse(claim());
        fresh = this::refresh;
        assertFalse(claim());
        assertEquals(0, freshReads.get());
    }

    @Test public void aMissingFreshCheckDiscardsTheLease() {
        arm();
        fresh = null;
        assertFalse(claim());
        fresh = this::refresh;
        assertFalse(claim());
        assertEquals(0, sourceChecks.get());
    }

    @Test public void aMissingCurrentCheckDiscardsTheLease() {
        arm();
        notRevoked = null;
        assertFalse(claim());
        notRevoked = () -> true;
        assertFalse(claim());
        assertEquals(0, freshReads.get());
    }

    @Test public void aThrowingCurrentCheckDiscardsTheLease() {
        arm();
        notRevoked = () -> { throw new IllegalStateException("operation closed"); };
        assertFalse(claim());
        notRevoked = () -> true;
        assertFalse(claim());
        assertEquals(0, freshReads.get());
    }

    @Test public void aMissingOrDifferentExplicitPolicyDiscardsWithoutCallingWmsOrFresh() {
        arm();
        RuntimeSnapshot original = policy;
        policy = null;
        assertFalse(claim());
        policy = original;
        assertFalse(claim());
        arm();
        settings.setRecognitionEnabled(false);
        publish();
        policy = actualConfiguration.get();
        assertFalse(claim());
        policy = original;
        assertFalse(claim());
        assertEquals(0, sourceChecks.get());
        assertEquals(0, freshReads.get());
    }

    @Test public void wrongSourceActivityOrShownDoesNotSpendACurrentLease() {
        arm();
        assertFalse(claim("com.example.other", target.activity, shown));
        assertFalse(claim(target.source, "com.example.OtherActivity", shown));
        assertFalse(claim(target.source, target.activity, shown + 1));
        assertFalse(claim(null, target.activity, shown));
        assertEquals(0, sourceChecks.get());
        assertEquals(0, freshReads.get());
        assertTrue(claim());
        assertEquals(2, freshReads.get());
    }

    @Test public void aDifferentForegroundActivityDiscardsBeforeFreshAndCannotRevive() {
        arm();
        sameSource.set(false);
        assertFalse(claim());
        sameSource.set(true);
        assertFalse(claim());
        assertEquals(0, freshReads.get());
    }

    @Test public void inputChangingDuringWmsPreventsTheConsumeAndCannotRevive() {
        arm();
        long originalInput = input.get();
        duringSourceCheck = input::incrementAndGet;
        assertFalse(claim());
        input.set(originalInput);
        duringSourceCheck = () -> {};
        assertFalse(claim());
    }

    @Test public void cancellationDuringWmsPreventsTheConsume() {
        arm();
        duringSourceCheck = retention::cancel;
        assertFalse(claim());
        duringSourceCheck = () -> {};
        assertFalse(claim());
    }

    @Test public void inputChangingAfterTheFreshReadCannotConsumeOrRestoreTheLease() {
        arm();
        long originalInput = input.get();
        fresh = () -> {
            boolean current = refresh();
            input.incrementAndGet();
            return current;
        };
        assertFalse(claim());
        assertEquals(1, freshReads.get());
        input.set(originalInput);
        fresh = this::refresh;
        assertFalse(claim());
    }

    @Test public void cancellationAfterTheFreshReadCannotConsumeTheLease() {
        arm();
        fresh = () -> {
            boolean current = refresh();
            retention.cancel();
            return current;
        };
        assertFalse(claim());
        assertEquals(1, freshReads.get());
        fresh = this::refresh;
        assertFalse(claim());
    }

    @Test public void inputChangingDuringThePostConsumeMemoryCheckCannotRetainOrRestore() {
        arm();
        long originalInput = input.get();
        AtomicInteger checks = new AtomicInteger();
        duringTouchCheck = () -> {
            if (checks.incrementAndGet() == 3) input.incrementAndGet();
        };
        assertFalse(claim());
        assertEquals(3, checks.get());
        input.set(originalInput);
        duringTouchCheck = () -> {};
        assertFalse(claim());
    }

    @Test public void cancellationDuringThePostConsumeMemoryCheckCannotRetain() {
        arm();
        AtomicInteger checks = new AtomicInteger();
        duringTouchCheck = () -> {
            if (checks.incrementAndGet() == 3) retention.cancel();
        };
        assertFalse(claim());
        assertEquals(3, checks.get());
        duringTouchCheck = () -> {};
        assertFalse(claim());
    }

    @Test public void policyEpochChangingDuringThePostConsumeMemoryCheckCannotRetain() {
        arm();
        AtomicInteger checks = new AtomicInteger();
        duringTouchCheck = () -> {
            if (checks.incrementAndGet() == 3) observedPolicyEpoch.incrementAndGet();
        };
        assertFalse(claim());
        assertEquals(3, checks.get());
        duringTouchCheck = () -> {};
        assertFalse(claim());
    }

    @Test public void theLeaseAllows249MillisecondsAndExpiresAt250Milliseconds() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        arm();
        ShadowSystemClock.advanceBy(Duration.ofMillis(249));
        assertTrue(claim());
        arm();
        ShadowSystemClock.advanceBy(Duration.ofMillis(250));
        assertFalse(claim());
        assertEquals(0, freshReads.get());
    }

    @Test public void aWmsCheckThatCrosses250MillisecondsCannotRetain() {
        arm();
        duringSourceCheck = () -> ShadowSystemClock.advanceBy(Duration.ofMillis(250));
        assertFalse(claim());
        assertEquals(1, freshReads.get());
        duringSourceCheck = () -> {};
        assertFalse(claim());
    }

    @Test public void concurrentWmsAndFreshCallbacksHoldNoLeaseMonitorAndHaveOneWinner() throws Exception {
        arm();
        CountDownLatch sourceEntered = new CountDownLatch(2), freshEntered = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger retained = new AtomicInteger();
        duringSourceCheck = () -> {
            sourceEntered.countDown();
            await(sourceEntered);
        };
        fresh = () -> {
            freshEntered.countDown();
            await(freshEntered);
            return refresh();
        };
        Runnable request = () -> {
            try { if (claim()) retained.incrementAndGet(); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        };
        Thread first = new Thread(request), second = new Thread(request);
        first.setDaemon(true);
        second.setDaemon(true);
        try {
            first.start();
            second.start();
            assertTrue("Both WMS callbacks must enter without a retained lease monitor", sourceEntered.await(2, TimeUnit.SECONDS));
            assertTrue("Both fresh callbacks must enter before a lease is consumed", freshEntered.await(2, TimeUnit.SECONDS));
        } finally {
            first.join(4_000);
            second.join(4_000);
        }
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(1, retained.get());
        assertEquals(3, freshCalls.get());
        assertEquals(3, freshReads.get());
        assertEquals(0, legacyReads.get());
    }

    private static void await(CountDownLatch entered) {
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
