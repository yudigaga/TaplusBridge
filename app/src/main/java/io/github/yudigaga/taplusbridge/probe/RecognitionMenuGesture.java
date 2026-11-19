package io.github.yudigaga.taplusbridge.probe;

/** Complete physical single-pointer gestures eligible to replace a native recognition menu. */
final class RecognitionMenuGesture {
    static final class Lease {
        private final int display, device, source, pointerId;
        private final long downAt;
        private final float downX, downY;
        private long lastAt, upAt = -1;
        private double maxDistanceSquared;

        private Lease(int display, int device, int source, int pointerId, long downAt, float x, float y) {
            this.display = display;
            this.device = device;
            this.source = source;
            this.pointerId = pointerId;
            this.downAt = lastAt = downAt;
            downX = x;
            downY = y;
        }
    }

    private Lease current;

    synchronized Lease observe(int action, int display, int device, int source, int pointerId, int count,
            long down, long at, long now, boolean canceled, boolean exclusive, float x, float y) {
        if (action == PhysicalTouchState.DOWN) current = null;
        if (canceled || !exclusive || display != 0 || device < 0 || (source & 0x1002) != 0x1002
                || pointerId < 0 || pointerId > 31 || count != 1
                || !Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0
                || down < 0 || at < down || at > now || now - at > 300) {
            current = null;
            return null;
        }
        if (action == PhysicalTouchState.DOWN) {
            if (at == down) current = new Lease(display, device, source, pointerId, down, x, y);
            return current;
        }
        Lease lease = current;
        if ((action != PhysicalTouchState.MOVE && action != PhysicalTouchState.UP)
                || lease == null || lease.upAt >= 0 || lease.display != display || lease.device != device
                || lease.source != source || lease.pointerId != pointerId || lease.downAt != down
                || at < lease.lastAt) {
            current = null;
            return null;
        }
        double dx = (double) x - lease.downX, dy = (double) y - lease.downY;
        lease.maxDistanceSquared = Math.max(lease.maxDistanceSquared, dx * dx + dy * dy);
        lease.lastAt = at;
        if (action == PhysicalTouchState.UP) lease.upAt = at;
        return lease;
    }

    synchronized boolean completed(Lease lease, long requiredDuration, float slop) {
        return lease != null && lease == current && lease.upAt >= 0 && requiredDuration >= 0
                && lease.upAt - lease.downAt >= requiredDuration && Float.isFinite(slop) && slop >= 0
                && lease.maxDistanceSquared <= (double) slop * slop;
    }

    synchronized void invalidate() {
        current = null;
    }
}
