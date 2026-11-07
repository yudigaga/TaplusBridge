package io.github.yudigaga.taplusbridge.bridge;

import android.content.Intent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Receiver format taken from the supplied APK and the official share-page ULink configuration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class CloudLinkHandoffAndroidTest extends BridgeRobolectricFixture {
    @Test public void unknownFileDirectoryAndFragmentSelectorsAreNeverDroppedByShareRootAdapters() {
        String[][] providers = {{"com.mfcloudcalculate.networkdisk", "share.123pan.cn", "/123pan/AbCd-Ef12"},
                {"com.baidu.netdisk", "pan.baidu.com", "/s/1AbCd-Ef12"},
                {"com.quark.browser", "pan.quark.cn", "/s/AbCdEf12"}};
        for (String[] provider : providers) {
            installCloudRule(provider[0], provider[1]);
            for (String suffix : new String[]{"?fid=File1", "?pwd=A1b2&dirFid=Directory1", "#Folder1"}) {
                String url = "https://" + provider[1] + provider[2] + suffix;
                assertEquals(url, LaunchPlanner.plan(context, web(url)).target().getDataString());
            }
        }
    }

    @Test public void quarkBrowserAndDriveUseTheirOwnVerifiedOuterLinksAndNestedSharePayload() throws Exception {
        for (String target : new String[]{"com.quark.browser", "com.quark.clouddrive"}) {
            new RuleRepository(context).replaceManualRules(java.util.List.of());
            installCloudRule(target, "pan.quark.cn");
            Uri outer = LaunchPlanner.plan(context, web("https://pan.quark.cn/s/AbCd1234?pwd=A%2BB%26C")).target().getData();
            boolean browser = target.equals("com.quark.browser");
            assertEquals(browser ? "qklink" : "qkcloudlink", outer.getScheme());
            assertEquals("www.uc.cn", outer.getHost());
            assertEquals(browser ? "/b20b84fd735a8dd3f7541129bacc4e9a" : "/d28241335bda4522a538cc12052f89dd", outer.getPath());
            assertEquals("open_url", outer.getQueryParameter("action"));
            Uri landing = Uri.parse(outer.getQueryParameter("url"));
            assertEquals("https", landing.getScheme()); assertEquals("www.myquark.cn", landing.getHost());
            assertEquals("flutter", landing.getQueryParameter("qk_tech"));
            assertEquals("cloud_disk", landing.getQueryParameter("qk_biz"));
            assertEquals("/clouddrive/main", landing.getQueryParameter("qk_module"));
            org.json.JSONObject data = new org.json.JSONObject(landing.getQueryParameter("qk_params"));
            assertEquals("share", data.getJSONObject("params").getString("action"));
            assertEquals("AbCd1234", data.getJSONObject("params").getJSONObject("query").getString("pwd_id"));
            assertEquals("A+B&C", data.getJSONObject("params").getJSONObject("query").getString("passcode"));
            assertTrue(data.getJSONObject("flutter_view_mode").getBoolean("immerse"));
        }
    }
    @Test public void xunleiWebsiteUsesItsAndroidWebViewRouteWithTheExactUrl() {
        installCloudRule("com.xunlei.downloadprovider", "pan.xunlei.com");
        String url = "https://pan.xunlei.com/s/AbCd1234?pwd=A%2BB#MixedCase";
        Uri data = LaunchPlanner.plan(context, web(url)).target().getData();
        assertEquals("xunleiapp", data.getScheme()); assertEquals("xunlei.com", data.getHost());
        assertEquals("/webview", data.getPath()); assertEquals(url, data.getQueryParameter("url"));
        assertEquals("迅雷云盘", data.getQueryParameter("title"));
        assertEquals("pan.xunlei.com", data.getQueryParameter("from"));
    }

    @Test public void baiduOfficialShareRouteUsesTheShortShareKeyAndPreservesTheUrlPassword() {
        installCloudRule("com.baidu.netdisk", "pan.baidu.com");
        String url = "https://pan.baidu.com/s/1AbCd-Ef_12?pwd=A1b2";
        Uri data = LaunchPlanner.plan(context, web(url)).target().getData();
        assertEquals("bdnetdisk", data.getScheme()); assertEquals("n", data.getHost());
        assertEquals("/action.SHARE_LINK", data.getPath());
        assertEquals("AbCd-Ef_12", data.getQueryParameter("surl"));
        assertEquals("A1b2", data.getQueryParameter("pwd"));
        assertEquals("8.3.0", data.getQueryParameter("m_n_v"));
        config.setCloudLinkHandoffEnabled(false);
        assertEquals(url, LaunchPlanner.plan(context, web(url)).target().getDataString());
    }
    @Test public void aliOfficialUnprotectedShareUsesItsVerifiedBusinessRouteAndKeepsOtherPagesUntouched() {
        installCloudRule("com.alicloud.databox", "www.alipan.com");
        String url = "https://www.alipan.com/s/AbCdEf12345";
        Uri data = LaunchPlanner.plan(context, web(url)).target().getData();
        assertEquals("smartdrive", data.getScheme()); assertEquals("share", data.getHost());
        assertEquals("/browse", data.getPath());
        assertEquals("AbCdEf12345", data.getQueryParameter("shareId"));
        assertEquals("", data.getQueryParameter("sharePwd"));
        assertEquals("scheme", data.getQueryParameter("source_type"));
        for (String unsupported : new String[]{url + "?pwd=A1b2", "https://www.alipan.com/s/bottle", "https://www.alipan.com/s/iqiyi"})
            assertEquals(unsupported, LaunchPlanner.plan(context, web(unsupported)).target().getDataString());
    }
    @Test public void drive115WebsiteOpensInItsOwnBrowserWithTheCompleteOriginalUrl() throws Exception {
        for (String host : new String[]{"115.com", "115cdn.com", "anxia.com", "share.115.com"}) {
            installCloudRule("com.ylmf.androidclient", host);
            String url = "https://" + host + (host.equals("share.115.com") ? "/ExampleCode" : "/s/ExampleCode") + "?password=A%2BB&literal=A+B&unicode=%E4%B8%AD#MixedCase";
            String data = LaunchPlanner.plan(context, web(url)).target().getDataString();
            assertTrue(data, data.startsWith("oof.disk://url/"));
            assertEquals(url, java.net.URLDecoder.decode(data.substring("oof.disk://url/".length()), "UTF-8"));
        }
    }
    private void installCloudRule(String target, String host) {
        install(target, true, true);
        new RuleRepository(context).saveManualRule(-1, "*", host, target, "none", "user rule");
    }

    @Test public void disablingTheCloudAdapterKeepsTheOriginalWebsiteAndItsUserRule() {
        context.getSharedPreferences("config", 0).edit().putBoolean("cloud_link_handoff", false).commit();
        String original = "https://12345.share.123pan.cn/123pan/AbCd-Ef12?pwd=Keep%2BCase";
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, web(original));
        assertNotNull(plan); assertEquals(CLOUD, plan.targetPackage());
        assertEquals(original, plan.target().getDataString());
        assertEquals(Intent.ACTION_VIEW, plan.target().getAction());
        assertEquals(original, plan.original().getDataString());
    }

    @Test public void frameworkPlanningUsesItsExplicitAdapterChoiceAndTheLegacyOverloadStaysOff() {
        String original = "https://12345.share.123pan.cn/123pan/AbCd-Ef12";
        var rules = new RuleRepository(context).effectiveRules();
        Context framework = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                throw new AssertionError("Framework planning must not read module preferences: " + name);
            }
        };
        assertEquals(original, LaunchPlanner.planWithRules(framework, web(original), true, true, rules).plan().target().getDataString());
        assertEquals(original, LaunchPlanner.planWithRules(framework, web(original), true, true, false, rules).plan().target().getDataString());
        assertEquals("pan", LaunchPlanner.planWithRules(framework, web(original), true, true, true, rules).plan().target().getData().getScheme());
    }

    @Test public void disablingCloudAdaptersDoesNotDisableProtocolRulesOrSchemeCaseNormalization() {
        config.setCloudLinkHandoffEnabled(false);
        new RuleRepository(context).saveManualRule(-1, "*", "magnet:", CLOUD, "none", "user protocol rule");
        new RuleRepository(context).saveManualRule(-1, "*", "ed2k:", CLOUD, "none", "user protocol rule");
        for (String link : java.util.List.of("MAGNET:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=KeepCase",
                "ED2K://|file|KeepCase.zip|42|0123456789abcdef0123456789abcdef|/")) {
            LaunchPlanner.Plan plan = LaunchPlanner.plan(context, web(link));
            assertNotNull(plan); assertEquals(CLOUD, plan.targetPackage());
            String normalized = link.substring(0, link.indexOf(':')).toLowerCase(java.util.Locale.ROOT) + link.substring(link.indexOf(':'));
            assertEquals(normalized, plan.target().getDataString());
        }
    }

    @Test public void passwordQueryUsesTheSameDecodedNamesAndPlusSemanticsAsTheOfficialWebPage() {
        String base = "https://share.123pan.cn/123pan/AbCd-Ef12";
        assertEquals("A B+C", LaunchPlanner.plan(context, web(base + "?%70wd=A+B%2BC")).target().getData().getQueryParameter("sharePwd"));
        String ambiguous = base + "?%70wd=two&pwd=one";
        assertEquals(ambiguous, LaunchPlanner.plan(context, web(ambiguous)).target().getDataString());
    }

    private static final String CLOUD = "com.mfcloudcalculate.networkdisk";
    @Before public void rule() {
        install(CLOUD, true, true);
        new RuleRepository(context).saveManualRule(-1, "*", "share.123pan.cn", CLOUD, "none", "user rule");
    }
    @Test public void recognizedShareUsesTheOfficialBusinessRouteWithoutUploadingItOrAddingTrackingIds() {
        String url = "https://12345.share.123pan.cn/123pan/AbCdef-Gh12?pwd=A%2BB%26C";
        Bundle request = web(url);
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, request);
        assertNotNull(plan);
        assertEquals(CLOUD, plan.targetPackage());
        assertEquals(Intent.ACTION_VIEW, plan.target().getAction());
        Uri target = plan.target().getData();
        assertEquals("pan", target.getScheme()); assertEquals("umeng.com", target.getHost());
        assertEquals("/share/list", target.getPath());
        assertEquals("share_list", target.getQueryParameter("action"));
        assertEquals("AbCdef-Gh12", target.getQueryParameter("shareKey"));
        assertEquals("A+B&C", target.getQueryParameter("sharePwd"));
        assertEquals("umeng", target.getQueryParameter("_sdk_"));
        assertEquals("usr1ke01qbgngpn9", target.getQueryParameter("_linkid_"));
        assertEquals("ushare", target.getQueryParameter("_bizType_"));
        assertNull(target.getQueryParameter("_ukid"));
        assertEquals(url, request.getString("url"));
        assertEquals(url, plan.original().getDataString());
        assertTrue(plan.target().filterEquals(LaunchPlanner.plan(context, request).target()));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, plan.target().getFlags());
        assertNull(plan.target().getSelector()); assertNull(plan.target().getClipData());
    }
    @Test public void threeOfficialShareRoutesAndOptionalHtmlSuffixKeepTheirCaseSensitiveKey() {
        for (String path : new String[]{"/s/AbCd-Ef12.html", "/ps/AbCd-Ef12", "/123pan/AbCd-Ef12"}) {
            Uri target = LaunchPlanner.plan(context, web("https://share.123pan.cn" + path)).target().getData();
            assertEquals("pan", target.getScheme());
            assertEquals("AbCd-Ef12", target.getQueryParameter("shareKey"));
            assertEquals("", target.getQueryParameter("sharePwd"));
        }
    }
    @Test public void unsupportedPathsAndAmbiguousPasswordsNeverInventAnApplicationRoute() {
        for (String suffix : new String[]{"/download/file", "/123pan/A%2FB", "/123pan/Key/extra", "/123pan/Key?pwd=one&pwd=two"}) {
            String url = "https://share.123pan.cn" + suffix;
            assertEquals(url, LaunchPlanner.plan(context, web(url)).target().getDataString());
        }
    }
    @Test public void onlyTheConfiguredCloudTargetOnTheVerifiedShareHostUsesTheAdapter() {
        String other = "com.example.reader";
        install(other, true, true);
        RuleRepository repository = new RuleRepository(context);
        repository.replaceManualRules(java.util.List.of());
        repository.saveManualRule(-1, "*", "share.123pan.cn", other, "none", "user choice");
        String url = "https://share.123pan.cn/123pan/AbCd-Ef12";
        assertEquals(url, LaunchPlanner.plan(context, web(url)).target().getDataString());
        repository.saveManualRule(-1, "*", "share.123pan.cn.evil.example", CLOUD, "none", "other site");
        String spoof = "https://share.123pan.cn.evil.example/123pan/AbCd-Ef12";
        assertEquals(spoof, LaunchPlanner.plan(context, web(spoof)).target().getDataString());
    }
}
