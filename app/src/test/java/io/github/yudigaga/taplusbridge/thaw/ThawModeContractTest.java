package io.github.yudigaga.taplusbridge.thaw;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ThawModeContractTest {
    @Test public void onlyRootAndShizukuRemainAvailable() {
        assertArrayEquals(new String[] {"ROOT", "SHIZUKU"}, Arrays.stream(ThawEngine.Mode.values())
                .map(Enum::name).toArray(String[]::new));
    }
}
