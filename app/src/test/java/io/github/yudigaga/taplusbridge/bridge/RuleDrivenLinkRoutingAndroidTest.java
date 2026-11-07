package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import io.github.yudigaga.taplusbridge.rules.RuleConfigTransfer;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The user configures each domain's target; routing has no per-application URL templates. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class RuleDrivenLinkRoutingAndroidTest extends BridgeRobolectricFixture {
    @Test public void downloadLinksNeedRulesAndKeepTheCompleteOriginalDataForTheChosenApplication() {
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=one%2Btwo&tr=https%3A%2F%2Ftracker.example%2F";
        String ed2k = "ed2k://|file|%E6%B5%8B%E8%AF%95.iso|123456|0123456789abcdef0123456789abcdef|/";
        for (String link : List.of(magnet, ed2k)) {
            Bundle unmatched = web(link);
            unmatched.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(OTHER));
            declined(unmatched, "no_match");
            unmatched.putParcelable("candidate", new Intent(Intent.ACTION_MAIN).setPackage(OTHER));
            declined(unmatched, "no_match");
            unmatched.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse("reader://native/download")).setPackage(OTHER));
            declined(unmatched, "no_match");
        }
        save("magnet:", CHOSEN); save("ed2k:", OTHER);
        routed(magnet, CHOSEN); routed(ed2k, OTHER);
        repository.setTargetEnabled(CHOSEN, false);
        Bundle disabled = web(magnet);
        disabled.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse(magnet)).setPackage(OTHER));
        declined(disabled, "no_match");
    }

    @Test public void protocolSchemeCaseIsCanonicalizedForAndroidFiltersWithoutChangingPayloadCase() {
        save("magnet:", CHOSEN); save("ed2k:", OTHER);
        for (String link : List.of("MAGNET:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=MixedCase",
                "ED2K://|file|MixedCase.iso|123456|0123456789ABCDEF0123456789ABCDEF|/")) {
            LaunchPlanner.Plan plan = LaunchPlanner.plan(context, web(link));
            assertNotNull(plan);
            String expected = link.substring(0, link.indexOf(':')).toLowerCase(java.util.Locale.ROOT) + link.substring(link.indexOf(':'));
            assertEquals(expected, plan.target().getDataString());
        }
    }

    private static final String CHOSEN = "com.example.chosenreader", OTHER = "com.example.otherreader";
    private static final String URL = "https://unlisted.example/share/article?item=one%2Btwo&from=message#details";
    private RuleRepository repository;
    @Before public void installedChoices() {
        install(CHOSEN, true, true); install(OTHER, true, true);
        repository = new RuleRepository(context);
    }
    private void save(String domainOrUrl, String target) {
        repository.saveManualRule(-1, "*", domainOrUrl, target, LinkRuleEntry.CONVERSION_NONE, "user choice");
    }
    private LaunchPlanner.Plan routed(String url, String target) { return routed(web(url), target, url); }
    private LaunchPlanner.Plan routed(Bundle request, String target, String url) {
        LaunchPlanner.PlanningResult result = LaunchPlanner.planWithReason(context, request);
        assertEquals("resolved", result.reason()); assertNotNull(result.plan());
        LaunchPlanner.Plan plan = result.plan(); assertEquals(target, plan.targetPackage());
        assertEquals(Intent.ACTION_VIEW, plan.target().getAction()); assertEquals(target, plan.target().getPackage());
        assertEquals(url, plan.target().getDataString()); assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, plan.target().getFlags());
        assertFalse(plan.target().hasCategory(Intent.CATEGORY_BROWSABLE)); assertFalse(plan.target().hasCategory(Intent.CATEGORY_LAUNCHER));
        assertNull(plan.target().getComponent()); assertNull(plan.target().getSelector()); assertNull(plan.target().getClipData());
        return plan;
    }
    private void declined(Bundle request, String reason) {
        LaunchPlanner.PlanningResult result = LaunchPlanner.planWithReason(context, request);
        assertNull(result.plan()); assertEquals(reason, result.reason());
    }

    @Test public void selectingAnApplicationAndPastingAFullUrlStoresOnlyItsDomain() {
        save("https://unlisted.example/some/old?query=one#fragment", CHOSEN);
        assertEquals("unlisted.example", repository.manualRules().get(0).host);
        routed(URL, CHOSEN);
    }
    @Test public void anUnknownWebsiteNeedsOnlyTheUsersRuleAndNoSoftwareAdapter() {
        declined(web(URL), "no_match"); save("unlisted.example", CHOSEN); routed(URL, CHOSEN);
    }
    @Test public void shortLinkRulesKeepTheOriginalHttpUrlWithoutResolvingOrRewritingIt() {
        save("https://t.cn/old-share", CHOSEN);
        routed("https://t.cn/A1b2C3d?from=share#part", CHOSEN);
    }
    @Test public void portsEscapesQueryAndFragmentRemainByteForByteUnchanged() {
        String url = "http://s.example:8080/Path/%E4%B8%AD?q=a%2Bb&next=%2Fa%3Fb%3D2#Frag";
        save(url, CHOSEN); assertEquals("s.example", repository.manualRules().get(0).host); routed(url, CHOSEN);
    }
    @Test public void twoDifferentDomainsDoNotStealEachOthersTargetApplication() {
        save("unlisted.example", CHOSEN); save("another.example", OTHER);
        routed(URL, CHOSEN); routed("https://another.example/posts/2", OTHER);
        routed("https://sub.unlisted.example/fresh", CHOSEN);
    }
    @Test public void anUnmatchedHttpCandidateCannotSendTheLinkToAnUnconfiguredApplication() {
        save("another.example", OTHER);
        Bundle request = web(URL);
        request.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse(URL)).setPackage(OTHER));
        declined(request, "no_match");
    }
    @Test public void disablingARuleTargetCannotFallThroughToAnotherHttpApplication() {
        save("unlisted.example", CHOSEN); repository.setTargetEnabled(CHOSEN, false);
        Bundle request = web(URL);
        request.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse(URL)).setPackage(OTHER));
        declined(request, "no_match");
        repository.setTargetEnabled(CHOSEN, true); routed(URL, CHOSEN);
    }
    @Test public void equalPriorityInstalledRuleTargetsAreAmbiguousInsteadOfGuessing() {
        save("unlisted.example", CHOSEN); save("unlisted.example", OTHER);
        Bundle request = web(URL);
        request.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse(URL)).setPackage(OTHER));
        declined(request, "ambiguous"); repository.setTargetEnabled(OTHER, false); routed(URL, CHOSEN);
    }
    @Test public void sourceSpecificRuleWinsOverWildcardAndUnknownSourceUsesWildcard() {
        save("unlisted.example", OTHER);
        repository.saveManualRule(-1, "com.tencent.mm", "unlisted.example", CHOSEN, "none", "source choice");
        Bundle fromWechat = web(URL); fromWechat.putString("source", "com.tencent.mm"); routed(fromWechat, CHOSEN, URL);
        Bundle unknown = web(URL); unknown.remove("source"); routed(unknown, OTHER, URL);
        routed(URL, OTHER);
    }
    @Test public void aSourceOnlyRuleDoesNotLeakIntoAnUnrelatedOrUnknownSource() {
        repository.saveManualRule(-1, "com.tencent.mm", "unlisted.example", CHOSEN, "none", "source choice");
        declined(web(URL), "no_match"); Bundle unknown = web(URL); unknown.remove("source"); declined(unknown, "no_match");
    }
    @Test public void anInstalledManualDomainChoiceWinsOverBothOnlineBiliCandidates() {
        install(BILI, true, true); install("com.example.piliplus", true, true);
        save("b23.tv", CHOSEN); routed("https://b23.tv/AbCd123", CHOSEN);
    }
    @Test public void legacyConversionFieldCanBeImportedButCannotRewriteTheHttpUrlAtRuntime() throws Exception {
        LinkRuleEntry old = new LinkRuleEntry("*", "unlisted.example", CHOSEN, "manual", "fixed_deep_link", "old imported field");
        assertEquals(1, repository.importManualRules(RuleConfigTransfer.exportJson(List.of(old))));
        assertTrue(repository.manualRules().get(0).hasFixedUrlConversion()); routed(URL, CHOSEN);
    }
    @Test public void aFrozenInstalledRuleTargetRetainsTheOriginalLinkForThawing() {
        install(CHOSEN, true, false); save("unlisted.example", CHOSEN);
        assertNull(LaunchPlanner.directIfReady(context, routed(URL, CHOSEN)));
    }
    @Test public void aMissingRuleTargetCannotBeReplacedByAnotherDomainsInstalledApp() {
        save("unlisted.example", "com.example.missing"); save("another.example", OTHER);
        declined(web(URL), "target_missing"); install("com.example.missing", false, false);
        IceRuleRouting.invalidateInstalledTarget("com.example.missing");
        declined(web(URL), "target_missing");
    }
    @Test public void invalidRuleStorageCannotOpenAnUnrelatedHttpCandidate() {
        save("unlisted.example", CHOSEN);
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().putString("manual", "{broken-json").commit();
        Bundle request = web(URL); request.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse(URL)).setPackage(OTHER));
        assertTrue(repository.effectiveRules().isEmpty()); assertNull(LaunchPlanner.plan(context, request));
    }
    @Test public void theMasterSwitchStillControlsEveryRuleBasedHandoff() {
        save("unlisted.example", CHOSEN); config.setEnabled(false); declined(web(URL), "disabled");
    }
    @Test public void unrelatedNativeDeepLinksAndLauncherRecommendationsKeepTheirExistingBehavior() {
        Bundle nativeRequest = new Bundle();
        nativeRequest.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse("reader://article/42?mode=full")).setPackage(OTHER));
        LaunchPlanner.Plan nativePlan = LaunchPlanner.plan(context, nativeRequest);
        assertNotNull(nativePlan); assertEquals(OTHER, nativePlan.targetPackage());
        assertEquals("reader://article/42?mode=full", nativePlan.target().getDataString());
        nativeRequest.putParcelable("candidate", new Intent(Intent.ACTION_MAIN).setPackage(OTHER).addCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(Intent.ACTION_MAIN, LaunchPlanner.plan(context, nativeRequest).target().getAction());
        assertNull(LaunchPlanner.plan(context, new Bundle()));
    }
    @Test public void malformedOrNonHttpInputCannotManufactureAnAppHandoff() {
        save("unlisted.example", CHOSEN);
        for (String url : List.of("javascript:alert(1)", "file:///sdcard/example", "https://unlisted.example/line%0Abreak", "https://unlisted.example/a b"))
            assertNull(LaunchPlanner.plan(context, web(url)));
    }
}
