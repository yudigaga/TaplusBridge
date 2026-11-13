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

    @Test public void startsNearEachEdgeCanReachTheirTargetWithLessThanFortyEightDpOfTravel() {
        float[][] paths = {{200,20,200,10},{200,780,200,790},{20,400,10,400},{380,400,390,400}};
        String[] directions = {"up", "down", "left", "right"};
        for (float density : new float[]{1, 3}) for (int i = 0; i < paths.length; i++) {
            float[] p = paths[i];
            var contact = EdgeDragTarget.at(p[2] * density, p[3] * density,
                    p[0] * density, p[1] * density, 400 * density, 800 * density, density);
            assertEquals(directions[i], contact.approaching());
            assertEquals(directions[i], contact.touching());
        }
    }

    @Test public void nearEdgeLongPressesAndMotionBelowEightDpDoNotSelectAnAction() {
        for (float density : new float[]{1, 3}) {
            for (float origin : new float[]{12, 20}) for (float distance : new float[]{0, 2, 7.9f}) {
                assertNull(EdgeDragTarget.at((origin - distance) * density, 400 * density,
                        origin * density, 400 * density, 400 * density, 800 * density, density).approaching());
            }
            assertEquals("left", EdgeDragTarget.at(4 * density, 400 * density,
                    12 * density, 400 * density, 400 * density, 800 * density, density).touching());
        }
        assertNull(EdgeDragTarget.at(0, 400, 0, 400, 400, 800, 1).touching());
    }

    @Test public void startsWithinEightDpOfAnEdgeMustReachTheEdgeBeforeContact() {
        float[][] paths = {{200,4,200,0},{200,796,200,800},{4,400,0,400},{396,400,400,400}};
        String[] directions = {"up", "down", "left", "right"};
        for (float density : new float[]{1, 3}) for (int i = 0; i < paths.length; i++) {
            float[] p = paths[i];
            float beforeX = p[2] + Math.signum(p[0] - p[2]) * .1f;
            float beforeY = p[3] + Math.signum(p[1] - p[3]) * .1f;
            assertNull(EdgeDragTarget.at(beforeX * density, beforeY * density,
                    p[0] * density, p[1] * density, 400 * density, 800 * density, density).touching());
            assertEquals(directions[i], EdgeDragTarget.at(p[2] * density, p[3] * density,
                    p[0] * density, p[1] * density, 400 * density, 800 * density, density).touching());
        }
        assertNull(EdgeDragTarget.at(.1f, 402, 4, 400, 400, 800, 1).touching());
    }

    @Test public void centerAndFarEdgeDragsKeepTheirExistingActivationDistance() {
        assertNull(at(200, 353).approaching());
        assertEquals("up", at(200, 352).approaching());
        assertNull(EdgeDragTarget.at(67, 400, 20, 400, 400, 800, 1).approaching());
        assertEquals("right", EdgeDragTarget.at(68, 400, 20, 400, 400, 800, 1).approaching());
    }

    @Test public void vibrationOccursOnlyOnNewContactOrReentry() {
        DragContactFeedback feedback = new DragContactFeedback();
        assertFalse(feedback.update(null)); assertTrue(feedback.update("up"));
        assertFalse(feedback.update("up")); assertTrue(feedback.update("left"));
        assertFalse(feedback.update(null)); assertTrue(feedback.update("left"));
    }
}
