package io.github.yudigaga.taplusbridge.hook;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ScopeCaptureDispatchAndroidTest {
    // Exact host PrivacyManager.checkThreadPermission contract, confirmed in Java, smali and device log.
    private static void originalPrivacyGuard() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("can not request privacy update in main thread!");
    }

    @Test public void requestFromMainRunsNativePrivacyCallbackOffMainThenTheHostCanPostItsUi() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            var dispatcher = new ScopeCaptureDispatch(worker);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean uiOnMain = new AtomicBoolean();
            dispatcher.submit(() -> {
                try {
                    originalPrivacyGuard();
                    new Handler(Looper.getMainLooper()).post(() -> uiOnMain.set(Looper.myLooper() == Looper.getMainLooper()));
                } catch (Throwable rejected) { failure.set(rejected); }
                finally { done.countDown(); }
            });
            ShadowLooper.idleMainLooper();
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertNull("Host privacy guard must not reject the screenshot callback", failure.get());
            ShadowLooper.idleMainLooper();
            assertTrue("The host still controls its original UI-thread boundary", uiOnMain.get());
        } finally { worker.shutdownNow(); }
    }

    @Test public void callbackAlreadyOnWorkerKeepsItsWorkerThread() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            var dispatcher = new ScopeCaptureDispatch(worker);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Thread> caller = new AtomicReference<>(), callbackThread = new AtomicReference<>();
            worker.execute(() -> {
                caller.set(Thread.currentThread());
                dispatcher.submit(() -> {
                    try { originalPrivacyGuard(); callbackThread.set(Thread.currentThread()); }
                    catch (Throwable rejected) { failure.set(rejected); }
                    finally { done.countDown(); }
                });
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (done.getCount() != 0 && System.nanoTime() < until) {
                ShadowLooper.idleMainLooper();
                done.await(20, TimeUnit.MILLISECONDS);
            }
            assertEquals(0, done.getCount()); assertNull(failure.get());
            assertSame(caller.get(), callbackThread.get());
        } finally { worker.shutdownNow(); }
    }
}
