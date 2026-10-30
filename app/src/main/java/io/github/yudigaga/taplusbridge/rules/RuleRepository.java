package io.github.yudigaga.taplusbridge.rules;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Manual data and validated owned online snapshots; never reads the removed Taplus rule store. */
public final class RuleRepository {
    public static final String PREFS = "icebridge_rules_v1";
    private static final Object STORE_LOCK = new Object(), REFRESH_LOCK = new Object();
    private static final String INITIALIZED = "initialized", MANUAL = "manual", DISABLED = "disabled_targets";
    private static final String PRIMARY = "online_primary", PRIMARY_HASH = "online_primary_sha256";
    private static final String BACKUP = "online_backup", BACKUP_HASH = "online_backup_sha256";
    private static final String HIGHEST = "highest_revision", HIGHEST_HASH = "highest_sha256";
    private static final String WITHDRAWN = "highest_withdrawn", ENABLED = "online_updates_enabled";
    private static final String UPDATED = "online_updated_at", STATUS = "online_status", SOURCE = "online_source";
    private static final String STORAGE_FAILURE = "storage_failure";
    private static final String SEED_FAILURE_STATUS = "离线规则基线不可用；未恢复旧规则，可手动刷新";
    private static final String FIRST_COMMIT_FAILURE_STATUS = "规则保存失败：已恢复内存配置，磁盘写入与恢复均未确认；在线最高版本保护保留";
    // XML normalizes raw CR/CRLF in text nodes on a cold parse. Hashes authenticate the downloaded
    // UTF-8 bytes, so snapshot strings must survive XML without newline normalization.
    private static final String SNAPSHOT_ENCODING = "utf8-base64-v1:";
    private final SharedPreferences prefs;
    private final OnlineRuleClient client;

    public RuleRepository(Context context) { this(context, new OnlineRuleClient()); }
    RuleRepository(Context context, OnlineRuleClient client) { this(context, client, null); }
    /** Test seam retains real preference semantics, including commit-to-memory before disk failure. */
    RuleRepository(Context context, OnlineRuleClient client, SharedPreferences storage) {
        Context app = context.getApplicationContext();
        Context owner = app == null ? context : app;
        prefs = storage == null ? owner.getSharedPreferences(PREFS, Context.MODE_PRIVATE) : storage; this.client = client;
        initialize(owner);
    }

