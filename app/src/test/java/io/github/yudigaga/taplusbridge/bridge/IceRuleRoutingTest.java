package io.github.yudigaga.taplusbridge.bridge;

import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class IceRuleRoutingTest {
    @Test public void packageQueriesAreLimitedToRulesMatchingTheCurrentHost() {
        List<LinkRuleEntry> rules = List.of(
                rule("matching.example", "com.example.reader"),
                rule("unrelated.example", "com.example.other"));
        List<String> queried = new ArrayList<>();
        IceRuleRouting.Decision result = IceRuleRouting.decideForHost(rules, "com.tencent.mm",
                "matching.example", target -> { queried.add(target); return true; });
        assertEquals("resolved", result.reason());
        assertEquals("com.example.reader", result.rule().targetPackage);
        assertEquals(List.of("com.example.reader"), queried);
    }

    @Test public void noDomainMatchDoesNotQueryPackageManager() {
        List<String> queried = new ArrayList<>();
        IceRuleRouting.Decision result = IceRuleRouting.decideForHost(
                List.of(rule("unrelated.example", "com.example.other")), "*", "matching.example",
                target -> { queried.add(target); return true; });
        assertEquals("no_match", result.reason());
        assertTrue(queried.isEmpty());
    }

    @Test public void matchedTargetsPreserveAmbiguityRules() {
        List<LinkRuleEntry> rules = List.of(
                rule("matching.example", "com.example.reader"),
                rule("matching.example", "com.example.other"));
        IceRuleRouting.Decision result = IceRuleRouting.decideForHost(rules, "*", "matching.example",
                target -> true);
        assertEquals("ambiguous", result.reason());
        assertNull(result.rule());
    }

    private static LinkRuleEntry rule(String host, String target) {
        return new LinkRuleEntry("*", host, target, LinkRuleEntry.SOURCE_MANUAL,
                LinkRuleEntry.CONVERSION_NONE, "");
    }
}
