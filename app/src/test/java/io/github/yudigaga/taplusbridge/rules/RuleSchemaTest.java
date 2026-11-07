package io.github.yudigaga.taplusbridge.rules;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RuleSchemaTest {
    @Test public void editorNormalizesIceStyleSeparatorsUrlsAndProtocolRelativeHosts() {
        assertEquals(List.of("example.com", "m.example.com", "sub.example.org"),
                RuleSchema.normalizeUserHosts("https://EXAMPLE.com/path?x=1，m.example.com; //sub.example.org/other\nexample.com"));
    }
    @Test public void manualInputReportsInvalidSourceTargetConversionAndNote() {
        RuleEditorValidation.Result invalid = RuleEditorValidation.validate("wrong", "*", "example.com", "script", "n".repeat(161));
        assertFalse(invalid.isValid()); assertTrue(invalid.entries.isEmpty()); assertEquals(4, invalid.errors.size());
    }
    @Test public void validatedEditorRowsPreserveEveryDataField() {
        RuleEditorValidation.Result result = RuleEditorValidation.validate("com.tencent.mm", "tv.danmaku.bili",
                "https://BILIBILI.com/video/BVtest\nb23.tv", LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK, "manual override");
        assertTrue(result.errors.toString(), result.isValid()); assertEquals(2, result.entries.size());
        LinkRuleEntry first = result.entries.get(0);
        assertEquals("com.tencent.mm", first.sourcePackage); assertEquals("bilibili.com", first.host);
        assertEquals("manual override", first.note); assertTrue(first.hasFixedUrlConversion());
        assertEquals(LinkRuleEntry.SOURCE_MANUAL, first.source);
    }
    @Test public void duplicateScopeHostTargetConversionRetainsManualOriginAndNote() {
        LinkRuleEntry online = new LinkRuleEntry("example.com", "com.target.app", "online", "none", "online note");
        LinkRuleEntry manual = new LinkRuleEntry("example.com", "com.target.app", "manual", "none", "manual note");
        List<LinkRuleEntry> result = RuleSchema.deduplicate(List.of(online, manual));
        assertEquals(1, result.size()); assertEquals("manual", result.get(0).source); assertEquals("manual note", result.get(0).note);
    }
    @Test public void capacityFailsInsteadOfSilentlyDroppingLaterRules() {
        java.util.ArrayList<LinkRuleEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 97; i++) entries.add(new LinkRuleEntry("h" + i + ".example", "com.target.app", "manual", "none", ""));
        try { RuleSchema.deduplicate(entries); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void targetReplacementDoesNotModifyOtherApplications() {
        LinkRuleEntry keep = new LinkRuleEntry("old.example", "com.keep.app", "manual", "none", "");
        LinkRuleEntry replace = new LinkRuleEntry("gone.example", "com.edit.app", "manual", "none", "");
        LinkRuleEntry fresh = new LinkRuleEntry("new.example", "com.edit.app", "manual", "none", "");
        assertEquals(List.of(keep, fresh), RuleEditorValidation.replaceTarget(List.of(keep, replace), "com.edit.app", List.of(fresh)));
        try { RuleEditorValidation.replaceTarget(List.of(keep), "com.edit.app", List.of(keep)); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void hostListOverCapacityOrInvalidTokenFailsWithoutReturningAPartialList() {
        StringBuilder hosts = new StringBuilder();
        for (int i = 0; i < 21; i++) hosts.append("h").append(i).append(".example\n");
        try { RuleSchema.normalizeUserHosts(hosts.toString()); fail(); } catch (IllegalArgumentException expected) { }
        try { RuleSchema.normalizeUserHosts("valid.example\nhttp://"); fail(); } catch (IllegalArgumentException expected) { }
        RuleEditorValidation.Result invalid = RuleEditorValidation.validate("*", "com.target.app", "valid.example\nhttp://", "none", "");
        assertFalse(invalid.isValid()); assertTrue(invalid.entries.isEmpty());
    }
    @Test public void onlineNoteCapacityDoesNotWidenManualTransferLimit() {
        assertNotNull(RuleSchema.sanitize(new LinkRuleEntry("example.com", "com.target.app", "online", "none", "n".repeat(512))));
        assertNull(RuleSchema.sanitize(new LinkRuleEntry("example.com", "com.target.app", "online", "none", "n".repeat(513))));
        assertNull(RuleSchema.sanitize(new LinkRuleEntry("example.com", "com.target.app", "manual", "none", "n".repeat(161))));
    }
    @Test public void mergingTheSameMaximumManualFileIsIdempotentInsteadOfFailingOnRawDuplicates() {
        java.util.ArrayList<LinkRuleEntry> entries = new java.util.ArrayList<>();
        for (int h = 0; h < 96; h++) for (int app = 0; app < 8; app++)
            entries.add(new LinkRuleEntry("h" + h + ".example", "com.target.app" + app, "manual", "none", ""));
        entries.addAll(new java.util.ArrayList<>(entries));
        assertEquals(768, RuleSchema.deduplicate(entries).size());
    }
}