    private void initialize(Context context) {
        synchronized (STORE_LOCK) {
            boolean hasOwnedState = hasOwnedMarkers();
            boolean initialized = booleanValue(INITIALIZED, false);
            if (hasOwnedState) {
                repairIdenticalSeedIfLost(context);
                if (!initialized) try { commit(prefs.edit().putBoolean(INITIALIZED, true)); }
                catch (StorageException ignored) { /* Keep the accepted identity even when flag persistence fails. */ }
                return;
            }
            // A historic load/parser failure could persist only initialized=true. Retry that known
            // never-accepted state, not arbitrary empty stores or any retained online metadata.
            if (initialized && !SEED_FAILURE_STATUS.equals(string(STATUS, ""))) return;
            try {
                OnlineRuleBundle bundled = verifiedSeed(context);
                validateCombined(manual().rules, bundled.rules);
                commit(prefs.edit().putBoolean(INITIALIZED, true).putString(PRIMARY, encodeSnapshot(bundled.raw))
                        .putString(PRIMARY_HASH, bundled.checksum).putLong(HIGHEST, bundled.revision)
                        .putString(HIGHEST_HASH, bundled.checksum).putBoolean(WITHDRAWN, bundled.rules.isEmpty())
                        .putString(SOURCE, "bundled").putString(STATUS, "已加载 IceBridge 离线在线基线，尚未联网"));
            } catch (IOException | RuntimeException invalid) {
                if (!(invalid instanceof StorageException)) try {
                    commit(prefs.edit().putBoolean(INITIALIZED, true).putString(STATUS, SEED_FAILURE_STATUS));
                } catch (StorageException ignored) { /* Restored memory state already records the unconfirmed disk failure. */ }
            }
        }
    }
    private boolean hasOwnedMarkers() {
        for (String key : new String[]{PRIMARY, PRIMARY_HASH, BACKUP, BACKUP_HASH, HIGHEST, HIGHEST_HASH, WITHDRAWN})
            if (prefs.contains(key)) return true;
        return false;
    }
    private static OnlineRuleBundle verifiedSeed(Context context) throws IOException {
        String manifestRaw = OnlineRuleClient.decodeUtf8(DefaultRuleCatalog.seedManifest(context), OnlineRuleBundle.MAX_MANIFEST_BYTES);
        OnlineRuleBundle.Manifest manifest = OnlineRuleBundle.parseManifest(manifestRaw);
        if (manifest.revision != DefaultRuleCatalog.SEED_REVISION || !manifest.checksum.equals(DefaultRuleCatalog.SEED_SHA256))
            throw new IOException("bundled-manifest");
        OnlineRuleBundle bundled = OnlineRuleBundle.parse(
                OnlineRuleClient.decodeUtf8(DefaultRuleCatalog.seedSnapshot(context), OnlineRuleBundle.MAX_BUNDLE_BYTES), manifest.checksum);
        if (bundled.revision != manifest.revision) throw new IOException("bundled-revision");
        return bundled;
    }
    /** Restoring bytes for an already accepted identical identity is not a version rollback. */
    private void repairIdenticalSeedIfLost(Context context) {
        try {
            Map<String, ?> values = prefs.getAll();
            if (!(values.get(HIGHEST) instanceof Long revision) || revision != DefaultRuleCatalog.SEED_REVISION
                    || !(values.get(HIGHEST_HASH) instanceof String checksum)
                    || !DefaultRuleCatalog.SEED_SHA256.equalsIgnoreCase(checksum)) return;
            boolean legacyFirstCommitFailure = !values.containsKey(WITHDRAWN)
                    && Boolean.TRUE.equals(values.get(INITIALIZED))
                    && FIRST_COMMIT_FAILURE_STATUS.equals(values.get(STORAGE_FAILURE))
                    && FIRST_COMMIT_FAILURE_STATUS.equals(values.get(STATUS))
                    && !values.containsKey(PRIMARY) && !values.containsKey(PRIMARY_HASH)
                    && !values.containsKey(BACKUP) && !values.containsKey(BACKUP_HASH);
            if (!Boolean.FALSE.equals(values.get(WITHDRAWN)) && !legacyFirstCommitFailure) return;
            if (values.containsKey(INITIALIZED) && !(values.get(INITIALIZED) instanceof Boolean)) return;
            for (String key : new String[]{PRIMARY, BACKUP})
                if (values.containsKey(key) && !(values.get(key) instanceof String)) return;
            for (String key : new String[]{PRIMARY_HASH, BACKUP_HASH})
                if (values.containsKey(key) && (!(values.get(key) instanceof String hash)
                        || !DefaultRuleCatalog.SEED_SHA256.equalsIgnoreCase(hash))) return;
            if (selected().cache.bundle != null) return;
            OnlineRuleBundle bundled = verifiedSeed(context);
            validateCombined(manual().rules, bundled.rules);
            commit(prefs.edit().putBoolean(INITIALIZED, true).putString(PRIMARY, encodeSnapshot(bundled.raw))
                    .putString(PRIMARY_HASH, bundled.checksum).putBoolean(WITHDRAWN, false).putString(SOURCE, "bundled")
                    .putString(STATUS, "已恢复相同版本与校验和的离线规则，手动配置和最高版本保护保留"));
        } catch (IOException | RuntimeException unavailable) {
            // Never weaken a retained floor, withdrawal marker or damaged metadata to make recovery work.
        }
    }

