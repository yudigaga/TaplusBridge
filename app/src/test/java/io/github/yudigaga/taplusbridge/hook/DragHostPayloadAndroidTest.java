package io.github.yudigaga.taplusbridge.hook;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class DragHostPayloadAndroidTest {
    private static final String IDENTITY = "a".repeat(43);

    @Test public void originalBodyIsNotRestrictedToAUrlOrRewritten() throws Exception {
        String original = "原始正文\n带空格 与链接 https://example.org/actual?q=1#part";
        FakeResult result = create(data(original), null);
        assertEquals("example.source", result.token.source);
        assertEquals("example.source.DragDrop", result.token.activity);
        assertEquals(IDENTITY, result.token.identity); assertEquals(77, result.token.version);
        Bundle text = (Bundle) result.jobs.get("text_pick").get("pick_mode");
        assertEquals(original, text.getString("viewContent"));
        assertTrue(text.getBoolean("taplus_drag")); assertEquals(1, text.getInt("catchType"));
        assertEquals(new Rect(0, 0, 1080, 2400), text.getParcelable("view_rect"));
        assertEquals(new PointF(540, 1200), text.getParcelableArray("touch_points")[0]);
        assertNull(result.jobs.get("image_pick")); assertFalse(text.containsKey("viewBitmap"));
        assertFalse(result.properties.containsKey("observe_control_event"));
    }

    @Test public void pictureAndOriginalBodyShareOneNativeImageJob() throws Exception {
        Bitmap image = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
        try {
            FakeResult result = create(data("保留原始说明 https://example.org/source"), image);
            Bundle picture = (Bundle) result.jobs.get("image_pick").get("image_pick_mode");
            assertSame(image, picture.getParcelable("viewBitmap")); assertFalse(image.isRecycled());
            assertEquals(2, picture.getInt("catchType")); assertTrue(picture.getBoolean("taplus_drag"));
            assertNull(result.jobs.get("text_pick"));
            assertEquals("保留原始说明 https://example.org/source", result.properties.get("taplus_scope_text"));
            assertEquals(Boolean.TRUE, result.properties.get("taplus_scope_mixed"));
        } finally { image.recycle(); }
    }

    @Test public void pictureWithoutCaptionStaysAnImageAndDoesNotInventText() throws Exception {
        Bitmap image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        try {
            FakeResult result = create(data(null), image);
            assertNull(result.jobs.get("text_pick"));
            assertFalse(result.properties.containsKey("taplus_scope_text"));
            assertFalse(result.properties.containsKey("taplus_scope_mixed"));
        } finally { image.recycle(); }
    }

    @Test public void emptyOrOversizedBodyWithoutAnImageIsRejected() {
        for (String text : new String[]{null, "", " \n\t", "a".repeat(128 * 1024 + 1)})
            assertThrows(IllegalArgumentException.class, () -> create(data(text), null));
    }

    @Test public void malformedSourceAndTicketAreRejected() throws Exception {
        DragHostPayload factory = new DragHostPayload(FakeResult.class, FakeToken.class);
        for (String source : new String[]{null, "invalid", "example.source;run", "example/source", " a.b"}) {
            Bundle value = data("正文"); value.putString("source", source);
            assertThrows(IllegalArgumentException.class, () -> create(value, null));
        }
        for (String ticket : new String[]{null, "", "a".repeat(42), "a".repeat(44), " ".repeat(43)})
            assertThrows(IllegalArgumentException.class, () -> factory.create(data("正文"), ticket, 77, 1080, 2400, null));
    }

    @Test public void screenBoundsAndVersionAreValidated() throws Exception {
        DragHostPayload factory = new DragHostPayload(FakeResult.class, FakeToken.class);
        assertThrows(IllegalArgumentException.class, () -> factory.create(data("正文"), IDENTITY, -1, 1080, 2400, null));
        for (int[] size : new int[][]{{0, 100}, {100, 0}, {-1, 1}, {16385, 100}, {100, 16385}})
            assertThrows(IllegalArgumentException.class, () -> factory.create(data("正文"), IDENTITY, 77, size[0], size[1], null));
    }

    @Test public void recycledAndOversizedPicturesAreRejected() {
        Bitmap recycled = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); recycled.recycle();
        assertThrows(IllegalArgumentException.class, () -> create(data(null), recycled));
        for (int[] size : new int[][]{{2001, 1}, {1, 2001}, {1501, 2000}}) {
            Bitmap image = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            try { assertThrows(IllegalArgumentException.class, () -> create(data(null), image)); }
            finally { image.recycle(); }
        }
    }

    private static FakeResult create(Bundle value, Bitmap image) throws ReflectiveOperationException {
        return (FakeResult) new DragHostPayload(FakeResult.class, FakeToken.class)
                .create(value, IDENTITY, 77, 1080, 2400, image);
    }

    private static Bundle data(String text) {
        Bundle value = new Bundle(); value.putString("source", "example.source"); value.putString("text", text); return value;
    }

    public static final class FakeToken {
        final String source, activity, identity;
        final int version;
        public FakeToken(String source, String activity, String identity, int version) {
            this.source = source; this.activity = activity; this.identity = identity; this.version = version;
        }
    }

    public static final class FakeResult {
        FakeToken token;
        final Map<String, Object> properties = new HashMap<>();
        final Map<String, Map<String, Object>> jobs = new HashMap<>();
        public FakeResult() { }
        public void setInjectorToken(FakeToken value) { token = value; }
        public Map<String, Object> getPropertyMap() { return properties; }
        public void addContent(String job, String feature, Object value) {
            jobs.computeIfAbsent(job, ignored -> new HashMap<>()).put(feature, value);
        }
    }
}
