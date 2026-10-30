package io.github.yudigaga.taplusbridge.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.util.Arrays;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.capture.CaptureBroker;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;

public final class BridgeProvider extends ContentProvider {
    @Override public boolean onCreate() {
        IceRuleRouting.registerPackageInvalidationReceiver(getContext(), null);
        return true;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context != null && ("capture_take".equals(method) || "capture_report".equals(method)
                || ("probe_status".equals(method) && Binder.getCallingUid() == 1000))) {
            if (Binder.getCallingUid() != 1000) return Bundle.EMPTY;
            long identity = Binder.clearCallingIdentity();
            try {
                if ("capture_take".equals(method)) return CaptureBroker.take(context, extras == null ? null : extras.getString("nonce"));
                if ("probe_status".equals(method)) {
                    Bundle result = new Bundle(); result.putBoolean("saved", CaptureBroker.recordProbeStatus(context, extras)); return result;
                }
                Bundle result = new Bundle(); result.putBoolean("saved", CaptureBroker.report(context, extras)); return result;
            } catch (RuntimeException malformedPayload) { return Bundle.EMPTY; }
            finally { Binder.restoreCallingIdentity(identity); }
        }
        if (context == null || (!BridgeContract.PREPARE.equals(method) && !"resolve".equals(method)
                && !"recognition_status".equals(method) && !"capture_available".equals(method)
                && !"capture_arm".equals(method) && !"probe_arm".equals(method) && !"probe_status".equals(method) && !"capture_cancel".equals(method))) return Bundle.EMPTY;
        int uid = Binder.getCallingUid();
        if (uid / 100000 != Process.myUid() / 100000) return Bundle.EMPTY;
        String[] callers = context.getPackageManager().getPackagesForUid(uid);
        if (callers == null || !Arrays.asList(callers).contains(BridgeContract.HOST_PACKAGE)) return Bundle.EMPTY;
        long identity = Binder.clearCallingIdentity();
        try {
            PackageInfo host = context.getPackageManager().getPackageInfo(BridgeContract.HOST_PACKAGE, 0);
            if (host.getLongVersionCode() != 40013 || !"4.1.3".equals(host.versionName)) return declined("unsupported_host");
            if ("probe_status".equals(method)) {
                Bundle result = new Bundle(); result.putBoolean("saved", CaptureBroker.recordProbeStatus(context, extras)); return result;
            }
            if ("capture_available".equals(method)) {
                Bundle result = new Bundle(); result.putBoolean("enabled", android.os.Build.VERSION.SDK_INT >= 33
                        && new ConfigStore(context).enabled() && new ConfigStore(context).captureEnabled()
                        && new ConfigStore(context).recognitionEnabled()
                        && extras != null && CaptureLease.allowedSource(extras.getString("source"))); return result;
            }
            if ("probe_arm".equals(method) && !new ConfigStore(context).recognitionEnabled()) return declined("recognition_disabled");
            if ("capture_arm".equals(method) || "probe_arm".equals(method)) return android.os.Build.VERSION.SDK_INT >= 33
                    ? CaptureBroker.arm(context, extras == null ? null : extras.getString("source")) : declined("unsupported_system");
            if ("capture_cancel".equals(method)) {
                Bundle result = new Bundle(); result.putBoolean("cancelled", CaptureBroker.cancel(context, extras == null ? null : extras.getString("nonce"))); return result;
            }
            if ("recognition_status".equals(method)) {
                if (extras == null) return Bundle.EMPTY;
                context.getSharedPreferences("status", Context.MODE_PRIVATE).edit()
                        .putLong("recognition_at", System.currentTimeMillis())
                        .putInt("recognition_links", Math.max(0, Math.min(8, extras.getInt("links"))))
                        .apply();
                return Bundle.EMPTY;
            }
            if ("resolve".equals(method) && extras != null && extras.getBoolean("local_recognition")
                    && !new ConfigStore(context).recognitionEnabled()) return declined("recognition_disabled");
            // A host IPC cannot impersonate an explicit choice made in our own foreground UI.
            if (extras != null && extras.containsKey("explicit_target")) return declined("invalid_request");
            boolean ephemeral = extras != null && extras.getBoolean("ephemeral");
            if (ephemeral) {
                // Captured HTTP must match current rules, never a global choice or stale launcher.
                extras = new Bundle(extras); extras.remove("original"); extras.remove("candidate");
            }
            LaunchPlanner.PlanningResult planned = LaunchPlanner.planWithReason(context, extras);
            LaunchPlanner.Plan plan = planned.plan();
            if (plan == null) return declined(planned.reason());
            if ("resolve".equals(method)) return preview(context, plan);
            Intent direct = LaunchPlanner.directIfReady(context, plan);
            if (direct != null) {
                Bundle result = new Bundle(); result.putParcelable("direct", direct);
                recordRequest(context); return result;
            }
            String token = ephemeral ? EphemeralTicketStore.issue(extras) : new TicketStore(context).issue(extras);
            Bundle result = new Bundle();
            if (token != null) {
                result.putString("token", token);
                recordRequest(context);
            }
            return result;
        } catch (Exception e) { return declined("exception"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    private static Bundle declined(String reason) {
        Bundle response = new Bundle(); response.putString("reason", reason); return response;
    }
    private static void recordRequest(Context context) {
        context.getSharedPreferences("status", Context.MODE_PRIVATE).edit()
                .putLong("last_request", System.currentTimeMillis()).apply();
    }
    private static Bundle preview(Context context, LaunchPlanner.Plan plan) throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        ApplicationInfo app = pm.getApplicationInfo(plan.targetPackage(),
                PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES);
        Bundle result = new Bundle();
        result.putString("target", plan.targetPackage());
        result.putString("title", pm.getApplicationLabel(app).toString());
        try {
            Drawable icon = pm.getApplicationIcon(app);
            Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 64, 64); icon.draw(new Canvas(bitmap));
            result.putParcelable("icon", bitmap);
        } catch (RuntimeException ignored) { }
        return result;
    }
    public static Bundle consume(Context context, String token) {
        return token != null && token.startsWith("p_") ? EphemeralTicketStore.consume(token) : new TicketStore(context).consume(token);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
