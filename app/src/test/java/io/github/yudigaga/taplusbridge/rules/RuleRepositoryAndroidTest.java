package io.github.yudigaga.taplusbridge.rules;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class RuleRepositoryAndroidTest {
    private Context context;
    private String snapshot;
    private AtomicInteger fetches;
    private RuleRepository repository;
    private OnlineRuleClient client;

    @Before public void createStore() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().putString("rules", "old invalid Taplus data").commit();
        snapshot = OnlineRuleContractTest.payload(2, OnlineRuleContractTest.RULE);
        fetches = new AtomicInteger();
        client = new OnlineRuleClient((url, max, deadline) -> {
            fetches.incrementAndGet();
            return url.getPath().endsWith("manifest.json") ? OnlineRuleContractTest.manifest(snapshot) : snapshot;
        });
        repository = new RuleRepository(context, client);
    }
    private void manual() { repository.saveManualRule(-1, "com.tencent.mm", "manual.example", "com.example.manual", "none", "我的规则"); }

    @Test public void unchangedStorageReusesValidatedRulesAcrossRepositoryInstances() {
        manual();
        List<LinkRuleEntry> online = repository.onlineRules(), effective = repository.effectiveRules();
        List<LinkRuleEntry> manual = repository.manualRules();
        for (int i = 0; i < 5; i++) {
            RuleRepository reopened = new RuleRepository(context);
            assertSame("Unchanged online bytes must not be parsed again", online, reopened.onlineRules());
            assertSame("Unchanged manual bytes must not be parsed again", manual, reopened.manualRules());
            assertSame("Unchanged routes must not be merged and validated again", effective, reopened.effectiveRules());
        }
        repository.setOnlineUpdatesEnabled(true);
        assertSame("Update permission does not alter accepted routes", effective, repository.effectiveRules());
        try { manual.clear(); fail("Cached manual rules must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        try { effective.clear(); fail("Cached effective rules must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        assertEquals(33, repository.effectiveRules().size());
    }

    @Test public void directPreferenceChangesImmediatelyInvalidateWarmedRoutes() {
        assertTrue(repository.refreshOnline().success);
        List<LinkRuleEntry> accepted = repository.effectiveRules(); assertEquals(1, accepted.size());
        SharedPreferences storage = context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE);
        storage.edit().putStringSet("disabled_targets", Set.of("com.example.reader")).commit();
        assertTrue(repository.effectiveRules().isEmpty());
        storage.edit().putStringSet("disabled_targets", Set.of()).commit();
        assertEquals(accepted, repository.effectiveRules());
        storage.edit().putString("manual", "broken").commit();
        assertTrue(repository.effectiveRules().isEmpty());
        storage.edit().putString("manual", "[]").putString("disabled_targets", "wrong type").commit();
        assertTrue(repository.effectiveRules().isEmpty());
        storage.edit().remove("disabled_targets").putString("highest_sha256", "invalid").commit();
        assertTrue(repository.effectiveRules().isEmpty()); assertFalse(repository.refreshOnline().success);
    }

    @Test public void unavailablePreferenceSnapshotNeverReturnsWarmedRoutes() {
        SharedPreferences actual = context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE);
        AtomicBoolean unavailable = new AtomicBoolean();
        SharedPreferences storage = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    if (unavailable.get() && "getAll".equals(method.getName())) throw new IllegalStateException("unavailable");
                    return FailingCommits.invoke(method, actual, args);
                });
        RuleRepository checked = new RuleRepository(context, client, storage);
        assertEquals(32, checked.effectiveRules().size());
        unavailable.set(true);
        assertTrue(checked.effectiveRules().isEmpty()); assertTrue(checked.onlineRules().isEmpty());
        unavailable.set(false);
        assertEquals(32, checked.effectiveRules().size());
    }

    @Test public void firstUseVerifiesBundledOnlineBaselineWithoutNetworkOrLegacyMigration() {
        RuleRepository.OnlineState state = repository.onlineState();
        assertEquals(0, fetches.get()); assertFalse(state.enabled); assertTrue(state.hasSnapshot);
        assertEquals(1, state.revision); assertEquals(DefaultRuleCatalog.SEED_SHA256, state.checksum); assertEquals(32, state.ruleCount);
        assertTrue(repository.manualRules().isEmpty()); assertTrue(repository.allRules().stream().allMatch(r -> "online".equals(r.source)));
        assertTrue(state.sourceName.contains("离线"));
        repository.setOnlineUpdatesEnabled(true); repository.setOnlineUpdatesEnabled(false);
        assertEquals(0, fetches.get()); assertEquals(32, repository.onlineRules().size());
    }
    @Test public void fullRefreshReplacesOnlineOnlyAndPreservesManualAndAppSwitches() {
        manual(); repository.setTargetEnabled("com.example.reader", false);
        assertTrue(repository.refreshOnline().success); assertEquals(1, repository.onlineRules().size());
        assertEquals("我的规则", repository.manualRules().get(0).note); assertEquals(2, repository.allRules().size());
        assertEquals(1, repository.effectiveRules().size()); assertEquals("manual", repository.effectiveRules().get(0).source);
        assertEquals(2, repository.onlineState().highestRevision); assertFalse(repository.onlineState().enabled);
    }
    @Test public void emptySnapshotWithdrawsOnlineAndNeverResurrectsBundledOrOlderBackup() {
        manual(); snapshot = OnlineRuleContractTest.payload(2, ""); assertTrue(repository.refreshOnline().success);
        assertTrue(repository.onlineRules().isEmpty()); assertEquals(1, repository.effectiveRules().size());
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().putString("online_primary", "corrupt").commit();
        RuleRepository reopened = new RuleRepository(context);
        assertTrue(reopened.onlineRules().isEmpty()); assertEquals(2, reopened.onlineState().highestRevision);
        reopened.restoreDefaults(); assertTrue(reopened.allRules().isEmpty()); assertEquals(2, reopened.onlineState().highestRevision);
    }
    @Test public void rollbackAndSameRevisionMutationCannotReplaceAcceptedBytes() {
        assertTrue(repository.refreshOnline().success); String accepted = repository.onlineState().checksum;
        snapshot = OnlineRuleContractTest.payload(1, ""); assertFalse(repository.refreshOnline().success);
        assertEquals(2, repository.onlineState().revision); assertEquals(accepted, repository.onlineState().checksum);
        snapshot = OnlineRuleContractTest.payload(2, ""); RuleRepository.RefreshResult changed = repository.refreshOnline();
        assertFalse(changed.success); assertTrue(changed.message.contains("同一在线版本")); assertEquals(accepted, repository.onlineState().checksum);
    }
    @Test public void validBackupRecoversCorruptionWithoutResettingTheHighestRevision() {
        assertTrue(repository.refreshOnline().success);
        snapshot = OnlineRuleContractTest.payload(3, OnlineRuleContractTest.RULE.replace("example.com", "other.example"));
        assertTrue(repository.refreshOnline().success);
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().putString("online_primary", "corrupt").commit();
        assertTrue(repository.onlineState().usingBackup); assertEquals(2, repository.onlineState().revision); assertEquals(3, repository.onlineState().highestRevision);
        snapshot = OnlineRuleContractTest.payload(2, OnlineRuleContractTest.RULE);
        assertFalse(repository.refreshOnline().success); assertEquals(3, repository.onlineState().highestRevision);
    }
    @Test public void invalidSnapshotAndAggregateOverflowLeaveCurrentManualAndOnlineIntact() {
        manual(); String accepted = repository.onlineState().checksum;
        snapshot = OnlineRuleContractTest.payload(2, OnlineRuleContractTest.RULE.replace("fixed_deep_link", "javascript"));
        assertFalse(repository.refreshOnline().success); assertEquals(accepted, repository.onlineState().checksum); assertEquals(1, repository.manualRules().size());
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 96; i++) {
            if (i > 0) rows.append(','); rows.append(OnlineRuleContractTest.RULE.replace("example.com", "d" + i + ".example.com"));
        }
        snapshot = OnlineRuleContractTest.payload(2, rows.toString());
        RuleRepository.RefreshResult overflow = repository.refreshOnline();
        assertFalse(overflow.success); assertTrue(overflow.message.contains("768条、96个不同域名"));
        assertEquals(accepted, repository.onlineState().checksum); assertEquals(1, repository.manualRules().size());
    }
    @Test public void editorAndImportKeepSourceConversionNoteAndDeduplicateWithoutChangingOnline() throws Exception {
        manual(); String online = repository.onlineState().checksum;
        String exported = repository.exportManualRules(); assertEquals(0, repository.importManualRules(exported));
        assertEquals(1, repository.manualRules().size()); assertEquals(online, repository.onlineState().checksum);
        repository.clearManualRules(); assertEquals(1, repository.importManualRules(exported));
        LinkRuleEntry restored = repository.manualRules().get(0);
        assertEquals("com.tencent.mm", restored.sourcePackage); assertEquals("none", restored.urlConversion); assertEquals("我的规则", restored.note);
    }
    @Test public void corruptManualStorageStopsEffectiveRoutingInsteadOfFallingBackOnline() {
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().putString("manual", "broken").commit();
        assertTrue(repository.effectiveRules().isEmpty()); assertEquals(32, repository.onlineRules().size());
        assertTrue(repository.onlineState().status.contains("停止自动路由"));
        repository.clearManualRules(); assertEquals(32, repository.effectiveRules().size());
    }
    @Test public void corruptRevisionMetadataCannotEnableRollbackOrSeedAgain() {
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().putLong("highest_revision", 3).putString("highest_sha256", "bad").commit();
        assertTrue(repository.onlineRules().isEmpty()); assertFalse(repository.refreshOnline().success);
        assertTrue(new RuleRepository(context).onlineRules().isEmpty());
    }
    @Test public void cancelledExplicitRefreshDoesNotFetchOrReplaceTheCache() {
        String accepted = repository.onlineState().checksum;
        assertFalse(repository.refreshOnline(() -> true).success); assertEquals(0, fetches.get()); assertEquals(accepted, repository.onlineState().checksum);
    }

    @Test public void failedSnapshotCommitRestoresDataWithoutLosingTheAttemptedRevisionFloor() {
        manual(); String previous = repository.onlineState().checksum;
        FailingCommits storage = new FailingCommits(context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE));
        RuleRepository failing = new RuleRepository(context, client, storage.prefs);
        storage.failNext = 10;
        RuleRepository.RefreshResult failure = failing.refreshOnline();
        assertFalse(failure.success); assertTrue(failure.message.contains("磁盘写入与恢复均未确认"));
        assertEquals(1, storage.commits); // Failure handling uses memory restoration, no second throwing commit.
        assertEquals("我的规则", failing.manualRules().get(0).note);
        assertEquals(2, failing.onlineState().highestRevision); assertEquals(1, failing.onlineState().revision);
        assertEquals(previous, failing.onlineState().checksum); assertTrue(failing.onlineState().usingBackup);
        assertTrue(failing.onlineState().status.contains("磁盘写入与恢复均未确认"));
        assertEquals(2, new RuleRepository(context).onlineState().highestRevision);
        storage.failNext = 0;
        snapshot = OnlineRuleContractTest.payload(1, ""); assertFalse(failing.refreshOnline().success);
        snapshot = OnlineRuleContractTest.payload(2, ""); assertFalse(failing.refreshOnline().success); // Same attempted revision cannot change bytes.
        snapshot = OnlineRuleContractTest.payload(2, OnlineRuleContractTest.RULE); assertTrue(failing.refreshOnline().success);
        assertEquals(2, failing.onlineState().revision); assertFalse(failing.onlineState().usingBackup);
    }
    @Test public void failedWithdrawalCommitDoesNotResurrectAnyPreviousOnlineRules() {
        FailingCommits storage = new FailingCommits(context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE));
        RuleRepository failing = new RuleRepository(context, client, storage.prefs);
        storage.failNext = 1; snapshot = OnlineRuleContractTest.payload(2, "");
        assertFalse(failing.refreshOnline().success);
        assertTrue(failing.onlineRules().isEmpty()); assertEquals(2, failing.onlineState().highestRevision);
        assertTrue(new RuleRepository(context).onlineRules().isEmpty());
        assertTrue(failing.refreshOnline().success); assertTrue(failing.onlineRules().isEmpty());
    }
    @Test public void failedStatusCommitStillReturnsAnExplicitFailure() {
        FailingCommits storage = new FailingCommits(context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE));
        RuleRepository failing = new RuleRepository(context, new OnlineRuleClient((url, max, deadline) -> { throw new IOException("offline"); }), storage.prefs);
        storage.failNext = 10;
        RuleRepository.RefreshResult result = failing.refreshOnline();
        assertFalse(result.success); assertTrue(result.message.contains("磁盘写入与恢复均未确认"));
        assertEquals(1, storage.commits); assertEquals(32, failing.onlineRules().size());
    }
    @Test public void failedManualCommitRestoresThePreviousManualRows() {
        manual(); FailingCommits storage = new FailingCommits(context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE));
        RuleRepository failing = new RuleRepository(context, client, storage.prefs); storage.failNext = 1;
        try { failing.clearManualRules(); fail(); } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("磁盘")); }
        assertEquals(1, failing.manualRules().size()); assertEquals("我的规则", failing.manualRules().get(0).note);
        assertEquals(1, failing.onlineState().highestRevision);
    }
    @Test public void firstInitializationFailureRetainsItsIdentityAndOnlyRetriesIdenticalSeedBytes() {
        SharedPreferences actual = context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE);
        actual.edit().clear().commit(); FailingCommits storage = new FailingCommits(actual); storage.failNext = 10;
        RuleRepository failing = new RuleRepository(context, client, storage.prefs);
        assertEquals(1, storage.commits); assertEquals(1, failing.onlineState().highestRevision);
        assertTrue(failing.onlineRules().isEmpty()); assertEquals(0, fetches.get());
        new RuleRepository(context, client, storage.prefs); assertEquals(2, storage.commits);
        assertTrue(failing.onlineRules().isEmpty());assertEquals(1, failing.onlineState().highestRevision);
        storage.failNext = 0;
        RuleRepository recovered = new RuleRepository(context, client, storage.prefs);
        assertEquals(3, storage.commits);assertEquals(32, recovered.onlineRules().size());
        assertEquals(DefaultRuleCatalog.SEED_SHA256, recovered.onlineState().checksum);
    }

    /** Delegates to real Robolectric preferences, then reports disk failure after memory changed. */
    private static final class FailingCommits {
        final SharedPreferences prefs;
        int failNext, commits;
        FailingCommits(SharedPreferences actual) {
            prefs = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                    (proxy, method, args) -> {
                        if (!"edit".equals(method.getName())) return invoke(method, actual, args);
                        SharedPreferences.Editor editor = actual.edit();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[] {SharedPreferences.Editor.class},
                                (editorProxy, editMethod, editArgs) -> {
                                    if ("commit".equals(editMethod.getName())) {
                                        commits++; boolean result = editor.commit();
                                        if (failNext > 0) { failNext--; return false; } return result;
                                    }
                                    Object result = invoke(editMethod, editor, editArgs);
                                    return result instanceof SharedPreferences.Editor ? editorProxy : result;
                                });
                    });
        }
        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try { return method.invoke(target, args); } catch (InvocationTargetException failed) { throw failed.getCause(); }
        }
    }
}
