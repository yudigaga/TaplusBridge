package io.github.yudigaga.taplusbridge.lens;

import android.net.Uri;
import java.net.URI;
import java.net.URISyntaxException;

/** Refines an existing Lens session without rewriting its encoded image/session parameters. */
public final class LensSearchUrl {
    private LensSearchUrl() { }

    public static String build(String realBase, String query, String mode) {
        if (realBase == null || mode == null || control(realBase) || control(query)
                || !LensPageParser.isResultUrl(realBase)) return null;
        String text = query == null ? "" : query.trim();
        if (text.length() > 512) return null;
        String udm = switch (mode) {
            case "all" -> "26";
            case "ai" -> "50";
            case "exact" -> "48";
            case "visual" -> "44";
            default -> null;
        };
        if (udm == null) return null;
        try {
            // Uri is lenient about malformed percent escapes; keep invalid URLs out of WebView.
            new URI(realBase);
            Uri base = Uri.parse(realBase);
            StringBuilder kept = new StringBuilder();
            boolean first = true;
            String encoded = base.getEncodedQuery();
            if (encoded != null && !encoded.isEmpty()) {
                for (String part : encoded.split("&", -1)) {
                    int equals = part.indexOf('=');
                    String name = Uri.decode(equals < 0 ? part : part.substring(0, equals));
                    if ("q".equals(name) || "udm".equals(name)) continue;
                    if (!first) kept.append('&');
                    kept.append(part); first = false;
                }
            }
            Uri.Builder result = base.buildUpon().encodedQuery(kept.length() == 0 ? null : kept.toString()).fragment(null);
            if (!text.isEmpty()) result.appendQueryParameter("q", text);
            result.appendQueryParameter("udm", udm);
            String built = result.build().toString();
            return built.length() <= 32768 ? built : null;
        } catch (URISyntaxException | IllegalArgumentException malformed) { return null; }
    }

    private static boolean control(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) return true;
        return false;
    }
}