    public List<LinkRuleEntry> manualRules() { synchronized (STORE_LOCK) { return manual().rules; } }
    public List<LinkRuleEntry> onlineRules() { synchronized (STORE_LOCK) {
        OnlineRuleBundle bundle = selected().cache.bundle;
        return bundle == null ? Collections.emptyList() : bundle.rules;
    } }
    public List<LinkRuleEntry> activeRemoteRules() { return onlineRules(); }
    /** UI projection retains origin, including online candidates disabled by the user. */
    public List<LinkRuleEntry> allRules() {
        synchronized (STORE_LOCK) {
            List<LinkRuleEntry> all = new ArrayList<>(manual().rules);
            OnlineRuleBundle bundle = selected().cache.bundle;
            if (bundle != null) all.addAll(bundle.rules);
            return Collections.unmodifiableList(all);
        }
    }
    /** Invalid manual/app-toggle storage fails closed, rather than silently routing an online fallback. */
    public List<LinkRuleEntry> effectiveRules() {
        synchronized (STORE_LOCK) {
            Manual manual = manual(); if (!manual.valid || !disabledStorageValid()) return Collections.emptyList();
            OnlineRuleBundle bundle = selected().cache.bundle;
            try {
                List<LinkRuleEntry> merged = merged(manual.rules, bundle == null ? Collections.emptyList() : bundle.rules);
                ScopedRuleResolver.encode(merged);
                return ScopedRuleResolver.filterEnabled(merged, disabledTargets());
            } catch (IllegalArgumentException invalidAggregate) { return Collections.emptyList(); }
        }
    }
    public Set<String> disabledTargets() {
        synchronized (STORE_LOCK) {
            try { return Collections.unmodifiableSet(new LinkedHashSet<>(prefs.getStringSet(DISABLED, Collections.emptySet()))); }
            catch (RuntimeException corrupt) { return Collections.emptySet(); }
        }
    }
    public void setTargetEnabled(String target, boolean enabled) {
        if (!validTarget(target)) throw new IllegalArgumentException("目标应用包名无效");
        synchronized (STORE_LOCK) {
            Set<String> disabled = new LinkedHashSet<>(disabledTargets());
            if (enabled) disabled.remove(target); else disabled.add(target);
            commit(prefs.edit().putStringSet(DISABLED, disabled));
        }
    }

