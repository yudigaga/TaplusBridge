package io.github.yudigaga.taplusbridge.drag;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class DragNativeImportAndroidTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private Bundle request() {
        Bundle request = new Bundle(); request.putString("lease", "G".repeat(43)); request.putString("source", "com.example.source");
        request.putString("direction", "down"); request.putString("text", "原文 https://example.org/actual"); return request;
    }

    private Context enabled() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", 0).edit().clear().commit();
        ConfigStore config = new ConfigStore(context); config.setEnabled(true);
        config.setDragSettings(new DragSettings(true, "portal", "share", "lens", "translate", false, java.util.List.of()));
        return context;
    }

    @Test public void nativeImportNeedsOnePhysicalProofAndKeepsTheDirectionsFixedAction() {
        Context context = enabled(); boolean[] used = {false};
        java.util.function.BiFunction<String, Bundle, Bundle> runtime = (method, data) -> {
            if ("status".equals(method)) return Bundle.EMPTY;
            assertEquals("drag_gesture_consume", method); assertEquals(3, data.size());
            assertEquals("G".repeat(43), data.getString("token")); assertEquals("down", data.getString("direction"));
            if (used[0]) return Bundle.EMPTY;
            used[0] = true; Bundle accepted = new Bundle(); accepted.putBoolean("accepted", true); accepted.putString("action", "share");
            return accepted;
        };
        Bundle response = DragNativeImport.receive(context, request(), runtime);
        String token = response.getString("token"); assertNotNull(token);
        Bundle pending = DragActionStore.take(context, token);
        assertEquals("share", pending.getString("action")); assertTrue(pending.getBoolean("recognize"));
        assertEquals("原文 https://example.org/actual", pending.getString("text"));
        assertNull(DragNativeImport.receive(context, request(), runtime).getString("token"));
        DragActionStore.cancel(token);
    }

    @Test public void callerActionsAndConfigurationChangesCannotCreateAnImport() {
        Context context = enabled(); int[] calls = {0};
        Bundle forged = request(); forged.putString("action", "browser");
        assertEquals("invalid_request", DragNativeImport.receive(context, forged, (method, data) -> { calls[0]++; return Bundle.EMPTY; }).getString("reason"));
        assertEquals(0, calls[0]);
        Bundle changed = DragNativeImport.receive(context, request(), (method, data) -> {
            if ("status".equals(method)) return Bundle.EMPTY;
            new ConfigStore(context).setEnabled(false);
            Bundle accepted = new Bundle(); accepted.putBoolean("accepted", true); accepted.putString("action", "share"); return accepted;
        });
        assertEquals("gesture_expired", changed.getString("reason")); assertNull(changed.getString("token"));
    }

    @Test public void originalTextAndRealLinksSurviveImportWithoutAnImage() throws Exception {
        try (var payload = DragNativeImport.read(RuntimeEnvironment.getApplication(),
                "标题 https://example.org/original", "com.example.source", null)) {
            assertEquals("标题 https://example.org/original", payload.text);
            assertEquals(java.util.List.of("https://example.org/original"), payload.links());
            assertNull(payload.image);
        }
    }

    @Test public void copiedImageRemainsUsableAfterTheHostsDescriptorAndFileAreGone() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File host = File.createTempFile("native-", ".png", context.getCacheDir()); Files.write(host.toPath(), PNG);
        var payload = DragNativeImport.read(context, "原文", "com.example.source",
                ParcelFileDescriptor.open(host, ParcelFileDescriptor.MODE_READ_ONLY));
        assertTrue(host.delete());
        try (payload) {
            assertEquals("原文", payload.text); assertEquals(1, payload.width); assertEquals(1, payload.height);
            assertArrayEquals(PNG, Files.readAllBytes(payload.image.toPath()));
            assertTrue(payload.image.getCanonicalPath().contains(File.separator + "drag" + File.separator));
        }
        assertFalse(payload.image.exists());
    }

    @Test public void malformedImagesOversizedTextEmptyContentAndForgedSourcesAreRejected() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertThrows(java.io.IOException.class, () -> DragNativeImport.read(context, "", "com.example.source", null));
        assertThrows(java.io.IOException.class, () -> DragNativeImport.read(context, "text", "bad;source", null));
        assertThrows(java.io.IOException.class, () -> DragNativeImport.read(context,
                "x".repeat(DragPayload.MAX_TEXT + 1), "com.example.source", null));
        File host = File.createTempFile("invalid-", ".png", context.getCacheDir()); Files.write(host.toPath(), new byte[50]);
        try {
            assertThrows(IllegalArgumentException.class, () -> DragNativeImport.read(context, "text", "com.example.source",
                    ParcelFileDescriptor.open(host, ParcelFileDescriptor.MODE_READ_ONLY)));
        } finally { host.delete(); }
    }
}
