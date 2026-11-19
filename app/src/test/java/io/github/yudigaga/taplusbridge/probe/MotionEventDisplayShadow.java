package io.github.yudigaga.taplusbridge.probe;

import android.view.MotionEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowMotionEvent;

/** Supplies the display primitive that Robolectric's default MotionEvent shadow does not retain. */
@Implements(MotionEvent.class)
public class MotionEventDisplayShadow extends ShadowMotionEvent {
    static final AtomicInteger hookDisplayOneReads = new AtomicInteger();
    @RealObject private MotionEvent realEvent;
    private int displayId;

    @Implementation public int getDisplayId() {
        if (displayId == 1) {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().equals(SystemProbe.class.getName())) {
                    hookDisplayOneReads.incrementAndGet();
                    break;
                }
            }
        }
        return displayId;
    }

    @Implementation public void setDisplayId(int value) { displayId = value; }

    @Implementation protected void recycle() {
        displayId = 0;
        Shadow.directlyOn(realEvent, MotionEvent.class, "recycle");
    }

    @Resetter public static void resetHookReads() { hookDisplayOneReads.set(0); }
}
