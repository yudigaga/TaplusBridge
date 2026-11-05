package io.github.yudigaga.taplusbridge.drag;

import android.os.SystemClock;

/** System-process gesture ownership only; no text, links, images or source identifiers are retained. */
public final class DragGestureGate {
    private static final State CURRENT = new State();
    private DragGestureGate() { }

    public static boolean suppressLongPress() { return CURRENT.suppress(SystemClock.elapsedRealtime()); }
    static void begin() { CURRENT.begin(SystemClock.elapsedRealtime()); }
    static void beginPhysical(int display, int device, long downAt) {
        CURRENT.beginPhysical(SystemClock.elapsedRealtime(), display, device, downAt);
    }
    static void end() { CURRENT.end(SystemClock.elapsedRealtime()); }
    public static void physicalInputUp() { CURRENT.end(SystemClock.elapsedRealtime()); }
    public static void physicalUp(int display, int device, long downAt) {
        CURRENT.physicalUp(display, device, downAt, SystemClock.elapsedRealtime());
    }
    static void releaseTransferred() { CURRENT.releaseTransferred(SystemClock.elapsedRealtime()); }
    public static void physicalDown(int display, int device, long downAt) {
        CURRENT.physicalDown(display, device, downAt, SystemClock.elapsedRealtime());
    }

    static final class State {
        static final long MAX_DRAG_MILLIS = 60_000, END_TAIL_MILLIS = 1_000;
        private long began = -1, ended = -1;
        private int ownerDisplay = -1, ownerDevice = -1;
        private long ownerDownAt = -1;

        synchronized void begin(long now) {
            if (now < 0) throw new IllegalArgumentException("drag_time");
            setOwnerless(now);
        }

        synchronized void beginPhysical(long now, int display, int device, long downAt) {
            if (now < 0 || display != 0 || device < 0 || downAt < 0)
                throw new IllegalArgumentException("physical_drag");
            began = now; ended = -1; ownerDisplay = display; ownerDevice = device; ownerDownAt = downAt;
        }

        synchronized void end(long now) {
            if (ownerDevice < 0 && suppress(now) && ended < 0) ended = now;
        }

        synchronized void nextDown(long now) {
            if (!suppress(now) || ended >= 0) clear();
        }

        synchronized void physicalDown(int display, int device, long downAt, long now) {
            if (!suppress(now)) return;
            if (ended >= 0 || (ownerDisplay == display && ownerDevice == device && ownerDownAt != downAt)) clear();
        }

        synchronized void physicalUp(int display, int device, long downAt, long now) {
            if (ownerDisplay == display && ownerDevice == device && ownerDownAt == downAt && suppress(now)) clear();
        }

        synchronized void releaseTransferred(long now) {
            if (ownerDevice >= 0 && suppress(now)) clear();
        }

        synchronized boolean suppress(long now) {
            if (began < 0) return false;
            if (now < began || now - began >= MAX_DRAG_MILLIS
                    || (ended >= 0 && (now < ended || now - ended >= END_TAIL_MILLIS))) {
                clear(); return false;
            }
            return true;
        }

        private void setOwnerless(long now) {
            began = now; ended = -1; ownerDisplay = ownerDevice = -1; ownerDownAt = -1;
        }

        private void clear() {
            began = ended = -1; ownerDisplay = ownerDevice = -1; ownerDownAt = -1;
        }
    }
}
