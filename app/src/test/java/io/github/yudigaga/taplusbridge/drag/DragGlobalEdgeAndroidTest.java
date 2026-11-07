package io.github.yudigaga.taplusbridge.drag;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class DragGlobalEdgeAndroidTest {
    @Test public void globalDropUsesTheSharedRainbowWithoutDuplicatingTheNativeDragShadow() throws Exception {
        DragActivity activity = Robolectric.buildActivity(DragActivity.class).get();
        Field settings = DragActivity.class.getDeclaredField("settings"); settings.setAccessible(true);
        settings.set(activity, new DragSettings(true, "share", "copy", "browser", "rules", false, List.of()));
        Class<?> type = Class.forName(DragActivity.class.getName() + "$DropView");
        var constructor = type.getDeclaredConstructor(DragActivity.class, Context.class); constructor.setAccessible(true);
        FrameLayout drop = (FrameLayout) constructor.newInstance(activity, RuntimeEnvironment.getApplication());
        drop.layout(0, 0, 400, 800);
        assertEquals(1, drop.getChildCount()); assertTrue(drop.getChildAt(0) instanceof RainbowDragView);
        Field preview = RainbowDragView.class.getDeclaredField("showPreview"); preview.setAccessible(true);
        assertFalse(preview.getBoolean(drop.getChildAt(0)));
        Method direction = type.getDeclaredMethod("direction", float.class, float.class); direction.setAccessible(true);
        assertNull(direction.invoke(drop, 200f, 400f));
        float density = drop.getResources().getDisplayMetrics().density;
        assertEquals(EdgeDragTarget.at(200, 5, 200, 400, 400, 800, density).touching(),
                direction.invoke(drop, 200f, 5f));
        View old = drop.getChildAt(0); drop.layout(0, 0, 800, 400);
        assertEquals(1, drop.getChildCount()); assertNotSame(old, drop.getChildAt(0));
        assertFalse(preview.getBoolean(drop.getChildAt(0)));
    }
}
