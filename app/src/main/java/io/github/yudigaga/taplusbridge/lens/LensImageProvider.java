package io.github.yudigaga.taplusbridge.lens;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** A read-only URI for the current task, consumed by WebView's file chooser. */
public final class LensImageProvider extends ContentProvider {
    private static File current;
    private static String token;
    private static String type;

    static synchronized Uri expose(File file, String mime) {
        current = file;
        type = mime;
        token = java.util.UUID.randomUUID().toString();
        return new Uri.Builder().scheme("content").authority(LensContract.PACKAGE + ".lens.images")
                .appendPath(token).appendPath(file.getName()).build();
    }
    static synchronized void revoke(File file) {
        if (file != null && file.equals(current)) { current = null; token = null; type = null; }
    }
    private static synchronized File resolve(Uri uri) throws FileNotFoundException {
        if (current == null || !"content".equals(uri.getScheme())
                || !(LensContract.PACKAGE + ".lens.images").equals(uri.getAuthority()) || uri.getPathSegments().size() != 2
                || !token.equals(uri.getPathSegments().get(0))
                || !current.getName().equals(uri.getLastPathSegment())) throw new FileNotFoundException("expired_lens_image");
        return current;
    }
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read_only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) {
        try { resolve(uri); return type; } catch (FileNotFoundException expired) { return null; }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            result.addRow(row); return result;
        } catch (FileNotFoundException expired) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read_only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read_only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read_only"); }
}
