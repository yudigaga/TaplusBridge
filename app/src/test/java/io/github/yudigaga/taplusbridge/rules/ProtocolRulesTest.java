package io.github.yudigaga.taplusbridge.rules;

import io.github.yudigaga.taplusbridge.routing.RouteRule;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProtocolRulesTest {
    private static final String TARGET = "com.example.downloader";
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=One,Two;Three";
    private static final String ED2K = "ed2k://|file|One,Two;Three.iso|123456|0123456789abcdef0123456789abcdef|/";
    private static LinkRuleEntry rule(String source, String key, String target) {
        return new LinkRuleEntry(source, key, target, LinkRuleEntry.SOURCE_MANUAL, LinkRuleEntry.CONVERSION_NONE, "");
    }

    @Test public void protocolKeysAndFullLinksNormalizeWithoutRetainingDownloadContent() {
        assertEquals("magnet:", RuleSchema.normalizeHostOrUrl("MAGNET:"));
        assertEquals("ed2k:", RuleSchema.normalizeHostOrUrl("ed2k://"));
        assertEquals("magnet:", RuleSchema.normalizeHostOrUrl(MAGNET));
        assertEquals("ed2k:", RuleSchema.normalizeHostOrUrl(ED2K));
        assertEquals(List.of("magnet:"), RuleSchema.normalizeUserHosts(MAGNET));
        assertEquals(List.of("ed2k:"), RuleSchema.normalizeUserHosts(ED2K));
        assertNull(RuleSchema.normalizeHostOrUrl("magnet:?dn=title-only"));
        assertNull(RuleSchema.normalizeHostOrUrl("ed2k://|server|127.0.0.1|4661|/"));
        assertNull(RuleSchema.normalizeHostOrUrl("javascript:"));
    }

    @Test public void existingManualEditorCanSaveProtocolRulesAlongsideDomains() {
        RuleEditorValidation.Result result = RuleEditorValidation.validate("*", TARGET,
                "magnet:\ned2k:\nexample.com", "none", "user rule");
        assertTrue(result.errors.toString(), result.isValid());
        assertEquals(List.of("magnet:", "ed2k:", "example.com"), result.entries.stream().map(r -> r.host).toList());
        for (String link : List.of(MAGNET, ED2K)) {
            RuleEditorValidation.Result full = RuleEditorValidation.validate("*", TARGET, link, "none", "");
            assertTrue(full.errors.toString(), full.isValid());
            assertEquals(1, full.entries.size());
        }
    }

    @Test public void previewUsesProtocolKeyRatherThanTrackerHostOrEd2kFilename() {
        assertEquals("magnet:", RuleEditorValidation.previewHost("*", MAGNET + "&tr=https://tracker.example/announce"));
        assertEquals("ed2k:", RuleEditorValidation.previewHost("com.example.source", ED2K));
    }

    @Test public void protocolKeysRemainExactWhileDomainRulesKeepSubdomainMatching() {
        LinkRuleEntry magnet = rule("*", "magnet:", TARGET), ed2k = rule("*", "ed2k:", TARGET);
        List<LinkRuleEntry> rules = List.of(magnet, ed2k, rule("*", "example.com", TARGET));
        assertEquals(List.of(magnet), ScopedRuleResolver.matchingRules(rules, null, "magnet:"));
        assertEquals(List.of(ed2k), ScopedRuleResolver.matchingRules(rules, null, "ED2K:"));
        assertTrue(ScopedRuleResolver.matchingRules(rules, null, "evil.magnet:").isEmpty());
        assertTrue(ScopedRuleResolver.matchingRules(rules, null, "magnet:evil").isEmpty());
        assertEquals(1, ScopedRuleResolver.matchingRules(rules, null, "sub.example.com").size());
    }

    @Test public void compactWireKeepsProtocolRulesAndRejectsOtherSchemes() {
        List<LinkRuleEntry> rules = List.of(rule("*", "magnet:", TARGET), rule("com.example.source", "ed2k:", TARGET));
        assertEquals(rules, ScopedRuleResolver.decode(ScopedRuleResolver.encode(rules)));
        assertThrows(IllegalArgumentException.class,
                () -> ScopedRuleResolver.decode(new String[]{"*|javascript:|" + TARGET + "|manual|none"}));
    }

    @Test public void legacyProjectionKeepsNewProtocolRulesReadable() {
        assertEquals("magnet:", new RouteRule("magnet:", TARGET, "*", true, false).host());
        assertEquals("ed2k:", new RouteRule("ed2k:", TARGET, "com.example.source", true, false).host());
    }

    @Test public void protocolRulesPreserveSourcePriorityAndAmbiguityRatherThanChoosing115() {
        LinkRuleEntry exact = rule("com.example.source", "magnet:", TARGET);
        String alternate = "com.example.other";
        List<LinkRuleEntry> rules = List.of(rule("*", "magnet:", alternate), exact);
        assertSame(exact, ScopedRuleResolver.selectRule(ScopedRuleResolver.matchingRules(rules,
                "com.example.source", "magnet:"), Set.of(TARGET, alternate)));
        List<LinkRuleEntry> conflicts = ScopedRuleResolver.matchingRules(List.of(exact,
                rule("com.example.source", "magnet:", alternate)), "com.example.source", "magnet:");
        assertTrue(ScopedRuleResolver.isAmbiguous(conflicts, Set.of(TARGET, alternate)));
        assertNull(ScopedRuleResolver.selectRule(conflicts, Set.of(TARGET, alternate)));
    }
}
