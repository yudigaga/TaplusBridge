package io.github.yudigaga.taplusbridge.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.UserHandle;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.lang.reflect.Method;

/** One-shot observation at the outer Binder entry. Original launch arguments/results are never changed. */
public final class SystemCaptureObserver {
    private static final String TAG = "TaplusBridge";
    private static final Uri PROVIDER = Uri.parse("content://" + BridgeContract.AUTHORITY);
    private final XposedModule module;
    private final CaptureLease leases = new CaptureLease();
    private final Handler worker;
    private volatile Session session;
    private boolean armRegistered, cancelRegistered;
    private record Session(long generation, String nonce, int user) { }

    public static void install(XposedModule module, ClassLoader loader) {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            module.log(5, TAG, "System observation requires Android 13 or newer"); return;
        }
        SystemCaptureObserver observer = null;
        try { observer = new SystemCaptureObserver(module); observer.start(loader); }
        catch (ReflectiveOperationException | RuntimeException e) {
            if (observer != null) observer.worker.getLooper().quitSafely();
            module.log(5, TAG, "System observer unavailable: " + e.getClass().getSimpleName());
        }
    }
    private SystemCaptureObserver(XposedModule module) {
        this.module = module;
        HandlerThread thread = new HandlerThread("TaplusBridge-observe"); thread.start();
        worker = new Handler(thread.getLooper());
    }
    private void start(ClassLoader loader) throws ReflectiveOperationException {
        Method found = null;
        for (Method method : loader.loadClass("com.android.server.wm.ActivityTaskManagerService").getDeclaredMethods()) {
            if (!AtmsSignature.matches(method)) continue;
            if (found != null) throw new NoSuchMethodException("Ambiguous ATMS entry");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("Unsupported ATMS entry");
        found.setAccessible(true);
        module.hook(found).setId("taplusbridge:one-shot-system-observe")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    CaptureLease.Observation match = null;
                    IntentObservation.Observation metadata = null;
                    Session owner = null;
                    boolean forResult = false;
                    if (leases.isArmed(SystemClock.elapsedRealtime())) try {
                        int uid = Binder.getCallingUid();
                        String source = (String) chain.getArg(1);
                        Intent intent = (Intent) chain.getArg(3);
                        int user = (Integer) chain.getArg(11);
                        ComponentName component = intent == null ? null : intent.getComponent();
                        if (component != null) synchronized (leases) {
                            match = leases.observe(source, uid, user, component.getPackageName(), component.getClassName(), SystemClock.elapsedRealtime());
                            if (match != null && session != null && session.generation() == match.generation()) owner = session;
                        }
                        if (match != null && owner != null) {
                            metadata = IntentObservation.inspect(intent);
                            forResult = chain.getArg(5) != null && (Integer) chain.getArg(7) >= 0;
                        }
                    } catch (RuntimeException e) { module.log(5, TAG, "Observation skipped: " + e.getClass().getSimpleName()); }
                    // Do not put proceed() inside a recovery catch: a platform failure must never replay a launch.
                    Object result = null;
                    try { result = chain.proceed(); return result; }
                    finally {
                        if (match != null && metadata != null && owner != null) {
                            CaptureLease.Observation event = match; IntentObservation.Observation summary = metadata;
                            Session ticket = owner; boolean hasResult = forResult;
                            int launchResult = result instanceof Integer value ? value : Integer.MIN_VALUE;
                            worker.post(() -> report(ticket, event, summary, hasResult, launchResult));
                        }
                    }
                });
        worker.post(() -> registerReceivers(0));
        module.log(4, TAG, "System one-shot observation hook installed; launches preserved");
    }
    private void registerReceivers(int attempt) {
        try {
            Context context = systemContext();
            if (context == null) throw new IllegalStateException("System context pending");
            BroadcastReceiver arm = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) { arm(context, intent); }
            };
            BroadcastReceiver cancel = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    try {
                        intent.setExtrasClassLoader(ResultReceiver.class.getClassLoader());
                        String nonce = intent.getStringExtra("nonce");
                        synchronized (leases) {
                            Session current = session;
                            if (current != null && CaptureBroker.validNonce(nonce) && current.nonce().equals(nonce)) {
                                leases.cancel(current.generation()); session = null;
                            }
                        }
                    } catch (RuntimeException ignoredPayload) { }
                }
            };
            if (!armRegistered) {
                registerAll(context, arm, new IntentFilter(CaptureBroker.ACTION_ARM), "android.permission.SET_ACTIVITY_WATCHER");
                armRegistered = true;
            }
            if (!cancelRegistered) {
                registerAll(context, cancel, new IntentFilter(CaptureBroker.ACTION_CANCEL), BridgeContract.APP_PACKAGE + ".CANCEL_CAPTURE");
                cancelRegistered = true;
            }
            module.log(4, TAG, "System observation command channel ready");
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (attempt < 5) worker.postDelayed(() -> registerReceivers(attempt + 1), 1000L << attempt);
            else module.log(5, TAG, "System command channel unavailable: " + e.getClass().getSimpleName());
        }
    }
    private void registerAll(Context context, BroadcastReceiver receiver, IntentFilter filter, String permission)
            throws ReflectiveOperationException {
        UserHandle all = (UserHandle) UserHandle.class.getDeclaredField("ALL").get(null);
        Method registration = Context.class.getDeclaredMethod("registerReceiverAsUser", BroadcastReceiver.class,
                UserHandle.class, IntentFilter.class, String.class, Handler.class, int.class);
        registration.setAccessible(true);
        registration.invoke(context, receiver, all, filter, permission, worker, Context.RECEIVER_EXPORTED);
    }
    @SuppressWarnings("deprecation")
    private void arm(Context system, Intent intent) {
        ResultReceiver ack = null;
        try {
            intent.setExtrasClassLoader(ResultReceiver.class.getClassLoader());
            ack = intent.getParcelableExtra("ack");
            if (ack == null) {
                module.log(5, TAG, "capture stage=command_rejected reason=missing_ack");
                return;
            }
            String nonce = intent.getStringExtra("nonce"); int user = intent.getIntExtra("user", -1);
            if (!CaptureBroker.validNonce(nonce) || user < 0 || user > 10000) return;
            Context context = userContext(system, user);
            Bundle extras = new Bundle(); extras.putString("nonce", nonce);
            Bundle ticket = context.getContentResolver().call(PROVIDER, "capture_take", null, extras);
            if (ticket == null || ticket.isEmpty() || ticket.getInt("user", -2) != user) { acknowledge(ack, false); return; }
            long generation;
            synchronized (leases) {
                generation = leases.arm(ticket.getString("source"), ticket.getInt("source_uid", -1), user, SystemClock.elapsedRealtime());
                session = new Session(generation, nonce, user);
            }
            module.log(4, TAG, "capture stage=armed source=" + ticket.getString("source") + " user=" + user + " ttl_ms=15000");
            acknowledge(ack, true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            module.log(5, TAG, "Capture arm unavailable: " + e.getClass().getSimpleName()); acknowledge(ack, false);
        }
    }
    private void report(Session owner, CaptureLease.Observation event, IntentObservation.Observation summary,
                        boolean forResult, int result) {
        try {
            Context context = userContext(systemContext(), owner.user());
            Bundle data = new Bundle(); data.putString("nonce", owner.nonce()); data.putInt("user", owner.user());
            data.putLong("observed_elapsed", event.observedAt());
            data.putString("component_package", summary.componentPackage()); data.putString("component_class", summary.componentClass());
            data.putString("outcome", summary.outcome().name()); data.putString("origin", summary.urlOrigin().name());
            data.putInt("url_count", summary.urlCount()); data.putInt("fields", summary.fieldPresence());
            data.putBoolean("for_result", forResult); data.putInt("launch_result", result);
            Bundle response = context.getContentResolver().call(PROVIDER, "capture_report", null, data);
            if (response != null && response.getBoolean("saved")) module.log(4, TAG,
                    "capture stage=observed component=" + summary.componentClass() + " outcome=" + summary.outcome().name()
                    + " url_count=" + summary.urlCount() + " origin=" + summary.urlOrigin().name()
                    + " fields=" + summary.fieldPresence() + " for_result=" + forResult + " launch_result=" + result);
        } catch (ReflectiveOperationException | RuntimeException e) { module.log(5, TAG, "Capture report unavailable: " + e.getClass().getSimpleName()); }
    }
    private static void acknowledge(ResultReceiver ack, boolean success) {
        try { if (ack != null) ack.send(success ? 1 : 0, Bundle.EMPTY); }
        catch (RuntimeException invalidReceiver) { }
    }
    private static Context systemContext() throws ReflectiveOperationException {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object current = thread.getMethod("currentActivityThread").invoke(null);
        return current == null ? null : (Context) thread.getMethod("getSystemContext").invoke(current);
    }
    private static Context userContext(Context context, int user) throws ReflectiveOperationException {
        if (context == null) throw new IllegalStateException("No system context");
        UserHandle handle = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, user);
        return (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class).invoke(context, handle, 0);
    }
}
