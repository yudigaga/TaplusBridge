package io.github.yudigaga.taplusbridge.thaw;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import io.github.yudigaga.taplusbridge.BuildConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import rikka.shizuku.Shizuku;

/** App-process permission and lifetime handling; no automatic permission prompts or Root fallback. */
public final class ShizukuAccess {
    private static final Map<Runnable, Listeners> LISTENERS = new HashMap<>();
    private ShizukuAccess() { }
    private static boolean available() {
        try { return Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.getVersion() >= 13; }
        catch (RuntimeException unavailable) { return false; }
    }
    public static boolean granted() {
        try { return available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException unavailable) { return false; }
    }
    public static String status() {
        try {
            if (!Shizuku.pingBinder()) return "Shizuku 未运行或未连接";
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) return "需要 Shizuku 13 或更新版本";
            if (!granted()) return "Shizuku 尚未授权，点击申请";
            int uid = Shizuku.getUid();
            return uid == 0 ? "Shizuku 已授权（Root 身份）" : uid == 2000 ? "Shizuku 已授权（ADB 身份）" : "Shizuku 身份不受支持";
        } catch (RuntimeException unavailable) { return "Shizuku 服务不可用"; }
    }
    public static void requestPermission(int code) {
        requireMain();
        if (!available()) throw new IllegalStateException("请先安装并启动 Shizuku 13 或更新版本");
        if (granted()) return;
        if (Shizuku.shouldShowRequestPermissionRationale()) throw new IllegalStateException("请在 Shizuku 管理器中允许 taplusbridge 使用权限");
        Shizuku.requestPermission(code);
    }
    public static void addPermissionListener(Runnable callback) {
        requireMain(); if (callback == null) throw new IllegalArgumentException("callback");
        Listeners listeners;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(callback)) return;
            listeners = new Listeners(callback); LISTENERS.put(callback, listeners);
        }
        Shizuku.addBinderReceivedListenerSticky(listeners.received, listeners.main);
        Shizuku.addBinderDeadListener(listeners.dead, listeners.main);
        Shizuku.addRequestPermissionResultListener(listeners.permission, listeners.main);
    }
    public static void removePermissionListener(Runnable callback) {
        requireMain(); Listeners listeners;
        synchronized (LISTENERS) { listeners = LISTENERS.remove(callback); }
        if (listeners == null) return;
        Shizuku.removeBinderReceivedListener(listeners.received); Shizuku.removeBinderDeadListener(listeners.dead);
        Shizuku.removeRequestPermissionResultListener(listeners.permission); listeners.main.removeCallbacksAndMessages(null);
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Shizuku 授权应从页面主线程发起");
    }
    private static final class Listeners {
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable callback;
        final Shizuku.OnBinderReceivedListener received = this::changed;
        final Shizuku.OnBinderDeadListener dead = this::changed;
        final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> changed();
        Listeners(Runnable callback) { this.callback = callback; }
        void changed() {
            main.post(() -> {
                synchronized (LISTENERS) { if (LISTENERS.get(callback) != this) return; }
                callback.run();
            });
        }
    }
    static BoundedCommandRunner.Outcome thaw(Context context, String pkg, int user) {
        if (Looper.myLooper() == Looper.getMainLooper()) return ShizukuThawClient.failure("Shizuku 解冻需要后台线程");
        if (!granted()) return ShizukuThawClient.failure("Shizuku 未运行或未授权，请先在模块中授权");
        try { if (Shizuku.getUid() != 0 && Shizuku.getUid() != 2000) return ShizukuThawClient.failure("Shizuku 身份不受支持"); }
        catch (RuntimeException disconnected) { return ShizukuThawClient.failure("Shizuku 服务已断开"); }
        String id = UUID.randomUUID().toString();
        Binding binding = new Binding(context, id);
        try {
            IThawService service = binding.await();
            if (service == null) return ShizukuThawClient.failure("Shizuku 解冻服务连接超时");
            return ShizukuThawClient.call(service, pkg, user, id, 12_000L);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); return ShizukuThawClient.failure("操作已取消");
        } catch (RuntimeException failed) { return ShizukuThawClient.failure("Shizuku 解冻服务不可用"); }
        finally { binding.close(); }
    }
    private static final class Binding implements ServiceConnection {
        final Handler main = new Handler(Looper.getMainLooper());
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicReference<IThawService> service = new AtomicReference<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final Shizuku.UserServiceArgs args;
        Binding(Context context, String id) {
            args = new Shizuku.UserServiceArgs(new ComponentName(context, PrivilegedThawService.class))
                    .tag("taplus-thaw-" + id).version(BuildConfig.VERSION_CODE).daemon(false)
                    .processNameSuffix("thaw").debuggable(BuildConfig.DEBUG);
        }
        IThawService await() throws InterruptedException {
            main.post(() -> {
                if (closed.get()) { connected.countDown(); return; }
                try { Shizuku.bindUserService(args, this); }
                catch (RuntimeException failed) { connected.countDown(); }
            });
            if (!connected.await(2500, TimeUnit.MILLISECONDS) || closed.get()) return null;
            return service.get();
        }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed.get()) { remove(); return; }
            if (binder != null && binder.pingBinder()) service.set(IThawService.Stub.asInterface(binder));
            connected.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service.set(null); connected.countDown(); }
        void close() {
            if (!closed.compareAndSet(false, true)) return;
            service.set(null); connected.countDown(); main.post(this::remove);
        }
        void remove() {
            // Detach the app-side connection even if startup failed before a service Binder
            // existed. remove=true alone relies on a later Binder-death callback for cleanup.
            try { Shizuku.unbindUserService(args, this, false); } catch (RuntimeException ignored) { }
            try { Shizuku.unbindUserService(args, this, true); } catch (RuntimeException ignored) { }
        }
    }
}
