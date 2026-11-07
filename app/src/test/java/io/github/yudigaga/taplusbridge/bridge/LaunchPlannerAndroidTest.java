package io.github.yudigaga.taplusbridge.bridge;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.routing.RouteRule;
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import android.content.Context;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class LaunchPlannerAndroidTest extends BridgeRobolectricFixture {
    @Before public void initializeNewRules() {
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        assertEquals(32, new RuleRepository(context).onlineRules().size());
    }
    @Test
    public void recognizedWebLinkRoutesToInstalledOfficialApp() {
        install(BILI, true, true);
        Bundle request = web("https://www.bilibili.com/video/BV1xx411c7mD?from=share");
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, request);
        assertNotNull(plan);
        assertEquals(BILI, plan.targetPackage());
        assertEquals(BILI, plan.target().getPackage());
        assertEquals(Intent.ACTION_VIEW, plan.target().getAction());
        assertEquals(request.getString("url"), plan.target().getDataString());
        assertEquals("com.example.source", plan.source());
        assertFalse(plan.target().hasCategory(Intent.CATEGORY_BROWSABLE));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, plan.target().getFlags());
        Intent original = request.getParcelable("original");
        assertSame(original, plan.original());
    }

    @Test
    public void disabledInstalledTargetStillProducesAPlanForThawing() {
        install(BILI, true, false);
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, web("https://b23.tv/abc123"));
        assertNotNull(plan);
        assertEquals(BILI, plan.targetPackage());
        assertEquals("https://b23.tv/abc123", plan.target().getDataString());
    }

    @Test
    public void unknownOrUninstalledTargetsCannotBecomeThawPlans() throws Exception {
        assertNull(LaunchPlanner.plan(context, web("https://b23.tv/abc123")));
        install(BILI, false, false);
        ApplicationInfo retained = context.getPackageManager().getApplicationInfo(BILI,
                PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS);
        assertEquals(0, retained.flags & ApplicationInfo.FLAG_INSTALLED);
        assertNull(LaunchPlanner.plan(context, web("https://b23.tv/abc123")));
    }

    @Test
    public void ambiguousWebRuleCannotFallThroughToNativeCandidate() {
        install(NATIVE, true, true);
        install("com.example.other", true, true);
        config.saveRules(List.of(new RouteRule("route.example", NATIVE, "*", false, false),
                new RouteRule("route.example", "com.example.other", "*", false, false)));
        Bundle request = web("https://route.example/path");
        request.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse("reader://article/123"))
                .setPackage(NATIVE));
        assertNull(LaunchPlanner.plan(context, request));
    }

    @Test
    public void browserWrapperCanRecoverWebUrlWhenNoSeparateUrlIsProvided() {
        install(BILI, true, true);
        String originalUrl = "https://b23.tv/abc123?key=one%2Btwo";
        Bundle request = new Bundle();
        request.putParcelable("original", new Intent(Intent.ACTION_VIEW,
                Uri.parse("mibrowser://home").buildUpon().appendQueryParameter("origin", "contentextension")
                        .appendQueryParameter("web_url", originalUrl).build()));
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, request);
        assertNotNull(plan);
        assertEquals(originalUrl, plan.target().getDataString());
    }

    @Test
    public void sourceRestrictedRuleUsesRecognizedSourceNotTaplusPackage() {
        install(NATIVE, true, true);
        config.saveRules(Collections.singletonList(new RouteRule("route.example", NATIVE,
                "com.example.source", false, false)));
        Bundle request = web("https://route.example/path");
        assertNotNull(LaunchPlanner.plan(context, request));
        request.putString("source", BridgeContract.HOST_PACKAGE);
        assertNull(LaunchPlanner.plan(context, request));
    }

    @Test
    public void nativeMainIsRebuiltAsPackageLauncherWithoutUntrustedExtras() {
        install(NATIVE, true, true);
        Intent originalCandidate = new Intent(Intent.ACTION_MAIN).setPackage(NATIVE)
                .putExtra("unused", "not forwarded").setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, nativeRequest(originalCandidate));
        assertNotNull(plan);
        assertEquals(NATIVE, plan.target().getPackage());
        assertTrue(plan.target().hasCategory(Intent.CATEGORY_LAUNCHER));
        assertFalse(plan.target().hasExtra("unused"));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, plan.target().getFlags());
    }

    @Test
    public void nativeViewRetainsLinkButDropsGrantsAndClipData() {
        install(NATIVE, true, true);
        Intent candidate = new Intent(Intent.ACTION_VIEW, Uri.parse("reader://article/123"))
                .setPackage(NATIVE).putExtra("mode", "article").setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        candidate.setClipData(ClipData.newPlainText("label", "private clipboard"));
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, nativeRequest(candidate));
        assertNotNull(plan);
        assertEquals("reader://article/123", plan.target().getDataString());
        assertEquals("article", plan.target().getStringExtra("mode"));
        assertNull(plan.target().getClipData());
        assertNull(plan.target().getSelector());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, plan.target().getFlags());
        assertNotNull(candidate.getClipData());
    }

    @Test
    public void nativeSendAndNonViewActionsAreNeverReplayed() {
        install(NATIVE, true, true);
        assertNull(LaunchPlanner.plan(context, nativeRequest(new Intent(Intent.ACTION_SEND)
                .setPackage(NATIVE).putExtra(Intent.EXTRA_TEXT, "some text"))));
        assertNull(LaunchPlanner.plan(context, nativeRequest(new Intent("com.example.ADMIN").setPackage(NATIVE))));
    }

    @Test
    public void dangerousNativeUrisAreDeclined() {
        install(NATIVE, true, true);
        for (String uri : new String[] { "file:///sdcard/x", "content://private/x", "javascript:alert(1)",
                "data:text/plain,hello", "intent://open#Intent;scheme=reader;end", "reader://a\nb" }) {
            assertNull(uri, LaunchPlanner.plan(context,
                    nativeRequest(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(NATIVE))));
        }
    }

    @Test
    public void moduleAndHostCannotBeTargetedByNativeIntentsOrUserRules() {
        for (String target : new String[] { BridgeContract.APP_PACKAGE, BridgeContract.HOST_PACKAGE }) {
            install(target, true, true);
            assertNull(LaunchPlanner.plan(context,
                    nativeRequest(new Intent(Intent.ACTION_MAIN).setPackage(target))));
            config.saveRules(Collections.singletonList(new RouteRule("route.example", target, "*", false, false)));
            assertNull(LaunchPlanner.plan(context, web("https://route.example/path")));
        }
    }

    @Test
    public void disabledConfigurationOrNativeSwitchDeclinesRelevantPlans() {
        install(BILI, true, true);
        install(NATIVE, true, true);
        config.setEnabled(false);
        assertNull(LaunchPlanner.plan(context, web("https://b23.tv/abc123")));
        config.setEnabled(true);
        config.setNativeEnabled(false);
        assertNull(LaunchPlanner.plan(context,
                nativeRequest(new Intent(Intent.ACTION_MAIN).setPackage(NATIVE))));
        assertNotNull(LaunchPlanner.plan(context, web("https://b23.tv/abc123")));
    }

    @Test
    public void unsafeOriginalFallbackDeclinesEvenWhenRouteMatches() {
        install(BILI, true, true);
        Bundle request = web("https://b23.tv/abc123");
        request.putParcelable("original", new Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/private")));
        assertNull(LaunchPlanner.plan(context, request));
        request.putParcelable("original", new Intent(Intent.ACTION_VIEW, Uri.parse("https://b23.tv/abc123"))
                .setPackage(BridgeContract.APP_PACKAGE));
        assertNull(LaunchPlanner.plan(context, request));
    }

    @Test public void equalInstalledOnlineBiliCandidatesAreAmbiguousUntilOneTargetIsDisabled() {
        String pili = "com.example.piliplus";
        install(BILI, true, false); install(pili, true, true);
        Bundle request = web("https://b23.tv/shared");
        LaunchPlanner.PlanningResult conflict = LaunchPlanner.planWithReason(context, request);
        assertNull(conflict.plan()); assertEquals("ambiguous", conflict.reason());
        RuleRepository repository = new RuleRepository(context); repository.setTargetEnabled(pili, false);
        LaunchPlanner.Plan official = LaunchPlanner.plan(context, request);
        assertNotNull(official); assertEquals(BILI, official.targetPackage());
        assertEquals(request.getString("url"), official.target().getDataString());
        repository.setTargetEnabled(BILI, false);
        assertNull(LaunchPlanner.plan(context, request));
    }
    @Test public void uninstalledHighPriorityManualTargetIsProjectedBeforeOnlineMatching() {
        install(BILI, true, false);
        RuleRepository repository = new RuleRepository(context);
        repository.replaceManualRules(List.of(new LinkRuleEntry("com.example.source", "b23.tv", "com.missing.reader",
                LinkRuleEntry.SOURCE_MANUAL, LinkRuleEntry.CONVERSION_NONE, "uninstalled override")));
        LaunchPlanner.Plan fallback = LaunchPlanner.plan(context, web("https://b23.tv/shared"));
        assertNotNull(fallback); assertEquals(BILI, fallback.targetPackage());
    }
    @Test public void installedManualOverrideOwnsLinkBeforeAmbiguousOnlineCandidates() {
        install(BILI, true, true); install("com.example.piliplus", true, true); install(NATIVE, true, false);
        new RuleRepository(context).replaceManualRules(List.of(new LinkRuleEntry("*", "b23.tv", NATIVE,
                LinkRuleEntry.SOURCE_MANUAL, LinkRuleEntry.CONVERSION_NONE, "reviewed user choice")));
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, web("https://b23.tv/shared"));
        assertNotNull(plan); assertEquals(NATIVE, plan.targetPackage());
    }
    @Test public void legacyFixedConversionFieldDoesNotRewriteTheMatchedHttpUrl() {
        config.setCloudLinkHandoffEnabled(false);
        install("com.baidu.netdisk", true, false);
        new RuleRepository(context).replaceManualRules(List.of(new LinkRuleEntry("*", "pan.baidu.com", "com.baidu.netdisk",
                LinkRuleEntry.SOURCE_MANUAL, LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK, "audited conversion")));
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, web("https://pan.baidu.com/s/1abcdef?pwd=code"));
        assertNotNull(plan); assertEquals("com.baidu.netdisk", plan.targetPackage());
        assertEquals("https://pan.baidu.com/s/1abcdef?pwd=code", plan.target().getDataString());
        assertEquals(Intent.ACTION_VIEW, plan.target().getAction());
        assertFalse(plan.target().hasCategory(Intent.CATEGORY_BROWSABLE));
    }
}
