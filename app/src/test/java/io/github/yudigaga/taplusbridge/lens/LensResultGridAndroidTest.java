package io.github.yudigaga.taplusbridge.lens;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, qualifiers = "w390dp-h844dp-mdpi")
public final class LensResultGridAndroidTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private Bundle item(int index) {
        Bundle result = new Bundle(); result.putString("page_url", "https://source.example/" + index);
        result.putString("title", "实际结果标题 " + index); result.putString("source_name", "来源 " + index);
        result.putString("thumbnail_url", "https://source.example/thumb-" + index + ".jpg"); return result;
    }
    private LinearLayout parent() { LinearLayout result = new LinearLayout(context); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout lane(LinearLayout parent, int index) { return (LinearLayout) ((LinearLayout) parent.getChildAt(0)).getChildAt(index); }

    @Test public void validResultsKeepSearchOrderAcrossTwoEqualColumnsAndOpenTheirActualLinks() {
        LinearLayout parent = parent(); List<String> clicks = new ArrayList<>(); List<String> thumbnails = new ArrayList<>();
        assertEquals(4, LensResultGrid.append(context, parent, List.of(item(0), item(1), item(2), item(3)), clicks::add,
                (view, address) -> thumbnails.add(address)));
        parent.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        parent.layout(0, 0, parent.getMeasuredWidth(), parent.getMeasuredHeight());
        assertEquals(lane(parent, 0).getMeasuredWidth(), lane(parent, 1).getMeasuredWidth());
        assertEquals(2, lane(parent, 0).getChildCount()); assertEquals(2, lane(parent, 1).getChildCount());
        lane(parent, 0).getChildAt(0).performClick(); lane(parent, 1).getChildAt(0).performClick();
        lane(parent, 0).getChildAt(1).performClick(); lane(parent, 1).getChildAt(1).performClick();
        assertEquals(List.of("https://source.example/0", "https://source.example/1", "https://source.example/2", "https://source.example/3"), clicks);
        assertEquals("https://source.example/thumb-2.jpg", thumbnails.get(2));
        assertEquals("实际结果标题 0，来源 0", lane(parent, 0).getChildAt(0).getContentDescription());
    }

    @Test public void unsafeResultsNeverBecomeClickableCardsOrTriggerThumbnailIoAndTheListIsBounded() {
        LinearLayout parent = parent(); List<Bundle> rows = new ArrayList<>(); rows.add(null);
        for (String invalid : List.of("javascript:alert(1)", "file:///image", "http://127.0.0.1/private", "https://user:secret@example.com/")) {
            Bundle row = item(99); row.putString("page_url", invalid); rows.add(row);
        }
        for (int i = 0; i < 30; i++) rows.add(item(i));
        List<String> loaded = new ArrayList<>();
        assertEquals(20, LensResultGrid.append(context, parent, rows, value -> fail("not clicked"), (view, address) -> loaded.add(address)));
        assertEquals(20, loaded.size()); assertEquals(10, lane(parent, 0).getChildCount()); assertEquals(10, lane(parent, 1).getChildCount());
    }

    @Test public void thumbnailHeightUsesTheDecodedImageRatioAndClampsExtremeShapes() {
        LinearLayout parent = parent(); List<ImageView> targets = new ArrayList<>();
        LensResultGrid.append(context, parent, List.of(item(0)), value -> { }, (view, address) -> targets.add(view));
        ImageView image = targets.get(0);
        int width = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        image.measure(width, height); assertEquals(100, image.getMeasuredHeight());
        image.setImageBitmap(Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888));
        image.measure(width, height); assertEquals(200, image.getMeasuredHeight());
        image.setImageBitmap(Bitmap.createBitmap(10, 100, Bitmap.Config.ARGB_8888));
        image.measure(width, height); assertEquals(220, image.getMeasuredHeight());
        image.setImageBitmap(Bitmap.createBitmap(100, 10, Bitmap.Config.ARGB_8888));
        image.measure(width, height); assertEquals(65, image.getMeasuredHeight());
    }

    @Test public void missingTextUsesHonestFallbacksAndEmptyInputDoesNotAddAContainer() {
        LinearLayout parent = parent(); Bundle row = item(0); row.putString("title", " "); row.putString("source_name", ""); row.remove("thumbnail_url");
        assertEquals(1, LensResultGrid.append(context, parent, List.of(row), value -> { }, null));
        LinearLayout card = (LinearLayout) lane(parent, 0).getChildAt(0);
        LinearLayout labels = (LinearLayout) card.getChildAt(1);
        assertEquals("查看匹配网页", ((TextView) labels.getChildAt(0)).getText().toString());
        assertEquals("source.example", ((TextView) labels.getChildAt(1)).getText().toString());
        assertTrue(card.isFocusable()); assertTrue(card.isClickable());
        LinearLayout empty = parent(); assertEquals(0, LensResultGrid.append(context, empty, List.of(), value -> { }, null));
        assertEquals(0, empty.getChildCount());
    }
}
