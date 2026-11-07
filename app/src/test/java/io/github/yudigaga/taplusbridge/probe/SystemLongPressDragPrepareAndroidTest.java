package io.github.yudigaga.taplusbridge.probe;

import android.app.KeyguardManager;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.hook.ScopeCapturePayload;
import io.github.yudigaga.taplusbridge.runtime.RuntimeServer;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import io.github.yudigaga.taplusbridge.runtime.SystemPackageOps;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
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

/** Exercises system eligibility and metadata transfer; the WMS capture backend is fake. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemLongPressDragPrepareAndroidTest {
    private static final int HOST_UID = 22002;
    private static final String SOURCE = "com.android.chrome";
    private final List<Intent> starts = new ArrayList<>();
    private Context context;
    private ConfigStore settings;
    private SharedPreferences stored;
    private RuntimeServer runtime;
    private SystemLongPressDrag drag;
    private PhysicalTouchState physical;
    private ScopeCaptureWindowAndroidTest.Atms atms;
    private ScopeCaptureWindowAndroidTest.Window main;
    private long down;
    private int captures;
    private boolean missingCapture;
    private RuntimeException captureFailure;
    private Runnable duringCapture = () -> {};

    @Before public void setup() throws Exception {
        Context application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("config", 0).edit().clear().commit();
        stored = application.getSharedPreferences("drag_prepare_test", 0);
        stored.edit().clear().commit();
        settings = new ConfigStore(application);
        settings.setEnabled(true);
        settings.setDragSettings(new DragSettings(true, "portal", "share", "lens", "translate", false, List.of(), 100, 100));
        publish();
        context = new ContextWrapper(application) {
            @Override public ComponentName startService(Intent intent) { starts.add(new Intent(intent)); return intent.getComponent(); }
        };
        ((org.robolectric.shadows.ShadowPowerManager) org.robolectric.shadow.api.Shadow.extract(context.getSystemService(PowerManager.class))).setIsInteractive(true);
        ((org.robolectric.shadows.ShadowKeyguardManager) org.robolectric.shadow.api.Shadow.extract(context.getSystemService(KeyguardManager.class))).setKeyguardLocked(false);
        ((org.robolectric.shadows.ShadowUserManager) org.robolectric.shadow.api.Shadow.extract(context.getSystemService(UserManager.class))).setUserUnlocked(true);
        context.getResources().getDisplayMetrics().widthPixels = 1080;
        context.getResources().getDisplayMetrics().heightPixels = 2400;
        Settings.System.putInt(context.getContentResolver(), "support_long_press_mode", 1);
        Settings.System.putInt(context.getContentResolver(), "support_long_press_time_out", 100);
        PackageInfo host = new PackageInfo(); host.packageName = HostPolicy.HOST_PACKAGE;
        host.versionName = "4.1.3"; host.setLongVersionCode(40013);
        host.applicationInfo = new ApplicationInfo(); host.applicationInfo.packageName = host.packageName;
        host.applicationInfo.uid = HOST_UID; host.applicationInfo.enabled = true;
        ((org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager())).installPackage(host);
        Handler worker = new Handler(Looper.getMainLooper());
        runtime = new RuntimeServer(context, worker, () -> stored, new SystemPackageOps() {}, ignored -> {});
        physical = new PhysicalTouchState();
        atms = new ScopeCaptureWindowAndroidTest.Atms(); main = new ScopeCaptureWindowAndroidTest.Window();
        main.mActivityRecord.main = main;
        atms.mWindowManager.mRoot.display.hit = main;
        atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        ActivityManager.RunningTaskInfo task = new ActivityManager.RunningTaskInfo();
        task.topActivity = main.mActivityRecord.mActivityComponent;
        ((org.robolectric.shadows.ShadowActivityManager) org.robolectric.shadow.api.Shadow.extract(
                context.getSystemService(ActivityManager.class))).setTasks(List.of(task));
        ScopeCaptureWindow windows = new ScopeCaptureWindow(ScopeCaptureWindowAndroidTest.Atms.class,
                new ScopeCaptureWindow.CaptureBackend() {
                    public Class<?> surfaceType() { return Object.class; }
                    public Bitmap capture(Object surface, Object[] excluded, int uid, Rect crop, float scale) {
                        captures++; duringCapture.run();
                        if (captureFailure != null) throw captureFailure;
                        return missingCapture ? null : Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888);
                    }
                });
        Constructor<SystemLongPressDrag> constructor;
        try { constructor = SystemLongPressDrag.class.getDeclaredConstructor(XposedModule.class, Handler.class,
                Supplier.class, Supplier.class, Supplier.class, ScopeCaptureWindow.class, PhysicalTouchState.class); }
        catch (NoSuchMethodException missing) { throw new AssertionError("System fallback must authenticate a still-held physical stream", missing); }
        constructor.setAccessible(true);
        drag = constructor.newInstance(null, worker, (Supplier<Context>) () -> context,
                (Supplier<Object>) () -> atms, (Supplier<RuntimeServer>) () -> runtime, windows, physical);
        runtime.setDragGestures(drag);
        pointer(MotionEvent.ACTION_DOWN, 50, 200);
    }

    private void publish() { stored.edit().putString(RuntimeSnapshot.KEY, RuntimeSnapshot.from(RuntimeEnvironment.getApplication())).commit(); }

    private void pointer(int action, float x, float y) {
        pointer(action, x, y, true);
    }

    private void pointer(int action, float x, float y, boolean exclusive) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) down = now;
        physical.event(0, 0, down, now, action, now);
        if (action == MotionEvent.ACTION_UP) physical.markReleasedElapsed(SystemClock.elapsedRealtime());
        MotionEvent event = MotionEvent.obtain(down, now, action, x, y, 0);
        try { drag.pointer(event, 0, exclusive); } finally { event.recycle(); }
        ShadowLooper.idleMainLooper();
    }

    private Bundle request() { Bundle request = new Bundle(); request.putString("token", starts.get(starts.size() - 1).getStringExtra("drag_token")); return request; }
    private Bundle prepare() { return runtime.handle(HOST_UID, "drag_gesture_prepare", request()); }
    private void ready() { ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS); }

    private void transferToMask() throws Exception {
        java.lang.reflect.Field active = SystemLongPressDrag.class.getDeclaredField("current"); active.setAccessible(true);
        Object run = active.get(drag);
        java.lang.reflect.Field transferred = run.getClass().getDeclaredField("transferred"); transferred.setAccessible(true);
        transferred.setBoolean(run, true);
    }

    private Bundle maskPointer(Bundle proof, int action, float x, float y) {
        Bundle event = new Bundle(proof); event.putInt("action", action); event.putInt("pointers", 1);
        event.putFloat("x", x); event.putFloat("y", y);
        return drag.update(event, runtime.dragConfiguration());
    }

    private void physicalRelease() {
        long now = SystemClock.uptimeMillis();
        physical.event(0, 0, down, now, MotionEvent.ACTION_UP, now);
        physical.markReleasedElapsed(SystemClock.elapsedRealtime());
    }

    @Test public void downWithoutMoveOrNativeContentPreparesExactlyOnceAtRecognitionDeadline() {
        ShadowLooper.idleMainLooper(199, java.util.concurrent.TimeUnit.MILLISECONDS); assertTrue(starts.isEmpty());
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.MILLISECONDS); assertEquals(1, starts.size());
        Intent start = starts.get(0);
        assertEquals("io.github.yudigaga.taplusbridge.PREPARE_LONG_PRESS_DRAG", start.getAction());
        assertEquals(new ComponentName(HostPolicy.HOST_PACKAGE, "com.miui.contentextension.services.TextContentExtensionService"), start.getComponent());
        assertEquals(1, start.getExtras().size()); assertTrue(start.getStringExtra("drag_token").matches("[A-Za-z0-9_-]{43}"));
        Bundle state = prepare(); assertEquals("holding", state.getString("state"));
        Bundle capture = state.getBundle("capture"); assertNotNull(capture);
        ScopeCapturePayload.Metadata metadata = ScopeCapturePayload.validate(capture, SystemClock.elapsedRealtime());
        assertEquals(SOURCE, metadata.source()); assertEquals("resume", metadata.kind());
        assertEquals(50f, metadata.x(), 0); assertEquals(200f, metadata.y(), 0); assertEquals(0, metadata.bytes());
        assertFalse(capture.containsKey("fd")); assertEquals(0, captures); assertTrue(prepare().isEmpty());
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS); assertEquals(1, starts.size());
    }

    @Test public void anEarlyNativeClaimDoesNotSuppressFallbackWhenItsPayloadWasEmpty() {
        Bundle nativePick = new Bundle(); nativePick.putString("source", SOURCE); nativePick.putFloat("x", 50f);
        nativePick.putFloat("y", 200f); nativePick.putLong("native_elapsed", SystemClock.elapsedRealtime());
        Bundle claimed = drag.claim(nativePick, runtime.dragConfiguration()); assertNotNull(claimed.getString("token"));
        ready(); assertEquals(claimed.getString("token"), request().getString("token"));
        assertNotNull(prepare().getBundle("capture"));
    }

    @Test public void releasedCancelledChangedConfigurationAndUnknownPhysicalStreamsDoNotPrepare() throws Exception {
        pointer(MotionEvent.ACTION_UP, 50, 200); ready(); assertTrue(starts.isEmpty());
        pointer(MotionEvent.ACTION_DOWN, 50, 200); pointer(MotionEvent.ACTION_CANCEL, 50, 200); ready(); assertTrue(starts.isEmpty());
        pointer(MotionEvent.ACTION_DOWN, 50, 200); settings.setEnabled(false); publish(); ready(); assertTrue(starts.isEmpty());
        settings.setEnabled(true); publish(); pointer(MotionEvent.ACTION_DOWN, 50, 200);
        physical.uncertainDevice(0, SystemClock.uptimeMillis()); ready(); assertTrue(starts.isEmpty());
    }

    @Test public void expiredTokenNewDownAndUnrelatedTokenCannotReadFallbackMetadata() {
        ready(); Bundle previous = request();
        Bundle bad = new Bundle(); bad.putString("token", "z".repeat(43));
        assertTrue(runtime.handle(HOST_UID, "drag_gesture_prepare", bad).isEmpty());
        pointer(MotionEvent.ACTION_DOWN, 50, 200);
        assertTrue(runtime.handle(HOST_UID, "drag_gesture_prepare", previous).isEmpty());
        ready(); pointer(MotionEvent.ACTION_UP, 50, 200); assertTrue(prepare().isEmpty());
    }

    @Test public void optedInScreenshotArrivesAsABoundedReadablePipe() throws Exception {
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), true)); publish();
        pointer(MotionEvent.ACTION_DOWN, 50, 200); ready(); Bundle capture = prepare().getBundle("capture");
        ScopeCapturePayload.Metadata metadata = ScopeCapturePayload.validate(capture, SystemClock.elapsedRealtime());
        assertEquals("image", metadata.kind()); assertEquals(1, captures);
        // Robolectric models a pipe with a regular file, whose reader cannot wait for the writer.
        java.util.concurrent.ExecutorService writer = org.robolectric.util.ReflectionHelpers.getField(drag, "writer");
        writer.submit(() -> {}).get(3, java.util.concurrent.TimeUnit.SECONDS);
        try (ParcelFileDescriptor fd = capture.getParcelable("fd"); var input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] png = ScopeCapturePayload.read(input, metadata.bytes());
            Bitmap image = ScopeCapturePayload.decode(png); assertEquals(32, image.getWidth()); image.recycle();
        }
    }

    @Test public void unavailableScreenshotKeepsTheVerifiedGestureAsMetadataForTheLinkFallback() {
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), true, true)); publish();
        for (boolean throwsFailure : new boolean[]{false, true}) {
            missingCapture = !throwsFailure;
            captureFailure = throwsFailure ? new IllegalStateException("capture_unavailable") : null;
            pointer(MotionEvent.ACTION_DOWN, 50, 200); ready(); Bundle proof = request();
            Bundle state = prepare(); Bundle capture = state.getBundle("capture");
            assertNotNull(capture);
            assertEquals("resume", ScopeCapturePayload.validate(capture, SystemClock.elapsedRealtime()).kind());
            assertEquals(0, capture.getInt("bytes")); assertFalse(capture.containsKey("fd"));
            assertTrue(state.getBoolean("link_probe"));
            assertEquals("holding", drag.poll(proof, runtime.dragConfiguration()).getString("state"));
        }
    }

    @Test public void screenshotFailureNeverFallsBackAfterTheSourceBecomesSecureOrChanges() {
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), true, true)); publish();
        pointer(MotionEvent.ACTION_DOWN, 50, 200); ready();
        captureFailure = new IllegalStateException("capture_unavailable");
        duringCapture = () -> main.mAttrs.flags |= android.view.WindowManager.LayoutParams.FLAG_SECURE;
        assertTrue(prepare().isEmpty());
        assertEquals("cancelled", drag.poll(request(), runtime.dragConfiguration()).getString("state"));
    }

    @Test public void linkRequestNeedsTheSameReleasedDirectedGestureAndRunsOnlyOnce() throws Exception {
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), false, true)); publish();
        pointer(MotionEvent.ACTION_DOWN, 50, 200); ready(); Bundle proof = request();
        List<ScopeCaptureWindow.Snapshot> links = new ArrayList<>();
        drag.setCardLinks((target, configuration, completed) -> { links.add(target); completed.accept("https://example.com/card"); });
        assertFalse(runtime.handle(HOST_UID, "drag_gesture_link", proof).getBoolean("accepted"));
        transferToMask();
        assertEquals("holding", maskPointer(proof, MotionEvent.ACTION_MOVE, 1070, 200).getString("state"));
        physicalRelease();
        assertEquals("released", maskPointer(proof, MotionEvent.ACTION_UP, 1070, 200).getString("state"));
        assertTrue(drag.sourcePointStable(SOURCE, SystemClock.elapsedRealtime()));
        assertTrue(drag.fingerReleasedForLink());
        assertTrue(runtime.handle(HOST_UID, "drag_gesture_link", proof).getBoolean("accepted"));
        assertEquals(1, links.size()); assertEquals(50f, links.get(0).x, 0);
        assertFalse(runtime.handle(HOST_UID, "drag_gesture_link", proof).getBoolean("accepted"));
        assertEquals("https://example.com/card", drag.poll(proof, runtime.dragConfiguration()).getString("card_link"));
    }

    @Test public void movementDeliveredToTheSourceBeforeTransferPreventsOriginalPointLinkFallback() {
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), false, true)); publish();
        pointer(MotionEvent.ACTION_DOWN, 50, 200); ready(); Bundle proof = request();
        int[] links = {0}; drag.setCardLinks((target, configuration, completed) -> links[0]++);
        pointer(MotionEvent.ACTION_MOVE, 1070, 200); pointer(MotionEvent.ACTION_UP, 1070, 200);
        assertEquals("released", drag.poll(proof, runtime.dragConfiguration()).getString("state"));
        assertFalse(drag.fingerReleasedForLink());
        assertFalse(runtime.handle(HOST_UID, "drag_gesture_link", proof).getBoolean("accepted"));
        assertEquals(0, links[0]);
    }

    @Test public void nativePreTransferJitterWithinTouchSlopKeepsTheOriginalSourcePointStable() {
        long pickedAt = SystemClock.elapsedRealtime();
        Bundle nativePick = new Bundle(); nativePick.putString("source", SOURCE); nativePick.putFloat("x", 50f);
        nativePick.putFloat("y", 200f); nativePick.putLong("native_elapsed", pickedAt);
        assertNotNull(drag.claim(nativePick, runtime.dragConfiguration()).getString("token"));
        ready();
        float slop = ViewConfiguration.get(context).getScaledTouchSlop();
        pointer(MotionEvent.ACTION_MOVE, 50 + slop, 200);
        pointer(MotionEvent.ACTION_UP, 50, 200);
        assertTrue(drag.sourcePointStable(SOURCE, pickedAt));
        assertTrue(drag.fingerReleasedForLink());
    }

    @Test public void nativePreTransferDriftRemainsUnsafeAfterReturningToTheDownPoint() {
        long began = SystemClock.elapsedRealtime();
        Bundle nativePick = new Bundle(); nativePick.putString("source", SOURCE); nativePick.putFloat("x", 50f);
        nativePick.putFloat("y", 200f); nativePick.putLong("native_elapsed", began);
        assertNotNull(drag.claim(nativePick, runtime.dragConfiguration()).getString("token"));
        ready();
        float slop = ViewConfiguration.get(context).getScaledTouchSlop();
        pointer(MotionEvent.ACTION_MOVE, 50 + slop + 1, 200, false);
        pointer(MotionEvent.ACTION_MOVE, 50, 200, false);
        pointer(MotionEvent.ACTION_UP, 50, 200);
        assertFalse(drag.sourcePointStable(SOURCE, began));
        assertFalse(drag.sourcePointStable(SOURCE, SystemClock.elapsedRealtime()));
        assertFalse(drag.fingerReleasedForLink());
        assertTrue(drag.sourcePointStable("example.unrelated", began));
        assertTrue(drag.sourcePointStable(SOURCE, began - 1));
        pointer(MotionEvent.ACTION_DOWN, 50, 200);
        assertTrue(drag.sourcePointStable(SOURCE, SystemClock.elapsedRealtime()));
    }

    @Test public void localMaskUpCannotStartTheLinkProbeUntilThePhysicalStreamHasReallyReleased() throws Exception {
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), false, true)); publish();
        pointer(MotionEvent.ACTION_DOWN, 50, 200); ready(); Bundle proof = request();
        transferToMask();
        int[] links = {0}; drag.setCardLinks((target, configuration, completed) -> links[0]++);
        Bundle up = new Bundle(proof); up.putInt("action", MotionEvent.ACTION_UP); up.putInt("pointers", 1);
        up.putFloat("x", 1070f); up.putFloat("y", 200f);
        drag.update(up, runtime.dragConfiguration());
        assertFalse(runtime.handle(HOST_UID, "drag_gesture_link", proof).getBoolean("accepted")); assertEquals(0, links[0]);
        physical.event(0, 0, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, SystemClock.uptimeMillis());
        physical.markReleasedElapsed(SystemClock.elapsedRealtime());
        assertTrue(runtime.handle(HOST_UID, "drag_gesture_link", proof).getBoolean("accepted")); assertEquals(1, links[0]);
    }

    @Test public void aNativeUrlOrChangedSourcePreventsThePreparedFallbackLinkProbe() {
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), false, true)); publish();
        pointer(MotionEvent.ACTION_DOWN, 50, 200); ready(); Bundle proof = request();
        int[] links = {0}; drag.setCardLinks((target, configuration, completed) -> links[0]++);
        drag.nativeResult(SOURCE, SystemClock.elapsedRealtime(), true);
        pointer(MotionEvent.ACTION_MOVE, 1070, 200); pointer(MotionEvent.ACTION_UP, 1070, 200);
        assertFalse(runtime.handle(HOST_UID, "drag_gesture_link", proof).getBoolean("accepted")); assertEquals(0, links[0]);
        pointer(MotionEvent.ACTION_DOWN, 50, 200); ready(); proof = request();
        pointer(MotionEvent.ACTION_MOVE, 1070, 200); pointer(MotionEvent.ACTION_UP, 1070, 200);
        main.mActivityRecord.mActivityComponent = new ComponentName(SOURCE, "org.chromium.chrome.browser.Other");
        assertFalse(runtime.handle(HOST_UID, "drag_gesture_link", proof).getBoolean("accepted")); assertEquals(0, links[0]);
    }

    private Bundle releasedGestureProof(long heldMillis) {
        pointer(MotionEvent.ACTION_DOWN, 50, 200); ready(); Bundle proof = request();
        if (heldMillis > 200) ShadowSystemClock.advanceBy(Duration.ofMillis(heldMillis - 200));
        pointer(MotionEvent.ACTION_MOVE, 1070, 200); pointer(MotionEvent.ACTION_UP, 1070, 200);
        proof.putString("source", SOURCE); proof.putString("direction", "right"); return proof;
    }

    @Test public void contentRemainsConsumableAfterTheFullLinkProbeTimeoutButNotAfterReleaseGrace() {
        Bundle proof = releasedGestureProof(300);
        ShadowLooper.idleMainLooper(6_500, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(drag.consume(proof, runtime.dragConfiguration()).getBoolean("accepted"));
        assertFalse(drag.consume(proof, runtime.dragConfiguration()).getBoolean("accepted"));
        proof = releasedGestureProof(300);
        ShadowLooper.idleMainLooper(7_500, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertFalse(drag.consume(proof, runtime.dragConfiguration()).getBoolean("accepted"));
    }

    @Test public void aLatePhysicalReleaseKeepsItsFullLinkGraceAfterTheHeldGestureTimeout() {
        Bundle proof = releasedGestureProof(29_000);
        ShadowLooper.idleMainLooper(6_500, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(drag.consume(proof, runtime.dragConfiguration()).getBoolean("accepted"));
    }
}
