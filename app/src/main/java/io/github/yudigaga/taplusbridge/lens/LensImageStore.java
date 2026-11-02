package io.github.yudigaga.taplusbridge.lens;

import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

/** The descriptor is consumed and closed on every path; incomplete images are never retained. */
final class LensImageStore {
    private LensImageStore() { }
    static BitmapFactory.Options copyVerified(ParcelFileDescriptor descriptor, File target, Bundle request,
                                              BooleanSupplier cancelled) throws IOException {
        boolean valid = false;
        try {
            // The host supplies an owned regular file; reject streams which could block the serial worker forever.
            long size = descriptor.getStatSize();
            if (size <= 0 || size > LensContract.MAX_IMAGE_BYTES) throw new IOException("invalid_image_size");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768]; int read, total = 0;
                while ((read = input.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean()) throw new IOException("cancelled");
                    total += read;
                    if (total > LensContract.MAX_IMAGE_BYTES) throw new IOException("image_too_large");
                    digest.update(buffer, 0, read); output.write(buffer, 0, read);
                }
                if (total == 0) throw new IOException("empty_image");
            }
            if (cancelled.getAsBoolean()) throw new IOException("cancelled");
            if (!LensContract.hex(digest.digest()).equals(request.getString("image_key"))) throw new IOException("image_changed");
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(target.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8192 || bounds.outHeight > 8192
                    || !request.getString("mime_type").equals(bounds.outMimeType)) throw new IOException("invalid_image");
            valid = true; return bounds;
        } catch (NoSuchAlgorithmException unavailable) { throw new IOException("sha256_unavailable", unavailable); }
        finally { LensService.close(descriptor); if (!valid) target.delete(); }
    }
}
