package io.github.yudigaga.taplusbridge.drag;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DragDirectionTest {
    @Test public void dominantAxisChoosesEachDirectionFromTheSameCenter() {
        assertEquals("up", DragDirection.at(200, 20, 400, 800, 60));
        assertEquals("down", DragDirection.at(200, 780, 400, 800, 60));
        assertEquals("left", DragDirection.at(10, 400, 400, 800, 60));
        assertEquals("right", DragDirection.at(390, 400, 400, 800, 60));
    }
    @Test public void centerJitterAndCancellationNeverSelectAnAction() {
        assertNull(DragDirection.at(200, 400, 400, 800, 60));
        assertNull(DragDirection.at(230, 430, 400, 800, 60));
        assertNull(DragDirection.at(200, 460, 400, 800, 60));
        assertEquals("down", DragDirection.at(200, 461, 400, 800, 60));
    }
    @Test public void offscreenOrNonFiniteCoordinatesDoNotBecomeDirections() {
        assertNull(DragDirection.at(-1, 400, 400, 800, 60));
        assertNull(DragDirection.at(401, 400, 400, 800, 60));
        assertNull(DragDirection.at(200, Float.NaN, 400, 800, 60));
        assertNull(DragDirection.at(Float.POSITIVE_INFINITY, 0, 400, 800, 60));
        assertNull(DragDirection.at(10, 10, 0, 800, 60));
    }
}
