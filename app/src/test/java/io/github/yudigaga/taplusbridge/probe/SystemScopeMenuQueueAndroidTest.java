package io.github.yudigaga.taplusbridge.probe;

import android.content.ComponentName;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeMenuQueueAndroidTest {
    private SystemMenuQueueFixture f;

    @Before public void setup() throws Exception { f = new SystemMenuQueueFixture(); f.begin(); }
    @After public void close() throws Exception { if (f != null) f.close(); }

    @Test public void aCompletelyStationaryHeldTouchWaitsForItsValidatedRelease() {
        var callback = f.showMenu(101);
        f.advanceTo(199); f.drainWorker(); assertEquals(0, callback.calls);
        f.advanceTo(200); f.drainWorker(); assertEquals(0, callback.calls);
        f.advanceTo(2_000); f.drainWorker(); assertEquals(0, callback.calls);
        f.queue(MotionEvent.ACTION_UP, 2_000); f.drainInput();
        assertEquals("The final pointer worker must validate the release before dismissal", 0, callback.calls);
        f.drainWorker(); assertEquals(1, callback.calls);
        f.advanceTo(3_000); f.drainWorker(); assertEquals(1, callback.calls);
    }

    @Test public void anEarlyUpObservedByTheHookBeforeTheDeadlineCannotCloseLater() {
        var callback = f.showMenu(101);
        f.advanceTo(199); f.queue(MotionEvent.ACTION_UP, 199); f.drainInput();
        assertTrue(f.physical.released());
        f.advanceTo(201); f.drainWorker();
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls);
        assertFalse(f.visible(f.shownElapsed));
    }

    @Test public void anEarlyUpObservedAt201BeforeTheWorkerRunsStillKeepsItsOriginal199Timestamp() {
        var callback = f.showMenu(101);
        f.advanceTo(199); f.queue(MotionEvent.ACTION_UP, 199);
        assertTrue(f.physical.soleStream(0, 0, f.downUptime));
        f.advanceTo(201); f.drainInput();
        assertEquals(f.beganElapsed + 199, f.physical.releasedElapsedAt());
        f.drainWorker(); f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls);
    }

    @Test public void slowThrowingPhysicalDiagnosticRunsAfterSafetyUpdatesAndCannotDamageDispatch() {
        var callback = f.showMenu(101);
        long input = ((AtomicLong) ReflectionHelpers.getField(f.capture, "inputGeneration")).get();
        int proceededBefore = f.proceededInputs.get();
        AtomicInteger diagnostics = new AtomicInteger();
        f.onLog = message -> {
            if (!message.contains("stage=physical_event ") || !message.contains(" action=1 ")) return;
            diagnostics.incrementAndGet();
            assertTrue("UP must be recorded before diagnostic work starts", f.physical.released());
            assertEquals(f.downUptime + 199, f.physical.releasedAt());
            assertEquals(f.beganElapsed + 199, f.physical.releasedElapsedAt());
            assertEquals("The displaced UP must synchronously revoke the scope input", input,
                    ((Long) ReflectionHelpers.getField(f.capture, "invalidMenuInput")).longValue());
            assertFalse(ReflectionHelpers.<Boolean>callInstanceMethod(f.capture, "menuTouchValidated"));
            assertTrue(message.contains(" e2e_ms=201 "));
            assertTrue(message.endsWith(" input_delivery_ms=2"));
            ShadowSystemClock.advanceBy(Duration.ofMillis(75));
            throw new IllegalStateException("diagnostic-only-failure");
        };
        f.advanceTo(199); f.queueAt(MotionEvent.ACTION_UP, 199, 200, 200);
        f.advanceTo(201); f.drainInput();
        assertEquals(1, diagnostics.get());
        assertEquals("The original input dispatch must still proceed exactly once", proceededBefore + 1, f.proceededInputs.get());
        assertTrue("A diagnostic failure must not make a successfully observed UP uncertain", f.physical.released());
        assertEquals(f.downUptime + 199, f.physical.releasedAt());
        assertEquals(f.beganElapsed + 199, f.physical.releasedElapsedAt());
        assertFalse(f.physical.unavailable());
        f.drainWorker(); f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls); assertFalse(f.visible(f.shownElapsed));
    }

    @Test public void anUpExactlyAtRecognitionRemainsEligibleAfterItsFinalPointerIsValidated() {
        assertEligibleRelease(200);
    }

    @Test public void anUpOneMillisecondAfterRecognitionRemainsEligibleAfterItsFinalPointerIsValidated() {
        assertEligibleRelease(201);
    }

    private void assertEligibleRelease(long originalOffset) {
        var callback = f.showMenu(101);
        f.advanceTo(originalOffset); f.drainWorker();
        assertEquals("Reaching recognition while still held cannot dismiss the menu", 0, callback.calls);
        f.queue(MotionEvent.ACTION_UP, originalOffset); f.drainInput();
        assertEquals(f.beganElapsed + originalOffset, f.physical.releasedElapsedAt());
        assertEquals("Physical UP alone must not dispatch a menu callback", 0, callback.calls);
        f.drainWorker(); assertEquals(1, callback.calls);
        f.advanceTo(2_000); f.drainWorker(); assertEquals(1, callback.calls);
    }

    @Test public void cancelRevokesTheOldLeaseEvenAfterTheOriginalUpArrives() {
        var callback = f.showMenu(101);
        f.advanceTo(199); f.queue(MotionEvent.ACTION_CANCEL, 199); f.drainInput();
        f.advanceTo(201); f.drainWorker();
        f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls); assertFalse(f.visible(f.shownElapsed));
    }

    @Test public void anotherPointerCannotRestoreTheOldLeaseWhenTheStreamBecomesSinglePointerAgain() {
        var callback = f.showMenu(101);
        f.advanceTo(199);
        f.queue(MotionEvent.ACTION_POINTER_DOWN | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT,
                f.downUptime + 199, 0, f.downUptime, 2, InputDevice.SOURCE_TOUCHSCREEN);
        f.drainInput();
        f.advanceTo(201); f.drainWorker();
        f.queue(MotionEvent.ACTION_POINTER_UP | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT,
                f.downUptime + 201, 0, f.downUptime, 2, InputDevice.SOURCE_TOUCHSCREEN);
        f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls); assertFalse(f.visible(f.shownElapsed));
    }

    @Test public void aSecondTouchscreenDeviceRevokesTheOldLeaseAfterBothDevicesRelease() {
        var callback = f.showMenu(101);
        f.advanceTo(199); long secondDown = SystemClock.uptimeMillis();
        f.queue(MotionEvent.ACTION_DOWN, secondDown, 7, secondDown, 1, InputDevice.SOURCE_TOUCHSCREEN);
        f.drainInput();
        f.advanceTo(201); f.drainWorker();
        f.queue(MotionEvent.ACTION_UP, f.downUptime + 201, 7, secondDown, 1, InputDevice.SOURCE_TOUCHSCREEN);
        f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls); assertFalse(f.visible(f.shownElapsed));
    }

    @Test public void aSecondStylusDeviceMustNotReviveTheOldHeldLeaseAfterTheSecondDeviceReleases() {
        var callback = f.showMenu(101);
        f.advanceTo(199); long secondDown = SystemClock.uptimeMillis();
        f.queue(MotionEvent.ACTION_DOWN, secondDown, 7, secondDown, 1, InputDevice.SOURCE_STYLUS);
        f.drainInput();
        assertFalse(f.physical.soleStream(0, 0, f.downUptime));
        f.advanceTo(200); f.drainWorker();
        assertEquals(0, callback.calls);
        f.advanceTo(201);
        f.queue(MotionEvent.ACTION_UP, f.downUptime + 201, 7, secondDown, 1, InputDevice.SOURCE_STYLUS);
        f.drainInput(); f.drainWorker();
        f.advanceTo(301); f.drainWorker();
        assertEquals("A transient second physical device must permanently revoke the old authorization",
                0, callback.calls);
        f.queue(MotionEvent.ACTION_UP, 301); f.drainInput(); f.drainWorker();
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls); assertFalse(f.visible(f.shownElapsed)); assertFalse(f.retain());
    }

    @Test public void aNewDownMakesBothTheOldVisibilityAndTheOldUpUnableToReviveItsLease() {
        var callback = f.showMenu(101);
        f.advanceTo(199); long newDown = SystemClock.uptimeMillis();
        f.queue(MotionEvent.ACTION_DOWN, newDown, 0, newDown, 1, InputDevice.SOURCE_TOUCHSCREEN);
        f.drainInput();
        f.advanceTo(201); f.drainWorker();
        f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls); assertFalse(f.visible(f.shownElapsed));
    }

    @Test public void aWindowChangeDuringTheFirstMenuInspectionPermanentlyRevokesTheOldLease() {
        var callback = f.showMenu(101);
        ComponentName original = f.main.mActivityRecord.mActivityComponent;
        AtomicInteger changes = new AtomicInteger();
        f.menu.beforeBackRead = () -> {
            if (f.menu.backReads != 1) return;
            changes.incrementAndGet();
            f.main.mActivityRecord.mActivityComponent = new ComponentName(f.main.mAttrs.packageName, "com.example.OtherActivity");
        };
        f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
        assertEquals("An authorized release must reach the first real menu inspection", 1, changes.get());
        f.main.mActivityRecord.mActivityComponent = original;
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls); assertFalse(f.visible(f.shownElapsed)); assertFalse(f.retain());
    }

    @Test public void aWindowChangeDuringTheSecondMenuInspectionCannotReachTheCallback() {
        var callback = f.showMenu(101);
        AtomicInteger changes = new AtomicInteger();
        f.menu.beforeBackRead = () -> {
            if (f.menu.backReads != 2) return;
            changes.incrementAndGet();
            f.atms.mWindowManager.mRoot.display.mCurrentFocus = f.main;
        };
        f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
        assertEquals("An authorized release must reach the second real menu inspection", 1, changes.get());
        f.atms.mWindowManager.mRoot.display.mCurrentFocus = f.menu;
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls); assertFalse(f.visible(f.shownElapsed)); assertFalse(f.retain());
    }

    @Test public void realBackAtTheInstalledKeyHookRevokesTheWaitingMenuLease() {
        var callback = f.showMenu(101);
        f.advanceTo(199); f.queueBack(); f.drainInput();
        f.advanceTo(200); f.drainWorker();
        f.advanceTo(201); f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls); assertFalse(f.visible(f.shownElapsed));
    }

    @Test public void realBackAfterModuleDismissalCannotBeClaimedAsTheModuleEcho() {
        var callback = f.showMenu(201);
        assertEquals(0, callback.calls);
        f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        assertEquals(1, callback.calls);
        f.queueBack(); f.drainInput(); f.drainWorker();
        assertFalse(f.retain()); assertFalse(f.retain());
        assertFalse(f.visible(f.shownElapsed));
        assertEquals(1, callback.calls);
    }

    @Test public void realBackObservedDuringTheSecondMenuInspectionWinsBeforeDispatch() {
        var callback = f.showMenu(101);
        AtomicInteger backInputs = new AtomicInteger();
        f.menu.beforeBackRead = () -> {
            if (f.menu.backReads != 2) return;
            backInputs.incrementAndGet();
            f.queueBack(); f.drainInput();
        };
        f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
        assertEquals("The installed Back Hook must run during the second real menu inspection", 1, backInputs.get());
        f.advanceTo(2_000); f.drainWorker();
        assertEquals(0, callback.calls); assertFalse(f.retain());
        assertFalse(f.visible(f.shownElapsed));
    }

    @Test public void aValidatedModuleEchoCanBeConsumedOnlyOnce() {
        var callback = f.showMenu(201);
        assertEquals(0, callback.calls);
        assertFalse("A held touch cannot arm an expected menu-close echo", f.retain());
        f.queue(MotionEvent.ACTION_UP, 201); f.drainInput(); f.drainWorker();
        assertEquals(1, callback.calls);
        assertTrue(f.retain()); assertFalse(f.retain());
    }
}
