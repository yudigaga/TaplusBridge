package io.github.yudigaga.taplusbridge.drag;

/** A stable central cancel area prevents small movements from choosing an action. */
public final class DragDirection {
    private DragDirection() { }

    public static String at(float x, float y, float width, float height, float cancelRadius) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || width <= 0 || height <= 0
                || !Float.isFinite(width) || !Float.isFinite(height) || x < 0 || y < 0 || x > width || y > height)
            return null;
        float dx = x - width / 2f, dy = y - height / 2f;
        if (Math.hypot(dx, dy) <= Math.max(0, cancelRadius)) return null;
        return Math.abs(dx) > Math.abs(dy) ? dx < 0 ? "left" : "right" : dy < 0 ? "up" : "down";
    }
}
