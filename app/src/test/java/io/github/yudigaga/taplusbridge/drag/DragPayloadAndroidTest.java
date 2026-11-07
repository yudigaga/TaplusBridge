package io.github.yudigaga.taplusbridge.drag;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DragPayloadAndroidTest {
    @Test public void downloadLinksSurviveNativeUriClipsRecognitionAndUserSelection() throws Exception {
        for (String link : List.of("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=a%2Bb",
                "ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/")) {
            assertTrue(DragPayload.validRecognizedLinks(List.of(link)));
            ClipData clip = new ClipData("download", new String[]{"text/uri-list"}, new ClipData.Item(Uri.parse(link)));
            try (DragPayload nativePayload = read(clip)) {
                assertEquals(List.of(link), nativePayload.links());
                DragPayload selected = nativePayload.select(List.of(), List.of(link), false);
                assertEquals(List.of(link), selected.links());
                assertEquals(link, selected.sharedText());
            }
        }
    }

    private Context context;
    private static final String SOURCE = "com.example.source";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); }
    private DragPayload read(ClipData data) throws IOException { return DragPayload.read(context, data, SOURCE, new CancellationSignal()); }

    @Test public void disabledNativeChannelCannotReenterThroughAnExternalDragClip() {
        new io.github.yudigaga.taplusbridge.config.ConfigStore(context).setRecognitionChannel(
                io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel.NATIVE,false);
        assertThrows(IOException.class,()->read(ClipData.newPlainText("card","https://example.com/native")));
    }

    @Test public void plainTextAndActualHtmlHrefPreserveTheirContent() throws Exception {
        try (DragPayload text = read(ClipData.newPlainText("label", "任意文字\n第二行"))) {
            assertEquals("任意文字\n第二行", text.text); assertTrue(text.links().isEmpty());
        }
        try (DragPayload card = read(ClipData.newHtmlText("card", "卡片标题", "<a href=\"https://example.com/item?x=1&amp;y=2\">卡片标题</a>"))) {
            assertEquals("卡片标题", card.text); assertEquals(List.of("https://example.com/item?x=1&y=2"), card.links());
        }
    }
    @Test public void readsOnlyTheExplicitHttpFieldsOfAnEmbeddedIntent() throws Exception {
        Intent launch = new Intent("dangerous.custom.ACTION", Uri.parse("https://example.com/real"))
                .setPackage("com.example.target").putExtra(Intent.EXTRA_TEXT, "https://example.org/extra");
        ClipData data = new ClipData("card", new String[]{"text/plain"}, new ClipData.Item("标题", launch, null));
        try (DragPayload card = read(data)) {
            assertEquals("标题", card.text);
            assertEquals(List.of("https://example.com/real", "https://example.org/extra"), card.links());
        }
        assertEquals("dangerous.custom.ACTION", launch.getAction());
        org.robolectric.shadows.ShadowApplication application = org.robolectric.shadow.api.Shadow.extract(context);
        assertNull(application.getNextStartedActivity());
    }
    @Test public void intentSchemesTitlesAndMissingDestinationsNeverInventALink() throws Exception {
        Intent unsupported = new Intent(Intent.ACTION_VIEW, Uri.parse("intent://secret#Intent;scheme=app;end"));
        ClipData data = new ClipData("card", new String[]{"text/plain"}, new ClipData.Item("卡片标题", unsupported, null));
        try (DragPayload card = read(data)) { assertEquals("卡片标题", card.text); assertTrue(card.links().isEmpty()); }
        ClipData intentOnly = new ClipData("card", new String[]{"text/plain"}, new ClipData.Item(unsupported));
        assertThrows(IOException.class, () -> read(intentOnly));
    }
    @Test public void rejectsOversizeTextEmptyContentAndUnboundedItemLists() {
        assertThrows(IOException.class, () -> read(ClipData.newPlainText("text", "x".repeat(DragPayload.MAX_TEXT + 1))));
        assertThrows(IOException.class, () -> read(ClipData.newPlainText("text", " \n ")));
        ClipData many = ClipData.newPlainText("text", "first");
        for (int i = 0; i < DragPayload.MAX_ITEMS; i++) many.addItem(new ClipData.Item("other"));
        assertThrows(IOException.class, () -> read(many));
    }
    @Test public void acceptsActualUriLinksButNeverDownloadsImageUrlsOrReadsFileUris() throws Exception {
        try (DragPayload link = read(ClipData.newRawUri("link", Uri.parse("https://example.com/image.png")))) {
            assertNull(link.image); assertEquals(List.of("https://example.com/image.png"), link.links());
        }
        assertThrows(IOException.class, () -> read(ClipData.newRawUri("file", Uri.parse("file:///sdcard/private.png"))));
    }
    @Test public void shareKeepsTheTitleAndEachActualLinkWithoutTruncatingOversizeUrls() throws Exception {
        DragPayload card = new DragPayload("卡片标题 https://example.com/visible", SOURCE, null, "text/plain", 0, 0,
                "<a href='https://example.com/hidden?x=1&amp;y=2'>标题</a> https://example.com/visible");
        assertEquals("卡片标题 https://example.com/visible\nhttps://example.com/hidden?x=1&y=2", card.sharedText());
        assertEquals("卡片标题 https://example.com/visible", card.text);
        DragPayload oversized = new DragPayload("x".repeat(DragPayload.MAX_TEXT - 1), SOURCE, null, "text/plain", 0, 0,
                "https://example.com/complete");
        assertThrows(IOException.class, oversized::sharedText);
    }
    @Test public void choosingContentCombinesOnlySelectedTextImageAndActualLinks() throws Exception {
        DragPayload base = new DragPayload("原文 https://example.com/original", SOURCE, null, "text/plain", 0, 0,
                "https://example.com/hidden").withRecognizedLinks("识别文字 https://example.org/ocr");
        assertEquals(3, base.links().size());
        DragPayload selected = base.select(List.of("图片识别文字", "图片识别文字"), List.of("https://example.org/ocr"), false);
        assertEquals("图片识别文字\nhttps://example.org/ocr", selected.text);
        assertEquals(List.of("https://example.org/ocr"), selected.links());
        assertThrows(IOException.class, () -> base.select(List.of(), List.of(), false));
        assertThrows(IOException.class, () -> base.select(List.of("原文"), List.of("javascript:alert(1)"), false));
        assertThrows(IOException.class, () -> base.select(List.of("x".repeat(DragPayload.MAX_TEXT), "extra"), List.of(), false));
        assertEquals("原文 https://example.com/original", base.text);
    }
    @Test public void capturedCardAndRecommendationLinksJoinOcrWithoutReplacingOriginalContent() {
        DragPayload original = new DragPayload("原卡片标题 https://example.com/visible", SOURCE, null,
                "text/plain", 0, 0, "https://example.com/original-hidden")
                .withRecognizedLinks("https://example.com/earlier");
        DragPayload recognized = original.withRecognizedLinks("OCR https://example.org/ocr",
                List.of("https://example.com/card", "https://example.org/ocr", "https://example.com/card"));
        assertEquals(original.text, recognized.text); assertEquals(SOURCE, recognized.source);
        assertEquals(List.of("https://example.com/visible", "https://example.com/original-hidden",
                "https://example.com/earlier", "https://example.org/ocr", "https://example.com/card"), recognized.links());
        assertEquals(3, original.links().size());
    }
    @Test public void completionLinkValidationRejectsMalformedTypesSchemesAndUnboundedInput() {
        assertFalse(DragPayload.validRecognizedLinks(null));
        assertFalse(DragPayload.validRecognizedLinks("https://example.com"));
        assertFalse(DragPayload.validRecognizedLinks(List.of(7)));
        assertFalse(DragPayload.validRecognizedLinks(List.of("javascript:alert(1)")));
        assertFalse(DragPayload.validRecognizedLinks(List.of("https://example.com/%0Ainjected")));
        assertFalse(DragPayload.validRecognizedLinks(List.of("https://example.com/" + "x".repeat(8192))));
        assertFalse(DragPayload.validRecognizedLinks(java.util.Collections.nCopies(io.github.yudigaga.taplusbridge.recognition.LinkExtractor.MAX_RESULTS + 1, "https://example.com")));
        assertFalse(DragPayload.validRecognizedLinks(java.util.Collections.nCopies(64, "https://example.com/" + "x".repeat(4096))));
        assertTrue(DragPayload.validRecognizedLinks(List.of("https://example.com/card?x=1&y=2")));
        assertTrue(DragPayload.validRecognizedLinks(List.of()));
    }
    @Test public void copiesGrantedContentImageIntoAnIndependentBoundedPng() throws Exception {
        ImageInput.file = File.createTempFile("source-", ".png", context.getCacheDir()); Files.write(ImageInput.file.toPath(), PNG);
        var provider = Robolectric.buildContentProvider(ImageInput.class).create("com.example.drag.input").get();
        ShadowContentResolver.registerProviderInternal("com.example.drag.input", provider);
        ClipData image = new ClipData("image", new String[]{"image/png"}, new ClipData.Item(Uri.parse("content://com.example.drag.input/image")));
        try (DragPayload result = read(image)) {
            assertNotNull(result.image); assertNotEquals(ImageInput.file, result.image);
            assertEquals("image/png", result.mime); assertEquals(1, result.width); assertEquals(1, result.height);
            assertTrue(result.image.length() > 0); assertTrue(result.image.length() <= DragPayload.MAX_BYTES);
        }
        assertTrue(ImageInput.file.isFile()); ImageInput.file.delete();
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void oversizeAndUndecodableImagesAreRejectedWithoutLeavingCopies() throws Exception {
        ImageInput.file = File.createTempFile("source-", ".png", context.getCacheDir());
        try (var file = new java.io.RandomAccessFile(ImageInput.file, "rw")) { file.setLength(DragPayload.MAX_BYTES + 1L); }
        var provider = Robolectric.buildContentProvider(ImageInput.class).create("com.example.drag.input").get();
        ShadowContentResolver.registerProviderInternal("com.example.drag.input", provider);
        ClipData image = new ClipData("image", new String[]{"image/png"}, new ClipData.Item(Uri.parse("content://com.example.drag.input/image")));
        assertThrows(IOException.class, () -> read(image)); Files.write(ImageInput.file.toPath(), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> read(image));
        File directory = new File(context.getCacheDir(), "drag");
        assertEquals(0, directory.listFiles() == null ? 0 : directory.listFiles().length); ImageInput.file.delete();
    }
    public static final class ImageInput extends ContentProvider {
        static File file;
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "image/png"; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
