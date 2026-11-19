package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.drag.DragTrace;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class DragTraceAndroidTest {
    @Test public void primitiveTraceRoundTripsWithoutPayloadOrCapability() {
        long now = SystemClock.elapsedRealtime(), uptime = SystemClock.uptimeMillis();
        DragTrace trace = new DragTrace(2, uptime, now);
        Bundle wire = new Bundle(); trace.writeTo(wire);
        assertEquals(3, wire.size());
        assertEquals(trace, DragTrace.read(wire));
        assertEquals("2_" + uptime, trace.gestureId());
        assertEquals(25, trace.elapsedAt(now + 25));
        assertFalse(trace.fields(now + 25).contains("token"));
    }

    @Test public void absentMalformedAndFutureTraceAreUnavailable() {
        assertEquals("unavailable", DragTrace.read(Bundle.EMPTY).gestureId());
        Bundle malformed = new Bundle(); malformed.putString("diagnostic_device", "private");
        assertEquals(-1, DragTrace.read(malformed).elapsedAt(SystemClock.elapsedRealtime()));
        Bundle future = new Bundle();
        new DragTrace(1, SystemClock.uptimeMillis(), SystemClock.elapsedRealtime() + 1).writeTo(future);
        assertEquals("unavailable", DragTrace.read(future).gestureId());
    }

    @Test public void deliveryTraceIsScopedAndRestoredAfterFailure() {
        DragTrace outer = new DragTrace(1, 1, 1), inner = new DragTrace(2, 2, 2);
        assertEquals(DragTrace.unavailable(), HostLongPressDrag.deliveringTrace());
        HostLongPressDrag.deliverTraced(outer, () -> {
            assertEquals(outer, HostLongPressDrag.deliveringTrace());
            assertThrows(IllegalStateException.class, () -> HostLongPressDrag.deliverTraced(inner, () -> {
                assertEquals(inner, HostLongPressDrag.deliveringTrace());
                throw new IllegalStateException();
            }));
            assertEquals(outer, HostLongPressDrag.deliveringTrace());
        });
        assertEquals(DragTrace.unavailable(), HostLongPressDrag.deliveringTrace());
    }
}
