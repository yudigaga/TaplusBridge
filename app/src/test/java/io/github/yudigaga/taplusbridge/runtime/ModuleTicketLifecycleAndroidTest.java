package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.drag.DragActionStore;
import io.github.yudigaga.taplusbridge.drag.DragPayload;
import io.github.yudigaga.taplusbridge.links.LinkChoiceRequests;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ModuleTicketLifecycleAndroidTest {
    private Context context;
    private final AtomicInteger exits = new AtomicInteger();
    private final ArrayList<String> dragTokens = new ArrayList<>(), ephemeralTokens = new ArrayList<>(), linkTokens = new ArrayList<>();
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", 0).edit().clear().commit();
        new ConfigStore(context).setEnabled(true);
        ModuleProcessLifecycle.installForTest(new Handler(Looper.getMainLooper()), exits::incrementAndGet);
    }
    @After public void cleanup() throws Exception {
        for (String token : dragTokens) DragActionStore.cancel(token);
        for (String token : ephemeralTokens) ephemeral("consume", token);
        for (String token : linkTokens) LinkChoiceRequests.consume(context, token);
        ModuleProcessLifecycle.resetForTest();
    }
    private void advance(long millis) {
        ((org.robolectric.shadows.ShadowLooper) org.robolectric.shadow.api.Shadow.extract(Looper.getMainLooper()))
                .idleFor(java.time.Duration.ofMillis(millis));
    }
    private DragPayload text(String value) {
        try {
            var constructor = DragPayload.class.getDeclaredConstructor(String.class, String.class, java.io.File.class,
                    String.class, int.class, int.class, String.class);
            constructor.setAccessible(true); return constructor.newInstance(value, "example.source", null, "text/plain", 0, 0, "");
        } catch (ReflectiveOperationException unavailable) { throw new AssertionError(unavailable); }
    }
    private String drag(boolean recognize) {
        String token = recognize ? DragActionStore.issue(context, "portal", text("original"))
                : DragActionStore.issueExecute(context, "portal", text("original"));
        dragTokens.add(token); return token;
    }
    @Test public void recognitionTicketProtectsTheWholeRoundUntilTheChoiceIsConsumed() {
        String token = drag(true); assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest());
        advance(4_000); assertEquals(0, exits.get());
        assertNotNull(DragActionStore.take(context, token)); assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest());
        String ready = DragActionStore.complete(context, token, "recognized"); dragTokens.add(ready);
        assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest());
        var choice = DragActionStore.takeChoice(ready); assertNotNull(choice); choice.payload().close();
        assertEquals(0, ModuleProcessLifecycle.activeLeaseCountForTest()); advance(3_000); assertEquals(1, exits.get());
    }
    @Test public void replacementCancellationExecuteAndExpiryReleaseOnlyTheirOwnDragLease() {
        String old = drag(true), current = drag(false);
        assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest());
        DragActionStore.cancel(old); assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest());
        assertNotNull(DragActionStore.take(context, current)); assertEquals(0, ModuleProcessLifecycle.activeLeaseCountForTest());
        drag(true); assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest());
        advance(DragActionStore.TTL); assertEquals(0, ModuleProcessLifecycle.activeLeaseCountForTest());
    }
    @Test public void linkChoiceHandoffPreventsExitUntilConsumedOrExpired() {
        var intent = LinkChoiceRequests.prepare(context, List.of("https://example.com/private"), null, true);
        assertNotNull(intent); String token = intent.getStringExtra("token"); linkTokens.add(token);
        assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest()); advance(4_000); assertEquals(0, exits.get());
        assertNotNull(LinkChoiceRequests.consume(context, token)); assertEquals(0, ModuleProcessLifecycle.activeLeaseCountForTest());
        intent = LinkChoiceRequests.prepare(context, List.of("https://example.com/next"), null, true);
        assertNotNull(intent); linkTokens.add(intent.getStringExtra("token"));
        assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest()); advance(30_000);
        assertEquals(0, ModuleProcessLifecycle.activeLeaseCountForTest());
    }
    private Object ephemeral(String method, Object value) throws Exception {
        Class<?> type = Class.forName("io.github.yudigaga.taplusbridge.bridge.EphemeralTicketStore");
        var target = type.getDeclaredMethod(method, "issue".equals(method) ? Bundle.class : String.class);
        target.setAccessible(true); return target.invoke(null, value);
    }
    @Test public void privateLaunchTicketCannotBeLostBetweenProviderAndTransitActivity() throws Exception {
        Bundle request = new Bundle(); request.putString("url", "https://example.com/private");
        String token = (String) ephemeral("issue", request); ephemeralTokens.add(token);
        assertNotNull(token); assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest());
        advance(4_000); assertEquals(0, exits.get());
        assertNotNull(ephemeral("consume", token)); assertEquals(0, ModuleProcessLifecycle.activeLeaseCountForTest());
        token = (String) ephemeral("issue", request); ephemeralTokens.add(token); advance(30_000);
        assertEquals(0, ModuleProcessLifecycle.activeLeaseCountForTest()); assertNull(ephemeral("consume", token));
    }
    @Test public void providerCallKeepsTheProcessAliveWhileItChecksTheCaller() {
        AtomicInteger duringCall = new AtomicInteger(-1); boolean[] observing = { false };
        var scoped = new android.content.ContextWrapper(context) {
            @Override public android.content.pm.PackageManager getPackageManager() {
                if (observing[0]) duringCall.set(ModuleProcessLifecycle.activeLeaseCountForTest());
                return super.getPackageManager();
            }
        };
        var provider = new io.github.yudigaga.taplusbridge.bridge.BridgeProvider();
        var info = new android.content.pm.ProviderInfo(); info.authority = io.github.yudigaga.taplusbridge.bridge.BridgeContract.AUTHORITY;
        provider.attachInfo(scoped, info); observing[0] = true;
        org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid());
        try { provider.call("resolve", null, Bundle.EMPTY); }
        finally { observing[0] = false; org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid()); }
        assertEquals(1, duringCall.get()); assertEquals(0, ModuleProcessLifecycle.activeLeaseCountForTest());
    }
}
