package io.github.yudigaga.taplusbridge.capture;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import java.time.Duration;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class CaptureBrokerAndroidTest {
    private static final String SOURCE = CaptureLease.WECHAT;
    private static final String COMPONENT = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI";
    private Context context;
    private ConfigStore config;
    private int user;
    private int sourceUid;

    @Before public void resetPreferencesAndSource() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("capture", Context.MODE_PRIVATE).edit().clear().commit();
        config = new ConfigStore(context); config.setEnabled(true); config.setCaptureEnabled(true);
        user = Process.myUid() / 100000;
        sourceUid = user * 100000 + 21001;
        installSource(sourceUid);
    }

    private void installSource(int uid) {
        PackageInfo info = new PackageInfo(); info.packageName = SOURCE;
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = SOURCE;
        info.applicationInfo.uid = uid; info.applicationInfo.enabled = true;
        info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).installPackage(info);
    }

    private String arm() {
        Bundle result = CaptureBroker.arm(context, SOURCE);
        String nonce = result.getString("nonce");
        assertTrue(CaptureBroker.validNonce(nonce));
        assertEquals(user, result.getInt("user", -1));
        return nonce;
    }

    private Bundle report(String nonce, String outcome) {
        Bundle data = new Bundle(); data.putString("nonce", nonce); data.putInt("user", user);
        data.putLong("observed_elapsed", SystemClock.elapsedRealtime());
        data.putString("component_package", SOURCE); data.putString("component_class", COMPONENT);
        data.putString("outcome", outcome); data.putString("origin", "URL_FOUND".equals(outcome) ? "RAW_URL" : "NONE");
        data.putInt("url_count", "URL_FOUND".equals(outcome) ? 2 : 0);
        data.putInt("fields", IntentObservation.RAW_URL_PRESENT);
        data.putBoolean("for_result", true); data.putInt("launch_result", 0);
        return data;
    }

    @Test public void currentProfileSourceGetsSingleUseBootstrapTicket() {
        String nonce = arm();
        Bundle ticket = CaptureBroker.take(context, nonce);
        assertEquals(SOURCE, ticket.getString("source"));
        assertEquals(sourceUid, ticket.getInt("source_uid", -1));
        assertEquals(user, ticket.getInt("user", -1));
        assertTrue(CaptureBroker.take(context, nonce).isEmpty());
        assertTrue(CaptureBroker.status(context).contains("15秒"));
    }

    @Test public void bootstrapExpiresAtTwoAndAHalfSeconds() {
        String nonce = arm();
        ShadowSystemClock.advanceBy(Duration.ofMillis(2500));
        assertTrue(CaptureBroker.take(context, nonce).isEmpty());
        assertTrue(CaptureBroker.status(context).contains("未收到系统确认"));
    }

    @Test public void cancellationAndStaleNonceCannotConsumeReplacement() {
        String stale = arm();
        String current = arm();
        assertNotEquals(stale, current);
        assertFalse(CaptureBroker.cancel(context, stale));
        assertTrue(CaptureBroker.take(context, stale).isEmpty());
        assertTrue(CaptureBroker.cancel(context, current));
        assertTrue(CaptureBroker.take(context, current).isEmpty());
        assertFalse(CaptureBroker.cancel(context, current));
        assertEquals("捕获已取消", CaptureBroker.status(context));
    }

    @Test public void overwriteAfterTakePreventsOldReportFromReplacingNewRequest() {
        String stale = arm(); assertFalse(CaptureBroker.take(context, stale).isEmpty());
        Bundle oldReport = report(stale, "URL_FOUND");
        String current = arm();
        assertFalse(CaptureBroker.report(context, oldReport));
        assertFalse(CaptureBroker.take(context, current).isEmpty());
        assertTrue(CaptureBroker.report(context, report(current, "NO_URL")));
        assertEquals("NO_URL", context.getSharedPreferences("capture", Context.MODE_PRIVATE).getString("outcome", null));
    }

    @Test public void reportStoresAllowlistedMetadataOnceAndNeverPersistsRawUrlOrIntent() {
        String nonce = arm(); assertFalse(CaptureBroker.take(context, nonce).isEmpty());
        Bundle data = report(nonce, "URL_FOUND");
        String privateUrl = "https://private.example.org/message?secret=never-store-this";
        data.putString("url", privateUrl); data.putString("rawUrl", privateUrl);
        data.putParcelable("original", new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(privateUrl)));
        assertTrue(CaptureBroker.report(context, data));
        assertFalse(CaptureBroker.report(context, data));
        Map<String, ?> persisted = context.getSharedPreferences("capture", Context.MODE_PRIVATE).getAll();
        assertFalse(persisted.containsKey("nonce"));
        assertFalse(persisted.containsKey("url")); assertFalse(persisted.containsKey("rawUrl"));
        assertFalse(persisted.containsKey("original"));
        assertFalse(persisted.toString().contains("never-store-this"));
        assertEquals("captured", persisted.get("state"));
        assertEquals(COMPONENT, persisted.get("component"));
        assertEquals(2, persisted.get("url_count"));
        assertTrue(CaptureBroker.status(context).contains("启动请求中发现网址"));
        assertFalse(CaptureBroker.status(context).contains(privateUrl));
        assertFalse(CaptureBroker.status(context).contains(nonce));
    }

    @Test public void noUrlIsReportedAsObservationWithoutInventingADestination() {
        String nonce = arm(); assertFalse(CaptureBroker.take(context, nonce).isEmpty());
        assertTrue(CaptureBroker.report(context, report(nonce, "NO_URL")));
        assertTrue(CaptureBroker.status(context).contains("启动请求中未取得完整网址"));
        assertEquals(0, context.getSharedPreferences("capture", Context.MODE_PRIVATE).getInt("url_count", -1));
    }

    @Test public void reportRequiresArmedCurrentNonceMatchingUserAndSupportedComponent() {
        String nonce = arm();
        assertFalse(CaptureBroker.report(context, report(nonce, "URL_FOUND")));
        assertFalse(CaptureBroker.take(context, nonce).isEmpty());
        Bundle wrongUser = report(nonce, "URL_FOUND"); wrongUser.putInt("user", user + 1);
        assertFalse(CaptureBroker.report(context, wrongUser));
        Bundle wrongComponent = report(nonce, "URL_FOUND");
        wrongComponent.putString("component_class", "com.tencent.mm.ui.LauncherUI");
        assertFalse(CaptureBroker.report(context, wrongComponent));
        Bundle invalidOutcome = report(nonce, "URL_FOUND"); invalidOutcome.putString("outcome", "arbitrary");
        assertFalse(CaptureBroker.report(context, invalidOutcome));
        assertTrue(CaptureBroker.report(context, report(nonce, "URL_FOUND")));
    }

    @Test public void disabledSettingsAndOtherProfileSourceCannotArm() {
        config.setCaptureEnabled(false);
        assertNull(CaptureBroker.arm(context, SOURCE).getString("nonce"));
        config.setCaptureEnabled(true); config.setEnabled(false);
        assertNull(CaptureBroker.arm(context, SOURCE).getString("nonce"));
        config.setEnabled(true);
        assertNull(CaptureBroker.arm(context, "com.example.reader").getString("nonce"));
        installSource((user + 1) * 100000 + 21001);
        assertNull(CaptureBroker.arm(context, SOURCE).getString("nonce"));
    }

    @Test public void disablingEitherSwitchAfterArmRejectsTheLateObservation() {
        String first = arm(); assertFalse(CaptureBroker.take(context, first).isEmpty());
        config.setEnabled(false);
        assertFalse(CaptureBroker.report(context, report(first, "URL_FOUND")));
        assertFalse(context.getSharedPreferences("capture", Context.MODE_PRIVATE).contains("outcome"));
        config.setEnabled(true);
        String second = arm(); assertFalse(CaptureBroker.take(context, second).isEmpty());
        config.setCaptureEnabled(false);
        assertFalse(CaptureBroker.report(context, report(second, "URL_FOUND")));
        CaptureBroker.cancelCurrent(context);
        assertEquals("捕获已取消", CaptureBroker.status(context));
    }

    @Test public void automaticDiagnosticsRequireCurrentNonceAndFixedStage() {
        String nonce=arm(); Bundle data=new Bundle();
        data.putString("nonce",nonce); data.putString("stage","waiting_release"); data.putString("reason","waiting_release");
        assertTrue(CaptureBroker.recordProbeStatus(context,data));
        assertTrue(CaptureBroker.status(context).contains("真实触摸抬起"));
        Bundle stale=new Bundle(data); stale.putString("nonce","S".repeat(43));
        assertFalse(CaptureBroker.recordProbeStatus(context,stale));
        Bundle arbitrary=new Bundle(data); arbitrary.putString("stage","raw_private_page_title");
        assertFalse(CaptureBroker.recordProbeStatus(context,arbitrary));
        assertFalse(CaptureBroker.recordProbeStatus(context,new Bundle()));
        assertFalse(CaptureBroker.recordProbeStatus(context,null));
    }

    @Test public void diagnosticFailureIsSilentPersistedEnumAndSurvivesTransactionCleanup() {
        String nonce=arm(); Bundle data=new Bundle(); data.putString("nonce",nonce);
        data.putString("stage","failed"); data.putString("reason","https://private.example/token");
        data.putString("url","https://private.example/token"); data.putString("title","private title");
        assertTrue(CaptureBroker.recordProbeStatus(context,data));
        assertTrue(CaptureBroker.cancel(context,nonce));
        Map<String,?> persisted=context.getSharedPreferences("capture",Context.MODE_PRIVATE).getAll();
        assertEquals("failed",persisted.get("probe_stage")); assertEquals("unavailable",persisted.get("probe_reason"));
        assertFalse(persisted.toString().contains("private")); assertFalse(persisted.containsKey("nonce"));
        assertTrue(CaptureBroker.status(context).contains("自动识别未完成"));
        String fresh=arm(); assertNotEquals(nonce,fresh);
        assertFalse(CaptureBroker.recordProbeStatus(context,data));
        assertFalse(context.getSharedPreferences("capture",Context.MODE_PRIVATE).contains("probe_stage"));
    }

    @Test public void automaticSuccessRemainsVisibleAfterSystemConsumesNonce() {
        String nonce=arm(); assertFalse(CaptureBroker.take(context,nonce).isEmpty());
        Bundle status=new Bundle(); status.putString("nonce",nonce); status.putString("stage","captured"); status.putString("reason","captured");
        assertTrue(CaptureBroker.recordProbeStatus(context,status));
        assertTrue(CaptureBroker.report(context,report(nonce,"URL_FOUND")));
        assertTrue(CaptureBroker.status(context).contains("自动识别已取得链接"));
        assertFalse(CaptureBroker.recordProbeStatus(context,status));
        assertNotNull(arm());
    }

    @Test public void abandonedPhysicalReleaseWaitShowsTimeoutAndAllowsNewGeneration() {
        String nonce=arm(); Bundle status=new Bundle(); status.putString("nonce",nonce);
        status.putString("stage","waiting_release"); status.putString("reason","waiting_release");
        assertTrue(CaptureBroker.recordProbeStatus(context,status));
        ShadowSystemClock.advanceBy(Duration.ofMillis(6000));
        assertTrue(CaptureBroker.status(context).contains("自动识别已超时"));
        assertNotEquals(nonce,arm());
    }
}
