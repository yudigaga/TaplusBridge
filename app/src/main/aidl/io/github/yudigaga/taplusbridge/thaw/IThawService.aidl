package io.github.yudigaga.taplusbridge.thaw;
import android.os.Bundle;

/** Only fixed package enabling operations. No caller-provided shell or URI is accepted. */
interface IThawService {
    Bundle thaw(String packageName, int userId, String requestId) = 0;
    oneway void cancel(String requestId) = 1;
    void destroy() = 16777114;
}
