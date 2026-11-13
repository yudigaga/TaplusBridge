package io.github.yudigaga.taplusbridge.drag;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.runtime.ModuleProcessLifecycle;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONObject;

/** Private, bounded image capabilities survive an idle module process being recreated. */
public final class DragShareProvider extends ContentProvider {
    public static final String AUTHORITY = BridgeContract.APP_PACKAGE + ".drag.images";
    static final long TTL = 10 * 60_000;
    private static final String PREFS = "drag_share_images";
    private record Entry(File file, long wall, long elapsed, long order) { }

    public static synchronized Uri expose(Context context, DragPayload payload) throws IOException {
        try (var work = ModuleProcessLifecycle.acquire()) {
            var files = cleanup(context);
            if (payload == null || payload.image == null || !owned(context, payload.image) || !payload.image.isFile()
                    || payload.image.length() <= 0 || payload.image.length() > DragPayload.MAX_BYTES || !"image/png".equals(payload.mime))
                throw new IOException("invalid_drag_image");
            long order = files.values().stream().mapToLong(Entry::order).max().orElse(0) + 1;
            if (order <= 0) throw new IOException("invalid_drag_image_store");
            var edit = prefs(context).edit(); var removed = new ArrayList<Entry>();
            while (files.size() >= 8) {
                String oldest = files.entrySet().stream().min(java.util.Comparator.comparingLong(item -> item.getValue().order))
                        .orElseThrow().getKey();
                removed.add(files.remove(oldest)); edit.remove(oldest);
            }
            String token = UUID.randomUUID().toString();
            long wall = System.currentTimeMillis();
            // Cache cleanup must use the same start time as this share grant.
            if (!payload.image.setLastModified(wall)) throw new IOException("unavailable_drag_image_store");
            try {
                JSONObject data = new JSONObject().put("file", payload.image.getName()).put("mime", "image/png")
                        .put("wall", wall).put("elapsed", SystemClock.elapsedRealtime()).put("order", order);
                if (!edit.putString(token, data.toString()).commit()) throw new IOException("unavailable_drag_image_store");
            } catch (org.json.JSONException invalid) { throw new IOException("invalid_drag_image_store", invalid); }
            for (Entry previous : removed) if (!previous.file.equals(payload.image)) previous.file.delete();
            return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).appendPath("image.png").build();
        }
    }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static LinkedHashMap<String, Entry> cleanup(Context context) {
        long elapsed = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
        var files = new LinkedHashMap<String, Entry>(); var edit = prefs(context).edit(); boolean changed = false;
        for (var item : prefs(context).getAll().entrySet()) {
            Entry entry = decode(context, item.getKey(), item.getValue());
            if (entry != null && elapsed >= entry.elapsed && elapsed - entry.elapsed < TTL
                    && wall >= entry.wall && wall - entry.wall < TTL) files.put(item.getKey(), entry);
            else {
                edit.remove(item.getKey()); changed = true;
                if (entry != null) entry.file.delete();
            }
        }
        if (changed) edit.commit();
        return files;
    }
    private static Entry decode(Context context, String token, Object value) {
        try {
            if (!token.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    || !(value instanceof String raw) || raw.length() > 1024) return null;
            JSONObject data = new JSONObject(raw);
            String name = data.getString("file");
            if (!name.matches("image-[A-Za-z0-9_-]+\\.png") || !"image/png".equals(data.getString("mime"))) return null;
            File file = new File(new File(context.getCacheDir().getCanonicalFile(), "drag"), name);
            long wall = data.getLong("wall"), elapsed = data.getLong("elapsed"), order = data.getLong("order");
            if (wall < 0 || elapsed < 0 || order <= 0 || !owned(context, file) || !file.isFile()
                    || file.length() <= 0 || file.length() > DragPayload.MAX_BYTES) return null;
            return new Entry(file, wall, elapsed, order);
        } catch (IOException | org.json.JSONException | RuntimeException invalid) { return null; }
    }

    private static boolean owned(Context context, File file) throws IOException {
        File directory = new File(context.getCacheDir().getCanonicalFile(), "drag");
        return directory.equals(directory.getCanonicalFile())
                && file.getName().matches("image-[A-Za-z0-9_-]+\\.png")
                && file.getAbsoluteFile().getParentFile().getCanonicalFile().equals(directory)
                && file.getCanonicalFile().equals(new File(directory, file.getName()));
    }

    private Entry resolve(Uri uri) throws FileNotFoundException {
        synchronized (DragShareProvider.class) {
            if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
                    || uri.getPathSegments().size() != 2 || !"image.png".equals(uri.getLastPathSegment())
                    || uri.getQuery() != null || uri.getFragment() != null) throw new FileNotFoundException("invalid_drag_uri");
            Entry entry = cleanup(getContext()).get(uri.getPathSegments().get(0));
            try {
                if (entry == null || !owned(getContext(), entry.file) || !entry.file.isFile())
                    throw new FileNotFoundException("expired_drag_image");
            } catch (IOException unavailable) { throw new FileNotFoundException("expired_drag_image"); }
            return entry;
        }
    }

    @Override public boolean onCreate() {
        synchronized (DragShareProvider.class) { cleanup(getContext()); }
        ModuleProcessLifecycle.touch(); return true;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try (var work = ModuleProcessLifecycle.acquire()) {
            if (!"r".equals(mode)) throw new FileNotFoundException("read_only");
            synchronized (DragShareProvider.class) {
                return ParcelFileDescriptor.open(resolve(uri).file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        }
    }
    @Override public String getType(Uri uri) {
        try (var work = ModuleProcessLifecycle.acquire()) { resolve(uri); return "image/png"; }
        catch (FileNotFoundException expired) { return null; }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try (var work = ModuleProcessLifecycle.acquire()) {
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
