package io.github.yudigaga.taplusbridge.lens;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LensSessionLifecycleAndroidTest {
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
        Bundle request = new Bundle(); request.putString("request_id", "lifecycle-request");
        request.putString("panel_session_id", "lifecycle-panel");
        request.putString("image_key", "1".repeat(64)); request.putString("mime_type", "image/png");
        Constructor<LensSession> constructor = LensSession.class.getDeclaredConstructor(
                Context.class, ParcelFileDescriptor.class, Bundle.class, ILensCallback.class);
        constructor.setAccessible(true);
        return constructor.newInstance(RuntimeEnvironment.getApplication(), null, request, new ILensCallback.Stub() {
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
}
