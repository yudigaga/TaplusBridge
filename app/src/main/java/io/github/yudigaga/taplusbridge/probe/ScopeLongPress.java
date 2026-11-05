package io.github.yudigaga.taplusbridge.probe;

/** One verified physical gesture. The caller owns touchscreen, scope and window authentication. */
public final class ScopeLongPress {
    public record Trigger(long downAt, long upAt, int device, int display, float x, float y) { }

    private static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3, POINTER_DOWN = 5, POINTER_UP = 6;
    private static final int IDLE = 0, SINGLE = 1, WAIT_SECOND = 2, DUAL = 3, DUAL_READY = 4;
    private static final long MAX_EVENT_AGE = 300, SECOND_POINTER_WINDOW = 200;
    private int state, device, mode, remainingOrigins;
    private long downAt, lastAt, secondAt, timeout;
    private float firstX, firstY, secondX, secondY, slop;

    public synchronized Trigger event(int display, int device, long downAt, long eventAt,
                                      int action, int pointers, float x, float y,
                                      float secondX, float secondY, int mode,
                                      long timeout, float slop, long now) {
        if (action == CANCEL) { cancel(); return null; }
        if (display != 0 || device < 0 || (mode != 1 && mode != 2) || timeout <= 0
                || !Float.isFinite(slop) || slop < 0 || !point(x, y)
                || pointers < 1 || pointers > 2 || downAt < 0 || eventAt < downAt
                || now < eventAt || now - eventAt > MAX_EVENT_AGE) {
            cancel(); return null;
        }
        if (state == IDLE) {
            if (action != DOWN || pointers != 1 || eventAt != downAt) return null;
            this.device = device; this.downAt = downAt; lastAt = eventAt;
            this.mode = mode; this.timeout = timeout; this.slop = slop;
            firstX = x; firstY = y;
            state = mode == 1 ? SINGLE : WAIT_SECOND;
            return null;
        }
        if (device != this.device || downAt != this.downAt || eventAt < lastAt
                || mode != this.mode || timeout != this.timeout || Float.compare(slop, this.slop) != 0
                || action == DOWN) {
            cancel(); return null;
        }
        lastAt = eventAt;
        if (state == SINGLE) {
            if (pointers != 1 || !near(x, y, firstX, firstY)) { cancel(); return null; }
            if (action == MOVE) return null;
            if (action == UP && eventAt - this.downAt >= timeout) return finish(eventAt);
        } else if (state == WAIT_SECOND) {
            if (eventAt - this.downAt > SECOND_POINTER_WINDOW || !near(x, y, firstX, firstY)) {
                cancel(); return null;
            }
            if (action == MOVE && pointers == 1) return null;
            if (action == POINTER_DOWN && pointers == 2 && point(secondX, secondY)) {
                this.secondX = secondX; this.secondY = secondY; secondAt = eventAt;
                state = DUAL; return null;
            }
        } else if (state == DUAL) {
            if (pointers != 2 || !point(secondX, secondY) || !near(x, y, firstX, firstY)
                    || !near(secondX, secondY, this.secondX, this.secondY)) {
                cancel(); return null;
            }
            if (action == MOVE) return null;
            if (action == POINTER_UP && eventAt - secondAt >= timeout) {
                // No pointer IDs are exposed by this interface. Either original point can own
                // the remaining pointer, but later events may only narrow that choice.
                remainingOrigins = 3; state = DUAL_READY; return null;
            }
        } else if (state == DUAL_READY) {
            if (pointers != 1 || (action != MOVE && action != UP)) { cancel(); return null; }
            int origins = (near(x, y, firstX, firstY) ? 1 : 0)
                    | (near(x, y, this.secondX, this.secondY) ? 2 : 0);
            remainingOrigins &= origins;
            if (remainingOrigins == 0) { cancel(); return null; }
            if (action == MOVE) return null;
            return finish(eventAt);
        }
        cancel(); return null;
    }

    public synchronized void cancel() { state = IDLE; remainingOrigins = 0; }

    public synchronized boolean isActive() { return state != IDLE; }

    private Trigger finish(long upAt) {
        Trigger result = new Trigger(downAt, upAt, device, 0, firstX, firstY);
        cancel(); return result;
    }

    private boolean near(float x, float y, float originX, float originY) {
        double dx = (double) x - originX, dy = (double) y - originY;
        return dx * dx + dy * dy <= (double) slop * slop;
    }

    private static boolean point(float x, float y) {
        return Float.isFinite(x) && Float.isFinite(y) && x >= 0 && y >= 0;
    }
}
