package io.github.yudigaga.taplusbridge.lens;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Real controller/layout screenshots with explicitly synthetic input and result cards; no Google requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, qualifiers = "w390dp-h844dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LensResultAppearanceAndroidTest {
    private static final int WIDTH = 390, HEIGHT = 844;
    private static final File OUTPUT = new File(System.getProperty("taplus.testArtifacts"), "ui-0.14.0");

    @Test public void lightHalfAndFullRenderRealNativeController() throws Exception { render("light", 1f); }
    @Test @Config(qualifiers = "w390dp-h844dp-night-mdpi")
    public void darkHalfAndFullRenderRealNativeController() throws Exception { render("dark", 1f); }
    @Test public void largeFontHalfAndFullStayInsideSheet() throws Exception { render("large-font", 1.6f); }

    private void render(String name, float fontScale) throws Exception {
        try (Fixture fixture = new Fixture(fontScale)) {
            fixture.layout(); fixture.capture(name + "-half");
            assertFalse(fixture.sheet.isExpanded()); fixture.assertBounds();
            fixture.sheet.expand(); fixture.finishAnimation(); fixture.layout();
            fixture.capture(name + "-full"); assertTrue(fixture.sheet.isExpanded()); fixture.assertBounds();
            View nativeSheet = get(fixture.sheet, "sheet");
            assertEquals((int) fixture.<Integer>sheetField("usableHeight"), nativeSheet.getHeight());
            assertFalse("The fixture must never recycle the selected host image", fixture.image.isRecycled());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ActivityController<Activity> lifecycle = Robolectric.buildActivity(Activity.class).setup().visible();
        final Activity activity = lifecycle.get();
        final Bitmap image = fixtureImage(360, 240, 0xff2366d8, 0xffd84a43, "UI TEST");
        final ArrayList<Bitmap> thumbs = new ArrayList<>();
        final PortalLensPanel panel;
        final LensResultSheet sheet;
        final View decor;

        @SuppressWarnings("deprecation") Fixture(float fontScale) throws Exception {
            if (fontScale != 1f) {
                Configuration config = new Configuration(activity.getResources().getConfiguration()); config.fontScale = fontScale;
                DisplayMetrics metrics = new DisplayMetrics(); metrics.setTo(activity.getResources().getDisplayMetrics());
                metrics.scaledDensity = metrics.density * fontScale;
                activity.getResources().updateConfiguration(config, metrics);
            }
            FrameLayout host = new FrameLayout(activity); host.setBackgroundColor(0xffd9dce3);
            LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
            TextView action = new TextView(activity); action.setText("识物"); card.addView(action);
            RelativeLayout imageGroup = new RelativeLayout(activity); card.addView(imageGroup, new LinearLayout.LayoutParams(-1, 240));
            host.addView(card, new FrameLayout.LayoutParams(-1, -1)); activity.setContentView(host);
            panel = new PortalLensPanel(card, action, imageGroup, () -> image, () -> "fixture.source");
            Class<?> jobType = Class.forName(PortalLensPanel.class.getName() + "$Job");
            Constructor<?> constructor = jobType.getDeclaredConstructor(PortalLensPanel.class, Bitmap.class);
            constructor.setAccessible(true); Object job = constructor.newInstance(panel, image);
            set(panel, "job", job); set(job, "key", "fixture-synthetic-image");
            Bundle update = new Bundle(); update.putString("request_id", get(job, "id"));
            update.putString("panel_session_id", get(panel, "panelId")); update.putString("image_key", "fixture-synthetic-image");
            update.putInt("page_generation", 1); update.putString("status", "results_ready");
            update.putString("result_url", "https://lens.google.com/search?p=ui-test-synthetic-fixture");
            ArrayList<Bundle> items = new ArrayList<>();
            int[][] sizes = {{180, 240}, {240, 170}, {180, 300}, {230, 210}, {200, 180}, {180, 250}};
            int[] colors = {0xff2468dc, 0xffd9534f, 0xff008d85, 0xffa860b6, 0xffd98b2b, 0xff406b80};
            for (int i = 0; i < sizes.length; i++) {
                Bitmap thumb = fixtureImage(sizes[i][0], sizes[i][1], colors[i], colors[(i + 1) % colors.length], "TEST " + (i + 1));
                thumbs.add(thumb); Bundle item = new Bundle();
                item.putString("title", "布局测试结果 " + (i + 1) + " · 合成图片样例");
                item.putString("source_name", "fixture.example · UI TEST"); item.putString("page_url", "https://fixture.example/result/" + i);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); assertTrue(thumb.compress(Bitmap.CompressFormat.PNG, 100, bytes));
                item.putString("thumbnail_url", "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
                items.add(item);
            }
            update.putParcelableArrayList("items", items);
            Method callback = PortalLensPanel.class.getDeclaredMethod("update", jobType, Bundle.class);
            callback.setAccessible(true); callback.invoke(panel, job, update);
            sheet = get(panel, "resultSheet"); assertNotNull("The real controller must create the floating result sheet", sheet);
            assertTrue(sheet.isShowing()); Dialog dialog = get(sheet, "dialog"); decor = dialog.getWindow().getDecorView();
            // Assign the same fixture bitmaps to the actual card views so async decoding cannot race the snapshot.
            LinearLayout cards = get(panel, "contents"); List<ImageView> nativeImages = new ArrayList<>(); collectImages(cards, nativeImages);
            assertEquals(thumbs.size(), nativeImages.size());
            int index = 0;
            // The real grid puts even results in the first lane and odd results in the second lane.
            for (int lane = 0; lane < 2; lane++) for (int item = lane; item < thumbs.size(); item += 2)
                nativeImages.get(index++).setImageBitmap(thumbs.get(item));
            ShadowLooper looper = Shadow.extract(Looper.getMainLooper()); looper.idle();
            FrameLayout root = get(sheet, "root");
            root.dispatchApplyWindowInsets(new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 24)).build());
        }

        void layout() throws Exception {
            for (int pass = 0; pass < 3; pass++) {
                decor.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, WIDTH, HEIGHT);
            }
        }

        void finishAnimation() throws Exception {
            ValueAnimator animation = get(sheet, "heightAnimation"); if (animation != null) animation.end();
        }

        void assertBounds() throws Exception {
            FrameLayout root = get(sheet, "root"); View nativeSheet = get(sheet, "sheet");
            assertTrue(nativeSheet.getHeight() > 0);
            assertTrue("The sheet must fit above navigation/IME padding", nativeSheet.getBottom() <= root.getHeight() - root.getPaddingBottom());
            assertTrue("The expanded sheet must fit below status padding", nativeSheet.getTop() >= root.getPaddingTop());
            FrameLayout content = sheet.contentContainer(); assertTrue(content.getHeight() > 0);
            assertTrue(content.getBottom() <= nativeSheet.getHeight());
            View search = get(panel, "searchInput"); assertTrue(search.getWidth() > 0); assertTrue(search.getHeight() > 0);
            View resultArea = get(panel, "resultArea"); assertTrue(resultArea.getHeight() > 0);
        }

        void capture(String name) throws Exception {
            assertBounds(); Bitmap output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output); canvas.drawColor(0xff6d7075); decor.draw(canvas);
            HashSet<Integer> colors = new HashSet<>();
            for (int y = 0; y < HEIGHT; y += 8) for (int x = 0; x < WIDTH; x += 8) colors.add(output.getPixel(x, y));
            assertTrue("The rendered native screenshot must contain actual content, not a blank surface", colors.size() > 16);
            assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
            try (FileOutputStream stream = new FileOutputStream(new File(OUTPUT, "lens-" + name + ".png"))) {
                assertTrue(output.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
            Files.writeString(new File(OUTPUT, "lens-fixture-notice.txt").toPath(),
                    "UI TEST ONLY: synthetic selected image and synthetic result cards. Rendered from real PortalLensPanel, LensResultSheet and LensResultGrid in Robolectric Native. No Google search, phone, OEM font or system-window compositor validation.\n",
                    StandardCharsets.UTF_8);
            output.recycle();
        }

        <T> T sheetField(String name) throws Exception { return get(sheet, name); }
        @Override public void close() {
            panel.invalidate(); lifecycle.pause().stop().destroy();
        }
    }

    private static Bitmap fixtureImage(int width, int height, int first, int second, String title) {
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(image);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(first); canvas.drawRect(0, 0, width, height, paint);
        paint.setColor(second); canvas.drawCircle(width * .64f, height * .45f, Math.min(width, height) * .3f, paint);
        paint.setColor(Color.WHITE); paint.setTextSize(Math.max(16, width / 9f)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText(title, width * .08f, height * .84f, paint); return image;
    }

    private static void collectImages(View view, List<ImageView> images) {
        if (view instanceof ImageView image) images.add(image);
        else if (view instanceof ViewGroup parent) for (int i = 0; i < parent.getChildCount(); i++) collectImages(parent.getChildAt(i), images);
    }
    @SuppressWarnings("unchecked") private static <T> T get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
}
