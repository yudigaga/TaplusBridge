package io.github.yudigaga.taplusbridge.capture;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import java.security.SecureRandom;

/** Private, single-use bootstrap capability. Stores metadata only, never a URL or original Intent. */
public final class CaptureBroker {
    public static final String ACTION_ARM = "io.github.yudigaga.taplusbridge.CAPTURE_NEXT";
    public static final String ACTION_CANCEL = "io.github.yudigaga.taplusbridge.CAPTURE_CANCEL";
    private static final Object LOCK = new Object();
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("capture", Context.MODE_PRIVATE); }
    private CaptureBroker() { }

    public static Bundle arm(Context context, String source) {
        if (!new ConfigStore(context).enabled() || !new ConfigStore(context).captureEnabled()
                || !CaptureLease.allowedSource(source)) return failure("unavailable");
        int user = Process.myUid() / 100000;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(source, 0);
            if (!info.enabled || (info.flags & ApplicationInfo.FLAG_INSTALLED) == 0 || info.uid / 100000 != user)
                return failure("source_unavailable");
            byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
            String nonce = Base64.encodeToString(random, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
            synchronized (LOCK) {
                if (!prefs(context).edit().clear().putString("nonce", nonce).putString("source", source)
                        .putInt("source_uid", info.uid).putInt("user", user).putString("state", "pending")
                        .putLong("issued", SystemClock.elapsedRealtime()).putLong("created_wall", System.currentTimeMillis()).commit())
                    return failure("storage_failed");
            }
            Bundle result = new Bundle(); result.putString("nonce", nonce); result.putInt("user", user); return result;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) { return failure("source_unavailable"); }
    }

    /** Called only by authenticated system UID through BridgeProvider. */
    public static Bundle take(Context context, String nonce) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            long age = SystemClock.elapsedRealtime() - p.getLong("issued", -10000);
            if (!validNonce(nonce) || !nonce.equals(p.getString("nonce", ""))
                    || !"pending".equals(p.getString("state", "")) || age < 0 || age >= 2500
                    || !new ConfigStore(context).captureEnabled() || !new ConfigStore(context).enabled()) return Bundle.EMPTY;
            int user = p.getInt("user", -1), uid = p.getInt("source_uid", -1);
            String source = p.getString("source", "");
            if (user != Process.myUid() / 100000 || uid < 0 || uid / 100000 != user || !CaptureLease.allowedSource(source)) return Bundle.EMPTY;
            if (!p.edit().putString("state", "armed").putLong("armed", SystemClock.elapsedRealtime()).commit()) return Bundle.EMPTY;
            Bundle result = new Bundle(); result.putString("source", source); result.putInt("source_uid", uid);
            result.putInt("user", user); return result;
        }
    }

    /** Authenticated system reports are additionally bound to the current one-use nonce. */
    public static boolean report(Context context, Bundle data) {
        if (data == null || !new ConfigStore(context).enabled() || !new ConfigStore(context).captureEnabled()) return false;
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            String nonce = data.getString("nonce"), source = p.getString("source", "");
            long now = SystemClock.elapsedRealtime(), armed = p.getLong("armed", -1);
            long observed = data.getLong("observed_elapsed", -1);
            if (!validNonce(nonce) || !nonce.equals(p.getString("nonce", ""))
                    || !"armed".equals(p.getString("state", "")) || armed < 0 || now < armed || now - armed > 30000
                    || observed < armed || observed - armed >= CaptureLease.TTL_MILLIS
                    || data.getInt("user", -1) != p.getInt("user", -2)
                    || !CaptureLease.expectedDestination(source, data.getString("component_package"), data.getString("component_class"))) return false;
            String outcome = data.getString("outcome", "NO_URL");
            if (!java.util.Set.of("URL_FOUND", "NO_URL", "UNREADABLE_PAYLOAD").contains(outcome)) return false;
            String origin = data.getString("origin", "NONE");
            if (!java.util.Set.of("DATA", "RAW_URL", "URL", "TEXT", "NESTED", "NONE").contains(origin)) return false;
            return p.edit().remove("nonce").putString("state", "captured")
                    .putString("component", data.getString("component_class"))
                    .putString("outcome", outcome).putString("origin", origin)
                    .putInt("url_count", Math.max(0, Math.min(8, data.getInt("url_count"))))
                    .putInt("fields", data.getInt("fields") & 31)
                    .putBoolean("for_result", data.getBoolean("for_result"))
                    .putInt("launch_result", data.getInt("launch_result", Integer.MIN_VALUE))
                    .putLong("completed_wall", System.currentTimeMillis()).commit();
        }
    }

    public static boolean cancel(Context context, String nonce) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            String current = p.getString("nonce", "");
            if (!validNonce(nonce) || !nonce.equals(current)) return false;
            int user = p.getInt("user", Process.myUid() / 100000);
            SharedPreferences.Editor edit = p.edit().remove("nonce").putString("state", "cancelled");
            String probe = p.getString("probe_stage", null);
            if (probe != null && !"failed".equals(probe) && !"captured".equals(probe))
                edit.putString("probe_stage", "cancelled").putString("probe_reason", "cancelled");
            if (!edit.commit()) return false;
            // A cancellation capability can only cancel its own nonce, never create an observation.
            context.sendBroadcast(new Intent(ACTION_CANCEL).setPackage("android").putExtra("nonce", nonce).putExtra("user", user));
            return true;
        }
    }
    public static void cancelCurrent(Context context) { cancel(context, prefs(context).getString("nonce", null)); }
    /** Diagnostics are fixed enums and bound to the current nonce; no source text or URL is stored. */
    public static boolean recordProbeStatus(Context context, Bundle data) {
        if (data == null) return false;
        synchronized (LOCK) {
            SharedPreferences p = prefs(context);
            String nonce = data.getString("nonce"), stage = data.getString("stage"), reason = data.getString("reason");
            if (stage == null || !validNonce(nonce) || !nonce.equals(p.getString("nonce", ""))
                    || !java.util.Set.of("waiting_release", "ready", "input_sent", "captured", "failed", "cancelled").contains(stage)) return false;
            if (reason == null || !java.util.Set.of("waiting_release", "ready", "input_sent", "captured", "cancelled", "replaced",
                    "stale_session", "ready_timeout", "capture_timeout", "geometry_unavailable", "protocol", "source_changed",
                    "window_occluded", "window_unavailable", "input_unavailable", "no_unique_url", "unreadable", "unsupported_request",
                    "capture_unavailable", "touch_active", "touch_unknown", "snapshot", "invalid_response", "unavailable").contains(reason)) reason = "unavailable";
            return p.edit().putString("probe_stage", stage).putString("probe_reason", reason)
                    .putLong("probe_updated", SystemClock.elapsedRealtime()).commit();
        }
    }
    public static String status(Context context) {
        SharedPreferences p = prefs(context);
        String state = p.getString("state", "idle");
        long now = SystemClock.elapsedRealtime();
        String stage = p.getString("probe_stage", null);
        if (stage != null) {
            String reason = p.getString("probe_reason", "unavailable");
            if ("captured".equals(stage)) return "自动识别已取得链接；目标应用以传送门卡片为准";
            if ("failed".equals(stage)) return "自动识别未完成\n原因：" + probeReason(reason);
            if ("cancelled".equals(stage)) return "自动识别已取消";
            long updated = p.getLong("probe_updated", -1);
            if (updated < 0 || now < updated || now - updated >= 6000) return "自动识别已超时，请重新长按下一张卡片";
            return switch (stage) {
                case "waiting_release" -> "等待真实触摸抬起，尚未模拟点击";
                case "ready" -> "系统已确认触摸抬起，准备自动识别";
                default -> "正在自动识别分享卡片";
            };
        }
        if ("pending".equals(state) && (now < p.getLong("issued", 0) || now - p.getLong("issued", 0) >= 2500)) return "未收到系统确认：请检查模块日志中的框架加载和命令通道状态";
        if ("armed".equals(state) && (now < p.getLong("armed", 0) || now - p.getLong("armed", 0) >= CaptureLease.TTL_MILLIS)) return "捕获已超时：没有收到符合条件的网页启动请求";
        return switch (state) {
            case "pending" -> "正在连接系统观察器";
            case "armed" -> "捕获中：请在15秒内点击原卡片";
            case "cancelled" -> "捕获已取消";
            case "captured" -> ("URL_FOUND".equals(p.getString("outcome", "")) ? "启动请求中发现网址" : "启动请求中未取得完整网址")
                    + "\n来源：" + p.getString("source", "") + "\n来源页面组件：" + p.getString("component", "")
                    + "\n网址数：" + p.getInt("url_count", 0) + "，字段：" + p.getString("origin", "NONE")
                    + (p.getBoolean("for_result", false) ? "\n此启动带结果回调，后续不能直接替换" : "");
            default -> "暂无自动识别记录；开启后长按分享卡片即可";
        };
    }
    public static boolean validNonce(String nonce) { return nonce != null && nonce.matches("[A-Za-z0-9_-]{43}"); }
    private static String probeReason(String reason) {
        return switch (reason) {
            case "waiting_release", "touch_active" -> "原触摸尚未确认抬起";
            case "touch_unknown" -> "系统触摸状态异常，已停止模拟点击；需重启后重试";
            case "source_changed", "snapshot", "geometry_unavailable" -> "原卡片位置或页面不可确认";
            case "window_occluded" -> "菜单或其他窗口遮挡原卡片";
            case "window_unavailable", "protocol" -> "当前系统接口或协议未就绪";
            case "input_unavailable" -> "输入无法安全投到原卡片";
            case "no_unique_url", "unreadable", "unsupported_request" -> "未取得唯一受支持的网页链接";
            case "ready_timeout", "capture_timeout" -> "等待系统确认或链接超时";
            case "replaced", "stale_session" -> "已切换到新卡片";
            case "cancelled" -> "已取消";
            default -> "系统探测暂不可用";
        };
    }
    private static Bundle failure(String reason) { Bundle result = new Bundle(); result.putString("reason", reason); return result; }
}
