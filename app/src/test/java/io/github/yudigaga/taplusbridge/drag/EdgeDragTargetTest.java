package io.github.yudigaga.taplusbridge.drag;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EdgeDragTargetTest {
    private EdgeDragTarget.Contact at(float x, float y) {
        return EdgeDragTarget.at(x, y, 200, 400, 400, 800, 1);
    }

    @Test public void eachEdgeApproachesUntilThePreviewTouchesIt() {
        float[][] points = {{200, 80}, {200, 720}, {40, 400}, {360, 400}};
        String[] directions = {"up", "down", "left", "right"};
        for (int i = 0; i < points.length; i++) {
            var contact = at(points[i][0], points[i][1]);
            assertEquals(directions[i], contact.approaching()); assertEquals(directions[i], contact.touching());
            assertTrue(contact.inset() >= 0); assertTrue(contact.progress() > 0);
        }
        assertNull(at(200, 340).touching()); assertEquals("up", at(200, 340).approaching());
        assertTrue(at(200, 200).inset() > at(200, 340).inset());
    }

    @Test public void returningToTheCenterAndInvalidGeometryCancelContact() {
        assertNotNull(at(200, 80).touching());
        assertNull(at(200, 400).touching()); assertNull(at(201, 401).approaching());
        assertNull(EdgeDragTarget.at(Float.NaN, 2, 200, 400, 400, 800, 1).touching());
        assertNull(at(-1, 400).touching()); assertNull(at(401, 400).touching());
        assertNull(EdgeDragTarget.at(100, 100, 200, 400, 400, 800, 0).touching());
    }

    @Test public void rainbowStaysAgainstThePreviewWhenDraggedBeyondFirstContact() {
        for (float y : new float[]{140, 100, 40, 0}) {
            var contact = at(200, y);
            assertEquals("up", contact.touching());
            float previewTop = Math.max(0, y - 36);
            assertTrue(contact.inset() <= previewTop);
            assertTrue(previewTop - contact.inset() <= 12);
        }
    }

    @Test public void densityScalingKeepsContactAndMotionConsistent() {
        var a = at(200, 80);
        var b = EdgeDragTarget.at(600, 240, 600, 1200, 1200, 2400, 3);
        assertEquals(a.touching(), b.touching()); assertEquals(a.inset() * 3, b.inset(), .001);
        assertNull(EdgeDragTarget.at(12, 10, 10, 10, 400, 800, 1).touching());
    }

    @Test public void vibrationOccursOnlyOnNewContactOrReentry() {
        DragContactFeedback feedback = new DragContactFeedback();
        assertFalse(feedback.update(null)); assertTrue(feedback.update("up"));
        assertFalse(feedback.update("up")); assertTrue(feedback.update("left"));
        assertFalse(feedback.update(null)); assertTrue(feedback.update("left"));
    }
}
