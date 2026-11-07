package io.github.yudigaga.taplusbridge.probe;

import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reflection keeps the initial missing-feature check compilable before the optional collector exists. */
public final class SystemAssistContentApiTest {
    @Test public void optionalCollectorExposesReadAndBoundedWaitWithoutSourceInput() throws Exception {
        Class<?> collector = Class.forName("io.github.yudigaga.taplusbridge.probe.SystemAssistContent");
        Class<?> snapshot = Class.forName("io.github.yudigaga.taplusbridge.probe.ScopeCaptureWindow$Snapshot");
        Class<?> config = Class.forName("io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot");
        assertTrue(Modifier.isPublic(collector.getMethod("arm", snapshot, config,
                long.class, long.class, long.class).getModifiers()));
        assertEquals(boolean.class, collector.getMethod("pending", snapshot, config, long.class).getReturnType());
        Class<?> result = collector.getMethod("read", snapshot, config, long.class).getReturnType();
        assertEquals(String.class, result.getMethod("text").getReturnType());
        assertEquals(List.class, result.getMethod("links").getReturnType());
        assertEquals(String.class, result.getMethod("deliveryText").getReturnType());
        assertNotNull(collector.getMethod("cancel"));
    }
}
