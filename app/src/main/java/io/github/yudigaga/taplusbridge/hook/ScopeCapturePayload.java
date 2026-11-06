package io.github.yudigaga.taplusbridge.hook;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.regex.Pattern;

/** Validates framework-owned capture responses and adapts them to the inspected system SDK. */
public final class ScopeCapturePayload {
    public static final int MAX_BYTES = 8 * 1024 * 1024, MAX_EDGE = 2000, MAX_PIXELS = 3_000_000;
    public static final int MAX_URL_LENGTH = 16 * 1024;
    public static final int MAX_CONTENT_LENGTH = 128 * 1024;
    public static final long MAX_AGE_MILLIS = 10_000;
    public static final String LINK_URL_KEY = "taplus_scope_link_url";
    private static final Pattern ACTIVITY = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private ScopeCapturePayload() {}

    public record Metadata(String source, String activity, int uid, int user, int display,
                           float x, float y, int left, int top, int right, int bottom,
                           long capturedElapsed, int width, int height, int bytes, String kind) {
        public Rect rect() { return new Rect(left, top, right, bottom); }
    }

    public static Metadata validate(Bundle response, long now) {
        if (response == null) throw invalid();
        String source = string(response, "source"), activity = string(response, "activity");
        String kind = response.containsKey("kind") ? string(response, "kind") : "image";
        if (!"image".equals(kind) && !"text".equals(kind) && !"resume".equals(kind) && !"content".equals(kind)) throw invalid();
        boolean text = "text".equals(kind);
        boolean content = "content".equals(kind);
        boolean resume = "resume".equals(kind);
        if ((content && !validContent(response.get("text")))
                || (response.containsKey("text") && (!validContent(response.get("text")) || resume))) throw invalid();
        if (resume ? response.containsKey("url") || response.containsKey("fd")
                : response.containsKey("url") && !validTextUrl(string(response, "url"))) throw invalid();
        int uid = integer(response, "uid"), user = integer(response, "user"), display = integer(response, "display");
        int width = integer(response, "width"), height = integer(response, "height"), bytes = integer(response, "bytes");
        int left = integer(response, "left"), top = integer(response, "top");
        int right = integer(response, "right"), bottom = integer(response, "bottom");
        float x = floating(response, "x"), y = floating(response, "y");
        Object elapsed = response.get("captured_elapsed");
        Object began = response.get("gesture_elapsed");
        if (!(elapsed instanceof Long captured) || !HostPolicy.validPackage(source)
                || activity.length() > 512 || !ACTIVITY.matcher(activity).matches()
                || uid < 10000 || uid >= 100000 || user != 0 || display != 0
                || width <= 0 || height <= 0 || width > 16384 || height > 16384
                || (resume ? bytes != 0 : content ? bytes != 0 || response.containsKey("fd")
                    : text ? bytes != 0 || response.containsKey("fd") || !validTextUrl(string(response, "url"))
                    : bytes <= 0 || bytes > MAX_BYTES) || left < 0 || top < 0
                || right <= left || bottom <= top || right > width || bottom > height
                || !Float.isFinite(x) || !Float.isFinite(y) || x < left || x >= right || y < top || y >= bottom
                || captured < 0 || now < captured || now - captured > MAX_AGE_MILLIS
                || (began != null && (!(began instanceof Long started) || started < 0 || started > captured
                    || captured - started > MAX_AGE_MILLIS))) throw invalid();
        return new Metadata(source, activity, uid, user, display, x, y, left, top, right, bottom,
                captured, width, height, bytes, kind);
    }

    public static boolean validContent(Object value) {
        return value instanceof String text && !text.isBlank() && text.length() <= MAX_CONTENT_LENGTH
                && text.indexOf('\0') < 0;
    }

