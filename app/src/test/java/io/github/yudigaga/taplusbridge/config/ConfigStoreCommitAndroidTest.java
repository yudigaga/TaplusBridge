package io.github.yudigaga.taplusbridge.config;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import io.github.yudigaga.taplusbridge.thaw.ThawEngine;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class ConfigStoreCommitAndroidTest {
    private Context app;
    private SharedPreferences preferences;
    private PreferenceCommitContext controlled;
    private ConfigStore store;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        preferences = app.getSharedPreferences("config", 0);
        preferences.edit().clear().putBoolean("enabled", true).putBoolean("native", true)
                .putBoolean("recognition", true).putBoolean("capture", true)
                .putString("engine", "ROOT").commit();
        controlled = new PreferenceCommitContext(app);
        store = new ConfigStore(controlled);
    }

    @Test public void ordinarySwitchAndModeSettersRejectFalseCommitAndRestorePriorMemory() {
        controlled.reject = true;
        for (Runnable save : List.<Runnable>of(() -> store.setEnabled(false), () -> store.setNativeEnabled(false),
                () -> store.setRecognitionEnabled(false), () -> store.setCaptureEnabled(false),
                () -> store.setMode(ThawEngine.Mode.SHIZUKU))) {
            Map<String, ?> before = new HashMap<>(preferences.getAll());
            assertThrows("Every failed save must report failure", IllegalStateException.class, save::run);
            assertEquals("Rejected settings must not remain active in Android's memory cache", before, preferences.getAll());
        }
    }

    @Test public void checkedSettersRestoreExistingAndAbsentKeysWhenCommitRejectsChangedMemory() {
        controlled.reject = true;
        DragSettings drag = new DragSettings(false, "share", "portal", "lens", "translate", false, List.of());
        for (Runnable save : List.<Runnable>of(() -> store.setLensEnabled(false),
                () -> store.setCloudLinkHandoffEnabled(false),
                () -> store.setRecognitionChannel(RecognitionChannels.Channel.ASSIST, false),
                () -> store.setDragSettings(drag), () -> store.setDragSettings(drag, true),
                () -> store.setScopeCaptureEnabled(true), () -> store.setScopeLinkProbeEnabled(true))) {
            Map<String, ?> before = new HashMap<>(preferences.getAll());
            assertThrows(IllegalStateException.class, save::run);
            assertEquals("Rollback must also remove keys that were absent before this save", before, preferences.getAll());
        }
        assertEquals("Rejected writes must never refresh the host", 0, controlled.broadcasts);
    }

    @Test public void rejectedPolicySaveRestoresModeListsAndCompanionSwitchesTogether() {
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,
                Set.of("com.example.blocked"), Set.of("com.example.allowed"), true, true));
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        controlled.reject = true;
        controlled.broadcasts = 0;
        assertFalse(store.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, Set.of(), Set.of(), false, false));
        assertEquals(before, preferences.getAll());
        assertEquals(0, controlled.broadcasts);
        RuntimeSnapshot snapshot = RuntimeSnapshot.parse(RuntimeSnapshot.from(app));
        assertEquals(SourceScopePolicy.Mode.WHITELIST, snapshot.sourceScope.mode);
        assertEquals(Set.of("com.example.allowed"), snapshot.sourceScope.whitelist);
        assertTrue(snapshot.scopeCapture); assertTrue(snapshot.scopeLinkProbe);
    }

    @Test public void throwingCommitAlsoRestoresThePreviouslyActiveChoice() {
        controlled.throwAfterMemory = true;
        assertThrows(IllegalStateException.class, () -> store.setScopeLinkProbeEnabled(true));
        assertFalse(new ConfigStore(app).scopeLinkProbeEnabled());
        assertFalse(preferences.contains(ConfigStore.SCOPE_LINK_PROBE_KEY));
        assertEquals(0, controlled.broadcasts);
    }

    @Test public void failedLegacyCleanupRestoresTheOriginalPreferences() {
        preferences.edit().putString("generic_target", "com.example.old")
                .putString("rules", "old-rule-table").putInt("seed_version", 1).commit();
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        controlled.reject = true;
        assertThrows(IllegalStateException.class, () -> new ConfigStore(controlled));
        assertEquals(before, preferences.getAll());
    }

    @Test public void failedModeMigrationKeepsTheRetainedValueAndReturnsTheSafeFallback() {
        preferences.edit().putString("engine", "ICEBOX").commit();
        controlled.reject = true;
        assertEquals(ThawEngine.Mode.ROOT, store.mode());
        assertEquals("ICEBOX", preferences.getString("engine", null));
        assertTrue(store.recognitionEnabled());
    }

    @Test public void sameThreadPreferenceCallbacksCannotReadARejectedChoiceThroughConfigStore() {
        java.util.List<Boolean> observed = new java.util.ArrayList<>();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (saved, key) ->
                observed.add(new ConfigStore(controlled).enabled());
        preferences.registerOnSharedPreferenceChangeListener(listener);
        controlled.reject = true;
        try {
            assertThrows(IllegalStateException.class, () -> store.setEnabled(false));
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertFalse("Exercise a preference callback during the commit attempt", observed.isEmpty());
            assertTrue("Reentrant settings callbacks must only see accepted values", observed.stream().allMatch(Boolean::booleanValue));
        } finally { preferences.unregisterOnSharedPreferenceChangeListener(listener); }
    }

    @Test public void failedMemoryRestorationKeepsTheAcceptedViewUntilAConfirmedCompleteSave() {
        controlled.reject = true;
        controlled.failRestore = true;
        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> store.setEnabled(false));
        assertTrue("The failed save must expose uncertainty about recovery", failed.getMessage().contains("状态未确认"));
        ConfigStore reopened = new ConfigStore(controlled);
        assertTrue("A new reader of this store must keep the accepted ON choice", reopened.enabled());
        assertTrue(RuntimeSnapshot.parse(RuntimeSnapshot.from(controlled)).enabled);
        controlled.reject = false;
        controlled.failRestore = false;
        reopened.setCaptureEnabled(false);
        assertTrue("Recovery must rebuild accepted keys, not persist the rejected OFF choice", preferences.getBoolean("enabled", false));
        assertFalse(preferences.getBoolean("capture", true));
        // Once a complete save succeeds, ordinary direct corruption must remain visible/fail closed.
        preferences.edit().putString(SourceScopePolicy.KEY, "{bad}").commit();
        assertThrows(IllegalArgumentException.class, reopened::sourceScope);
    }

    @Test public void directReadersCannotObserveAnUnconfirmedCommit() throws Exception {
        assertReadWaitsForCommit(false, false);
    }

    @Test public void snapshotWaitsForSuccessfulCommitBeforeReadingTheWholeConfiguration() throws Exception {
        assertReadWaitsForCommit(true, true);
    }

    @Test public void snapshotWaitsForRejectedCommitAndReadsTheRestoredConfiguration() throws Exception {
        assertReadWaitsForCommit(true, false);
    }

    private void assertReadWaitsForCommit(boolean snapshot, boolean succeed) throws Exception {
        if (snapshot) RuntimeSnapshot.from(app); // Keep rule-store initialization outside the race.
        controlled.reject = !succeed;
        controlled.memoryChanged = new CountDownLatch(1);
        controlled.releaseCommit = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> saving = threads.submit(() -> {
                if (succeed) store.setEnabled(false);
                else assertThrows(IllegalStateException.class, () -> store.setEnabled(false));
            });
            assertTrue(controlled.memoryChanged.await(5, TimeUnit.SECONDS));
            CountDownLatch reading = new CountDownLatch(1);
            Future<Boolean> active = threads.submit(() -> {
                reading.countDown();
                return snapshot ? RuntimeSnapshot.parse(RuntimeSnapshot.from(app)).enabled : new ConfigStore(app).enabled();
            });
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertThrows("Readers must wait while disk outcome is unknown", TimeoutException.class,
                    () -> active.get(100, TimeUnit.MILLISECONDS));
            controlled.releaseCommit.countDown();
            saving.get(5, TimeUnit.SECONDS);
            assertEquals(!succeed, active.get(5, TimeUnit.SECONDS));
        } finally {
            controlled.releaseCommit.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
