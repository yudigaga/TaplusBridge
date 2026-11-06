package io.github.yudigaga.taplusbridge.translation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Discover only advertised text handlers and recheck the exact saved component before dispatch. */
public final class TranslationApps {
    public static final String ACTION_TRANSLATE = "android.intent.action.TRANSLATE";
    private static final List<String> ACTIONS = List.of(ACTION_TRANSLATE, Intent.ACTION_PROCESS_TEXT, Intent.ACTION_SEND);
    public record Target(String component, String action, String label, Drawable icon) {}
    private TranslationApps() {}

    public static List<Target> installed(Context context) {
        PackageManager packages = context.getPackageManager();
        LinkedHashMap<String, Target> targets = new LinkedHashMap<>();
        for (String action : ACTIONS) {
            List<ResolveInfo> matches;
            try { matches = packages.queryIntentActivities(probe(action), PackageManager.MATCH_DEFAULT_ONLY); }
            catch (RuntimeException unavailable) { continue; }
            for (ResolveInfo match : matches) {
                ActivityInfo activity = match.activityInfo;
                if (!available(context, activity)) continue;
                ComponentName component = new ComponentName(activity.packageName, activity.name);
                String label;
                Drawable icon = null;
                try { label = String.valueOf(match.loadLabel(packages)); }
                catch (RuntimeException unavailable) { label = activity.packageName; }
                if (label.isBlank()) label = activity.packageName;
                try { icon = match.loadIcon(packages); } catch (RuntimeException ignored) { }
                targets.putIfAbsent(component.flattenToString() + '\n' + action,
                        new Target(component.flattenToString(), action, label + " · " + kind(action), icon));
            }
        }
        ArrayList<Target> result = new ArrayList<>(targets.values());
        Collator collator = Collator.getInstance();
        result.sort((left, right) -> {
            int priority = Integer.compare(ACTIONS.indexOf(left.action()), ACTIONS.indexOf(right.action()));
            if (priority != 0) return priority;
            int label = collator.compare(left.label(), right.label());
            return label != 0 ? label : left.component().compareTo(right.component());
        });
        return List.copyOf(result);
    }

    public static Intent intent(Context context, TranslationSettings settings, String text) {
        settings.validate();
        if (settings.mode != TranslationSettings.Mode.APP || text == null || text.isBlank() || text.length() > 32768)
            throw new IllegalArgumentException("请选择可用文字与翻译应用");
        boolean found = false;
        for (ResolveInfo item : context.getPackageManager().queryIntentActivities(probe(settings.appAction), PackageManager.MATCH_DEFAULT_ONLY)) {
            ActivityInfo activity = item.activityInfo;
            if (available(context, activity) && new ComponentName(activity.packageName, activity.name).flattenToString().equals(settings.appComponent)) {
                found = true; break;
            }
        }
        if (!found) throw new IllegalArgumentException("所选翻译应用当前不可用，请重新选择");
        Intent intent = probe(settings.appAction).setComponent(ComponentName.unflattenFromString(settings.appComponent));
        if (Intent.ACTION_PROCESS_TEXT.equals(settings.appAction)) {
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT, text);
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        } else intent.putExtra(Intent.EXTRA_TEXT, text);
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static Intent probe(String action) {
        Intent request = new Intent(action);
        return ACTION_TRANSLATE.equals(action) ? request : request.setType("text/plain");
    }
    private static String kind(String action) {
        return ACTION_TRANSLATE.equals(action) ? "专用翻译" : Intent.ACTION_PROCESS_TEXT.equals(action) ? "文字处理" : "分享兼容";
    }
    private static boolean available(Context context, ActivityInfo activity) {
        if (activity == null || activity.applicationInfo == null || activity.packageName == null || activity.name == null
                || !activity.exported || !activity.enabled || !activity.applicationInfo.enabled
                || BridgeContract.APP_PACKAGE.equals(activity.packageName) || BridgeContract.HOST_PACKAGE.equals(activity.packageName)) return false;
        if ((activity.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0) return false;
        if (activity.permission != null && !activity.permission.isBlank()
                && context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            PackageManager packages = context.getPackageManager();
            int component = packages.getComponentEnabledSetting(new ComponentName(activity.packageName, activity.name));
            int application = packages.getApplicationEnabledSetting(activity.packageName);
            return (component == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || component == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    && (application == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || application == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        } catch (RuntimeException unavailable) { return false; }
    }
}
