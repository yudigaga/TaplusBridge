package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge;
import io.github.yudigaga.taplusbridge.runtime.RuntimeClient;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ScopeLateLinkAndroidTest {
    private static final String SOURCE = "com.example.source", IDENTITY = "configuration_identity_1";
    private static final String URL = "https://example.com/target";
    private RecognitionHooks hooks;
    private HostRecognitionEnhancer enhancer;
    private Object service;
    private NativeResult nativeResult;
    private RecognitionHooks.DragPick pick;
    private HostScopeCaptureHooks.PendingNative pending;
    private boolean probeEnabled;
    private int nativeCalls;
    private final List<String> sourceEvents = new ArrayList<>();

    @Before public void setup() throws Exception {
        FakeHost.context = RuntimeEnvironment.getApplication(); FakeHost.published = 0;
        enhancer = new HostRecognitionEnhancer(Model.class);
        Constructor<RecognitionHooks> constructor = RecognitionHooks.class.getDeclaredConstructor(XposedModule.class,
                Supplier.class, HostRecognitionEnhancer.class, Method.class, Method.class);
        constructor.setAccessible(true);
        hooks = constructor.newInstance(null, (Supplier<String>) () -> SOURCE, enhancer,
                FakeHost.class.getDeclaredMethod("application"), FakeHost.class.getDeclaredMethod("publish", ArrayList.class));
        enhancer.setReporter(sourceEvents::add);
        service = new Object(); nativeResult = new NativeResult();
        FakeHost.result = nativeResult; FakeHost.finished = false;
        field("hostResult").set(hooks, FakeHost.class.getDeclaredField("result"));
        field("hostFinished").set(hooks, FakeHost.class.getDeclaredField("finished"));
        field("supportedHost").set(hooks, true);
        field("active").set(hooks, true); field("epoch").setLong(hooks, 7);
        field("pickService").set(hooks, service);
        pick = new RecognitionHooks.DragPick(7, service, nativeResult, 1000);
        field("dragPick").set(hooks, pick);
        field("fallbackDeadlinePick").set(hooks, pick); field("fallbackRecognitionDeadline").setLong(hooks, 1);
        probeEnabled = true;
        Method bridge = RuntimeClient.class.getDeclaredMethod("testBridge", IRuntimeBridge.class); bridge.setAccessible(true);
        bridge.invoke(null, new IRuntimeBridge.Stub() {
            public Bundle call(String method, Bundle input) {
                Bundle response = new Bundle(); response.putString("identity", IDENTITY);
                response.putBoolean("scope_link_probe_enabled", probeEnabled); return response;
            }
        });
        pending = new HostScopeCaptureHooks.PendingNative();
    }

    @After public void resetBridge() throws Exception {
        Method bridge = RuntimeClient.class.getDeclaredMethod("testBridge", IRuntimeBridge.class); bridge.setAccessible(true);
        bridge.invoke(null, new Object[]{null});
    }

    @Test public void busyNativeLabelGetsLateLinkWithoutReplayingTheRejectedCallbackOrLosingText() throws Exception {
        nativeCallback(new NativeResult()); assertEquals(1, nativeCalls);
        assertEquals(0, FakeHost.published); FakeHost.result = nativeResult; nativeCalls = 0;
        var picks = matched(7);
        assertTrue(relay(picks, true));
        assertEquals(0, nativeCalls); assertSame(nativeResult, FakeHost.result);
        assertFalse(FakeHost.finished); assertSame(pick, field("dragPick").get(hooks));
        assertEquals(7, field("epoch").getLong(hooks));
        assertEquals("查看链接", HostScopeCaptureHooks.nativeText(nativeResult));
        assertEquals(List.of(URL), enhancer.capturedLinks()); assertTrue(enhancer.isProbeRecommendation(model(URL)));
        assertEquals(URL, field("scopeLinkUrl").get(hooks));
        assertTrue(sourceEvents.stream().anyMatch(event -> event.equals("recognition source stage=links channel=probe found=1 added=1 total=1")));
        ShadowLooper.idleMainLooper(); assertEquals(1, FakeHost.published);
    }

    @Test public void replacementEpochResultServiceOrSourceCannotReceiveTheOldGestureLink() throws Exception {
        var picks = matched(7);
        field("epoch").setLong(hooks, 8);
        field("dragPick").set(hooks, new RecognitionHooks.DragPick(8, service, nativeResult, 1000));
        assertFalse(relay(picks, true)); assertEquals(0, nativeCalls);
        field("epoch").setLong(hooks, 7); field("dragPick").set(hooks, pick);
        NativeResult replacement = new NativeResult(); FakeHost.result = replacement;
        assertFalse(relay(picks, true)); assertEquals(0, nativeCalls); assertSame(replacement, FakeHost.result);
        FakeHost.result = nativeResult; assertFalse(relay(picks, true, new Object(), SOURCE, IDENTITY));
        assertFalse(relay(picks, true, service, "com.example.other", IDENTITY));
        assertFalse(relay(picks, true, service, SOURCE, "new_configuration")); assertEquals(0, nativeCalls);
        Context savedContext = FakeHost.context; FakeHost.context = null;
        assertFalse(relay(picks, true)); assertEquals(0, nativeCalls); FakeHost.context = savedContext;
        assertTrue(enhancer.capturedLinks().isEmpty());
    }

    @Test public void disabledProbeAndFinishedSamePickDropWithoutReopeningTheHost() throws Exception {
        probeEnabled = false; assertFalse(relay(matched(7), true)); assertEquals(0, nativeCalls);
        assertTrue(enhancer.capturedLinks().isEmpty());
        probeEnabled = true; FakeHost.result = nativeResult; FakeHost.finished = true;
        assertFalse(relay(matched(7), true)); assertEquals(0, nativeCalls);
        assertSame(nativeResult, FakeHost.result); assertTrue(FakeHost.finished);
        assertTrue(enhancer.capturedLinks().isEmpty());
    }

    @Test public void unrelatedConfigurationOrGestureCannotSupplyAnEpochBoundNativePick() throws Exception {
        pending.hold(false, SOURCE, 1000, IDENTITY, nativeResult);
        assertNull(pending.take(SOURCE, "other", 900, 1100, 1100).text());
        assertNull(pending.take(SOURCE, IDENTITY, 1101, 1200, 1200).text());
        assertFalse(accept(pending.take(SOURCE, IDENTITY, 900, 1100, 1200), service, SOURCE, IDENTITY));
        assertTrue(enhancer.capturedLinks().isEmpty());
    }

    private HostScopeCaptureHooks.PendingNative.Picks matched(long epoch) throws Exception {
        var held = pending.hold(false, SOURCE, 1000, IDENTITY, nativeResult);
        Method bind = pending.getClass().getDeclaredMethod("bindEpoch", held.getClass(), long.class); bind.setAccessible(true);
        bind.invoke(pending, held, epoch);
        return pending.take(SOURCE, IDENTITY, 900, 1100, 1200);
    }
    private boolean relay(HostScopeCaptureHooks.PendingNative.Picks picks, boolean enabled) throws Exception {
        return relay(picks, enabled, service, SOURCE, IDENTITY);
    }
    private boolean relay(HostScopeCaptureHooks.PendingNative.Picks picks, boolean enabled, Object owner, String source, String identity) throws Exception {
        Method relay = HostScopeCaptureHooks.class.getDeclaredMethod("deliverProbeIfCurrent", boolean.class,
                String.class, BooleanSupplier.class); relay.setAccessible(true);
        return (Boolean) relay.invoke(null, enabled, URL, (BooleanSupplier) () -> {
            try { return accept(picks, owner, source, identity); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
    }
    private boolean accept(HostScopeCaptureHooks.PendingNative.Picks picks, Object owner, String source, String identity) throws Exception {
        Method accept = RecognitionHooks.class.getDeclaredMethod("acceptScopeLink", Context.class, Object.class,
                HostScopeCaptureHooks.PendingNative.Picks.class, String.class, String.class, String.class);
        accept.setAccessible(true); return (Boolean) accept.invoke(hooks, FakeHost.context, owner, picks, source, identity, URL);
    }
    private void nativeCallback(Object supplied) {
        nativeCalls++; FakeHost.result = supplied;
        if (!FakeHost.finished) return;
        FakeHost.published++;
    }
    private Field field(String name) throws Exception { Field field = RecognitionHooks.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Model model(String url) { Model model = new Model(); model.detailUrl = url; return model; }
    public static final class FakeHost {
        static Context context; public static Object result; public static boolean finished; static int published;
        public static Context application() { return context; }
        public static void publish(ArrayList<?> cards) { published++; }
    }
    public static final class NativeResult {
        private final Bundle text = new Bundle();
        NativeResult() { text.putString("viewContent", "查看链接"); }
        public Map<String, Object> getParams(String job) { return "text_pick".equals(job) ? Map.of("pick_mode", text) : Map.of(); }
        public Map<String, Object> getPropertyMap() { return new HashMap<>(); }
    }
    public static final class Model { public String title, cat, detailUrl, packageName, intent, sourceIcon; }
}
