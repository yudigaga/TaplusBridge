package io.github.yudigaga.taplusbridge.browser;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.recognition.RouteLink;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Browser candidates advertise general web handling or the platform browser category. */
public final class BrowserApps {
    public record Target(String packageName, String label, Drawable icon) {}
    private BrowserApps() {}

    public static List<Target> installed(Context context) {
        PackageManager packages = context.getPackageManager();
        ArrayList<Target> result = new ArrayList<>();
        for (ResolveInfo browser : candidates(context).values()) {
            String packageName = browser.activityInfo.packageName;
            String label = packageName;
            Drawable icon = null;
            try {
                CharSequence title = browser.loadLabel(packages);
                if (title != null && !title.toString().isBlank()) label = title.toString();
            } catch (RuntimeException ignored) { }
            try { icon = browser.loadIcon(packages); } catch (RuntimeException ignored) { }
            result.add(new Target(packageName, label, icon));
        }
        Collator labels = Collator.getInstance();
        result.sort((left, right) -> {
            int order = labels.compare(left.label(), right.label());
            return order != 0 ? order : left.packageName().compareTo(right.packageName());
        });
        return List.copyOf(result);
    }

    /** Recheck both browser status and this URL; constructing the intent never launches it. */
    public static Intent viewIntent(Context context, String url, String packageName) {
        if (!RouteLink.validHttp(url) || !HostPolicy.validTarget(packageName)) return null;
        try {
            if (!candidates(context).containsKey(packageName)) return null;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(packageName);
            ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved != null && available(context, resolved.activityInfo)
                    && packageName.equals(resolved.activityInfo.packageName) ? intent : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    private static LinkedHashMap<String, ResolveInfo> candidates(Context context) {
        PackageManager packages = context.getPackageManager();
        LinkedHashMap<String, ResolveInfo> browsers = new LinkedHashMap<>();
        Intent generic = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            for (ResolveInfo browser : packages.queryIntentActivities(generic,
                    PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER))
                if (available(context, browser.activityInfo) && browser.filter != null
                        && browser.filter.countDataAuthorities() == 0 && browser.filter.countDataPaths() == 0)
                    browsers.putIfAbsent(browser.activityInfo.packageName, browser);
        } catch (RuntimeException ignored) { }
        try {
            Intent selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
            for (ResolveInfo browser : packages.queryIntentActivities(selector, 0))
                if (available(context, browser.activityInfo)) browsers.putIfAbsent(browser.activityInfo.packageName, browser);
        } catch (RuntimeException ignored) { }
        return browsers;
    }

    private static boolean available(Context context, ActivityInfo activity) {
        if (activity == null || activity.applicationInfo == null || activity.name == null
                || !HostPolicy.validTarget(activity.packageName) || !activity.exported || !activity.enabled
                || !activity.applicationInfo.enabled) return false;
        if (activity.permission != null && !activity.permission.isBlank()
                && context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            PackageManager packages = context.getPackageManager();
            ApplicationInfo application = packages.getApplicationInfo(activity.packageName, 0);
            if (!application.enabled || (application.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) return false;
            int component = packages.getComponentEnabledSetting(new ComponentName(activity.packageName, activity.name));
            int app = packages.getApplicationEnabledSetting(activity.packageName);
            return (component == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || component == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    && (app == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || app == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { return false; }
    }
}
