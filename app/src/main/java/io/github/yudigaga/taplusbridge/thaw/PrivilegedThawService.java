package io.github.yudigaga.taplusbridge.thaw;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/** Shizuku UserService: a shell/root process, never a normal Android Service. */
public final class PrivilegedThawService extends IThawService.Stub {
    static final int PROTOCOL = 1;
    static final long COMMAND_TIMEOUT_MILLIS = 10_000L;
    @FunctionalInterface interface Executor { BoundedCommandRunner.Outcome run(String command, long timeout) throws InterruptedException; }
    private final Executor executor;
    private final IntSupplier caller;
    private final AtomicBoolean used = new AtomicBoolean();
    private volatile String activeRequest;
    private volatile Thread activeThread;
    private volatile int activeCaller = -1;
    private volatile boolean stopped;

    public PrivilegedThawService() {
        this((command, timeout) -> BoundedCommandRunner.run(new ProcessBuilder("/system/bin/sh", "-c", command), timeout), Binder::getCallingUid);
    }
    public PrivilegedThawService(Context ignored) { this(); }
    PrivilegedThawService(Executor executor, IntSupplier caller) { this.executor = executor; this.caller = caller; }

    static boolean validRequestId(String value) { return value != null && value.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"); }
    static boolean allowed(String pkg, int user, int uid) {
        return RootCommandPolicy.validPackage(pkg) && user >= 0 && uid >= 0 && uid % 100000 >= 10000
                && uid / 100000 == user && !"io.github.yudigaga.taplusbridge".equals(pkg)
                && !"com.miui.contentextension".equals(pkg);
    }
    @Override public Bundle thaw(String pkg, int user, String id) {
        int callerUid = caller.getAsInt();
        if (!validRequestId(id) || !allowed(pkg, user, callerUid)) return response(false, -1, "invalid_request");
        if (stopped || !used.compareAndSet(false, true)) return response(false, -1, "unavailable");
        activeRequest = id; activeCaller = callerUid; activeThread = Thread.currentThread();
        try {
            if (stopped || Thread.currentThread().isInterrupted()) return response(false, -1, "cancelled");
            BoundedCommandRunner.Outcome result = executor.run(RootCommandPolicy.enableCommand(pkg, user), COMMAND_TIMEOUT_MILLIS);
            return response(result.completed, result.exitCode, result.successful() ? "" : result.completed ? "denied" : "incomplete");
        } catch (InterruptedException cancelled) {
            return response(false, -1, "cancelled");
        } catch (RuntimeException unavailable) {
            return response(false, -1, "unavailable");
        } finally {
            activeThread = null; activeRequest = null; activeCaller = -1;
            // A Binder pool thread must not carry a cancelled operation's interrupt into its next call.
            Thread.interrupted();
        }
    }
    @Override public void cancel(String id) {
        if (!validRequestId(id) || !id.equals(activeRequest) || caller.getAsInt() != activeCaller) return;
        Thread thread = activeThread; if (thread != null) thread.interrupt();
    }
    void stop() {
        stopped = true; Thread thread = activeThread; if (thread != null) thread.interrupt();
        if (thread != null && thread != Thread.currentThread()) try { thread.join(650L); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
    }
    @Override public void destroy() { stop(); System.exit(0); }
    static Bundle response(boolean completed, int exit, String failure) {
        Bundle data = new Bundle(); data.putInt("protocol", PROTOCOL); data.putBoolean("completed", completed);
        data.putInt("exit", exit); data.putString("failure", failure); return data;
    }
}
