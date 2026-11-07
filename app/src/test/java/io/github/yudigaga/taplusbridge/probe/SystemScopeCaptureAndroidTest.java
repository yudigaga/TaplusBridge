package io.github.yudigaga.taplusbridge.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.time.Duration;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeCaptureAndroidTest {
    private ScopeCaptureWindow.Snapshot snapshot;
    private RuntimeSnapshot config;
    private SystemScopeCapture capture;
    private ScopeLinkProbe link;
    private PhysicalTouchState physical;
    private long down, began;

    @Before public void setup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", 0).edit().clear().commit();
        assertTrue(new ConfigStore(context).saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,
                Set.of(), Set.of(), false, true));
        config = RuntimeSnapshot.parse(RuntimeSnapshot.from(context));
        var atms = new ScopeCaptureWindowAndroidTest.Atms();
        var main = new ScopeCaptureWindowAndroidTest.Window(); main.mActivityRecord.main = main;
        atms.mWindowManager.mRoot.display.hit = main;
        atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        var windows = new ScopeCaptureWindow(ScopeCaptureWindowAndroidTest.Atms.class,
                new ScopeCaptureWindow.CaptureBackend() {
                    public Class<?> surfaceType() { return Object.class; }
                    public Bitmap capture(Object layer, Object[] excluded, int uid, Rect crop, float scale) {
                        throw new AssertionError("Link ownership must not capture an image");
                    }
                });
        snapshot = windows.inspect(atms, 0, 50, 200);
        Handler worker = new Handler(Looper.getMainLooper());
        physical = new PhysicalTouchState();
        capture = new SystemScopeCapture(null, worker, () -> null, () -> atms, () -> null, windows, physical);
        link = new ScopeLinkProbe(worker, physical, () -> config, picked -> {},
                (picked, action, at) -> { throw new AssertionError("Host owns the only fallback tap"); }, ignored -> {});
        capture.setLinkProbe(link);
        beginCandidate();
    }

    private void beginCandidate() {
        down = SystemClock.uptimeMillis(); began = SystemClock.elapsedRealtime();
        physical.event(0, 1, down, down, MotionEvent.ACTION_DOWN, down);
        ReflectionHelpers.setField(capture, "origin", snapshot);
        ReflectionHelpers.setField(capture, "configuration", config);
        ReflectionHelpers.setField(capture, "downElapsed", began);
        ReflectionHelpers.setField(capture, "lastOrigin", snapshot);
        ReflectionHelpers.setField(capture, "sourceDown", down);
        ReflectionHelpers.setField(capture, "sourceDevice", 1);
        link.arm(snapshot, config, down, began, 500, 1);
    }

    private boolean claim(long pickedAt) {
        return capture.tryYieldLinkToHost(snapshot.source, snapshot.uid, snapshot.user, pickedAt);
    }

    private void pointer(int action) {
        MotionEvent event = touch(action, snapshot.x, snapshot.y);
        try { capture.pointer(event, 0, true); } finally { event.recycle(); }
        ShadowLooper.idleMainLooper();
    }

    private MotionEvent touch(int action,float x,float y) {
        MotionEvent.PointerProperties property=new MotionEvent.PointerProperties();property.id=0;
        property.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();point.x=x;point.y=y;point.pressure=1;
        return MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{property},
                new MotionEvent.PointerCoords[]{point},0,0,1,1,1,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
    }

    @Test public void handoffInvalidatesPendingScopeDeliveryAndRejectsRepeatedArm() {
        long generation = ReflectionHelpers.getField(capture, "generation");
        assertTrue(claim(SystemClock.elapsedRealtime()));
        assertTrue((long) ReflectionHelpers.getField(capture, "generation") > generation);
        assertFalse(link.ownsNative(snapshot.source, began));
        assertFalse(claim(SystemClock.elapsedRealtime()));
    }

    @Test public void sourceMovementRevokesNativeFallbackEvenIfTheWindowAndOriginalPointStillExist() {
        MotionEvent move=touch(MotionEvent.ACTION_MOVE,snapshot.x+100,snapshot.y);
        try {capture.pointer(move,0,true);}finally{move.recycle();}
        ShadowLooper.idleMainLooper();
        assertFalse("same Activity does not prove the same card after a scroll",claim(SystemClock.elapsedRealtime()));
        pointer(MotionEvent.ACTION_MOVE);
        assertFalse("returning to the original point cannot restore moved content",claim(SystemClock.elapsedRealtime()));
    }

    @Test public void movementAfterYieldToNativeStillRevokesTheWaitingSourceClick() {
        assertTrue(claim(SystemClock.elapsedRealtime()));
        MotionEvent move=touch(MotionEvent.ACTION_MOVE,snapshot.x+100,snapshot.y);
        try {capture.pointer(move,0,true);}finally{move.recycle();}
        ShadowLooper.idleMainLooper();
        assertFalse("yielding the probe must not erase subsequent source movement evidence",
                capture.sourcePointStable(snapshot.source,SystemClock.elapsedRealtime()));
    }

    @Test public void differentNativePickTimestampsCannotStartAnotherHostProbeInTheSameGesture() {
        assertTrue(claim(SystemClock.elapsedRealtime()));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        assertFalse(claim(SystemClock.elapsedRealtime()));
    }

    @Test public void releaseAndScopeCancellationDoNotReleaseHostOwnership() {
        assertTrue(claim(SystemClock.elapsedRealtime()));
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        physical.event(0, 1, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, SystemClock.uptimeMillis());
        physical.markReleasedElapsed(SystemClock.elapsedRealtime());
        pointer(MotionEvent.ACTION_UP);
        assertFalse(claim(SystemClock.elapsedRealtime()));
    }

    @Test public void hostOwnershipOutlivesScopeCandidateTimeoutButExpiresAfterSnapshotLifetime() {
        assertTrue(claim(SystemClock.elapsedRealtime()));
        ShadowSystemClock.advanceBy(Duration.ofMillis(20_001));
        ShadowLooper.idleMainLooper();
        assertFalse(claim(SystemClock.elapsedRealtime()));
        ShadowSystemClock.advanceBy(Duration.ofMillis(10_000));
        assertTrue(claim(SystemClock.elapsedRealtime()));
    }

    @Test public void aNewPhysicalDownRejectsOldPicksEvenWhenScopeCannotStart() {
        long previousPick = SystemClock.elapsedRealtime();
        assertTrue(claim(previousPick));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        down = SystemClock.uptimeMillis(); pointer(MotionEvent.ACTION_DOWN);
        assertFalse(claim(previousPick));
        assertTrue(claim(SystemClock.elapsedRealtime()));
    }

    @Test public void aNewCandidateAllowsOneHostProbeButRejectsThePreviousGesturesPick() {
        long previousPick = SystemClock.elapsedRealtime();
        assertTrue(claim(previousPick));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        down = SystemClock.uptimeMillis(); pointer(MotionEvent.ACTION_DOWN);
        beginCandidate();
        assertFalse(claim(previousPick));
        assertTrue(claim(SystemClock.elapsedRealtime()));
        assertFalse(claim(SystemClock.elapsedRealtime()));
    }

    @Test public void anotherSourceDoesNotConsumeTheCurrentSourcesHandoff() {
        assertTrue(capture.tryYieldLinkToHost("com.example.other", 22002, 0, SystemClock.elapsedRealtime()));
        assertTrue(link.ownsNative(snapshot.source, began));
        assertTrue(claim(SystemClock.elapsedRealtime()));
    }

    @Test public void invalidFuturePickDoesNotConsumeTheCurrentHandoff() {
        assertFalse(claim(SystemClock.elapsedRealtime() + 1));
        assertTrue(link.ownsNative(snapshot.source, began));
        assertTrue(claim(SystemClock.elapsedRealtime()));
    }
}