    public void replaceManualRules(List<LinkRuleEntry> input) {
        List<LinkRuleEntry> safe = new ArrayList<>();
        if (input != null) for (LinkRuleEntry entry : input) {
            LinkRuleEntry checked = RuleSchema.requireValid(entry);
            if (!LinkRuleEntry.SOURCE_MANUAL.equals(checked.source)) throw new IllegalArgumentException("只能保存手动来源规则");
            safe.add(checked);
        }
        safe = RuleSchema.deduplicate(safe);
        synchronized (STORE_LOCK) {
            OnlineRuleBundle bundle = selected().cache.bundle;
            validateCombined(safe, bundle == null ? Collections.emptyList() : bundle.rules);
            commit(prefs.edit().putString(MANUAL, RuleJsonCodec.encode(safe)));
        }
    }
    public void saveManualRule(int index, String source, String host, String target, String conversion, String note) {
        List<String> hosts = RuleSchema.normalizeUserHosts(host);
        if (hosts.size() != 1) throw new IllegalArgumentException("单条规则请填写一个域名");
        LinkRuleEntry entry = RuleSchema.requireValid(new LinkRuleEntry(source, hosts.get(0), target,
                LinkRuleEntry.SOURCE_MANUAL, conversion, note));
        synchronized (STORE_LOCK) {
            List<LinkRuleEntry> rules = new ArrayList<>(manual().rules);
            if (index == -1) rules.add(entry);
            else { checkIndex(index, rules.size()); rules.set(index, entry); }
            replaceManualRules(rules);
        }
    }
    public void deleteManualRule(int index) {
        synchronized (STORE_LOCK) {
            List<LinkRuleEntry> rules = new ArrayList<>(manual().rules); checkIndex(index, rules.size());
            rules.remove(index); replaceManualRules(rules);
        }
    }
    public void saveManualRulesForTarget(String target, String source, String hostText, String conversion, String note) {
        String origin = source == null || source.trim().isEmpty() ? "*" : source.trim();
        if (!validTarget(target) || !RuleSchema.isValidRulePackage(origin)) throw new IllegalArgumentException("应用包名无效");
        List<String> hosts = hostText == null || hostText.trim().isEmpty() ? Collections.emptyList() : RuleSchema.normalizeUserHosts(hostText);
        synchronized (STORE_LOCK) {
            List<LinkRuleEntry> rules = new ArrayList<>(manual().rules);
            rules.removeIf(rule -> target.equals(rule.targetPackage) && origin.equals(rule.sourcePackage));
            for (String host : hosts) rules.add(RuleSchema.requireValid(new LinkRuleEntry(origin, host, target,
                    LinkRuleEntry.SOURCE_MANUAL, conversion, note)));
            replaceManualRules(rules);
        }
    }
    public void clearManualRules() { replaceManualRules(Collections.emptyList()); }
    /** Resets manual overrides/app switches only; never downgrades or resurrects online data. */
    public void restoreDefaults() {
        synchronized (STORE_LOCK) { commit(prefs.edit().putString(MANUAL, "[]").putStringSet(DISABLED, Collections.emptySet())); }
    }
    public String exportManualRules() { return RuleConfigTransfer.exportJson(manualRules()); }
    public int importManualRules(String json) throws RuleConfigTransfer.TransferException {
        List<LinkRuleEntry> imported = RuleConfigTransfer.importJson(json);
        synchronized (STORE_LOCK) {
            int previous = manual().rules.size();
            List<LinkRuleEntry> merged = new ArrayList<>(manual().rules); merged.addAll(imported);
            replaceManualRules(merged); return manual().rules.size() - previous;
        }
    }

