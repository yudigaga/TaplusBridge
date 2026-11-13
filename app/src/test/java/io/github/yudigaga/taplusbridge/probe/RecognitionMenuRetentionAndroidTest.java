package io.github.yudigaga.taplusbridge.probe;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** A single expected menu-close event, independent of the completed dismissal controller. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RecognitionMenuRetentionAndroidTest {
    private final AtomicLong input = new AtomicLong(1);
    private final AtomicBoolean touchValidated = new AtomicBoolean(true);
    private final AtomicBoolean sameSourceActivity = new AtomicBoolean(true);
    private final AtomicReference<RuntimeSnapshot> configuration = new AtomicReference<>();
    private ConfigStore settings;
    private ScopeCaptureWindow.Snapshot target;
    private Class<?> type;
    private Object retention;
    private long shown;
    private Consumer<ScopeCaptureWindow.Snapshot> duringSourceCheck = ignored -> {};
    private Runnable duringConfigurationRead = () -> {};

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
        try {
            type = Class.forName("io.github.yudigaga.taplusbridge.probe.RecognitionMenuRetention");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("An exact menu close needs a bounded, one-use recognition retention lease", missing);
        }
        Class<?> sourceCheckType = Class.forName(type.getName() + "$SourceCheck");
        Object sourceCheck = Proxy.newProxyInstance(sourceCheckType.getClassLoader(), new Class<?>[]{sourceCheckType},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return null;
                    ScopeCaptureWindow.Snapshot checked = (ScopeCaptureWindow.Snapshot) arguments[0];
                    assertSame("The foreground check must use the exact DOWN snapshot", target, checked);
                    duringSourceCheck.accept(checked);
                    return sameSourceActivity.get();
                });
        var constructor = type.getDeclaredConstructor(LongSupplier.class, Supplier.class,
                BooleanSupplier.class, sourceCheckType);
        constructor.setAccessible(true);
        retention = constructor.newInstance((LongSupplier) input::get,
                (Supplier<RuntimeSnapshot>) () -> {
                    duringConfigurationRead.run();
                    return configuration.get();
                }, (BooleanSupplier) touchValidated::get, sourceCheck);
        shown = SystemClock.elapsedRealtime();
    }

    private void publish() {
        configuration.set(RuntimeSnapshot.parse(RuntimeSnapshot.from(RuntimeEnvironment.getApplication())));
    }

    private Object call(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        try {
            var method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(retention, arguments);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            if (wrapped.getCause() instanceof Error failure) throw failure;
            throw wrapped;
        }
    }

    private boolean arm() throws Exception { return arm(target, configuration.get().identity, input.get(), shown, true); }

    private boolean arm(ScopeCaptureWindow.Snapshot snapshot, String identity, long sequence,
                        long shownElapsed, boolean navigationReady) throws Exception {
        return (Boolean) call("arm", new Class<?>[]{ScopeCaptureWindow.Snapshot.class, String.class,
                long.class, long.class, boolean.class}, snapshot, identity, sequence, shownElapsed, navigationReady);
    }

    private boolean claim() throws Exception { return claim(target.source, target.activity, shown); }

    private boolean claim(String source, String activity, long shownElapsed) throws Exception {
        return (Boolean) call("claim", new Class<?>[]{String.class, String.class, long.class}, source, activity, shownElapsed);
    }

    private void cancel() throws Exception { call("cancel", new Class<?>[0]); }

    @Test public void aMatchingExpectedCloseCanRetainTheSameRecognitionSessionExactlyOnce() throws Exception {
        assertTrue(arm());
        assertTrue(claim());
        assertFalse(claim());
    }

    @Test public void noDispatchAndAnUnavailableNavigationGuardCannotAuthorizeRetention() throws Exception {
        assertFalse(claim());
        assertFalse(arm(target, configuration.get().identity, input.get(), shown, false));
        assertFalse(claim());
    }

    @Test public void mismatchedSourceActivityOrShowDoesNotSpendTheCorrectLease() throws Exception {
        assertTrue(arm());
        assertFalse(claim("com.example.other", target.activity, shown));
        assertFalse(claim(target.source, "com.example.OtherActivity", shown));
        assertFalse(claim(target.source, target.activity, shown + 1));
        assertFalse(claim(null, target.activity, shown));
        assertTrue(claim());
    }

    @Test public void expirationIsMeasuredFromDispatchAndStopsAt250Milliseconds() throws Exception {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        assertTrue("The recognition card may have appeared before menu dispatch", arm());
        ShadowSystemClock.advanceBy(Duration.ofMillis(249));
        assertTrue(claim());
        assertTrue(arm());
        ShadowSystemClock.advanceBy(Duration.ofMillis(250));
        assertFalse(claim());
    }

    @Test public void navigationCancellationPreservesARealBackEvent() throws Exception {
        assertTrue(arm());
        cancel();
        assertFalse("A real Back must reach the host's ordinary quit path", claim());
    }

    @Test public void aNewPhysicalDownRevokesTheExpectedClose() throws Exception {
        assertTrue(arm());
        input.incrementAndGet();
        assertFalse(claim());
    }

    @Test public void movementRevokesTheLeaseEvenIfTouchLaterBecomesStableAgain() throws Exception {
        assertTrue(arm());
        touchValidated.set(false);
        assertFalse(claim());
        touchValidated.set(true);
        assertFalse(claim());
    }

    @Test public void changingTheConfigurationPermanentlyRevokesTheExistingLease() throws Exception {
        RuntimeSnapshot original = configuration.get();
        assertTrue(arm());
        settings.setCaptureEnabled(false);
        publish();
        assertFalse(claim());
        configuration.set(original);
        assertFalse(claim());
    }

    @Test public void switchingAwayFromTheOriginalActivityRevokesTheLease() throws Exception {
        assertTrue(arm());
        sameSourceActivity.set(false);
        assertFalse(claim());
        sameSourceActivity.set(true);
        assertFalse(claim());
    }

    @Test public void missingConfigurationAndDisabledRecognitionCannotArmALease() throws Exception {
        RuntimeSnapshot original = configuration.get();
        configuration.set(null);
        assertFalse(arm(target, original.identity, input.get(), shown, true));
        settings.setRecognitionEnabled(false);
        publish();
        assertFalse(arm());
        settings.setRecognitionEnabled(true);
        settings.setEnabled(false);
        publish();
        assertFalse(arm());
    }

    @Test public void aDifferentBuildOrDisallowedSourceCannotArmALease() throws Exception {
        JSONObject staleBuild = new JSONObject(configuration.get().encode());
        staleBuild.put("build", 1);
        configuration.set(RuntimeSnapshot.parse(staleBuild.toString()));
        assertFalse(arm());
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(target.source), List.of(), false, false));
        publish();
        assertFalse(arm());
    }

    @Test public void staleIdentityInputAndMalformedShownTimeCannotArmALease() throws Exception {
        assertFalse(arm(target, "different", input.get(), shown, true));
        assertFalse(arm(target, configuration.get().identity, input.get() - 1, shown, true));
        assertFalse(arm(target, configuration.get().identity, input.get(), -1, true));
        assertFalse(arm(target, configuration.get().identity, input.get(), SystemClock.elapsedRealtime() + 1, true));
        assertFalse(arm(null, configuration.get().identity, input.get(), shown, true));
    }

    @Test public void inputChangingDuringTheForegroundCheckCannotConsumeTheLease() throws Exception {
        assertTrue(arm());
        duringSourceCheck = ignored -> input.incrementAndGet();
        assertFalse(claim());
    }

    @Test public void configurationChangingDuringTheForegroundCheckCannotConsumeTheLease() throws Exception {
        assertTrue(arm());
        duringSourceCheck = ignored -> configuration.set(null);
        assertFalse(claim());
    }

    @Test public void inputChangingDuringTheFinalConfigurationReadCannotRetainOrRestoreTheLease() throws Exception {
        assertTrue(arm());
        long originalInput = input.get();
        AtomicInteger reads = new AtomicInteger();
        duringConfigurationRead = () -> {
            if (reads.incrementAndGet() == 2) input.incrementAndGet();
        };
        assertFalse("Input may change after valid checked it but before the consume CAS", claim());
        duringConfigurationRead = () -> {};
        input.set(originalInput);
        assertFalse("A failed post-consume check must never restore the permission", claim());
    }

    @Test public void navigationCancellationDuringTheForegroundCheckWinsOverClaim() throws Exception {
        assertTrue(arm());
        duringSourceCheck = ignored -> {
            try { cancel(); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        assertFalse(claim());
    }

    @Test public void foregroundChecksRunWithoutAnInternalLockAndConcurrentClaimsHaveOneWinner() throws Exception {
        assertTrue(arm());
        CountDownLatch entered = new CountDownLatch(2), unblock = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger retained = new AtomicInteger();
        duringSourceCheck = ignored -> {
            entered.countDown();
            try { assertTrue(unblock.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        };
        Runnable request = () -> {
            try { if (claim()) retained.incrementAndGet(); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        };
        Thread first = new Thread(request), second = new Thread(request);
        first.setDaemon(true); second.setDaemon(true);
        try {
            first.start(); second.start();
            assertTrue("Both external WMS checks must be able to run without a retained monitor", entered.await(2, TimeUnit.SECONDS));
        } finally {
            unblock.countDown(); first.join(2_000); second.join(2_000);
        }
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(1, retained.get());
    }

    @Test public void theLeaseRemainsClaimableAfterTheControllerClearsItsSentCandidate() throws Exception {
        AtomicReference<RecognitionMenuController> controller = new AtomicReference<>();
        controller.set(new RecognitionMenuController(new Handler(Looper.getMainLooper()), input::get,
                configuration::get, touchValidated::get, (snapshot, current) -> {
                    try {
                        long shownAt = (Long) RecognitionMenuController.class.getMethod("shownElapsed", ScopeCaptureWindow.Snapshot.class)
                                .invoke(controller.get(), snapshot);
                        assertTrue(arm(snapshot, configuration.get().identity, input.get(), shownAt, true));
                    } catch (ReflectiveOperationException reflection) { throw reflection; }
                    catch (Exception error) { throw new AssertionError(error); }
                    return "sent";
                }, ignored -> {}));
        long now = SystemClock.elapsedRealtime();
        controller.get().begin(target, configuration.get(), now, now, input.get());
        ShadowLooper.idleMainLooper();
        assertTrue(controller.get().shown(target.source, shown));
        ShadowLooper.idleMainLooper();
        assertEquals(-1L, (long) (Long) RecognitionMenuController.class.getMethod("shownElapsed", ScopeCaptureWindow.Snapshot.class)
                .invoke(controller.get(), target));
        assertTrue("The expected close must outlive controller finish(sent)", claim());
    }
}
