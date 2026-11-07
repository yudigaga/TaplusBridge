package io.github.yudigaga.taplusbridge.rules;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real strict publisher parser and fixed-origin download pipeline, with no external HTTP calls. */
public final class OnlineRuleContractTest {
    static final String RULE = "{\"sourcePackage\":\"com.tencent.mm\",\"host\":\"example.com\",\"package\":\"com.example.reader\",\"conversion\":\"fixed_deep_link\",\"note\":\"已审查\"}";
    static String payload(long revision, String rules) { return "{\"format\":\"icebridge-online-rules\",\"version\":1,\"revision\":" + revision + ",\"rules\":[" + rules + "]}"; }
    static String manifest(String raw) throws IOException {
        java.util.regex.Matcher revision = java.util.regex.Pattern.compile("\\\"revision\\\":([0-9]+)").matcher(raw);
        if (!revision.find()) throw new IOException("fixture-revision");
        return "{\"format\":\"icebridge-rule-manifest\",\"version\":1,\"revision\":" + revision.group(1)
                + ",\"file\":\"revisions/rules.json\",\"sha256\":\"" + OnlineRuleBundle.sha256(raw) + "\"}";
    }
    private static OnlineRuleBundle parse(String raw) throws IOException { return OnlineRuleBundle.parse(raw, OnlineRuleBundle.sha256(raw)); }
    private static void reject(String raw) { try { parse(raw); fail(); } catch (IOException expected) { } }

