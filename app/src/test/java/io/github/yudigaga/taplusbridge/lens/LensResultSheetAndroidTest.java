package io.github.yudigaga.taplusbridge.lens;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, qualifiers = "w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class LensResultSheetAndroidTest {
    @Test public void separateWindowInheritsHostTypeWithoutChangingHostChildrenOrBitmap() throws Exception {
        Fixture fixture = new Fixture();
        WindowManager.LayoutParams host = (WindowManager.LayoutParams) fixture.anchor.getRootView().getLayoutParams();
        assertTrue(fixture.sheet.show()); fixture.size(400, 800);
        assertEquals(host.type, fixture.dialog().getWindow().getAttributes().type);
        assertEquals(1, fixture.host.getChildCount()); assertSame(fixture.anchor, fixture.host.getChildAt(0));
        assertEquals(View.VISIBLE, fixture.anchor.getRootView().getVisibility());
        assertNotSame(fixture.host, fixture.sheet.contentContainer());
        ImageView image = fixture.field("preview");
        assertSame(fixture.bitmap, ((BitmapDrawable) image.getDrawable()).getBitmap());
        assertFalse(fixture.bitmap.isRecycled()); fixture.sheet.dismiss(); fixture.sheet.dismiss();
        assertEquals(1, fixture.dismissals.get()); assertFalse(fixture.sheet.isShowing());
        assertNull(image.getDrawable()); assertFalse(fixture.bitmap.isRecycled());
    }

    @Test public void halfSheetExpandsWithinInsetsAndBackCollapsesBeforeClosing() throws Exception {
        Fixture fixture = new Fixture(); assertTrue(fixture.sheet.show()); fixture.size(400, 800);
        FrameLayout root = fixture.field("root");
        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(8, 24, 8, 32)).build());
        fixture.size(400, 800);
        assertEquals(744, (int) fixture.<Integer>field("usableHeight"));
        assertEquals(Math.round(744 * 0.45f), (int) fixture.<Integer>field("currentHeight"));
        fixture.sheet.expand(); fixture.endAnimation();
        assertTrue(fixture.sheet.isExpanded()); assertEquals(744, (int) fixture.<Integer>field("currentHeight"));
        assertEquals(View.GONE, fixture.<ImageView>field("preview").getVisibility());
        fixture.dialog().onBackPressed(); fixture.endAnimation();
        assertFalse(fixture.sheet.isExpanded()); assertTrue(fixture.sheet.isShowing());
        fixture.dialog().onBackPressed(); assertEquals(1, fixture.dismissals.get());
    }

    @Test public void contentUpSwipeExpandsAndHorizontalGestureStaysWithContent() throws Exception {
        Fixture fixture = new Fixture(); assertTrue(fixture.sheet.show()); fixture.size(400, 800);
        ViewGroup sheet = fixture.field("sheet"); int y = fixture.<View>field("chrome").getBottom() + 100;
        assertFalse(sheet.onInterceptTouchEvent(fixture.event(MotionEvent.ACTION_DOWN, 100, y)));
        assertFalse(sheet.onInterceptTouchEvent(fixture.event(MotionEvent.ACTION_MOVE, 220, y - 10)));
        assertFalse(fixture.sheet.isExpanded());
        sheet.onInterceptTouchEvent(fixture.event(MotionEvent.ACTION_DOWN, 100, y));
        MotionEvent move = fixture.event(MotionEvent.ACTION_MOVE, 100, y - 90);
        assertTrue(sheet.onInterceptTouchEvent(move)); sheet.onTouchEvent(move);
        sheet.onTouchEvent(fixture.event(MotionEvent.ACTION_UP, 100, y - 90)); fixture.endAnimation();
        assertTrue(fixture.sheet.isExpanded()); fixture.sheet.dismiss();
    }

    @Test public void downwardContentDragWaitsUntilInnerScrollIsAtTop() throws Exception {
        Fixture fixture = new Fixture(); AtomicBoolean scrolled = new AtomicBoolean(true);
        fixture.sheet.contentContainer().addView(new View(fixture.activity) {
            @Override public boolean canScrollVertically(int direction) { return direction < 0 && scrolled.get(); }
        }, new FrameLayout.LayoutParams(-1, -1));
        assertTrue(fixture.sheet.show()); fixture.size(400, 800); fixture.sheet.expand(); fixture.endAnimation(); fixture.size(400, 800);
        ViewGroup sheet = fixture.field("sheet"); int y = fixture.<View>field("chrome").getBottom() + 100;
        sheet.onInterceptTouchEvent(fixture.event(MotionEvent.ACTION_DOWN, 100, y));
        assertFalse(sheet.onInterceptTouchEvent(fixture.event(MotionEvent.ACTION_MOVE, 100, y + 90)));
        scrolled.set(false); sheet.onInterceptTouchEvent(fixture.event(MotionEvent.ACTION_DOWN, 100, y));
        MotionEvent move = fixture.event(MotionEvent.ACTION_MOVE, 100, y + 90);
        assertTrue(sheet.onInterceptTouchEvent(move)); sheet.onTouchEvent(move);
        sheet.onTouchEvent(fixture.event(MotionEvent.ACTION_UP, 100, y + 90)); fixture.endAnimation();
        assertFalse(fixture.sheet.isExpanded()); fixture.sheet.dismiss();
    }

    @Test public void sourceWebViewKeepsItsContentGestureAndBackHandlerRunsFirst() throws Exception {
        Fixture fixture = new Fixture(); WebView web = new WebView(fixture.activity);
        fixture.sheet.contentContainer().addView(web, new FrameLayout.LayoutParams(-1, -1));
        assertTrue(fixture.sheet.show()); fixture.size(400, 800);
        // Robolectric's proxy WebViewProvider drops setFrame; retain the real parent's measured bounds.
        Shadow.directlyOn(web, View.class, "setFrame", ClassParameter.from(int.class, 0), ClassParameter.from(int.class, 0),
                ClassParameter.from(int.class, web.getMeasuredWidth()), ClassParameter.from(int.class, web.getMeasuredHeight()));
        assertTrue("The WebView fixture must be measured before testing gesture ownership", web.getWidth() > 0 && web.getHeight() > 0);
        ViewGroup sheet = fixture.field("sheet"); int y = fixture.<View>field("chrome").getBottom() + 100;
        sheet.onInterceptTouchEvent(fixture.event(MotionEvent.ACTION_DOWN, 100, y));
        assertFalse("WebView=" + web.getWidth() + "x" + web.getHeight() + " content="
                + fixture.sheet.contentContainer().getWidth() + "x" + fixture.sheet.contentContainer().getHeight()
                + " top=" + fixture.sheet.contentContainer().getTop() + " downY=" + y,
                sheet.onInterceptTouchEvent(fixture.event(MotionEvent.ACTION_MOVE, 100, y - 90)));
        fixture.sheet.expand(); fixture.endAnimation();
        fixture.sheet.setBackHandler(() -> true); fixture.dialog().onBackPressed(); assertTrue(fixture.sheet.isExpanded());
        fixture.sheet.setBackHandler(() -> false); fixture.dialog().onBackPressed(); assertFalse(fixture.sheet.isExpanded());
        fixture.sheet.dismiss(); web.destroy();
    }

    @Test public void originalActionAndInteractionVisibilityDoNotAlterHostWindow() throws Exception {
        Fixture fixture = new Fixture(); AtomicInteger opens = new AtomicInteger(); assertTrue(fixture.sheet.show()); fixture.size(400, 800);
        Button original = fixture.field("originalPage"); assertFalse(original.isEnabled());
        fixture.sheet.setOriginalPage(true, opens::incrementAndGet); original.performClick(); assertEquals(1, opens.get());
        fixture.sheet.setInteractionHidden(true);
        assertEquals(View.INVISIBLE, fixture.dialog().getWindow().getDecorView().getVisibility());
        assertEquals(View.VISIBLE, fixture.anchor.getRootView().getVisibility()); assertTrue(fixture.sheet.isShowing());
        fixture.sheet.setInteractionHidden(false); assertEquals(View.VISIBLE, fixture.dialog().getWindow().getDecorView().getVisibility());
        fixture.sheet.dismiss(); assertFalse(original.isEnabled());
    }

    @Test @Config(sdk = 28) public void android9InsetsAndOrientationRemeasureStayInsideAvailableHeight() throws Exception {
        Fixture fixture = new Fixture(); assertTrue(fixture.sheet.show()); fixture.size(400, 800);
        WindowInsets insets = ReflectionHelpers.callConstructor(WindowInsets.class,
                ClassParameter.from(Rect.class, new Rect(0, 24, 0, 32)));
        fixture.<FrameLayout>field("root").dispatchApplyWindowInsets(insets);
        fixture.size(800, 400); assertEquals(344, (int) fixture.<Integer>field("usableHeight"));
        fixture.sheet.expand(); fixture.endAnimation(); assertEquals(344, (int) fixture.<Integer>field("currentHeight"));
        fixture.sheet.dismiss();
    }

    @Test public void detachingAnchorClosesOnceAndReleasesMountedResultViews() throws Exception {
        Fixture fixture = new Fixture(); fixture.sheet.contentContainer().addView(new View(fixture.activity));
        assertTrue(fixture.sheet.show()); fixture.activity.setContentView(new FrameLayout(fixture.activity));
        assertEquals(1, fixture.dismissals.get()); assertEquals(0, fixture.sheet.contentContainer().getChildCount());
        fixture.sheet.dismiss(); assertEquals(1, fixture.dismissals.get());
    }

    private static final class Fixture {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        final FrameLayout host = new FrameLayout(activity);
        final View anchor = new View(activity);
        final Bitmap bitmap = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888);
        final AtomicInteger dismissals = new AtomicInteger();
        final LensResultSheet sheet;
        Fixture() {
            host.addView(anchor, new FrameLayout.LayoutParams(-1, -1)); activity.setContentView(host);
            sheet = new LensResultSheet(anchor, bitmap, dismissals::incrementAndGet);
        }
        @SuppressWarnings("unchecked") <T> T field(String name) throws Exception {
            Field field = LensResultSheet.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(sheet);
        }
        Dialog dialog() throws Exception { return field("dialog"); }
        void size(int width, int height) throws Exception {
            View root = dialog().getWindow().getDecorView();
            for (int pass = 0; pass < 3; pass++) {
                forceLayout(root);
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
            }
        }
        void forceLayout(View view) {
            view.forceLayout();
            if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) forceLayout(group.getChildAt(i));
        }
        void endAnimation() throws Exception {
            ValueAnimator animation = field("heightAnimation"); if (animation != null) animation.end();
        }
        MotionEvent event(int action, int x, int y) throws Exception {
            View view = field("sheet"); int[] location = new int[2]; view.getLocationOnScreen(location);
            MotionEvent event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action,
                    x + location[0], y + location[1], 0);
            event.offsetLocation(-location[0], -location[1]); return event;
        }
    }
}
