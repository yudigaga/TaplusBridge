package io.github.yudigaga.taplusbridge.drag;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.time.Duration;
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
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DragHandoffAndroidTest {
    private Context context;
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); }
    private DragPayload text(String value) { return new DragPayload(value, "com.example.source", null, "text/plain", 0, 0, ""); }
    private DragPayload image() throws Exception {
        File directory = new File(context.getCacheDir(), "drag"); directory.mkdirs();
        File file = File.createTempFile("image-", ".png", directory); Files.write(file.toPath(), PNG);
        return new DragPayload("", "com.example.source", file, "image/png", 1, 1, "");
    }
    @Test public void handoffTokensAreSingleUseAndOnlyTheNewestRoundCanBeTaken() {
        String old = DragActionStore.issue(context, "portal", text("old"));
        String latest = DragActionStore.issue(context, "translate", text("latest"));
        assertNull(DragActionStore.take(context, old)); assertNull(DragActionStore.take(context, "forged"));
        Bundle result = DragActionStore.take(context, latest);
        assertEquals("latest", result.getString("text")); assertEquals("translate", result.getString("action"));
        assertTrue(result.getBoolean("recognize"));
        assertEquals("com.example.source", result.getString("source")); assertNull(DragActionStore.take(context, latest));
    }
    @Test public void expiredAndCancelledHandoffsCannotRunLater() {
        String expired = DragActionStore.issue(context, "portal", text("private"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(DragActionStore.TTL)); assertNull(DragActionStore.take(context, expired));
        String cancelled = DragActionStore.issue(context, "portal", text("cancelled"));
        DragActionStore.cancel(cancelled); assertNull(DragActionStore.take(context, cancelled));
    }
    @Test public void handoffKeepsActualHiddenLinksAlongsideTheOriginalTitle() {
        DragPayload card = new DragPayload("卡片标题", "com.example.source", null, "text/plain", 0, 0,
                "<a href='https://example.com/actual?x=1&amp;y=2'>卡片标题</a>");
        String token = DragActionStore.issue(context, "portal", card);
        Bundle result = DragActionStore.take(context, token);
        assertEquals("卡片标题", result.getString("text"));
        assertEquals(List.of("https://example.com/actual?x=1&y=2"), result.getStringArrayList("links"));
    }
    @Test public void imageHandoffTransfersAReadableDescriptor() throws Exception {
        String token = DragActionStore.issue(context, "lens", image()); Bundle result = DragActionStore.take(context, token);
        assertEquals(1, result.getInt("width")); assertEquals(1, result.getInt("height")); assertEquals("image/png", result.getString("mime"));
        try (ParcelFileDescriptor descriptor = result.getParcelable("fd");
             var in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) { assertArrayEquals(PNG, in.readAllBytes()); }
        assertNull(DragActionStore.take(context, token));
    }
    @Test public void shareGrantsAreReadOnlyRandomAndExpireIndependentlyOfActivity() throws Exception {
        var provider = Robolectric.buildContentProvider(DragShareProvider.class).create(DragShareProvider.AUTHORITY).get();
        DragPayload payload = image(); Uri uri = DragShareProvider.expose(context, payload);
        assertEquals("image/png", provider.getType(uri));
        try (var in = new ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r"))) { assertArrayEquals(PNG, in.readAllBytes()); }
        assertThrows(FileNotFoundException.class, () -> provider.openFile(uri, "rw"));
        Uri forged = uri.buildUpon().path("/00000000-0000-0000-0000-000000000000/image.png").build();
        assertThrows(FileNotFoundException.class, () -> provider.openFile(forged, "r"));
        assertThrows(FileNotFoundException.class, () -> provider.openFile(uri.buildUpon().appendQueryParameter("path", "../x").build(), "r"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(DragShareProvider.TTL));
        assertNull(provider.getType(uri)); assertThrows(FileNotFoundException.class, () -> provider.openFile(uri, "r"));
    }
    @Test public void shareProviderCannotExposeFilesOutsideItsOwnedCache() throws Exception {
        File outside = File.createTempFile("image-", ".png", context.getCacheDir()); Files.write(outside.toPath(), PNG);
        DragPayload forged = new DragPayload("", "com.example.source", outside, "image/png", 1, 1, "");
        assertThrows(java.io.IOException.class, () -> DragShareProvider.expose(context, forged)); forged.close();
        assertThrows(IllegalArgumentException.class, () -> DragActionStore.issueExecute(context, "lens", text("title")));
        assertThrows(IllegalArgumentException.class, () -> DragActionStore.issueExecute(context, "browser", text("https://example.com")));
    }
    @Test public void sharedImageCanBeReopenedAfterTheModuleProcessLosesItsMemory() throws Exception {
        DragPayload payload = image(); Uri uri = DragShareProvider.expose(context, payload);
        // A receiver may open the granted URI after the module's idle process has exited.
        try {
            var memory = DragShareProvider.class.getDeclaredField("files"); memory.setAccessible(true);
            ((java.util.Map<?, ?>) memory.get(null)).clear();
        } catch (NoSuchFieldException persistedInstead) { }
        var reopened = Robolectric.buildContentProvider(DragShareProvider.class).create(DragShareProvider.AUTHORITY).get();
        assertEquals("image/png", reopened.getType(uri));
        try (var in = new ParcelFileDescriptor.AutoCloseInputStream(reopened.openFile(uri, "r"))) {
            assertArrayEquals(PNG, in.readAllBytes());
        }
    }
    @Test public void sharedImageExpiresWhenTheWallClockMovesBackwards() throws Exception {
        var provider = Robolectric.buildContentProvider(DragShareProvider.class).create(DragShareProvider.AUTHORITY).get();
        Uri uri = DragShareProvider.expose(context, image());
        assertTrue(android.os.SystemClock.setCurrentTimeMillis(System.currentTimeMillis() - 1_000));
        assertThrows(FileNotFoundException.class, () -> provider.openFile(uri, "r"));
    }
    @Test public void sharingMoreThanEightImagesRevokesTheOldestWithoutDeletingOtherFiles() throws Exception {
        var provider = Robolectric.buildContentProvider(DragShareProvider.class).create(DragShareProvider.AUTHORITY).get();
        DragPayload oldest = image(); Uri first = DragShareProvider.expose(context, oldest);
        Uri newest = null;
        for (int i = 0; i < 8; i++) newest = DragShareProvider.expose(context, image());
        assertThrows(FileNotFoundException.class, () -> provider.openFile(first, "r"));
        assertFalse(oldest.image.exists()); assertEquals("image/png", provider.getType(newest));
    }
    @Test public void completePreservesOriginalTextOcrImageAndLinksForOneChoiceOnly() throws Exception {
        DragPayload originalImage = image();
        DragPayload card = new DragPayload("原卡片标题", "com.example.source", originalImage.image, "image/png", 1, 1,
                "https://example.com/original");
        String token = DragActionStore.issue(context, "share", card);
        assertNull(DragActionStore.complete(context, token, "too soon"));
        Bundle imported = DragActionStore.take(context, token);
        try (ParcelFileDescriptor descriptor = imported.getParcelable("fd")) { assertNotNull(descriptor); }
        assertTrue(card.image.isFile());
        String resultToken = DragActionStore.complete(context, token, "图片里的文字 https://example.org/ocr");
        assertNotNull(resultToken); assertNotEquals(token, resultToken);
        assertNull(DragActionStore.complete(context, token, "late duplicate"));
        DragActionStore.Choice choice = DragActionStore.takeChoice(resultToken);
        assertEquals("share", choice.action()); assertEquals("原卡片标题", choice.originalText());
        assertEquals("图片里的文字 https://example.org/ocr", choice.recognizedText());
        assertEquals(List.of("https://example.com/original", "https://example.org/ocr"), choice.payload().links());
        assertTrue(choice.payload().image.isFile()); assertNull(DragActionStore.takeChoice(resultToken)); choice.payload().close();
    }
    @Test public void emptyOcrStillOffersTheOriginalContentAndExecuteDoesNotRestartRecognition() {
        String token = DragActionStore.issue(context, "copy", text("原文"));
        assertTrue(DragActionStore.take(context, token).getBoolean("recognize"));
        String ready = DragActionStore.complete(context, token, "");
        DragActionStore.Choice choice = DragActionStore.takeChoice(ready);
        assertEquals("", choice.recognizedText()); assertEquals("原文", choice.payload().text);
        String execute = DragActionStore.issueExecute(context, "translate", choice.payload());
        assertFalse(DragActionStore.take(context, execute).getBoolean("recognize"));
        assertNull(DragActionStore.complete(context, execute, "cannot loop"));
        assertNull(DragActionStore.take(context, execute));
    }
    @Test public void completionPreservesNativeCardLinksAndRejectsInvalidLinksWithoutConsumingTheRound() {
        String token = DragActionStore.issue(context, "rules", new DragPayload("卡片标题", "com.example.source",
                null, "text/plain", 0, 0, "https://example.com/original"));
        assertNotNull(DragActionStore.take(context, token));
        assertNull(DragActionStore.complete(context, token, "OCR", List.of("intent://untrusted")));
        assertNull(DragActionStore.complete(context, token, "OCR", java.util.Collections.nCopies(65, "https://example.com")));
        String ready = DragActionStore.complete(context, token, "OCR https://example.org/ocr",
                List.of("https://example.com/card", "https://example.org/ocr", "https://example.com/card"));
        assertNotNull(ready);
        assertNull(DragActionStore.complete(context, token, "late", List.of("https://example.org/later")));
        DragActionStore.Choice choice = DragActionStore.takeChoice(ready);
        assertEquals("rules", choice.action()); assertEquals("卡片标题", choice.originalText());
        assertEquals("OCR https://example.org/ocr", choice.recognizedText());
        assertEquals(List.of("https://example.com/original", "https://example.org/ocr", "https://example.com/card"), choice.payload().links());
        assertNull(DragActionStore.takeChoice(ready)); choice.payload().close();
    }
    @Test public void aNewRoundInvalidatesBothWaitingAndReadyRecognitionResults() {
        String old = DragActionStore.issue(context, "share", text("old")); DragActionStore.take(context, old);
        String ready = DragActionStore.complete(context, old, "recognized");
        String latest = DragActionStore.issue(context, "browser", text("https://example.com/latest"));
        assertNull(DragActionStore.takeChoice(ready)); assertNull(DragActionStore.complete(context, old, "late"));
        assertNotNull(DragActionStore.take(context, latest));
        ShadowSystemClock.advanceBy(Duration.ofMillis(DragActionStore.TTL));
        assertNull(DragActionStore.complete(context, latest, "late"));
    }
    @Test public void readyChoicesAlsoExpireAndRejectTamperedTokens() {
        String token = DragActionStore.issue(context, "portal", text("original")); DragActionStore.take(context, token);
        assertNull(DragActionStore.complete(context, "forged", "other"));
        String ready = DragActionStore.complete(context, token, "recognized");
        assertNull(DragActionStore.takeChoice("forged"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(DragActionStore.TTL)); assertNull(DragActionStore.takeChoice(ready));
    }
}