    @Test public void exactUtf8BytesPreserveSourceConversionOriginAndOrder() throws Exception {
        String raw = payload(2, RULE + "," + RULE.replace("com.tencent.mm", "*")).replace("{", "{\r\n") + "\n";
        byte[] utf8 = raw.getBytes(StandardCharsets.UTF_8);
        String decoded = OnlineRuleClient.decodeUtf8(utf8, utf8.length);
        OnlineRuleBundle result = parse(decoded);
        assertEquals(raw, decoded); assertEquals(2, result.rules.size());
        assertEquals("com.tencent.mm", result.rules.get(0).sourcePackage);
        assertEquals(LinkRuleEntry.SOURCE_ONLINE, result.rules.get(0).source);
        assertEquals(LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK, result.rules.get(0).urlConversion);
        assertEquals("已审查", result.rules.get(0).note);
        assertFalse(OnlineRuleBundle.checksumMatches(decoded.replace("\r\n", "\n"), result.checksum));
        try { result.rules.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void strictSyntaxAndUnknownActionsNeverEnterTheRuleModel() {
        reject(payload(2, RULE).replace("\"format\"", "/* comment */\"format\""));
        reject(payload(2, RULE).replace("\"version\"", "'version'"));
        reject(payload(2, RULE) + " {}");
        reject(payload(2, RULE.replace("\"note\":\"已审查\"", "\"note\":\"\",\"note\":\"again\"")));
        reject(payload(2, RULE.replace("\"note\":\"已审查\"", "\"note\":\"\",\"actions\":[\"execute()\"]")));
        reject(payload(2, RULE.replace("fixed_deep_link", "javascript")));
        reject(payload(2, RULE + "," + RULE));
    }
    @Test public void onlyCanonicalProtocolRuleKeysAreAcceptedWithTheSameChecksumContract() throws Exception {
        String raw = payload(2, RULE.replace("example.com", "magnet:") + "," + RULE.replace("example.com", "ed2k:"));
        OnlineRuleBundle bundle = parse(raw);
        assertEquals(List.of("magnet:", "ed2k:"), bundle.rules.stream().map(r -> r.host).toList());
        assertEquals(raw, bundle.raw);
        assertFalse(OnlineRuleBundle.checksumMatches(raw + " ", bundle.checksum));
        reject(payload(2, RULE.replace("example.com", "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")));
        reject(payload(2, RULE.replace("example.com", "javascript:")));
    }
    @Test public void incompleteAndInvalidFieldsAreRejectedAsAWhole() {
        reject(payload(2, RULE.replace(",\"note\":\"已审查\"", "")));
        reject(payload(2, RULE.replace("example.com", "https://example.com")));
        reject(payload(2, RULE.replace("example.com", "*.example.com")));
        reject(payload(2, RULE.replace("com.tencent.mm", "bad package")));
        reject(payload(2, RULE.replace("com.example.reader", "reader")));
        reject(payload(0, RULE)); reject(payload(2, RULE).replace("\"revision\":2", "\"revision\":2.5"));
    }
    @Test public void oversizedRulesHostsAndNotesFailInsteadOfTruncating() {
        List<String> hosts = new ArrayList<>(), rows = new ArrayList<>();
        for (int i = 0; i < 97; i++) hosts.add(RULE.replace("example.com", "h" + i + ".example.com"));
        for (int i = 0; i < 769; i++) rows.add(RULE.replace("com.tencent.mm", "com.source.p" + i));
        reject(payload(2, String.join(",", hosts))); reject(payload(2, String.join(",", rows)));
        reject(payload(2, RULE.replace("已审查", "x".repeat(513))));
    }
    @Test public void malformedUtf8AndOversizedResponsesFailBeforeJsonParsing() {
        try { OnlineRuleClient.decodeUtf8(new byte[] {(byte) 0xc3, 0x28}, 2); fail(); } catch (IOException expected) { }
        try { OnlineRuleClient.decodeUtf8(new byte[5], 4); fail(); } catch (IOException expected) { }
    }
    @Test public void manifestCannotEscapeItsFixedHttpsDirectory() throws Exception {
        URI primary = URI.create(OnlineRuleClient.PRIMARY_MANIFEST);
        assertEquals("https://raw.githubusercontent.com/yudigaga/IceBridge/rules/revisions/rules.json",
                OnlineRuleClient.bundleUrl(primary, "revisions/rules.json").toString());
        for (String path : new String[] {"../other.json", "/root.json", "https://other.example/rules.json",
                "revisions/%2e%2e/rules.json", "x.json?redirect=1", "x.json#fragment", "x\\rules.json"}) {
            try { OnlineRuleClient.bundleUrl(primary, path); fail(path); } catch (IOException expected) { }
        }
        assertFalse(OnlineRuleClient.trusted(URI.create("http://raw.githubusercontent.com/yudigaga/IceBridge/rules/manifest.json")));
        assertFalse(OnlineRuleClient.trusted(URI.create("https://raw.githubusercontent.com/other/project/rules.json")));
    }
    @Test public void revisionFloorRejectsRollbackAndSameRevisionRewrite() throws Exception {
        OnlineRuleBundle old = parse(payload(2, RULE)), newer = parse(payload(3, ""));
        try { OnlineRuleBundle.checkRevision(new OnlineRuleBundle.Manifest(2, "rules.json", old.checksum), 3, newer.checksum); fail(); }
        catch (IOException expected) { assertEquals("revision-rollback", expected.getMessage()); }
        try { OnlineRuleBundle.checkRevision(new OnlineRuleBundle.Manifest(3, "rules.json", old.checksum), 3, newer.checksum); fail(); }
        catch (IOException expected) { assertEquals("revision-content-changed", expected.getMessage()); }
    }
    @Test public void validCurrentManifestReusesVerifiedCacheWithoutDownloadingTheBundle() throws Exception {
        String raw = payload(2, RULE); OnlineRuleBundle bundle = parse(raw); List<URI> calls = new ArrayList<>();
        OnlineRuleClient client = new OnlineRuleClient((url, limit, deadline) -> { calls.add(url); return manifest(raw); });
        OnlineRuleBundle.Cache cache = OnlineRuleBundle.selectCache(raw, bundle.checksum, "", "", 2, bundle.checksum, true);
        assertEquals(raw, client.download(2, bundle.checksum, cache).bundle.raw); assertEquals(1, calls.size());
    }
    @Test public void fallbackStillUsesTheSameSnapshotChecksumAndRevisionContract() throws Exception {
        String raw = payload(3, RULE); List<URI> calls = new ArrayList<>();
        OnlineRuleClient client = new OnlineRuleClient((url, limit, deadline) -> {
            calls.add(url); if ("raw.githubusercontent.com".equals(url.getHost())) throw new IOException("http-status");
            return url.getPath().endsWith("manifest.json") ? manifest(raw) : raw;
        });
        OnlineRuleBundle.Cache empty = OnlineRuleBundle.selectCache("", "", "", "", 0, "", true);
        OnlineRuleClient.Download result = client.download(0, "", empty);
        assertEquals(3, result.bundle.revision); assertEquals("jsdelivr", result.source); assertEquals(3, calls.size());
        assertTrue(calls.stream().allMatch(OnlineRuleClient::trusted));
    }
}
