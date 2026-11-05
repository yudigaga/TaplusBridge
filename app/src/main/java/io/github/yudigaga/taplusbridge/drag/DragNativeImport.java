package io.github.yudigaga.taplusbridge.drag;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.hook.ScopeCapturePayload;
import io.github.yudigaga.taplusbridge.runtime.RuntimeClient;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;

/** A host-owned native pick still needs a one-use physical gesture lease before import. */
public final class DragNativeImport {
    private static final Set<String> KEYS = Set.of("lease", "source", "direction", "text", "fd");
    private DragNativeImport() { }

    public static Bundle receive(Context context, Bundle request) {
        return receive(context, request, (method, data) -> RuntimeClient.call(context, method, data));
    }

    static Bundle receive(Context context, Bundle request, java.util.function.BiFunction<String, Bundle, Bundle> runtime) {
        DragPayload payload = null;
        ParcelFileDescriptor incoming = request != null && request.get("fd") instanceof ParcelFileDescriptor fd ? fd : null;
        try {
            ConfigStore config = new ConfigStore(context);
            if (!config.enabled() || !config.getDragSettings().enabled) return declined("disabled");
            if (request == null || !KEYS.containsAll(request.keySet())
                    || !(request.get("lease") instanceof String lease) || !lease.matches("[A-Za-z0-9_-]{43}")
                    || !(request.get("source") instanceof String source) || !HostPolicy.validTarget(source)
                    || !config.sourceScope().allows(source) || !(request.get("direction") instanceof String direction)
                    || !DragSettings.DIRECTIONS.contains(direction) || !(request.get("text") instanceof String text)
                    || text.length() > DragPayload.MAX_TEXT
                    || (request.containsKey("fd") && !(request.get("fd") instanceof ParcelFileDescriptor))) return declined("invalid_request");
            if (runtime.apply("status", Bundle.EMPTY) == null) return declined("runtime_unavailable");
            payload = read(context, text, source, incoming);
            Bundle proof = new Bundle(); proof.putString("token", lease); proof.putString("source", source);
            proof.putString("direction", direction);
            Bundle admitted = runtime.apply("drag_gesture_consume", proof);
            if (admitted == null) return declined("runtime_unavailable");
            String action = admitted.getString("action");
            ConfigStore latest = new ConfigStore(context);
            if (!admitted.getBoolean("accepted") || !latest.enabled() || !latest.getDragSettings().enabled
                    || !latest.sourceScope().allows(source) || "none".equals(action)
                    || !latest.getDragSettings().action(direction).equals(action)) return declined("gesture_expired");
            String token = DragActionStore.issue(context, action, payload); payload = null;
            Bundle result = new Bundle(); result.putString("token", token); return result;
        } catch (IOException | RuntimeException unavailable) { return declined("invalid_payload"); }
        finally {
            if (payload != null) payload.close();
            if (incoming != null) try { incoming.close(); } catch (IOException ignored) { }
        }
    }

    static DragPayload read(Context context, String text, String source, ParcelFileDescriptor descriptor) throws IOException {
        if (!HostPolicy.validTarget(source) || text == null || text.length() > DragPayload.MAX_TEXT)
            throw new IOException("drag_native_text");
        if (descriptor == null) {
            if (text.isBlank()) throw new IOException("drag_native_empty");
            return new DragPayload(text, source, null, "text/plain", 0, 0, "");
        }
        File file = null;
        try (descriptor; var stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            long size = descriptor.getStatSize();
            if (size <= 0 || size > DragPayload.MAX_BYTES) throw new IOException("drag_native_size");
            byte[] bytes = ScopeCapturePayload.read(stream, (int) size);
            var bitmap = ScopeCapturePayload.decode(bytes);
            int width, height;
            try { width = bitmap.getWidth(); height = bitmap.getHeight(); }
            finally { bitmap.recycle(); }
            File directory = new File(context.getCacheDir(), "drag");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("drag_native_cache");
            DragPayload.deleteExpired(directory);
            file = File.createTempFile("image-", ".png", directory);
            try (var output = new FileOutputStream(file)) { output.write(bytes); }
            DragPayload payload = new DragPayload(text, source, file, "image/png", width, height, "");
            file = null; return payload;
        } finally { if (file != null) file.delete(); }
    }

    private static Bundle declined(String reason) { Bundle result = new Bundle(); result.putString("reason", reason); return result; }
}
