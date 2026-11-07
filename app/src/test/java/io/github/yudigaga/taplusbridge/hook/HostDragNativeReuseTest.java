package io.github.yudigaga.taplusbridge.hook;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostDragNativeReuseTest {
    @Test public void onlyTheAttachedOriginalPanelCanBeReused() {
        assertEquals(1, HostDragHooks.nativeTarget(false, true, true));
        assertEquals(0, HostDragHooks.nativeTarget(false, true, false));
        assertEquals(0, HostDragHooks.nativeTarget(false, false, true));
        assertEquals(0, HostDragHooks.nativeTarget(false, false, false));
    }

    @Test public void naturalDismissalAllowsTheNewActionWithoutCancellingAnotherPanel() {
        assertEquals(2, HostDragHooks.nativeTarget(true, true, true));
        assertEquals(2, HostDragHooks.nativeTarget(true, true, false));
        assertEquals(2, HostDragHooks.nativeTarget(true, false, true));
        assertEquals(2, HostDragHooks.nativeTarget(true, false, false));
    }
}
