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
import io.github.yudigaga.taplusbridge.runtime.ModuleProcessLifecycle;
import java.io.IOException;
import java.util.Arrays;

/** The exported bound service accepts only the inspected portal package in the same Android user. */
public final class LensService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean bound;
    private volatile int bindingGeneration;
    private ModuleProcessLifecycle.Lease bindingLease;
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
        @Override public void refine(String requestId, String query, String mode) {
            enforceCaller();
            String[] checked = LensContract.validatedRefinement(requestId, query, mode);
            main.post(() -> LensSession.refine(checked[0], checked[1], checked[2]));
        }
        @Override public void attachBrowser(String requestId, String viewId, IBinder hostToken,
                                           int displayId, int width, int height, ILensSurfaceCallback callback) {
            enforceCaller();
            LensContract.validateBrowser(requestId, viewId, width, height);
            if (hostToken == null || displayId < 0 || callback == null) throw new IllegalArgumentException("missing_lens_surface");
            int expectedBinding = bindingGeneration;
            main.post(() -> {
                LensSession session = bound && expectedBinding == bindingGeneration ? LensSession.current(requestId) : null;
                Bundle result = new Bundle(); result.putString("request_id", requestId); result.putString("view_id", viewId);
                if (session != null) session.attachBrowser(result, viewId, hostToken, displayId, width, height);
                try { callback.onSurface(result); }
                catch (android.os.RemoteException | RuntimeException disconnected) {
                    if (session != null) session.detachBrowser(viewId);
                } finally {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        android.view.SurfaceControlViewHost.SurfacePackage surface = result.getParcelable("surface_package");
                        if (surface != null) surface.release();
                    }
                }
            });
        }
        @Override public void resizeBrowser(String requestId, String viewId, int width, int height) {
            enforceCaller(); LensContract.validateBrowser(requestId, viewId, width, height);
            main.post(() -> {
                LensSession session = LensSession.current(requestId);
                if (session != null) session.resizeBrowser(viewId, width, height);
            });
        }
        @Override public void detachBrowser(String requestId, String viewId) {
            enforceCaller(); LensContract.validateBrowser(requestId, viewId, 1, 1);
            main.post(() -> {
                LensSession session = LensSession.current(requestId);
                if (session != null) session.detachBrowser(viewId);
            });
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

    @Override public IBinder onBind(Intent intent) {
        if (bindingLease == null) bindingLease = ModuleProcessLifecycle.acquire();
        bound = true; bindingGeneration++; return binder;
    }
    @Override public boolean onUnbind(Intent intent) {
        bound = false; bindingGeneration++;
        try { LensSession.cancelAll(); }
        finally { releaseBinding(); }
        return false;
    }
    @Override public void onDestroy() {
        bound = false; bindingGeneration++;
        try { LensSession.cancelAll(); }
        finally { releaseBinding(); }
        super.onDestroy();
    }
    private void releaseBinding() {
        if (bindingLease != null) { bindingLease.close(); bindingLease = null; }
    }
    static void close(ParcelFileDescriptor file) {
        if (file != null) try { file.close(); } catch (IOException ignored) { }
    }
}
