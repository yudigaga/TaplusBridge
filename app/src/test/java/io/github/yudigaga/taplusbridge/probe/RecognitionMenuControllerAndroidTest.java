package io.github.yudigaga.taplusbridge.probe;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Controller timing tests; WMS dismissal is replaced by an observable, guarded boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RecognitionMenuControllerAndroidTest {
    private static final String SOURCE = "com.android.chrome";
    private final AtomicLong input = new AtomicLong(1);
    private final AtomicBoolean touchValidated = new AtomicBoolean();
    private final AtomicReference<RuntimeSnapshot> configuration = new AtomicReference<>();
    private final List<ScopeCaptureWindow.Snapshot> attempts = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private ConfigStore settings;
    private ScopeCaptureWindow.Snapshot target;
    private Class<?> type;
    private Object controller;
    private long began, readyAt;
    private String outcome = "sent";
    private Consumer<BooleanSupplier> duringClose = ignored -> {};

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
                    @Override public Class<?> surfaceType() { return Object.class; }
                    @Override public Bitmap capture(Object layer, Object[] excluded, int uid, Rect crop, float scale) {
                        throw new AssertionError("Menu coordination must not capture pixels");
                    }
                });
        target = windows.inspect(atms, 0, 50, 200);
        try {
            type = Class.forName("io.github.yudigaga.taplusbridge.probe.RecognitionMenuController");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Recognition UI must coordinate same-gesture source-menu dismissal", missing);
        }
        Class<?> closerType = Class.forName(type.getName() + "$Closer");
        Object closer = Proxy.newProxyInstance(closerType.getClassLoader(), new Class<?>[]{closerType},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return null;
                    attempts.add((ScopeCaptureWindow.Snapshot) arguments[0]);
                    BooleanSupplier current = (BooleanSupplier) arguments[1];
                    assertTrue("Dismissal boundary starts with current, validated touch", current.getAsBoolean());
                    duringClose.accept(current);
                    return outcome;
                });
        var constructor = type.getDeclaredConstructor(Handler.class, LongSupplier.class, Supplier.class,
                BooleanSupplier.class, closerType, Consumer.class);
        constructor.setAccessible(true);
        controller = constructor.newInstance(new Handler(Looper.getMainLooper()),
                (LongSupplier) input::get, (Supplier<RuntimeSnapshot>) configuration::get,
                (BooleanSupplier) touchValidated::get, closer, (Consumer<String>) logs::add);
        begin();
    }

    private void publish() {
        configuration.set(RuntimeSnapshot.parse(RuntimeSnapshot.from(RuntimeEnvironment.getApplication())));
    }

    private Object call(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        try {
            var method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(controller, arguments);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            if (wrapped.getCause() instanceof Error failure) throw failure;
            throw wrapped;
        }
    }

    private void begin() throws Exception {
        began = SystemClock.elapsedRealtime();
        readyAt = began + 500;
        call("begin", new Class<?>[]{ScopeCaptureWindow.Snapshot.class, RuntimeSnapshot.class,
                long.class, long.class, long.class}, target, configuration.get(), began, readyAt, input.get());
        ShadowLooper.idleMainLooper();
    }

    private boolean shown(String source, long at) throws Exception {
        return (Boolean) call("shown", new Class<?>[]{String.class, long.class}, source, at);
    }

    private boolean visible() throws Exception {
        long remaining = readyAt - SystemClock.elapsedRealtime();
        if (remaining > 0) advance(remaining);
        return shown(SOURCE, SystemClock.elapsedRealtime());
    }

    private void validateTouch() throws Exception {
        touchValidated.set(true);
        call("released", new Class<?>[0]);
        ShadowLooper.idleMainLooper();
    }

    private void advance(long millis) {
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(millis));
    }

    @Test public void visibleUiClosesTheOriginalCandidateOnceWhileItsHeldTouchRemainsValidated() throws Exception {
        touchValidated.set(true);
        assertTrue(visible());
        assertTrue("Binder visibility notification must only enqueue the close", attempts.isEmpty());
        ShadowLooper.idleMainLooper();
        assertEquals(1, attempts.size());
        assertSame(target, attempts.get(0));
        advance(5_000);
        assertEquals(1, attempts.size());
    }

    @Test public void validatedTouchStillWaitsForActualVisibleUiAndNeverBlocksItsNotifier() throws Exception {
        validateTouch();
        assertTrue(attempts.isEmpty());
        assertTrue(visible());
        assertTrue("Binder visibility notification must only enqueue the close", attempts.isEmpty());
        ShadowLooper.idleMainLooper();
        assertEquals(1, attempts.size());
    }

    @Test public void anUnvalidatedPhysicalUpCannotCloseBeforeItsFinalPointerEventIsChecked() throws Exception {
        assertTrue(visible());
        ShadowLooper.idleMainLooper();
        touchValidated.set(false);
        advance(2_000);
        assertTrue("Input state can report UP before its final coordinates are validated", attempts.isEmpty());
        validateTouch();
        assertEquals(1, attempts.size());
    }

    @Test public void noVisibilityNotificationNeverClosesAnything() throws Exception {
        validateTouch();
        advance(31_000);
        assertTrue(attempts.isEmpty());
    }

    @Test public void shownTimeIsUnavailableUntilAcceptedAndKeepsTheFirstVisibleNotification() throws Exception {
        assertEquals(-1L, (long) (Long) call("shownElapsed", new Class<?>[]{ScopeCaptureWindow.Snapshot.class}, target));
        assertTrue(visible());
        long firstShown = SystemClock.elapsedRealtime();
        ShadowLooper.idleMainLooper();
        assertEquals(firstShown, (long) (Long) call("shownElapsed", new Class<?>[]{ScopeCaptureWindow.Snapshot.class}, target));
        advance(50);
        assertTrue(shown(SOURCE, SystemClock.elapsedRealtime()));
        ShadowLooper.idleMainLooper();
        assertEquals(firstShown, (long) (Long) call("shownElapsed", new Class<?>[]{ScopeCaptureWindow.Snapshot.class}, target));
        assertEquals(-1L, (long) (Long) call("shownElapsed", new Class<?>[]{ScopeCaptureWindow.Snapshot.class}, (Object) null));
        validateTouch();
        assertEquals(-1L, (long) (Long) call("shownElapsed", new Class<?>[]{ScopeCaptureWindow.Snapshot.class}, target));
    }

    @Test public void wrongSourceAndPreReadyOrFutureVisibilityCannotAuthorizeClosing() throws Exception {
        validateTouch();
        advance(500);
        assertFalse(shown("com.example.other", SystemClock.elapsedRealtime()));
        assertFalse(shown(SOURCE, began - 1));
        assertFalse(shown(SOURCE, readyAt - 1));
        assertFalse(shown(SOURCE, SystemClock.elapsedRealtime() + 1));
        advance(5_000);
        assertTrue(attempts.isEmpty());
    }

    @Test public void aChangedConfigurationIdentityRejectsVisibility() throws Exception {
        advance(500);
        settings.setCaptureEnabled(false);
        publish();
        assertFalse(shown(SOURCE, SystemClock.elapsedRealtime()));
        validateTouch();
        assertTrue(attempts.isEmpty());
    }

    @Test public void disablingRecognitionWhileWaitingForTouchValidationRevokesAnAcceptedNotification() throws Exception {
        assertTrue(visible());
        ShadowLooper.idleMainLooper();
        settings.setRecognitionEnabled(false);
        publish();
        validateTouch();
        advance(5_000);
        assertTrue(attempts.isEmpty());
    }

    @Test public void aDisabledConfigurationCannotBeginACandidate() throws Exception {
        settings.setEnabled(false);
        publish();
        begin();
        advance(500);
        assertFalse(shown(SOURCE, SystemClock.elapsedRealtime()));
        validateTouch();
        assertTrue(attempts.isEmpty());
    }

    @Test public void aNewPhysicalDownRevokesAnAcceptedNotificationBeforeItsWorkerRuns() throws Exception {
        assertTrue(visible());
        input.incrementAndGet();
        validateTouch();
        assertTrue(attempts.isEmpty());
    }

    @Test public void cancellationFromMoveMultitouchOrCancelRevokesPendingWork() throws Exception {
        assertTrue(visible());
        call("cancel", new Class<?>[0]);
        validateTouch();
        advance(5_000);
        assertTrue(attempts.isEmpty());
        assertFalse(shown(SOURCE, SystemClock.elapsedRealtime()));
    }

    @Test public void aReplacementCandidateDoesNotInheritThePriorVisibilitySignal() throws Exception {
        assertTrue(visible());
        input.incrementAndGet();
        begin();
        validateTouch();
        advance(1_000);
        assertTrue(attempts.isEmpty());
        assertTrue(visible());
        ShadowLooper.idleMainLooper();
        assertEquals(1, attempts.size());
    }

    @Test public void duplicateVisibilityCannotResendATerminalDismissal() throws Exception {
        validateTouch();
        assertTrue(visible());
        ShadowLooper.idleMainLooper();
        shown(SOURCE, SystemClock.elapsedRealtime());
        advance(3_000);
        assertEquals(1, attempts.size());
    }

    @Test public void onlyNoMenuIsRetriedAndTheFirstSentResultIsTerminal() throws Exception {
        outcome = "none";
        validateTouch();
        assertTrue(visible());
        ShadowLooper.idleMainLooper();
        assertEquals(1, attempts.size());
        advance(100);
        assertTrue(attempts.size() >= 2);
        outcome = "sent";
        advance(100);
        int terminalAttempts = attempts.size();
        advance(5_000);
        assertEquals(terminalAttempts, attempts.size());
        assertTrue(terminalAttempts <= 5);
    }

    @Test public void noMenuPollingStopsWithinTheBoundedObservationWindow() throws Exception {
        outcome = "none";
        validateTouch();
        assertTrue(visible());
        ShadowLooper.idleMainLooper();
        advance(2_000);
        int terminalAttempts = attempts.size();
        assertTrue(terminalAttempts >= 2);
        assertTrue("Only a short observation window is allowed", terminalAttempts <= 32);
        advance(30_000);
        assertEquals(terminalAttempts, attempts.size());
    }

    @Test public void unsupportedAmbiguousChangedAndStaleResultsNeverRetry() throws Exception {
        for (String terminal : List.of("unsupported", "ambiguous", "changed", "stale")) {
            outcome = terminal;
            attempts.clear();
            input.incrementAndGet();
            begin();
            validateTouch();
            assertTrue(visible());
            ShadowLooper.idleMainLooper();
            advance(3_000);
            assertEquals(terminal, 1, attempts.size());
        }
    }

    @Test public void callbackPredicateRechecksNewInputInsideTheCloseBoundary() throws Exception {
        duringClose = current -> {
            input.incrementAndGet();
            assertFalse("New physical input must revoke a callback already being inspected", current.getAsBoolean());
            outcome = "stale";
        };
        validateTouch();
        assertTrue(visible());
        ShadowLooper.idleMainLooper();
        advance(3_000);
        assertEquals(1, attempts.size());
    }

    @Test public void aCandidateExpiresWithinThirtySecondsWithoutValidatedTouch() throws Exception {
        assertTrue(visible());
        ShadowLooper.idleMainLooper();
        advance(30_000);
        validateTouch();
        assertTrue(attempts.isEmpty());
        assertFalse(shown(SOURCE, SystemClock.elapsedRealtime()));
    }
}
