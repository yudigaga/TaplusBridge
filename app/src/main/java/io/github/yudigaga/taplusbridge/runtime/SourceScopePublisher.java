package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Publish only the host's app blacklist key, using the privileged host or system Context. */
public final class SourceScopePublisher {
    public static final String KEY = "taplus_blacklist";
    // Platform PackageManager.MATCH_ANY_USER: the Global setting is shared across profiles.
    private static final int MATCH_ANY_USER = 0x00400000;
    private SourceScopePublisher() {}

    public static List<String> effectiveBlacklist(Context context, SourceScopePolicy policy) {
        if (!policy.managed) throw new IllegalArgumentException("scope_unmanaged");
        if (policy.mode == SourceScopePolicy.Mode.BLACKLIST) return new ArrayList<>(policy.blacklist);
        List<ApplicationInfo> applications = context.getPackageManager().getInstalledApplications(
                PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER);
        if (applications == null) throw new IllegalStateException("package_catalog_unavailable");
        TreeSet<String> denied = new TreeSet<>();
        for (ApplicationInfo app : applications) {
            // The trusted PM catalog also contains single-component platform/resource packages.
            // This is not the target/command validation used for launching or thawing apps.
            if (app == null || app.packageName == null || app.packageName.length() >= 256
                    || !app.packageName.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*"))
                throw new IllegalStateException("package_catalog_invalid");
            if (!policy.allows(app.packageName)) denied.add(app.packageName);
        }
        return new ArrayList<>(denied);
    }

    public static void publish(Context context, List<String> blacklist) {
        try {
            JSONObject data = new JSONObject();
            data.put(KEY, new JSONArray(blacklist));
            String encoded = data.toString();
            if (!encoded.equals(Settings.Global.getString(context.getContentResolver(), KEY))
                    && !Settings.Global.putString(context.getContentResolver(), KEY, encoded))
                throw new IllegalStateException("scope_publish_failed");
        } catch (org.json.JSONException malformed) {
            throw new IllegalArgumentException("scope_invalid", malformed);
        }
    }
}
