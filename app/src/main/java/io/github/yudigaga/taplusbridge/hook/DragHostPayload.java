package io.github.yudigaga.taplusbridge.hook;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/** Separate drag adapter: native capture keeps its strict URL-only text contract. */
public final class DragHostPayload {
    private final Constructor<?> result, token;
    private final Method setToken, properties, addContent;

    public DragHostPayload(ClassLoader loader) throws ReflectiveOperationException {
        this(loader.loadClass("miui.contentcatcher.sdk.ClientCatcherResult"),
                loader.loadClass("miui.contentcatcher.sdk.Token"));
    }

    public DragHostPayload(Class<?> resultClass, Class<?> tokenClass) throws ReflectiveOperationException {
        result = resultClass.getDeclaredConstructor(); result.setAccessible(true);
        token = tokenClass.getDeclaredConstructor(String.class, String.class, String.class, int.class); token.setAccessible(true);
        setToken = resultClass.getDeclaredMethod("setInjectorToken", tokenClass); setToken.setAccessible(true);
        properties = resultClass.getDeclaredMethod("getPropertyMap"); properties.setAccessible(true);
        addContent = resultClass.getDeclaredMethod("addContent", String.class, String.class, Object.class); addContent.setAccessible(true);
    }

    public Object create(Bundle data, String identity, int version, int width, int height, Bitmap bitmap)
            throws ReflectiveOperationException {
        String source = data.getString("source"), text = data.getString("text");
        var links = data.getStringArrayList("links");
        if (links != null) {
            if (links.size() > 24) throw new IllegalArgumentException("drag_links");
            StringBuilder combined = new StringBuilder(text == null ? "" : text);
            for (String link : links) {
                if (!ScopeCapturePayload.validTextUrl(link)) throw new IllegalArgumentException("drag_link");
                if (!io.github.yudigaga.taplusbridge.recognition.LinkExtractor.extract(combined.toString()).contains(link)) {
                    if (combined.length() > 0) combined.append('\n');
                    combined.append(link);
                }
                if (combined.length() > 128 * 1024) throw new IllegalArgumentException("drag_text");
            }
            text = combined.toString();
        }
        if (!HostPolicy.validPackage(source) || identity == null || !identity.matches("[A-Za-z0-9_-]{43}")
                || version < 0 || width <= 0 || height <= 0 || width > 16384 || height > 16384
                || (text != null && text.length() > 128 * 1024)
                || (bitmap == null && (text == null || text.isBlank())))
            throw new IllegalArgumentException("drag_payload");
        if (bitmap != null && (bitmap.isRecycled() || bitmap.getWidth() > ScopeCapturePayload.MAX_EDGE
                || bitmap.getHeight() > ScopeCapturePayload.MAX_EDGE
                || (long) bitmap.getWidth() * bitmap.getHeight() > ScopeCapturePayload.MAX_PIXELS))
            throw new IllegalArgumentException("drag_image");
        Object value = result.newInstance();
        setToken.invoke(value, token.newInstance(source, source + ".DragDrop", identity, version));
        Object raw = properties.invoke(value);
        if (!(raw instanceof Map<?, ?>)) throw new IllegalArgumentException("drag_properties");
        @SuppressWarnings("unchecked") Map<String, Object> flags = (Map<String, Object>) raw;
        float x = width / 2f, y = height / 2f;
        flags.put("coordinate", x + "-" + y);
        for (String name : new String[]{"isfullscreen", "isedittext", "actionbarvisible", "ismultiscreen"}) flags.put(name, "0");
        Bundle content = new Bundle(); content.putBoolean("taplus_drag", true);
        content.putInt("catchType", bitmap == null ? 1 : 2);
        content.putParcelableArray("touch_points", new PointF[]{new PointF(x, y), new PointF(x, y)});
        content.putParcelable("view_rect", new Rect(0, 0, width, height));
        if (bitmap == null) {
            content.putString("viewContent", text);
            addContent.invoke(value, "text_pick", "pick_mode", content);
        } else {
            content.putParcelable("viewBitmap", bitmap);
            addContent.invoke(value, "image_pick", "image_pick_mode", content);
            if (text != null && !text.isBlank()) {
                flags.put("taplus_scope_text", text);
                flags.put("taplus_scope_mixed", true);
            }
        }
        return value;
    }

    public static Bitmap image(Bundle data) throws IOException {
        if (!(data.get("fd") instanceof ParcelFileDescriptor fd)) return null;
        try (fd; ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            long size = fd.getStatSize();
            if (size <= 0 || size > ScopeCapturePayload.MAX_BYTES) throw new IOException("drag_image_size");
            Bitmap bitmap = ScopeCapturePayload.decode(ScopeCapturePayload.read(stream, (int) size));
            if (bitmap.getWidth() != data.getInt("width") || bitmap.getHeight() != data.getInt("height")) {
                bitmap.recycle(); throw new IOException("drag_image_dimensions");
            }
            return bitmap;
        }
    }
}
