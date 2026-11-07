package io.github.yudigaga.taplusbridge.capture;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.bridge.BridgeProvider;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowPackageManager;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class BridgeCaptureAuthAndroidTest {
    private Context context;
    private BridgeProvider provider;
    private int hostUid;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("capture", Context.MODE_PRIVATE).edit().clear().commit();
        ConfigStore config = new ConfigStore(context); config.setEnabled(true); config.setCaptureEnabled(true);
        int user = Process.myUid() / 100000;
        hostUid = user * 100000 + 10123;
        install(BridgeContract.HOST_PACKAGE, hostUid, 40013, "4.1.3");
        install(CaptureLease.WECHAT, user * 100000 + 21001, 1, "1");
        provider = Robolectric.buildContentProvider(BridgeProvider.class).create(BridgeContract.AUTHORITY).get();
    }

    @SuppressWarnings("deprecation")
    private void install(String pkg, int uid, int version, String name) {
        PackageInfo info = new PackageInfo(); info.packageName = pkg; info.versionCode = version; info.versionName = name;
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = pkg;
        info.applicationInfo.uid = uid; info.applicationInfo.enabled = true;
        info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        ShadowPackageManager pm = Shadow.extract(context.getPackageManager());
        pm.installPackage(info); pm.setPackagesForUid(uid, pkg);
    }

    @After public void resetCallingIdentity() { ShadowBinder.setCallingUid(Process.myUid()); }

    private Bundle armRequest() {
        Bundle data = new Bundle(); data.putString("source", CaptureLease.WECHAT); return data;
    }
    private Bundle nonceRequest(String nonce) {
        Bundle data = new Bundle(); data.putString("nonce", nonce); return data;
    }
    private String armAsHost() {
        ShadowBinder.setCallingUid(hostUid);
        String nonce = provider.call("capture_arm", null, armRequest()).getString("nonce");
        assertTrue(CaptureBroker.validNonce(nonce)); return nonce;
    }
    private Bundle observation(String nonce) {
        Bundle data = nonceRequest(nonce); data.putInt("user", Process.myUid() / 100000);
        data.putLong("observed_elapsed", SystemClock.elapsedRealtime());
        data.putString("component_package", CaptureLease.WECHAT);
        data.putString("component_class", "com.tencent.mm.plugin.webview.ui.tools.WebViewUI");
        data.putString("outcome", "NO_URL"); data.putString("origin", "NONE"); return data;
    }

    @Test public void unrelatedAppCannotArmOrCancelEvenWithKnownNonce() {
        String nonce = armAsHost();
        int attacker = hostUid + 1;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).setPackagesForUid(attacker, "com.example.unrelated");
        ShadowBinder.setCallingUid(attacker);
        assertTrue(provider.call("capture_arm", null, armRequest()).isEmpty());
        assertTrue(provider.call("capture_cancel", null, nonceRequest(nonce)).isEmpty());
        ShadowBinder.setCallingUid(1000);
        assertFalse(provider.call("capture_take", null, nonceRequest(nonce)).isEmpty());
    }

    @Test public void matchingHostNameFromAnotherUserCannotArm() {
        int otherProfileHost = hostUid + 100000;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).setPackagesForUid(otherProfileHost, BridgeContract.HOST_PACKAGE);
        ShadowBinder.setCallingUid(otherProfileHost);
        assertTrue(provider.call("capture_arm", null, armRequest()).isEmpty());
    }

    @Test public void onlySystemUidMayConsumeTicketAndReportMetadata() {
        String nonce = armAsHost();
        assertTrue(provider.call("capture_take", null, nonceRequest(nonce)).isEmpty());
        assertTrue(provider.call("capture_report", null, observation(nonce)).isEmpty());
        ShadowBinder.setCallingUid(101000);
        assertTrue(provider.call("capture_take", null, nonceRequest(nonce)).isEmpty());
        ShadowBinder.setCallingUid(1000);
        assertFalse(provider.call("capture_take", null, nonceRequest(nonce)).isEmpty());
        Bundle observed = observation(nonce);
        assertTrue(provider.call("capture_report", null, observed).getBoolean("saved"));
        assertFalse(provider.call("capture_report", null, observed).getBoolean("saved"));
        assertFalse(context.getSharedPreferences("capture", Context.MODE_PRIVATE).contains("nonce"));
    }

    @Test public void hostCanCancelItsCurrentCapabilityBeforeSystemConsumesIt() {
        String nonce = armAsHost();
        assertTrue(provider.call("capture_cancel", null, nonceRequest(nonce)).getBoolean("cancelled"));
        ShadowBinder.setCallingUid(1000);
        assertTrue(provider.call("capture_take", null, nonceRequest(nonce)).isEmpty());
    }

    @Test public void systemUidAloneCannotArmAndUnsupportedHostIsDeclined() {
        ShadowBinder.setCallingUid(1000);
        assertTrue(provider.call("capture_arm", null, armRequest()).isEmpty());
        install(BridgeContract.HOST_PACKAGE, hostUid, 40014, "4.1.4");
        ShadowBinder.setCallingUid(hostUid);
        Bundle response = provider.call("capture_arm", null, armRequest());
        assertNull(response.getString("nonce"));
        assertEquals("unsupported_host", response.getString("reason"));
    }

    @Test public void disabledSupplementalTapDeclinesProbeButKeepsManualObservation() {
        ShadowBinder.setCallingUid(hostUid);
        Bundle available = provider.call("capture_available", null, armRequest());
        assertFalse(available.getBoolean("enabled"));
        assertTrue(available.getInt("recognition_delay") > 0);
        assertNull(provider.call("probe_arm", null, armRequest()).getString("nonce"));
        String manual = provider.call("capture_arm", null, armRequest()).getString("nonce");
        assertTrue(CaptureBroker.validNonce(manual));
        ShadowBinder.setCallingUid(1000);
        assertEquals(CaptureLease.WECHAT, provider.call("capture_take", null, nonceRequest(manual)).getString("source"));
    }

    @Test public void disablingSupplementalTapRevokesAnIssuedProbeBeforeSystemTake() {
        context.getSharedPreferences("config", 0).edit().putBoolean(ConfigStore.SCOPE_LINK_PROBE_KEY, true).commit();
        ShadowBinder.setCallingUid(hostUid);
        assertTrue(provider.call("capture_available", null, armRequest()).getBoolean("enabled"));
        String nonce = provider.call("probe_arm", null, armRequest()).getString("nonce");
        assertTrue(CaptureBroker.validNonce(nonce));
        context.getSharedPreferences("config", 0).edit().putBoolean(ConfigStore.SCOPE_LINK_PROBE_KEY, false).commit();
        ShadowBinder.setCallingUid(1000);
        assertTrue(provider.call("capture_take", null, nonceRequest(nonce)).isEmpty());
    }

    @Test public void onlySystemCanRecheckAnArmedProbeAgainstTheCurrentSwitch() {
        context.getSharedPreferences("config", 0).edit().putBoolean(ConfigStore.SCOPE_LINK_PROBE_KEY, true).commit();
        ShadowBinder.setCallingUid(hostUid);
        String nonce = provider.call("probe_arm", null, armRequest()).getString("nonce");
        Bundle request = nonceRequest(nonce); request.putInt("user", Process.myUid() / 100000);
        assertFalse(provider.call("probe_valid", null, request).getBoolean("enabled"));
        ShadowBinder.setCallingUid(1000);
        assertFalse(provider.call("capture_take", null, request).isEmpty());
        assertTrue(provider.call("probe_valid", null, request).getBoolean("enabled"));
        context.getSharedPreferences("config", 0).edit().putBoolean(ConfigStore.SCOPE_LINK_PROBE_KEY, false).commit();
        assertFalse(provider.call("probe_valid", null, request).getBoolean("enabled"));
    }
}
