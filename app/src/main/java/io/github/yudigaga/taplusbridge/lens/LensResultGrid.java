package io.github.yudigaga.taplusbridge.lens;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Native result cards only; the caller owns loading, navigation and the surrounding sheet. */
final class LensResultGrid {
    private LensResultGrid() { }

    static int append(Context context, LinearLayout parent, List<Bundle> items, Consumer<String> openPage,
                      BiConsumer<ImageView, String> loadThumb) {
        if (items == null || items.isEmpty()) return 0;
        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        LinearLayout columns = new LinearLayout(context); columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setBaselineAligned(false);
        LinearLayout[] lanes = new LinearLayout[2];
        for (int i = 0; i < lanes.length; i++) {
            lanes[i] = new LinearLayout(context); lanes[i].setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (i == 0) params.rightMargin = dp(context, 5); else params.leftMargin = dp(context, 5);
            columns.addView(lanes[i], params);
        }
        int shown = 0;
        for (Bundle item : items) {
            if (shown == 20) break;
            if (item == null) continue;
            String page = item.getString("page_url");
            if (!PortalLensPanel.webUrl(page)) continue;
            String title = item.getString("title", "").trim();
            if (title.isEmpty()) title = "查看匹配网页";
            String source = item.getString("source_name", "").trim();
            if (source.isEmpty()) source = Uri.parse(page).getHost();

            LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(dark ? Color.rgb(35, 35, 35) : Color.rgb(247, 247, 249));
            shape.setCornerRadius(dp(context, 14));
            card.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x22ffffff : 0x18000000), shape, null));
            card.setClipToOutline(true); card.setMinimumHeight(dp(context, 48));
            card.setFocusable(true); card.setClickable(true); card.setContentDescription(title + "，" + source);
            card.setOnClickListener(view -> openPage.accept(page));

            ImageView thumbnail = new AspectImageView(context); thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackgroundColor(dark ? Color.rgb(48, 48, 50) : Color.rgb(231, 232, 235));
            thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(thumbnail, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout labels = new LinearLayout(context); labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 10));
            TextView heading = text(context, title, 14, dark ? Color.WHITE : Color.rgb(28, 28, 30), 2);
            labels.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            TextView site = text(context, source, 12, dark ? Color.rgb(184, 184, 190) : Color.rgb(102, 102, 109), 1);
            LinearLayout.LayoutParams siteParams = new LinearLayout.LayoutParams(-1, -2); siteParams.topMargin = dp(context, 4);
            labels.addView(site, siteParams); card.addView(labels, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.bottomMargin = dp(context, 10);
            lanes[shown++ % 2].addView(card, cardParams);
            String address = item.getString("thumbnail_url");
            if (address != null && loadThumb != null) loadThumb.accept(thumbnail, address);
        }
        if (shown != 0) parent.addView(columns, new LinearLayout.LayoutParams(-1, -2));
        return shown;
    }

    private static TextView text(Context context, String value, int size, int color, int lines) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setMaxLines(lines); text.setEllipsize(TextUtils.TruncateAt.END);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return text;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class AspectImageView extends ImageView {
        AspectImageView(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = View.MeasureSpec.getSize(widthSpec);
            if (View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.UNSPECIFIED) width = dp(getContext(), 144);
            double ratio = 1;
            Drawable image = getDrawable();
            if (image != null && image.getIntrinsicWidth() > 0 && image.getIntrinsicHeight() > 0)
                ratio = Math.max(0.65, Math.min(2.2, (double) image.getIntrinsicHeight() / image.getIntrinsicWidth()));
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize((int) Math.round(width * ratio), heightSpec));
        }
    }
}
