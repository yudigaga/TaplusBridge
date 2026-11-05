package io.github.yudigaga.taplusbridge.drag;

/** Shared geometry for the visible rainbow, contact feedback and release decision. */
public final class EdgeDragTarget {
    private EdgeDragTarget() { }

    public record Contact(String approaching, String touching, float progress, float inset) { }

    public static Contact at(float x, float y, float originX, float originY,
                             float width, float height, float density) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(originX) || !Float.isFinite(originY)
                || !Float.isFinite(width) || !Float.isFinite(height) || !Float.isFinite(density)
                || width <= 0 || height <= 0 || density <= 0 || x < 0 || y < 0 || x > width || y > height
                || originX < 0 || originY < 0 || originX > width || originY > height)
            return new Contact(null, null, 0, 0);
        float dx = x - originX, dy = y - originY;
        if (Math.hypot(dx, dy) < 48 * density) return new Contact(null, null, 0, 0);
        boolean horizontal = Math.abs(dx) > Math.abs(dy);
        String direction = horizontal ? dx < 0 ? "left" : "right" : dy < 0 ? "up" : "down";
        float distance = horizontal ? Math.abs(dx) : Math.abs(dy);
        float travel = horizontal ? dx < 0 ? originX : width - originX : dy < 0 ? originY : height - originY;
        float dimension = horizontal ? width : height;
        float progress = Math.max(0, Math.min(1, distance / Math.max(1, travel)));
        float approachingInset = Math.min(dimension * .32f, 190 * density) * progress;
        float previewX = Math.max(72 * density, Math.min(width - 72 * density, x));
        float previewY = Math.max(36 * density, Math.min(height - 36 * density, y));
        float previewGap = switch (direction) {
            case "left" -> previewX - 72 * density;
            case "right" -> width - previewX - 72 * density;
            case "up" -> previewY - 36 * density;
            default -> height - previewY - 36 * density;
        };
        // Once touching, the rainbow stays against the actual clamped preview instead of crossing it.
        float inset = Math.min(approachingInset, Math.max(0, previewGap - 8 * density));
        return new Contact(direction, previewGap - approachingInset <= 12 * density ? direction : null, progress, inset);
    }

    public static float normal(float width, float height, String direction, float inset) {
        return switch (direction) {
            case "left", "up" -> inset;
            case "right" -> width - inset;
            default -> height - inset;
        };
    }
}