    public static final class OnlineState {
        public final boolean enabled, hasSnapshot, usingBackup;
        public final long revision, highestRevision, updatedAt;
        public final String checksum, status, sourceName;
        public final int ruleCount;
        private OnlineState(boolean enabled, Cache cache, long updatedAt, String status, String sourceName) {
            this.enabled = enabled; hasSnapshot = cache.highest > 0;
            usingBackup = cache.cache.usingBackup;
            revision = cache.cache.bundle == null ? cache.highest : cache.cache.bundle.revision;
            highestRevision = cache.highest;
            checksum = cache.cache.bundle == null ? cache.highestHash : cache.cache.bundle.checksum;
            ruleCount = cache.cache.bundle == null ? 0 : cache.cache.bundle.rules.size();
            this.updatedAt = updatedAt; this.status = status; this.sourceName = sourceName;
        }
    }
    public OnlineState onlineState() {
        synchronized (STORE_LOCK) {
            Cache cache = selected();
            String status = string(STATUS, "尚未联网刷新");
            if (!cache.valid) status = "在线缓存元数据损坏，已拒绝恢复旧规则或降低版本";
            else if (cache.cache.usingBackup) status = "主缓存不可用，正在使用已校验备份；最高版本记录保留";
            else if (booleanValue(WITHDRAWN, false) && cache.highest > 0) status = "在线规则已由完整空快照撤回";
            else if (cache.cache.bundle == null && cache.highest > 0) status = "在线缓存不可用，保留手动规则；请显式刷新修复";
            if (!manual().valid || !disabledStorageValid()) status = "手动规则或应用开关存储损坏，已停止自动路由";
            String storageFailure = string(STORAGE_FAILURE, "");
            if (!storageFailure.isEmpty()) status = storageFailure;
            String source = string(SOURCE, "none");
            String name = "bundled".equals(source) ? "IceBridge 离线在线基线" : "none".equals(source) ? "尚无在线缓存" : "IceBridge 自有规则 · " + source;
            return new OnlineState(booleanValue(ENABLED, false), cache, longValue(UPDATED, 0), status, name);
        }
    }
    /** Subscription controls future update permission, not validity of already accepted offline data. */
    public void setOnlineUpdatesEnabled(boolean enabled) { synchronized (STORE_LOCK) { commit(prefs.edit().putBoolean(ENABLED, enabled)); } }
    public static final class RefreshResult {
        public final boolean success;
        public final String message;
        public final OnlineState state;
        private RefreshResult(boolean success, String message, OnlineState state) { this.success = success; this.message = message; this.state = state; }
    }
    /** Explicit action only. Constructor, effectiveRules and subscription toggles never use the network. */
    public RefreshResult refreshOnline() { return refreshOnline(() -> Thread.currentThread().isInterrupted()); }
    public RefreshResult refreshOnline(BooleanSupplier cancelled) {
        synchronized (REFRESH_LOCK) {
            try {
                if (cancelled.getAsBoolean()) throw new IOException("refresh-cancelled");
                Cache before;
                synchronized (STORE_LOCK) { before = selected(); if (!before.valid) throw new IOException("revision-state-invalid"); }
                OnlineRuleClient.Download downloaded = client.download(before.highest, before.highestHash, before.cache);
                if (cancelled.getAsBoolean()) throw new IOException("refresh-cancelled");
                synchronized (STORE_LOCK) {
                    Cache current = selected();
                    if (!current.valid) throw new IOException("revision-state-invalid");
                    OnlineRuleBundle.Manifest proposed = new OnlineRuleBundle.Manifest(downloaded.bundle.revision, "snapshot.json", downloaded.bundle.checksum);
                    OnlineRuleBundle.checkRevision(proposed, current.highest, current.highestHash);
                    Manual manual = manual(); if (!manual.valid) throw new IOException("manual-storage-invalid");
                    validateCombined(manual.rules, downloaded.bundle.rules);
                    if (cancelled.getAsBoolean()) throw new IOException("refresh-cancelled");
                    SharedPreferences.Editor edit = prefs.edit();
                    if (current.cache.bundle != null && !current.cache.bundle.checksum.equals(downloaded.bundle.checksum)) {
                        edit.putString(BACKUP, encodeSnapshot(current.cache.bundle.raw)).putString(BACKUP_HASH, current.cache.bundle.checksum);
                    }
                    String message = "在线规则已更新至 r" + downloaded.bundle.revision + "（" + downloaded.bundle.rules.size() + " 条）";
                    commit(edit.putString(PRIMARY, encodeSnapshot(downloaded.bundle.raw)).putString(PRIMARY_HASH, downloaded.bundle.checksum)
                            .putLong(HIGHEST, downloaded.bundle.revision).putString(HIGHEST_HASH, downloaded.bundle.checksum)
                            .putBoolean(WITHDRAWN, downloaded.bundle.rules.isEmpty()).putLong(UPDATED, System.currentTimeMillis())
                            .putString(SOURCE, downloaded.source).putString(STATUS, message));
                    return new RefreshResult(true, message, onlineState());
                }
            } catch (IOException | RuntimeException failed) {
                String message = failureMessage(failed);
                if (!(failed instanceof StorageException)) synchronized (STORE_LOCK) {
                    try { commit(prefs.edit().putString(STATUS, message)); }
                    catch (StorageException storageFailure) { message = storageFailure.getMessage(); }
                }
                return failedRefresh(message);
            }
        }
    }

    /** Delivers a failure even if a platform preference call unexpectedly throws on a worker. */
    public RefreshResult failedRefresh(String message) {
        OnlineState state;
        try { state = onlineState(); }
        catch (RuntimeException unavailable) {
            state = new OnlineState(false, new Cache(new OnlineRuleBundle.Cache(null, false, false), 0, "", false),
                    0, message, "规则状态暂不可用");
        }
        return new RefreshResult(false, message, state);
    }

