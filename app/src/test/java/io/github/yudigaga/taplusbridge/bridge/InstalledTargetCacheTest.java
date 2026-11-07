package io.github.yudigaga.taplusbridge.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public final class InstalledTargetCacheTest {
    @Test public void reusesFreshPackageStateAndInvalidationForcesARead() {
        InstalledTargetCache cache = new InstalledTargetCache();
        int[] reads = {0};
        assertTrue(cache.isInstalled("com.example.reader", 100, () -> { reads[0]++; return true; }));
        assertTrue(cache.isInstalled("com.example.reader", 101, () -> { reads[0]++; return false; }));
        assertEquals(1, reads[0]);
        cache.invalidate("com.example.reader");
        assertFalse(cache.isInstalled("com.example.reader", 102, () -> { reads[0]++; return false; }));
        assertEquals(2, reads[0]);
    }

    @Test public void expiryIsABackstopWhenPackageChangeBroadcastIsMissed() {
        InstalledTargetCache cache = new InstalledTargetCache();
        int[] reads = {0};
        assertFalse(cache.isInstalled("com.example.reader", 100, () -> { reads[0]++; return false; }));
        assertTrue(cache.isInstalled("com.example.reader", 5_000_000_101L,
                () -> { reads[0]++; return true; }));
        assertEquals(2, reads[0]);
    }

    @Test public void transientPackageManagerFailureIsNotCached() {
        InstalledTargetCache cache = new InstalledTargetCache();
        assertThrows(IllegalStateException.class, () -> cache.isInstalled("com.example.reader", 100,
                () -> { throw new IllegalStateException("package manager unavailable"); }));
        assertTrue(cache.isInstalled("com.example.reader", 101, () -> true));
    }
}
