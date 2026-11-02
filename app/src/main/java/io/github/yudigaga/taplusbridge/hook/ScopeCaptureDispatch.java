package io.github.yudigaga.taplusbridge.hook;

import android.os.Looper;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Native content callbacks have a worker-thread contract; the host schedules its own UI work. */
public final class ScopeCaptureDispatch {
    private final Executor worker;
    public ScopeCaptureDispatch(Executor worker) { this.worker = Objects.requireNonNull(worker); }
    public void submit(Runnable callback) {
        if (Looper.myLooper() == Looper.getMainLooper()) worker.execute(callback);
        else callback.run();
    }
}
