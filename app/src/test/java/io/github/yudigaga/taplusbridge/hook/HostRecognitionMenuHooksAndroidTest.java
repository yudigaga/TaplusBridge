package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Actual recognition-window readiness and the host-to-system notification boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class HostRecognitionMenuHooksAndroidTest {
    private static final String SOURCE = "com.example.source";
    private final List<Bundle> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Boolean> calledOnMain = new AtomicReference<>();
    private final AtomicReference<Bundle> reply = new AtomicReference<>();
    private CountDownLatch notified;
    private volatile CountDownLatch firstReply;
    private Context context;

    @Before public void setup() throws Exception {
        Context application = RuntimeEnvironment.getApplication();
        context = new ContextWrapper(application) {
            @Override public String getPackageName() { return HostPolicy.HOST_PACKAGE; }
        };
        installHost("5.1.5", 50015);
        notified = new CountDownLatch(1);
        reply.set(accepted(true));
        Class<?> bridge = Class.forName("io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge");
        Object remote = Proxy.newProxyInstance(bridge.getClassLoader(), new Class<?>[]{bridge}, (target, method, args) -> {
            if (!"call".equals(method.getName())) return null;
            assertEquals("recognition_visible", args[0]);
            calledOnMain.set(Looper.myLooper() == Looper.getMainLooper());
            Bundle result = reply.get();
            CountDownLatch gate = requests.isEmpty() ? firstReply : null;
            requests.add(new Bundle((Bundle) args[1]));
            notified.countDown();
            if (gate != null) assertTrue("fixture must release the first Binder reply", gate.await(2, TimeUnit.SECONDS));
            return result;
        });
        installBridge(remote);
    }

    @After public void cleanup() throws Exception {
        if (firstReply != null) firstReply.countDown();
        installBridge(null);
    }

    @Test public void visibleRecognitionReportsOnlySourceAndShownTimeFromBackground() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        long began = SystemClock.elapsedRealtime();
        recognizeShown(splash, () -> SOURCE);
        ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
        assertTrue("a drawn recognition window must notify system_server", notified.await(2, TimeUnit.SECONDS));
        assertEquals(1, requests.size());
        Bundle request = requests.get(0);
        assertEquals(Set.of("source", "shown_elapsed"), request.keySet());
        assertEquals(SOURCE, request.getString("source"));
        assertTrue(request.get("shown_elapsed") instanceof Long);
        assertTrue(request.getLong("shown_elapsed") >= began);
        assertTrue(request.getLong("shown_elapsed") <= SystemClock.elapsedRealtime());
        assertEquals("Binder notification must stay off the host UI thread", Boolean.FALSE, calledOnMain.get());
        ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS);
        assertEquals("one show must not notify on every animation frame", 1, requests.size());
    }

    @Test public void animationCanMakeTheSameRecognitionWindowVisibleBeforeTheDeadline() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        splash.wmParams.alpha = 0f;
        recognizeShown(splash, () -> SOURCE);
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        assertTrue(requests.isEmpty());
        splash.wmParams.alpha = 1f;
        ShadowLooper.idleMainLooper(64, TimeUnit.MILLISECONDS);
        assertTrue("animation completion must not lose the visible notification", notified.await(2, TimeUnit.SECONDS));
        assertEquals(1, requests.size());
        assertTrue("readiness must be checked after a rendered frame", splash.mMainView.postedFrames > 0);
    }

    @Test public void loadingAnimationMustRevealTheRecognitionCardBeforeNotifying() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        splash.mRecognitionCard.setVisibility(View.INVISIBLE);
        recognizeShown(splash, () -> SOURCE);
        ShadowLooper.idleMainLooper(96, TimeUnit.MILLISECONDS);
        assertFalse("the loading window is not the recognition entry", notified.await(100, TimeUnit.MILLISECONDS));
        assertTrue(requests.isEmpty());
        splash.mRecognitionCard.setVisibility(View.VISIBLE);
        ShadowLooper.idleMainLooper(64, TimeUnit.MILLISECONDS);
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        assertEquals(1, requests.size());
    }

    @Test public void transparentRootOrRecognitionCardWaitsForItsVisibleAnimation() throws Exception {
        for (String target : List.of("root", "card")) {
            FakeSplash splash = new FakeSplash(context);
            View animated = "root".equals(target) ? splash.mMainView : splash.mRecognitionCard;
            animated.setAlpha(0f);
            recognizeShown(splash, () -> SOURCE);
            ShadowLooper.idleMainLooper(96, TimeUnit.MILLISECONDS);
            assertFalse(target + " opacity must gate source-menu dismissal", notified.await(100, TimeUnit.MILLISECONDS));
            assertTrue(requests.isEmpty());
            animated.setAlpha(1f);
            ShadowLooper.idleMainLooper(64, TimeUnit.MILLISECONDS);
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertEquals(1, requests.size());
            requests.clear(); notified = new CountDownLatch(1);
        }
    }

    @Test public void hiddenDetachedClipboardAndTransparentWindowsDoNotNotify() throws Exception {
        for (String state : List.of("hidden", "detached", "not_showing", "transparent", "clipboard")) {
            FakeSplash splash = new FakeSplash(context);
            switch (state) {
                case "hidden" -> splash.mMainView.setVisibility(View.INVISIBLE);
                case "detached" -> splash.mMainView.attached = false;
                case "not_showing" -> splash.mIsShowing = false;
                case "transparent" -> splash.wmParams.alpha = 0f;
                case "clipboard" -> splash.mIsClipboardView = true;
            }
            recognizeShown(splash, () -> SOURCE);
            ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS);
            assertFalse(state + " must not notify asynchronously", notified.await(100, TimeUnit.MILLISECONDS));
            assertTrue(state + " must not dismiss a source menu", requests.isEmpty());
        }
    }

    @Test public void sourceOrWindowReplacementInvalidatesAnAnimationRetry() throws Exception {
        for (String replacement : List.of("source", "view", "params", "card")) {
            FakeSplash splash = new FakeSplash(context);
            splash.wmParams.alpha = 0f;
            AtomicReference<String> source = new AtomicReference<>(SOURCE);
            recognizeShown(splash, source::get);
            ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
            switch (replacement) {
                case "source" -> source.set("com.example.other");
                case "view" -> splash.mMainView = new FakeView(context);
                case "params" -> splash.wmParams = new WindowManager.LayoutParams();
                case "card" -> splash.mRecognitionCard = new FakeView(context);
            }
            splash.wmParams.alpha = 1f;
            ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS);
            assertFalse(replacement + " must not notify asynchronously", notified.await(100, TimeUnit.MILLISECONDS));
            assertTrue(replacement + " cannot reuse an earlier recognition show", requests.isEmpty());
        }
    }

    @Test public void aWindowBecomingVisibleAfterOneSecondCannotDismissALaterMenu() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        splash.wmParams.alpha = 0f;
        recognizeShown(splash, () -> SOURCE);
        ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS);
        splash.wmParams.alpha = 1f;
        ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS);
        assertFalse(notified.await(100, TimeUnit.MILLISECONDS));
        assertTrue(requests.isEmpty());
        assertTrue("readiness retries must be bounded", splash.mMainView.postedFrames <= 70);
    }

    @Test public void anUnsupportedHostBuildCannotSendRecognitionVisibility() throws Exception {
        installHost("5.1.99", 50199);
        recognizeShown(new FakeSplash(context), () -> SOURCE);
        ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS);
        assertFalse(notified.await(100, TimeUnit.MILLISECONDS));
        assertTrue(requests.isEmpty());
    }

    @Test public void aConnectingBridgeRetriesAfterMainThreadValidationWithTheOriginalShownTime() throws Exception {
        reply.set(null);
        firstReply = new CountDownLatch(1);
        FakeSplash splash = new FakeSplash(context);
        recognizeShown(splash, () -> SOURCE);
        ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        assertEquals(1, requests.size());
        long firstShown = requests.get(0).getLong("shown_elapsed");
        reply.set(accepted(true));
        firstReply.countDown();
        awaitAttempts(2);
        assertEquals("reconnecting must not make an old recognition look newly shown", firstShown,
                requests.get(1).getLong("shown_elapsed"));
        assertEquals(Boolean.FALSE, calledOnMain.get());
        ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS);
        Thread.sleep(100);
        assertEquals("an accepted notification ends retries", 2, requests.size());
        assertTrue(splash.mMainView.attached);
    }

    @Test public void anExplicitSystemDeclineDoesNotRetryTheVisibilityNotification() throws Exception {
        reply.set(accepted(false));
        FakeSplash splash = new FakeSplash(context);
        recognizeShown(splash, () -> SOURCE);
        ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS);
        Thread.sleep(100);
        assertEquals("a non-null decline is final", 1, requests.size());
        assertTrue(splash.mMainView.attached);
    }

    @Test public void duplicateShowsOfTheSameModelKeepTheFirstVisibleTimestamp() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        recognizeShown(splash, () -> SOURCE);
        ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
        assertTrue(notified.await(2, TimeUnit.SECONDS));
        long firstShown = requests.get(0).getLong("shown_elapsed");
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        recognizeShown(splash, () -> SOURCE);
        awaitAttempts(2);
        assertEquals("Root matches retention to the first accepted visibility lease", firstShown,
                requests.get(1).getLong("shown_elapsed"));
        assertTrue(splash.mMainView.attached);
    }

    @Test public void aConnectingBridgeCannotRetryAfterSourceWindowOrCardChanges() throws Exception {
        for (String change : List.of("source", "view", "card", "hidden", "expired")) {
            reply.set(null);
            firstReply = new CountDownLatch(1);
            FakeSplash splash = new FakeSplash(context);
            AtomicReference<String> source = new AtomicReference<>(SOURCE);
            recognizeShown(splash, source::get);
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertEquals("UI changes must precede the first retry", 1, requests.size());
            switch (change) {
                case "source" -> source.set("com.example.other");
                case "view" -> splash.mMainView = new FakeView(context);
                case "card" -> splash.mRecognitionCard = new FakeView(context);
                case "hidden" -> splash.mRecognitionCard.setVisibility(View.INVISIBLE);
                case "expired" -> ShadowSystemClock.advanceBy(Duration.ofMillis(1200));
            }
            reply.set(accepted(true));
            firstReply.countDown();
            ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS);
            Thread.sleep(100);
            assertEquals(change + " must invalidate the pending notification", 1, requests.size());
            requests.clear(); notified = new CountDownLatch(1);
        }
    }

    private void awaitAttempts(int count) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (requests.size() < count && System.nanoTime() < until) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
            Thread.sleep(2);
        }
        assertEquals("the pending bridge notification must retry within its deadline", count, requests.size());
    }

    private static Bundle accepted(boolean value) {
        Bundle result = new Bundle(); result.putBoolean("accepted", value); return result;
    }

    private void installHost(String version, int code) {
        PackageInfo host = new PackageInfo(); host.packageName = HostPolicy.HOST_PACKAGE;
        host.versionName = version; host.setLongVersionCode(code);
        host.applicationInfo = new ApplicationInfo(); host.applicationInfo.packageName = host.packageName;
        host.applicationInfo.enabled = true;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).installPackage(host);
    }

    private static void recognizeShown(Object splash, Supplier<String> source) throws Exception {
        Class<?> hooks = null;
        try { hooks = Class.forName("io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuHooks"); }
        catch (ClassNotFoundException missing) { }
        assertNotNull("Host recognition visibility helper is missing", hooks);
        Method entry = null;
        try { entry = hooks.getDeclaredMethod("afterRecognizeShown", Object.class, Supplier.class); }
        catch (NoSuchMethodException missing) { }
        assertNotNull("Recognition show must expose the readiness helper", entry);
        entry.setAccessible(true);
        try { entry.invoke(null, splash, source); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }

    private static void installBridge(Object remote) throws Exception {
        Class<?> bridge = Class.forName("io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge");
        Method install = Class.forName("io.github.yudigaga.taplusbridge.runtime.RuntimeClient")
                .getDeclaredMethod("testBridge", bridge);
        install.setAccessible(true); install.invoke(null, remote);
    }

    public static final class FakeSplash {
        public final Context mContext;
        public final FakeService mService;
        public FakeView mMainView;
        public FakeView mRecognitionCard;
        public WindowManager.LayoutParams wmParams = new WindowManager.LayoutParams();
        public boolean mIsShowing = true;
        public boolean mIsClipboardView;
        FakeSplash(Context context) {
            mContext = context; mService = new FakeService(context); mService.mSplashFloatView = this;
            mMainView = new FakeView(context); mRecognitionCard = new FakeView(context);
            wmParams.alpha = 1f;
        }
    }

    public static final class FakeService extends ContextWrapper {
        public static String source;
        public static String activity;
        public static FakeResult mCatcherResult;
        public static boolean sIsTaskFinished;
        public FakeSplash mSplashFloatView;
        FakeService(Context context) {
            super(context);
            source = SOURCE; activity = SOURCE + ".MainActivity"; sIsTaskFinished = false;
            mCatcherResult = new FakeResult(source, activity);
            mCatcherResult.jobs.put("text_pick", Map.of("pick_mode", "fixture text"));
        }
        @Override public String getPackageName() { return HostPolicy.HOST_PACKAGE; }
        public static String getInjectorPackage() { return source; }
        public static String getInjectorActivity() { return activity; }
    }

    public static final class FakeResult {
        public FakeToken token;
        public final Map<String, Object> properties = new HashMap<>();
        public final Map<String, Map<String, Object>> jobs = new HashMap<>();
        FakeResult(String source, String activity) { token = new FakeToken(source, activity); }
        public Map<String, Object> getPropertyMap() { return properties; }
        public Map<String, Object> getParams(String job) { return jobs.get(job); }
        public FakeToken getInjectorToken() { return token; }
    }

    public record FakeToken(String source, String activity) {
        public String getPkgName() { return source; }
        public String getActivityName() { return activity; }
    }

    public static final class FakeView extends View {
        public boolean attached = true;
        public int postedFrames;
        FakeView(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() {
            assertSame("window readiness must be rechecked on the host UI thread", Looper.getMainLooper(), Looper.myLooper());
            return attached;
        }
        @Override public int getWindowVisibility() { return View.VISIBLE; }
        @Override public void postOnAnimation(Runnable action) {
            postedFrames++;
            new Handler(Looper.getMainLooper()).postDelayed(action, 16);
        }
    }
}
