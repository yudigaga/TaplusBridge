package io.github.yudigaga.taplusbridge.lens;

import android.content.Context;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LensSessionLifecycleAndroidTest {
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void cancelledPreparationResumingAfterConfigCallCannotDeleteNewSessionImage() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        BlockingConfigProvider provider = Robolectric.buildContentProvider(BlockingConfigProvider.class)
                .create(LensContract.PACKAGE + ".bridge").get();
        ShadowContentResolver.registerProviderInternal(LensContract.PACKAGE + ".bridge", provider);
        File source = File.createTempFile("lens-lifecycle-source-", ".png", context.getCacheDir());
        byte[] expected = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        Files.write(source.toPath(), expected);
        // Native graphics loads its runtime lazily; initialize it before timing the actual worker handoff.
        android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(expected, 0, expected.length);
        assertNotNull(decoded); assertEquals(1, decoded.getWidth()); decoded.recycle();
        Bundle newerRequest = request(); newerRequest.putString("request_id", "newer-session");
        newerRequest.putString("image_key", LensContract.hex(MessageDigest.getInstance("SHA-256").digest(expected)));
        LensSession old = session(false);
        LensSession newer = session(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY), newerRequest, false);
        try {
            worker(old).submit(() -> { invoke(old, "prepareImage"); return null; });
            assertTrue("The old task must be suspended in the synchronous config IPC", provider.entered.await(2, TimeUnit.SECONDS));
            invoke(old, "complete");
            worker(newer).submit(() -> { invoke(newer, "prepareImage"); return null; }).get(10, TimeUnit.SECONDS);
            File directory = new File(context.getCacheDir(), "lens");
            File[] prepared = directory.listFiles(file -> file.getName().startsWith("selected-"));
            assertNotNull(prepared); assertEquals("The newer real preparation must retain its verified image", 1, prepared.length);
            File newerImage = prepared[0];
            assertArrayEquals(expected, Files.readAllBytes(newerImage.toPath()));
            provider.resume.countDown();
            assertTrue(worker(old).awaitTermination(2, TimeUnit.SECONDS));
            assertTrue("A cancelled old task returning from IPC must not remove the newer session's image", newerImage.isFile());
            assertArrayEquals(expected, Files.readAllBytes(newerImage.toPath()));
            assertEquals("The cancelled worker must not retain its own partial file", 1, directory.listFiles().length);
        } finally {
            provider.resume.countDown(); invoke(old, "complete"); invoke(newer, "complete");
            worker(old).awaitTermination(2, TimeUnit.SECONDS);
            worker(newer).awaitTermination(2, TimeUnit.SECONDS);
            ShadowLooper.shadowMainLooper().idle(); source.delete();
        }
        assertTrue("Both session workers must terminate after cleanup", worker(old).isTerminated() && worker(newer).isTerminated());
    }

    @Test public void closingSessionInterruptsItsWorkerAndDiscardsQueuedImageWork() throws Exception {
        LensSession session = session(false);
        ExecutorService worker = worker(session);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        worker.execute(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            finally { exited.countDown(); }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            worker.execute(() -> queuedRan.set(true));
            invoke(session, "complete");
            assertTrue("Closing a session must shut down its image/upload executor", worker.isShutdown());
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse("A closed session must never start queued image or upload work", queuedRan.get());
        } finally { release.countDown(); invoke(session, "complete"); }
    }

    @Test public void disconnectedHostReleasesTheSessionWorkerWithoutWaitingForTheServiceToUnbind() throws Exception {
        LensSession session = session(true);
        ExecutorService worker = worker(session);
        try {
            Method send = LensSession.class.getDeclaredMethod("send", String.class, String.class);
            send.setAccessible(true); send.invoke(session, "loading", "loading");
            assertTrue("A dead host callback must release the session's worker", worker.isShutdown());
        } finally { invoke(session, "complete"); }
    }

    private static LensSession session(boolean disconnected) throws Exception {
        return session(null, request(), disconnected);
    }
    private static Bundle request() {
        Bundle request = new Bundle(); request.putString("request_id", "lifecycle-request");
        request.putString("panel_session_id", "lifecycle-panel");
        request.putString("image_key", "1".repeat(64)); request.putString("mime_type", "image/png");
        return request;
    }
    private static LensSession session(ParcelFileDescriptor image, Bundle request, boolean disconnected) throws Exception {
        Constructor<LensSession> constructor = LensSession.class.getDeclaredConstructor(
                Context.class, ParcelFileDescriptor.class, Bundle.class, ILensCallback.class);
        constructor.setAccessible(true);
        return constructor.newInstance(RuntimeEnvironment.getApplication(), image, request, new ILensCallback.Stub() {
            @Override public void onUpdate(Bundle update) { if (disconnected) throw new IllegalStateException("host stopped"); }
        });
    }
    private static ExecutorService worker(LensSession session) throws Exception {
        for (Field field : LensSession.class.getDeclaredFields()) {
            if (!ExecutorService.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true); return (ExecutorService) field.get(session);
        }
        throw new AssertionError("Session image/upload worker was not found");
    }
    private static void invoke(LensSession session, String name) throws Exception {
        Method method = LensSession.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(session);
    }

    public static final class BlockingConfigProvider extends ContentProvider {
        final CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            assertEquals("lens_config", method);
            if (calls.getAndIncrement() == 0) {
                entered.countDown(); boolean interrupted = false;
                for (;;) {
                    try { resume.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            Bundle enabled = new Bundle(); enabled.putBoolean("enabled", true); return enabled;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
