package io.github.yudigaga.taplusbridge.runtime;
import android.os.Bundle;
/** Every transaction authenticates the real Binder caller; the handle itself is not authority. */
interface IRuntimeBridge {
    Bundle call(String method, in Bundle request);
}
