package io.github.yudigaga.taplusbridge.config;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import io.github.yudigaga.taplusbridge.routing.RouteRule;
import io.github.yudigaga.taplusbridge.thaw.ThawEngine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Feature preferences retain their keys; all routing data now lives in the Ice rule repository. */
public final class ConfigStore {
    public static final String SOURCE_SCOPE_CHANGED = "io.github.yudigaga.taplusbridge.SOURCE_SCOPE_CHANGED";
    public static final String SCOPE_CAPTURE_KEY = "scope_capture_fallback";
    public static final String SCOPE_LINK_PROBE_KEY = "scope_link_probe";
    public static final String CLOUD_LINK_HANDOFF_KEY = "cloud_link_handoff";
    private static final Object STORE_LOCK = new Object();
    // Listeners can reenter before commit returns; a failed memory restore retains this accepted view.
    private static final Map<SharedPreferences, Map<String, Object>> ACCEPTED_VIEWS = new WeakHashMap<>();
    private static SharedPreferences committingStore;
    private final SharedPreferences prefs;
    private final Context context;
    public ConfigStore(Context context) {
        this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
        prefs = this.context.getSharedPreferences("config", Context.MODE_PRIVATE);
        synchronized (STORE_LOCK) {
            // A synchronous preference callback may reopen the store before commit() returns.
            if (ACCEPTED_VIEWS.containsKey(prefs)) return;
            boolean oldTarget = prefs.contains("generic_target");
            boolean oldRules = prefs.contains("rules") || prefs.contains("seed_version");
            if (oldTarget || oldRules) save(editor -> {
                // 0.6's global target must never override domain rules; retain service/toggle choices.
                if (oldTarget) editor.remove("generic_target");
                if (oldRules) editor.remove("rules").remove("seed_version").putInt("rule_system", 1);
            }, "旧配置迁移保存失败，请重试");
        }
    }
    /** Complete snapshots and individual active readers share the checked-write boundary. */
    public static <T> T readCommitted(Supplier<T> reader) {
        synchronized (STORE_LOCK) { return reader.get(); }
    }
    public boolean enabled() { return readCommitted(() -> booleanValue("enabled", true)); }
    public void setEnabled(boolean value) { save(editor -> editor.putBoolean("enabled", value), "总开关设置保存失败，请重试"); }
    public boolean nativeEnabled() { return readCommitted(() -> booleanValue("native", true)); }
    public void setNativeEnabled(boolean value) { save(editor -> editor.putBoolean("native", value), "原生链接设置保存失败，请重试"); }
    public boolean cloudLinkHandoffEnabled() {
        return readCommitted(() -> {
            try { return booleanValue(CLOUD_LINK_HANDOFF_KEY, true); }
            catch (RuntimeException malformed) { return false; }
        });
    }
    public void setCloudLinkHandoffEnabled(boolean value) {
        save(editor -> editor.putBoolean(CLOUD_LINK_HANDOFF_KEY, value), "网盘链接适配设置保存失败，请重试");
        context.sendBroadcast(new Intent(SOURCE_SCOPE_CHANGED).setPackage(HostPolicy.HOST_PACKAGE));
    }
    public boolean recognitionEnabled() { return readCommitted(() -> booleanValue("recognition", true)); }
    public void setRecognitionEnabled(boolean value) { save(editor -> editor.putBoolean("recognition", value), "识别增强设置保存失败，请重试"); }
    public boolean lensEnabled() { return readCommitted(() -> booleanValue("google_lens", true)); }
    public void setLensEnabled(boolean value) {
        save(editor -> editor.putBoolean("google_lens", value), "识图设置保存失败，请重试");
    }
    public RecognitionChannels recognitionChannels() {
        return readCommitted(() -> {
            if (!contains(RecognitionChannels.KEY)) return RecognitionChannels.defaults();
            try { return RecognitionChannels.parse(stringValue(RecognitionChannels.KEY, null)); }
            catch (RuntimeException malformed) { return RecognitionChannels.disabled(); }
        });
    }
    public void setRecognitionChannel(RecognitionChannels.Channel channel, boolean enabled) {
        synchronized (STORE_LOCK) {
            RecognitionChannels changed = recognitionChannels().with(channel, enabled);
            save(editor -> editor.putString(RecognitionChannels.KEY, changed.toJson()), "识别来源设置保存失败，请重试");
        }
        context.sendBroadcast(new Intent(SOURCE_SCOPE_CHANGED).setPackage(HostPolicy.HOST_PACKAGE));
    }
    public DragSettings getDragSettings() {
        return readCommitted(() -> {
            try {
                String raw = stringValue(DragSettings.KEY, null);
                return raw == null ? DragSettings.defaults() : DragSettings.parse(raw);
            } catch (RuntimeException malformed) { return DragSettings.defaults(); }
        });
    }
    public void setDragSettings(DragSettings value) {
        if (value == null) throw new IllegalArgumentException("拖拽配置无效，请重新设置");
        save(editor -> editor.putString(DragSettings.KEY, value.toJson()), "拖拽设置保存失败，请重试");
    }
    public void setDragSettings(DragSettings value, boolean linkProbe) {
        if (value == null) throw new IllegalArgumentException("拖拽配置无效，请重新设置");
        save(editor -> editor.putString(DragSettings.KEY, value.toJson())
                .putBoolean(SCOPE_LINK_PROBE_KEY, linkProbe), "拖拽设置保存失败，请重试");
        context.sendBroadcast(new Intent(SOURCE_SCOPE_CHANGED).setPackage(HostPolicy.HOST_PACKAGE));
    }
    public boolean captureEnabled() { return readCommitted(() -> booleanValue("capture", true)); }
    public void setCaptureEnabled(boolean value) { save(editor -> editor.putBoolean("capture", value), "聊天卡片设置保存失败，请重试"); }
    public boolean scopeCaptureEnabled() {
        return readCommitted(() -> {
            try { return booleanValue(SCOPE_CAPTURE_KEY, false); }
            catch (RuntimeException malformed) { return false; }
        });
    }
    public void setScopeCaptureEnabled(boolean value) {
        save(editor -> editor.putBoolean(SCOPE_CAPTURE_KEY, value), "截图兜底设置保存失败，请重试");
        context.sendBroadcast(new Intent(SOURCE_SCOPE_CHANGED).setPackage(HostPolicy.HOST_PACKAGE));
    }
    public boolean scopeLinkProbeEnabled() {
        return readCommitted(() -> {
            try { return booleanValue(SCOPE_LINK_PROBE_KEY, false); }
            catch (RuntimeException malformed) { return false; }
        });
    }
    public void setScopeLinkProbeEnabled(boolean value) {
        save(editor -> editor.putBoolean(SCOPE_LINK_PROBE_KEY, value), "补点设置保存失败，请重试");
        context.sendBroadcast(new Intent(SOURCE_SCOPE_CHANGED).setPackage(HostPolicy.HOST_PACKAGE));
    }
    public SourceScopePolicy sourceScope() {
        return readCommitted(() -> {
            if (!contains(SourceScopePolicy.KEY)) return SourceScopePolicy.unmanaged();
            try { return SourceScopePolicy.parse(stringValue(SourceScopePolicy.KEY, null)); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("source_scope_invalid"); }
        });
    }
    /** One committed JSON value keeps mode and both selections in the same disk transaction. */
    public boolean saveSourceScope(SourceScopePolicy.Mode mode, Collection<String> blacklist,
                                   Collection<String> whitelist) {
        return saveSourceScopeSelection(mode, blacklist, whitelist, null, null);
    }
    public boolean saveSourceScope(SourceScopePolicy.Mode mode, Collection<String> blacklist,
                                   Collection<String> whitelist, boolean fallback) {
        return saveSourceScopeSelection(mode, blacklist, whitelist, fallback, null);
    }
    public boolean saveSourceScope(SourceScopePolicy.Mode mode, Collection<String> blacklist,
                                   Collection<String> whitelist, boolean fallback, boolean linkProbe) {
        return saveSourceScopeSelection(mode, blacklist, whitelist, fallback, linkProbe);
    }
    private boolean saveSourceScopeSelection(SourceScopePolicy.Mode mode, Collection<String> blacklist,
                                            Collection<String> whitelist, Boolean fallback, Boolean linkProbe) {
        if (android.os.Process.myUid() / 100000 != 0)
            throw new IllegalStateException("请在主用户中设置传送门的生效应用");
        String raw = SourceScopePolicy.of(mode, blacklist, whitelist).encode();
        if (!commit(editor -> {
            editor.putString(SourceScopePolicy.KEY, raw);
            // The legacy overload deliberately leaves the existing key untouched.
            if (fallback != null) editor.putBoolean(SCOPE_CAPTURE_KEY, fallback);
            if (linkProbe != null) editor.putBoolean(SCOPE_LINK_PROBE_KEY, linkProbe);
        }, "生效应用设置保存失败，请重试")) return false;
        context.sendBroadcast(new Intent(SOURCE_SCOPE_CHANGED).setPackage(HostPolicy.HOST_PACKAGE));
        return true;
    }
    public ThawEngine.Mode mode() {
        return readCommitted(() -> {
            String value;
            try { value = stringValue("engine", "ROOT"); }
            catch (RuntimeException malformed) { value = "ROOT"; }
            if (!"ROOT".equals(value) && !"SHIZUKU".equals(value)) {
                value = "ROOT";
                try { save(editor -> editor.putString("engine", "ROOT"), "解冻通道设置保存失败，请重试"); }
                catch (IllegalStateException ignored) { /* The safe read fallback does not depend on repairing disk. */ }
            }
            try { return ThawEngine.Mode.valueOf(value); }
            catch (IllegalArgumentException unavailable) { return ThawEngine.Mode.ROOT; }
        });
    }
    public void setMode(ThawEngine.Mode mode) { save(editor -> editor.putString("engine", mode.name()), "解冻通道设置保存失败，请重试"); }

