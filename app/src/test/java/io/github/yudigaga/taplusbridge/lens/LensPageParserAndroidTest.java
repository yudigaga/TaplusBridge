package io.github.yudigaga.taplusbridge.lens;

import static org.junit.Assert.*;
import android.os.Bundle;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class LensPageParserAndroidTest {
    private JSONObject envelope(String status) throws Exception {
        return new JSONObject().put("request_id", "request-A").put("page_generation", 7)
                .put("status", status).put("items", new JSONArray());
    }
    @Test public void doubleEncodedResultRetainsTaskIdentityAndEscapedTitle() throws Exception {
        JSONObject data = envelope("results_ready");
        data.getJSONArray("items").put(new JSONObject().put("page_url", "https://example.org/page")
                .put("title", "含引号\"与换行\n标题").put("thumbnail_url", "https://example.org/thumb.jpg"));
        Bundle parsed = LensPageParser.parse(JSONObject.quote(data.toString()));
        assertEquals("results_ready", parsed.getString("status"));
        assertEquals("request-A", parsed.getString("request_id"));
        assertEquals(7, parsed.getInt("page_generation"));
        ArrayList<Bundle> rows = parsed.getParcelableArrayList("items");
        assertEquals("含引号\"与换行\n标题", rows.get(0).getString("title"));
    }
    @Test public void unsafeLinksAreRejectedAndDuplicatesDoNotBecomeSeparateResults() throws Exception {
        JSONObject data = envelope("results_ready");
        for (String link : new String[]{"javascript:alert(1)", "https://user:pass@example.org/", "content://private/image",
                "https://example.org/a", "https://example.org/a"}) {
            data.getJSONArray("items").put(new JSONObject().put("page_url", link));
        }
        Bundle parsed = LensPageParser.parse(data.toString());
        ArrayList<Bundle> rows = parsed.getParcelableArrayList("items");
        assertEquals(1, rows.size());
        assertEquals("https://example.org/a", rows.get(0).getString("page_url"));
        assertEquals("", rows.get(0).getString("source_name"));
    }
    @Test public void noItemsDoesNotTurnReadyIntoEmpty() throws Exception {
        assertEquals("parse_error", LensPageParser.parse(envelope("results_ready").toString()).getString("status"));
        assertEquals("empty", LensPageParser.parse(envelope("empty").toString()).getString("status"));
        assertEquals("needs_interaction", LensPageParser.parse(envelope("needs_interaction").toString()).getString("status"));
    }
    @Test public void malformedOrUncorrelatedOutputsAreNotAccepted() throws Exception {
        for (String output : new String[]{null, "null", "{}", "\"undefined\"", "{invalid", "[]"}) {
            assertEquals("parse_error", LensPageParser.parse(output).getString("status"));
        }
        JSONObject data = envelope("results_ready"); data.remove("page_generation");
        assertEquals("parse_error", LensPageParser.parse(data.toString()).getString("status"));
    }
    @Test public void resultAndPayloadLimitsAreEnforced() throws Exception {
        JSONObject data = envelope("results_ready");
        for (int i = 0; i < 100; i++) data.getJSONArray("items").put(new JSONObject().put("page_url", "https://example.org/" + i));
        ArrayList<Bundle> rows = LensPageParser.parse(data.toString()).getParcelableArrayList("items");
        assertEquals(40, rows.size());
        assertEquals("parse_error", LensPageParser.parse("x".repeat(524289)).getString("status"));
    }
    @Test public void observedGoogleRedirectRemainsARedirectAndInlineThumbnailIsBounded() throws Exception {
        JSONObject data = envelope("results_ready");
        data.getJSONArray("items").put(new JSONObject().put("page_url", "https://www.google.com/goto?url=opaque")
                .put("match_type", "visual").put("thumbnail_url", "data:image/jpeg;base64,AABB=="));
        ArrayList<Bundle> rows = LensPageParser.parse(data.toString()).getParcelableArrayList("items");
        assertEquals("https://www.google.com/goto?url=opaque", rows.get(0).getString("page_url"));
        assertEquals("google_redirect", rows.get(0).getString("link_type"));
        assertEquals("visual", rows.get(0).getString("match_type"));
        assertEquals("data:image/jpeg;base64,AABB==", rows.get(0).getString("thumbnail_url"));
        assertNull(LensPageParser.thumbnail("data:image/svg+xml;base64,AABB=="));
        assertNull(LensPageParser.thumbnail("data:image/png;base64," + "A".repeat(131073)));
        assertNull(LensPageParser.thumbnail("data:image/jpeg;base64,not valid"));
    }
    @Test public void callbackFailuresAndUnsupportedLayoutsHaveDifferentSafeReasons() throws Exception {
        assertEquals("payload_null", LensPageParser.parse("null").getString("parse_reason"));
        assertEquals("payload_invalid_json", LensPageParser.parse("{invalid").getString("parse_reason"));
        assertEquals("payload_not_object", LensPageParser.parse("[]").getString("parse_reason"));
        assertEquals("task_identity_missing", LensPageParser.parse("{}").getString("parse_reason"));
        assertEquals("payload_oversize", LensPageParser.parse("x".repeat(524289)).getString("parse_reason"));
        JSONObject data = envelope("parse_error").put("message", "https://private.example/signed?token=private")
                .put("diagnostics", new JSONObject().put("document_state", "complete").put("result_root_count", 1)
                        .put("visual_button_count", 0).put("source_link_count", 999999).put("heading_count", -1)
                        .put("raw_body", "private webpage text"));
        Bundle parsed = LensPageParser.parse(data.toString());
        assertEquals("layout_unrecognized", parsed.getString("parse_reason"));
        String log = LensPageParser.diagnostic(parsed);
        assertTrue(log.contains("document=complete"));
        assertTrue(log.contains("result_root_count=1"));
        assertTrue(log.contains("source_link_count=100000"));
        assertTrue(log.contains("heading_count=0"));
        assertFalse(log.contains("private"));
        assertFalse(log.contains("http"));
        assertFalse(log.contains("request-A"));
        assertNull(parsed.getBundle("diagnostics").getString("raw_body"));
    }
    @Test public void resultUrlRecognitionMatchesOnlyTheSupportedHttpsGoogleSearchRoutes() {
        for (String url : new String[]{"https://www.google.com/search?udm=26", "https://google.com/search?vsrid=opaque",
                "https://www.google.com/search?lns_mode=un", "https://lens.google.com/search?q=label"})
            assertTrue(url, LensPageParser.isResultUrl(url));
        for (String url : new String[]{null, "about:blank", "https://www.google.com/sorry/index", "https://accounts.google.com/",
                "https://www.google.com/search?q=ordinary", "https://google.com.evil.example/search?udm=26",
                "http://www.google.com/search?udm=26", "https://user@www.google.com/search?udm=26",
                "https://www.google.com:444/search?udm=26"}) assertFalse(url, LensPageParser.isResultUrl(url));
    }
}
