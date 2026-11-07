package io.github.yudigaga.taplusbridge.recognition;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BatchLinksTest {
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    private static final String ED2K = "ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/";
    @Test public void orderAndExactDataAreKeptInAnIndependentImmutableSnapshot() {
        ArrayList<String> supplied = new ArrayList<>(List.of(MAGNET, ED2K, MAGNET));
        List<String> result = BatchLinks.normalize(supplied); supplied.clear();
        assertEquals(List.of(MAGNET, ED2K), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(MAGNET));
    }
    @Test public void malformedTypesProtocolsAndCountsAreRejectedWithoutPartialResults() {
        for (Object invalid : new Object[]{null, MAGNET, List.of(), List.of(7), List.of("https://example.com/"),
                List.of("magnet:?dn=title-only"), List.of(MAGNET, "file:///private"), java.util.Collections.nCopies(65, MAGNET)})
            assertThrows(String.valueOf(invalid), IllegalArgumentException.class, () -> BatchLinks.normalize(invalid));
        assertEquals(1, BatchLinks.normalize(java.util.Collections.nCopies(64, MAGNET)).size());
    }
    @Test public void aggregatePayloadBoundsDoNotDropTailLinks() {
        String large = MAGNET + "&dn=" + "x".repeat(6000);
        assertEquals(4, BatchLinks.normalize(List.of(large + "1", large + "2", large + "3", large + "4")).size());
        assertThrows(IllegalArgumentException.class,
                () -> BatchLinks.normalize(List.of(large + "1", large + "2", large + "3", large + "4", large + "5")));
    }
    @Test public void onlyVerifiedBulkReceiverIsAdvertised() {
        assertTrue(BatchLinks.supportedTarget("com.ylmf.androidclient"));
        assertFalse(BatchLinks.supportedTarget("com.xunlei.downloadprovider"));
        assertFalse(BatchLinks.supportedTarget(null));
    }
}
