package io.github.yudigaga.taplusbridge.lens;

import android.app.Activity;
import android.os.IBinder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LensEmbeddedBrowserAndroidTest {
    private final List<Fixture> fixtures = new ArrayList<>();

    @After public void closeWindows() {
        for (Fixture fixture : fixtures) { fixture.browser.destroy(); fixture.activity.finish(); }
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
    }

    @Test public void closingRemovesOnlyItsSurfaceAndNotifiesOnce() throws Exception {
        Fixture fixture = new Fixture(); assertTrue(fixture.browser.mount(fixture.target));
        assertTrue(fixture.browser.isShowing()); assertEquals(2, fixture.target.getChildCount());
        fixture.browser.destroy(); fixture.browser.destroy();
        assertFalse(fixture.browser.isShowing()); assertEquals(1, fixture.closed);
        assertEquals(1, fixture.target.getChildCount()); assertSame(fixture.original, fixture.target.getChildAt(0));
        assertFalse(fixture.browser.mount(fixture.target));
    }

    @Test public void removingTheEmbeddedSurfaceCancelsItsWorkerAttachment() throws Exception {
        Fixture fixture = new Fixture(); assertTrue(fixture.browser.mount(fixture.target));
        fixture.target.removeView(fixture.surface());
        assertEquals(1, fixture.closed); assertFalse(fixture.browser.isShowing());
        fixture.browser.destroy(); assertEquals(1, fixture.closed);
    }

    @Test public void noSurfaceRequestIsSentBeforeACreatedSurfaceHasPositiveBounds() throws Exception {
        Fixture fixture = new Fixture(); assertTrue(fixture.browser.mount(fixture.target));
        SurfaceView surface = fixture.surface();
        fixture.browser.surfaceChanged(surface.getHolder(), 0, 400, 600);
        assertEquals(0, fixture.ready);
        fixture.browser.surfaceCreated(surface.getHolder());
        fixture.browser.surfaceChanged(surface.getHolder(), 0, 0, 600);
        assertEquals(0, fixture.ready);
    }

    @Test public void destroyedSurfaceIgnoresQueuedGeometryAndCannotRemount() throws Exception {
        Fixture fixture = new Fixture(); assertTrue(fixture.browser.mount(fixture.target));
        SurfaceView surface = fixture.surface(); fixture.browser.surfaceDestroyed(surface.getHolder());
        fixture.browser.surfaceCreated(surface.getHolder()); fixture.browser.surfaceChanged(surface.getHolder(), 0, 500, 700);
        assertEquals(1, fixture.closed); assertEquals(0, fixture.ready); assertEquals(0, fixture.resizes);
        assertFalse(fixture.browser.mount(fixture.target));
    }

    @Test public void destroyingAStillMountedSurfaceRemovesItAfterTheCallbackReturns() throws Exception {
        Fixture fixture = new Fixture(); assertTrue(fixture.browser.mount(fixture.target));
        SurfaceView surface = fixture.surface(); fixture.browser.surfaceDestroyed(surface.getHolder());
        assertEquals(1, fixture.closed); assertFalse(fixture.browser.isShowing());
        assertEquals(2, fixture.target.getChildCount());
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(1, fixture.target.getChildCount()); assertSame(fixture.original, fixture.target.getChildAt(0));
        fixture.browser.destroy(); assertEquals(1, fixture.closed);
    }

    @Test @Config(sdk = 28) public void unsupportedAndroidVersionLeavesTheContainerUntouched() {
        Fixture fixture = new Fixture(); assertFalse(fixture.browser.mount(fixture.target));
        assertEquals(1, fixture.target.getChildCount()); assertEquals(0, fixture.ready); assertFalse(fixture.browser.isShowing());
    }

    private final class Fixture implements LensEmbeddedBrowser.Listener {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        final FrameLayout target = new FrameLayout(activity);
        final View original = new View(activity);
        final LensEmbeddedBrowser browser;
        int ready, resizes, closed;
        Fixture() {
            fixtures.add(this); target.addView(original); activity.setContentView(target);
            browser = new LensEmbeddedBrowser(target, this);
        }
        SurfaceView surface() throws Exception {
            Field field = LensEmbeddedBrowser.class.getDeclaredField("surface"); field.setAccessible(true);
            return (SurfaceView) field.get(browser);
        }
        @Override public void onSurfaceReady(IBinder token, int displayId, int width, int height) { ready++; }
        @Override public void onResize(int width, int height) { resizes++; }
        @Override public void onClosed() { closed++; }
    }
}
