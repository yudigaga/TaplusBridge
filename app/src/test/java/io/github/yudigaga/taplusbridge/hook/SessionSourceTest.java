package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class SessionSourceTest {
    @Test public void freshLongPressHasItsOwnSource() {
        SessionSource source = new SessionSource();
        assertNull(source.forRecommendation(false));
        source.captured("com.example.reader");
        assertEquals("com.example.reader", source.forRecommendation(false));
    }

    @Test public void delayedClipboardNeverInheritsLongPressOrigin() {
        SessionSource source = new SessionSource();
        source.captured("com.example.old");
        assertNull(source.forRecommendation(true));
        source.clear();
        assertNull(source.forRecommendation(false));
    }

    @Test public void cancellationAndMissingTokensClearPreviousOrigin() {
        SessionSource source = new SessionSource();
        source.captured("com.example.first");
        source.clear();
        assertNull(source.forRecommendation(false));
        source.captured("com.example.second");
        source.captured(null);
        assertNull(source.forRecommendation(false));
        source.captured("com.example;invalid");
        assertNull(source.forRecommendation(false));
    }
}
