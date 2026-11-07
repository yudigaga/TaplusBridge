package io.github.yudigaga.taplusbridge.probe;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ScopeLinkProbeAndroidTest {
    @Test public void protocolLaunchesAreCapturedOnlyAsUniquePlainIntents() {
        for (String link : List.of("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                "ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/")) {
            Intent request = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            assertEquals(link, ScopeLinkProbe.strictHttp(request, SOURCE));
            assertNull(ScopeLinkProbe.strictHttp(new Intent(request).putExtra("url", URL), SOURCE));
            assertNull(ScopeLinkProbe.strictHttp(new Intent(request).putExtra("url", "magnet:?dn=incomplete"), SOURCE));
            assertNull(ScopeLinkProbe.strictHttp(new Intent(request).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), SOURCE));
            Intent withClip = new Intent(request); withClip.setClipData(ClipData.newPlainText("other", "private"));
            assertNull(ScopeLinkProbe.strictHttp(withClip, SOURCE));
        }
    }

    private static final String SOURCE = "com.android.chrome", URL = "https://example.com/repository";
    private static final int UID = 21001;
    private Context context;
    private ConfigStore config;
    private ScopeCaptureWindow.Snapshot snapshot;
    private ScopeCaptureWindow windows;
    private ScopeCaptureWindowAndroidTest.Atms atms;
    private ScopeCaptureWindowAndroidTest.Window main;
    private PhysicalTouchState physical;
    private AtomicReference<RuntimeSnapshot> current;
    private ScopeLinkProbe probe;
    private long down, began;
    private final List<Integer> actions = new ArrayList<>();
    private final List<String> completed = new ArrayList<>(), logs = new ArrayList<>();
    private Consumer<Integer> duringTap = action -> {};
    private boolean windowValid = true, inputAccepted = true;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", 0).edit().clear().commit();
        config = new ConfigStore(context);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, Set.of(), Set.of(), false, true));
        current = new AtomicReference<>(RuntimeSnapshot.parse(RuntimeSnapshot.from(context)));
        prepareSource(SOURCE);
    }

    private void prepareSource(String source) throws Exception {
        atms = new ScopeCaptureWindowAndroidTest.Atms();
        main = new ScopeCaptureWindowAndroidTest.Window(); main.mActivityRecord.main = main;
        main.mAttrs.packageName = source;
        main.mActivityRecord.mActivityComponent = new ComponentName(source, source + ".MainActivity");
        atms.mWindowManager.mRoot.display.hit = main; atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        windows = new ScopeCaptureWindow(ScopeCaptureWindowAndroidTest.Atms.class,
                new ScopeCaptureWindow.CaptureBackend() {
                    public Class<?> surfaceType() { return Object.class; }
                    public android.graphics.Bitmap capture(Object layer, Object[] excluded, int uid, android.graphics.Rect crop, float scale) {
                        throw new AssertionError("A link probe must not take a screenshot");
                    }
                });
        snapshot = windows.inspect(atms, 0, 50, 200);
        physical = new PhysicalTouchState();
        probe = new ScopeLinkProbe(new Handler(Looper.getMainLooper()), physical, current::get,
                picked -> { assertSame(snapshot, picked); if (!windowValid) throw new IllegalStateException("window_changed"); },
                (picked, action, at) -> { actions.add(action); duringTap.accept(action); return inputAccepted; }, logs::add);
        down = SystemClock.uptimeMillis(); began = SystemClock.elapsedRealtime();
        physical.event(0, 1, down, down, MotionEvent.ACTION_DOWN, down);
    }

    private void arm() { probe.arm(snapshot, current.get(), down, began, 500, 1); }
    private void release() {
        physical.event(0, 1, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, SystemClock.uptimeMillis());
        physical.markReleasedElapsed(SystemClock.elapsedRealtime());
        probe.pointer(MotionEvent.ACTION_UP, 1, snapshot.x, snapshot.y, down, 20);
    }
    private void hold() { ShadowSystemClock.advanceBy(Duration.ofMillis(2_600)); release(); }
    private void drain() { ShadowLooper.idleMainLooper(); }
    private Intent link() { return new Intent(Intent.ACTION_VIEW, Uri.parse(URL)); }
    private boolean intercept(Intent intent, boolean ordinary) { return probe.intercept(UID, snapshot.source, 0, intent, ordinary); }
    private void confirm() { probe.confirm(snapshot, current.get(), completed::add); }

    private void confirmDrag(ScopeCaptureWindow.Snapshot target, long actualDown) throws Exception {
        java.lang.reflect.Method method;
        try { method = ScopeLinkProbe.class.getMethod("confirmDrag", ScopeCaptureWindow.Snapshot.class,
                RuntimeSnapshot.class, long.class, long.class, long.class, Consumer.class); }
        catch (NoSuchMethodException missing) { throw new AssertionError("The drag must reuse its same physical scope candidate", missing); }
        method.invoke(probe, target, current.get(), actualDown, began + 2_500,
                SystemClock.elapsedRealtime(), (Consumer<String>) completed::add);
    }

    private void transferredRelease() {
        physical.event(0, 1, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, SystemClock.uptimeMillis());
        physical.markReleasedElapsed(SystemClock.elapsedRealtime());
    }

    @Test public void transferredDragWithAnEquivalentSnapshotUsesTheArmedCandidateAndOnlyOneOriginalPointTap() throws Exception {
        arm(); ScopeCaptureWindow.Snapshot dragTarget = windows.inspect(atms, 0, 50, 200);
        assertNotSame(snapshot, dragTarget); ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        transferredRelease();
        duringTap = action -> { if (action == MotionEvent.ACTION_UP) assertTrue(intercept(link(), true)); };
        confirmDrag(dragTarget, down); drain();
        assertEquals(List.of(URL), completed); assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions);
        confirmDrag(dragTarget, down); drain(); assertEquals(2, actions.size());
    }

    @Test public void transferredDragRetainsTheUrlAlreadyObservedByTheSamePhysicalCandidate() throws Exception {
        arm(); ScopeCaptureWindow.Snapshot dragTarget = windows.inspect(atms, 0, 50, 200);
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_600)); assertTrue(intercept(link(), true));
        transferredRelease(); confirmDrag(dragTarget, down); drain();
        assertEquals(List.of(URL), completed); assertTrue(actions.isEmpty());
    }

    @Test public void dragWithoutAnArmedScopeCandidateConfirmsItsOwnVerifiedSnapshot() throws Exception {
        ScopeCaptureWindow.Snapshot dragTarget = windows.inspect(atms, 0, 50, 200);
        probe = new ScopeLinkProbe(new Handler(Looper.getMainLooper()), physical, current::get,
                picked -> assertSame(dragTarget, picked), (picked, action, at) -> {
                    assertSame(dragTarget, picked); actions.add(action);
                    if (action == MotionEvent.ACTION_UP) assertTrue(intercept(link(), true));
                    return true;
                }, logs::add);
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_600)); transferredRelease();
        confirmDrag(dragTarget, down); drain();
        assertEquals(List.of(URL), completed); assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions);
    }

    @Test public void dragHandoffCannotStealAnotherDownSourceOrWindowIdentity() throws Exception {
        arm(); ScopeCaptureWindow.Snapshot dragTarget = windows.inspect(atms, 0, 50, 200);
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_600)); assertTrue(intercept(link(), true)); transferredRelease();
        confirmDrag(dragTarget, down + 1); drain(); assertTrue(actions.isEmpty()); assertNull(completed.get(0));
        String source = main.mAttrs.packageName; ComponentName component = main.mActivityRecord.mActivityComponent;
        main.mAttrs.packageName = "com.example.other";
        main.mActivityRecord.mActivityComponent = new ComponentName("com.example.other", "com.example.other.MainActivity");
        ScopeCaptureWindow.Snapshot otherSource = windows.inspect(atms, 0, 50, 200);
        main.mAttrs.packageName = source; main.mActivityRecord.mActivityComponent = component;
        confirmDrag(otherSource, down); drain(); assertNull(completed.get(1));
        Object layer = main.layer; main.layer = new Object();
        ScopeCaptureWindow.Snapshot otherLayer = windows.inspect(atms, 0, 50, 200); main.layer = layer;
        confirmDrag(otherLayer, down); drain(); assertNull(completed.get(2));
        confirm(); drain(); assertEquals(URL, completed.get(3)); assertTrue(actions.isEmpty());
    }

    @Test public void nativeLongPressUrlIsConsumedBeforeBodyAndDeliveredOnlyAfterConfirmedRelease() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        assertTrue(intercept(link(), true)); drain();
        assertTrue(completed.isEmpty()); assertTrue(actions.isEmpty());
        release(); confirm(); assertTrue(completed.isEmpty()); drain();
        assertEquals(List.of(URL), completed); assertTrue(actions.isEmpty());
    }

    @Test public void shortPressLaunchesRemainUntouched() {
        arm(); assertFalse(intercept(link(), true));
        ShadowSystemClock.advanceBy(Duration.ofMillis(200)); release();
        assertFalse(intercept(link(), true)); confirm(); drain();
        assertEquals(1, completed.size()); assertNull(completed.get(0)); assertTrue(actions.isEmpty());
    }

    @Test public void nativeLaunchBeforeRecognitionDeadlineIsLeftUntouchedAndEarlyReleaseClearsProbe() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(600));
        assertFalse(intercept(link(), true));
        release(); confirm(); drain();
        assertFalse(intercept(link(), true));
        assertTrue(actions.isEmpty());
        assertEquals(1, completed.size()); assertNull(completed.get(0));
    }

    @Test public void aConfirmedGestureSendsExactlyOneTapAndDefersItsCallback() {
        arm(); hold();
        duringTap = action -> {
            if (action == MotionEvent.ACTION_UP) {
                assertTrue(intercept(link(), true));
                assertTrue(completed.isEmpty());
            }
        };
        confirm(); assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions);
        assertTrue(completed.isEmpty()); drain(); assertEquals(List.of(URL), completed);
        confirm(); drain(); assertEquals(2, actions.size());
    }

    @Test public void releasingBeforeRecognitionDeadlineSkipsTheSyntheticTap() {
        arm(); hold();
        long recognitionAt = SystemClock.elapsedRealtime() + 250;
        probe.confirm(snapshot, current.get(), recognitionAt, SystemClock.elapsedRealtime(), completed::add);
        assertTrue(actions.isEmpty());
        ShadowLooper.idleMainLooper(249, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(actions.isEmpty());
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(actions.isEmpty());
    }

    @Test public void aReleaseAfterTheRecognitionDeadlineCanUseTheFallbackTap() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        long recognitionAt = began + 2_500;
        release();
        probe.confirm(snapshot, current.get(), recognitionAt, SystemClock.elapsedRealtime(), completed::add);
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions);
    }

    @Test public void aNativeUrlCancelsTheScheduledFallbackTap() {
        arm(); hold();
        long now = SystemClock.elapsedRealtime();
        probe.nativeResult(SOURCE, now, true);
        probe.confirm(snapshot, current.get(), now, now, completed::add);
        drain();
        assertTrue(actions.isEmpty());
    }

    @Test public void anUnusableNativeResultDoesNotSuppressMissingUrlFallback() {
        arm(); hold();
        long now = SystemClock.elapsedRealtime();
        probe.nativeResult(SOURCE, now, false);
        confirm();
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions);
    }

    @Test public void systemReleaseTimestampMustAlsoBeAfterRecognitionDeadline() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        long recognitionAt = began + 2_500;
        release();
        physical.markReleasedElapsed(recognitionAt - 1);
        probe.confirm(snapshot, current.get(), recognitionAt, SystemClock.elapsedRealtime(), completed::add);
        assertTrue(actions.isEmpty());
        drain(); assertEquals(1, completed.size()); assertNull(completed.get(0));
    }

    @Test(timeout = 5000) public void synchronousBinderInterceptionDoesNotWaitForTheInputSendingLock() {
        arm(); hold();
        duringTap = action -> {
            if (action != MotionEvent.ACTION_UP) return;
            AtomicBoolean returned = new AtomicBoolean(), consumed = new AtomicBoolean();
            Thread binder = new Thread(() -> { consumed.set(intercept(link(), true)); returned.set(true); });
            binder.start();
            try { binder.join(1500); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertTrue("Binder must return while the input sender waits", returned.get());
            assertTrue(consumed.get()); assertTrue(completed.isEmpty());
        };
        confirm(); drain(); assertEquals(List.of(URL), completed);
    }

    @Test public void unclaimedLaunchTimesOutWithoutRetryingTheTap() {
        arm(); hold(); confirm();
        ShadowLooper.idleMainLooper(3001, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions);
        assertEquals(1, completed.size()); assertNull(completed.get(0));
    }

    @Test public void aUrlResolvedAfterNineHundredMillisecondsIsStillCapturedBeforeTheThreeSecondDeadline() {
        arm(); hold(); confirm();
        ShadowLooper.idleMainLooper(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(completed.isEmpty());
        assertTrue(intercept(link(), true)); drain();
        assertEquals(List.of(URL), completed);
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions);
    }

    @Test public void aLaunchAfterTimeoutIsConsumedForTenSecondsWithoutDeliveringALateUrl() {
        arm(); hold(); confirm();
        ShadowLooper.idleMainLooper(3001, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1, completed.size()); assertNull(completed.get(0));
        assertTrue(intercept(link(), true)); drain();
        assertFalse(probe.intercept(UID + 1, SOURCE, 0, link(), true));
        assertFalse(probe.intercept(UID, "com.example.other", 0, link(), true));
        assertFalse(probe.intercept(UID, SOURCE, 10, link(), true));
        ShadowSystemClock.advanceBy(Duration.ofMillis(9998));
        assertTrue(intercept(link(), true));
        ShadowSystemClock.advanceBy(Duration.ofMillis(2));
        assertFalse(intercept(link(), true));
        assertEquals(1, completed.size()); assertNull(completed.get(0));
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions);
    }

    @Test public void anExpiredCaptureConsumesItsLaunchBeforeTheTimeoutHandlerRuns() {
        arm(); hold(); confirm();
        ShadowSystemClock.advanceBy(Duration.ofMillis(3001));
        assertTrue(completed.isEmpty());
        assertTrue(intercept(link(), true)); drain();
        assertEquals(1, completed.size()); assertNull(completed.get(0));
    }

    @Test public void aNewPhysicalDownReleasesTheTimeoutTailImmediately() {
        arm(); hold(); confirm();
        ShadowLooper.idleMainLooper(3001, java.util.concurrent.TimeUnit.MILLISECONDS);
        probe.pointer(MotionEvent.ACTION_DOWN, 1, snapshot.x, snapshot.y, SystemClock.uptimeMillis(), 20);
        assertFalse(intercept(link(), true));
    }

    @Test public void aConfigurationChangeReleasesTheTimeoutTailImmediately() {
        arm(); hold(); confirm();
        ShadowLooper.idleMainLooper(3001, java.util.concurrent.TimeUnit.MILLISECONDS);
        config.setEnabled(false); current.set(RuntimeSnapshot.parse(RuntimeSnapshot.from(context)));
        assertFalse(intercept(link(), true));
    }

    @Test public void anotherUidPackageOrUserCannotConsumeTheArmedSource() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        assertFalse(probe.intercept(UID + 1, SOURCE, 0, link(), true));
        assertFalse(probe.intercept(UID, "com.example.other", 0, link(), true));
        assertFalse(probe.intercept(UID, SOURCE, 10, link(), true));
        assertFalse(probe.intercept(UID, SOURCE, -2, link(), true));
        assertTrue(intercept(link(), true)); release(); confirm(); drain();
        assertEquals(List.of(URL), completed);
    }

    @Test public void nativeUnsupportedActionsAreConsumedOnlyAfterTheLongPressThresholdAndFailAfterConfirmation() {
        arm(); assertFalse(intercept(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:123")), true));
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        assertTrue(intercept(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:123")), true));
        assertTrue(intercept(link(), false)); drain(); assertTrue(completed.isEmpty());
        release(); confirm(); drain();
        assertEquals(1, completed.size()); assertNull(completed.get(0)); assertTrue(actions.isEmpty());
    }

    @Test public void safeHttpUrlIsCapturedEvenThroughNonOrdinaryAtmsOverload() {
        arm(); hold(); confirm(); assertTrue(intercept(link(), false)); drain();
        assertEquals(List.of(URL), completed);
    }

    private Intent wechatPage() {
        return new Intent().setComponent(new ComponentName("com.tencent.mm",
                "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI"));
    }

    @Test public void sameSourceWechatWebViewRawUrlIsCapturedWithoutActionOrData() throws Exception {
        prepareSource("com.tencent.mm"); arm(); hold(); confirm();
        Intent request = wechatPage().putExtra("rawUrl", URL);
        String before = request.toUri(0);
        assertTrue(intercept(request, true)); drain();
        assertEquals(List.of(URL), completed);
        assertEquals(before, request.toUri(0));
    }

    @Test public void sameSourceKnownWebViewNestedUrlIsCaptured() throws Exception {
        prepareSource("com.tencent.mm"); arm(); hold(); confirm();
        Bundle nested = new Bundle(); nested.putString("url", URL);
        assertTrue(intercept(wechatPage().putExtra("webview_params", nested), true)); drain();
        assertEquals(List.of(URL), completed);
    }

    @Test public void sameSourcePageWithACustomActionCanSupplyAUniqueRealUrlWithoutAnApplicationAdapter() {
        arm();hold();confirm();
        Bundle route=new Bundle();route.putString("destination",URL);
        Intent request=new Intent("app.OPEN_PAGE").setComponent(new ComponentName(SOURCE,SOURCE+".PageActivity"));
        request.putExtra("route_payload",route);
        assertTrue(intercept(request,true));drain();assertEquals(List.of(URL),completed);
    }

    @Test public void genericPageMetadataRejectsAmbiguousDestinationsAndNeverUsesAThumbnailAsTheLink() {
        arm();hold();confirm();
        Intent request=new Intent().setComponent(new ComponentName(SOURCE,SOURCE+".PageActivity"));
        request.putExtra("landing_page",URL);request.putExtra("thumburl","https://images.example/preview.jpg");
        assertTrue(intercept(request,true));drain();assertEquals(List.of(URL),completed);
    }

    @Test public void sameSourceReferrerOrAnalyticsUrlCannotBecomeTheSelectedCardDestination() {
        Intent request=new Intent("app.OPEN_DETAIL").setComponent(new ComponentName(SOURCE,SOURCE+".DetailActivity"));
        request.putExtra(Intent.EXTRA_REFERRER_NAME,"https://tracking.example/feed");
        request.putExtra("analytics_payload","https://tracking.example/event");request.putExtra("article_id",123);
        assertNull(ScopeLinkProbe.strictHttp(request,SOURCE));
        request.putExtra("landing_page",URL);assertEquals(URL,ScopeLinkProbe.strictHttp(request,SOURCE));
    }

    @Test public void knownWebViewConflictingAndUnsafePayloadsRemainRejected() throws Exception {
        Intent clip = wechatPage().putExtra("rawUrl", URL);
        clip.setClipData(ClipData.newPlainText("link", URL));
        for (Intent request : List.of(wechatPage().putExtra("rawUrl", URL)
                        .putExtra("url", "https://different.example/"),
                wechatPage().putExtra("rawUrl", URL).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                clip)) {
            prepareSource("com.tencent.mm"); arm(); hold(); confirm();
            assertTrue(intercept(request, true)); drain();
            assertNull(completed.get(completed.size() - 1));
        }
    }

    @Test public void anotherSourcesWebViewCannotAuthorizeExtrasOnlyCapture() {
        arm(); hold(); confirm();
        assertTrue(intercept(wechatPage().putExtra("rawUrl", URL), true)); drain();
        assertEquals(1, completed.size()); assertNull(completed.get(0));
    }

    @Test public void rejectedWebPayloadReportsAFixedReasonWithoutLeakingItsValues() throws Exception {
        prepareSource("com.tencent.mm"); arm(); hold(); confirm();
        assertTrue(intercept(wechatPage().putExtra("rawUrl", URL)
                .putExtra("url", "https://different.example/"), true)); drain();
        assertTrue(logs.contains("scope link stage=rejected reason=web_payload_invalid"));
        assertTrue(logs.stream().noneMatch(message -> message.contains(URL)
                || message.contains("different.example") || message.contains("com.tencent.mm")));
    }

    @Test public void hostHandoffStopsBothScopeInterceptionAndItsDelayedFallbackTap() {
        arm(); hold();
        assertTrue(probe.tryYieldToHost(snapshot));
        confirm(); drain();
        assertTrue(actions.isEmpty());
        assertFalse(intercept(link(), true));
        assertFalse(probe.tryYieldToHost(snapshot));
        assertEquals(1, completed.size()); assertNull(completed.get(0));
    }

    @Test public void hostHandoffCannotSendASecondTapAfterScopeAlreadyClicked() {
        arm(); hold(); confirm();
        assertFalse(probe.tryYieldToHost(snapshot));
        assertTrue(intercept(link(), true)); drain();
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions);
        assertEquals(List.of(URL), completed);
        assertFalse(probe.tryYieldToHost(snapshot));
    }

    @Test public void observedScopeLaunchKeepsItsOwnResultInsteadOfStartingAnotherProbe() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        assertTrue(intercept(link(), true)); release();
        assertFalse(probe.tryYieldToHost(snapshot));
        confirm(); drain();
        assertTrue(actions.isEmpty()); assertEquals(List.of(URL), completed);
    }

    @Test public void newGestureCanClaimTheSourceAfterPreviousHostHandoff() {
        arm(); hold(); assertTrue(probe.tryYieldToHost(snapshot));
        down = SystemClock.uptimeMillis(); began = SystemClock.elapsedRealtime();
        physical.event(0, 1, down, down, MotionEvent.ACTION_DOWN, down);
        arm(); hold(); confirm();
        assertTrue(intercept(link(), true)); drain();
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions);
        assertEquals(List.of(URL), completed);
    }

    @Test public void unsafeIntentIsNotCapturedThroughNonOrdinaryAtmsOverload() {
        arm(); hold(); confirm();
        Intent unsafe = link().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertTrue(intercept(unsafe, false)); drain();
        assertEquals(1, completed.size()); assertNull(completed.get(0));
    }

    @Test public void chooserWithOnePlainViewIntentCanReturnItsSingleHttpData() {
        arm(); hold(); confirm();
        Intent chooser = new Intent(Intent.ACTION_CHOOSER).putExtra(Intent.EXTRA_INTENT, link());
        assertTrue(intercept(chooser, true)); drain(); assertEquals(List.of(URL), completed);
    }

    @Test public void aChooserWithAlternativeDestinationsIsConsumedAsAnAmbiguousFailure() {
        arm(); hold(); confirm();
        Intent chooser = new Intent(Intent.ACTION_CHOOSER).putExtra(Intent.EXTRA_INTENT, link())
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{link()});
        assertTrue(intercept(chooser, true)); drain();
        assertEquals(1, completed.size()); assertNull(completed.get(0));
    }

    @Test public void uriGrantsSelectorsClipDataAndConflictingUrlsNeverProduceALink() {
        Intent selector = link(); selector.setSelector(new Intent("custom.selector"));
        Intent clip = link(); clip.setClipData(ClipData.newPlainText("link", URL));
        List<Intent> invalid = List.of(link().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                selector, clip,
                link().putExtra("rawUrl", "https://different.example/"));
        for (Intent intent : invalid) {
            down = SystemClock.uptimeMillis(); began = SystemClock.elapsedRealtime();
            physical.event(0, 1, down, down, MotionEvent.ACTION_DOWN, down);
            probe.arm(snapshot, current.get(), down, began, 500, 1);
            hold();
            confirm(); assertTrue(intercept(intent, true)); drain();
            assertNull(completed.get(completed.size() - 1));
        }
    }

    @Test public void directRedirectAndMatchingInnerExtraResolveBeforeIntentConflictChecks() {
        String target="https://1817601681.share.123pan.cn/123pan/E4XTjv-BeZ53";
        String wrapper="https://bridge.example/link?url="+java.net.URLEncoder.encode(target,java.nio.charset.StandardCharsets.UTF_8);
        Intent request=new Intent(Intent.ACTION_VIEW,Uri.parse(wrapper)).putExtra("rawUrl",target);
        assertEquals(target,ScopeLinkProbe.strictHttp(request,SOURCE));
        assertNull(ScopeLinkProbe.strictHttp(new Intent(request).putExtra("url","https://other.example/page"),SOURCE));
        assertNull(ScopeLinkProbe.strictHttp(new Intent(request).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),SOURCE));
        Intent clip=new Intent(request);clip.setClipData(ClipData.newPlainText("link",target));
        assertNull(ScopeLinkProbe.strictHttp(clip,SOURCE));
    }

    @Test public void twoConflictingEarlyNativeUrlsFailInsteadOfGuessing() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        assertTrue(intercept(link(), true));
        assertTrue(intercept(new Intent(Intent.ACTION_VIEW, Uri.parse("https://different.example/")), true));
        release(); confirm(); drain(); assertEquals(1, completed.size()); assertNull(completed.get(0));
        assertTrue(actions.isEmpty());
    }

    @Test public void newDownMovementCancellationAndMultiplePointersRevokeCandidateOwnership() {
        arm(); probe.pointer(MotionEvent.ACTION_MOVE, 1, snapshot.x + 100, snapshot.y, down, 20);
        ShadowSystemClock.advanceBy(Duration.ofMillis(600)); assertFalse(intercept(link(), true));
        down = SystemClock.uptimeMillis(); began = SystemClock.elapsedRealtime(); arm();
        probe.pointer(MotionEvent.ACTION_POINTER_DOWN, 2, snapshot.x, snapshot.y, down, 20);
        assertFalse(intercept(link(), true));
        arm(); probe.pointer(MotionEvent.ACTION_CANCEL, 1, snapshot.x, snapshot.y, down, 20);
        assertFalse(intercept(link(), true));
        arm(); probe.pointer(MotionEvent.ACTION_DOWN, 1, snapshot.x, snapshot.y, down, 20);
        assertFalse(intercept(link(), true));
    }

    @Test public void changedConfigurationAndUnavailableMainWindowPreventAnySyntheticTap() {
        arm(); hold(); config.setEnabled(false); current.set(RuntimeSnapshot.parse(RuntimeSnapshot.from(context)));
        confirm(); drain(); assertTrue(actions.isEmpty()); assertNull(completed.get(0));
        config.setEnabled(true); current.set(RuntimeSnapshot.parse(RuntimeSnapshot.from(context)));
        down = SystemClock.uptimeMillis(); began = SystemClock.elapsedRealtime(); arm();
        windowValid = false; confirm(); drain(); assertTrue(actions.isEmpty());
    }

    @Test public void failedDownAttemptsOnlyCancelCleanupAndNeverAnUpOrRetry() {
        arm(); hold(); inputAccepted = false; confirm(); drain();
        assertEquals(List.of(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), actions);
        assertEquals(1, completed.size()); assertNull(completed.get(0));
    }

    @Test public void confirmedDoubleGestureCanProbeWithoutArmingAnEarlyCandidate() {
        probe.arm(snapshot, current.get(), down, began, 500, 2);
        ShadowSystemClock.advanceBy(Duration.ofMillis(600)); assertFalse(intercept(link(), true));
        release(); confirm(); assertTrue(intercept(link(), true)); drain();
        assertEquals(List.of(URL), completed); assertEquals(2, actions.size());
    }

    @Test public void acceptedNativeResultOnlyCancelsItsOwnCurrentSourceAndGesture() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        probe.nativeResult("com.example.other", SystemClock.elapsedRealtime());
        probe.nativeResult(SOURCE, began - 1);
        assertTrue(intercept(link(), true));
        probe.nativeResult(SOURCE, SystemClock.elapsedRealtime());
        release(); confirm(); drain(); assertEquals(1, completed.size()); assertNull(completed.get(0));
        assertTrue(actions.isEmpty());
    }

    @Test public void nativeOwnershipStartsBeforeTheLongPressThresholdButOnlyForTheCurrentSourceAndGesture() {
        arm();
        assertTrue(probe.ownsNative(SOURCE, began));
        assertFalse(probe.ownsNative("com.example.other", began));
        assertFalse(probe.ownsNative(SOURCE, began - 1));
        assertFalse(probe.ownsNative(SOURCE, SystemClock.elapsedRealtime() + 1));
        assertTrue(actions.isEmpty()); assertTrue(completed.isEmpty());
        assertTrue(probe.ownsNative(SOURCE, began));
    }

    @Test public void nativeOwnershipIsRevokedWhenTheConfigurationOrTargetWindowChanges() {
        arm(); windowValid = false;
        assertFalse(probe.ownsNative(SOURCE, began));
        windowValid = true;
        assertTrue(probe.ownsNative(SOURCE, began));
        config.setEnabled(false); current.set(RuntimeSnapshot.parse(RuntimeSnapshot.from(context)));
        assertFalse(probe.ownsNative(SOURCE, began));
    }

    @Test public void nativeOwnershipDoesNotOutliveTheCandidateOrItsCancellation() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(20_001));
        assertFalse(probe.ownsNative(SOURCE, SystemClock.elapsedRealtime()));
        down = SystemClock.uptimeMillis(); began = SystemClock.elapsedRealtime(); arm();
        assertTrue(probe.ownsNative(SOURCE, began));
        probe.cancel(); assertFalse(probe.ownsNative(SOURCE, began));
    }

    @Test public void aRepeatedLaunchFromTheSameProbeIsBrieflyConsumedAfterCompletion() {
        arm(); hold(); confirm(); assertTrue(intercept(link(), true)); drain();
        assertEquals(List.of(URL), completed);
        assertTrue(intercept(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/second")), true));
        ShadowSystemClock.advanceBy(Duration.ofMillis(401));
        assertFalse(intercept(link(), true)); assertEquals(List.of(URL), completed);
    }

    @Test public void aNewPhysicalDownImmediatelyReleasesTheTailForANormalUserClick() {
        arm(); hold(); confirm(); assertTrue(intercept(link(), true)); drain();
        probe.pointer(MotionEvent.ACTION_DOWN, 1, snapshot.x, snapshot.y, SystemClock.uptimeMillis(), 20);
        assertFalse(intercept(link(), true)); assertEquals(List.of(URL), completed);
    }

    @Test public void aConfigurationChangeAlsoRemovesTheShortTail() {
        arm(); hold(); confirm(); assertTrue(intercept(link(), true)); drain();
        config.setEnabled(false); current.set(RuntimeSnapshot.parse(RuntimeSnapshot.from(context)));
        assertFalse(intercept(link(), true));
    }

    @Test public void anOwnerLaunchAfterTheLongPressThresholdIsConsumedEvenIfTheWindowCheckFails() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(2_600)); windowValid = false;
        assertTrue(intercept(link(), true)); assertTrue(completed.isEmpty());
        release(); confirm(); drain();
        assertEquals(1, completed.size()); assertNull(completed.get(0)); assertTrue(actions.isEmpty());
    }

    @Test public void aDelayedNativeReportFromThePreviousGestureCannotCancelANewCandidate() {
        arm(); ShadowSystemClock.advanceBy(Duration.ofMillis(2_600));
        long oldNative = SystemClock.elapsedRealtime(); probe.nativeResult(SOURCE, oldNative);
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        down = SystemClock.uptimeMillis(); began = SystemClock.elapsedRealtime();
        physical.event(0, 1, down, down, MotionEvent.ACTION_DOWN, down); arm();
        probe.nativeResult(SOURCE, oldNative); hold(); confirm();
        assertTrue(intercept(link(), true)); drain(); assertEquals(List.of(URL), completed);
    }

    @Test public void logsContainOnlyFixedStagesAndReasons() {
        arm(); hold(); confirm(); assertTrue(intercept(link(), true)); drain();
        assertFalse(logs.isEmpty());
        for (String message : logs) {
            assertTrue(message.matches("scope link stage=[a-z_]+ reason=[a-z_]+"));
            assertFalse(message.contains(URL)); assertFalse(message.contains(SOURCE));
        }
    }
}
