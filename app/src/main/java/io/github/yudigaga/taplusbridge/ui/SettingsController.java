package io.github.yudigaga.taplusbridge.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.BuildConfig;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.bridge.IceRuleRouting;
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner;
import io.github.yudigaga.taplusbridge.capture.CaptureBroker;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.routing.RouteRule;
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import io.github.yudigaga.taplusbridge.rules.RuleConfigTransfer;
import io.github.yudigaga.taplusbridge.runtime.RuntimePublisher;
import io.github.yudigaga.taplusbridge.thaw.ThawEngine;
import io.github.yudigaga.taplusbridge.thaw.ShizukuAccess;
import java.lang.ref.WeakReference;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** UI-only adapter. Keeps stored configuration and routing semantics independent of Compose. */
public final class SettingsController implements AutoCloseable {
    public static final int SHIZUKU_PERMISSION_REQUEST = 21;
    private static final int APP_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES;
    private final Context context;
    private final ConfigStore config;
    private final RuleRepository ruleRepository;
    private final WeakReference<Activity> activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final Supplier<ThawEngine.Result> rootProbe;
    private final Supplier<RuleRepository.RefreshResult> onlineRefresh;
    private final ShizukuAuthorization shizukuAuthorization;
    private final Runnable authorizationChanged = this::dispatchAuthorizationRefresh;
    private Runnable authorizationRefresh;
    private boolean observingAuthorization;
    private final Object lifecycleLock = new Object();
    private final List<Future<?>> pending = new ArrayList<>();
    private boolean closed;
    private long generation;
    private long rootGeneration;
    private long appsGeneration;
    private long onlineGeneration;
    private long authorizationGeneration;

    interface ShizukuAuthorization {
        boolean granted();
        String status();
        void request(int requestCode);
        void observe(Runnable listener);
        void unobserve(Runnable listener);
    }
    private static final ShizukuAuthorization SHIZUKU = new ShizukuAuthorization() {
        public boolean granted() { return ThawEngine.shizukuGranted(); }
        public String status() { return ThawEngine.shizukuStatus(); }
        public void request(int requestCode) { ThawEngine.requestShizukuPermission(requestCode); }
        public void observe(Runnable listener) { ShizukuAccess.addPermissionListener(listener); }
        public void unobserve(Runnable listener) { ShizukuAccess.removePermissionListener(listener); }
    };

    public SettingsController(Activity activity) {
        this(activity, Executors.newSingleThreadExecutor(), ThawEngine::probeRoot);
    }

    /** Controlled worker seam for lifecycle tests; the production constructor uses real Root checks. */
    SettingsController(Activity activity, ExecutorService worker, Supplier<ThawEngine.Result> rootProbe) {
        this(activity, worker, rootProbe, null);
    }
    SettingsController(Activity activity, ExecutorService worker, Supplier<ThawEngine.Result> rootProbe,
                       Supplier<RuleRepository.RefreshResult> onlineRefresh) {
        this(activity, worker, rootProbe, onlineRefresh, SHIZUKU);
    }
    SettingsController(Activity activity, ExecutorService worker, Supplier<ThawEngine.Result> rootProbe,
                       Supplier<RuleRepository.RefreshResult> onlineRefresh, ShizukuAuthorization shizukuAuthorization) {
        Activity owner = Objects.requireNonNull(activity, "activity");
        Context application = owner.getApplicationContext();
        context = application == null ? owner : application;
        config = new ConfigStore(context);
        ruleRepository = new RuleRepository(context);
        this.activity = new WeakReference<>(owner);
        this.worker = Objects.requireNonNull(worker, "worker");
        this.rootProbe = Objects.requireNonNull(rootProbe, "rootProbe");
        this.onlineRefresh = onlineRefresh == null ? () -> ruleRepository.refreshOnline(this::isClosed) : onlineRefresh;
        this.shizukuAuthorization = Objects.requireNonNull(shizukuAuthorization, "shizukuAuthorization");
    }

