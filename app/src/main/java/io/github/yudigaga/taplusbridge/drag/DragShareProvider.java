package io.github.yudigaga.taplusbridge.drag;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Short-lived read-only image grants survive the drag Activity that started sharing. */
public final class DragShareProvider extends ContentProvider {
    public static final String AUTHORITY = BridgeContract.APP_PACKAGE + ".drag.images";
    static final long TTL = 10 * 60_000;
    private static final LinkedHashMap<String, Entry> files = new LinkedHashMap<>();
    private record Entry(File file, String mime, long issued) { }

    public static synchronized Uri expose(Context context, DragPayload payload) throws IOException {
        cleanup();
        if (payload == null || payload.image == null || !owned(context, payload.image) || !payload.image.isFile()
                || payload.image.length() > DragPayload.MAX_BYTES || !"image/png".equals(payload.mime))
            throw new IOException("invalid_drag_image");
        while (files.size() >= 8) { String oldest = files.keySet().iterator().next(); revoke(oldest); }
        String token = UUID.randomUUID().toString();
        files.put(token, new Entry(payload.image, payload.mime, SystemClock.elapsedRealtime()));
        new Handler(Looper.getMainLooper()).postDelayed(() -> revoke(token), TTL);
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).appendPath("image.png").build();
    }

    private static synchronized void revoke(String token) {
        Entry removed = files.remove(token); if (removed != null) removed.file.delete();
    }

    private static void cleanup() {
        long now = SystemClock.elapsedRealtime();
        var iterator = files.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (now < entry.issued || now - entry.issued >= TTL) { entry.file.delete(); iterator.remove(); }
        }
    }

    private static boolean owned(Context context, File file) throws IOException {
        return file.getCanonicalFile().getParentFile().equals(new File(context.getCacheDir(), "drag").getCanonicalFile())
                && file.getName().matches("image-[A-Za-z0-9_-]+\\.png");
    }

    private synchronized Entry resolve(Uri uri) throws FileNotFoundException {
        synchronized (DragShareProvider.class) {
            cleanup();
            if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
                    || uri.getPathSegments().size() != 2 || !"image.png".equals(uri.getLastPathSegment())
                    || uri.getQuery() != null || uri.getFragment() != null) throw new FileNotFoundException("invalid_drag_uri");
            Entry entry = files.get(uri.getPathSegments().get(0));
            try {
                if (entry == null || !owned(getContext(), entry.file) || !entry.file.isFile())
                    throw new FileNotFoundException("expired_drag_image");
            } catch (IOException unavailable) { throw new FileNotFoundException("expired_drag_image"); }
            return entry;
        }
    }

    @Override public boolean onCreate() {
        DragPayload.deleteExpired(new File(getContext().getCacheDir(), "drag")); return true;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read_only");
        return ParcelFileDescriptor.open(resolve(uri).file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { try { return resolve(uri).mime; } catch (FileNotFoundException expired) { return null; } }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            Entry entry = resolve(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(columns); Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "image.png";
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = entry.file.length();
            }
            result.addRow(row); return result;
        } catch (FileNotFoundException expired) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read_only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read_only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read_only"); }
}