    private void save(Consumer<SharedPreferences.Editor> change, String message) {
        if (!commit(change, message)) throw new IllegalStateException(message);
    }
    private boolean commit(Consumer<SharedPreferences.Editor> change, String message) {
        synchronized (STORE_LOCK) {
            if (committingStore != null) throw new IllegalStateException(message);
            Map<String, Object> accepted = ACCEPTED_VIEWS.get(prefs);
            Map<String, Object> before = copyPreferences(accepted == null ? prefs.getAll() : accepted);
            boolean retainAccepted = accepted != null;
            ACCEPTED_VIEWS.put(prefs, before);
            committingStore = prefs;
            try {
                RuntimeException cause = null;
                try {
                    SharedPreferences.Editor editor = prefs.edit();
                    // A previous restore failure requires replacing the entire rejected memory state.
                    if (accepted != null) {
                        editor.clear();
                        for (var entry : before.entrySet()) putValue(editor, entry.getKey(), entry.getValue());
                    }
                    change.accept(editor);
                    if (editor.commit()) { retainAccepted = false; return true; }
                } catch (RuntimeException failed) { cause = failed; }
                try {
                    SharedPreferences.Editor restore = prefs.edit().clear();
                    for (var entry : before.entrySet()) putValue(restore, entry.getKey(), entry.getValue());
                    // As in RuleRepository, apply() restores memory without claiming a durable save.
                    restore.apply();
                    if (!before.equals(prefs.getAll())) throw new IllegalStateException("memory_restore_mismatch");
                } catch (RuntimeException restoreFailed) {
                    retainAccepted = true;
                    if (cause != null) restoreFailed.addSuppressed(cause);
                    throw new IllegalStateException(message + "；内存恢复未完成，存储状态未确认", restoreFailed);
                }
                if (cause != null) throw new IllegalStateException(message, cause);
                return false;
            } finally {
                committingStore = null;
                if (!retainAccepted) ACCEPTED_VIEWS.remove(prefs);
            }
        }
    }
    private boolean contains(String key) {
        Map<String, Object> accepted = ACCEPTED_VIEWS.get(prefs);
        return accepted == null ? prefs.contains(key) : accepted.containsKey(key);
    }
    private boolean booleanValue(String key, boolean fallback) {
        Map<String, Object> accepted = ACCEPTED_VIEWS.get(prefs);
        return accepted == null ? prefs.getBoolean(key, fallback) : (Boolean) accepted.getOrDefault(key, fallback);
    }
    private String stringValue(String key, String fallback) {
        Map<String, Object> accepted = ACCEPTED_VIEWS.get(prefs);
        return accepted == null ? prefs.getString(key, fallback) : (String) accepted.getOrDefault(key, fallback);
    }
    private static Map<String, Object> copyPreferences(Map<String, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : input.entrySet()) result.put(entry.getKey(),
                entry.getValue() instanceof Set<?> set ? new LinkedHashSet<>(set) : entry.getValue());
        return result;
    }
    @SuppressWarnings("unchecked")
    private static void putValue(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String text) editor.putString(key, text);
        else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
        else if (value instanceof Long number) editor.putLong(key, number);
        else if (value instanceof Integer number) editor.putInt(key, number);
        else if (value instanceof Float number) editor.putFloat(key, number);
        else if (value instanceof Set<?> set) editor.putStringSet(key, (Set<String>) set);
        else throw new IllegalArgumentException("unsupported-preference-type");
    }
    /** Legacy Java projection for existing callers; runtime matching uses complete Ice entries. */
    public List<RouteRule> rules() {
        List<RouteRule> result = new ArrayList<>();
        for (LinkRuleEntry entry : new RuleRepository(context).manualRules()) {
            result.add(new RouteRule(entry.host, entry.targetPackage, entry.sourcePackage, true, entry.hasFixedUrlConversion()));
        }
        return result;
    }
    public void saveRules(List<RouteRule> rules) {
        List<LinkRuleEntry> result = new ArrayList<>();
        for (RouteRule rule : rules) result.add(new LinkRuleEntry(rule.sourcePackage(), rule.host(), rule.targetPackage(),
                LinkRuleEntry.SOURCE_MANUAL, rule.convert() ? LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK : LinkRuleEntry.CONVERSION_NONE, ""));
        new RuleRepository(context).replaceManualRules(result);
    }
}
