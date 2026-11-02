package io.github.yudigaga.taplusbridge.lens;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.io.IOException;
import java.util.Arrays;

/** The exported bound service accepts only the inspected portal package in the same Android user. */
public final class LensService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean bound;
    private volatile int bindingGeneration;
    private final ILensService.Stub binder = new ILensService.Stub() {
        @Override public void search(ParcelFileDescriptor image, Bundle request, ILensCallback callback) {
            try {
                enforceCaller();
                Bundle checked = LensContract.validatedRequest(request);
                if (image == null || callback == null) throw new IllegalArgumentException("missing_image_or_callback");
                int expectedBinding = bindingGeneration;
                main.post(() -> {
                    if (!bound || expectedBinding != bindingGeneration) { close(image); return; }
                    LensSession.start(LensService.this, image, checked, callback);
                });
            } catch (RuntimeException rejected) {
                close(image);
                throw rejected;
            }
        }
        @Override public void cancel(String requestId) {
            enforceCaller();
            if (requestId != null && requestId.length() <= 128)
                main.post(() -> LensSession.cancel(requestId));
        }
    };

    private void enforceCaller() {
        int caller = Binder.getCallingUid();
        if (caller == Process.myUid()) return;
        if (caller / 100000 != Process.myUid() / 100000) throw new SecurityException("lens_wrong_user");
        String[] packages = getPackageManager().getPackagesForUid(caller);
        if (packages == null || !Arrays.asList(packages).contains(LensContract.HOST))
            throw new SecurityException("lens_caller_rejected");
        try {
            PackageInfo host = getPackageManager().getPackageInfo(LensContract.HOST, 0);
            if (!HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode()))
                throw new SecurityException("lens_unsupported_host");
        } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
            throw new SecurityException("lens_host_missing");
        }
    }

    @Override public IBinder onBind(Intent intent) { bound = true; bindingGeneration++; return binder; }
    @Override public boolean onUnbind(Intent intent) {
        bound = false; bindingGeneration++;
        LensSession.cancelAll();
        return false;
    }
    @Override public void onDestroy() {
        bound = false; bindingGeneration++;
        LensSession.cancelAll();
        super.onDestroy();
    }
    static void close(ParcelFileDescriptor file) {
        if (file != null) try { file.close(); } catch (IOException ignored) { }
    }
}
