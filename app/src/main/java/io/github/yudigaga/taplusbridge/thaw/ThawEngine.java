package io.github.yudigaga.taplusbridge.thaw;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

/** Current-profile thawing. Call from a worker thread; opening the target belongs to the UI. */
public final class ThawEngine {
    public enum Mode { ROOT, SHIZUKU }
    private static final long STATE_WAIT_MILLIS = 3_000L;

    public static final class Result {
        public final boolean success;
        public final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private ThawEngine() {}

    public static Result thaw(Context context, String packageName, Mode mode) {
        if (context == null || !RootCommandPolicy.validPackage(packageName) || mode == null) {
            return failure("目标应用或解冻方式无效");
        }
        if (onMainThread()) return failure("解冻需要在后台线程执行");
        if (packageName.equals(context.getPackageName())) return failure("不能将模块自身作为解冻目标");
        try {
            State before = readState(context, packageName);
            if (!before.installed) return failure("目标应用未安装在当前用户中");
            if (before.suspended) return failure("目标应用被系统暂停，当前解冻方式不处理暂停状态");
            if (before.ready()) return success("目标应用已启用");
            if (mode == Mode.ROOT) {
                BoundedCommandRunner.Outcome result = BoundedCommandRunner.root(
                        RootCommandPolicy.enableCommand(packageName, Process.myUid() / 100000));
                if (!result.successful()) {
                    return failure(result.completed ? "Root 解冻失败，请检查超级用户授权"
                            : "Root 解冻未完成：" + result.failure);
                }
            } else {
                BoundedCommandRunner.Outcome result = ShizukuAccess.thaw(context, packageName, Process.myUid() / 100000);
                if (!result.successful()) return failure(result.failure);
            }
            return waitForReady(context, packageName);
        } catch (PackageManager.NameNotFoundException error) {
            return failure("目标应用未安装在当前用户中");
        } catch (SecurityException error) {
            return failure("解冻授权不足，请检查所选 Root 或 Shizuku 权限");
        } catch (RuntimeException error) {
            return failure("解冻服务不可用：" + error.getClass().getSimpleName());
        }
    }

    /** Worker-thread Root authorization check. Does not change package state. */
    public static Result probeRoot() {
        if (onMainThread()) return failure("Root 检测需要在后台线程执行");
        BoundedCommandRunner.Outcome outcome = BoundedCommandRunner.root("id -u");
        return outcome.successful() && "0".equals(outcome.output.trim())
                ? success("Root 已授权") : failure("Root 不可用或尚未授权");
    }

    public static String shizukuStatus() { return ShizukuAccess.status(); }
    public static boolean shizukuGranted() { return ShizukuAccess.granted(); }
    public static void requestShizukuPermission(int requestCode) { ShizukuAccess.requestPermission(requestCode); }

    private static Result waitForReady(Context context, String packageName) {
        long deadline = SystemClock.elapsedRealtime() + STATE_WAIT_MILLIS;
        do {
            if (Thread.currentThread().isInterrupted()) return failure("解冻验证已取消");
            try {
                State state = readState(context, packageName);
                if (state.ready()) return success("目标应用已解冻");
                if (!state.installed) return failure("目标应用已从当前用户卸载");
            } catch (PackageManager.NameNotFoundException ignored) {
                // PM may briefly not expose an app while an enabling operation completes.
            }
            try { Thread.sleep(100L); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return failure("解冻验证已取消");
            }
        } while (SystemClock.elapsedRealtime() < deadline);
        return failure("解冻请求已发送，但未确认应用恢复；已停止自动打开");
    }

    private static State readState(Context context, String packageName)
            throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        ApplicationInfo includingHidden = pm.getApplicationInfo(packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES);
        boolean installed = (includingHidden.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
        boolean suspended = (includingHidden.flags & ApplicationInfo.FLAG_SUSPENDED) != 0;
        boolean visible;
        try {
            ApplicationInfo normal = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            visible = (normal.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
        } catch (PackageManager.NameNotFoundException hidden) {
            visible = false;
        }
        return new State(installed, includingHidden.enabled, visible, suspended);
    }

    private static boolean onMainThread() {
        return Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper();
    }

    private static Result success(String message) { return new Result(true, message); }
    private static Result failure(String message) { return new Result(false, message); }

    private static final class State {
        final boolean installed;
        final boolean enabled;
        final boolean visible;
        final boolean suspended;

        State(boolean installed, boolean enabled, boolean visible, boolean suspended) {
            this.installed = installed;
            this.enabled = enabled;
            this.visible = visible;
            this.suspended = suspended;
        }

        boolean ready() { return installed && enabled && visible && !suspended; }
    }
}
