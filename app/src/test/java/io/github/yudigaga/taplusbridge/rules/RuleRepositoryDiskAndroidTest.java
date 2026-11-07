package io.github.yudigaga.taplusbridge.rules;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Bypasses Context's preference-instance cache and reloads the actual on-disk XML. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class RuleRepositoryDiskAndroidTest {
    private Context context;
    private String snapshot;
    private OnlineRuleClient client;
    @Before public void initialize() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(RuleRepository.PREFS, 0).edit().clear().commit();
        snapshot = OnlineRuleContractTest.payload(2, OnlineRuleContractTest.RULE);
        client = new OnlineRuleClient((url, limit, deadline) ->
                url.getPath().endsWith("manifest.json") ? OnlineRuleContractTest.manifest(snapshot) : snapshot);
    }
    private SharedPreferences coldPreferences() throws Exception {
        Class.forName("android.app.QueuedWork").getDeclaredMethod("waitToFinish").invoke(null);
        File file = new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"), RuleRepository.PREFS + ".xml");
        assertTrue("A commit must have produced an actual preference file", file.isFile());
        Constructor<?> constructor = Class.forName("android.app.SharedPreferencesImpl").getDeclaredConstructor(File.class, int.class);
        constructor.setAccessible(true);
        SharedPreferences cold = (SharedPreferences) constructor.newInstance(file, Context.MODE_PRIVATE);
        assertNotSame(context.getSharedPreferences(RuleRepository.PREFS, 0), cold);
        cold.getAll(); // Wait for this new object's disk loader, not the old context cache.
        return cold;
    }
    @Test public void fetchedSnapshotSurvivesAnActualDiskReload() throws Exception {
        RuleRepository repository = new RuleRepository(context, client);
        assertTrue(repository.refreshOnline().success);
        RuleRepository cold = new RuleRepository(context, client, coldPreferences());
        assertEquals(2, cold.onlineState().revision);
        assertEquals(1, cold.onlineRules().size());
        assertEquals(OnlineRuleBundle.sha256(snapshot), cold.onlineState().checksum);
    }
    @Test public void fetchedCrlfSnapshotKeepsItsChecksumAndRulesAcrossActualDiskReload() throws Exception {
        snapshot = snapshot.replace("{", "{\r\n") + "\r\n";
        RuleRepository repository = new RuleRepository(context, client);
        assertTrue(repository.refreshOnline().success);
        assertEquals(2, repository.onlineState().revision);
        RuleRepository cold = new RuleRepository(context, client, coldPreferences());
        assertEquals("Fetched raw bytes must remain usable after XML reload", 2, cold.onlineState().revision);
        assertEquals(1, cold.onlineRules().size());
        assertEquals(OnlineRuleBundle.sha256(snapshot), cold.onlineState().checksum);
    }
    @Test public void refreshThenManualToggleAndFailureStatusCommitsKeepTheDiskSnapshot() throws Exception {
        RuleRepository repository = new RuleRepository(context, client);
        assertTrue(repository.refreshOnline().success);
        String accepted = repository.onlineState().checksum;
        repository.saveManualRule(-1, "com.tencent.mm", "manual.example", "com.example.manual", "none", "keep this row");
        repository.setTargetEnabled("com.example.reader", false);
        repository.setOnlineUpdatesEnabled(true);repository.setOnlineUpdatesEnabled(false);
        snapshot = OnlineRuleContractTest.payload(1, "");
        assertFalse(repository.refreshOnline().success); // A separate status commit must not drop the cache.
        RuleRepository cold = new RuleRepository(context, client, coldPreferences());
        assertEquals(2, cold.onlineState().revision);assertEquals(2, cold.onlineState().highestRevision);
        assertEquals(accepted, cold.onlineState().checksum);assertEquals(1, cold.onlineRules().size());
        assertFalse(cold.onlineState().enabled);assertTrue(cold.disabledTargets().contains("com.example.reader"));
        assertEquals("keep this row", cold.manualRules().get(0).note);assertEquals(1, cold.effectiveRules().size());
    }
    @Test public void backupAndHighestRevisionRemainDistinctAfterActualDiskReload() throws Exception {
        RuleRepository repository = new RuleRepository(context, client);assertTrue(repository.refreshOnline().success);
        snapshot = OnlineRuleContractTest.payload(3, OnlineRuleContractTest.RULE.replace("example.com", "new.example"));
        assertTrue(repository.refreshOnline().success);
        context.getSharedPreferences(RuleRepository.PREFS, 0).edit().putString("online_primary", "corrupt").commit();
        RuleRepository cold = new RuleRepository(context, client, coldPreferences());
        assertTrue(cold.onlineState().usingBackup);assertEquals(2, cold.onlineState().revision);
        assertEquals(3, cold.onlineState().highestRevision);assertEquals(1, cold.onlineRules().size());
        snapshot = OnlineRuleContractTest.payload(2, OnlineRuleContractTest.RULE);
        assertFalse(cold.refreshOnline().success);assertEquals(3, cold.onlineState().highestRevision);
    }
    @Test public void emptyWithdrawalSurvivesDiskReloadAndCorruptedPrimaryWithoutSeedResurrection() throws Exception {
        RuleRepository repository = new RuleRepository(context, client);assertTrue(repository.refreshOnline().success);
        snapshot = OnlineRuleContractTest.payload(3, "");assertTrue(repository.refreshOnline().success);
        SharedPreferences firstReload = coldPreferences();
        RuleRepository cold = new RuleRepository(context, client, firstReload);
        assertTrue(cold.onlineRules().isEmpty());assertEquals(3, cold.onlineState().highestRevision);
        firstReload.edit().putString("online_primary", "corrupt").commit();
        RuleRepository afterCorruption = new RuleRepository(context, client, coldPreferences());
        assertTrue(afterCorruption.onlineRules().isEmpty());assertEquals(3, afterCorruption.onlineState().highestRevision);
    }
    @Test public void knownNeverInitializedSeedFailureIsRetriedOnColdStartWithoutResettingManualRows() throws Exception {
        context.getSharedPreferences(RuleRepository.PREFS, 0).edit().clear().putBoolean("initialized", true)
                .putString("online_status", "离线规则基线不可用；未恢复旧规则，可手动刷新")
                .putString("manual", RuleJsonCodec.encode(java.util.List.of(new LinkRuleEntry(
                        "*", "manual.example", "com.example.manual", "manual", "none", "preserve")))).commit();
        RuleRepository cold = new RuleRepository(context, client, coldPreferences());
        assertEquals("A historic seed load failure must not become a permanent empty cache", 32, cold.onlineRules().size());
        assertEquals("preserve", cold.manualRules().get(0).note);assertEquals(1, cold.onlineState().highestRevision);
    }
    @Test public void partialOwnedMetadataNeverReseedsEvenWhenInitializationFlagWasLost() throws Exception {
        for (String marker : new String[]{"highest_sha256", "online_primary_sha256", "online_backup_sha256", "highest_withdrawn"}) {
            SharedPreferences.Editor editor = context.getSharedPreferences(RuleRepository.PREFS, 0).edit().clear()
                    .putBoolean("initialized", false).putString("online_status", "离线规则基线不可用；未恢复旧规则，可手动刷新");
            if ("highest_withdrawn".equals(marker)) editor.putBoolean(marker, true);
            else editor.putString(marker, "a".repeat(64));
            assertTrue(editor.commit());
            RuleRepository cold = new RuleRepository(context, client, coldPreferences());
            assertTrue("A retained owned marker must prevent seed resurrection: " + marker, cold.onlineRules().isEmpty());
        }
    }
    @Test public void identicalBundledBytesRepairTheFailedFirstCommitFloorAfterColdReload() throws Exception {
        context.getSharedPreferences(RuleRepository.PREFS, 0).edit().clear().putBoolean("initialized", true)
                .putLong("highest_revision", 1).putString("highest_sha256", DefaultRuleCatalog.SEED_SHA256)
                .putBoolean("highest_withdrawn", false).commit();
        RuleRepository cold = new RuleRepository(context, client, coldPreferences());
        assertEquals(32, cold.onlineRules().size());assertEquals(1, cold.onlineState().highestRevision);
        assertEquals(DefaultRuleCatalog.SEED_SHA256, cold.onlineState().checksum);
        RuleRepository secondCold = new RuleRepository(context, client, coldPreferences());
        assertEquals(32, secondCold.onlineRules().size());
    }
    @Test public void seedRepairNeverDowngradesChangesHashOrUndoesWithdrawal() throws Exception {
        for (int scenario = 0; scenario < 5; scenario++) {
            SharedPreferences.Editor editor = context.getSharedPreferences(RuleRepository.PREFS, 0).edit().clear()
                    .putBoolean("initialized", true).putLong("highest_revision", scenario == 0 ? 2 : 1)
                    .putString("highest_sha256", scenario == 1 ? "a".repeat(64) : DefaultRuleCatalog.SEED_SHA256);
            if (scenario == 2) editor.putBoolean("highest_withdrawn", true);
            else if (scenario == 3) editor.putString("highest_withdrawn", "false");
            else if (scenario == 4) { /* An absent withdrawal marker is not proof of false. */ }
            else editor.putBoolean("highest_withdrawn", false);
            assertTrue(editor.commit());
            RuleRepository cold = new RuleRepository(context, client, coldPreferences());
            assertTrue("Unsafe seed repair scenario " + scenario, cold.onlineRules().isEmpty());
        }
    }
    @Test public void malformedOwnedMarkerTypesCannotAuthorizeSameRevisionSeedRepair() throws Exception {
        context.getSharedPreferences(RuleRepository.PREFS, 0).edit().clear().putBoolean("initialized", true)
                .putLong("highest_revision", 1).putString("highest_sha256", DefaultRuleCatalog.SEED_SHA256)
                .putBoolean("highest_withdrawn", false).putInt("online_primary_sha256", 42).commit();
        assertTrue(new RuleRepository(context, client, coldPreferences()).onlineRules().isEmpty());
    }
    @Test public void legacyPlainLfSnapshotRemainsReadableWithoutClearingAnyPreferences() throws Exception {
        String hash = OnlineRuleBundle.sha256(snapshot);
        context.getSharedPreferences(RuleRepository.PREFS, 0).edit().clear().putBoolean("initialized", true)
                .putString("online_primary", snapshot).putString("online_primary_sha256", hash)
                .putLong("highest_revision", 2).putString("highest_sha256", hash)
                .putBoolean("highest_withdrawn", false).commit();
        RuleRepository cold = new RuleRepository(context, client, coldPreferences());
        assertEquals(2, cold.onlineState().revision);assertEquals(1, cold.onlineRules().size());
        cold.setOnlineUpdatesEnabled(true);
        RuleRepository secondCold = new RuleRepository(context, client, coldPreferences());
        assertTrue(secondCold.onlineState().enabled);assertEquals(hash, secondCold.onlineState().checksum);
        assertEquals(1, secondCold.onlineRules().size());
    }
    @Test public void realFirstCommitFailureDiskStateWithMissingWithdrawalFlagCanRecoverIdenticalSeed() throws Exception {
        SharedPreferences actual = context.getSharedPreferences(RuleRepository.PREFS, 0);
        AtomicBoolean failFirst = new AtomicBoolean(true);
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!"edit".equals(method.getName())) return invoke(method, actual, args);
                    SharedPreferences.Editor editor = actual.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                            (editorProxy, operation, input) -> {
                                if ("commit".equals(operation.getName())) {
                                    boolean accepted = editor.commit();
                                    return failFirst.getAndSet(false) ? false : accepted;
                                }
                                Object returned = invoke(operation, editor, input);
                                return returned instanceof SharedPreferences.Editor ? editorProxy : returned;
                            });
                });
        RuleRepository initial = new RuleRepository(context, client, failing);
        assertTrue(initial.onlineRules().isEmpty());
        SharedPreferences reloaded = coldPreferences();
        assertFalse(reloaded.contains("highest_withdrawn"));
        assertFalse(reloaded.contains("online_primary"));assertFalse(reloaded.contains("online_backup"));
        assertEquals(DefaultRuleCatalog.SEED_SHA256, reloaded.getString("highest_sha256", ""));
        assertTrue(reloaded.getString("storage_failure", "").startsWith("规则保存失败"));
        RuleRepository repaired = new RuleRepository(context, client, reloaded);
        assertEquals(32, repaired.onlineRules().size());assertEquals(1, repaired.onlineState().highestRevision);
        assertEquals(32, new RuleRepository(context, client, coldPreferences()).onlineRules().size());
    }
    @Test public void retryingNeverAcceptedSeedCannotOverflowAndDisableExistingManualRules() throws Exception {
        java.util.List<LinkRuleEntry> manual = new java.util.ArrayList<>();
        for (int i = 0; i < 95; i++) manual.add(new LinkRuleEntry("*", "d" + i + ".manual.example", "com.example.manual", "manual", "none", ""));
        context.getSharedPreferences(RuleRepository.PREFS, 0).edit().clear().putBoolean("initialized", true)
                .putString("online_status", "离线规则基线不可用；未恢复旧规则，可手动刷新")
                .putString("manual", RuleJsonCodec.encode(manual)).commit();
        RuleRepository cold = new RuleRepository(context, client, coldPreferences());
        assertTrue(cold.onlineRules().isEmpty());assertEquals(95, cold.manualRules().size());
        assertEquals(95, cold.effectiveRules().size());
    }
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failed) { throw failed.getCause(); }
    }
}
