package io.github.yudigaga.taplusbridge.drag;

/** A physical long press may move after its hold time; the original point always owns the content. */
public final class LongPressDirection {
    private static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3;
    private static final long MAX_EVENT_AGE = 300, MAX_GESTURE = 30_000;
    private final long downAt, timeout;
    private final int device;
    private final float originX, originY, slop, threshold;
    private float x, y;
    private long lastAt;
    private boolean valid, released;
    private String direction;

    public LongPressDirection(long downAt, int device, float x, float y, long timeout,
                              float slop, float threshold) {
        this.downAt = downAt; this.device = device; originX = this.x = x; originY = this.y = y;
        this.timeout = timeout; this.slop = slop; this.threshold = threshold; lastAt = downAt;
        valid = downAt >= 0 && device >= 0 && point(x, y) && timeout > 0 && timeout <= MAX_GESTURE
                && Float.isFinite(slop) && slop > 0 && Float.isFinite(threshold) && threshold > slop;
    }

    public synchronized void event(int display, int device, long down, long at, int action,
                                   int pointers, float x, float y, long now) {
        if (!valid) return;
        if (action == DOWN) { cancel(); return; }
        if (released) return;
        if (display != 0 || device != this.device || down != downAt || pointers != 1 || !point(x, y)
                || at < lastAt || at < downAt || now < at || now - at > MAX_EVENT_AGE
                || at - downAt > MAX_GESTURE || now - downAt > MAX_GESTURE
                || (action != MOVE && action != UP)) {
            cancel(); return;
        }
        lastAt = at;
        double dx = (double) x - originX, dy = (double) y - originY;
        double distance = Math.hypot(dx, dy);
        if (at - downAt < timeout) {
            if (distance > slop) { cancel(); return; }
            direction = null;
        } else {
            direction = distance < threshold ? null
                    : Math.abs(dx) > Math.abs(dy) ? dx < 0 ? "left" : "right" : dy < 0 ? "up" : "down";
        }
        this.x = x; this.y = y;
        if (action == UP) released = true;
    }

    public synchronized boolean valid() { return valid; }
    public synchronized boolean released() { return released; }
    public synchronized boolean held() { return valid && lastAt - downAt >= timeout; }
    public synchronized String direction() { return direction; }
    public synchronized float x() { return x; }
    public synchronized float y() { return y; }
    public long downAt() { return downAt; }
    public int device() { return device; }

    private void cancel() { valid = false; released = false; direction = null; }
    private static boolean point(float x, float y) {
        return Float.isFinite(x) && Float.isFinite(y) && x >= 0 && y >= 0;
    }
}
