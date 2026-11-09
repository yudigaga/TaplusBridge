package io.github.yudigaga.taplusbridge.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses close-aware blocking streams because Robolectric represents PFD pipes as regular files. */
public final class CaptureIoTest {
    private Class<?> io() {
        try { return Class.forName("io.github.yudigaga.taplusbridge.runtime.CaptureIo"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("capture transfer needs a shared total deadline", missing); }
    }
    @Test(timeout = 3000) public void blockedReadIsClosedAtItsTotalDeadline() throws Exception {
        BlockingInput input = new BlockingInput();
        long started = System.nanoTime();
        try {
            io().getMethod("read", InputStream.class, int.class, long.class).invoke(null, input, 8, 80L);
            fail("an abandoned producer must time out");
        } catch (InvocationTargetException expected) { assertTrue(expected.getCause().toString(), expected.getCause() instanceof IOException); }
        assertTrue(input.closed.await(1, TimeUnit.SECONDS));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
    }
    @Test(timeout = 3000) public void abandonedReceiverClosesBlockedWriteAndErasesPixels() throws Exception {
        BlockingOutput output = new BlockingOutput(); byte[] pixels = new byte[1024]; java.util.Arrays.fill(pixels, (byte) 3);
        try {
            io().getMethod("write", OutputStream.class, byte[].class, long.class).invoke(null, output, pixels, 80L);
            fail("an abandoned receiver must time out");
        } catch (InvocationTargetException expected) { assertTrue(expected.getCause().toString(), expected.getCause() instanceof IOException); }
        assertTrue(output.closed.await(1, TimeUnit.SECONDS));
        assertArrayEquals(new byte[pixels.length], pixels);
    }
    @Test(timeout = 3000) public void dripReadCannotRestartTheTotalDeadline() throws Exception {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        InputStream drip = new InputStream() {
            @Override public int read() throws IOException {
                try { Thread.sleep(25); } catch (InterruptedException e) { throw new IOException(e); }
                if (closed.get()) throw new IOException("closed"); return 1;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException { b[off]=(byte)read(); return 1; }
            @Override public void close() { closed.set(true); }
        };
        try {
            io().getMethod("read", InputStream.class, int.class, long.class).invoke(null, drip, 1024, 80L);
            fail("continuous progress must not extend the total budget");
        } catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IOException); }
        assertTrue(closed.get());
    }
    private static final class BlockingInput extends InputStream {
        final CountDownLatch closed = new CountDownLatch(1);
        @Override public int read() throws IOException { await(closed); throw new IOException("closed"); }
        @Override public void close() { closed.countDown(); }
    }
    private static final class BlockingOutput extends OutputStream {
        final CountDownLatch closed = new CountDownLatch(1);
        @Override public void write(int value) throws IOException { await(closed); throw new IOException("closed"); }
        @Override public void close() { closed.countDown(); }
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
}