    private record Manual(List<LinkRuleEntry> rules, boolean valid) { }
    private Manual manual() {
        try {
            List<LinkRuleEntry> rules = RuleJsonCodec.decode(prefs.getString(MANUAL, "[]"));
            for (LinkRuleEntry entry : rules) if (!LinkRuleEntry.SOURCE_MANUAL.equals(entry.source)) throw new IllegalArgumentException();
            return new Manual(rules, true);
        } catch (RuntimeException corrupt) { return new Manual(Collections.emptyList(), false); }
    }
    private record Cache(OnlineRuleBundle.Cache cache, long highest, String highestHash, boolean valid) { }
    private Cache selected() {
        try {
            long highest = prefs.getLong(HIGHEST, 0);
            String hash = prefs.getString(HIGHEST_HASH, "").toLowerCase(Locale.ROOT);
            if (highest < 0 || (highest == 0 && !hash.isEmpty()) || (highest > 0 && !hash.matches("[0-9a-f]{64}"))) throw new IllegalArgumentException();
            OnlineRuleBundle.Cache cache = OnlineRuleBundle.selectCache(decodeSnapshot(prefs.getString(PRIMARY, "")), prefs.getString(PRIMARY_HASH, ""),
                    decodeSnapshot(prefs.getString(BACKUP, "")), prefs.getString(BACKUP_HASH, ""), highest, hash, true);
            // A declared full withdrawal must not resurrect older nonempty data after corruption.
            if (prefs.getBoolean(WITHDRAWN, false) && (cache.bundle == null || cache.bundle.revision < highest))
                cache = new OnlineRuleBundle.Cache(null, false, false);
            return new Cache(cache, highest, hash, true);
        } catch (RuntimeException corrupt) { return new Cache(new OnlineRuleBundle.Cache(null, false, false), 0, "", false); }
    }
    private boolean disabledStorageValid() {
        try { for (String target : prefs.getStringSet(DISABLED, Collections.emptySet())) if (!validTarget(target)) return false; return true; }
        catch (RuntimeException corrupt) { return false; }
    }
    private static List<LinkRuleEntry> merged(List<LinkRuleEntry> manual, List<LinkRuleEntry> online) {
        List<LinkRuleEntry> result = new ArrayList<>(manual); result.addAll(online);
        return RuleSchema.deduplicate(result);
    }
    private static void validateCombined(List<LinkRuleEntry> manual, List<LinkRuleEntry> online) { ScopedRuleResolver.encode(merged(manual, online)); }
    private static boolean validTarget(String target) { return target != null && !"*".equals(target) && RuleSchema.isValidRulePackage(target); }
    private static void checkIndex(int index, int size) { if (index < 0 || index >= size) throw new IllegalArgumentException("规则已变化，请刷新后重试"); }
    private static final class StorageException extends IllegalStateException {
        StorageException(String message, Throwable cause) { super(message, cause); }
    }
    private void commit(SharedPreferences.Editor edit) {
        Map<String, Object> before;
        try { before = copyPreferences(prefs.getAll()); }
        catch (RuntimeException unavailable) { throw new StorageException("规则保存失败，无法确认当前存储状态", unavailable); }
        RuntimeException cause = null;
        try { if (edit.remove(STORAGE_FAILURE).commit()) return; }
        catch (RuntimeException failed) { cause = failed; }
        // Android commit() updates memory before reporting a disk failure. Restore ordinary data
        // immediately, but never lower a revision/hash or undo an attempted full withdrawal.
        String message = FIRST_COMMIT_FAILURE_STATUS;
        try {
            Map<String, Object> after = copyPreferences(prefs.getAll());
            preserveRevisionFloor(before, after);
            if (Boolean.TRUE.equals(after.get(INITIALIZED))) before.put(INITIALIZED, true);
            before.put(STORAGE_FAILURE, message); before.put(STATUS, message);
            SharedPreferences.Editor restore = prefs.edit().clear();
            for (Map.Entry<String, Object> entry : before.entrySet()) putValue(restore, entry.getKey(), entry.getValue());
            // apply() provides only the immediate memory restoration. Its disk result is unknown.
            restore.apply();
        } catch (RuntimeException restoreFailed) {
            message = "规则保存失败，内存恢复未完成，磁盘状态未确认；请重新打开页面检查";
            if (cause == null) cause = restoreFailed; else cause.addSuppressed(restoreFailed);
        }
        throw new StorageException(message, cause);
    }
    private static Map<String, Object> copyPreferences(Map<String, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : input.entrySet()) {
            Object value = entry.getValue();
            result.put(entry.getKey(), value instanceof Set<?> set ? new LinkedHashSet<>(set) : value);
        }
        return result;
    }
    private static void preserveRevisionFloor(Map<String, Object> before, Map<String, Object> after) {
        Object candidate = after.get(HIGHEST), checksum = after.get(HIGHEST_HASH);
        if (!(candidate instanceof Long highest) || highest <= 0 || !(checksum instanceof String hash)
                || !hash.matches("[0-9a-fA-F]{64}")) return;
        long previous = before.get(HIGHEST) instanceof Long value ? value : 0;
        if (highest < previous) return;
        if (highest > previous) {
            OnlineRuleBundle.Cache old = OnlineRuleBundle.selectCache(decodeSnapshot(mapString(before, PRIMARY)), mapString(before, PRIMARY_HASH),
                    decodeSnapshot(mapString(before, BACKUP)), mapString(before, BACKUP_HASH), previous, mapString(before, HIGHEST_HASH), true);
            if (old.bundle != null) { before.put(BACKUP, encodeSnapshot(old.bundle.raw)); before.put(BACKUP_HASH, old.bundle.checksum); }
        }
        before.put(HIGHEST, highest); before.put(HIGHEST_HASH, hash);
        if (Boolean.TRUE.equals(after.get(WITHDRAWN))) before.put(WITHDRAWN, true);
    }
    private static String mapString(Map<String, Object> values, String key) { return values.get(key) instanceof String text ? text : ""; }
    private static String encodeSnapshot(String raw) {
        return SNAPSHOT_ENCODING + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
    private static String decodeSnapshot(String stored) {
        if (!stored.startsWith(SNAPSHOT_ENCODING)) return stored; // Existing LF-only caches retain compatibility.
        if (stored.length() > SNAPSHOT_ENCODING.length() + 4 * ((OnlineRuleBundle.MAX_BUNDLE_BYTES + 2) / 3)) return "";
        try {
            byte[] bytes = Base64.getDecoder().decode(stored.substring(SNAPSHOT_ENCODING.length()));
            return OnlineRuleClient.decodeUtf8(bytes, OnlineRuleBundle.MAX_BUNDLE_BYTES);
        } catch (IOException | IllegalArgumentException invalid) { return ""; }
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
    private String string(String key, String fallback) { try { return prefs.getString(key, fallback); } catch (RuntimeException ignored) { return fallback; } }
    private long longValue(String key, long fallback) { try { return prefs.getLong(key, fallback); } catch (RuntimeException ignored) { return fallback; } }
    private boolean booleanValue(String key, boolean fallback) { try { return prefs.getBoolean(key, fallback); } catch (RuntimeException ignored) { return fallback; } }
    private static String failureMessage(Exception failure) {
        if (failure instanceof StorageException) return failure.getMessage();
        String reason = failure.getMessage();
        if ("revision-rollback".equals(reason)) return "已拒绝回退在线版本，继续保留当前规则";
        if ("revision-content-changed".equals(reason)) return "同一在线版本内容发生变化，已拒绝更新";
        if ("refresh-cancelled".equals(reason)) return "在线更新已取消，当前规则保留";
        if ("refresh-deadline".equals(reason)) return "在线更新超时，当前规则保留";
        if (reason != null && (reason.contains("limit") || reason.startsWith("too-many") || "response-too-large".equals(reason)))
            return "在线规则超过大小或条数限制（最多768条、96个不同域名），已拒绝更新";
        return "在线规则下载或校验失败，当前有效缓存和手动规则保留";
    }
}
