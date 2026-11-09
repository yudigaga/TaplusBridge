package io.github.yudigaga.taplusbridge.probe;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.view.MotionEvent;
import android.view.WindowManager;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.runtime.RuntimeServer;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowKeyguardManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowUserManager;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real PNG encoding with a controlled queue; no device capture or real source input. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeImageDeliveryAndroidTest {
    private final QueueExecutor encoder = new QueueExecutor();
    private final List<Intent> starts = new ArrayList<>();
    private final List<Bitmap> images = new ArrayList<>();
    private final Handler worker = new Handler(Looper.getMainLooper());
    private SystemScopeCapture capture;
    private RuntimeServer runtime;
    private ConfigStore settings;
    private SharedPreferences stored;
    private ScopeCaptureWindow.Snapshot target;
    private ScopeCaptureWindowAndroidTest.Window main;
    private PhysicalTouchState physical;
    private Context context;

    @Before public void setup() throws Exception {
        Context application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("config", 0).edit().clear().commit();
        stored = application.getSharedPreferences("scope_image_delivery_test", 0);
        stored.edit().clear().commit();
        settings = new ConfigStore(application);
        settings.setEnabled(true);
        settings.setRecognitionEnabled(true);
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), true, false));
        publish();
        context = new ContextWrapper(application) {
            @Override public ComponentName startService(Intent intent) {
                starts.add(new Intent(intent));
                return intent.getComponent();
            }
        };
        ((ShadowPowerManager) Shadow.extract(context.getSystemService(PowerManager.class))).setIsInteractive(true);
        ((ShadowKeyguardManager) Shadow.extract(context.getSystemService(KeyguardManager.class))).setKeyguardLocked(false);
        ((ShadowUserManager) Shadow.extract(context.getSystemService(UserManager.class))).setUserUnlocked(true);
        PackageInfo host = new PackageInfo();
        host.packageName = HostPolicy.HOST_PACKAGE;
        host.versionName = "4.1.3";
        host.setLongVersionCode(40013);
        host.applicationInfo = new ApplicationInfo();
        host.applicationInfo.packageName = host.packageName;
        host.applicationInfo.uid = 22002;
        host.applicationInfo.enabled = true;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).installPackage(host);
        runtime = new RuntimeServer(context, worker, () -> stored, ignored -> {});
        physical = new PhysicalTouchState();
        long released = SystemClock.uptimeMillis();
        physical.event(0, 1, released, released, MotionEvent.ACTION_UP, released);
        physical.markReleasedElapsed(SystemClock.elapsedRealtime());
        var atms = new ScopeCaptureWindowAndroidTest.Atms();
        main = new ScopeCaptureWindowAndroidTest.Window();
        main.mActivityRecord.main = main;
        atms.mWindowManager.mRoot.display.hit = main;
        atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        ScopeCaptureWindow windows = new ScopeCaptureWindow(ScopeCaptureWindowAndroidTest.Atms.class,
                new ScopeCaptureWindow.CaptureBackend() {
                    public Class<?> surfaceType() { return Object.class; }
                    public Bitmap capture(Object layer, Object[] excluded, int uid, Rect crop, float scale) {
                        Bitmap image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
                        images.add(image);
                        return image;
                    }
                });
        target = windows.inspect(atms, 0, 50, 200);
        capture = new SystemScopeCapture(null, worker, () -> context, () -> atms, () -> runtime, windows, physical, encoder);
        runtime.setSourceCapture(capture);
    }

    private void publish() {
        stored.edit().putString(RuntimeSnapshot.KEY, RuntimeSnapshot.from(RuntimeEnvironment.getApplication())).commit();
    }

    private void deliver() throws Exception {
        deliver(null);
    }

    private void deliver(String url) throws Exception {
        Method method = SystemScopeCapture.class.getDeclaredMethod("deliverCapture", long.class,
                ScopeCaptureWindow.Snapshot.class, RuntimeSnapshot.class, long.class, String.class);
        method.setAccessible(true);
        long generation = ReflectionHelpers.getField(capture, "generation");
        method.invoke(capture, generation, target, runtime.sourceCaptureConfiguration(), 0L, url);
    }

    private void finishEncoding() {
        encoder.runNext();
        ShadowLooper.idleMainLooper();
    }

    @Test public void pngEncodingDoesNotOccupyThePointerWorker() throws Exception {
        deliver();
        assertTrue("Image delivery must await background PNG encoding", starts.isEmpty());
        assertEquals(1, encoder.tasks.size());
        AtomicBoolean pointerHandled = new AtomicBoolean();
        worker.post(() -> pointerHandled.set(true));
        ShadowLooper.idleMainLooper();
        assertTrue("Touch work must proceed before encoding completes", pointerHandled.get());
        assertTrue(starts.isEmpty());
        finishEncoding();
        assertEquals(1, starts.size());
        assertTrue(images.get(0).isRecycled());
    }

    @Test public void newPhysicalDownDiscardsOldEncodedPixels() throws Exception {
        deliver();
        deliver();
        assertTrue(starts.isEmpty());
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 50, 200, 0);
        try { capture.pointer(down, 0, true); } finally { down.recycle(); }
        finishEncoding();
        assertTrue(starts.isEmpty());
        assertTrue(images.get(0).isRecycled());
        assertEquals("A cancelled waiting request must never capture pixels", 1, images.size());
        assertTrue(encoder.tasks.isEmpty());
    }

    @Test public void latestGestureWaitsWithoutDeliveringThePreviousGesturesImage() throws Exception {
        deliver();
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            physical.event(0, 1, now, now, action, now);
            MotionEvent event = MotionEvent.obtain(now, now, action, 50, 200, 0);
            try { capture.pointer(event, 0, true); } finally { event.recycle(); }
            ShadowLooper.idleMainLooper();
        }
        deliver();
        assertEquals(1, images.size());
        finishEncoding();
        assertTrue("An obsolete gesture must not submit its image", starts.isEmpty());
        assertEquals(2, images.size());
        finishEncoding();
        assertEquals(1, starts.size());
        assertTrue(images.stream().allMatch(Bitmap::isRecycled));
    }

    @Test public void changedConfigurationDiscardsOldEncodedPixels() throws Exception {
        deliver();
        assertTrue(starts.isEmpty());
        settings.setEnabled(false);
        publish();
        finishEncoding();
        assertTrue(starts.isEmpty());
        assertTrue(images.get(0).isRecycled());
    }

    @Test public void windowBecomingSecureDuringEncodingPreventsDelivery() throws Exception {
        deliver();
        assertTrue(starts.isEmpty());
        main.mAttrs.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        finishEncoding();
        assertTrue(starts.isEmpty());
        assertTrue(images.get(0).isRecycled());
    }

    @Test public void lockingScreenDuringEncodingPreventsDelivery() throws Exception {
        deliver();
        assertTrue(starts.isEmpty());
        ((ShadowKeyguardManager) Shadow.extract(context.getSystemService(KeyguardManager.class))).setKeyguardLocked(true);
        finishEncoding();
        assertTrue(starts.isEmpty());
        assertTrue(images.get(0).isRecycled());
    }

    @Test public void busyEncoderRetainsOnlyOnePendingCaptureWithoutAllocatingMorePixels() throws Exception {
        deliver();
        assertTrue(starts.isEmpty());
        for (int i = 0; i < 10; i++) deliver();
        assertEquals("Waiting captures must not retain screenshot bitmaps", 1, images.size());
        assertEquals(1, encoder.tasks.size());
        finishEncoding();
        assertEquals(2, images.size());
        assertEquals(1, encoder.tasks.size());
        finishEncoding();
        assertTrue(encoder.tasks.isEmpty());
        assertEquals(2, starts.size());
        assertTrue(images.stream().allMatch(Bitmap::isRecycled));
    }

    @Test public void rejectedEncodingReleasesPixelsAndAllowsTheNextCapture() throws Exception {
        encoder.reject = true;
        deliver();
        assertTrue("Rejected encoding must not deliver pixels synchronously", starts.isEmpty());
        assertTrue(images.get(0).isRecycled());
        encoder.reject = false;
        deliver();
        assertEquals(1, encoder.tasks.size());
        finishEncoding();
        assertEquals(1, starts.size());
        assertTrue(images.stream().allMatch(Bitmap::isRecycled));
    }

    @Test public void failedEncodingDoesNotPreventTheNextCapture() throws Exception {
        deliver();
        images.get(0).recycle();
        finishEncoding();
        assertTrue(starts.isEmpty());
        deliver();
        finishEncoding();
        assertEquals(1, starts.size());
        assertTrue(images.stream().allMatch(Bitmap::isRecycled));
    }

    @Test public void nativeLinkArrivingDuringEncodingPreventsDuplicateDelivery() throws Exception {
        deliver();
        android.os.Bundle nativeResult = new android.os.Bundle();
        nativeResult.putString("source", target.source);
        nativeResult.putLong("native_elapsed", SystemClock.elapsedRealtime());
        nativeResult.putBoolean("has_valid_url", true);
        capture.nativeResult(nativeResult);
        finishEncoding();
        assertTrue(starts.isEmpty());
        assertTrue(images.get(0).isRecycled());
    }

    @Test public void encodingFailureKeepsTheEnabledLinkFallback() throws Exception {
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), true, true));
        publish();
        String url = "https://example.com/captured-link";
        deliver(url);
        images.get(0).recycle();
        finishEncoding();
        assertEquals(1, starts.size());
        Bundle request = new Bundle();
        request.putString("nonce", starts.get(0).getStringExtra("scope_nonce"));
        Bundle payload = capture.take(22002, request, runtime.sourceCaptureConfiguration());
        assertEquals("text", payload.getString("kind"));
        assertEquals(url, payload.getString("url"));
    }

    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean reject;
        public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException("test_encoder_busy");
            tasks.add(task);
        }
        void runNext() {
            assertFalse("Expected one pending PNG encoding task", tasks.isEmpty());
            tasks.remove().run();
        }
    }
}
