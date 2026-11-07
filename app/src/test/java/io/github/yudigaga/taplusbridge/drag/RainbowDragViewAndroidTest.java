package io.github.yudigaga.taplusbridge.drag;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class RainbowDragViewAndroidTest {
    @Test public void dragSurfacesFollowTheSystemLightAndDarkTheme() throws Exception {
        var light = new android.content.res.Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        light.uiMode = (light.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_NO;
        var dark = new android.content.res.Configuration(light);
        dark.uiMode = (dark.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int lightColor = renderTheme(light, "drag-glass-light.png");
        int darkColor = renderTheme(dark, "drag-glass-dark.png");
        assertNotEquals("the drag preview must not force the light palette in dark mode", lightColor, darkColor);
        assertTrue(android.graphics.Color.red(lightColor) > android.graphics.Color.red(darkColor));
    }

    private int renderTheme(android.content.res.Configuration configuration, String name) throws Exception {
        var context = RuntimeEnvironment.getApplication().createConfigurationContext(configuration);
        RainbowDragView view = new RainbowDragView(context, 432, 960, 1, 216, 480,
                Map.of("up", "portal", "down", "share", "left", "rules", "right", "browser"),
                "分享卡片标题\n长按识别的内容", null);
        view.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 432, 960);
        Bitmap bitmap = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(bitmap));
            File output = new File("build/rainbow-preview"); assertTrue(output.isDirectory() || output.mkdirs());
            try (var stream = new FileOutputStream(new File(output, name))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
            return bitmap.getPixel(216, 450);
        } finally { bitmap.recycle(); view.dispose(); }
    }

    @Test public void fourRainbowTargetsRenderThroughoutContactAndCancel() throws Exception {
        Map<String, String> actions = Map.of("up", "portal", "down", "share", "left", "rules", "right", "browser");
        RainbowDragView view = new RainbowDragView(RuntimeEnvironment.getApplication(), 432, 960, 1,
                216, 480, actions, "分享卡片标题\n长按识别的内容", null);
        view.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 432, 960);
        float[][] points = {{216,480},{216,300},{216,100},{40,480},{390,480},{216,870}};
        File output = new File("build/rainbow-preview"); assertTrue(output.isDirectory() || output.mkdirs());
        for (int i = 0; i < points.length; i++) {
            view.update(points[i][0], points[i][1], false);
            Bitmap bitmap = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888);
            try {
                view.draw(new Canvas(bitmap));
                assertTrue(bitmap.getPixel(216, 2) != 0);
                try (var stream = new FileOutputStream(new File(output, "rainbow-" + i + ".png"))) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                }
            } finally { bitmap.recycle(); }
        }
        view.dispose();
    }
}
