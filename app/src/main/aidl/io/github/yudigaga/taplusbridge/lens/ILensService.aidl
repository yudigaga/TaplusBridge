package io.github.yudigaga.taplusbridge.lens;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import io.github.yudigaga.taplusbridge.lens.ILensCallback;
interface ILensService {
    void search(in ParcelFileDescriptor image, in Bundle request, ILensCallback callback);
    void cancel(String requestId);
}
