package io.github.yudigaga.taplusbridge.probe;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class HostProbeWindowAndroidTest {
    private FakeService service;
    private long captured;

    @Before public void setup() {
        service = freshService(RuntimeEnvironment.getApplication());
        captured = SystemClock.elapsedRealtime();
    }

    private HostProbeWindow prepare() throws ReflectiveOperationException {
        return HostProbeWindow.prepare(service, CaptureLease.WECHAT, captured);
    }

    @Test public void readsCurrentSourceGeometryWithoutHidingTheHostOrChangingItsAlpha() throws Exception {
        int splashFlags = service.mSplashFloatView.wmParams.flags;
        int mainFlags = service.mMainFloatView.wmParams.flags;
        try (HostProbeWindow window = prepare()) {
            Bundle data = window.snapshot();
            assertEquals(service.activity, data.getString("source_activity"));
            assertEquals(220f, data.getFloat("x"), 0f);
            assertEquals(420f, data.getFloat("y"), 0f);
            assertEquals(100, data.getInt("left")); assertEquals(800, data.getInt("bottom"));
            assertEquals(1080, data.getInt("width")); assertEquals(2400, data.getInt("height"));
            assertEquals(0, data.getInt("display")); assertEquals(0, data.getInt("rotation"));
            assertEquals(captured, data.getLong("captured_elapsed"));
            assertEquals(View.VISIBLE, service.mSplashFloatView.mMainView.getVisibility());
            assertEquals(View.INVISIBLE, service.mMainFloatView.mMainView.getVisibility());
            assertEquals(1f, service.mSplashFloatView.wmParams.alpha, 0f);
            assertEquals(0, service.mSplashFloatView.wmParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            assertEquals(splashFlags, service.mSplashFloatView.wmParams.flags);
            assertEquals(mainFlags, service.mMainFloatView.wmParams.flags);
        }
        assertEquals(splashFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(mainFlags, service.mMainFloatView.wmParams.flags);
    }

    @Test public void usesFirstFingerAndNeverTheTwoFingerMidpoint() throws Exception {
        service.first = new PointF(120f, 310f);
        service.injector = new Point(400, 600);
        service.second = new PointF(1060f, 2300f);
        try (HostProbeWindow window = prepare()) {
            assertEquals(120f, window.snapshot().getFloat("x"), 0f);
            assertEquals(310f, window.snapshot().getFloat("y"), 0f);
        }
        assertEquals(0, service.secondCalls);
    }

    @Test public void changedNativePointInvalidatesSnapshotAndRestoresFlags() throws Exception {
        int splashFlags = service.mSplashFloatView.wmParams.flags;
        int mainFlags = service.mMainFloatView.wmParams.flags;
        HostProbeWindow window = prepare();
        window.passthrough();
        service.first = new PointF(650f, 780f);
        service.injector = new Point(650, 780);
        assertThrows(IllegalStateException.class, window::snapshot);
        assertEquals(splashFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(mainFlags, service.mMainFloatView.wmParams.flags);
    }

    @Test public void systemFallbackPassthroughNeedsNoCardGeometryAndKeepsInputCoordinates() throws Exception {
        PointF first = service.first; Point injector = service.injector;
        int splashFlags = service.mSplashFloatView.wmParams.flags;
        int mainFlags = service.mMainFloatView.wmParams.flags;
        service.source = null; service.activity = null; service.rect = null; service.failGeometryAccess = true;
        service.mMainFloatView.mIsShowing = false;
        try (AutoCloseable ignored = passthroughOwnedWindows(service)) {
            for (FakeFloatWindow window : List.of(service.mSplashFloatView, service.mMainFloatView)) {
                assertEquals(0f, window.wmParams.alpha, 0f);
                assertNotEquals(0, window.wmParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                assertEquals(0, window.wmParams.flags & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
            }
            assertSame(first, service.first); assertSame(injector, service.injector);
            assertEquals(220f, service.first.x, 0f); assertEquals(420f, service.first.y, 0f);
        }
        assertEquals(splashFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(mainFlags, service.mMainFloatView.wmParams.flags);
        assertEquals(1f, service.mSplashFloatView.wmParams.alpha, 0f);
        assertEquals(1f, service.mMainFloatView.wmParams.alpha, 0f);
    }

    @Test public void passthroughReleasesFocusableHostPanelsUntilTheLeaseCloses() throws Exception {
        service.mMainFloatView.mMainView.setVisibility(View.VISIBLE);
        service.mMainFloatView.wmParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        int original = service.mMainFloatView.wmParams.flags;
        try (AutoCloseable ignored = passthroughOwnedWindows(service)) {
            assertNotEquals("source focus must be restored before the fallback click", 0,
                    service.mMainFloatView.wmParams.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        assertEquals(original, service.mMainFloatView.wmParams.flags);
        try (HostProbeWindow window = prepare()) {
            window.passthrough();
            assertNotEquals("native probes must also release covering panel focus", 0,
                    service.mMainFloatView.wmParams.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        assertEquals(original, service.mMainFloatView.wmParams.flags);
    }

    @Test public void systemFallbackWithNoAttachedOwnedWindowsIsANoop() throws Exception {
        service.mSplashFloatView.mMainView.attached = false;
        service.mMainFloatView.mMainView.attached = false;
        service.failGeometryAccess = true;
        try (AutoCloseable ignored = passthroughOwnedWindows(service)) { assertUntouched(); }
        assertUntouched();
    }

    @Test public void systemFallbackPassthroughRejectsOtherPackages() {
        assertThrows(IllegalStateException.class,
                () -> passthroughOwnedWindows(RuntimeEnvironment.getApplication()));
        assertUntouched();
    }

    @Test public void failedSystemFallbackPassthroughRollsBackEveryChangedWindow() {
        int splashFlags = service.mSplashFloatView.wmParams.flags;
        int mainFlags = service.mMainFloatView.wmParams.flags;
        service.mMainFloatView.updater.failAt = 1;
        assertThrows(IllegalStateException.class, () -> passthroughOwnedWindows(service));
        assertEquals(splashFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(mainFlags, service.mMainFloatView.wmParams.flags);
        assertEquals(1f, service.mSplashFloatView.wmParams.alpha, 0f);
        assertEquals(1f, service.mMainFloatView.wmParams.alpha, 0f);
        assertEquals(2, service.mSplashFloatView.updater.updates.size());
        assertEquals(2, service.mMainFloatView.updater.updates.size());
    }

    @Test public void newerHostWindowFlagsAreNotOverwrittenWhenSystemFallbackCloses() throws Exception {
        AutoCloseable lease = passthroughOwnedWindows(service);
        int updatedFlags = service.mSplashFloatView.wmParams.flags | WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        service.mSplashFloatView.wmParams.flags = updatedFlags;
        lease.close(); lease.close();
        assertEquals(updatedFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(0f, service.mSplashFloatView.wmParams.alpha, 0f);
        assertEquals(1, service.mSplashFloatView.updater.updates.size());
        assertEquals(1f, service.mMainFloatView.wmParams.alpha, 0f);
    }

    @Test public void replacementHostWindowIsNotOverwrittenWhenSystemFallbackCloses() throws Exception {
        FakeFloatWindow original = service.mSplashFloatView;
        AutoCloseable lease = passthroughOwnedWindows(service);
        service.mSplashFloatView = new FakeFloatWindow(service, original.mMainView.display, 10, 10, 200, 100);
        int replacementFlags = service.mSplashFloatView.wmParams.flags;
        lease.close();
        assertEquals(1, original.updater.updates.size());
        assertEquals(replacementFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(1f, service.mSplashFloatView.wmParams.alpha, 0f);
        assertTrue(service.mSplashFloatView.updater.updates.isEmpty());
    }

    @Test public void detachedOwnedWindowRestoresSavedValuesWithoutUpdatingItsDetachedView() throws Exception {
        FakeFloatWindow window = service.mSplashFloatView;
        int flags = window.wmParams.flags;
        AutoCloseable lease = passthroughOwnedWindows(service);
        window.mMainView.attached = false;
        lease.close();
        assertEquals(flags, window.wmParams.flags);
        assertEquals(1f, window.wmParams.alpha, 0f);
        assertEquals(1, window.updater.updates.size());
    }

    @Test public void fallsBackOnlyToAnInjectorPointInsideTheOriginalCard() throws Exception {
        service.first = new PointF(Float.NaN, 400f);
        service.injector = new Point(650, 780);
        try (HostProbeWindow window = prepare()) {
            assertEquals(650f, window.snapshot().getFloat("x"), 0f);
            assertEquals(780f, window.snapshot().getFloat("y"), 0f);
        }
    }

    @Test public void missingOrOutsidePointsAreRejectedBeforeAnyWindowChange() throws Exception {
        service.first = new PointF(700f, 800f);
        service.injector = new Point(99, 400);
        assertThrows(IllegalStateException.class, this::prepare);
        service.first = null; service.injector = null;
        assertThrows(IllegalStateException.class, this::prepare);
        assertUntouched();
    }

    @Test public void staleAndFutureCaptureTimesAreRejectedBeforeWindowChange() throws Exception {
        ShadowSystemClock.advanceBy(Duration.ofMillis(30_001));
        assertThrows(IllegalStateException.class, this::prepare);
        captured = SystemClock.elapsedRealtime() + 1;
        assertThrows(IllegalStateException.class, this::prepare);
        assertUntouched();
    }

    @Test public void missingOrOffScreenBoundsAreNotClippedOrGuessed() throws Exception {
        for (Rect rect : new Rect[] { null, new Rect(), new Rect(-1, 0, 500, 500),
                new Rect(0, 0, 1081, 200), new Rect(0, 0, 200, 2401) }) {
            service.rect = rect;
            assertThrows(IllegalStateException.class, this::prepare);
        }
        assertUntouched();
    }

    @Test public void wrongSourceOrSourceActivityCannotPrepare() throws Exception {
        service.source = "com.example.other";
        assertThrows(IllegalStateException.class, this::prepare);
        service.source = CaptureLease.WECHAT; service.activity = "com.example.other.MainActivity";
        assertThrows(IllegalStateException.class, this::prepare);
        assertUntouched();
    }

    @Test public void opaqueOwnedSplashReleasesThePointWithoutForgingTrustedFlags() throws Exception {
        service.mSplashFloatView.mMainView.place(100, 300, 500, 500);
        int flags=service.mSplashFloatView.wmParams.flags;
        try(HostProbeWindow window=prepare()) {
            window.passthrough();
            assertEquals(0f,service.mSplashFloatView.wmParams.alpha,0f);
            assertNotEquals(0,service.mSplashFloatView.wmParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            assertEquals((flags & ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,service.mSplashFloatView.wmParams.flags);
        }
        assertEquals(flags,service.mSplashFloatView.wmParams.flags);
        assertEquals(View.VISIBLE, service.mSplashFloatView.mMainView.getVisibility());
        assertEquals(1f, service.mSplashFloatView.wmParams.alpha, 0f);
        assertEquals(2,service.mSplashFloatView.updater.updates.size());
    }

    @Test public void preparationLogsOnlyAWhitelistedReason() {
        assertEquals("unsupported_covering_window",HostProbeWindow.failureReason(new IllegalStateException("unsupported_covering_window")));
        assertEquals("geometry_unavailable",HostProbeWindow.failureReason(new IllegalStateException("PRIVATE_TEXT https://private.invalid/?secret=x")));
    }

    @SuppressWarnings("deprecation")
    @Test public void inspectedFullScreenMainWindowCanReleaseTheSourcePointAndRestore() throws Exception {
        // Stock 4.1.3 uses TYPE_PHONE, MATCH_PARENT and alpha=1 for TaplusMainFloatView.
        service.mMainFloatView.mMainView.setVisibility(View.VISIBLE);
        service.mMainFloatView.mMainView.place(0, 0, 1080, 2400);
        service.mMainFloatView.wmParams.type = WindowManager.LayoutParams.TYPE_PHONE;
        int flags=service.mMainFloatView.wmParams.flags;
        try(HostProbeWindow window=prepare()) {
            window.passthrough();
            assertEquals(0f,service.mMainFloatView.wmParams.alpha,0f);
            assertNotEquals(0,service.mMainFloatView.wmParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            assertEquals(220f,window.snapshot().getFloat("x"),0f);
        }
        assertEquals(1f,service.mMainFloatView.wmParams.alpha,0f);
        assertEquals(flags,service.mMainFloatView.wmParams.flags);
    }

    @Test public void twoTranslucentCoveringWindowsGetTemporaryFlagsThenRestoreExactlyOnce() throws Exception {
        coverBoth(0.4f);
        int firstFlags = service.mSplashFloatView.wmParams.flags;
        int secondFlags = service.mMainFloatView.wmParams.flags;
        HostProbeWindow window = prepare();
        window.passthrough();
        for (FakeFloatWindow host : List.of(service.mSplashFloatView, service.mMainFloatView)) {
            assertNotEquals(0, host.wmParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            assertEquals(0, host.wmParams.flags & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
            assertEquals(0f, host.wmParams.alpha, 0f);
            assertEquals(1, host.updater.updates.size());
        }
        window.close(); window.close();
        assertEquals(firstFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(secondFlags, service.mMainFloatView.wmParams.flags);
        assertEquals(.4f,service.mSplashFloatView.wmParams.alpha,0f);
        assertEquals(.4f,service.mMainFloatView.wmParams.alpha,0f);
        assertEquals(2, service.mSplashFloatView.updater.updates.size());
        assertEquals(2, service.mMainFloatView.updater.updates.size());
    }

    @Test public void twoOwnedOverlaysDoNotAccumulateOcclusionAtTheSourcePoint() throws Exception {
        coverBoth(0.6f);
        try(HostProbeWindow window=prepare()) {
            window.passthrough();
            assertEquals(0f,service.mSplashFloatView.wmParams.alpha,0f);
            assertEquals(0f,service.mMainFloatView.wmParams.alpha,0f);
        }
        assertEquals(.6f,service.mSplashFloatView.wmParams.alpha,0f);
        assertEquals(.6f,service.mMainFloatView.wmParams.alpha,0f);
    }

    @Test public void unverifiedLegacyWindowCoveringTheCardIsNotAssumedTrusted() throws Exception {
        service.mMainFloatView.mMainView.setVisibility(View.VISIBLE);
        service.mMainFloatView.wmParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        assertThrows(IllegalStateException.class, this::prepare);
        assertUntouched();
    }

    @Test public void throwingSecondWindowUpdateRollsBackBothOriginalFlags() throws Exception {
        int firstFlags = service.mSplashFloatView.wmParams.flags;
        int secondFlags = service.mMainFloatView.wmParams.flags;
        HostProbeWindow window = prepare();
        service.mMainFloatView.updater.failAt = 1;
        assertThrows(IllegalStateException.class, window::passthrough);
        assertEquals(firstFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(secondFlags, service.mMainFloatView.wmParams.flags);
        assertEquals(2, service.mSplashFloatView.updater.updates.size());
        assertEquals(2, service.mMainFloatView.updater.updates.size());
    }

    @Test public void restorationAttemptsTheOtherWindowEvenWhenOneUpdateFails() throws Exception {
        int firstFlags = service.mSplashFloatView.wmParams.flags;
        int secondFlags = service.mMainFloatView.wmParams.flags;
        HostProbeWindow window = prepare();
        window.passthrough();
        service.mMainFloatView.updater.failAt = 2;
        assertThrows(IllegalStateException.class, window::close);
        assertEquals(firstFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(secondFlags, service.mMainFloatView.wmParams.flags);
        assertEquals(2, service.mSplashFloatView.updater.updates.size());
        window.close();
        assertEquals(2, service.mSplashFloatView.updater.updates.size());
    }

    @Test public void sourceOrBoundsChangesInvalidateSnapshotAndRestoreTheWindows() throws Exception {
        int flags = service.mSplashFloatView.wmParams.flags;
        HostProbeWindow window = prepare();
        window.passthrough();
        service.source = CaptureLease.QQ;
        assertThrows(IllegalStateException.class, window::snapshot);
        assertEquals(flags, service.mSplashFloatView.wmParams.flags);
        assertEquals(2, service.mMainFloatView.updater.updates.size());
    }

    @Test public void replacedWindowCannotReceiveOldFlagsOrProduceAStaleSnapshot() throws Exception {
        FakeFloatWindow original = service.mSplashFloatView;
        int flags = original.wmParams.flags;
        HostProbeWindow window = prepare();
        service.mSplashFloatView = new FakeFloatWindow(service, original.mMainView.display, 50, 50, 100, 100);
        int replacementFlags = service.mSplashFloatView.wmParams.flags;
        assertThrows(IllegalStateException.class, window::snapshot);
        assertEquals(flags, original.wmParams.flags);
        assertEquals(replacementFlags, service.mSplashFloatView.wmParams.flags);
        assertEquals(0, service.mSplashFloatView.updater.updates.size());
    }

    @Test public void rotationOrScreenChangesInvalidateSnapshotAndRestoreFlags() throws Exception {
        int flags = service.mSplashFloatView.wmParams.flags;
        HostProbeWindow window = prepare();
        ShadowDisplay display = Shadow.extract(service.mSplashFloatView.mMainView.display);
        display.setRotation(Surface.ROTATION_90);
        assertThrows(IllegalStateException.class, window::snapshot);
        assertEquals(flags, service.mSplashFloatView.wmParams.flags);
    }

    @Test public void onlyTheDefaultUnrotatedPortraitDisplayIsSupported() throws Exception {
        ShadowDisplay display = Shadow.extract(service.mSplashFloatView.mMainView.display);
        display.setRotation(Surface.ROTATION_90);
        assertThrows(IllegalStateException.class, this::prepare);
        display.setRotation(Surface.ROTATION_0);
        int otherId = ShadowDisplayManager.addDisplay("w1080dp-h2400dp");
        Display other = service.getSystemService(DisplayManager.class).getDisplay(otherId);
        service.mSplashFloatView.mMainView.display = other;
        assertThrows(IllegalStateException.class, this::prepare);
        assertUntouched();
    }

    @Test public void snapshotIsDefensiveAndClosedLeaseCannotBeReused() throws Exception {
        HostProbeWindow window = prepare();
        Bundle first = window.snapshot(); first.putFloat("x", 1f); first.putString("source_activity", "other");
        Bundle second = window.snapshot();
        assertEquals(220f, second.getFloat("x"), 0f);
        assertEquals(service.activity, second.getString("source_activity"));
        assertFalse(second.containsKey("url")); assertFalse(second.containsKey("nonce"));
        window.close();
        assertThrows(IllegalStateException.class, window::snapshot);
    }

    @Test public void detachedWindowIsRestoredInMemoryWithoutUpdatingADetachedView() throws Exception {
        FakeFloatWindow first = service.mSplashFloatView;
        int flags = first.wmParams.flags;
        HostProbeWindow window = prepare();
        window.passthrough();
        first.mMainView.attached = false;
        assertThrows(IllegalStateException.class, window::snapshot);
        assertEquals(flags, first.wmParams.flags);
        assertEquals(1, first.updater.updates.size());
    }

    @Test public void anotherWindowAttachingDuringTheHandshakeInvalidatesTheSnapshot() throws Exception {
        service.mMainFloatView.mMainView.attached = false;
        int flags = service.mSplashFloatView.wmParams.flags;
        int untouchedMainFlags = service.mMainFloatView.wmParams.flags;
        HostProbeWindow window = prepare();
        service.mMainFloatView.mMainView.attached = true;
        assertThrows(IllegalStateException.class, window::snapshot);
        assertEquals(flags, service.mSplashFloatView.wmParams.flags);
        assertEquals(untouchedMainFlags, service.mMainFloatView.wmParams.flags);
        assertTrue(service.mMainFloatView.updater.updates.isEmpty());
    }

    @Test public void windowOperationsRequireTheMainThread() throws Exception {
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { prepare(); } catch (Throwable failure) { result.set(failure); }
        });
        worker.start(); worker.join();
        assertTrue(result.get() instanceof IllegalStateException);
        assertUntouched();
    }

    private void coverBoth(float alpha) {
        for (FakeFloatWindow window : List.of(service.mSplashFloatView, service.mMainFloatView)) {
            window.mMainView.setVisibility(View.VISIBLE);
            window.mMainView.place(100, 300, 500, 500);
            window.wmParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            window.wmParams.alpha = alpha;
        }
    }
    private void assertUntouched() {
        assertTrue(service.mSplashFloatView.updater.updates.isEmpty());
        assertTrue(service.mMainFloatView.updater.updates.isEmpty());
    }

    private static AutoCloseable passthroughOwnedWindows(Object service) throws Exception {
        Method entry = null;
        for (Method method : HostProbeWindow.class.getMethods()) {
            if (method.getName().equals("passthroughOwnedWindows")
                    && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{Object.class})) entry = method;
        }
        assertNotNull("Owned-window passthrough API is missing", entry);
        try { return (AutoCloseable) entry.invoke(null, service); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            if (failed.getCause() instanceof Error error) throw error;
            throw new AssertionError(failed.getCause());
        }
    }

    /** Shared fixture for the real host-window and handshake boundary tests. */
    public static FakeService freshService(Context context) {
        return new FakeService(context);
    }

    public static final class FakeService extends ContextWrapper {
        public String source = CaptureLease.WECHAT;
        public String activity = "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI";
        public Rect rect = new Rect(100, 300, 700, 800);
        public PointF first = new PointF(220f, 420f);
        public PointF second = new PointF(1050f, 2200f);
        public Point injector = new Point(250, 450);
        public int secondCalls;
        public boolean failGeometryAccess;
        public FakeFloatWindow mSplashFloatView;
        public FakeFloatWindow mMainFloatView;
        public FakeService(Context context) {
            super(context);
            Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            ShadowDisplay shadow = Shadow.extract(display);
            shadow.setRealWidth(1080); shadow.setRealHeight(2400); shadow.setRotation(Surface.ROTATION_0);
            mSplashFloatView = new FakeFloatWindow(this, display, 800, 200, 200, 100);
            mMainFloatView = new FakeFloatWindow(this, display, 0, 0, 1080, 2400);
            mMainFloatView.wmParams.type = WindowManager.LayoutParams.TYPE_PHONE;
            mMainFloatView.mMainView.setVisibility(View.INVISIBLE);
        }
        @Override public String getPackageName() { return BridgeContract.HOST_PACKAGE; }
        private void geometryRead() { if (failGeometryAccess) throw new AssertionError("System fallback read card geometry"); }
        public String getInjectorPackage() { geometryRead(); return source; }
        public String getInjectorActivity() { geometryRead(); return activity; }
        public Rect getContentRect() { geometryRead(); return rect; }
        public PointF getFirstTouchPoint() { geometryRead(); return first; }
        public Point getInjectorPoint() { geometryRead(); return injector; }
        public PointF getSecondTouchPoint() { geometryRead(); secondCalls++; return second; }
    }

    public static class FakeWindowBase {
        public FakeView mMainView;
        public WindowManager mWindowManager;
        public WindowManager.LayoutParams wmParams = new WindowManager.LayoutParams();
        public boolean mIsShowing = true;
    }
    public static final class FakeFloatWindow extends FakeWindowBase {
        public final Updater updater;
        public FakeFloatWindow(Context context, Display display, int x, int y, int width, int height) {
            mMainView = new FakeView(context, display); mMainView.place(x, y, width, height);
            wmParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            wmParams.alpha = 1f;
            wmParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            updater = new Updater(display); mWindowManager = updater.manager;
        }
    }

    public static final class FakeView extends View {
        public boolean attached = true;
        public Display display;
        public int windowVisibility = View.VISIBLE;
        private int screenX, screenY;
        FakeView(Context context, Display display) { super(context); this.display = display; }
        public void place(int x, int y, int width, int height) {
            screenX = x; screenY = y; layout(0, 0, width, height);
        }
        @Override public Display getDisplay() { return display; }
        @Override public boolean isAttachedToWindow() { return attached; }
        @Override public int getWindowVisibility() { return windowVisibility; }
        @Override public void getLocationOnScreen(int[] location) { location[0] = screenX; location[1] = screenY; }
    }

    public static final class Updater {
        public final List<Integer> updates = new ArrayList<>();
        public int failAt = -1;
        public final WindowManager manager;
        Updater(Display display) {
            manager = (WindowManager) Proxy.newProxyInstance(WindowManager.class.getClassLoader(),
                    new Class<?>[]{WindowManager.class}, (proxy, method, args) -> {
                        if ("updateViewLayout".equals(method.getName())) {
                            updates.add(((WindowManager.LayoutParams) args[1]).flags);
                            if (updates.size() == failAt) throw new IllegalStateException("fixture_update_failed");
                            return null;
                        }
                        if ("getDefaultDisplay".equals(method.getName())) return display;
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        if ("toString".equals(method.getName())) return "ProbeWindowManagerFixture";
                        return null;
                    });
        }
    }
}
