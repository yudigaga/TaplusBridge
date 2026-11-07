package io.github.yudigaga.taplusbridge.thaw;

import android.os.Bundle;
import android.os.RemoteException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercises the actual restricted UserService and bounded RPC client without root or a daemon. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class ShizukuThawAndroidTest {
    private static final String ID = "01234567-0123-4567-89ab-0123456789ab";
    private static final String OTHER = "fedcba98-7654-3210-fedc-ba9876543210";
    private static BoundedCommandRunner.Outcome ok() { return new BoundedCommandRunner.Outcome(true, 0, "", ""); }

    @Test public void permittedProfileProducesOnlyFixedEnableAndUnhideCommands() {
        AtomicReference<String> command = new AtomicReference<>();
        PrivilegedThawService service = new PrivilegedThawService((value, timeout) -> {
            command.set(value); assertEquals(10000, timeout); return ok();
        }, () -> 1010000);
        Bundle response = service.thaw("com.example.target", 10, ID);
        assertEquals("pm enable --user 10 com.example.target && pm unhide --user 10 com.example.target", command.get());
        assertTrue(response.getBoolean("completed")); assertEquals(0, response.getInt("exit"));
        assertEquals("", response.getString("failure")); assertFalse(response.containsKey("output"));
    }
    @Test public void malformedAndCrossUserInputsNeverReachThePrivilegedExecutor() {
        AtomicInteger calls = new AtomicInteger();
        PrivilegedThawService service = new PrivilegedThawService((value, timeout) -> { calls.incrementAndGet(); return ok(); }, () -> 10000);
        for (String bad : new String[] {"com.example.x;id", "com.example.x $(id)", "https://example.com", "io.github.yudigaga.taplusbridge", "com.miui.contentextension"})
            assertEquals("invalid_request", service.thaw(bad, 0, ID).getString("failure"));
        assertEquals("invalid_request", service.thaw("com.example.target", 1, ID).getString("failure"));
        assertEquals("invalid_request", service.thaw("com.example.target", 0, "caller text").getString("failure"));
        assertEquals(0, calls.get());
        assertFalse(PrivilegedThawService.allowed("com.example.target", 0, 2000));
        assertFalse(PrivilegedThawService.allowed("com.example.target", 10, 1002000));
    }
    @Test public void eachEphemeralUserServiceAcceptsOnlyOneOperation() {
        AtomicInteger calls = new AtomicInteger();
        PrivilegedThawService service = new PrivilegedThawService((value, timeout) -> { calls.incrementAndGet(); return ok(); }, () -> 10000);
        assertTrue(service.thaw("com.example.target", 0, ID).getBoolean("completed"));
        assertEquals("unavailable", service.thaw("com.example.other", 0, OTHER).getString("failure"));
        assertEquals(1, calls.get());
    }
    @Test public void commandFailureIsReturnedWithoutPretendingTheTargetWasRestored() {
        PrivilegedThawService service = new PrivilegedThawService((value, timeout) ->
                new BoundedCommandRunner.Outcome(true, 1, "vendor private diagnostic", ""), () -> 10000);
        Bundle response = service.thaw("com.example.target", 0, ID);
        assertEquals(1, response.getInt("exit")); assertEquals("denied", response.getString("failure"));
        assertFalse(response.toString().contains("vendor private diagnostic"));
    }
    @Test public void cancelInterruptsOnlyTheCurrentOperationAndDoesNotPoisonBinderThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger caller = new AtomicInteger(10000);
        PrivilegedThawService service = new PrivilegedThawService((value, timeout) -> {
            entered.countDown(); new CountDownLatch(1).await(); return ok();
        }, caller::get);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Bundle> pending = worker.submit(() -> service.thaw("com.example.target", 0, ID));
            assertTrue(entered.await(5, TimeUnit.SECONDS)); service.cancel(OTHER); assertFalse(pending.isDone());
            caller.set(110000); service.cancel(ID); assertFalse(pending.isDone());
            caller.set(10000); service.cancel(ID);
            assertEquals("cancelled", pending.get(5, TimeUnit.SECONDS).getString("failure"));
            assertFalse(worker.submit(() -> Thread.currentThread().isInterrupted()).get(5, TimeUnit.SECONDS));
        } finally { service.stop(); worker.shutdownNow(); }
    }
    @Test public void stoppedServiceNeverStartsANewCommand() {
        PrivilegedThawService service = new PrivilegedThawService((value, timeout) -> { fail("must not execute"); return ok(); }, () -> 10000);
        service.stop(); assertEquals("unavailable", service.thaw("com.example.target", 0, ID).getString("failure"));
    }
    @Test public void boundedClientChecksProtocolAndCancelsInvalidReplies() {
        AtomicInteger cancelled = new AtomicInteger();
        IThawService service = fake(() -> { Bundle result = new Bundle(); result.putInt("protocol", 99); return result; }, cancelled);
        BoundedCommandRunner.Outcome outcome = ShizukuThawClient.call(service, "com.example.target", 0, ID, 5000);
        assertFalse(outcome.successful()); assertTrue(outcome.failure.contains("协议")); assertEquals(1, cancelled.get());
    }
    @Test public void boundedClientHandlesBinderDeathAndNeverFallsBackToRoot() {
        AtomicInteger cancelled = new AtomicInteger();
        BoundedCommandRunner.Outcome outcome = ShizukuThawClient.call(fake(() -> { throw new RemoteException("dead"); }, cancelled),
                "com.example.target", 0, ID, 5000);
        assertFalse(outcome.successful()); assertTrue(outcome.failure.contains("断开")); assertEquals(1, cancelled.get());
    }
    @Test public void boundedClientTimeoutCancelsTheRemoteOperation() {
        AtomicInteger cancelled = new AtomicInteger();
        IThawService service = fake(() -> { new CountDownLatch(1).await(); return PrivilegedThawService.response(true, 0, ""); }, cancelled);
        BoundedCommandRunner.Outcome outcome = ShizukuThawClient.call(service, "com.example.target", 0, ID, 30);
        assertFalse(outcome.successful()); assertTrue(outcome.failure.contains("超时")); assertEquals(1, cancelled.get());
    }
    @Test public void boundedClientAcceptsOnlyExplicitSuccessfulServiceResults() {
        BoundedCommandRunner.Outcome outcome = ShizukuThawClient.call(fake(() -> PrivilegedThawService.response(true, 0, ""), new AtomicInteger()),
                "com.example.target", 0, ID, 5000);
        assertTrue(outcome.successful());
        BoundedCommandRunner.Outcome denied = ShizukuThawClient.call(fake(() -> PrivilegedThawService.response(true, 1, "denied"), new AtomicInteger()),
                "com.example.target", 0, ID, 5000);
        assertFalse(denied.successful());
    }
    @Test public void missingBinderHasSafeReadOnlyStatusAndClearExplicitRequestError() {
        assertFalse(ThawEngine.shizukuGranted()); assertTrue(ThawEngine.shizukuStatus().contains("未运行"));
        try { ThawEngine.requestShizukuPermission(3); fail("not connected"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Shizuku")); }
        Runnable listener = () -> fail("missing binder must not grant permission");
        ShizukuAccess.addPermissionListener(listener); ShizukuAccess.removePermissionListener(listener);
    }
    @FunctionalInterface private interface Reply { Bundle get() throws Exception; }
    private static IThawService fake(Reply reply, AtomicInteger cancelled) {
        return new IThawService.Stub() {
            @Override public Bundle thaw(String pkg, int user, String id) throws RemoteException {
                try { return reply.get(); }
                catch (RemoteException dead) { throw dead; }
                catch (Exception cancelled) { return PrivilegedThawService.response(false, -1, "cancelled"); }
            }
            @Override public void cancel(String id) { cancelled.incrementAndGet(); }
            @Override public void destroy() { }
        };
    }
}
