package io.github.yudigaga.taplusbridge.probe;

import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemAssistContentAndroidTest {
    private static final String SOURCE = "com.example.source", ACTIVITY = SOURCE + ".MainActivity";
    private ScopeCaptureWindow.Snapshot target;
    private RuntimeSnapshot config;
    private Context context;
    private ScopeCaptureWindowAndroidTest.Atms atms;
    private ScopeCaptureWindow windows;
    private AtomicReference<RuntimeSnapshot> current;
    private SystemAssistContent assist;
    private final List<Consumer<Bundle>> replies = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();
    private final QueueExecutor reader = new QueueExecutor();
    private final PhysicalTouchState physical = new PhysicalTouchState();
    private boolean windowValid = true;
    private SystemAssistContent.Result supplied = new SystemAssistContent.Result("real selected text", List.of());
    private int extracted;
    private Runnable duringExtract = () -> {};
    private long down, began;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", 0).edit().clear().commit();
        ConfigStore preferences = new ConfigStore(context);
        assertTrue(preferences.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, Set.of(), Set.of(), false, true));
        config = RuntimeSnapshot.parse(RuntimeSnapshot.from(context)); current = new AtomicReference<>(config);
        atms = new ScopeCaptureWindowAndroidTest.Atms();
        var window = new ScopeCaptureWindowAndroidTest.Window(); window.mActivityRecord.main = window;
        window.mAttrs.packageName = SOURCE;
        window.mActivityRecord.mActivityComponent = new ComponentName(SOURCE, ACTIVITY);
        atms.mWindowManager.mRoot.display.hit = window; atms.mWindowManager.mRoot.display.mCurrentFocus = window;
        windows = new ScopeCaptureWindow(ScopeCaptureWindowAndroidTest.Atms.class,
                new ScopeCaptureWindow.CaptureBackend() {
                    public Class<?> surfaceType() { return Object.class; }
                    public android.graphics.Bitmap capture(Object surface, Object[] excluded, int uid,
                                                           android.graphics.Rect crop, float scale) {
                        throw new AssertionError("Assist collection must not capture pixels");
                    }
                });
        target = windows.inspect(atms, 0, 50, 200);
        assist = new SystemAssistContent(new Handler(Looper.getMainLooper()), current::get,
                picked -> { if (!windowValid) throw new IllegalStateException("capture_window_changed"); },
                (picked, receiver) -> { replies.add(receiver); return true; },
                (structure, picked) -> { extracted++; duringExtract.run(); return supplied; },
                reader, logs::add, physical);
        down = SystemClock.uptimeMillis(); began = SystemClock.elapsedRealtime();
        physical.event(0, 1, down, down, PhysicalTouchState.DOWN, down);
    }

    @Test public void optionalBackendDoesNotResolveAnUninitializedAtmsDuringConstruction() {
        int[] lookups = {0};
        SystemAssistContent optional = new SystemAssistContent(new Handler(Looper.getMainLooper()), () -> context,
                () -> { lookups[0]++; return atms; }, current::get, windows, logs::add, physical);
        assertEquals(0, lookups[0]);
        optional.arm(target, config, down, began, began + 500, 1);
        advance(500);
        assertTrue(lookups[0] > 0);
        assertFalse(optional.pending(target, config, began));
        assertEquals(SystemAssistContent.Result.EMPTY, optional.read(target, config, began));
    }

    @Test public void disabledAssistChannelNeverRequestsStructureAndRevokesPreviouslyCollectedContent() {
        context.getSharedPreferences("config",0).edit().putString("recognition_channels",
                "{\"native\":true,\"assist\":false,\"ocr\":true,\"server\":true,\"segment\":true,\"clipboard\":true}").commit();
        config=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));current.set(config);
        arm();advance(500);
        assertTrue("Turning structure recognition off must prevent the actual source request",replies.isEmpty());
        assertEquals(SystemAssistContent.Result.EMPTY,assist.read(target,config,began));
    }

    @Test public void coalescesSameGestureAndKeepsLazyStructureWorkOffTheInputWorker() {
        arm(); arm();
        assertTrue(assist.pending(target, config, began));
        advance(499); assertEquals(0, replies.size());
        advance(1); assertEquals(1, replies.size());
        replies.get(0).accept(data(SOURCE, ACTIVITY));
        assertEquals(0, extracted); assertEquals(1, reader.tasks.size());
        assertEquals(SystemAssistContent.Result.EMPTY, assist.read(target, config, began));
        reader.run();
        assertEquals("real selected text", assist.read(target, config, began).text());
        assertFalse(assist.pending(target, config, began));
    }

    @Test public void emptyStructureContentIsLoggedAsEmptyInsteadOfReceived() {
        supplied = SystemAssistContent.Result.EMPTY;
        arm(); advance(500); replies.get(0).accept(data(SOURCE, ACTIVITY)); reader.run();
        assertEquals(SystemAssistContent.Result.EMPTY, assist.read(target, config, began));
        assertTrue(logs.contains("assist capture stage=empty"));
        assertFalse(logs.contains("assist capture stage=received"));
    }

    @Test public void anActualHrefWithoutVisibleTextStillCountsAsReceivedContent() {
        supplied = new SystemAssistContent.Result("", List.of("https://example.com/selected"));
        arm(); advance(500); replies.get(0).accept(data(SOURCE, ACTIVITY)); reader.run();
        assertEquals("https://example.com/selected", assist.read(target, config, began).deliveryText());
        assertTrue(logs.contains("assist capture stage=received"));
        assertFalse(logs.contains("assist capture stage=empty"));
    }

    @Test public void oneMillisecondElapsedConversionDifferenceCoalescesTheSamePhysicalDown() throws Exception {
        arm(); advance(500); assertEquals(1, replies.size());
        assist.arm(target, config, down, began + 1, began + 500, 1);
        advance(0); assertEquals(1, replies.size());
        assertTrue(pending(began, down));
        assertTrue(pending(began + 1, down));
        replies.get(0).accept(data(SOURCE, ACTIVITY)); reader.run();
        assertEquals("real selected text", read(began, down).text());
        assertEquals("real selected text", read(began + 1, down).text());
        assertEquals(SystemAssistContent.Result.EMPTY, assist.read(target, config, began + 1));
    }

    @Test public void downIdentityReadAndPendingNeverBorrowContentFromAnotherPhysicalDown() throws Exception {
        arm();
        assertFalse(pending(began, down + 1));
        cancelPending(began, down + 1);
        assertTrue(pending(began + 1, down));
        advance(500); replies.get(0).accept(data(SOURCE, ACTIVITY)); reader.run();
        assertEquals(SystemAssistContent.Result.EMPTY, read(began, down + 1));
        assertEquals("real selected text", read(began + 1, down).text());
    }

    @Test public void exactDownCancellationAcceptsConversionJitterWithoutCancellingADifferentDown() throws Exception {
        arm(); cancelPending(began + 1, down); advance(500);
        assertEquals(0, replies.size());
    }

    @Test public void shortReleasedGestureDoesNotIssueADelayedRequest() {
        arm(); advance(100);
        long now = SystemClock.uptimeMillis();
        physical.event(0, 1, down, now, PhysicalTouchState.UP, now);
        physical.markReleasedElapsed(SystemClock.elapsedRealtime());
        advance(500);
        assertEquals(0, replies.size());
        assertFalse(assist.pending(target, config, began));
    }

    @Test public void cancellingPendingGesturePreventsItsRequestButStaleCancellationCannotClearNewOne() {
        arm(); assist.cancelPending(target, config, began); advance(500);
        assertEquals(0, replies.size());
        long next = SystemClock.elapsedRealtime();
        newArm(next);
        assist.cancelPending(target, config, began); advance(500);
        assertEquals(1, replies.size());
    }

    @Test public void staleCallbackAndReadCannotReplaceOrEraseNewGestureContent() {
        arm(); advance(500);
        long next = SystemClock.elapsedRealtime();
        newArm(next);
        replies.get(0).accept(data(SOURCE, ACTIVITY)); assertTrue(reader.tasks.isEmpty());
        advance(500); replies.get(1).accept(data(SOURCE, ACTIVITY)); reader.run();
        assertEquals(SystemAssistContent.Result.EMPTY, assist.read(target, config, began));
        assertEquals("real selected text", assist.read(target, config, next).text());
    }

    @Test public void responseAfterTwoSecondDeadlineCannotPopulateTheResult() {
        arm(); advance(500); advance(2001);
        assertFalse(assist.pending(target, config, began));
        replies.get(0).accept(data(SOURCE, ACTIVITY));
        assertTrue(reader.tasks.isEmpty());
        assertEquals(SystemAssistContent.Result.EMPTY, assist.read(target, config, began));
    }

    @Test public void differentReturnedActivityIsRejectedBeforeAnyTraversal() {
        arm(); advance(500); replies.get(0).accept(data(SOURCE, SOURCE + ".OtherActivity")); reader.run();
        assertEquals(0, extracted);
        assertEquals(SystemAssistContent.Result.EMPTY, assist.read(target, config, began));
    }

    @Test public void windowAndConfigurationAreValidatedBeforeRequestAndAfterTraversal() {
        arm(); windowValid = false; advance(500); assertEquals(0, replies.size());
        windowValid = true;
        long next = SystemClock.elapsedRealtime();
        newArm(next);
        advance(500); replies.get(0).accept(data(SOURCE, ACTIVITY));
        duringExtract = () -> windowValid = false; reader.run();
        assertEquals(SystemAssistContent.Result.EMPTY, assist.read(target, config, next));
        current.set(null);
        assertFalse(assist.pending(target, config, next));
    }

    @Test public void queuedCallbackIsDiscardedWhenTheGestureIsCancelled() {
        arm(); advance(500); replies.get(0).accept(data(SOURCE, ACTIVITY));
        assist.cancel(); reader.run();
        assertEquals(0, extracted);
        assertEquals(SystemAssistContent.Result.EMPTY, assist.read(target, config, began));
    }

    @Test public void anOldQueuedDownCannotArmAfterAnUnrelatedNewPhysicalDown() {
        advance(100);
        long newerDown = SystemClock.uptimeMillis();
        physical.event(0, 1, newerDown, newerDown, PhysicalTouchState.DOWN, newerDown);
        assist.cancel();
        arm(); advance(500);
        assertEquals(0, replies.size());
        assertFalse(assist.pending(target, config, began));
    }

    @Test public void callbackFromTheOldStreamCannotBeAcceptedDuringANewPhysicalTouch() {
        arm(); advance(500);
        long newerDown = SystemClock.uptimeMillis();
        physical.event(0, 1, newerDown, newerDown, PhysicalTouchState.DOWN, newerDown);
        replies.get(0).accept(data(SOURCE, ACTIVITY));
        assertTrue(reader.tasks.isEmpty());
        assertEquals(SystemAssistContent.Result.EMPTY, assist.read(target, config, began));
    }

    @Test public void productionPhysicalCollectorRejectsArmingWithoutTheActualDeviceIdentity() {
        assist.arm(target, config, down, began, began + 500);
        advance(500); assertEquals(0, replies.size());
    }

    private void arm() { assist.arm(target, config, down, began, began + 500, 1); }
    private boolean pending(long at, long physicalDown) throws Exception {
        return (Boolean) SystemAssistContent.class.getMethod("pending", ScopeCaptureWindow.Snapshot.class,
                RuntimeSnapshot.class, long.class, long.class).invoke(assist, target, config, at, physicalDown);
    }
    private SystemAssistContent.Result read(long at, long physicalDown) throws Exception {
        return (SystemAssistContent.Result) SystemAssistContent.class.getMethod("read", ScopeCaptureWindow.Snapshot.class,
                RuntimeSnapshot.class, long.class, long.class).invoke(assist, target, config, at, physicalDown);
    }
    private void cancelPending(long at, long physicalDown) throws Exception {
        SystemAssistContent.class.getMethod("cancelPending", ScopeCaptureWindow.Snapshot.class,
                RuntimeSnapshot.class, long.class, long.class).invoke(assist, target, config, at, physicalDown);
    }
    private void newArm(long next) {
        long nextDown = SystemClock.uptimeMillis();
        physical.event(0, 1, nextDown, nextDown, PhysicalTouchState.DOWN, nextDown);
        assist.arm(target, config, nextDown, next, next + 500, 1);
    }
    private static void advance(long millis) { ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS); }
    private static Bundle data(String source, String activity) {
        AssistStructure structure = new AssistStructure();
        ReflectionHelpers.setField(structure, "mActivityComponent", new ComponentName(source, activity));
        Bundle data = new Bundle(); data.putParcelable("structure", structure); return data;
    }
    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void run() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
}
