package io.github.yudigaga.taplusbridge.bridge;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class BridgeProviderAndroidTest extends BridgeRobolectricFixture {
    private BridgeProvider provider;
    private int hostUid;

    @Before
    public void attachProvider() {
        provider = Robolectric.buildContentProvider(BridgeProvider.class).create(BridgeContract.AUTHORITY).get();
        hostUid = Process.myUid() + 1;
        install(BILI, true, true);
        installHost(40013, "4.1.3");
    }

    @SuppressWarnings("deprecation")
    private void installHost(int versionCode, String versionName) {
        PackageInfo info = new PackageInfo();
        info.packageName = BridgeContract.HOST_PACKAGE;
        info.versionCode = versionCode;
        info.versionName = versionName;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = BridgeContract.HOST_PACKAGE;
        info.applicationInfo.uid = hostUid;
        info.applicationInfo.enabled = true;
        info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        ((org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager())).installPackage(info);
        ((org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager())).setPackagesForUid(hostUid, BridgeContract.HOST_PACKAGE);
    }

    @After
    public void resetBinderIdentity() {
        ShadowBinder.setCallingUid(Process.myUid());
    }

    @Test
    public void verifiedHostUidAndVersionCanPrepareSingleUseTicket() {
        ShadowBinder.setCallingUid(hostUid);
        Bundle response = provider.call(BridgeContract.PREPARE, null, web("https://b23.tv/abc123"));
        String token = response.getString("token");
        assertNotNull(token);
        assertNotNull(BridgeProvider.consume(context, token));
        assertNull(BridgeProvider.consume(context, token));
    }

    @Test public void updatedHostCanResolveAndPrepareSingleUseTicket() {
        installHost(50015, "5.1.5");
        ShadowBinder.setCallingUid(hostUid);
        Bundle request = web("https://b23.tv/abc123");
        assertEquals(BILI, provider.call("resolve", null, request).getString("target"));
        String token = provider.call(BridgeContract.PREPARE, null, request).getString("token");
        assertNotNull(token);
        assertNotNull(BridgeProvider.consume(context, token));
        assertNull(BridgeProvider.consume(context, token));
    }

    @Test public void mixedOrUninspectedUpdatedHostVersionsAreDeclined() {
        ShadowBinder.setCallingUid(hostUid);
        int[] codes = {40013, 50015, 50016, 50015};
        String[] names = {"5.1.5", "4.1.3", "5.1.5", "5.1.6"};
        for (int i = 0; i < codes.length; i++) {
            installHost(codes[i], names[i]);
            assertEquals("unsupported_host", provider.call(BridgeContract.PREPARE, null,
                    web("https://b23.tv/abc123")).getString("reason"));
        }
    }

    @Test public void onlyVerifiedHostCanReadSavedSourceScopeAndCannotReplaceIt() {
        var policy = io.github.yudigaga.taplusbridge.config.SourceScopePolicy.of(
                io.github.yudigaga.taplusbridge.config.SourceScopePolicy.Mode.WHITELIST,
                java.util.Set.of("com.example.blocked"), java.util.Set.of("com.example.allowed"));
        assertTrue(config.saveSourceScope(policy.mode, policy.blacklist, policy.whitelist));
        installHost(50015, "5.1.5"); ShadowBinder.setCallingUid(hostUid);
        Bundle forged = new Bundle(); forged.putString("policy", "untrusted");
        assertEquals(policy.encode(), provider.call("source_scope", null, forged).getString("policy"));
        assertEquals(policy.encode(), config.sourceScope().encode());
        ShadowBinder.setCallingUid(Process.myUid());
        assertNull(provider.call("source_scope", null, null).getString("policy"));
        ShadowBinder.setCallingUid(hostUid); installHost(50016, "5.1.6");
        assertEquals("unsupported_host", provider.call("source_scope", null, null).getString("reason"));
    }

    @Test public void recognitionChannelsAreHostOnlyAndReturnTheDurableChoiceWithoutAcceptingCallerOverrides()throws Exception {
        String channels="{\"native\":true,\"assist\":false,\"ocr\":false,\"server\":true,\"segment\":true,\"clipboard\":false}";
        context.getSharedPreferences("config",0).edit().putString("recognition_channels",channels).commit();
        ShadowBinder.setCallingUid(hostUid);
        Bundle forged=new Bundle();forged.putString("channels","{}");
        Bundle response=provider.call("recognition_channels",null,forged);
        assertNotNull(response.getString("channels"));
        assertFalse(new org.json.JSONObject(response.getString("channels")).getBoolean("assist"));
        assertTrue(response.getBoolean("active"));
        assertEquals(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.parse(
                io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.from(context)).identity,response.getString("identity"));
        config.setRecognitionEnabled(false);
        assertFalse(provider.call("recognition_channels",null,null).getBoolean("active",true));
        ShadowBinder.setCallingUid(Process.myUid());
        assertNull(provider.call("recognition_channels",null,null).getString("channels"));
        ShadowBinder.setCallingUid(hostUid);installHost(50016,"5.1.6");
        assertEquals("unsupported_host",provider.call("recognition_channels",null,null).getString("reason"));
    }

    @Test
    public void unrelatedLocalAppCannotPrepareTicketEvenWithValidRequest() {
        int attackerUid = hostUid + 1;
        ((org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager())).setPackagesForUid(attackerUid, "com.example.unrelated");
        ShadowBinder.setCallingUid(attackerUid);
        assertNull(provider.call(BridgeContract.PREPARE, null, web("https://b23.tv/abc123")).getString("token"));
    }

    @Test public void lensConfigIsIndependentAndCanBeDisabledWithoutChangingLinkRouting() {
        ShadowBinder.setCallingUid(hostUid);
        assertTrue(provider.call("lens_config", null, null).getBoolean("enabled"));
        config.setEnabled(false); config.setRecognitionEnabled(false);
        assertTrue(provider.call("lens_config", null, null).getBoolean("enabled"));
        config.setLensEnabled(false);
        assertFalse(provider.call("lens_config", null, null).getBoolean("enabled", true));
        assertFalse(new ConfigStore(context).lensEnabled());
    }

    @Test public void lensWorkerCanReadCurrentPreferenceFromMainProcess() {
        ShadowBinder.setCallingUid(Process.myUid());
        config.setLensEnabled(false);
        assertFalse(provider.call("lens_config", null, null).getBoolean("enabled", true));
        config.setLensEnabled(true);
        assertTrue(provider.call("lens_config", null, null).getBoolean("enabled"));
    }

    @Test public void lensConfigRejectsForeignUserAndUnknownHostVersion() {
        ShadowBinder.setCallingUid(hostUid + 100_000);
        assertFalse(provider.call("lens_config", null, null).containsKey("enabled"));
        ShadowBinder.setCallingUid(hostUid);
        installHost(50016, "5.1.6");
        assertEquals("unsupported_host", provider.call("lens_config", null, null).getString("reason"));
    }

    @Test public void lensConfigRejectsUnrelatedLocalApplication() {
        int other = hostUid + 2;
        ((org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager()))
                .setPackagesForUid(other, "com.example.other");
        ShadowBinder.setCallingUid(other);
        assertFalse(provider.call("lens_config", null, null).containsKey("enabled"));
    }

    @Test
    public void sameHostPackageInAnotherProfileCannotPrepareTicket() {
        int otherUserUid = hostUid + 100_000;
        ((org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager())).setPackagesForUid(otherUserUid, BridgeContract.HOST_PACKAGE);
        ShadowBinder.setCallingUid(otherUserUid);
        assertNull(provider.call(BridgeContract.PREPARE, null, web("https://b23.tv/abc123")).getString("token"));
    }

    @Test
    public void unsupportedHostVersionIsDeclined() {
        installHost(40014, "4.1.4");
        ShadowBinder.setCallingUid(hostUid);
        assertNull(provider.call(BridgeContract.PREPARE, null, web("https://b23.tv/abc123")).getString("token"));
    }

    @Test
    public void invalidMethodOrNoPlanNeverIssuesTicket() {
        ShadowBinder.setCallingUid(hostUid);
        assertNull(provider.call("unrelated", null, web("https://b23.tv/abc123")).getString("token"));
        assertNull(provider.call(BridgeContract.PREPARE, null, null).getString("token"));
        config.setEnabled(false);
        assertNull(provider.call(BridgeContract.PREPARE, null, web("https://b23.tv/abc123")).getString("token"));
    }

    @Test public void previewReturnsActualTargetLabelWithoutIssuingLaunchTicket() {
        ShadowBinder.setCallingUid(hostUid);
        Bundle preview = provider.call("resolve", null, web("https://b23.tv/abc123"));
        assertEquals(BILI, preview.getString("target"));
        assertNotNull(preview.getString("title"));
        assertFalse(preview.containsKey("token"));
        config.setRecognitionEnabled(false);
        Bundle local = web("https://b23.tv/abc123"); local.putBoolean("local_recognition", true);
        assertEquals("recognition_disabled", provider.call("resolve", null, local).getString("reason"));
        assertEquals(BILI, provider.call("resolve", null, web("https://b23.tv/abc123")).getString("target"));
    }

    @Test public void readyResolvableTargetReturnsDirectIntentAndFrozenOneKeepsTicket() {
        ShadowBinder.setCallingUid(hostUid);
        Bundle request = web("https://b23.tv/abc123");
        Intent target = LaunchPlanner.plan(context, request).target();
        ResolveInfo handler = new ResolveInfo(); handler.activityInfo = new ActivityInfo();
        handler.activityInfo.packageName = BILI; handler.activityInfo.name = BILI + ".OpenActivity";
        handler.activityInfo.applicationInfo = new ApplicationInfo();
        handler.activityInfo.applicationInfo.enabled = true; handler.activityInfo.exported = true;
        org.robolectric.shadows.ShadowPackageManager pm = org.robolectric.shadow.api.Shadow.extract(context.getPackageManager());
        pm.addResolveInfoForIntent(target, handler);
        Bundle direct = provider.call(BridgeContract.PREPARE, null, request);
        Intent launch = direct.getParcelable("direct");
        assertNotNull(launch); assertEquals(BILI, launch.getPackage());
        assertFalse(direct.containsKey("token"));
        install(BILI, true, false);
        Bundle frozen = provider.call(BridgeContract.PREPARE, null, request);
        assertNull(frozen.getParcelable("direct")); assertNotNull(frozen.getString("token"));
    }
}
