package io.github.yudigaga.taplusbridge.runtime;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadow.api.Shadow;
import io.github.yudigaga.taplusbridge.ui.MainActivity;
import androidx.lifecycle.ViewModelProvider;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class ModuleProcessLifecycleAndroidTest {
    private final AtomicInteger exits = new AtomicInteger();
    private ModuleProcessLifecycle lifecycle;

    @Before public void setUp() {
        lifecycle = ModuleProcessLifecycle.installForTest(new Handler(Looper.getMainLooper()), exits::incrementAndGet);
    }
    @After public void tearDown() { ModuleProcessLifecycle.resetForTest(); }
    private void advance(long millis) { ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idleFor(Duration.ofMillis(millis)); }

    @Test public void idleProcessExitsAfterThreeSeconds() {
        advance(2999); assertEquals(0, exits.get());
        advance(1); assertEquals(1, exits.get());
        advance(3000); assertEquals(1, exits.get());
    }
    @Test public void activeWorkPreventsExitAndFinishingStartsANewGracePeriod() {
        advance(2900);
        var work = ModuleProcessLifecycle.acquire();
        advance(10_000); assertEquals(0, exits.get());
        work.close(); advance(2999); assertEquals(0, exits.get());
        advance(1); assertEquals(1, exits.get());
    }
    @Test public void leasesAreIndependentAndClosingTwiceDoesNotReleaseOtherWork() {
        var first = ModuleProcessLifecycle.acquire(); var second = ModuleProcessLifecycle.acquire();
        first.close(); first.close();
        assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest());
        advance(4000); assertEquals(0, exits.get());
        second.close(); advance(3000); assertEquals(1, exits.get());
    }
    @Test public void abandonedHandoffExpiresButDoesNotImmediatelyKillTheProcess() {
        var handoff = ModuleProcessLifecycle.acquire(1000);
        advance(1000); assertEquals(0, ModuleProcessLifecycle.activeLeaseCountForTest());
        advance(2999); assertEquals(0, exits.get());
        handoff.close(); advance(1); assertEquals(1, exits.get());
    }
    @Test public void returningToAnActivityCancelsAnAlreadyScheduledExit() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        advance(2900); lifecycle.onActivityCreated(activity, null);
        advance(10_000); assertEquals(0, exits.get());
        lifecycle.onActivityStarted(activity); lifecycle.onActivityStopped(activity);
        advance(2900); lifecycle.onActivityStarted(activity);
        advance(10_000); assertEquals(0, exits.get());
        lifecycle.onActivityStopped(activity); lifecycle.onActivityDestroyed(activity);
        advance(3000); assertEquals(1, exits.get());
    }
    @Test public void anActivityRecreationDoesNotInterruptTheNextInstance() {
        Activity old = Robolectric.buildActivity(Activity.class).get();
        Activity replacement = Robolectric.buildActivity(Activity.class).get();
        lifecycle.onActivityCreated(old, null); lifecycle.onActivityStopped(old);
        advance(100); lifecycle.onActivityCreated(replacement, null); lifecycle.onActivityDestroyed(old);
        advance(4000); assertEquals(0, exits.get());
        lifecycle.onActivityDestroyed(replacement); advance(3000); assertEquals(1, exits.get());
    }
    @Test public void providerActivityRestartsTheIdleGracePeriod() {
        advance(2900); ModuleProcessLifecycle.touch(); advance(2900);
        assertEquals(0, exits.get()); advance(100); assertEquals(1, exits.get());
    }
    @Test public void leaseCanBeReleasedFromAnotherThread() throws Exception {
        var work = ModuleProcessLifecycle.acquire();
        Thread worker = new Thread(work::close); worker.start(); worker.join(1000);
        assertFalse(worker.isAlive()); advance(3000); assertEquals(1, exits.get());
    }
    @Test public void externalFileSelectionKeepsItsPendingResultUntilCancellation() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json"), 123, null);
        assertEquals(1, ModuleProcessLifecycle.activeLeaseCountForTest());
        advance(10_000); assertEquals(0, exits.get());
        var result = MainActivity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
        result.setAccessible(true); result.invoke(activity, 123, Activity.RESULT_CANCELED, null);
        assertEquals(0, ModuleProcessLifecycle.activeLeaseCountForTest());
        advance(3000); assertEquals(1, exits.get());
    }
    @Test public void externalFileSelectionSurvivesSettingsActivityRecreation() throws Exception {
        var owner = Robolectric.buildActivity(MainActivity.class).create().start();
        var retained = new ViewModelProvider(owner.get()).get(MainActivity.ExternalResults.class);
        owner.get().startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json"), 124, null);
        owner.recreate();
        assertSame(retained, new ViewModelProvider(owner.get()).get(MainActivity.ExternalResults.class));
        var pending = MainActivity.ExternalResults.class.getDeclaredField("pending"); pending.setAccessible(true);
        assertEquals(1, ((java.util.Map<?, ?>) pending.get(retained)).size());
        advance(4000); assertEquals(0, exits.get());
        var result = MainActivity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
        result.setAccessible(true); result.invoke(owner.get(), 124, Activity.RESULT_CANCELED, null);
        assertTrue(((java.util.Map<?, ?>) pending.get(retained)).isEmpty());
        owner.stop().destroy();
    }
    @Test public void onlyTheModulesOwnOrdinaryUidProcessesMayBeRetired() {
        String app = "io.github.yudigaga.taplusbridge";
        assertTrue(ModuleProcessLifecycle.eligible(app, app, 10123, 10123));
        assertTrue(ModuleProcessLifecycle.eligible(app, app + ":lens", 10123, 10123));
        assertFalse(ModuleProcessLifecycle.eligible(app, "system_server", 10123, 1000));
        assertFalse(ModuleProcessLifecycle.eligible(app, "com.miui.contentextension", 10123, 10123));
        assertFalse(ModuleProcessLifecycle.eligible("com.miui.contentextension", app, 10123, 10123));
        assertFalse(ModuleProcessLifecycle.eligible(app, app, 1000, 1000));
        assertFalse(ModuleProcessLifecycle.eligible(app, app + ":shizuku", 10123, 10123));
    }
}
