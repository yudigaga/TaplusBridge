package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.recognition.RouteLink;
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import io.github.yudigaga.taplusbridge.rules.ScopedRuleResolver;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** One installed-target projection shared by real launches and the settings preview. */
public final class IceRuleRouting {
    public record Decision(LinkRuleEntry rule, String reason) { }
    private static final Map<PackageManager, InstalledTargetCache> TARGET_CACHES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicBoolean INVALIDATION_REGISTERED = new AtomicBoolean();
    private IceRuleRouting() { }
    public static Decision decide(Context context, String url, String source) {
        return decide(context,url,source,new RuleRepository(context).effectiveRules());
    }
    public static Decision decide(Context context,String url,String source,List<LinkRuleEntry> effective) {
        String host = RouteLink.ruleKey(url);
        if (host == null) return new Decision(null, "invalid_request");
        registerPackageInvalidationReceiver(context, null);
        PackageManager pm = context.getPackageManager();
        return decideForHost(effective, source, host, target -> installed(pm, target));
    }

    static Decision decideForHost(List<LinkRuleEntry> effective, String source, String host,
                                  Predicate<String> installedLookup) {
        List<LinkRuleEntry> candidates = ScopedRuleResolver.matchingRules(effective, source, host);
        if (candidates.isEmpty()) return new Decision(null, "no_match");
        Set<String> inspected = new LinkedHashSet<>(), installed = new LinkedHashSet<>();
        for (LinkRuleEntry entry : candidates) if (inspected.add(entry.targetPackage)
                && !BridgeContract.APP_PACKAGE.equals(entry.targetPackage)
                && !BridgeContract.HOST_PACKAGE.equals(entry.targetPackage)
                && isInstalled(installedLookup, entry.targetPackage)) installed.add(entry.targetPackage);
        List<LinkRuleEntry> matches = ScopedRuleResolver.filterInstalled(candidates, installed);
        if (ScopedRuleResolver.isAmbiguous(matches, installed)) return new Decision(null, "ambiguous");
        LinkRuleEntry selected = ScopedRuleResolver.selectRule(matches, installed);
        if (selected != null) return new Decision(selected, "resolved");
        return new Decision(null, "target_missing");
    }

    public static void registerPackageInvalidationReceiver(Context context, Handler handler) {
        if (context == null || !INVALIDATION_REGISTERED.compareAndSet(false, true)) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                Uri data = intent == null ? null : intent.getData();
                invalidateInstalledTarget(data == null ? null : data.getSchemeSpecificPart());
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter, null, handler);
        } catch (RuntimeException unavailable) {
            INVALIDATION_REGISTERED.set(false);
        }
    }

    public static void invalidateInstalledTarget(String packageName) {
        synchronized (TARGET_CACHES) {
            for (InstalledTargetCache cache : TARGET_CACHES.values()) cache.invalidate(packageName);
        }
    }

    private static boolean installed(PackageManager pm, String packageName) {
        InstalledTargetCache cache;
        synchronized (TARGET_CACHES) {
            cache = TARGET_CACHES.computeIfAbsent(pm, ignored -> new InstalledTargetCache());
        }
        return cache.isInstalled(packageName, SystemClock.elapsedRealtimeNanos(), () -> {
            try {
                ApplicationInfo app = pm.getApplicationInfo(packageName,
                        PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                return (app.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
            } catch (PackageManager.NameNotFoundException missing) {
                return false;
            }
        });
    }

    private static boolean isInstalled(Predicate<String> lookup, String packageName) {
        try { return lookup.test(packageName); }
        catch (RuntimeException unavailable) { return false; }
    }
}
