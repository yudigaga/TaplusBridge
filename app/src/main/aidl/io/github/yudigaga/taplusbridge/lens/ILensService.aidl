package io.github.yudigaga.taplusbridge.lens;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import io.github.yudigaga.taplusbridge.lens.ILensCallback;
import io.github.yudigaga.taplusbridge.lens.ILensSurfaceCallback;
interface ILensService {
    void search(in ParcelFileDescriptor image, in Bundle request, ILensCallback callback);
    void refine(String requestId, String query, String mode);
    void attachBrowser(String requestId, String viewId, IBinder hostToken, int displayId, int width, int height, ILensSurfaceCallback callback);
    void resizeBrowser(String requestId, String viewId, int width, int height);
    void detachBrowser(String requestId, String viewId);
    void cancel(String requestId);
}