    /** Immutable display state; hostSupported means inspected version, not LSPosed activation. */
    public static final class Snapshot {
        public final boolean enabled, nativeEnabled, recognitionEnabled, captureEnabled, lensEnabled, cloudLinkHandoffEnabled;
        public final ThawEngine.Mode mode;
        public final boolean hostInstalled, hostSupported, shizukuGranted;
        public final String hostVersionName, hostVersion, shizukuStatus, versionName;
        public final long hostVersionCode;
        public final int versionCode;
        public final DragSettings dragSettings;
        public final RecognitionChannels recognitionChannels;
        private Snapshot(ConfigStore config, boolean hostInstalled, boolean hostSupported,
                         String hostVersionName, long hostVersionCode, boolean shizukuGranted, String shizukuStatus) {
            enabled = config.enabled(); nativeEnabled = config.nativeEnabled();
            recognitionEnabled = config.recognitionEnabled(); captureEnabled = config.captureEnabled();
            lensEnabled = config.lensEnabled();
            cloudLinkHandoffEnabled = config.cloudLinkHandoffEnabled();
            dragSettings = config.getDragSettings();
            recognitionChannels = config.recognitionChannels();
            mode = config.mode(); this.hostInstalled = hostInstalled; this.hostSupported = hostSupported;
            this.hostVersionName = hostVersionName; this.hostVersionCode = hostVersionCode;
            hostVersion = hostInstalled ? hostVersionName + " / " + hostVersionCode : "未安装传送门";
            this.shizukuGranted = shizukuGranted; this.shizukuStatus = shizukuStatus;
            versionName = BuildConfig.VERSION_NAME; versionCode = BuildConfig.VERSION_CODE;
        }
    }

    public Snapshot snapshot() {
        boolean installed = false, supported = false;
        String hostVersion = "未知版本"; long hostCode = 0;
        try {
            PackageInfo host = context.getPackageManager().getPackageInfo(BridgeContract.HOST_PACKAGE, 0);
            installed = true; hostVersion = host.versionName == null ? "未知版本" : host.versionName;
            hostCode = host.getLongVersionCode(); supported = HostPolicy.supportedVersion(hostVersion, hostCode);
        } catch (PackageManager.NameNotFoundException ignored) { }
        return new Snapshot(config, installed, supported, hostVersion, hostCode,
                shizukuAuthorization.granted(), shizukuAuthorization.status());
    }

    public void setCloudLinkHandoffEnabled(boolean enabled) {
        requireOpen(); config.setCloudLinkHandoffEnabled(enabled);
        RuntimePublisher.publishSavedSettings(context);
    }

    /** Compatibility projection for the initial UI draft; runtime routing uses full Ice entries. */
    public List<RouteRule> rules() {
        List<RouteRule> projected = new ArrayList<>();
        for (LinkRuleEntry entry : ruleRepository.manualRules()) {
            try { projected.add(new RouteRule(entry.host, entry.targetPackage, entry.sourcePackage, true, false)); }
            catch (IllegalArgumentException unavailableToLegacyModel) { }
        }
        return projected;
    }
    public List<LinkRuleEntry> ruleEntries() { return ruleRepository.allRules(); }
    public List<LinkRuleEntry> manualRuleEntries() { return ruleRepository.manualRules(); }
    public Set<String> disabledTargets() { return ruleRepository.disabledTargets(); }
    public void setTargetEnabled(String target, boolean enabled) { requireOpen(); ruleRepository.setTargetEnabled(target, enabled); }
    public RuleRepository.OnlineState onlineRulesState() { return ruleRepository.onlineState(); }
    public void setOnlineUpdatesEnabled(boolean enabled) { requireOpen(); ruleRepository.setOnlineUpdatesEnabled(enabled); }
    public void refreshOnlineRules(Consumer<RuleRepository.RefreshResult> callback) {
        Objects.requireNonNull(callback, "callback");
        final long request;
        synchronized (lifecycleLock) { requireOpenLocked(); request = ++onlineGeneration; }
        submit(() -> {
            try { return onlineRefresh.get(); }
            catch (RuntimeException failed) { return ruleRepository.failedRefresh("在线规则更新失败，保存状态未确认，请重新尝试"); }
        }, result -> {
            synchronized (lifecycleLock) { if (request != onlineGeneration) return; }
            callback.accept(result);
        });
    }
    public void saveIceRule(int manualIndex, String source, String host, String target, String conversion, String note) {
        requireOpen();
        if (HostPolicy.MODULE_PACKAGE.equals(clean(target)) || HostPolicy.HOST_PACKAGE.equals(clean(target)))
            throw new IllegalArgumentException("不能将传送门或模块自身作为目标应用");
        try { ruleRepository.saveManualRule(manualIndex, source, host, target, LinkRuleEntry.CONVERSION_NONE, note); }
        catch (IllegalArgumentException invalid) {
            String reason = invalid.getMessage();
            if (reason != null && (reason.startsWith("too-many") || reason.contains("limit")))
                throw new IllegalArgumentException("规则超过数量限制：最多768条、96个不同域名，单组最多20个域名");
            throw new IllegalArgumentException("规则无效，请检查来源包名、域名、目标包名及说明长度");
        }
    }
    public void deleteIceRule(int manualIndex) { requireOpen(); ruleRepository.deleteManualRule(manualIndex); }
    public void clearManualRules() { requireOpen(); ruleRepository.clearManualRules(); }
    public String exportRules() { return ruleRepository.exportManualRules(); }
    public int importRules(String json) throws RuleConfigTransfer.TransferException {
        requireOpen();
        try { return ruleRepository.importManualRules(json); }
        catch (IllegalArgumentException invalid) { throw new RuleConfigTransfer.TransferException("合并后的规则无效或超过768条、96个域名限制", invalid); }
    }

