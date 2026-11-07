package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = RuntimePublisherSaveAndroidTest.FrameworkResultReceiverShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RuntimePublisherSaveAndroidTest {
    private Context context;
    private ConfigStore config;
    private RuntimePublisher publisher;
    private ExecutorService worker;
    private FakeFramework framework;
    private XposedService service;
    private IRuntimeBridge runtimeBridge;
    private volatile boolean acknowledgeCurrent;
    private CountDownLatch releaseWorker;

    // Keep the SDK's Binder forwarding when the runtime replies to a parceled receiver.
    @Implements(value = ResultReceiver.class, callThroughByDefault = true)
    public static final class FrameworkResultReceiverShadow { }

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        stopPublisher();
        RuntimeClient.testBridge(null);
        clearServiceHelper();
        for (String name : List.of("config", RuleRepository.PREFS))
            context.getSharedPreferences(name, 0).edit().clear().commit();
        config = new ConfigStore(context);
        config.setScopeLinkProbeEnabled(true);
        framework = new FakeFramework(RuntimeSnapshot.from(context));
        service = framework.service();
        RuntimePublisher.start(context);
        publisher = (RuntimePublisher) field(RuntimePublisher.class, "instance").get(null);
        assertNotNull(publisher);
        worker = (ExecutorService) field(RuntimePublisher.class, "worker").get(publisher);
        field(RuntimePublisher.class, "service").set(publisher, service);
        runtimeBridge = new IRuntimeBridge.Stub() {
            @Override public Bundle call(String method, Bundle request) {
                Bundle result = new Bundle();
                result.putBoolean("ready", true);
                result.putBoolean("scope_ready", true);
                result.putBoolean("scope_capture_ready", true);
                result.putBoolean("scope_link_probe_ready", true);
                result.putString("identity", acknowledgeCurrent ? framework.snapshot().identity : "old-identity");
                return result;
            }
        };
        RuntimeClient.testBridge(runtimeBridge);
    }

    @After public void cleanup() throws Exception {
        if (releaseWorker != null) releaseWorker.countDown();
        stopPublisher();
        RuntimeClient.testBridge(null);
        clearServiceHelper();
    }

    @Test public void savedOffChoicePublishesWithoutWaitingForMainLooperDebounce() throws Exception {
        config.setScopeLinkProbeEnabled(false);
        long before = SystemClock.uptimeMillis();

        publishSavedSettings();
        drainWorker();

        assertFalse("Saving OFF must update durable framework storage before the 100ms debounce",
                framework.snapshot().scopeLinkProbe);
        assertEquals("The test must not advance the main looper to publish", before, SystemClock.uptimeMillis());
        assertEquals(RuntimeSnapshot.parse(RuntimeSnapshot.from(context)).identity, framework.snapshot().identity);
    }

    @Test public void latestOffChoiceSupersedesAnOlderQueuedPublish() throws Exception {
        CountDownLatch workerBlocked = new CountDownLatch(1);
        releaseWorker = new CountDownLatch(1);
        worker.submit(() -> {
            workerBlocked.countDown();
            try {
                if (!releaseWorker.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker barrier timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(workerBlocked.await(5, TimeUnit.SECONDS));
        publishSavedSettings();
        config.setScopeLinkProbeEnabled(false);
        publishSavedSettings();

        releaseWorker.countDown();
        drainWorker();
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        drainWorker();

        assertFalse(framework.snapshot().scopeLinkProbe);
        assertFalse("An obsolete queued ON snapshot must never be written after the newer save",
                framework.writtenProbeChoices.contains(Boolean.TRUE));
        assertEquals(RuntimeSnapshot.parse(RuntimeSnapshot.from(context)).identity, framework.snapshot().identity);
    }

    @Test public void failedRealRemoteCommitRetriesEvenThoughItsMemoryCacheAlreadyChanged() throws Exception {
        framework.failuresRemaining.set(1);
        config.setScopeLinkProbeEnabled(false);
        publishSavedSettings();
        drainWorker();

        assertTrue("A failed Binder update must leave durable storage unchanged", framework.snapshot().scopeLinkProbe);
        String cached = service.getRemotePreferences(RuntimeSnapshot.GROUP).getString(RuntimeSnapshot.KEY, null);
        assertFalse("Exercise real RemotePreferences cache mutation before failed commit",
                RuntimeSnapshot.parse(cached).scopeLinkProbe);
        assertFalse(RuntimePublisher.status().contains("系统已确认"));
        assertEquals(1, framework.updateAttempts.get());

        ShadowLooper.idleMainLooper(249, TimeUnit.MILLISECONDS);
        drainWorker();
        assertEquals("Failed writes must wait for the bounded retry delay", 1, framework.updateAttempts.get());
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS);
        drainWorker();

        assertFalse("Retry must reach durable storage despite cache equality", framework.snapshot().scopeLinkProbe);
        assertEquals(2, framework.updateAttempts.get());
    }

    @Test public void exhaustedWriteRetriesStopAndRequestManualRetry() throws Exception {
        framework.failuresRemaining.set(6);
        config.setScopeLinkProbeEnabled(false);
        publishSavedSettings();
        drainWorker();

        for (int retry = 0; retry < 5; retry++) {
            ShadowLooper.idleMainLooper(250L << retry, TimeUnit.MILLISECONDS);
            drainWorker();
        }

        assertEquals("One initial write and five retries exhaust the bounded attempts", 6,
                framework.updateAttempts.get());
        assertTrue("Failed writes must leave the durable ON choice untouched", framework.snapshot().scopeLinkProbe);
        assertFalse(RuntimePublisher.status().contains("系统已确认"));
        assertTrue("Exhaustion must tell the user to retry manually", RuntimePublisher.status().contains("手动重试"));
        assertFalse("No automatic retry remains after exhaustion", RuntimePublisher.status().contains("等待重试"));

        ShadowLooper.idleMainLooper(8_000, TimeUnit.MILLISECONDS);
        drainWorker();
        assertEquals("Retry exhaustion must not leave a seventh write scheduled", 6, framework.updateAttempts.get());
    }

    @Test public void frameworkSaveDoesNotClaimSystemConfirmationUntilIdentityMatches() throws Exception {
        config.setScopeLinkProbeEnabled(false);
        publishSavedSettings();
        drainWorker();
        assertFalse(framework.snapshot().scopeLinkProbe);
        assertFalse(RuntimePublisher.status().contains("系统已确认"));

        ShadowLooper.idleMainLooper();
        drainWorker();
        assertFalse("A ready runtime with an old identity has not confirmed this save",
                RuntimePublisher.status().contains("系统已确认"));

        acknowledgeCurrent = true;
        ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS);
        drainWorker();
        assertTrue("Only the matching runtime identity confirms the saved configuration",
                RuntimePublisher.status().contains("系统已确认"));
    }

    @Test public void reconnectPublishesLatestOffChoiceThatWasSavedWhileDisconnected() throws Exception {
        field(RuntimePublisher.class, "service").set(publisher, null);
        config.setScopeLinkProbeEnabled(false);
        publishSavedSettings();
        drainWorker();
        assertTrue(framework.snapshot().scopeLinkProbe);
        assertFalse(RuntimePublisher.status().contains("系统已确认"));

        XposedServiceHelper.OnServiceListener listener =
                (XposedServiceHelper.OnServiceListener) field(XposedServiceHelper.class, "mListener").get(null);
        assertNotNull(listener);
        listener.onServiceBind(service);
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        drainWorker();

        assertFalse(framework.snapshot().scopeLinkProbe);
        assertEquals(RuntimeSnapshot.parse(RuntimeSnapshot.from(context)).identity, framework.snapshot().identity);
    }

    @Test public void confirmationRecoversWhenRuntimeConnectsAfterInitialTimeout() throws Exception {
        RuntimeClient.testBridge(null);
        acknowledgeCurrent = true;
        publishSavedSettings();
        drainWorker();
        advanceMainAndWorker(0);
        assertFalse(RuntimePublisher.status().contains("系统已确认"));

        for (long delay : new long[]{250, 500, 1000, 750}) advanceMainAndWorker(delay);
        RuntimeClient.connect(context, null);
        replyToLatestRuntimeBind();
        advanceMainAndWorker(1250);

        assertTrue("Runtime reconnect must resume confirmation without another settings save",
                RuntimePublisher.status().contains("系统已确认"));
    }

    @Test public void latestSaveConfirmsWhileInitialConnectionIsInFlight() throws Exception {
        RuntimeClient.testBridge(null);
        acknowledgeCurrent = true;
        publishSavedSettings();
        drainWorker();
        advanceMainAndWorker(0);

        config.setScopeLinkProbeEnabled(false);
        publishSavedSettings();
        drainWorker();
        advanceMainAndWorker(0);
        assertFalse("An unconnected runtime has not confirmed the latest save",
                RuntimePublisher.status().contains("系统已确认"));

        replyToLatestRuntimeBind();
        advanceMainAndWorker(250);

        assertFalse(framework.snapshot().scopeLinkProbe);
        assertTrue("The newer save must confirm even when its connect callback would be dropped",
                RuntimePublisher.status().contains("系统已确认"));
    }

    @Test public void confirmationStopsWhenRuntimeNeverReplies() throws Exception {
        RuntimeClient.testBridge(null);
        publishSavedSettings();
        drainWorker();
        advanceMainAndWorker(0);

        for (long delay : new long[]{250, 500, 1000, 2000, 4000}) advanceMainAndWorker(delay);

        String terminalStatus = RuntimePublisher.status();
        assertTrue("No runtime reply must end confirmation with a recoverable status",
                terminalStatus.contains("系统尚未就绪"));
        assertFalse(terminalStatus.contains("系统已确认"));
        int binds = runtimeBindCommands().size();
        advanceMainAndWorker(16_000);
        assertEquals("Exhausted confirmation must stop retrying", binds, runtimeBindCommands().size());
        assertEquals(terminalStatus, RuntimePublisher.status());
    }

    private void advanceMainAndWorker(long millis) throws Exception {
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS);
        drainWorker();
        ShadowLooper.idleMainLooper();
        drainWorker();
    }

    private List<Intent> runtimeBindCommands() {
        List<Intent> commands = new ArrayList<>();
        ShadowApplication application = Shadow.extract(RuntimeEnvironment.getApplication());
        for (Intent command : application.getBroadcastIntents())
            if (RuntimeClient.BIND.equals(command.getAction())) commands.add(command);
        return commands;
    }

    @SuppressWarnings("deprecation")
    private void replyToLatestRuntimeBind() throws Exception {
        List<Intent> commands = runtimeBindCommands();
        assertFalse("Confirmation must request the runtime Binder", commands.isEmpty());
        ResultReceiver reply = commands.get(commands.size() - 1).getParcelableExtra("reply");
        assertNotNull(reply);
        Bundle result = new Bundle(); result.putBinder("bridge", runtimeBridge.asBinder());
        reply.send(1, result);
        advanceMainAndWorker(0);
    }

    private void publishSavedSettings() throws Exception {
        Method method;
        try {
            method = RuntimePublisher.class.getMethod("publishSavedSettings", Context.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("Saving settings must immediately enqueue RuntimePublisher.publishSavedSettings(Context)", missing);
        }
        try {
            method.invoke(null, context);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("Saved-settings publication failed before it could be queued", failure.getCause());
        }
    }

    private void drainWorker() throws Exception {
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private static void stopPublisher() throws Exception {
        RuntimePublisher active = (RuntimePublisher) field(RuntimePublisher.class, "instance").get(null);
        if (active == null) return;
        ((java.util.concurrent.atomic.AtomicLong) field(RuntimePublisher.class, "generation").get(active)).incrementAndGet();
        Handler handler = (Handler) field(RuntimePublisher.class, "main").get(active);
        handler.removeCallbacksAndMessages(null);
        SharedPreferences.OnSharedPreferenceChangeListener changed =
                (SharedPreferences.OnSharedPreferenceChangeListener) field(RuntimePublisher.class, "changed").get(active);
        for (String name : List.of("config", "rules"))
            ((SharedPreferences) field(RuntimePublisher.class, name).get(active)).unregisterOnSharedPreferenceChangeListener(changed);
        ExecutorService executor = (ExecutorService) field(RuntimePublisher.class, "worker").get(active);
        executor.shutdownNow();
        assertTrue("Publisher worker leaked into the next test", executor.awaitTermination(5, TimeUnit.SECONDS));
        handler.removeCallbacksAndMessages(null);
        field(RuntimePublisher.class, "instance").set(null, null);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void clearServiceHelper() throws Exception {
        field(XposedServiceHelper.class, "mListener").set(null, null);
        Set<?> cache = (Set<?>) field(XposedServiceHelper.class, "mCache").get(null);
        synchronized (cache) { cache.clear(); }
    }

    /** Keep libxposed's real cache/editor; replace only the external framework Binder. */
    private static final class FakeFramework implements InvocationHandler {
        private final Map<String, Object> durable = new HashMap<>();
        private final List<Boolean> writtenProbeChoices = new ArrayList<>();
        private final AtomicInteger failuresRemaining = new AtomicInteger();
        private final AtomicInteger updateAttempts = new AtomicInteger();
        private final Binder binder = new Binder();

        FakeFramework(String raw) { durable.put(RuntimeSnapshot.KEY, raw); }

        XposedService service() throws Exception {
            Class<?> type = Class.forName("io.github.libxposed.service.IXposedService");
            Object remote = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this);
            var constructor = XposedService.class.getDeclaredConstructor(type);
            constructor.setAccessible(true);
            return constructor.newInstance(remote);
        }

        synchronized RuntimeSnapshot snapshot() {
            return RuntimeSnapshot.parse((String) durable.get(RuntimeSnapshot.KEY));
        }

        @Override public synchronized Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getApiVersion" -> 102;
                case "asBinder" -> binder;
                case "toString" -> "FakeFramework";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "requestRemotePreferences" -> {
                    assertEquals(RuntimeSnapshot.GROUP, args[0]);
                    Bundle result = new Bundle();
                    result.putSerializable("map", new HashMap<>(durable));
                    yield result;
                }
                case "updateRemotePreferences" -> {
                    assertEquals(RuntimeSnapshot.GROUP, args[0]);
                    updateAttempts.incrementAndGet();
                    if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
                        throw new RemoteException("Injected durable-storage failure");
                    Bundle changes = (Bundle) args[1];
                    if (changes.getBoolean("clear")) durable.clear();
                    if (changes.getSerializable("delete") instanceof Set<?> removed)
                        for (Object key : removed) durable.remove(key);
                    if (changes.getSerializable("put") instanceof Map<?, ?> put)
                        for (var entry : put.entrySet()) durable.put((String) entry.getKey(), entry.getValue());
                    writtenProbeChoices.add(snapshot().scopeLinkProbe);
                    yield null;
                }
                default -> throw new AssertionError("Unexpected framework call: " + method);
            };
        }
    }
}