    /** A complete single supported link, not a title or substring containing one. */
    public static boolean validTextUrl(String value) {
        if (io.github.yudigaga.taplusbridge.recognition.RouteLink.valid(value, MAX_URL_LENGTH)
                && !io.github.yudigaga.taplusbridge.recognition.RouteLink.validHttp(value, MAX_URL_LENGTH)) return true;
        if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 32 || Character.isISOControl(c)) return false;
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16), low = Character.digit(value.charAt(i + 2), 16);
                int decoded = high < 0 || low < 0 ? -1 : high * 16 + low;
                if (decoded >= 0 && (decoded < 32 || decoded == 127)) return false;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        try {
            URI uri = new URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && !uri.isOpaque() && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getRawUserInfo() == null && uri.getRawAuthority() != null
                    && !uri.getRawAuthority().endsWith(":") && uri.getPort() != 0 && uri.getPort() <= 65535;
        } catch (URISyntaxException invalid) { return false; }
    }

    /** Requires the exact announced length and EOF; never allocates or reads an unbounded payload. */
    public static byte[] read(InputStream stream, int expectedBytes) throws IOException {
        if (expectedBytes <= 0 || expectedBytes > MAX_BYTES) throw invalid();
        byte[] data = new byte[expectedBytes];
        int position = 0;
        while (position < data.length) {
            int count = stream.read(data, position, Math.min(8192, data.length - position));
            if (count < 0) throw new IOException("scope_capture_truncated");
            if (count == 0) {
                int next = stream.read();
                if (next < 0) throw new IOException("scope_capture_truncated");
                data[position++] = (byte) next;
            } else position += count;
        }
        if (stream.read() != -1) throw new IOException("scope_capture_oversized");
        return data;
    }

    public static Bitmap decode(byte[] data) {
        if (data == null || data.length < 24 || data.length > MAX_BYTES
                || (data[0] & 255) != 137 || data[1] != 80 || data[2] != 78 || data[3] != 71
                || data[4] != 13 || data[5] != 10 || data[6] != 26 || data[7] != 10) throw invalid();
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        dimensions(bounds.outWidth, bounds.outHeight);
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw invalid();
        if (bitmap.getWidth() != bounds.outWidth || bitmap.getHeight() != bounds.outHeight) {
            bitmap.recycle(); throw invalid();
        }
        return bitmap;
    }

    private static void dimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE
                || (long) width * height > MAX_PIXELS) throw invalid();
    }

    public static final class Factory {
        private final Constructor<?> resultConstructor, tokenConstructor;
        private final Method setToken, properties, addContent;

        public Factory(ClassLoader loader) throws ReflectiveOperationException {
            this(loader.loadClass("miui.contentcatcher.sdk.ClientCatcherResult"), loader.loadClass("miui.contentcatcher.sdk.Token"));
        }

        public Factory(Class<?> result, Class<?> token) throws ReflectiveOperationException {
            resultConstructor = result.getDeclaredConstructor(); resultConstructor.setAccessible(true);
            tokenConstructor = token.getDeclaredConstructor(String.class, String.class, String.class, int.class);
            tokenConstructor.setAccessible(true);
            setToken = method(result, "setInjectorToken", void.class, token);
            properties = method(result, "getPropertyMap", Map.class);
            addContent = method(result, "addContent", void.class, String.class, String.class, Object.class);
        }

        public Object create(Metadata metadata, Bitmap bitmap, int versionCode, String identity)
                throws ReflectiveOperationException {
            return createImage(metadata, bitmap, null, versionCode, identity);
        }

        public Object createImage(Metadata metadata, Bitmap bitmap, String url, int versionCode, String identity)
                throws ReflectiveOperationException {
            if (!"image".equals(metadata.kind()) || bitmap == null || bitmap.isRecycled()) throw invalid();
            dimensions(bitmap.getWidth(), bitmap.getHeight());
            Object result = result(metadata, versionCode, identity);
            Bundle image = geometry(metadata, 2); image.putParcelable("viewBitmap", bitmap);
            addContent.invoke(result, "image_pick", "image_pick_mode", image);
            return enrich(result, url);
        }

        public Object createText(Metadata metadata, String url, int versionCode, String identity)
                throws ReflectiveOperationException {
            if (!"text".equals(metadata.kind()) || !validTextUrl(url)) throw invalid();
            Object result = result(metadata, versionCode, identity);
            Bundle text = geometry(metadata, 1); text.putString("viewContent", url);
            addContent.invoke(result, "text_pick", "pick_mode", text);
            return enrich(result, url);
        }

        public Object createContent(Metadata metadata, String text, String url, int versionCode, String identity)
                throws ReflectiveOperationException {
            if (!"content".equals(metadata.kind()) || !validContent(text)
                    || (url != null && !validTextUrl(url))) throw invalid();
            Object result = result(metadata, versionCode, identity);
            Bundle content = geometry(metadata, 1); content.putString("viewContent", text);
            addContent.invoke(result, "text_pick", "pick_mode", content);
            return enrich(result, url);
        }

        /** Supplement the same native pick; never add a competing primary text/image job. */
        public Object enrich(Object nativeResult, String url) throws ReflectiveOperationException {
            if (!resultConstructor.getDeclaringClass().isInstance(nativeResult) || (url != null && !validTextUrl(url)))
                throw invalid();
            if (url != null) {
                Object raw = properties.invoke(nativeResult);
                if (!(raw instanceof Map<?, ?>)) throw invalid();
                @SuppressWarnings("unchecked") Map<String, Object> flags = (Map<String, Object>) raw;
                flags.put(LINK_URL_KEY, url);
            }
            return nativeResult;
        }

        private Object result(Metadata metadata, int versionCode, String identity) throws ReflectiveOperationException {
            if (versionCode < 0 || identity == null || identity.length() < 16 || identity.length() > 128) throw invalid();
            Object result = resultConstructor.newInstance();
            Object token = tokenConstructor.newInstance(metadata.source(), metadata.activity(), identity, versionCode);
            setToken.invoke(result, token);
            Object raw = properties.invoke(result);
            if (!(raw instanceof Map<?, ?>)) throw invalid();
            @SuppressWarnings("unchecked") Map<String, Object> flags = (Map<String, Object>) raw;
            flags.put("coordinate", metadata.x() + "-" + metadata.y());
            for (String flag : new String[]{"isfullscreen", "isedittext", "actionbarvisible", "ismultiscreen"}) flags.put(flag, "0");
            return result;
        }

        private static Bundle geometry(Metadata metadata, int catchType) {
            Bundle value = new Bundle(); value.putInt("catchType", catchType);
            value.putBoolean("taplus_scope_capture", true);
            value.putParcelableArray("touch_points", new PointF[]{new PointF(metadata.x(), metadata.y()), new PointF(metadata.x(), metadata.y())});
            value.putParcelable("view_rect", metadata.rect()); return value;
        }

        private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... arguments)
                throws ReflectiveOperationException {
            Method method = owner.getDeclaredMethod(name, arguments);
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result)
                throw new NoSuchMethodException("scope_capture_sdk_contract");
            method.setAccessible(true); return method;
        }
    }

    private static int integer(Bundle data, String key) {
        Object value = data.get(key); if (!(value instanceof Integer number)) throw invalid(); return number;
    }
    private static float floating(Bundle data, String key) {
        Object value = data.get(key); if (!(value instanceof Float number)) throw invalid(); return number;
    }
    private static String string(Bundle data, String key) {
        Object value = data.get(key); if (!(value instanceof String text)) throw invalid(); return text;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("scope_capture_invalid"); }
}