    public void saveRule(int index, String host, String target, String source, boolean subdomains, boolean convert) {
        requireOpen();
        String domain = clean(host), destination = clean(target), origin = clean(source);
        if (domain.isEmpty()) throw new IllegalArgumentException("请填写链接域名");
        if (domain.contains("://") || domain.indexOf('/') >= 0) throw new IllegalArgumentException("只填写域名，不包含协议或路径");
        if (!RouteRule.validPackage(destination)) throw new IllegalArgumentException("请填写有效的目标应用包名");
        if (!HostPolicy.validTarget(destination)) throw new IllegalArgumentException("不能将传送门或模块自身作为目标应用");
        if (origin.isEmpty()) origin = "*";
        if (!"*".equals(origin) && !RouteRule.validPackage(origin)) throw new IllegalArgumentException("来源应为应用包名或 *");
        // Ice rules deliberately match the domain and its subdomains; the legacy checkbox no longer defines a separate rule type.
        ruleRepository.saveManualRule(index, origin, domain, destination,
                LinkRuleEntry.CONVERSION_NONE, "");
    }
    public void deleteRule(int index) {
        requireOpen(); ruleRepository.deleteManualRule(index);
    }
    public void restoreDefaults() { requireOpen(); ruleRepository.restoreDefaults(); }

