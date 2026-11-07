package io.github.yudigaga.taplusbridge.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class IceRuleResolverTest {
    private static final String WX = "com.tencent.mm", BILI = "tv.danmaku.bili", PILI = "com.example.piliplus";
    private static LinkRuleEntry rule(String source, String host, String target, String origin) {
        return new LinkRuleEntry(source, host, target, origin, LinkRuleEntry.CONVERSION_NONE, "");
    }
    @Test public void manualOriginWinsBeforeSpecificOnlineSourceAndHost() {
        LinkRuleEntry manual = rule("*", "bilibili.com", BILI, LinkRuleEntry.SOURCE_MANUAL);
        List<LinkRuleEntry> matches = ScopedRuleResolver.matchingRules(List.of(
                rule(WX, "m.bilibili.com", PILI, LinkRuleEntry.SOURCE_ONLINE), manual), WX, "m.bilibili.com");
        assertSame(manual, ScopedRuleResolver.selectRule(matches, Set.of(BILI, PILI)));
    }
    @Test public void preciseSourceWinsBeforeMoreSpecificWildcardHostWithinAnOrigin() {
        LinkRuleEntry exact = rule(WX, "bilibili.com", BILI, LinkRuleEntry.SOURCE_MANUAL);
        List<LinkRuleEntry> matches = ScopedRuleResolver.matchingRules(List.of(
                rule("*", "m.bilibili.com", PILI, LinkRuleEntry.SOURCE_MANUAL), exact), WX, "m.bilibili.com");
        assertSame(exact, ScopedRuleResolver.selectRule(matches, Set.of(BILI, PILI)));
    }
    @Test public void longestMatchingHostWinsAndHostBoundaryIsRequired() {
        LinkRuleEntry precise = rule("*", "m.bilibili.com", BILI, LinkRuleEntry.SOURCE_ONLINE);
        List<LinkRuleEntry> rules = List.of(rule("*", "bilibili.com", PILI, LinkRuleEntry.SOURCE_ONLINE), precise);
        assertSame(precise, ScopedRuleResolver.selectRule(ScopedRuleResolver.matchingRules(rules, null, "M.BILIBILI.COM"), Set.of(BILI, PILI)));
        assertTrue(ScopedRuleResolver.matchingRules(rules, WX, "evilbilibili.com").isEmpty());
        assertTrue(ScopedRuleResolver.matchingRules(rules, WX, "bilibili.com.evil.test").isEmpty());
    }

    @Test public void share123PanRuleMatchesNumericShareSubdomainWithoutMatchingLookalikes() {
        String url="https://1817601681.share.123pan.cn/123pan/E4XTjv-BeZ53";
        LinkRuleEntry share=rule("*","share.123pan.cn","com.example.disk",LinkRuleEntry.SOURCE_MANUAL);
        List<LinkRuleEntry> rules=List.of(share);
        assertSame(share,ScopedRuleResolver.selectRule(ScopedRuleResolver.matchingRules(rules,"com.coolapk.market",
                java.net.URI.create(url).getHost()),Set.of("com.example.disk")));
        assertTrue(ScopedRuleResolver.matchingRules(rules,"com.coolapk.market","evilshare.123pan.cn").isEmpty());
        assertTrue(ScopedRuleResolver.matchingRules(rules,"com.coolapk.market","share.123pan.cn.evil.test").isEmpty());
    }
    @Test public void unknownSourceOnlySeesWildcardRules() {
        LinkRuleEntry wildcard = rule("*", "b23.tv", BILI, LinkRuleEntry.SOURCE_ONLINE);
        assertEquals(List.of(wildcard), ScopedRuleResolver.matchingRules(List.of(
                rule(WX, "b23.tv", PILI, LinkRuleEntry.SOURCE_MANUAL), wildcard), null, "b23.tv"));
    }
    @Test public void equalPriorityInstalledBiliCandidatesAreAmbiguousRatherThanListOrder() {
        List<LinkRuleEntry> matches = ScopedRuleResolver.matchingRules(List.of(
                rule("*", "b23.tv", PILI, LinkRuleEntry.SOURCE_ONLINE),
                rule("*", "b23.tv", BILI, LinkRuleEntry.SOURCE_ONLINE)), WX, "b23.tv");
        assertTrue(ScopedRuleResolver.isAmbiguous(matches, Set.of(BILI, PILI)));
        assertNull(ScopedRuleResolver.selectRule(matches, Set.of(BILI, PILI)));
        assertEquals(BILI, ScopedRuleResolver.selectRule(matches, Set.of(BILI)).targetPackage);
    }
    @Test public void unavailableManualIsProjectedBeforeMatchingSoOnlineCanApply() {
        List<LinkRuleEntry> durable = List.of(rule(WX, "b23.tv", "com.missing.app", LinkRuleEntry.SOURCE_MANUAL),
                rule("*", "b23.tv", BILI, LinkRuleEntry.SOURCE_ONLINE));
        List<LinkRuleEntry> installed = ScopedRuleResolver.filterInstalled(durable, Set.of(BILI));
        assertEquals(BILI, ScopedRuleResolver.selectRule(ScopedRuleResolver.matchingRules(installed, WX, "b23.tv"), Set.of(BILI)).targetPackage);
    }
    @Test public void disabledTargetRemovesAllOriginsWithoutChangingDurableRules() {
        List<LinkRuleEntry> durable = new ArrayList<>(List.of(
                rule(WX, "b23.tv", PILI, LinkRuleEntry.SOURCE_MANUAL),
                rule("*", "b23.tv", PILI, LinkRuleEntry.SOURCE_ONLINE),
                rule("*", "b23.tv", BILI, LinkRuleEntry.SOURCE_ONLINE)));
        List<LinkRuleEntry> enabled = ScopedRuleResolver.filterEnabled(durable, Set.of(PILI));
        assertEquals(1, enabled.size()); assertEquals(3, durable.size());
        assertEquals(BILI, ScopedRuleResolver.selectRule(ScopedRuleResolver.matchingRules(enabled, WX, "b23.tv"), Set.of(BILI, PILI)).targetPackage);
    }
    @Test public void installedProjectionDoesNotKnowEnabledStateSoFrozenTargetsRemainEligible() {
        LinkRuleEntry frozen = rule("*", "b23.tv", BILI, LinkRuleEntry.SOURCE_ONLINE);
        assertEquals(List.of(frozen), ScopedRuleResolver.filterInstalled(List.of(frozen), Set.of(BILI)));
        assertTrue(ScopedRuleResolver.filterInstalled(List.of(frozen), Set.of()).isEmpty());
    }
    @Test public void compactWirePreservesScopeOriginAndConversion() {
        LinkRuleEntry rule = new LinkRuleEntry(WX, "b23.tv", BILI, LinkRuleEntry.SOURCE_MANUAL,
                LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK, "UI note remains in JSON, not system wire");
        LinkRuleEntry found = ScopedRuleResolver.decode(ScopedRuleResolver.encode(List.of(rule))).get(0);
        assertEquals(WX, found.sourcePackage); assertEquals(rule.source, found.source);
        assertEquals(rule.urlConversion, found.urlConversion); assertEquals(BILI, found.targetPackage);
    }
    @Test public void invalidWireCannotWidenABlankSourceOrIntroduceExecutableConversions() {
        for (String invalid : new String[]{"|b23.tv|" + BILI + "|manual|none",
                "*|b23.tv|" + BILI + "|manual|javascript", "*|b23.tv|sh -c bad|manual|none"}) {
            try { ScopedRuleResolver.decode(new String[]{invalid}); fail(); } catch (IllegalArgumentException expected) { }
        }
    }
}
