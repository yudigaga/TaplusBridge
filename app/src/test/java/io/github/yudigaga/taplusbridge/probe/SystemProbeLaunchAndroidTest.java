package io.github.yudigaga.taplusbridge.probe;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemProbeLaunchAndroidTest {
    private static final String NONCE = "a".repeat(43), OTHER_NONCE = "b".repeat(43);
    private static final String SOURCE = CaptureLease.WECHAT, URL = "https://example.com/card";
    private static final int UID = 21001;

    private static ProbeGate.Ticket arm(ProbeGate gate, String source, int uid, int user) {
        return gate.arm(NONCE, source, uid, user, SystemClock.elapsedRealtime());
    }

    private static ProbeGate.Ticket tapped(ProbeGate gate, String source, int uid, int user) {
        ProbeGate.Ticket ticket = arm(gate, source, uid, user);
        assertTrue(gate.released(ticket, SystemClock.elapsedRealtime()));
        assertTrue(gate.tap(NONCE, user, SystemClock.elapsedRealtime()));
        return ticket;
    }

    private static Intent wechatPage() {
        return new Intent().setComponent(new ComponentName(SOURCE,
                "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI"));
    }

    private static Intent link() { return new Intent(Intent.ACTION_VIEW, Uri.parse(URL)); }

    private static SystemProbe.LaunchClaim claim(ProbeGate gate, Intent intent) {
        return SystemProbe.claimLaunch(gate, UID, SOURCE, 0, intent, false);
    }

    private static void assertCapturedUrl(ProbeGate.Ticket ticket, SystemProbe.LaunchClaim claim, String url) {
        assertEquals(ProbeGate.Decision.CAPTURE, claim.decision());
        assertEquals(ticket.generation(), claim.generation());
        assertNotNull(claim.captured());
        assertEquals(url, claim.captured().url());
    }

    @Test public void wechatRawUrlWithoutActionOrDataIsCapturedWithoutMutatingTheRequest() {
        ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, UID, 0);
        Intent request = wechatPage().putExtra("rawUrl", URL);
        String before = request.toUri(0);
        SystemProbe.LaunchClaim claimed = claim(gate, request);
        assertCapturedUrl(ticket, claimed, URL);
        assertNull(request.getAction()); assertNull(request.getData());
        assertEquals(before, request.toUri(0));
        assertEquals(IntentObservation.UrlOrigin.RAW_URL, claimed.captured().metadata().urlOrigin());
        assertEquals(IntentObservation.RAW_URL_PRESENT, claimed.captured().metadata().fieldPresence());
        assertFalse(claimed.captured().forResult());
        assertEquals(SystemClock.elapsedRealtime(), claimed.captured().observedAt());
    }

    @Test public void qqKnownWebViewNestedExtrasSupplyTheUniqueUrl() {
        ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, CaptureLease.QQ, UID, 0);
        Bundle nested = new Bundle(); nested.putString("url", URL);
        Intent request = new Intent().setComponent(new ComponentName(CaptureLease.QQ,
                "com.tencent.mobileqq.activity.QQBrowserActivity")).putExtra("params", nested);
        SystemProbe.LaunchClaim claimed = SystemProbe.claimLaunch(gate, UID, CaptureLease.QQ, 0, request, false);
        assertCapturedUrl(ticket, claimed, URL);
        assertEquals(IntentObservation.UrlOrigin.NESTED, claimed.captured().metadata().urlOrigin());
        assertEquals(IntentObservation.NESTED_PRESENT, claimed.captured().metadata().fieldPresence());
    }

    @Test public void standardActionViewDataDoesNotRequireAnExplicitComponent() {
        ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, UID, 0);
        assertCapturedUrl(ticket, claim(gate, link()), URL);
    }

    @Test public void safeForResultLaunchUsesTheSameUrlPolicyAndRetainsItsMetadata() {
        for (Intent request : new Intent[]{link(), wechatPage().putExtra("rawUrl", URL)}) {
            ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, UID, 0);
            SystemProbe.LaunchClaim claimed = SystemProbe.claimLaunch(gate, UID, SOURCE, 0, request, true);
            assertCapturedUrl(ticket, claimed, URL);
            assertTrue(claimed.captured().forResult());
        }
    }

    @Test public void conflictingKnownWebViewUrlsConsumeTheProbeWithoutReturningAUrl() {
        ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, UID, 0);
        assertCapturedUrl(ticket, claim(gate, wechatPage().putExtra("rawUrl", URL)
                .putExtra("url", "https://different.example/card")), null);
    }

    @Test public void everyUriGrantRemainsUnsafeForOwnedLaunches() {
        for (int grant : new int[]{Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION}) {
            ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, UID, 0);
            assertCapturedUrl(ticket, claim(gate, wechatPage().putExtra("rawUrl", URL).addFlags(grant)), null);
        }
    }

    @Test public void clipDataCannotAuthorizeAUrlEvenWhenTheKnownFieldIsSafe() {
        ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, UID, 0);
        Intent request = wechatPage().putExtra("rawUrl", URL);
        request.setClipData(ClipData.newPlainText("link", URL));
        assertCapturedUrl(ticket, claim(gate, request), null);
    }

    @Test public void aKnownWebViewWithoutAUsableUrlStillConsumesOnlyItsOwnedProbe() {
        ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, UID, 0);
        assertCapturedUrl(ticket, claim(gate, wechatPage()), null);
    }

    @Test public void anotherSourceOrUidPassesWithoutClaimingTheCurrentTicket() {
        ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, UID, 0);
        SystemProbe.LaunchClaim wrongSource = SystemProbe.claimLaunch(gate, UID, CaptureLease.QQ, 0, link(), false);
        SystemProbe.LaunchClaim wrongUid = SystemProbe.claimLaunch(gate, UID + 1, SOURCE, 0, link(), false);
        assertEquals(ProbeGate.Decision.PASS, wrongSource.decision()); assertNull(wrongSource.captured());
        assertEquals(ProbeGate.Decision.PASS, wrongUid.decision()); assertNull(wrongUid.captured());
        assertTrue(gate.awaitingLaunch(ticket));
        assertCapturedUrl(ticket, claim(gate, link()), URL);
    }

    @Test public void unsupportedRequestedUsersKeepTheOwnedLaunchConsumedButReturnNoUrl() {
        for (int user : new int[]{1, -1, -10000}) {
            ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, UID, 0);
            assertCapturedUrl(ticket, SystemProbe.claimLaunch(gate, UID, SOURCE, user, link(), false), null);
        }
    }

    @Test public void currentUserAliasesBindToTheAuthenticatedProfile() {
        int profile = 10, profileUid = profile * 100000 + UID;
        for (int user : new int[]{profile, -2, -3}) {
            ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, profileUid, profile);
            assertCapturedUrl(ticket, SystemProbe.claimLaunch(gate, profileUid, SOURCE, user, link(), false), URL);
        }
    }

    @Test public void waitReleaseAndReadyNeverCaptureAnOrdinaryLaunchBeforeTheSyntheticTap() {
        ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = arm(gate, SOURCE, UID, 0);
        SystemProbe.LaunchClaim waiting = claim(gate, link());
        assertEquals(ProbeGate.Decision.PASS, waiting.decision()); assertNull(waiting.captured());
        assertTrue(gate.awaitingRelease(ticket));
        assertTrue(gate.released(ticket, SystemClock.elapsedRealtime()));
        SystemProbe.LaunchClaim ready = claim(gate, link());
        assertEquals(ProbeGate.Decision.PASS, ready.decision()); assertNull(ready.captured());
        assertTrue(gate.awaitingTap(ticket));
        assertTrue(gate.tap(NONCE, 0, SystemClock.elapsedRealtime()));
        assertCapturedUrl(ticket, claim(gate, link()), URL);
    }

    @Test public void aClaimCompletesOnceAndOnlyItsMatchingLateTailIsBlocked() {
        ProbeGate gate = new ProbeGate(); ProbeGate.Ticket ticket = tapped(gate, SOURCE, UID, 0);
        Intent request = wechatPage().putExtra("rawUrl", URL);
        assertCapturedUrl(ticket, claim(gate, request), URL);
        SystemProbe.LaunchClaim duplicate = claim(gate, request);
        assertEquals(ProbeGate.Decision.BLOCK_LATE, duplicate.decision()); assertNull(duplicate.captured());
        SystemProbe.LaunchClaim different = claim(gate, wechatPage().putExtra("rawUrl", "https://different.example/card"));
        assertEquals(ProbeGate.Decision.PASS, different.decision()); assertNull(different.captured());
        assertTrue(gate.finish(ticket)); assertFalse(gate.finish(ticket));
        assertEquals(ProbeGate.Decision.PASS, claim(gate, request).decision());
    }

    @Test public void aLateFingerprintDoesNotClaimTheNextGestureBeforeItsTap() {
        ProbeGate gate = new ProbeGate(); ProbeGate.Ticket old = tapped(gate, SOURCE, UID, 0);
        Intent request = wechatPage().putExtra("rawUrl", URL);
        assertCapturedUrl(old, claim(gate, request), URL); assertTrue(gate.finish(old));
        ProbeGate.Ticket fresh = gate.arm(OTHER_NONCE, SOURCE, UID, 0, SystemClock.elapsedRealtime());
        SystemProbe.LaunchClaim waiting = claim(gate, request);
        assertEquals(ProbeGate.Decision.BLOCK_LATE, waiting.decision()); assertNull(waiting.captured());
        assertTrue(gate.awaitingRelease(fresh));
        assertTrue(gate.released(fresh, SystemClock.elapsedRealtime()));
        SystemProbe.LaunchClaim ready = claim(gate, request);
        assertEquals(ProbeGate.Decision.BLOCK_LATE, ready.decision()); assertNull(ready.captured());
        assertTrue(gate.awaitingTap(fresh));
        assertTrue(gate.tap(OTHER_NONCE, 0, SystemClock.elapsedRealtime()));
        assertCapturedUrl(fresh, claim(gate, request), URL);
    }
}
