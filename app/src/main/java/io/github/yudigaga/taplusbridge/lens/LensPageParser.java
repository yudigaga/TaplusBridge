package io.github.yudigaga.taplusbridge.lens;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Limits and validates the untrusted result of evaluateJavascript; no remote code bridge. */
public final class LensPageParser {
    public static final String VERSION = "google-visual-cards-20261102";
    private LensPageParser() { }

    public static String script(Context context, String requestId, int pageGeneration) throws IOException {
        try (InputStream input = context.getAssets().open("lens/extract.js")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            String function = bytes.toString(StandardCharsets.UTF_8.name());
            // Return one explicit JSON string; parse also accepts WebView's direct JSON-object encoding.
            return "JSON.stringify(" + function + "(" + JSONObject.quote(requestId) + "," + pageGeneration + "))";
        }
    }

    public static Bundle parse(String encoded) {
        Bundle output = new Bundle();
        output.putString("parser_version", VERSION);
        output.putString("status", "parse_error");
        output.putString("message", "无法读取 Google Lens 页面结果");
        output.putString("parse_reason", "payload_null");
        if (encoded == null || "null".equals(encoded)) return output;
        if (encoded.length() > 524288) { output.putString("parse_reason", "payload_oversize"); return output; }
        try {
            Object value = new JSONTokener(encoded).nextValue();
            // evaluateJavascript may serialize JSON.stringify output as a JSON string.
            if (value instanceof String string) value = new JSONTokener(string).nextValue();
            if (!(value instanceof JSONObject object)) { output.putString("parse_reason", "payload_not_object"); return output; }
            String request = object.optString("request_id", "");
            if (request.isEmpty() || request.length() > 128 || !object.has("page_generation")) {
                output.putString("parse_reason", "task_identity_missing"); return output;
            }
            output.putString("request_id", request);
            output.putInt("page_generation", object.getInt("page_generation"));
            JSONObject details = object.optJSONObject("diagnostics");
            if (details != null) {
                Bundle safe = new Bundle();
                String document = details.optString("document_state");
                safe.putString("document_state", Set.of("loading", "interactive", "complete").contains(document) ? document : "unknown");
                for (String key : new String[]{"result_root_count", "visual_button_count", "card_count", "source_link_count", "heading_count"})
                    safe.putInt(key, Math.max(0, Math.min(100000, details.optInt(key, 0))));
                output.putBundle("diagnostics", safe);
            }
            String status = object.optString("status", "parse_error");
            if (!Set.of("loading", "results_ready", "empty", "needs_interaction", "parse_error").contains(status)) {
                output.putString("parse_reason", "status_unknown"); return output;
            }
            output.putString("status", status);
            output.putString("parse_reason", "parse_error".equals(status) ? "layout_unrecognized" : "page_observed");
            output.putString("message", clipped(object.optString("message", ""), 2048));
            ArrayList<Bundle> items = new ArrayList<>();
            HashSet<String> urls = new HashSet<>();
            JSONArray rows = object.optJSONArray("items");
            if (rows != null && "results_ready".equals(status)) {
                for (int i = 0; i < Math.min(rows.length(), 80) && items.size() < 40; i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    String page = http(row.optString("page_url", ""));
                    if (page == null || !urls.add(page)) continue;
                    Bundle item = new Bundle();
                    item.putString("page_url", page);
                    item.putString("title", clipped(row.optString("title", ""), 1024));
                    item.putString("source_name", clipped(row.optString("source_name", ""), 1024));
                    item.putString("thumbnail_url", thumbnail(row.optString("thumbnail_url", "")));
                    item.putString("image_url", http(row.optString("image_url", "")));
                    item.putString("match_type", "visual".equals(row.optString("match_type")) ? "visual" : "unknown");
                    item.putString("link_type", googleRedirect(page) ? "google_redirect" : "direct");
                    items.add(item);
                }
            }
            output.putParcelableArrayList("items", items);
            if ("results_ready".equals(status) && items.isEmpty()) {
                output.putString("status", "parse_error");
                output.putString("parse_reason", "no_valid_links");
                output.putString("message", "页面结果没有可用的网页链接，无法解析");
            }
        } catch (JSONException | IllegalArgumentException malformed) {
            output.putString("status", "parse_error");
            output.putString("parse_reason", "payload_invalid_json");
        }
        return output;
    }

    private static String clipped(String value, int length) {
        return value.substring(0, Math.min(value.length(), length));
    }

    /** Safe for logs: no request IDs, page text, URLs, source titles or image contents. */
    public static String diagnostic(Bundle parsed) {
        if (parsed == null) return "reason=payload_null";
        String reason = parsed.getString("parse_reason", "unknown");
        if (!Set.of("payload_null", "payload_oversize", "payload_not_object", "task_identity_missing", "status_unknown",
                "layout_unrecognized", "page_observed", "no_valid_links", "payload_invalid_json").contains(reason)) reason = "unknown";
        StringBuilder value = new StringBuilder("reason=").append(reason);
        Bundle details = parsed.getBundle("diagnostics");
        if (details != null) {
            String document = details.getString("document_state", "unknown");
            value.append(" document=").append(Set.of("loading", "interactive", "complete").contains(document) ? document : "unknown");
            for (String key : new String[]{"result_root_count", "visual_button_count", "card_count", "source_link_count", "heading_count"})
                value.append(' ').append(key).append('=').append(Math.max(0, Math.min(100000, details.getInt(key, 0))));
        }
        return value.toString();
    }

    public static boolean isResultUrl(String raw) {
        if (raw == null || raw.length() > 32768) return false;
        try {
            Uri uri = Uri.parse(raw);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !("google.com".equalsIgnoreCase(host) || "www.google.com".equalsIgnoreCase(host)
                    || "lens.google.com".equalsIgnoreCase(host)) || !"/search".equals(uri.getPath())) return false;
            return "lens.google.com".equalsIgnoreCase(host) || "26".equals(uri.getQueryParameter("udm"))
                    || uri.getQueryParameterNames().contains("lns_mode") || uri.getQueryParameterNames().contains("vsrid");
        } catch (RuntimeException malformed) { return false; }
    }

    static String http(String value) {
        if (value == null || value.isEmpty() || value.length() > 8192) return null;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) { return null; }
    }

    public static String thumbnail(String value) {
        String web = http(value);
        if (web != null) return web;
        return value != null && value.length() <= 131072
                && value.matches("^data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/]+={0,2}$") ? value : null;
    }

    private static boolean googleRedirect(String value) {
        URI uri = URI.create(value);
        return ("www.google.com".equalsIgnoreCase(uri.getHost()) || "google.com".equalsIgnoreCase(uri.getHost()))
                && "/goto".equals(uri.getPath()) && uri.getRawQuery() != null
                && ("&" + uri.getRawQuery()).contains("&url=");
    }
}
