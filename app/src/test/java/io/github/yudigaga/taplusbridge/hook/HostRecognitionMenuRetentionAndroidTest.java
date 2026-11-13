package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import static io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuHooksAndroidTest.*;
import static org.junit.Assert.*;

/** Preserves the exact native model before stock onContentReceived can overwrite it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class HostRecognitionMenuRetentionAndroidTest {
    private static final String SOURCE = "com.example.source";
    private final List<Bundle> requests = new CopyOnWriteArrayList<>();
    private Context context;
    private Bundle response;

    @Before public void setup() throws Exception {
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public String getPackageName() { return HostPolicy.HOST_PACKAGE; }
        };
        PackageInfo host = new PackageInfo(); host.packageName = HostPolicy.HOST_PACKAGE;
        host.versionName = "5.1.5"; host.setLongVersionCode(50015);
        host.applicationInfo = new ApplicationInfo(); host.applicationInfo.packageName = host.packageName;
        host.applicationInfo.enabled = true;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).installPackage(host);
        response = new Bundle(); response.putBoolean("keep", true);
        Class<?> bridge = Class.forName("io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge");
        Object remote = Proxy.newProxyInstance(bridge.getClassLoader(), new Class<?>[]{bridge}, (target, method, args) -> {
            if (!"call".equals(method.getName())) return null;
            assertEquals("recognition_menu_retain", args[0]);
            requests.add(new Bundle((Bundle) args[1])); return response;
        });
        installBridge(remote);
    }

    @After public void cleanup() throws Exception { installBridge(null); }

    @Test public void anAuthorizedMenuControlKeepsTheOriginalModelBeforeTheStockWrite() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        FakeResult original = FakeService.mCatcherResult;
        long shown = SystemClock.elapsedRealtime();
        assertEquals(shown, remember(splash, shown));
        FakeCallback callback = new FakeCallback(splash.mService);
        deliver(callback, control("257"));
        assertSame("filtering quitByDefault later would already have lost this model", original, FakeService.mCatcherResult);
        assertFalse(FakeService.sIsTaskFinished);
        assertEquals(0, callback.calls);
        assertEquals(1, requests.size());
        Bundle request = requests.get(0);
        assertEquals(Set.of("source", "activity", "shown_elapsed"), request.keySet());
        assertEquals(SOURCE, request.getString("source"));
        assertEquals(SOURCE + ".MainActivity", request.getString("activity"));
        assertEquals(shown, request.getLong("shown_elapsed"));
    }

    @Test public void aSecondBackIsNeverRetainedByTheSameShownRecognition() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        remember(splash, SystemClock.elapsedRealtime());
        FakeCallback callback = new FakeCallback(splash.mService);
        deliver(callback, control("257"));
        FakeResult realBack = control("257");
        deliver(callback, realBack);
        assertEquals(1, requests.size());
        assertEquals(1, callback.calls);
        assertEquals(1, callback.quits);
        assertSame(realBack, FakeService.mCatcherResult);
        assertTrue(FakeService.sIsTaskFinished);
    }

    @Test public void aRealBackDeclinedBySystemOrAnUnavailableBridgeStillRunsStock() throws Exception {
        for (boolean unavailable : List.of(false, true)) {
            response = unavailable ? null : new Bundle();
            FakeSplash splash = new FakeSplash(context);
            remember(splash, SystemClock.elapsedRealtime());
            FakeCallback callback = new FakeCallback(splash.mService);
            FakeResult incoming = control("257");
            deliver(callback, incoming);
            assertSame(incoming, FakeService.mCatcherResult);
            assertEquals(1, callback.calls);
            assertEquals(1, callback.quits);
            assertTrue(FakeService.sIsTaskFinished);
        }
        assertEquals(2, requests.size());
    }

    @Test public void moveDataMixedPropertiesAndMalformedCodesNeverConsumePermission() throws Exception {
        for (String kind : List.of("move", "text", "image", "extra", "numeric", "missing")) {
            FakeSplash splash = new FakeSplash(context);
            remember(splash, SystemClock.elapsedRealtime());
            FakeResult incoming = control("257");
            switch (kind) {
                case "move" -> incoming.properties.put("observe_control_event", "258");
                case "text" -> incoming.jobs.put("text_pick", Map.of("pick_mode", new Bundle()));
                case "image" -> incoming.jobs.put("image_pick", Map.of("image_pick_mode", new Bundle()));
                case "extra" -> incoming.properties.put("unrelated", "fixture");
                case "numeric" -> incoming.properties.put("observe_control_event", 257);
                case "missing" -> incoming.properties.clear();
            }
            FakeCallback callback = new FakeCallback(splash.mService);
            deliver(callback, incoming);
            assertEquals(kind, 1, callback.calls);
            assertSame(incoming, FakeService.mCatcherResult);
            assertTrue(kind + " must not ask for a module-close permission", requests.isEmpty());
        }
    }

    @Test public void sourceActivityOrMissingInjectorCannotBorrowTheClosePermission() throws Exception {
        for (String kind : List.of("source", "activity", "missing")) {
            FakeSplash splash = new FakeSplash(context);
            remember(splash, SystemClock.elapsedRealtime());
            FakeResult incoming = control("257");
            incoming.token = switch (kind) {
                case "source" -> new FakeToken("com.example.other", FakeService.activity);
                case "activity" -> new FakeToken(SOURCE, SOURCE + ".OtherActivity");
                default -> null;
            };
            FakeCallback callback = new FakeCallback(splash.mService);
            deliver(callback, incoming);
            assertEquals(kind, 1, callback.calls);
            assertSame(incoming, FakeService.mCatcherResult);
            assertTrue(requests.isEmpty());
        }
    }

    @Test public void changedHostTaskModelOrWindowIdentityCannotRetainControl() throws Exception {
        for (String change : List.of("source", "activity", "model", "hidden", "root", "card", "params", "finished")) {
            FakeSplash splash = new FakeSplash(context);
            remember(splash, SystemClock.elapsedRealtime());
            FakeResult incoming = control("257");
            switch (change) {
                case "source" -> FakeService.source = "com.example.other";
                case "activity" -> FakeService.activity = SOURCE + ".OtherActivity";
                case "model" -> FakeService.mCatcherResult = new FakeResult(SOURCE, FakeService.activity);
                case "hidden" -> splash.mIsShowing = false;
                case "root" -> splash.mMainView = new FakeView(context);
                case "card" -> splash.mRecognitionCard = new FakeView(context);
                case "params" -> splash.wmParams = new android.view.WindowManager.LayoutParams();
                case "finished" -> FakeService.sIsTaskFinished = true;
            }
            FakeCallback callback = new FakeCallback(splash.mService);
            deliver(callback, incoming);
            assertEquals(change, 1, callback.calls);
            assertSame(incoming, FakeService.mCatcherResult);
            assertTrue(change + " must not consume a previous task's permission", requests.isEmpty());
        }
    }

    @Test public void aNewNativeResultInvalidatesTheRememberedModelBeforeALaterBack() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        remember(splash, SystemClock.elapsedRealtime());
        FakeCallback callback = new FakeCallback(splash.mService);
        FakeResult next = new FakeResult(SOURCE, FakeService.activity);
        next.jobs.put("text_pick", Map.of("pick_mode", "next fixture"));
        deliver(callback, next);
        assertSame(next, FakeService.mCatcherResult);
        FakeResult back = control("257");
        deliver(callback, back);
        assertEquals(2, callback.calls);
        assertEquals(1, callback.quits);
        assertTrue(requests.isEmpty());
    }

    @Test public void duplicateShowsKeepTheFirstTimestampForTheSameModelAndWindows() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        long first = SystemClock.elapsedRealtime();
        assertEquals(first, remember(splash, first));
        ShadowSystemClock.advanceBy(Duration.ofMillis(50));
        assertEquals(first, remember(splash, SystemClock.elapsedRealtime()));
        deliver(new FakeCallback(splash.mService), control("257"));
        assertEquals(first, requests.get(0).getLong("shown_elapsed"));
    }

    @Test public void clearingTheServiceAtATaskBoundaryPreventsReusingItsModel() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        remember(splash, SystemClock.elapsedRealtime());
        invoke("clear", new Class<?>[]{Object.class}, splash.mService);
        FakeCallback callback = new FakeCallback(splash.mService);
        FakeResult back = control("257");
        deliver(callback, back);
        assertEquals(1, callback.calls);
        assertSame(back, FakeService.mCatcherResult);
        assertTrue(requests.isEmpty());
    }

    @Test public void aBinderCallbackCanRetainWithoutReadingViewStateOffMain() throws Exception {
        FakeSplash splash = new FakeSplash(context);
        FakeResult original = FakeService.mCatcherResult;
        remember(splash, SystemClock.elapsedRealtime());
        FakeCallback callback = new FakeCallback(splash.mService);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread binder = new Thread(() -> {
            try { deliver(callback, control("257")); }
            catch (Throwable unexpected) { failure.set(unexpected); }
        });
        binder.start(); binder.join(2000);
        assertFalse(binder.isAlive());
        assertNull("retention must use the saved window identity, not read Views on Binder", failure.get());
        assertSame(original, FakeService.mCatcherResult);
        assertEquals(0, callback.calls);
        assertEquals(1, requests.size());
    }

    private static long remember(FakeSplash splash, long shown) throws Exception {
        return (Long) invoke("rememberShown", new Class<?>[]{Object.class, String.class, long.class}, splash, SOURCE, shown);
    }

    private static void deliver(FakeCallback callback, FakeResult incoming) throws Exception {
        boolean keep = (Boolean) invoke("retainControl", new Class<?>[]{Object.class, Object.class}, callback, incoming);
        if (!keep) callback.onContentReceived(incoming);
    }

    private static FakeResult control(String code) {
        FakeResult incoming = new FakeResult(SOURCE, SOURCE + ".MainActivity");
        incoming.properties.put("observe_control_event", code); return incoming;
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> helper = null;
        try { helper = Class.forName("io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuRetention"); }
        catch (ClassNotFoundException missing) { }
        assertNotNull("Host recognition retention helper is missing", helper);
        Method method = null;
        try { method = helper.getDeclaredMethod(name, types); }
        catch (NoSuchMethodException missing) { }
        assertNotNull("Host recognition retention boundary is missing: " + name, method);
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }

    private static void installBridge(Object remote) throws Exception {
        Class<?> bridge = Class.forName("io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge");
        Method method = Class.forName("io.github.yudigaga.taplusbridge.runtime.RuntimeClient")
                .getDeclaredMethod("testBridge", bridge);
        method.setAccessible(true); method.invoke(null, remote);
    }

    public static final class FakeCallback {
        public final FakeService this$0;
        int calls, quits;
        FakeCallback(FakeService service) { this$0 = service; }
        public void onContentReceived(FakeResult result) {
            calls++;
            FakeService.mCatcherResult = result;
            Object control = result.getPropertyMap().get("observe_control_event");
            if (!FakeService.sIsTaskFinished && ("257".equals(control) || "258".equals(control))) {
                FakeService.sIsTaskFinished = true; quits++;
            }
        }
    }
}
