package io.github.yudigaga.taplusbridge.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class RuleDataAndroidTest {
    @Test public void fullWebsiteWithLongShareParametersStoresOnlyItsHost() {
        assertEquals("site.example", RuleSchema.normalizeHostOrUrl("https://site.example/path?share=" + "x".repeat(1024)));
        assertNull(RuleSchema.normalizeHostOrUrl("https://site.example/" + "x".repeat(8192)));
    }
    private LinkRuleEntry manual() {
        return new LinkRuleEntry("com.tencent.mm", "b23.tv", "tv.danmaku.bili", "manual", "fixed_deep_link", "说明与转换保留");
    }
    @Test public void localSnapshotRetainsAllSixFields() {
        LinkRuleEntry entry = manual();
        assertEquals(List.of(entry), RuleJsonCodec.decode(RuleJsonCodec.encode(List.of(entry))));
    }
    @Test public void protocolRulesRoundTripThroughLocalAndManualTransferFormats() throws Exception {
        List<LinkRuleEntry> entries = List.of(
                new LinkRuleEntry("magnet:", "com.example.downloader", "manual", "none", "magnet rule"),
                new LinkRuleEntry("com.example.source", "ed2k:", "com.example.other", "manual", "none", "ed2k rule"));
        assertEquals(entries, RuleJsonCodec.decode(RuleJsonCodec.encode(entries)));
        assertEquals(entries, RuleConfigTransfer.importJson(RuleConfigTransfer.exportJson(entries)));
    }
    @Test public void iceExportImportRoundTripKeepsOnlyDurableManualRules() throws Exception {
        LinkRuleEntry entry = manual();
        String exported = RuleConfigTransfer.exportJson(List.of(entry,
                new LinkRuleEntry("example.org", "com.other.app", "online", "none", "owned cache")));
        JSONObject root = new JSONObject(exported);
        assertEquals("icebridge-rules", root.getString("format")); assertEquals(1, root.getInt("version"));
        assertEquals(1, root.getJSONArray("rules").length());
        assertEquals(List.of(entry), RuleConfigTransfer.importJson(exported));
    }
    @Test public void transferRejectsActionsWrongTypesAndUnknownFormat() {
        for (String json : new String[]{
                "{\"format\":\"other\",\"version\":1,\"rules\":[]}",
                "{\"format\":\"icebridge-rules\",\"version\":1.2,\"rules\":[]}",
                "{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[],\"script\":\"open()\"}",
                "{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[{\"host\":\"b23.tv\",\"package\":\"tv.danmaku.bili\",\"action\":\"sh\"}]}",
                "{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[{\"host\":\"b23.tv\",\"package\":true}]}"}) {
            try { RuleConfigTransfer.importJson(json); fail(json); } catch (RuleConfigTransfer.TransferException expected) { }
        }
    }
    @Test public void bundledOnlineBytesMatchOriginalManifestAndKeepBothBiliCandidates() throws Exception {
        byte[] manifest = DefaultRuleCatalog.seedManifest(RuntimeEnvironment.getApplication());
        byte[] raw = DefaultRuleCatalog.seedSnapshot(RuntimeEnvironment.getApplication());
        JSONObject metadata = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(raw)) digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        assertEquals(DefaultRuleCatalog.SEED_SHA256, digest.toString());
        assertEquals(metadata.getString("sha256"), digest.toString());
        JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        assertEquals(1, root.getLong("revision"));
        JSONArray rules = root.getJSONArray("rules"); assertEquals(32, rules.length());
        java.util.Set<String> bili = new java.util.HashSet<>();
        for (int i = 0; i < rules.length(); i++) {
            JSONObject item = rules.getJSONObject(i);
            if ("bilibili.com".equals(item.getString("host"))) bili.add(item.getString("package"));
        }
        assertEquals(java.util.Set.of("tv.danmaku.bili", "com.example.piliplus"), bili);
        assertTrue(DefaultRuleCatalog.builtInRules().isEmpty());
    }
    @Test public void nestedScriptDataCannotBeImportedAsConversionOrPackage() {
        String item = "[{\"sourcePackage\":\"*\",\"host\":\"example.com\",\"package\":\"com.target.app\",\"source\":\"manual\",\"conversion\":{\"script\":\"bad()\"},\"note\":\"\"}]";
        try { RuleJsonCodec.decode(item); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void transferRejectsDuplicateKeysCommentsAndTrailingExecutableText() {
        for (String json : new String[]{
                "{\"format\":\"icebridge-rules\",\"version\":1,\"version\":1,\"rules\":[]}",
                "{/*comment*/\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[]}",
                "{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[]} ; bad()",
                "{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[],}"}) {
            try { RuleConfigTransfer.importJson(json); fail(json); } catch (RuleConfigTransfer.TransferException expected) { }
        }
    }
}
