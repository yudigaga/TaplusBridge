package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel;
import io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge;
import io.github.yudigaga.taplusbridge.runtime.RuntimeClient;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

/** Runs the real host action through the final snapshot, Provider request and chooser Binder call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class HostDragDeliveryAndroidTest {
    private static final long EPOCH = 7, GENERATION = 3;
    private static final String URL = "https://example.com/first";
    private static final String SECOND_URL = "https://example.org/second";
    private static final String TOKEN = "a".repeat(43), READY_TOKEN = "b".repeat(43);
    private HostDragHooks host;
    private RecognitionHooks recognitionHooks;
    private HostRecognitionEnhancer enhancer;
    private RecognitionEpochs origins;
    private RecordingProvider provider;
    private ActivityController<Activity> activity;
    private ExecutorService worker;
    private FakeService service;
    private FakeResult result;
    private FakeExpandedCard card;
    private FakeTextCard textCard;
    private Object pending;
    private IRuntimeBridge savedBridge;
    private Consumer<String> onLog = ignored -> { };
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Bundle> choices = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> workerReleases = new ArrayList<>();
    private final Map<String, XposedInterface.Hooker> lifecycleHooks = new HashMap<>();

    @Before public void setup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        FakeRecognitionHost.context = context;
        provider = Robolectric.buildContentProvider(RecordingProvider.class).create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
        enhancer = new HostRecognitionEnhancer(Model.class);
        recognitionHooks = new RecognitionHooks(null, () -> "com.example.source", enhancer,
                FakeRecognitionHost.class.getDeclaredMethod("application"),
                FakeRecognitionHost.class.getDeclaredMethod("publish", ArrayList.class));
        result = new FakeResult();
        service = new FakeService();
        field(RecognitionHooks.class, "active").setBoolean(recognitionHooks, true);
        field(RecognitionHooks.class, "epoch").setLong(recognitionHooks, EPOCH);
        field(RecognitionHooks.class, "dragPick").set(recognitionHooks,
                new RecognitionHooks.DragPick(EPOCH, service, result, SystemClock.elapsedRealtime()));
        field(RecognitionHooks.class, "recommendationTask").set(recognitionHooks,
                FakeRecognitionHost.class.getDeclaredField("task"));
        origins = (RecognitionEpochs) field(RecognitionHooks.class, "origins").get(recognitionHooks);
        origins.reset(EPOCH);
        FakeRecognitionHost.task = new FakeTask();
        assertTrue(origins.bind(FakeRecognitionHost.task, EPOCH));

        XposedModule module = new XposedModule() { };
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (target, method, args) -> {
                    if ("log".equals(method.getName())) {
                        String message = (String) args[2]; events.add(message); onLog.accept(message);
                    }
                    if ("hook".equals(method.getName())) {
                        Method hooked = (Method) args[0];
                        return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                                new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, operation, values) -> {
                                    if ("intercept".equals(operation.getName())) {
                                        lifecycleHooks.put(hooked.getName(), (XposedInterface.Hooker) values[0]);
                                        return null;
                                    }
                                    return builder;
                                });
                    }
                    return null;
                });
        module.attachFramework(framework, () -> { });
        Constructor<HostDragHooks> constructor = HostDragHooks.class.getDeclaredConstructor(
                XposedModule.class, ClassLoader.class, RecognitionHooks.class);
        constructor.setAccessible(true);
        host = constructor.newInstance(module, fixtureLoader(), recognitionHooks);
        Method install = HostDragHooks.class.getDeclaredMethod("install", ClassLoader.class);
        install.setAccessible(true); install.invoke(host, fixtureLoader());
        service.hooks = lifecycleHooks;
        worker = (ExecutorService) field(HostDragHooks.class, "worker").get(host);
        ((AtomicLong) field(HostDragHooks.class, "generation").get(host)).set(GENERATION);

        activity = Robolectric.buildActivity(Activity.class).setup().visible();
        View attached = new View(activity.get());
        activity.get().setContentView(attached);
        assertTrue(attached.isAttachedToWindow());
        card = new FakeExpandedCard();
        textCard = card.mRecognitionAdapter.text;
        service.mMainFloatView = new FakeMainView(attached, card);
        FakeService.mCatcherResult = result;
        FakeService.sIsTaskFinished = false;

        savedBridge = (IRuntimeBridge) ((AtomicReference<?>) field(RuntimeClient.class, "remote").get(null)).get();
        installBridge(new IRuntimeBridge.Stub() {
            @Override public Bundle call(String method, Bundle request) {
                if (!"drag_choose".equals(method)) return null;
                choices.add(copyRequest(request));
                Bundle response = new Bundle(); response.putBoolean("accepted", true); return response;
            }
        });
        arm(false);
    }

    @After public void cleanup() throws Exception {
        for (CountDownLatch release : workerReleases) release.countDown();
        if (host != null) {
            ((Handler) field(HostDragHooks.class, "main").get(host)).removeCallbacksAndMessages(null);
            HostLongPressDrag longPress = (HostLongPressDrag) field(HostDragHooks.class, "longPress").get(host);
            longPress.close(service);
            ((ScheduledExecutorService) field(HostLongPressDrag.class, "worker").get(longPress)).shutdownNow();
        }
        if (worker != null) worker.shutdownNow();
        installBridge(savedBridge);
        if (activity != null) activity.pause().stop().destroy();
        FakeRecognitionHost.context = null; FakeRecognitionHost.task = null;
        FakeService.mCatcherResult = null; FakeService.sIsTaskFinished = false;
    }

    @Test public void recommendationArrivingBeforeTheFinalSnapshotIsIncludedInTheRealCompletion() throws Exception {
        enhancer.captureText(URL, "assist");
        AtomicBoolean added = new AtomicBoolean();
        // This deterministic callback falls between perform's ready snapshot and completeRecognition's final snapshot.
        onLog = message -> {
            if (message.contains("stage=recognition_ready") && added.compareAndSet(false, true))
                addRecommendation(SECOND_URL);
        };
        perform(); finishDelivery();
        assertTrue(added.get());
        assertEquals(AsyncTask.Status.PENDING, FakeRecognitionHost.task.getStatus());
        assertEquals("Current ready and final-snapshot path has four policy IPC reads", 4, provider.policyReads.get());
        assertDelivered("recognized words", List.of(URL, SECOND_URL));
    }

    @Test public void recommendationArrivingAfterTheFinalSnapshotCannotRewriteTheQueuedRequest() throws Exception {
        enhancer.captureText(URL, "assist");
        CountDownLatch release = blockWorker();
        perform();
        assertNull(field(HostDragHooks.class, "pending").get(host));
        assertTrue(provider.completions.isEmpty());
        addRecommendation(SECOND_URL);
        assertEquals(List.of(URL, SECOND_URL), recognitionHooks.dragSnapshot(service, result, EPOCH).links());
        release.countDown(); finishDelivery();
        assertDelivered("recognized words", List.of(URL));
    }

    @Test public void emptyPendingRecommendationWaitsThenCompletesOnceWithNoLinks() throws Exception {
        perform(); assertNoCompletion();
        assertSame(pending, field(HostDragHooks.class, "pending").get(host));
        finishRecommendation();
        perform(); finishDelivery();
        assertDelivered("recognized words", List.of());
    }

    @Test public void malformedEntriesCannotReleaseOrEnterTheRealCompletion() throws Exception {
        capturedEntries().addAll(List.of("javascript:alert(1)", "https://example.com/%0Ahidden"));
        perform(); assertNoCompletion();
        finishRecommendation();
        perform(); finishDelivery();
        assertDelivered("recognized words", List.of());
    }

    @Test public void theRealCompletionFiltersBeforeItsSixtyFourLinkLimit() throws Exception {
        List<String> expected = new ArrayList<>();
        capturedEntries().addAll(List.of("javascript:alert(1)", "https://example.com/%0Ahidden"));
        for (int i = 0; i < 65; i++) {
            String url = "https://example.net/card/" + i;
            capturedEntries().add(url);
            if (i < 64) expected.add(url);
        }
        perform(); finishDelivery();
        assertDelivered("recognized words", expected);
    }

    @Test public void validDownloadLinksReachTheRealCompletionWithoutBeingTruncatedToRuleKeys() throws Exception {
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
        String ed2k = "ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/";
        enhancer.captureProbeUrl(magnet); enhancer.captureProbeUrl(ed2k);
        perform(); finishDelivery();
        assertDelivered("recognized words", List.of(magnet, ed2k));
    }

    @Test public void disabledServerCompletesAnEmptyDragWithoutWaitingForItsPendingTask() throws Exception {
        provider.channels = provider.channels.with(Channel.SERVER, false);
        perform(); finishDelivery();
        assertEquals(AsyncTask.Status.PENDING, FakeRecognitionHost.task.getStatus());
        assertDelivered("recognized words", List.of());
    }

    @Test public void anOldRecommendationCannotHoldTheCurrentEmptyCompletion() throws Exception {
        FakeRecognitionHost.task = new FakeTask();
        origins.reset(EPOCH - 1); assertTrue(origins.bind(FakeRecognitionHost.task, EPOCH - 1)); origins.reset(EPOCH);
        perform(); finishDelivery();
        assertDelivered("recognized words", List.of());
    }

    @Test public void boundOldEpochCannotDeliverANewPicksLinksEvenWhenServiceAndResultAreReused() throws Exception {
        perform(); assertNoCompletion();
        assertEquals(EPOCH, field(pending.getClass(), "recognitionEpoch").getLong(pending));
        field(RecognitionHooks.class, "epoch").setLong(recognitionHooks, EPOCH + 1);
        field(RecognitionHooks.class, "dragPick").set(recognitionHooks,
                new RecognitionHooks.DragPick(EPOCH + 1, service, result, SystemClock.elapsedRealtime()));
        origins.reset(EPOCH + 1); enhancer.clear(); addRecommendation(SECOND_URL);
        perform(); assertNoCompletion();
        assertNull(field(HostDragHooks.class, "pending").get(host));
    }

    @Test public void replacedGenerationCannotSendAnAlreadyFrozenCompletion() throws Exception {
        enhancer.captureText(URL, "assist");
        CountDownLatch release = blockWorker();
        perform();
        ((AtomicLong) field(HostDragHooks.class, "generation").get(host)).incrementAndGet();
        release.countDown(); finishDelivery();
        assertNoCompletion();
    }

    @Test public void replacedNativeResultCannotLaunchTheChooserAfterCompletion() throws Exception {
        enhancer.captureText(URL, "assist");
        perform(); flushWorker();
        assertEquals(1, provider.completions.size());
        FakeService.mCatcherResult = new FakeResult();
        ShadowLooper.idleMainLooper(); flushWorker();
        assertTrue(choices.isEmpty());
    }

    @Test public void externalCancelAfterTheFinalSnapshotCannotSendTheQueuedCompletion() throws Throwable {
        enhancer.captureText(URL, "assist");
        CountDownLatch release = blockWorker();
        perform();
        service.cancelTask();
        release.countDown(); finishDelivery();
        assertEquals(1, service.cancelCalls);
        assertNoCompletion();
    }

    @Test public void externalDestroyAfterCompletionCannotLaunchTheQueuedChooser() throws Throwable {
        enhancer.captureText(URL, "assist");
        perform(); flushWorker();
        assertEquals(1, provider.completions.size());
        service.onDestroy();
        ShadowLooper.idleMainLooper(); flushWorker();
        assertEquals(1, service.destroyCalls);
        assertTrue(choices.isEmpty());
    }

    @Test public void externalCancelAfterCompletionCannotLaunchTheQueuedChooser() throws Throwable {
        enhancer.captureText(URL, "assist");
        perform(); flushWorker();
        assertEquals(1, provider.completions.size());
        service.cancelTask();
        ShadowLooper.idleMainLooper(); flushWorker();
        assertEquals(1, service.cancelCalls);
        assertTrue(choices.isEmpty());
    }

    @Test public void moduleOwnedNativeCancellationStillAllowsItsAuthorizedChooser() throws Exception {
        arm(false, false); enhancer.captureText(URL, "assist");
        perform(); finishDelivery();
        assertEquals(1, service.cancelCalls);
        assertDelivered("recognized words", List.of(URL));
    }

    @Test public void aUsableLinkNeverSkipsEnabledSegmentationLoading() throws Exception {
        enhancer.captureText(URL, "assist"); textCard.loading = true;
        perform(); assertNoCompletion();
        textCard.loading = false;
        perform(); finishDelivery();
        assertDelivered("recognized words", List.of(URL));
    }

    @Test public void aTextDragStillNeedsItsEnabledSegmentsEvenWithAUsableLink() throws Exception {
        enhancer.captureText(URL, "assist"); textCard.count = 0;
        perform(); assertNoCompletion();
        textCard.count = 1;
        perform(); finishDelivery();
        assertDelivered("recognized words", List.of(URL));
    }

    @Test public void enabledImageSegmentsStillNeedTheNativeRefreshWhenThereAreNoWords() throws Exception {
        arm(true); enhancer.captureText(URL, "assist"); textCard.count = 0;
        textCard.mSegmentAdapter.words = "";
        card.refreshCompletes = false;
        perform(); assertNoCompletion();
        assertEquals(1, card.refreshCalls);
        card.refreshed = true;
        perform(); finishDelivery();
        assertDelivered("", List.of(URL));
    }

    @Test public void disabledSegmentsStillWaitForEnabledImageOcrEvenWithAUsableLink() throws Exception {
        arm(true); provider.channels = provider.channels.with(Channel.SEGMENT, false);
        enhancer.captureText(URL, "assist");
        perform(); assertNoCompletion();
        field(RecognitionHooks.class, "ocrText").set(recognitionHooks, "image OCR words");
        field(RecognitionHooks.class, "ocrFinished").setBoolean(recognitionHooks, true);
        perform(); finishDelivery();
        assertDelivered("image OCR words", List.of(URL));
    }

    @Test public void disabledImageOcrAndSegmentsDoNotHoldAUsableLinkForEitherJob() throws Exception {
        arm(true);
        provider.channels = provider.channels.with(Channel.SEGMENT, false).with(Channel.OCR, false);
        enhancer.captureText(URL, "assist"); textCard.loading = true;
        perform(); finishDelivery();
        assertFalse(field(RecognitionHooks.class, "ocrFinished").getBoolean(recognitionHooks));
        assertDelivered("", List.of(URL));
    }

    @Test public void disabledTextSegmentsDoNotWaitForImageOcr() throws Exception {
        provider.channels = provider.channels.with(Channel.SEGMENT, false);
        enhancer.captureText(URL, "assist"); textCard.loading = true;
        perform(); finishDelivery();
        assertEquals("Current disabled-segment path also has four policy IPC reads", 4, provider.policyReads.get());
        assertDelivered("", List.of(URL));
    }

    @Test public void disablingOcrAndSegmentsStillDoesNotSkipTheEmptyCurrentServerRecommendation() throws Exception {
        arm(true);
        provider.channels = provider.channels.with(Channel.SEGMENT, false).with(Channel.OCR, false);
        perform(); assertNoCompletion();
        finishRecommendation();
        perform(); finishDelivery();
        assertDelivered("", List.of());
    }

    @Test public void legacyPolicyConstructionKeepsConsumerSemanticsWithoutInventingPublicationIdentity() {
        RecognitionChannels channels = RecognitionChannels.defaults().with(Channel.SEGMENT, false);
        RecognitionPolicy.State managed = new RecognitionPolicy.State(channels, true);
        RecognitionPolicy.State unmanaged = new RecognitionPolicy.State(RecognitionChannels.disabled(), false);
        assertSame(channels, managed.channels());
        assertTrue(managed.disabled(Channel.SEGMENT));
        assertFalse(unmanaged.disabled(Channel.SEGMENT));
        assertFalse(unmanaged.commerceDisabled());
        for (RecognitionPolicy.State state : List.of(managed, unmanaged)) {
            assertEquals("", state.identity()); assertEquals(0, state.readMillis());
            assertTrue(state.diagnosticFields().contains("policy_identity=unavailable"));
        }
    }

    @Test public void unavailablePolicyMeasuresOnlyItsAttemptedCallAndNullContextDoesNotAttemptOne() {
        provider.policyMillis = 25;
        provider.policyFailure = new IllegalStateException("private-policy-body");
        RecognitionPolicy.State failed = RecognitionPolicy.current(FakeRecognitionHost.context);
        assertEquals(25, failed.readMillis()); assertEquals("transport_unavailable", failed.status());
        assertFalse(failed.managed());
        RecognitionPolicy.State absent = RecognitionPolicy.current(null);
        assertEquals(0, absent.readMillis()); assertEquals("context_unavailable", absent.status());
        assertEquals(1, provider.policyReads.get());
        assertFalse(failed.diagnosticFields().contains("private-policy-body"));
    }

    @Test public void segmentReadyLogsAreSampledUntilLoadingRestartsWhileRecommendationsArePending() throws Exception {
        perform(); ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        assertEquals(1, stages("ocr_segment_ready").size()); assertNoCompletion();
        textCard.loading = true; ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        textCard.loading = false; ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
        assertEquals(2, stages("ocr_segment_ready").size()); assertNoCompletion();
        enhancer.captureText(URL, "assist"); perform(); finishDelivery();
        assertEquals(2, stages("ocr_segment_ready").size());
        assertDelivered("recognized words", List.of(URL));
    }

    @Test public void disabledSegmentReadyLogsAreSampledUntilOcrWaitRestarts() throws Exception {
        arm(true); provider.channels = provider.channels.with(Channel.SEGMENT, false);
        field(RecognitionHooks.class, "ocrFinished").setBoolean(recognitionHooks, true);
        perform(); ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        assertEquals(1, stages("ocr_segment_ready").size()); assertNoCompletion();
        field(RecognitionHooks.class, "ocrFinished").setBoolean(recognitionHooks, false);
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        field(RecognitionHooks.class, "ocrFinished").setBoolean(recognitionHooks, true);
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
        assertEquals(2, stages("ocr_segment_ready").size()); assertNoCompletion();
        enhancer.captureText(URL, "assist"); perform(); finishDelivery();
        assertEquals(2, stages("ocr_segment_ready").size());
        assertDelivered("", List.of(URL));
    }

    @Test public void readyLogsRecordAnEnabledChannelChangeDuringTheSameRecommendationWait() throws Exception {
        perform();
        provider.channels = provider.channels.with(Channel.SEGMENT, false); perform(); perform();
        provider.channels = provider.channels.with(Channel.SEGMENT, true); perform(); perform();
        assertEquals(3, stages("ocr_segment_ready").size()); assertNoCompletion();
        enhancer.captureText(URL, "assist"); perform(); finishDelivery();
        assertDelivered("recognized words", List.of(URL));
    }

    @Test public void deepSleepCountsTowardElapsedDeliveryTimeButNotMainQueueLateness() throws Exception {
        enhancer.captureText(URL, "assist"); textCard.loading = true;
        perform(); assertNoCompletion();
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(5));
        textCard.loading = false;
        // This shadowed sleep preserves the elapsed/uptime gap; 4.14.1's absolute clock setter flattens it.
        SystemClock.sleep(100);
        ShadowLooper.idleMainLooper(); finishDelivery();
        String ready = stages("ocr_segment_ready").get(0);
        assertEquals(0, metric(ready, "main_queue_late_ms"));
        assertTrue(metric(ready, "elapsed_ms") >= 5_100);
        assertDelivered("recognized words", List.of(URL));
    }

    @Test public void completionIpcDurationExcludesLocalStageLogging() throws Exception {
        provider.completionMillis = 25;
        onLog = message -> {
            if (message.contains("stage=drag_complete_started ")) ShadowSystemClock.advanceBy(Duration.ofMillis(75));
        };
        enhancer.captureText(URL, "assist"); perform(); finishDelivery();
        assertEquals(25, metric(stages("drag_complete").get(0), "ipc_ms"));
        assertDelivered("recognized words", List.of(URL));
    }

    @Test public void synchronousCompletionFailureStillLogsOnlyItsCallDurationAndExceptionType() throws Exception {
        provider.completionMillis = 40;
        provider.completionFailure = new IllegalStateException("private-body " + TOKEN);
        enhancer.captureText(URL, "assist"); perform(); finishDelivery();
        assertEquals(1, stages("drag_complete").size());
        assertEquals(40, metric(stages("drag_complete").get(0), "ipc_ms"));
        assertEquals(1, provider.completions.size()); assertTrue(choices.isEmpty());
        assertTrue(events.stream().anyMatch(event -> event.endsWith("unavailable: IllegalStateException")));
        assertFalse(String.join("\n", events).contains("private-body"));
        assertFalse(String.join("\n", events).contains(TOKEN));
    }

    private List<String> stages(String name) {
        return events.stream().filter(event -> event.contains("stage=" + name + " ")).toList();
    }

    private static long metric(String event, String name) {
        for (String field : event.split(" ")) if (field.startsWith(name + "="))
            return Long.parseLong(field.substring(name.length() + 1));
        throw new AssertionError("Missing metric " + name + " in " + event);
    }

    private void arm(boolean image) throws Exception {
        arm(image, true);
    }

    private void arm(boolean image, boolean preserveNative) throws Exception {
        Class<?> type = Class.forName(HostDragHooks.class.getName() + "$Pending");
        Constructor<?> constructor = type.getDeclaredConstructor(long.class, Object.class, String.class,
                String.class, boolean.class, boolean.class, boolean.class, long.class);
        constructor.setAccessible(true);
        pending = constructor.newInstance(GENERATION, result, TOKEN, "share", true, image, preserveNative,
                SystemClock.elapsedRealtime() + 20_000);
        field(HostDragHooks.class, "pending").set(host, pending);
    }

    private void perform() throws Exception {
        Method method = HostDragHooks.class.getDeclaredMethod("perform", Context.class, Object.class, pending.getClass());
        method.setAccessible(true); method.invoke(host, FakeRecognitionHost.context, service, pending);
    }

    private void finishRecommendation() {
        FakeRecognitionHost.task.executeOnExecutor(Runnable::run);
        ShadowLooper.idleMainLooper();
        assertEquals(AsyncTask.Status.FINISHED, FakeRecognitionHost.task.getStatus());
    }

    private void addRecommendation(String url) {
        Model model = new Model(); model.detailUrl = url;
        enhancer.captureRecommendationLinks(List.of(model));
    }

    private void flushWorker() throws Exception { worker.submit(() -> { }).get(2, TimeUnit.SECONDS); }

    private void finishDelivery() throws Exception {
        flushWorker(); ShadowLooper.idleMainLooper(); flushWorker();
    }

    private void assertNoCompletion() throws Exception {
        flushWorker();
        assertTrue(provider.completions.isEmpty()); assertTrue(choices.isEmpty());
    }

    private void assertDelivered(String words, List<String> links) {
        assertEquals(1, provider.completions.size());
        Bundle request = provider.completions.get(0);
        assertEquals(TOKEN, request.getString("token"));
        assertEquals(words, request.getString("text"));
        assertEquals(links, request.getStringArrayList("links"));
        assertEquals(Set.of("token", "text", "links"), request.keySet());
        assertEquals(1, choices.size());
        assertEquals(READY_TOKEN, choices.get(0).getString("token"));
    }

    private CountDownLatch blockWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        workerReleases.add(release);
        worker.execute(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker was not released"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        return release;
    }

    @SuppressWarnings("unchecked") private Set<String> capturedEntries() throws Exception {
        return (Set<String>) field(HostRecognitionEnhancer.class, "links").get(enhancer);
    }

    private static void installBridge(IRuntimeBridge bridge) throws Exception {
        Method install = RuntimeClient.class.getDeclaredMethod("testBridge", IRuntimeBridge.class);
        install.setAccessible(true); install.invoke(null, bridge);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static Bundle copyRequest(Bundle request) {
        Bundle copy = new Bundle(request);
        ArrayList<String> links = request.getStringArrayList("links");
        if (links != null) copy.putStringArrayList("links", new ArrayList<>(links));
        return copy;
    }

    private static ClassLoader fixtureLoader() {
        Map<String, Class<?>> classes = Map.ofEntries(
                Map.entry("miui.contentcatcher.sdk.ClientCatcherResult", FakeResult.class),
                Map.entry("miui.contentcatcher.sdk.Token", FakeToken.class),
                Map.entry("miui.contentcatcher.sdk.listener.IContentListenerCallback", FakeListener.class),
                Map.entry("com.miui.contentextension.services.TextContentExtensionService", FakeService.class),
                Map.entry("com.miui.contentextension.text.floatview.BaseFloatView", FakeBaseView.class),
                Map.entry("com.miui.contentextension.text.floatview.TaplusMainFloatView", FakeMainView.class),
                Map.entry("com.miui.contentextension.text.floatview.TaplusSplashFloatView", FakeSplash.class),
                Map.entry("com.miui.contentextension.text.cardview.TaplusRecognitionExpandedCard", FakeExpandedCard.class),
                Map.entry("com.miui.contentextension.text.cardview.TaplusRecognitionExpandedTextCard", FakeTextCard.class),
                Map.entry("com.miui.contentextension.text.cardview.TaplusRecognitionExpandedImageCard", FakeImageCard.class),
                Map.entry("com.miui.contentextension.text.adapter.TaplusRecognitionAdapter", FakeAdapter.class),
                Map.entry("com.miui.contentextension.text.adapter.TaplusSegmentAdapter", FakeSegments.class));
        return new ClassLoader(HostDragDeliveryAndroidTest.class.getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                Class<?> fixture = classes.get(name); return fixture == null ? super.loadClass(name) : fixture;
            }
        };
    }

    public static final class RecordingProvider extends ContentProvider {
        RecognitionChannels channels = RecognitionChannels.defaults();
        final AtomicInteger policyReads = new AtomicInteger();
        final List<Bundle> completions = new CopyOnWriteArrayList<>();
        long completionMillis;
        RuntimeException completionFailure;
        long policyMillis;
        RuntimeException policyFailure;
        public boolean onCreate() { return true; }
        public Bundle call(String method, String arg, Bundle request) {
            Bundle response = new Bundle();
            if (RecognitionChannels.KEY.equals(method)) {
                policyReads.incrementAndGet();
                if (policyMillis > 0) ShadowSystemClock.advanceBy(Duration.ofMillis(policyMillis));
                if (policyFailure != null) throw policyFailure;
                response.putBoolean("active", true); response.putString("identity", "c".repeat(64));
                response.putString("channels", channels.toJson()); return response;
            }
            if ("drag_complete".equals(method)) {
                completions.add(copyRequest(request));
                if (completionMillis > 0) ShadowSystemClock.advanceBy(Duration.ofMillis(completionMillis));
                if (completionFailure != null) throw completionFailure;
                response.putString("token", READY_TOKEN); return response;
            }
            return null;
        }
        public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        public String getType(Uri u) { return null; }
        public Uri insert(Uri u, ContentValues v) { return null; }
        public int delete(Uri u, String s, String[] a) { return 0; }
        public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }

    public static final class FakeRecognitionHost {
        static Context context;
        public static FakeTask task;
        public static Context application() { return context; }
        public static void publish(ArrayList<?> ignored) { }
    }
    @SuppressWarnings("deprecation") public static final class FakeTask extends AsyncTask<Void, Void, String> {
        @Override protected String doInBackground(Void... ignored) { return ""; }
    }
    public static final class Model { public String title, cat, detailUrl, packageName, intent, sourceIcon; }
    public static final class FakeResult {
        private final Map<String, Object> properties = new HashMap<>();
        private final Map<String, Map<String, Object>> jobs = new HashMap<>();
        public FakeResult() { }
        public void setInjectorToken(FakeToken ignored) { }
        public Map<String, Object> getPropertyMap() { return properties; }
        public Map<String, Object> getParams(String job) { return jobs.getOrDefault(job, Map.of()); }
        public void addContent(String job, String name, Object value) {
            jobs.computeIfAbsent(job, ignored -> new HashMap<>()).put(name, value);
        }
    }
    public static final class FakeToken { public FakeToken(String a, String b, String c, int d) { } }
    public static final class FakeListener { public void onContentReceived(FakeResult ignored) { } }
    public static final class FakeService {
        public static FakeResult mCatcherResult;
        public static boolean sIsTaskFinished;
        public FakeListener mCallback = new FakeListener();
        public FakeMainView mMainFloatView;
        public FakeSplash mSplashFloatView;
        Map<String, XposedInterface.Hooker> hooks = Map.of();
        int cancelCalls, destroyCalls;
        public int onStartCommand(Intent intent, int flags, int startId) { return 0; }
        public void onStartPick() { }
        public void cancelTask() throws Throwable { dispatchLifecycle("cancelTask"); }
        public void onDestroy() throws Throwable { dispatchLifecycle("onDestroy"); }
        public void showMainFloatView(boolean ignored) { }
        private void dispatchLifecycle(String name) throws Throwable {
            XposedInterface.Hooker hook = hooks.get(name);
            assertNotNull("The real host lifecycle interceptor must be installed", hook);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (target, method, args) -> switch (method.getName()) {
                        case "getThisObject" -> this;
                        case "proceed" -> {
                            if ("cancelTask".equals(name)) cancelCalls++; else destroyCalls++;
                            yield null;
                        }
                        default -> throw new AssertionError(method.getName());
                    });
            hook.intercept(chain);
        }
    }
    public static class FakeBaseView { public View mMainView; }
    public static final class FakeMainView extends FakeBaseView {
        public FakeExpandedCard mRecognitionCard;
        FakeMainView(View view, FakeExpandedCard card) { mMainView = view; mRecognitionCard = card; }
    }
    public static final class FakeSplash { public void hideSplashFloatView(boolean ignored) { } }
    public static final class FakeExpandedCard {
        public FakeAdapter mRecognitionAdapter = new FakeAdapter();
        boolean refreshed, refreshCompletes = true;
        int refreshCalls;
        public void doRefreshImageRecognition() { refreshCalls++; if (refreshCompletes) refreshed = true; }
        public boolean hasRefreshed() { return refreshed; }
        public void doSwitchRecognition(boolean ignored) { }
    }
    public static final class FakeAdapter {
        final FakeTextCard text = new FakeTextCard();
        final FakeImageCard image = new FakeImageCard();
        public Object getItem(int position) { return position == 0 ? text : image; }
    }
    public static final class FakeTextCard {
        public TextView mTranslate;
        public View mSelectAll;
        public FakeSegments mSegmentAdapter = new FakeSegments();
        boolean loading;
        int count = 1;
        public boolean isLoading() { return loading; }
        public String getSelectedWords() { return mSegmentAdapter.getSelectedWords(); }
        public int getSegmentCount() { return count; }
        public int getSelectedNumber() { return count; }
    }
    public static final class FakeImageCard { public TextView mRecognizeObject; }
    public static final class FakeSegments {
        String words = "recognized words";
        public String getSelectedWords() { return words; }
    }
}
