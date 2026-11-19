package io.github.yudigaga.taplusbridge.probe;

import android.view.MotionEvent;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Observed configuration changes revoke the system echo lease without requiring a rejected claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = MotionEventDisplayShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeMenuConfigurationRevocationAndroidTest {
    @Test public void disabledRequestAndRestoredRequestHaveDistinctNonRevivalAssertions() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            var callback = closeOwnMenu(f);
            int closed = callback.calls;
            String originalRaw = storedRaw();
            String disabledRaw = publishRecognition(false);
            boolean rejected = f.retain();
            String restoredRaw = publishRecognition(true);
            boolean revived = f.retain();
            // Match the frozen failure's full observation; do not drain the main Looper to alter publication.
            f.advanceTo(2_000); f.drainWorker();

            assertEquals("actual UP200 must dispatch the original menu close", 1, closed);
            assertFalse("disabled raw is committed", RuntimeSnapshot.parse(disabledRaw).recognition);
            assertEquals("restoring the original configuration restores its content identity",
                    RuntimeSnapshot.parse(originalRaw).identity, RuntimeSnapshot.parse(restoredRaw).identity);
            assertFalse("first disabled retain must reject; rejected=" + rejected + " revived=" + revived, rejected);
            assertFalse("observed disabled configuration must permanently revoke; rejected=" + rejected
                    + " revived=" + revived, revived);
            assertEquals(1, callback.calls);
        }
    }

    @Test public void observingDisabledConfigurationWithoutClaimingRevokesTheOldEcho() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            var callback = closeOwnMenu(f);
            publishRecognition(false);
            RuntimeSnapshot disabled = f.configuration();
            publishRecognition(true);
            boolean revived = f.retain();
            f.advanceTo(2_000); f.drainWorker();

            assertNull("the system must actually observe the disabled snapshot", disabled);
            assertFalse("configuration observation revokes even without a disabled retain request", revived);
            assertEquals(1, callback.calls);
        }
    }

    @Test public void observingAMissingSnapshotCannotReviveAnEchoAfterRestoringTheSameRaw() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            var callback = closeOwnMenu(f);
            String original = storedRaw();
            assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("menu_queue_test", 0)
                    .edit().remove(RuntimeSnapshot.KEY).commit());
            RuntimeSnapshot unavailable = f.configuration();
            storeRaw(original);
            boolean revived = f.retain();
            f.advanceTo(2_000); f.drainWorker();

            assertNull("missing snapshot must be observed before restoration", unavailable);
            assertFalse("restoring identical raw cannot restore an observed-invalid echo", revived);
            assertEquals(1, callback.calls);
        }
    }

    @Test public void anObservedValidButDifferentIdentityAlsoRevokesTheOldEcho() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            var callback = closeOwnMenu(f);
            String original = storedRaw();
            var settings = new ConfigStore(RuntimeEnvironment.getApplication());
            settings.setCaptureEnabled(false);
            storeRaw(RuntimeSnapshot.from(RuntimeEnvironment.getApplication()));
            RuntimeSnapshot changed = f.configuration();
            settings.setCaptureEnabled(true);
            storeRaw(original);
            boolean revived = f.retain();
            f.advanceTo(2_000); f.drainWorker();

            assertNotNull("the changed snapshot still permits source recognition", changed);
            assertNotEquals(RuntimeSnapshot.parse(original).identity, changed.identity);
            assertFalse("the full observed configuration identity owns the echo lease", revived);
            assertEquals(1, callback.calls);
        }
    }

    @Test public void aFreshValidGestureAfterRestorationCanCloseAndRetainExactlyOnce() throws Exception {
        try (SystemMenuQueueFixture f = new SystemMenuQueueFixture()) {
            var old = closeOwnMenu(f);
            publishRecognition(false); RuntimeSnapshot disabled = f.configuration();
            publishRecognition(true); boolean oldEcho = f.retain();
            f.restoreMainWindow();
            var next = closeOwnMenu(f);
            int nextClosed = next.calls;
            boolean firstEcho = f.retain(), secondEcho = f.retain();
            f.advanceTo(2_000); f.drainWorker();

            assertNull(disabled); assertFalse(oldEcho); assertEquals(1, old.calls);
            assertEquals("a new complete gesture is not disabled by the old revocation", 1, nextClosed);
            assertTrue(firstEcho); assertFalse(secondEcho); assertEquals(1, next.calls);
        }
    }

    private ScopeCaptureWindowAndroidTest.FakeBackCallback closeOwnMenu(SystemMenuQueueFixture f) {
        f.begin();
        var callback = f.showMenu(101);
        f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput(); f.drainWorker();
        return callback;
    }

    private String publishRecognition(boolean enabled) {
        var context = RuntimeEnvironment.getApplication();
        new ConfigStore(context).setRecognitionEnabled(enabled);
        String raw = RuntimeSnapshot.from(context);
        storeRaw(raw);
        return raw;
    }

    private String storedRaw() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("menu_queue_test", 0)
                .getString(RuntimeSnapshot.KEY, null);
    }

    private void storeRaw(String raw) {
        assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("menu_queue_test", 0)
                .edit().putString(RuntimeSnapshot.KEY, raw).commit());
    }
}
