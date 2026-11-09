package io.github.yudigaga.taplusbridge.runtime;

import io.github.yudigaga.taplusbridge.hook.ScopeCapturePayload;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A total transfer deadline also closes the stream, waking Android's blocked file I/O. */
public final class CaptureIo {
    public static final long TIMEOUT_MILLIS = 5_000;
    private static final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "TaplusBridge-capture-deadline"); thread.setDaemon(true); return thread;
    });
    static { deadlines.setRemoveOnCancelPolicy(true); }
    private CaptureIo() {}

    /** The sender's read descriptor must survive Binder marshalling, even when its writer finishes first. */
    public static void expireReadEnd(Closeable readEnd) {
        deadlines.schedule(() -> close(readEnd), TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    public static byte[] read(InputStream input, int expected, long timeoutMillis) throws IOException {
        AtomicBoolean expired = new AtomicBoolean();
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        ScheduledFuture<?> deadline = deadlines.schedule(() -> { expired.set(true); close(input); }, timeoutMillis, TimeUnit.MILLISECONDS);
        try (input) {
            byte[] data = ScopeCapturePayload.read(input, expected);
            if (expired.get() || System.nanoTime() >= until) throw new IOException("scope_capture_timeout");
            return data;
        } finally { deadline.cancel(false); }
    }

    public static void write(OutputStream output, byte[] pixels, long timeoutMillis) throws IOException {
        AtomicBoolean expired = new AtomicBoolean();
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        ScheduledFuture<?> deadline = deadlines.schedule(() -> { expired.set(true); close(output); }, timeoutMillis, TimeUnit.MILLISECONDS);
        try (output) {
            output.write(pixels);
            if (expired.get() || System.nanoTime() >= until) throw new IOException("scope_capture_timeout");
        } finally { deadline.cancel(false); Arrays.fill(pixels, (byte) 0); }
    }

    /** Queued writes own only their output endpoint; cancellation drops their pixel reference immediately. */
    public static final class Write implements Runnable, Closeable {
        private final OutputStream output;
        private final ScheduledFuture<?> deadline;
        private byte[] pixels;
        public Write(OutputStream output, byte[] pixels) {
            this.output = output; this.pixels = pixels;
            deadline = deadlines.schedule(this::close, TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
        @Override public void run() {
            byte[] data;
            synchronized (this) { data = pixels; }
            if (data == null) return;
            try { write(output, data, TIMEOUT_MILLIS); }
            catch (IOException ignored) { }
            finally { close(); }
        }
        @Override public void close() {
            synchronized (this) {
                if (pixels != null) Arrays.fill(pixels, (byte) 0);
                pixels = null;
            }
            CaptureIo.close(output); deadline.cancel(false);
        }
    }

    private static void close(Closeable stream) { try { stream.close(); } catch (IOException ignored) { } }
}
