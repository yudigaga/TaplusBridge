package io.github.yudigaga.taplusbridge.drag;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.View;
import java.util.Map;

/** Four inward-moving glass targets and a content preview that follows the active finger. */
@android.annotation.SuppressLint("ViewConstructor") // Built only for an authorized drag, never inflated from XML.
public final class RainbowDragView extends View {
    private static final String[] SIDES = {"up", "down", "left", "right"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float density, width, height, originX, originY;
    private final float fontScale;
    private final DragGlassPalette palette;
    private final Map<String, String> actions;
    private final DragContactFeedback feedback = new DragContactFeedback();
    private String caption;
    private Bitmap thumbnail;
    private boolean showPreview = true;
    private float fingerX, fingerY;
    private EdgeDragTarget.Contact contact;
    private final Shader horizontal, vertical;

    public RainbowDragView(Context context, float width, float height, float density, float originX, float originY,
                           Map<String, String> actions, String caption, Bitmap image) {
        super(context);
        this.width = width; this.height = height; this.density = density;
        this.fontScale = context.getResources().getConfiguration().fontScale;
        this.palette = DragGlassPalette.from(context);
        this.originX = this.fingerX = originX; this.originY = this.fingerY = originY;
        this.actions = Map.copyOf(actions);
        this.caption = caption == null || caption.isBlank() ? image == null ? "已识别内容" : "图片" : caption.substring(0, Math.min(100, caption.length()));
        if (image != null && !image.isRecycled()) {
            float scale = Math.min(1, 320f / Math.max(image.getWidth(), image.getHeight()));
            Bitmap resized = Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * scale)),
                    Math.max(1, Math.round(image.getHeight() * scale)), true);
            thumbnail = resized == image ? image.copy(Bitmap.Config.ARGB_8888, false) : resized;
        }
        int[] tint = {palette.getAccentSoft(), palette.getAccent(), palette.getAccentSoft()};
        horizontal = new LinearGradient(0, 0, width, 0, tint, null, Shader.TileMode.CLAMP);
        vertical = new LinearGradient(0, 0, height, 0, tint, null, Shader.TileMode.CLAMP);
        setClickable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        contact = EdgeDragTarget.at(originX, originY, originX, originY, width, height, density);
    }

    public void update(float x, float y, boolean haptic) {
        fingerX = x; fingerY = y;
        contact = EdgeDragTarget.at(x, y, originX, originY, width, height, density);
        String touching = contact.touching();
        if (touching != null && "none".equals(actions.get(touching))) touching = null;
        boolean pulse = feedback.update(touching);
        if (haptic && pulse) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    public void updateCaption(String value) {
        caption = value == null || value.isBlank() ? thumbnail == null ? "已识别内容" : "图片"
                : value.substring(0, Math.min(100, value.length()));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float sx = getWidth() / width, sy = getHeight() / height;
        int save = canvas.save(); canvas.scale(sx, sy);
        canvas.drawColor(palette.getScrim());
        for (String side : SIDES) drawEdge(canvas, side);
        if (showPreview) drawPreview(canvas);
        String side = contact.touching();
        String message = side != null && !"none".equals(actions.get(side)) ? "松手" + label(actions.get(side)) : "拖向操作区域 · 接触后松手";
        text.setTextSize(13 * density * fontScale); text.setTextAlign(Paint.Align.CENTER);
        message = android.text.TextUtils.ellipsize(message, text, Math.max(0, width - 64 * density),
                android.text.TextUtils.TruncateAt.END).toString();
        float hintY = Math.min(height - 80 * density, height * .72f);
        float hintWidth = Math.min(width - 32 * density, text.measureText(message) + 32 * density);
        float hintHeight = Math.max(40 * density, text.getFontSpacing() + 20 * density);
        rect.set((width - hintWidth) / 2, hintY - hintHeight / 2, (width + hintWidth) / 2, hintY + hintHeight / 2);
        drawGlass(canvas, rect, hintHeight / 2, side != null && !"none".equals(actions.get(side)));
        text.setColor(palette.getForeground());
        canvas.drawText(message, width / 2, hintY - (text.ascent() + text.descent()) / 2, text);
        canvas.restoreToCount(save);
    }

    private void drawEdge(Canvas canvas, String side) {
        boolean horizontalSide = "up".equals(side) || "down".equals(side);
        boolean selected = side.equals(contact.approaching()), touching = side.equals(contact.touching());
        String action = actions.getOrDefault(side, "none");
        boolean enabled = !"none".equals(action);
        float inset = selected ? contact.inset() : 0;
        float axis = EdgeDragTarget.normal(width, height, side, inset);
        float along = horizontalSide ? selected ? fingerX : width / 2 : selected ? fingerY : height / 2;
        float span = horizontalSide ? width : height;
        along = Math.max(40 * density, Math.min(span - 40 * density, along));
        float base = "up".equals(side) || "left".equals(side) ? 0 : horizontalSide ? height : width;
        canvas.save();
        if (!horizontalSide) { canvas.rotate(90); canvas.scale(1, -1); }
        float length = horizontalSide ? width : height;
        path.reset(); path.moveTo(0, base);
        path.cubicTo(along * .6f, base, along - 52 * density, axis, along, axis);
        path.cubicTo(along + 52 * density, axis, along + (length - along) * .4f, base, length, base);
        paint.setStyle(Paint.Style.STROKE); paint.setShader(horizontalSide ? horizontal : vertical);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int layer = 3; layer >= 0; layer--) {
            paint.setStrokeWidth((touching && enabled ? 9 : 4) * density + layer * 8 * density);
            paint.setAlpha(enabled ? (layer == 0 ? 205 : 10 + layer * 4) : 28);
            canvas.drawPath(path, paint);
        }
        paint.setShader(null); paint.setAlpha(255); canvas.restore();
        float sign = "up".equals(side) || "left".equals(side) ? 1 : -1;
        float normal = axis - sign * 30 * density;
        normal = Math.max(28 * density, Math.min((horizontalSide ? height : width) - 28 * density, normal));
        float cx = horizontalSide ? along : normal, cy = horizontalSide ? normal : along;
        float previewX = Math.max(72 * density, Math.min(width - 72 * density, fingerX));
        float previewY = Math.max(36 * density, Math.min(height - 36 * density, fingerY));
        if (showPreview && touching && Math.abs(cx - previewX) < 98 * density
                && Math.abs(cy - previewY) < 62 * density) {
            // Keep the selected icon beside the preview when the preview reaches the screen boundary.
            if (horizontalSide) cx = previewX + 108 * density < width - 28 * density
                    ? previewX + 108 * density : previewX - 108 * density;
            else cy = previewY - 70 * density > 28 * density ? previewY - 70 * density : previewY + 70 * density;
        }
        rect.set(cx - 24 * density, cy - 24 * density, cx + 24 * density, cy + 24 * density);
        drawGlass(canvas, rect, 24 * density, touching && enabled);
        paint.setColor(enabled ? palette.getAccent() : palette.getSecondary());
        icon(canvas, action, cx, cy);
        text.setTextSize(12 * density * fontScale); text.setColor(enabled ? palette.getForeground() : palette.getSecondary()); text.setTextAlign(Paint.Align.CENTER);
        if (!touching) {
            String title = android.text.TextUtils.ellipsize(label(action), text, Math.max(0, width - 48 * density),
                    android.text.TextUtils.TruncateAt.END).toString();
            float labelWidth = text.measureText(title) + 18 * density;
            float labelHeight = Math.max(28 * density, text.getFontSpacing() + 10 * density);
            float labelX = Math.max(labelWidth / 2 + 4 * density,
                    Math.min(width - labelWidth / 2 - 4 * density, cx));
            float labelY = cy + 24 * density + labelHeight / 2 + 4 * density;
            if (labelY + labelHeight / 2 > height - 8 * density)
                labelY = cy - 24 * density - labelHeight / 2 - 4 * density;
            rect.set(labelX - labelWidth / 2, labelY - labelHeight / 2,
                    labelX + labelWidth / 2, labelY + labelHeight / 2);
            drawGlass(canvas, rect, labelHeight / 2, false);
            canvas.drawText(title, labelX, labelY - (text.ascent() + text.descent()) / 2, text);
        }
    }

    private void drawPreview(Canvas canvas) {
        float halfWidth = 72 * density, halfHeight = Math.max(36, 18 + 14 * fontScale) * density;
        float cx = Math.max(halfWidth, Math.min(width - halfWidth, fingerX));
        float cy = Math.max(halfHeight, Math.min(height - halfHeight, fingerY));
        rect.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
        drawGlass(canvas, rect, 20 * density, false);
        float left = rect.left + 12 * density;
        if (thumbnail != null && !thumbnail.isRecycled()) {
            RectF thumb = new RectF(left, rect.top + 12 * density, left + 48 * density, rect.bottom - 12 * density);
            int save = canvas.save(); path.reset(); path.addRoundRect(thumb, 6 * density, 6 * density, Path.Direction.CW);
            canvas.clipPath(path); paint.setAlpha(235); canvas.drawBitmap(thumbnail, null, thumb, paint);
            paint.setAlpha(255); canvas.restoreToCount(save); left += 56 * density;
        }
        text.setTextAlign(Paint.Align.LEFT); text.setTextSize(12 * density * fontScale); text.setColor(palette.getForeground());
        String value = caption.replace('\n', ' ').replace('\r', ' ');
        float available = rect.right - 10 * density - left;
        for (int row = 0; row < 2 && !value.isEmpty(); row++) {
            int count = text.breakText(value, true, available, null);
            if (count == 0) break;
            String line = value.substring(0, count); value = value.substring(count);
            if (row == 1 && !value.isEmpty()) line = android.text.TextUtils.ellipsize(line + "…", text, available,
                    android.text.TextUtils.TruncateAt.END).toString();
            canvas.drawText(line, left, rect.top + 12 * density - text.ascent() + row * text.getFontSpacing(), text);
        }
    }

    private void drawGlass(Canvas canvas, RectF bounds, float radius, boolean selected) {
        paint.setStyle(Paint.Style.FILL); paint.setShader(null); paint.setAlpha(255);
        paint.setColor(selected ? palette.getSelected() : palette.getSurface());
        if (!selected) paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
                palette.getSurface(), palette.getSurfaceBottom(), Shader.TileMode.CLAMP));
        paint.setShadowLayer(12 * density, 0, 4 * density, palette.getShadow());
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.clearShadowLayer(); paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density);
        paint.setColor(selected ? palette.getAccent() : palette.getOutline());
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void icon(Canvas canvas, String action, float cx, float cy) {
        int save = canvas.save(); canvas.translate(cx - 12 * density, cy - 12 * density); canvas.scale(density, density);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch (action) {
            case "share" -> {
                canvas.drawLine(6,12,18,5,paint); canvas.drawLine(6,12,18,19,paint);
                for (float[] p : new float[][]{{5,12},{19,4},{19,20}}) canvas.drawCircle(p[0],p[1],2.5f,paint);
            }
            case "browser" -> {
                canvas.drawCircle(12,12,10,paint); canvas.drawOval(7,2,17,22,paint);
                canvas.drawLine(2,12,22,12,paint); canvas.drawLine(4,6,20,6,paint); canvas.drawLine(4,18,20,18,paint);
            }
            case "rules" -> {
                canvas.drawRoundRect(2,3,18,21,3,3,paint); canvas.drawLine(11,12,23,12,paint);
                canvas.drawLine(19,8,23,12,paint); canvas.drawLine(19,16,23,12,paint);
                canvas.drawLine(6,7,10,7,paint); canvas.drawLine(6,17,10,17,paint);
            }
            case "lens" -> {
                canvas.drawRoundRect(2,4,22,21,4,4,paint); canvas.drawCircle(12,12,4,paint);
                canvas.drawLine(7,4,9,1,paint); canvas.drawLine(9,1,15,1,paint); canvas.drawLine(15,1,17,4,paint);
            }
            case "translate" -> {
                canvas.drawLine(2,5,13,5,paint); canvas.drawLine(8,2,8,5,paint);
                path.reset();path.moveTo(11,5);path.quadTo(10,13,3,17);canvas.drawPath(path,paint);
                path.reset();path.moveTo(4,8);path.quadTo(6,13,12,16);canvas.drawPath(path,paint);
                canvas.drawLine(13,22,18,9,paint);canvas.drawLine(18,9,23,22,paint);canvas.drawLine(15,17,21,17,paint);
            }
            case "copy" -> {
                canvas.drawRoundRect(7,7,22,22,3,3,paint);path.reset();path.moveTo(17,3);path.lineTo(5,3);
                path.quadTo(2,3,2,6);path.lineTo(2,17);canvas.drawPath(path,paint);
            }
            case "portal" -> {
                for (int x : new int[]{3,14}) for (int y : new int[]{3,14}) canvas.drawRoundRect(x,y,x+7,y+7,2,2,paint);
            }
            default -> { canvas.drawCircle(12,12,9,paint);canvas.drawLine(6,18,18,6,paint); }
        }
        paint.setStyle(Paint.Style.FILL); canvas.restoreToCount(save);
    }

    public static String label(String action) {
        return switch (action == null ? "none" : action) {
            case "portal" -> "模块能力"; case "share" -> "分享"; case "browser" -> "浏览器";
            case "rules" -> "对应应用"; case "lens" -> "识图"; case "translate" -> "翻译"; case "copy" -> "复制";
            default -> "未设置";
        };
    }

    public void setShowPreview(boolean showPreview) { this.showPreview = showPreview; invalidate(); }

    public void dispose() {
        if (thumbnail != null) { thumbnail.recycle(); thumbnail = null; }
    }
}
