package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import io.github.yudigaga.taplusbridge.capture.CaptureBroker;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Exercises the authenticated Provider endpoint used when a user opens a probed link card. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class BridgeProbeRouteAndroidTest extends BridgeRobolectricFixture {
    private static final String LINK = "https://www.bilibili.com/video/BV1xx411c7mD?private=PROBE-NEVER-DISK";
    private BridgeProvider provider;
    private ShadowPackageManager pm;
    private int hostUid;

    @Before public void attachRealProviderAndTrustedHost() {
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        assertEquals(32, new RuleRepository(context).onlineRules().size());
        context.getSharedPreferences("capture", Context.MODE_PRIVATE).edit().clear().commit();
        pm = Shadow.extract(context.getPackageManager());
        hostUid = Process.myUid() + 1;
        installHost(40013, "4.1.3");
        install(CaptureLease.WECHAT, true, true);
        config.setEnabled(true); config.setCaptureEnabled(true);
        config.setScopeLinkProbeEnabled(true);
        provider = Robolectric.buildContentProvider(BridgeProvider.class).create(BridgeContract.AUTHORITY).get();
        ShadowBinder.setCallingUid(hostUid);
    }

    @After public void resetCallingUid() { ShadowBinder.setCallingUid(Process.myUid()); }

    @Test public void trustedCurrentProfileHostMayArmProbeWithoutWritingAnyLink() {
        Bundle request = sourceRequest(); request.putString("url", LINK);
        Bundle response = provider.call("probe_arm", null, request);
        assertTrue(CaptureBroker.validNonce(response.getString("nonce")));
        assertEquals(Process.myUid() / 100000, response.getInt("user", -1));
        assertEquals(CaptureLease.WECHAT, context.getSharedPreferences("capture", Context.MODE_PRIVATE)
                .getString("source", null));
        assertFalse(context.getSharedPreferences("capture", Context.MODE_PRIVATE).getAll().toString()
                .contains("PROBE-NEVER-DISK"));
        assertNoDiskLaunchTicket();
    }

    @Test public void captureAvailabilityReturnsConfiguredRecognitionDelayIndependentOfDragSwitch() {
        new ConfigStore(context).setDragSettings(new DragSettings(false, "portal", "share", "lens", "translate",
                false, java.util.List.of(), 1_400, 3_200));
        Bundle response = provider.call("capture_available", null, sourceRequest());
        assertTrue(response.getBoolean("enabled"));
        assertEquals(1_400, response.getInt("recognition_delay"));
    }

    @SuppressWarnings("deprecation")
    @Test public void disabledTargetUsesSingleUseMemoryTicketAndDropsOriginalIntent() {
        install(BILI, true, false);
        Bundle request = probeRequest();
        Intent original = request.getParcelable("original");
        Intent candidate = new Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://video/BV1xx411c7mD")).setPackage(BILI);
        request.putParcelable("candidate", candidate);
        String candidateBefore = candidate.toUri(0);
        Bundle response = provider.call(BridgeContract.PREPARE, null, request);
        String token = memoryToken(response);
        assertNull(response.getParcelable("direct"));
        assertNoDiskLaunchTicket();
        Bundle consumed = BridgeProvider.consume(context, token);
        assertNotNull(consumed);
        assertTrue(consumed.getBoolean("ephemeral"));
        assertFalse(consumed.containsKey("original"));
        assertEquals(LINK, consumed.getString("url"));
        assertEquals(CaptureLease.WECHAT, consumed.getString("source"));
        assertFalse(consumed.containsKey("candidate"));
        assertSame(original, request.getParcelable("original"));
        assertSame(candidate, request.getParcelable("candidate"));
        assertEquals(candidateBefore, candidate.toUri(0));
        LaunchPlanner.Plan planned = LaunchPlanner.plan(context, consumed);
        assertNotNull(planned); assertEquals(BILI, planned.targetPackage()); assertNull(planned.original());
        assertNull(BridgeProvider.consume(context, token));
        assertNoDiskLaunchTicket();
    }

    @Test public void hiddenInstalledTargetUsesMemoryTicketInsteadOfBrowserFallback() throws Exception {
        install(BILI, true, true);
        Bundle request = probeRequest();
        addResolvableHandler();
        Method hide = context.getPackageManager().getClass().getMethod("setApplicationHiddenSettingAsUser",
                String.class, boolean.class, UserHandle.class);
        hide.setAccessible(true);
        assertEquals(Boolean.TRUE, hide.invoke(context.getPackageManager(), BILI, true, Process.myUserHandle()));
        Bundle response = provider.call(BridgeContract.PREPARE, null, request);
        String token = memoryToken(response);
        assertFalse(response.containsKey("direct"));
        Bundle consumed = BridgeProvider.consume(context, token);
        assertNotNull(consumed); assertTrue(consumed.getBoolean("ephemeral"));
        assertFalse(consumed.containsKey("original"));
        assertNull(BridgeProvider.consume(context, token));
        assertNoDiskLaunchTicket();
    }

    @SuppressWarnings("deprecation")
    @Test public void enabledResolvableTargetReturnsOnlyTheCorrectDirectAppIntent() {
        install(BILI, true, true);
        Bundle request = probeRequest();
        addResolvableHandler();
        Bundle response = provider.call(BridgeContract.PREPARE, null, request);
        Intent direct = response.getParcelable("direct");
        assertNotNull(direct); assertEquals(BILI, direct.getPackage());
        assertEquals(Intent.ACTION_VIEW, direct.getAction());
        assertEquals(LINK, direct.getDataString());
        assertFalse(direct.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertNull(direct.getComponent());
        assertFalse(response.containsKey("token"));
        assertFalse(response.containsKey("original"));
        assertTrue(request.containsKey("original"));
        assertNoDiskLaunchTicket();
    }

    @SuppressWarnings("deprecation")
    @Test public void targetDisabledAfterIssueCannotRestoreTheBrowserOrUseAnOldLauncherCandidate() {
        install(BILI, true, false);
        Bundle request = probeRequest();
        Intent oldLauncher = new Intent(Intent.ACTION_MAIN).setPackage(BILI).addCategory(Intent.CATEGORY_LAUNCHER);
        request.putParcelable("candidate", oldLauncher);
        String token = memoryToken(provider.call(BridgeContract.PREPARE, null, request));
        new RuleRepository(context).setTargetEnabled(BILI, false);
        Bundle consumed = BridgeProvider.consume(context, token);
        assertNotNull(consumed); assertFalse(consumed.containsKey("original"));
        assertFalse(consumed.containsKey("candidate"));
        assertTrue(consumed.getBoolean("ephemeral"));
        LaunchPlanner.PlanningResult replanned = LaunchPlanner.planWithReason(context, consumed);
        // Ice's remaining PiliPlus candidate is uninstalled after the official target is disabled.
        assertNull(replanned.plan()); assertEquals("target_missing", replanned.reason());
        Bundle declined = provider.call(BridgeContract.PREPARE, null, request);
        assertEquals("target_missing", declined.getString("reason"));
        assertFalse(declined.containsKey("token")); assertFalse(declined.containsKey("direct"));
        assertFalse(declined.containsKey("original"));
        assertTrue(request.containsKey("original"));
        assertSame(oldLauncher, request.getParcelable("candidate"));
        assertEquals(Intent.ACTION_MAIN, oldLauncher.getAction());
        assertEquals(BILI, oldLauncher.getPackage());
        assertNull(BridgeProvider.consume(context, token));
        assertNoDiskLaunchTicket();
    }

    @Test public void unrelatedUidCannotArmProbeOrPrepareAnEphemeralLaunch() {
        install(BILI, true, false);
        int unrelated = hostUid + 1;
        pm.setPackagesForUid(unrelated, "com.example.unrelated");
        ShadowBinder.setCallingUid(unrelated);
        assertTrue(provider.call("probe_arm", null, sourceRequest()).isEmpty());
        assertTrue(provider.call(BridgeContract.PREPARE, null, probeRequest()).isEmpty());
        assertTrue(context.getSharedPreferences("capture", Context.MODE_PRIVATE).getAll().isEmpty());
        assertNoDiskLaunchTicket();
    }

    @Test public void matchingHostNameInAnotherProfileCannotArmOrPrepare() {
        install(BILI, true, false);
        int otherProfile = hostUid + 100000;
        pm.setPackagesForUid(otherProfile, BridgeContract.HOST_PACKAGE);
        ShadowBinder.setCallingUid(otherProfile);
        assertTrue(provider.call("probe_arm", null, sourceRequest()).isEmpty());
        assertTrue(provider.call(BridgeContract.PREPARE, null, probeRequest()).isEmpty());
        assertNoDiskLaunchTicket();
    }

    @Test public void unsupportedHostCannotUseProbeAliasOrEphemeralPrepare() {
        install(BILI, true, false); installHost(40014, "4.1.4");
        Bundle arm = provider.call("probe_arm", null, sourceRequest());
        Bundle prepare = provider.call(BridgeContract.PREPARE, null, probeRequest());
        assertEquals("unsupported_host", arm.getString("reason"));
        assertEquals("unsupported_host", prepare.getString("reason"));
        assertFalse(arm.containsKey("nonce")); assertFalse(prepare.containsKey("token"));
        assertNoDiskLaunchTicket();
    }

    @Test public void expiredInMemoryTicketCannotBeRecoveredFromDisk() {
        install(BILI, true, false);
        String token = memoryToken(provider.call(BridgeContract.PREPARE, null, probeRequest()));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
        assertNull(BridgeProvider.consume(context, token));
        assertNull(BridgeProvider.consume(context, token));
        assertNoDiskLaunchTicket();
    }

    private Bundle sourceRequest() {
        Bundle request = new Bundle(); request.putString("source", CaptureLease.WECHAT); return request;
    }

    private Bundle probeRequest() {
        Bundle request = sourceRequest(); request.putString("url", LINK); request.putBoolean("ephemeral", true);
        // It must be discarded before planning: this browser carrier itself has no Intent.data URL.
        Intent original = new Intent(Intent.ACTION_VIEW)
                .setClassName(CaptureLease.WECHAT, "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI")
                .putExtra("rawUrl", LINK);
        request.putParcelable("original", original);
        return request;
    }

    private String memoryToken(Bundle response) {
        String token = response.getString("token");
        assertNotNull(token); assertTrue(token.matches("p_[A-Za-z0-9_-]{43}"));
        return token;
    }

    @Test public void trustedHostCannotForgeTheModulesLocalExplicitSelection() {
        install(BILI,true,true);
        Bundle request=probeRequest();
        request.putString("source",BridgeContract.APP_PACKAGE);
        request.putString("explicit_target",BILI);
        assertEquals("invalid_request",provider.call(BridgeContract.PREPARE,null,request).getString("reason"));
        assertEquals("invalid_request",provider.call("resolve",null,request).getString("reason"));
        assertNoDiskLaunchTicket();
    }

    private void assertNoDiskLaunchTicket() {
        assertTrue(context.getSharedPreferences("launch_tickets", Context.MODE_PRIVATE).getAll().isEmpty());
        assertFalse(context.getSharedPreferences("status", Context.MODE_PRIVATE).getAll().toString()
                .contains("PROBE-NEVER-DISK"));
    }

    private void addResolvableHandler() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(LINK))
                .setPackage(BILI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo handler = new ResolveInfo(); handler.activityInfo = new ActivityInfo();
        handler.activityInfo.packageName = BILI; handler.activityInfo.name = BILI + ".OpenActivity";
        handler.activityInfo.enabled = true; handler.activityInfo.exported = true;
        handler.activityInfo.applicationInfo = new ApplicationInfo();
        handler.activityInfo.applicationInfo.packageName = BILI;
        handler.activityInfo.applicationInfo.enabled = true;
        handler.activityInfo.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        pm.addResolveInfoForIntent(intent, handler);
    }

    @SuppressWarnings("deprecation")
    private void installHost(int versionCode, String versionName) {
        PackageInfo host = new PackageInfo(); host.packageName = BridgeContract.HOST_PACKAGE;
        host.versionCode = versionCode; host.versionName = versionName;
        host.applicationInfo = new ApplicationInfo(); host.applicationInfo.packageName = host.packageName;
        host.applicationInfo.uid = hostUid; host.applicationInfo.enabled = true;
        host.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        pm.installPackage(host); pm.setPackagesForUid(hostUid, BridgeContract.HOST_PACKAGE);
    }
}
