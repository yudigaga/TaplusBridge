package io.github.yudigaga.taplusbridge.thaw;

import android.os.Bundle;
import android.os.RemoteException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds a remote call even if its Binder thread stalls; cancellation never opens the target. */
final class ShizukuThawClient {
    private ShizukuThawClient() { }
    static BoundedCommandRunner.Outcome call(IThawService service, String pkg, int user, String id, long timeout) {
        if (service == null || timeout <= 0) return failure("Shizuku 服务未连接");
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "TaplusBridge-shizuku-call"); thread.setDaemon(true); return thread;
        });
        Future<Bundle> pending = null;
        boolean completed = false;
        try {
            if (Thread.currentThread().isInterrupted()) return failure("操作已取消");
            pending = worker.submit(() -> service.thaw(pkg, user, id));
            Bundle result = pending.get(timeout, TimeUnit.MILLISECONDS);
            if (result == null || result.getInt("protocol", -1) != PrivilegedThawService.PROTOCOL) return failure("Shizuku 服务协议不匹配");
            boolean finished = result.getBoolean("completed"); int exit = result.getInt("exit", -1);
            String reason = result.getString("failure", "unavailable");
            completed = true;
            return new BoundedCommandRunner.Outcome(finished, exit, "", "".equals(reason) && finished && exit == 0 ? "" : "Shizuku 解冻未完成，请检查权限和系统限制");
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); return failure("操作已取消");
        } catch (TimeoutException expired) {
            return failure("Shizuku 命令超时");
        } catch (ExecutionException | RuntimeException disconnected) {
            return failure("Shizuku 服务已断开");
        } finally {
            if (!completed) {
                try { service.cancel(id); } catch (RemoteException | RuntimeException ignored) { }
                if (pending != null) pending.cancel(true);
            }
            worker.shutdownNow();
        }
    }
    static BoundedCommandRunner.Outcome failure(String message) { return new BoundedCommandRunner.Outcome(false, -1, "", message); }
}
