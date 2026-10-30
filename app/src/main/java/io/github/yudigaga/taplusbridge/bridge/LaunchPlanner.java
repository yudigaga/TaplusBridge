package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import java.util.Locale;

public final class LaunchPlanner {
    public record Plan(String targetPackage, Intent target, Intent original, String source, String kind) {}
    public record PlanningResult(Plan plan, String reason) {}
    private LaunchPlanner() {}

    public static Plan plan(Context context, Bundle request) {
        return planWithReason(context, request).plan();
    }
    @SuppressWarnings("deprecation")
    public static PlanningResult planWithReason(Context context, Bundle request) {
        ConfigStore config = new ConfigStore(context);
        return planWithRules(context,request,config.enabled(),config.nativeEnabled(),null);
    }
    /** Framework-owned configuration path must never open the frozen module's private preferences. */
    public static PlanningResult planWithRules(Context context,Bundle request,boolean enabled,boolean nativeEnabled,
                    java.util.List<LinkRuleEntry> rules) {
        if (request == null) return new PlanningResult(null, "invalid_request");
        if (!enabled) return new PlanningResult(null, "disabled");
        Intent original = request.getParcelable("original");
        Intent candidate = request.getParcelable("candidate");
        String source = request.getString("source");
        if (source != null && !validPackage(source)) source = null;
        String url = request.getString("url");
        if (url == null && original != null && original.getData() != null) {
            Uri data = original.getData();
            url = "mibrowser".equals(data.getScheme()) ? data.getQueryParameter("web_url") : data.toString();
        }
        if (!safeFallback(original)) return new PlanningResult(null, "invalid_request");
        if (request.containsKey("explicit_target")) return new PlanningResult(null,"invalid_request");
        IceRuleRouting.Decision decision = rules==null?IceRuleRouting.decide(context, url, source):IceRuleRouting.decide(context,url,source,rules);
        if ("ambiguous".equals(decision.reason())) return new PlanningResult(null, "ambiguous");
        LinkRuleEntry rule = decision.rule();
        if (rule != null) {
            // All packages use the same handoff: match the configured host, forward the original URL.
            // Legacy conversion fields remain importable, but no app-specific URI templates execute.
            String destination = url;
            if (safeUri(destination)) {
                Intent target = new Intent(Intent.ACTION_VIEW, Uri.parse(destination))
                        .setPackage(rule.targetPackage)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return new PlanningResult(new Plan(rule.targetPackage, target, original, source, "域名规则"), "resolved");
            }
        }
        // Native targets originate in Taplus's clicked recommendation, never in a public activity extra.
        if (candidate == null) return new PlanningResult(null, rule == null ? decision.reason() : "target_missing");
        if (Intent.ACTION_VIEW.equals(candidate.getAction()) && IntentObservation.validHttpUrl(candidate.getDataString()))
            return new PlanningResult(null, decision.reason());
        if (!nativeEnabled) return new PlanningResult(null, "disabled");
        String targetPackage = candidate.getPackage();
        if (candidate.getComponent() != null) targetPackage = candidate.getComponent().getPackageName();
        if (!validTarget(targetPackage)) return new PlanningResult(null, "invalid_request");
        if (!installed(context, targetPackage)) return new PlanningResult(null, "target_missing");
        String action = candidate.getAction();
        if (Intent.ACTION_MAIN.equals(action)) {
            Intent target = new Intent(Intent.ACTION_MAIN).setPackage(targetPackage)
                    .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return new PlanningResult(new Plan(targetPackage, target, original, source, "原生应用"), "resolved");
        }
        if (!Intent.ACTION_VIEW.equals(action) || candidate.getData() == null
                || !safeUri(candidate.getDataString())) return new PlanningResult(null, "invalid_request");
        // Keep trusted primitive extras in the native Intent, but never delegate grants or selectors.
        Intent target = new Intent(candidate);
        target.setSelector(null); target.setClipData(null);
        target.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        target.setPackage(targetPackage);
        return new PlanningResult(new Plan(targetPackage, target, original, source, "原生链接"), "resolved");
    }
    public static boolean validPackage(String value) {
        return value != null && value.length() < 256 && value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
    private static boolean validTarget(String value) {
        return validPackage(value) && !BridgeContract.APP_PACKAGE.equals(value)
                && !BridgeContract.HOST_PACKAGE.equals(value);
    }
    private static boolean installed(Context context, String value) {
        if (!validTarget(value)) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(value,
                    PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
        } catch (PackageManager.NameNotFoundException e) { return false; }
    }
    /** Enabled/resolvable targets can be opened directly by Taplus, without a trampoline. */
    public static Intent directIfReady(Context context, Plan plan) {
        if (plan == null) return null;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(plan.targetPackage(),
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            if (!info.enabled || (info.flags & ApplicationInfo.FLAG_INSTALLED) == 0
                    || (info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) return null;
            Intent launch = new Intent(plan.target());
            if (Intent.ACTION_MAIN.equals(launch.getAction()))
                launch = context.getPackageManager().getLaunchIntentForPackage(plan.targetPackage());
            if (launch == null || context.getPackageManager().resolveActivity(launch, 0) == null) return null;
            return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) { return null; }
    }
    static boolean safeUri(String value) {
        if (value == null || value.length() > 32768 || value.chars().anyMatch(c -> c < 32 || c == 127)) return false;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "intent", "file", "content", "javascript", "data" -> false;
            default -> true;
        };
    }
    private static boolean safeFallback(Intent intent) {
        if (intent == null) return true;
        if (intent.getSelector() != null || intent.getClipData() != null) return false;
        if (BridgeContract.APP_PACKAGE.equals(intent.getPackage()) || (intent.getComponent() != null
                && BridgeContract.APP_PACKAGE.equals(intent.getComponent().getPackageName()))) return false;
        return Intent.ACTION_MAIN.equals(intent.getAction()) || (Intent.ACTION_VIEW.equals(intent.getAction())
                && safeUri(intent.getDataString()));
    }
}