    public SourceScopePolicy sourceScope() {
        try { return config.sourceScope(); }
        catch (IllegalArgumentException malformed) {
            // Only a UI draft: the invalid saved value remains rejected by host/runtime readers.
            return SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST, List.of(), List.of());
        }
    }
    public String sourceScopeError() {
        try { config.sourceScope(); return null; }
        catch (IllegalArgumentException malformed) { return "应用范围配置损坏，请重新选择名单并保存"; }
    }
    public boolean scopeCaptureEnabled() { return config.scopeCaptureEnabled(); }
    public void setScopeCaptureEnabled(boolean enabled) {
        requireOpen(); config.setScopeCaptureEnabled(enabled);
        RuntimePublisher.publishSavedSettings(context);
    }
    public RecognitionChannels recognitionChannels() { return config.recognitionChannels(); }
    public void setRecognitionChannel(RecognitionChannels.Channel channel, boolean enabled) {
        requireOpen(); config.setRecognitionChannel(channel, enabled);
        RuntimePublisher.publishSavedSettings(context);
    }
    public boolean scopeLinkProbeEnabled() { return config.scopeLinkProbeEnabled(); }
    public void setScopeLinkProbeEnabled(boolean enabled) {
        requireOpen(); config.setScopeLinkProbeEnabled(enabled);
        if (!enabled) CaptureBroker.cancelProbe(context);
        RuntimePublisher.publishSavedSettings(context);
    }
    public void saveSourceScope(SourceScopePolicy.Mode mode, Collection<String> blacklist,
                                Collection<String> whitelist) {
        requireOpen();
        if (!config.saveSourceScope(Objects.requireNonNull(mode, "mode"), blacklist, whitelist))
            throw new IllegalStateException("应用范围保存失败，请重试");
        CaptureBroker.cancelCurrent(context);
    }
    public void saveSourceScope(SourceScopePolicy.Mode mode, Collection<String> blacklist,
                                Collection<String> whitelist, boolean fallback) {
        requireOpen();
        if (!config.saveSourceScope(Objects.requireNonNull(mode, "mode"), blacklist, whitelist, fallback))
            throw new IllegalStateException("应用范围保存失败，请重试");
        CaptureBroker.cancelCurrent(context);
    }
    public void saveSourceScope(SourceScopePolicy.Mode mode, Collection<String> blacklist,
                                Collection<String> whitelist, boolean fallback, boolean linkProbe) {
        requireOpen();
        if (!config.saveSourceScope(Objects.requireNonNull(mode, "mode"), blacklist, whitelist, fallback, linkProbe))
            throw new IllegalStateException("应用范围保存失败，请重试");
        CaptureBroker.cancelCurrent(context);
    }

    public void setEnabled(boolean enabled) {
        requireOpen(); config.setEnabled(enabled); if (!enabled) CaptureBroker.cancelCurrent(context);
    }
    public void setNativeEnabled(boolean enabled) { requireOpen(); config.setNativeEnabled(enabled); }
    public void setLensEnabled(boolean enabled) {
        requireOpen(); config.setLensEnabled(enabled);
        RuntimePublisher.publishSavedSettings(context);
    }
    public DragSettings getDragSettings() { return config.getDragSettings(); }
    public void setDragSettings(DragSettings settings) { requireOpen(); config.setDragSettings(settings); }
    public void setDragSettings(DragSettings settings, boolean linkProbe) {
        requireOpen(); config.setDragSettings(settings, linkProbe);
        if (!linkProbe) CaptureBroker.cancelProbe(context);
    }
    public void setRecognitionEnabled(boolean enabled) {
        requireOpen(); config.setRecognitionEnabled(enabled); if (!enabled) CaptureBroker.cancelCurrent(context);
    }
    public void setCaptureEnabled(boolean enabled) {
        requireOpen(); config.setCaptureEnabled(enabled); if (!enabled) CaptureBroker.cancelCurrent(context);
    }
    public void setMode(ThawEngine.Mode mode) { requireOpen(); config.setMode(Objects.requireNonNull(mode, "mode")); }
    public void requestShizukuPermission() {
        requireOpen();
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("授权应从页面主线程发起");
        Activity owner = activity.get();
        if (owner == null || owner.isFinishing() || owner.isDestroyed()) throw new IllegalStateException("页面已关闭");
        shizukuAuthorization.request(SHIZUKU_PERMISSION_REQUEST);
    }

    /** Main-thread observer; permission and binder events never request authorization themselves. */
    public void setAuthorizationRefresh(Runnable refresh) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("授权状态应从页面主线程订阅");
        boolean add = false, remove = false;
        synchronized (lifecycleLock) {
            if (closed && refresh == null) return;
            requireOpenLocked(); authorizationRefresh = refresh; authorizationGeneration++;
            if (refresh != null && !observingAuthorization) { observingAuthorization = true; add = true; }
            if (refresh == null && observingAuthorization) { observingAuthorization = false; remove = true; }
        }
        if (add) shizukuAuthorization.observe(authorizationChanged);
        if (remove) shizukuAuthorization.unobserve(authorizationChanged);
    }
    private void dispatchAuthorizationRefresh() {
        final long accepted;
        synchronized (lifecycleLock) { if (closed || !observingAuthorization) return; accepted = authorizationGeneration; }
        main.post(() -> {
            Runnable refresh;
            synchronized (lifecycleLock) {
                if (closed || accepted != authorizationGeneration) return;
                refresh = authorizationRefresh;
            }
            Activity owner = activity.get();
            if (refresh != null && owner != null && !owner.isFinishing() && !owner.isDestroyed()) refresh.run();
        });
    }

    public static final class AppEntry {
        public final String label, packageName;
        public final Drawable icon;
        public final boolean enabled;
        private AppEntry(String label, String packageName, Drawable icon, boolean enabled) {
            this.label = label; this.packageName = packageName; this.icon = icon; this.enabled = enabled;
        }
    }

    public static final class ShareTarget {
        public final String component, label, packageName;
        public final Drawable icon;
        private ShareTarget(String component, String label, String packageName, Drawable icon) {
            this.component = component; this.label = label; this.packageName = packageName; this.icon = icon;
        }
    }

    /** Combine text and image destinations; the actual share filters these again by payload type. */
    public List<ShareTarget> installedShareTargets() {
        PackageManager pm = context.getPackageManager();
        LinkedHashMap<String, ShareTarget> targets = new LinkedHashMap<>();
        for (String mime : List.of("text/plain", "image/*")) {
            List<ResolveInfo> resolved;
            try { resolved = pm.queryIntentActivities(new Intent(Intent.ACTION_SEND).setType(mime),
                    PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_DISABLED_COMPONENTS); }
            catch (RuntimeException unavailable) { continue; }
            for (ResolveInfo item : resolved) {
                if (item.activityInfo == null || !item.activityInfo.exported
                        || !HostPolicy.validTarget(item.activityInfo.packageName)) continue;
                String component = new ComponentName(item.activityInfo.packageName, item.activityInfo.name).flattenToString();
                String label; Drawable icon = null;
                try { label = item.loadLabel(pm).toString(); } catch (RuntimeException unavailable) { label = item.activityInfo.packageName; }
                try { icon = item.loadIcon(pm); } catch (RuntimeException unavailable) { }
                targets.putIfAbsent(component, new ShareTarget(component, label, item.activityInfo.packageName, icon));
            }
        }
        List<ShareTarget> result = new ArrayList<>(targets.values());
        Collator collator = Collator.getInstance();
        result.sort((a, b) -> { int label = collator.compare(a.label, b.label); return label == 0 ? a.component.compareTo(b.component) : label; });
        return result;
    }
    public void loadShareTargets(Consumer<List<ShareTarget>> callback) {
        Objects.requireNonNull(callback, "callback"); submit(this::installedShareTargets, callback);
    }

    /** Call off the UI thread for a full installed-app picker. Includes installed disabled/hidden apps. */
    public List<AppEntry> installedApps() {
        PackageManager pm = context.getPackageManager();
        List<AppEntry> result = new ArrayList<>();
        List<ApplicationInfo> applications;
        try { applications = pm.getInstalledApplications(APP_FLAGS); }
        catch (RuntimeException unavailable) { return result; }
        for (ApplicationInfo app : applications) {
            if ((app.flags & ApplicationInfo.FLAG_INSTALLED) == 0) continue;
            String label;
            try { label = pm.getApplicationLabel(app).toString(); }
            catch (RuntimeException unavailable) { label = app.packageName; }
            Drawable icon = null;
            try { icon = pm.getApplicationIcon(app); } catch (RuntimeException unavailable) { }
            result.add(new AppEntry(label, app.packageName, icon, app.enabled));
        }
        Collator collator = Collator.getInstance();
        result.sort((left, right) -> {
            int byLabel = collator.compare(left.label, right.label);
            return byLabel == 0 ? left.packageName.compareTo(right.packageName) : byLabel;
        });
        return result;
    }

    public String appLabel(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "未选择应用";
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, APP_FLAGS)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException missing) { return packageName + " · 未安装"; }
    }

    public enum PreviewStatus { MATCHED, NO_MATCH, AMBIGUOUS, INVALID }
    public static final class Preview {
        public final PreviewStatus status;
        public final String title, detail, targetPackage, convertedUrl;
        public final RouteRule rule;
        public final LinkRuleEntry entry;
        private Preview(PreviewStatus status, String title, String detail, LinkRuleEntry entry, String convertedUrl) {
            this(status, title, detail, entry, entry == null ? null : entry.targetPackage, convertedUrl);
        }
        private Preview(PreviewStatus status, String title, String detail, LinkRuleEntry entry, String targetPackage, String convertedUrl) {
            this.status = status; this.title = title; this.detail = detail; this.entry = entry;
            RouteRule projection = null;
            if (entry != null) try { projection = new RouteRule(entry.host, entry.targetPackage, entry.sourcePackage, true, false); }
            catch (IllegalArgumentException unavailableToLegacyModel) { }
            rule = projection; this.targetPackage = targetPackage; this.convertedUrl = convertedUrl;
        }
    }
    /** Pure preview: neither opens a page nor thaws an application. */
    public Preview matchPreview(String url, String source) {
        String value = clean(url), origin = clean(source);
        if (!io.github.yudigaga.taplusbridge.recognition.RouteLink.valid(value)) return new Preview(PreviewStatus.INVALID,
                "链接格式无效", "请输入完整的网页、磁力或 ed2k 文件链接", null, null);
        if (!origin.isEmpty() && !"*".equals(origin) && !RouteRule.validPackage(origin)) return new Preview(PreviewStatus.INVALID,
                "来源包名无效", "来源可留空，或填写应用包名", null, null);
        String normalizedSource = origin.isEmpty() || "*".equals(origin) ? null : origin;
        Bundle request = new Bundle(); request.putString("url", value); request.putString("source", normalizedSource);
        LaunchPlanner.PlanningResult planned = LaunchPlanner.planWithReason(context, request);
        if ("ambiguous".equals(planned.reason())) return new Preview(PreviewStatus.AMBIGUOUS,
                "存在多个已安装目标", "同优先级有多个已安装目标应用，请通过应用开关或手动规则选择；无感识别不会自动打开", null, null);
        LaunchPlanner.Plan plan = planned.plan();
        if (plan == null) return new Preview("invalid_request".equals(planned.reason()) ? PreviewStatus.INVALID : PreviewStatus.NO_MATCH, "未匹配到可用规则",
                ("disabled".equals(planned.reason()) ? "请先开启应用流转" : "target_missing".equals(planned.reason()) ? "候选应用未安装在当前用户中" : "当前来源和链接没有启用规则")
                        + "；普通识别保留原打开方式，无感识别结束，不回放原网页", null, null);
        LinkRuleEntry rule = IceRuleRouting.decide(context, value, normalizedSource).rule();
        if (rule != null && !plan.targetPackage().equals(rule.targetPackage)) rule = null;
        String handoff = value.equals(plan.target().getDataString()) ? "链接保持原样，不改写地址"
                : io.github.yudigaga.taplusbridge.recognition.RouteLink.protocolRule(
                        io.github.yudigaga.taplusbridge.recognition.RouteLink.ruleKey(value))
                        ? "仅规范协议头，文件名、哈希和参数保持原样" : "使用已适配的应用入口，保留对应分享信息；部分网盘在应用内网页打开";
        return new Preview(PreviewStatus.MATCHED, "已匹配到" + appLabel(plan.targetPackage()),
                "目标包名：" + plan.targetPackage() + (rule == null ? "" : "\n匹配条件：" + rule.host
                        + "\n来源条件：" + ("*".equals(rule.sourcePackage) ? "所有应用" : appLabel(rule.sourcePackage))
                        + "\n规则来源：" + (LinkRuleEntry.SOURCE_MANUAL.equals(rule.source) ? "手动" : "在线"))
                        + "\n" + handoff + "\n预览不会解冻或打开应用", rule, plan.targetPackage(), plan.target().getDataString());
    }

    public void loadInstalledApps(Consumer<List<AppEntry>> callback) {
        Objects.requireNonNull(callback, "callback");
        final long request;
        synchronized (lifecycleLock) { requireOpenLocked(); request = ++appsGeneration; }
        submit(() -> installedApps(), value -> {
            synchronized (lifecycleLock) { if (request != appsGeneration) return; }
            callback.accept(value);
        });
    }
    public void probeRoot(Consumer<ThawEngine.Result> callback) {
        Objects.requireNonNull(callback, "callback");
        final long request;
        synchronized (lifecycleLock) { requireOpenLocked(); request = ++rootGeneration; }
        submit(rootProbe, value -> {
            synchronized (lifecycleLock) { if (request != rootGeneration) return; }
            callback.accept(value);
        });
    }
    private <T> void submit(Supplier<T> task, Consumer<T> callback) {
        synchronized (lifecycleLock) {
            requireOpenLocked();
            long acceptedGeneration = generation;
            pending.removeIf(Future::isDone);
            try {
                pending.add(worker.submit(() -> {
                    T value = task.get();
                    main.post(() -> {
                        synchronized (lifecycleLock) { if (closed || generation != acceptedGeneration) return; }
                        Activity owner = activity.get();
                        if (owner != null && !owner.isFinishing() && !owner.isDestroyed()) callback.accept(value);
                    });
                }));
            } catch (RejectedExecutionException unavailable) { throw new IllegalStateException("页面任务已结束，请重新打开页面"); }
        }
    }
    @Override public void close() {
        boolean removeAuthorization;
        synchronized (lifecycleLock) {
            if (closed) return; closed = true; generation++; rootGeneration++; appsGeneration++; onlineGeneration++;
            authorizationGeneration++; authorizationRefresh = null;
            removeAuthorization = observingAuthorization; observingAuthorization = false;
            for (Future<?> future : pending) future.cancel(true);
            pending.clear(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); activity.clear();
        }
        if (removeAuthorization) {
            if (Looper.myLooper() == Looper.getMainLooper()) shizukuAuthorization.unobserve(authorizationChanged);
            else main.post(() -> shizukuAuthorization.unobserve(authorizationChanged));
        }
    }
    private void requireOpen() { synchronized (lifecycleLock) { requireOpenLocked(); } }
    private boolean isClosed() { synchronized (lifecycleLock) { return closed || Thread.currentThread().isInterrupted(); } }
    private void requireOpenLocked() { if (closed) throw new IllegalStateException("页面已关闭"); }
    private static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) throw new IllegalArgumentException("规则已变化，请刷新后重试");
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
