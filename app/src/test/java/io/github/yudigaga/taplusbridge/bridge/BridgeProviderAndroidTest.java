package io.github.yudigaga.taplusbridge.bridge;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
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

    @Test public void allowedSourceReadsToolbarTargetsThroughItsOwnAuthenticatedEndpoint() {
        new ConfigStore(context).setEnabled(true);
        int sourceUid = hostUid + 1;
        ((org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager()))
                .setPackagesForUid(sourceUid, "example.source");
        ShadowBinder.setCallingUid(sourceUid);
        Bundle request = new Bundle(); request.putString("source", "example.source");
        assertTrue(provider.call("selection_actions", null, request).getBoolean("enabled"));
        assertTrue(provider.call("selection_actions", "unexpected", request).isEmpty());
        Bundle extra = new Bundle(request); extra.putString("text", "private selected text");
        assertTrue(provider.call("selection_actions", null, extra).isEmpty());
        ShadowBinder.setCallingUid(sourceUid + 1);
        assertTrue(provider.call("selection_actions", null, request).isEmpty());
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

    @Test public void actionTargetRequiresVerifiedHostAndExactPresentationOnlyInput() {
        ShadowBinder.setCallingUid(hostUid);
        Bundle request = actionTarget("translate");
        Bundle response = provider.call("action_target", null, request);
        assertEquals(java.util.Set.of("configured", "available"), response.keySet());
        assertFalse(response.getBoolean("configured"));
        assertFalse(response.getBoolean("available"));
        request.putString("text", "private original");
        assertTrue(provider.call("action_target", null, request).isEmpty());
        assertTrue(provider.call("action_target", "extra", actionTarget("translate")).isEmpty());
        assertTrue(provider.call("action_target", null, actionTarget("unknown")).isEmpty());
        ShadowBinder.setCallingUid(Process.myUid());
        assertTrue(provider.call("action_target", null, actionTarget("translate")).isEmpty());
        int foreign = hostUid + 100_000;
        ((org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager()))
                .setPackagesForUid(foreign, BridgeContract.HOST_PACKAGE);
        ShadowBinder.setCallingUid(foreign);
        assertTrue(provider.call("action_target", null, actionTarget("translate")).isEmpty());
        ShadowBinder.setCallingUid(hostUid); installHost(50016, "5.1.6");
        assertEquals("unsupported_host", provider.call("action_target", null, actionTarget("translate")).getString("reason"));
    }

    @Test public void aiActionTargetOnlyExposesModulePresentationAndNeverCredentials() {
        new io.github.yudigaga.taplusbridge.translation.TranslationStore(context).save(
                new io.github.yudigaga.taplusbridge.translation.TranslationSettings(
                        io.github.yudigaga.taplusbridge.translation.TranslationSettings.Mode.AI, "", "",
                        "https://private.example/v1/completions", "secret-key", "private-model", "中文", "private-prompt"));
        ShadowBinder.setCallingUid(hostUid);
        Bundle result = provider.call("action_target", null, actionTarget("translate"));
        assertTrue(result.getBoolean("configured")); assertTrue(result.getBoolean("available"));
        assertEquals(BridgeContract.APP_PACKAGE, result.getString("target"));
        assertEquals(java.util.Set.of("configured", "available", "target", "title", "icon"), result.keySet());
        android.graphics.Bitmap icon = result.getParcelable("icon");
        assertNotNull(icon); assertEquals(64, icon.getWidth()); assertEquals(64, icon.getHeight());
        assertFalse(result.toString().contains("secret-key"));
        assertFalse(result.toString().contains("private.example"));
        assertFalse(result.toString().contains("private-prompt"));
    }

    @Test public void selectedTranslationAppUsesItsPackageIconAndUnavailableTargetKeepsSelection() {
        ResolveInfo handler = actionHandler("com.example.translate", "Translate App");
        var pm = (org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager());
        pm.addResolveInfoForIntent(new Intent(Intent.ACTION_SEND).setType("text/plain"), handler);
        pm.setApplicationIcon("com.example.translate", new android.graphics.drawable.ColorDrawable(android.graphics.Color.MAGENTA));
        pm.addActivityIcon(new android.content.ComponentName("com.example.translate", "com.example.translate.Entry"),
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.GREEN));
        new io.github.yudigaga.taplusbridge.translation.TranslationStore(context).save(
                new io.github.yudigaga.taplusbridge.translation.TranslationSettings(
                        io.github.yudigaga.taplusbridge.translation.TranslationSettings.Mode.APP,
                        "com.example.translate/com.example.translate.Entry", Intent.ACTION_SEND, "", "", "", "中文", ""));
        ShadowBinder.setCallingUid(hostUid);
        Bundle result = provider.call("action_target", null, actionTarget("translate"));
        assertTrue(result.getBoolean("configured")); assertTrue(result.getBoolean("available"));
        assertEquals("com.example.translate", result.getString("target"));
        android.graphics.Bitmap icon = result.getParcelable("icon");
        assertNotNull(icon); assertEquals(64, icon.getWidth()); assertEquals(64, icon.getHeight());
        assertEquals("Use the actual app icon rather than the activity or stock translation icon", android.graphics.Color.MAGENTA, icon.getPixel(32, 32));
        context.getPackageManager().setComponentEnabledSetting(new android.content.ComponentName("com.example.translate", "com.example.translate.Entry"),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
        Bundle missing = provider.call("action_target", null, actionTarget("translate"));
        assertTrue(missing.getBoolean("configured")); assertFalse(missing.getBoolean("available"));
    }

    @Test public void translationPresentationUsesTheSameSourceScopeAsTheActualTranslationAction() {
        ResolveInfo handler = actionHandler("com.example.translate", "Translate App");
        var pm = (org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager());
        pm.addResolveInfoForIntent(new Intent(Intent.ACTION_SEND).setType("text/plain"), handler);
        new io.github.yudigaga.taplusbridge.translation.TranslationStore(context).save(
                new io.github.yudigaga.taplusbridge.translation.TranslationSettings(
                        io.github.yudigaga.taplusbridge.translation.TranslationSettings.Mode.APP,
                        "com.example.translate/com.example.translate.Entry", Intent.ACTION_SEND, "", "", "", "中文", ""));
        assertTrue(config.saveSourceScope(io.github.yudigaga.taplusbridge.config.SourceScopePolicy.Mode.WHITELIST,
                java.util.List.of(), java.util.List.of("com.example.allowed")));
        ShadowBinder.setCallingUid(hostUid);
        for (String source : new String[]{"com.example.allowed", "com.example.denied", null}) {
            Bundle presentationRequest = actionTarget("translate");
            Bundle translationRequest = new Bundle(); translationRequest.putString("text", "selected words");
            if (source != null) {
                presentationRequest.putString("source", source); translationRequest.putString("source", source);
            }
            Intent prepared = provider.call("translation_prepare", null, translationRequest).getParcelable("direct");
            Bundle presentation = provider.call("action_target", null, presentationRequest);
            boolean allowed = !"com.example.denied".equals(source);
            assertEquals("The saved APP selection must remain configured even for an excluded source", true, presentation.getBoolean("configured"));
            assertEquals(allowed, prepared != null);
            assertEquals("The icon must describe the same action that the click can prepare", prepared != null, presentation.getBoolean("available"));
            if (allowed) assertEquals("com.example.translate", presentation.getString("target"));
            else assertEquals(java.util.Set.of("configured", "available"), presentation.keySet());
        }
    }

    @Test public void actionTargetRejectsMalformedSourceAndUnknownFields() {
        ShadowBinder.setCallingUid(hostUid);
        for (Object source : new Object[]{"not a package", 7, null}) {
            Bundle request = actionTarget("translate");
            if (source instanceof String value) request.putString("source", value);
            else if (source instanceof Integer value) request.putInt("source", value);
            else request.putString("source", null);
            assertTrue(provider.call("action_target", null, request).isEmpty());
        }
        Bundle request = actionTarget("translate"); request.putString("source", "com.example.allowed");
        request.putString("endpoint", "https://private.example/api");
        assertTrue(provider.call("action_target", null, request).isEmpty());
    }

    @Test public void selectedBrowserIsSharedByUnmatchedPreviewAndActualLaunchWhileRulesWin() {
        selectBrowser(); ShadowBinder.setCallingUid(hostUid);
        Bundle preview = provider.call("resolve", null, web("https://ordinary.example/article"));
        assertEquals("com.example.browser", preview.getString("target"));
        assertTrue(preview.getBoolean("browser_fallback"));
        Bundle prepared = provider.call(BridgeContract.PREPARE, null, web("https://ordinary.example/article"));
        Intent direct = prepared.getParcelable("direct"); assertNotNull(direct);
        assertEquals(preview.getString("target"), direct.getPackage());
        assertEquals("https://ordinary.example/article", direct.getDataString());
        Bundle presentation = provider.call("action_target", null, actionTarget("browser"));
        assertTrue(presentation.getBoolean("configured")); assertTrue(presentation.getBoolean("available"));
        assertEquals(preview.getString("target"), presentation.getString("target"));
        assertEquals(BILI, provider.call("resolve", null, web("https://b23.tv/abc123")).getString("target"));
    }

    @Test public void unavailableSelectedBrowserDeclinesWithoutSilentlyFallingBackToAnotherBrowser() {
        selectBrowser(); install("com.example.browser", true, false); ShadowBinder.setCallingUid(hostUid);
        Bundle result = provider.call(BridgeContract.PREPARE, null, web("https://ordinary.example/article"));
        assertEquals("browser_unavailable", result.getString("reason"));
        assertNull(result.getParcelable("direct")); assertNull(result.getString("token"));
        Bundle presentation = provider.call("action_target", null, actionTarget("browser"));
        assertTrue(presentation.getBoolean("configured")); assertFalse(presentation.getBoolean("available"));
    }

    @Test public void selectedBrowserDoesNotReplaceNativeLauncherOrNativeHttpAppRecommendation() {
        selectBrowser(); install(NATIVE, true, true); ShadowBinder.setCallingUid(hostUid);
        Bundle nativeLaunch = nativeRequest(new Intent(Intent.ACTION_MAIN).setPackage(NATIVE).addCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(NATIVE, provider.call("resolve", null, nativeLaunch).getString("target"));
        Bundle nativeHttp = web("https://ordinary.example/article");
        nativeHttp.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse("https://ordinary.example/article")).setPackage(NATIVE));
        assertNull(provider.call("resolve", null, nativeHttp).getString("target"));
    }

    private Bundle actionTarget(String kind) { Bundle request = new Bundle(); request.putString("kind", kind); return request; }

    private ResolveInfo actionHandler(String target, String title) {
        install(target, true, true);
        ((org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager()))
                .setApplicationIcon(target, new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE));
        ResolveInfo handler = new ResolveInfo(); handler.isDefault = true; handler.nonLocalizedLabel = title;
        handler.activityInfo = new ActivityInfo(); handler.activityInfo.packageName = target;
        handler.activityInfo.name = target + ".Entry"; handler.activityInfo.enabled = true; handler.activityInfo.exported = true;
        try { handler.activityInfo.applicationInfo = context.getPackageManager().getApplicationInfo(target, 0); }
        catch (android.content.pm.PackageManager.NameNotFoundException impossible) { throw new AssertionError(impossible); }
        return handler;
    }

    private void selectBrowser() {
        ResolveInfo browser = actionHandler("com.example.browser", "My Browser");
        var pm = (org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(context.getPackageManager());
        browser.filter = new android.content.IntentFilter(Intent.ACTION_VIEW);
        browser.filter.addCategory(Intent.CATEGORY_DEFAULT); browser.filter.addCategory(Intent.CATEGORY_BROWSABLE);
        browser.filter.addDataScheme("https"); browser.filter.addDataScheme("http");
        for (String url : java.util.List.of("https://", "http://", "https://ordinary.example/article", "https://example.com", "https://example.com/")) {
            Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            pm.addResolveInfoForIntent(probe, browser);
            pm.addResolveInfoForIntent(new Intent(probe).setPackage("com.example.browser"), browser);
            probe.addCategory(Intent.CATEGORY_BROWSABLE);
            pm.addResolveInfoForIntent(probe, browser);
            pm.addResolveInfoForIntent(new Intent(probe).setPackage("com.example.browser"), browser);
        }
        pm.addResolveInfoForIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), browser);
        context.getSharedPreferences("taplus_bridge_browser", android.content.Context.MODE_PRIVATE).edit()
                .putString("selected_package", "com.example.browser").commit();
    }
}
