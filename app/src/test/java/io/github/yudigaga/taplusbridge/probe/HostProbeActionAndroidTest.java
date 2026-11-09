package io.github.yudigaga.taplusbridge.probe;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import io.github.yudigaga.taplusbridge.hook.AutoProbeReadiness;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = HostProbeActionAndroidTest.FrameworkResultReceiverShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class HostProbeActionAndroidTest {
    private static final String NONCE = "N".repeat(43);
    private static final String LINK = "https://www.bilibili.com/video/BV1xx411c7mD";
    private Application application;
    private HostProbeWindowAndroidTest.FakeService service;
    private ProbeProvider provider;
    private ShadowLooper main;
    private final AtomicBoolean current = new AtomicBoolean(true);
    private final List<String> captured = new ArrayList<>();
    private int splashFlags;
    private int mainFlags;

    // The stock shadow calls the receiver object's empty callback and skips Binder forwarding.
    // Keep the SDK implementation so a parceled framework receiver reaches its original callback.
    @Implements(value = ResultReceiver.class, callThroughByDefault = true)
    public static final class FrameworkResultReceiverShadow { }

    @Before public void setUp() {
        application = RuntimeEnvironment.getApplication();
        main = Shadow.extract(Looper.getMainLooper());
        service = HostProbeWindowAndroidTest.freshService(application);
        splashFlags = service.mSplashFloatView.wmParams.flags;
        mainFlags = service.mMainFloatView.wmParams.flags;
        provider = Robolectric.buildContentProvider(ProbeProvider.class).create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
    }

    @After public void cleanUpAndForbidActivityLaunches() {
        current.set(false);
        HostProbeAction.cancelActive();
        main.idle();
        main.idleFor(Duration.ofMillis(ProbeContract.TIMEOUT_MILLIS + 100));
        assertNull(((ShadowApplication) Shadow.extract(application)).getNextStartedActivity());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test public void actualParcelReadyThenCapturedReturnsOnceAndRetainsHostWindows() {
        ResultReceiver ack = begin();
        assertEquals(1, provider.armCalls);
        assertEquals(CaptureLease.WECHAT, provider.source);
        assertPreparedWindows();
        ack.send(ProbeContract.READY, response(true)); main.idle();
        assertEquals(0, broadcasts(ProbeContract.TAP).size());
        main.idleFor(Duration.ofMillis(63));
        assertEquals(0, broadcasts(ProbeContract.TAP).size());
        main.idleFor(Duration.ofMillis(1));
        assertEquals(1, broadcasts(ProbeContract.TAP).size());
        Intent tap = broadcasts(ProbeContract.TAP).get(0);
        assertEquals("android", tap.getPackage());
        assertEquals(NONCE, tap.getStringExtra("nonce"));
        assertEquals(ProbeContract.VERSION, tap.getIntExtra("protocol", -1));
        assertEquals(ProbeContract.MODE, tap.getStringExtra("mode"));
        assertEquals(Process.myUid() / 100000, tap.getIntExtra("user", -1));
        Bundle result = response(true); result.putString("url", LINK);
        ack.send(ProbeContract.CAPTURED, result); ack.send(ProbeContract.CAPTURED, result);
        main.idle();
        assertEquals(List.of(LINK), captured);
        assertEquals(0, provider.cancelCalls);
        assertRestoredWindows();
        main.idleFor(Duration.ofMillis(ProbeContract.TIMEOUT_MILLIS + 1));
        assertEquals(0, provider.cancelCalls);
        assertEquals(1, captured.size());
    }

    @Test public void definitiveFailureNotifiesCompletionOnceAndKeepsInflightCapturePending() throws Exception {
        List<String> order = new ArrayList<>();
        startWithCompletion(url -> order.add("url"), () -> order.add("complete"));
        main.idle(); ResultReceiver ack = acknowledgement();
        assertTrue(order.isEmpty());
        ack.send(ProbeContract.READY, response(true)); main.idleFor(Duration.ofMillis(64));
        assertTrue("READY is not completion while a URL may still arrive", order.isEmpty());
        ack.send(ProbeContract.FAILED, response(false)); ack.send(ProbeContract.FAILED, response(false)); main.idle();
        assertEquals(List.of("complete"), order);
        main.idleFor(Duration.ofMillis(ProbeContract.TIMEOUT_MILLIS + 1));
        assertEquals(List.of("complete"), order);
    }

    @Test public void successfulUrlIsDeliveredBeforeCompletionExactlyOnce() throws Exception {
        List<String> order = new ArrayList<>();
        startWithCompletion(url -> { assertEquals(LINK, url); order.add("url"); }, () -> order.add("complete"));
        main.idle(); ResultReceiver ack = acknowledgement();
        ack.send(ProbeContract.READY, response(true)); main.idleFor(Duration.ofMillis(64));
        Bundle result = response(true); result.putString("url", LINK);
        ack.send(ProbeContract.CAPTURED, result); ack.send(ProbeContract.CAPTURED, result); main.idle();
        assertEquals(List.of("url", "complete"), order);
    }

    @Test public void aStaleProbeDoesNotSignalCompletionForItsReplacement() throws Exception {
        List<String> order = new ArrayList<>();
        startWithCompletion(url -> order.add("old_url"), () -> order.add("old_complete"));
        main.idle(); ResultReceiver old = acknowledgement();
        current.set(false); start(); main.idle();
        old.send(ProbeContract.FAILED, response(false)); main.idle();
        assertTrue(order.isEmpty());
    }

    @Test public void startLeavesTheOriginalTouchDispatchBeforePreparingWindows() {
        start();
        assertEquals(0, provider.armCalls);
        assertTrue(broadcasts(ProbeContract.ARM).isEmpty());
        assertRestoredWindows();
        main.idle();
        assertEquals(1, provider.armCalls);
        assertPreparedWindows();
    }

    @Test public void missingRecognitionDeadlineCannotStartAnAutomaticProbe() {
        HostProbeAction.start(service, service, CaptureLease.WECHAT, SystemClock.elapsedRealtime(), 0,
                current::get, captured::add);
        main.idle();
        assertEquals(0, provider.armCalls);
        assertTrue(broadcasts(ProbeContract.ARM).isEmpty());
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
    }

    @Test public void armCarriesTheOriginalRecognitionDeadlineToSystemServer() {
        long recognitionAt = SystemClock.elapsedRealtime() + 2_000;
        start(recognitionAt); main.idle();
        assertEquals(recognitionAt, broadcasts(ProbeContract.ARM).get(0).getLongExtra("recognition_at", 0));
    }

    @Test public void legacyEmptyAcknowledgementNeverPermitsATap() {
        ResultReceiver ack = begin();
        ack.send(1, Bundle.EMPTY); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertEquals(NONCE, provider.cancelledNonce);
        assertRestoredWindows();
        ack.send(ProbeContract.READY, response(true)); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
    }

    @Test public void wrongNonceCannotAuthorizeInjectionOrReturnAUrl() {
        ResultReceiver ack = begin();
        Bundle wrong = response(true); wrong.putString("nonce", "X".repeat(43));
        ack.send(ProbeContract.READY, wrong); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void wrongUserCannotAuthorizeInjection() {
        ResultReceiver ack = begin();
        Bundle wrong = response(true); wrong.putInt("user", Process.myUid() / 100000 + 1);
        ack.send(ProbeContract.READY, wrong); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void wrongProtocolCannotAuthorizeInjection() {
        ResultReceiver ack = begin();
        Bundle wrong = response(true); wrong.putInt("protocol", ProbeContract.VERSION + 1);
        ack.send(ProbeContract.READY, wrong); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void wrongModeCannotAuthorizeInjection() {
        ResultReceiver ack = begin();
        Bundle wrong = response(true); wrong.putString("mode", "observe");
        ack.send(ProbeContract.READY, wrong); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void readyWithoutBlockingCapabilityNeverPermitsATap() {
        ResultReceiver ack = begin();
        ack.send(ProbeContract.READY, response(false)); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void readyWithoutPhysicalReleaseProofNeverPermitsATap() {
        ResultReceiver ack=begin(); Bundle data=response(true); data.remove("release_confirmed");
        ack.send(ProbeContract.READY,data); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty()); assertTrue(captured.isEmpty());
        assertEquals(1,provider.cancelCalls); assertRestoredWindows();
        assertEquals("protocol",provider.lastReason);
    }

    @Test public void nativeUrlAppearingAfterArmCancelsBeforeTheSyntheticTap() {
        ResultReceiver ack = begin();
        ack.send(ProbeContract.READY, response(true)); main.idle();
        current.set(false);
        main.idleFor(Duration.ofMillis(64));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void disabledSupplementalTapNeverArmsOrPreparesAWindow() {
        provider.probeEnabled = false;
        start(); main.idle();
        assertEquals(0, provider.armCalls);
        assertTrue(broadcasts(ProbeContract.ARM).isEmpty());
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertRestoredWindows();
    }

    @Test public void disablingSupplementalTapWhileWaitingForReadyCancelsTheOldRun() {
        ResultReceiver ack = begin(); provider.probeEnabled = false;
        ack.send(ProbeContract.READY, response(true)); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertEquals(1, provider.cancelCalls); assertRestoredWindows();
    }

    @Test public void disablingSupplementalTapDuringReadySettlePreventsTheTapCommand() {
        ResultReceiver ack = begin();
        ack.send(ProbeContract.READY, response(true)); main.idleFor(Duration.ofMillis(63));
        provider.probeEnabled = false; main.idleFor(Duration.ofMillis(1));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertEquals(1, provider.cancelCalls); assertRestoredWindows();
    }

    @Test public void confirmedReleaseDismissesDragOverlayBeforeSyntheticTap() throws Exception {
        List<String> order = new ArrayList<>();
        startWithOverlayBarrier(() -> { order.add("overlay_dismissed"); return true; });
        main.idle(); ResultReceiver ack = acknowledgement();
        ack.send(ProbeContract.READY, response(true)); main.idle();
        assertEquals(List.of("overlay_dismissed"), order);
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        main.idleFor(Duration.ofMillis(64));
        assertEquals(1, broadcasts(ProbeContract.TAP).size());
    }

    @Test public void failedDragOverlayDismissalBlocksSyntheticTap() throws Exception {
        List<String> order = new ArrayList<>();
        startWithOverlayBarrier(() -> { order.add("overlay_dismiss_failed"); return false; });
        main.idle(); ResultReceiver ack = acknowledgement();
        ack.send(ProbeContract.READY, response(true)); main.idleFor(Duration.ofMillis(100));
        assertEquals(List.of("overlay_dismiss_failed"), order);
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void lateRecognizedLinkBeforeReadyCancelsAutomaticProbeWithoutTap() {
        AtomicBoolean link=new AtomicBoolean(); AutoProbeReadiness readiness=new AutoProbeReadiness(link::get);
        readiness.reset(1); readiness.segmentComplete(1); assertTrue(readiness.claim(1));
        HostProbeAction.start(service,service,CaptureLease.WECHAT,SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime(),
                () -> current.get() && readiness.current(1),captured::add); main.idle();
        ResultReceiver ack=broadcasts(ProbeContract.ARM).get(0).getParcelableExtra("ack");
        link.set(true); ack.send(ProbeContract.READY,response(true)); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty()); assertEquals(1,provider.cancelCalls);
        assertTrue(captured.isEmpty()); assertRestoredWindows();
    }

    @Test public void lateRecognizedLinkDuringReadySettleCancelsBeforeTapCommand() {
        AtomicBoolean link=new AtomicBoolean(); AutoProbeReadiness readiness=new AutoProbeReadiness(link::get);
        readiness.reset(1); readiness.segmentComplete(1); assertTrue(readiness.claim(1));
        HostProbeAction.start(service,service,CaptureLease.WECHAT,SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime(),
                () -> current.get() && readiness.current(1),captured::add); main.idle();
        ResultReceiver ack=broadcasts(ProbeContract.ARM).get(0).getParcelableExtra("ack");
        ack.send(ProbeContract.READY,response(true)); main.idleFor(Duration.ofMillis(63));
        link.set(true); main.idleFor(Duration.ofMillis(1));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty()); assertEquals(1,provider.cancelCalls); assertRestoredWindows();
    }

    @Test public void newPickReplacesPendingRunAndOldCallbackCannotAffectIt() {
        ResultReceiver old=begin(); provider.ticketNonce="B".repeat(43);
        start(); main.idle();
        assertEquals(2,provider.armCalls); assertEquals(1,provider.cancelCalls);
        old.send(ProbeContract.READY,response(true)); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty()); assertPreparedWindows();
        assertEquals(2,broadcasts(ProbeContract.ARM).size());
        ResultReceiver fresh=broadcasts(ProbeContract.ARM).get(1).getParcelableExtra("ack");
        Bundle ready=response(true); ready.putString("nonce",provider.ticketNonce);
        fresh.send(ProbeContract.READY,ready); main.idleFor(Duration.ofMillis(64));
        assertEquals(1,broadcasts(ProbeContract.TAP).size());
        Bundle result=new Bundle(ready); result.putString("url",LINK);
        fresh.send(ProbeContract.CAPTURED,result); main.idle();
        assertEquals(List.of(LINK),captured); assertRestoredWindows();
    }

    @Test public void duplicateReadyCannotSendTwoTapRequests() {
        ResultReceiver ack = begin();
        ack.send(ProbeContract.READY, response(true)); ack.send(ProbeContract.READY, response(true));
        main.idleFor(Duration.ofMillis(100));
        assertEquals(1, broadcasts(ProbeContract.TAP).size());
        ack.send(ProbeContract.READY, response(true)); main.idleFor(Duration.ofMillis(100));
        assertEquals(1, broadcasts(ProbeContract.TAP).size());
        ack.send(ProbeContract.FAILED, response(false)); main.idle();
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void aCapturedPayloadCannotSkipReadyAndTap() {
        ResultReceiver ack = begin();
        Bundle result = response(true); result.putString("url", LINK);
        ack.send(ProbeContract.CAPTURED, result); main.idle();
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void staleSessionAtStartDoesNotPrepareOrArm() {
        current.set(false); start(); main.idle();
        assertEquals(0, provider.armCalls);
        assertTrue(broadcasts(ProbeContract.ARM).isEmpty());
        assertTrue(captured.isEmpty());
        assertRestoredWindows();
    }

    @Test public void sessionChangingDuringReadyDelayCancelsWithoutATap() {
        ResultReceiver ack = begin();
        ack.send(ProbeContract.READY, response(true)); main.idle();
        current.set(false); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void geometryChangingAfterReadyMustBeRevalidatedBeforeTap() {
        ResultReceiver ack = begin();
        ack.send(ProbeContract.READY, response(true)); main.idle();
        service.rect = new Rect(100, 900, 700, 1400);
        main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void sessionChangingAfterTapDiscardsTheCapturedUrl() {
        ResultReceiver ack = tapped();
        current.set(false);
        Bundle result = response(true); result.putString("url", LINK);
        ack.send(ProbeContract.CAPTURED, result); main.idle();
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void failureAfterTapRestoresWindowsAndCancelsOnlyOnce() {
        ResultReceiver ack = tapped();
        ack.send(ProbeContract.FAILED, response(false)); ack.send(ProbeContract.FAILED, response(false));
        main.idle();
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void timeoutRestoresWindowsAndRejectsLateReadyAndCapturedResponses() {
        ResultReceiver ack = begin();
        main.idleFor(Duration.ofMillis(ProbeContract.TIMEOUT_MILLIS + 1));
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
        ack.send(ProbeContract.READY, response(true));
        Bundle result = response(true); result.putString("url", LINK);
        ack.send(ProbeContract.CAPTURED, result); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
    }

    @Test public void invalidCapturedUrlCannotReachTheRecognitionConsumer() {
        ResultReceiver ack = tapped();
        Bundle result = response(true); result.putString("url", "javascript:alert(1)");
        ack.send(ProbeContract.CAPTURED, result); main.idle();
        assertTrue(captured.isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    @Test public void failedTicketPreparationRestoresWindowsWithoutBroadcastOrTap() {
        provider.ticketAvailable = false;
        start(); main.idle();
        assertEquals(1, provider.armCalls);
        assertTrue(broadcasts(ProbeContract.ARM).isEmpty());
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertTrue(captured.isEmpty());
        assertRestoredWindows();
    }

    @Test public void explicitCancellationStopsPendingInjectionAndRestoresTheLease() {
        ResultReceiver ack = begin();
        ack.send(ProbeContract.READY, response(true)); main.idle();
        HostProbeAction.cancelActive(); main.idleFor(Duration.ofMillis(100));
        assertTrue(broadcasts(ProbeContract.TAP).isEmpty());
        assertEquals(1, provider.cancelCalls);
        assertRestoredWindows();
    }

    private void start() {
        start(SystemClock.elapsedRealtime());
    }
    private void start(long recognitionAt) {
        HostProbeAction.start(service, service, CaptureLease.WECHAT, SystemClock.elapsedRealtime(), recognitionAt,
                current::get, captured::add);
    }
    private void startWithOverlayBarrier(BooleanSupplier prepareForTap) throws Exception {
        HostProbeAction.start(service, service, CaptureLease.WECHAT, SystemClock.elapsedRealtime(),
                SystemClock.elapsedRealtime(), current::get, prepareForTap, captured::add);
    }

    private void startWithCompletion(java.util.function.Consumer<String> capturedUrl, Runnable completed) throws Exception {
        HostProbeAction.class.getMethod("start", Context.class, Object.class, String.class, long.class, long.class,
                BooleanSupplier.class, BooleanSupplier.class, java.util.function.Consumer.class, Runnable.class)
                .invoke(null, service, service, CaptureLease.WECHAT, SystemClock.elapsedRealtime(),
                        SystemClock.elapsedRealtime(), (BooleanSupplier) current::get, (BooleanSupplier) () -> true, capturedUrl, completed);
    }

    private ResultReceiver begin() {
        start(); main.idle();
        return acknowledgement();
    }
    private ResultReceiver acknowledgement() {
        List<Intent> commands = broadcasts(ProbeContract.ARM);
        assertEquals(1, commands.size());
        Intent command = commands.get(0);
        assertEquals("android", command.getPackage());
        assertEquals(NONCE, command.getStringExtra("nonce"));
        assertEquals(ProbeContract.VERSION, command.getIntExtra("protocol", -1));
        assertEquals(ProbeContract.MODE, command.getStringExtra("mode"));
        Parcel parcel = Parcel.obtain();
        try {
            command.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            Intent delivered = Intent.CREATOR.createFromParcel(parcel);
            delivered.setExtrasClassLoader(new ClassLoader(ResultReceiver.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.startsWith("io.github.")) throw new ClassNotFoundException("Module classes not available to framework receiver");
                    return super.loadClass(name, resolve);
                }
            });
            @SuppressWarnings("deprecation") ResultReceiver ack = delivered.getParcelableExtra("ack");
            assertNotNull(ack); assertEquals(ResultReceiver.class, ack.getClass());
            return ack;
        } finally { parcel.recycle(); }
    }

    private ResultReceiver tapped() {
        ResultReceiver ack = begin();
        ack.send(ProbeContract.READY, response(true)); main.idleFor(Duration.ofMillis(64));
        assertEquals(1, broadcasts(ProbeContract.TAP).size());
        return ack;
    }

    private Bundle response(boolean blockReady) {
        Bundle result = new Bundle(); result.putString("nonce", NONCE);
        result.putInt("user", Process.myUid() / 100000); result.putInt("protocol", ProbeContract.VERSION);
        result.putString("mode", ProbeContract.MODE); result.putBoolean("block_ready", blockReady);
        result.putBoolean("release_confirmed", true);
        return result;
    }

    private List<Intent> broadcasts(String action) {
        ShadowApplication shadow = Shadow.extract(application);
        List<Intent> matches = new ArrayList<>();
        for (Intent command : shadow.getBroadcastIntents())
            if (action.equals(command.getAction())) matches.add(command);
        return matches;
    }

    private void assertPreparedWindows() {
        assertEquals(splashFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(mainFlags, service.mMainFloatView.wmParams.flags);
        assertEquals(1f, service.mSplashFloatView.wmParams.alpha, 0f);
    }

    private void assertRestoredWindows() {
        assertEquals(splashFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(mainFlags, service.mMainFloatView.wmParams.flags);
        assertTrue(service.mSplashFloatView.mIsShowing); assertTrue(service.mMainFloatView.mIsShowing);
        assertTrue(service.mSplashFloatView.mMainView.isAttachedToWindow());
        assertTrue(service.mMainFloatView.mMainView.isAttachedToWindow());
        assertEquals(View.VISIBLE, service.mSplashFloatView.mMainView.getVisibility());
        assertEquals(View.INVISIBLE, service.mMainFloatView.mMainView.getVisibility());
    }

    public static final class ProbeProvider extends ContentProvider {
        boolean ticketAvailable = true;
        boolean probeEnabled = true;
        int armCalls, cancelCalls;
        String source, cancelledNonce, lastStage, lastReason, ticketNonce=NONCE;
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if ("capture_available".equals(method)) {
                Bundle result = new Bundle(); result.putBoolean("enabled", probeEnabled); return result;
            }
            if ("probe_arm".equals(method)) {
                armCalls++; source = extras == null ? null : extras.getString("source");
                if (!ticketAvailable) return Bundle.EMPTY;
                Bundle result = new Bundle(); result.putString("nonce", ticketNonce);
                result.putInt("user", Process.myUid() / 100000); return result;
            }
            if ("capture_cancel".equals(method)) {
                cancelCalls++; cancelledNonce = extras == null ? null : extras.getString("nonce");
                return Bundle.EMPTY;
            }
            if ("probe_status".equals(method)) {
                lastStage=extras.getString("stage"); lastReason=extras.getString("reason");
                return Bundle.EMPTY;
            }
            throw new AssertionError("Unexpected provider call");
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    }
}
