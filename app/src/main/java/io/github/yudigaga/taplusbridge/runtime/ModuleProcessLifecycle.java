package io.github.yudigaga.taplusbridge.runtime;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Retires only this APK's idle processes; injected host/framework code never installs it. */
public final class ModuleProcessLifecycle implements Application.ActivityLifecycleCallbacks {
    private static final long IDLE_MILLIS = 3000;
    private static final Lease NO_WORK = () -> {};
    private static volatile ModuleProcessLifecycle current;
    private final Handler main;
    private final Runnable exit;
    private final Set<Activity> activities = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Runnable idle = this::exitIfIdle;
    private int leases;
    private long idleAt;
    private boolean stopped;

    public interface Lease extends AutoCloseable { @Override void close(); }

    private ModuleProcessLifecycle(Handler main, Runnable exit) {
        this.main = main; this.exit = exit;
    }

    static boolean eligible(String packageName, String processName, int applicationUid, int processUid) {
        return BridgeContract.APP_PACKAGE.equals(packageName)
                && (packageName.equals(processName) || (packageName + ":lens").equals(processName))
                && applicationUid == processUid && processUid % 100000 >= 10000;
    }

    public static synchronized void install(Application app) {
        if (current != null || !eligible(app.getPackageName(), Application.getProcessName(),
                app.getApplicationInfo().uid, Process.myUid())) return;
        ModuleProcessLifecycle owner = new ModuleProcessLifecycle(new Handler(Looper.getMainLooper()),
                () -> {
                    android.util.Log.i("TaplusBridge", "module process idle; exiting process=" + Application.getProcessName());
                    Process.killProcess(Process.myPid());
                });
        current = owner;
        app.registerActivityLifecycleCallbacks(owner);
        owner.scheduleIdle();
    }

    /** Holds the process until the task explicitly finishes; do not use for idle cached data. */
    public static Lease acquire() { return acquireInternal(0); }

    /** A bounded handoff also releases itself if its consumer never arrives. */
    public static Lease acquire(long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("positive_lease_timeout_required");
        return acquireInternal(timeoutMillis);
    }

    private static Lease acquireInternal(long timeoutMillis) {
        ModuleProcessLifecycle owner = current;
        return owner == null ? NO_WORK : owner.hold(timeoutMillis);
    }

    public static void touch() {
        ModuleProcessLifecycle owner = current;
        if (owner != null) owner.scheduleIdle();
    }

    private synchronized Lease hold(long timeoutMillis) {
        if (stopped) return NO_WORK;
        leases++;
        main.removeCallbacks(idle);
        Held held = new Held(this);
        if (timeoutMillis > 0)
            main.postAtTime(held.expire, this, SystemClock.uptimeMillis() + timeoutMillis);
        return held;
    }

    private synchronized void release() {
        if (stopped) return;
        if (leases > 0) leases--;
        scheduleIdle();
    }

    private synchronized void scheduleIdle() {
        main.removeCallbacks(idle);
        if (stopped || leases != 0 || !activities.isEmpty()) return;
        idleAt = SystemClock.uptimeMillis() + IDLE_MILLIS;
        main.postAtTime(idle, this, idleAt);
    }

    private synchronized void exitIfIdle() {
        if (stopped || leases != 0 || !activities.isEmpty() || SystemClock.uptimeMillis() < idleAt) return;
        stopped = true;
        main.removeCallbacksAndMessages(this);
        exit.run();
    }

    private synchronized void show(Activity activity) {
        if (stopped) return;
        activities.add(activity);
        main.removeCallbacks(idle);
    }
    private synchronized void hide(Activity activity) {
        activities.remove(activity);
        scheduleIdle();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { show(activity); }
    @Override public void onActivityStarted(Activity activity) { show(activity); }
    @Override public void onActivityResumed(Activity activity) { show(activity); }
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) { hide(activity); }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) { hide(activity); }

    private static final class Held implements Lease {
        private ModuleProcessLifecycle owner;
        private final Runnable expire = this::close;
        Held(ModuleProcessLifecycle owner) { this.owner = owner; }
        @Override public synchronized void close() {
            ModuleProcessLifecycle active = owner;
            if (active == null) return;
            owner = null;
            active.main.removeCallbacks(expire);
            active.release();
        }
    }

    static synchronized ModuleProcessLifecycle installForTest(Handler main, Runnable exit) {
        resetForTest();
        current = new ModuleProcessLifecycle(main, exit);
        current.scheduleIdle();
        return current;
    }
    static synchronized void resetForTest() {
        ModuleProcessLifecycle owner = current;
        current = null;
        if (owner != null) synchronized (owner) {
            owner.stopped = true;
            owner.main.removeCallbacksAndMessages(owner);
            owner.activities.clear();
        }
    }
    static int activeLeaseCountForTest() {
        ModuleProcessLifecycle owner = current;
        if (owner == null) return 0;
        synchronized (owner) { return owner.leases; }
    }
}
