package io.github.yudigaga.taplusbridge.lens;

import android.os.Bundle;
import java.util.Set;
import java.util.regex.Pattern;

/** Only metadata crosses Binder; the image is transferred through an owned descriptor. */
public final class LensContract {
    public static final String PACKAGE = "io.github.yudigaga.taplusbridge";
    public static final String HOST = "com.miui.contentextension";
    public static final String SERVICE = PACKAGE + ".lens.LensService";
    public static final int MAX_IMAGE_BYTES = 24 * 1024 * 1024;
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private LensContract() { }

    public static Bundle validatedRequest(Bundle input) {
        if (input == null) throw new IllegalArgumentException("missing_request");
        Bundle result = new Bundle();
        for (String name : new String[]{"request_id", "panel_session_id"}) {
            String value = input.getString(name);
            if (value == null || value.isEmpty() || value.length() > 128)
                throw new IllegalArgumentException("invalid_" + name);
            result.putString(name, value);
        }
        String key = input.getString("image_key");
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("invalid_image_key");
        result.putString("image_key", key);
        String mime = input.getString("mime_type");
        if (!"image/jpeg".equals(mime) && !"image/png".equals(mime) && !"image/webp".equals(mime))
            throw new IllegalArgumentException("invalid_image_type");
        result.putString("mime_type", mime);
        return result;
    }

    public static Bundle update(Bundle request, int generation, String status, String message) {
        Bundle result = new Bundle(request);
        result.putInt("page_generation", generation);
        result.putString("provider", "google_lens_web");
        result.putString("status", status);
        result.putString("message", message);
        return result;
    }

    static String[] validatedRefinement(String requestId, String query, String mode) {
        if (requestId == null || requestId.isEmpty() || requestId.length() > 128
                || query == null || query.length() > 512 || mode == null
                || !Set.of("all", "ai", "exact", "visual").contains(mode))
            throw new IllegalArgumentException("invalid_lens_refinement");
        for (int i = 0; i < query.length(); i++)
            if (Character.isISOControl(query.charAt(i))) throw new IllegalArgumentException("invalid_lens_query");
        return new String[]{requestId, query, mode};
    }

    static void validateBrowser(String requestId, String viewId, int width, int height) {
        if (requestId == null || requestId.isEmpty() || requestId.length() > 128
                || viewId == null || viewId.isEmpty() || viewId.length() > 128
                || width < 1 || height < 1 || width > 8192 || height > 16384 || (long) width * height > 33554432L)
            throw new IllegalArgumentException("invalid_lens_browser");
    }

    static boolean matchesPage(Bundle request, int generation, Bundle parsed) {
        return parsed != null && request.getString("request_id").equals(parsed.getString("request_id"))
                && generation == parsed.getInt("page_generation", -1);
    }

    static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte part : value) result.append(Character.forDigit((part >>> 4) & 15, 16))
                .append(Character.forDigit(part & 15, 16));
        return result.toString();
    }
}
