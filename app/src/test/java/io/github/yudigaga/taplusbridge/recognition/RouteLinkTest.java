package io.github.yudigaga.taplusbridge.recognition;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RouteLinkTest {
    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String MD4 = "0123456789abcdef0123456789abcdef";
    private static final String MAGNET = "magnet:?xt=urn:btih:" + SHA1;
    private static final String ED2K = "ed2k://|file|Example.iso|123456|" + MD4 + "|/";

    @Test public void torrentMagnetsAcceptHexBase32AndHybridTopicsWithoutRewritingTheInput() {
        for (String link : List.of(MAGNET, MAGNET.toUpperCase(),
                "magnet:?xt=urn:btih:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567",
                "magnet:?xt=urn:btmh:1220" + "ab".repeat(32),
                MAGNET + "&xt=urn:btmh:1220" + "cd".repeat(32),
                "magnet:?xt=urn%3Abtih%3A" + SHA1 + "&dn=A%20B+Plus&tr=https%3A%2F%2Ftracker.example%2Fannounce")) {
            assertTrue(link, RouteLink.valid(link));
            assertEquals("magnet:", RouteLink.ruleKey(link));
        }
    }

    @Test public void magnetsNeedACompleteSupportedExactTopic() {
        for (String link : List.of("magnet:", "magnet://", "magnet:?dn=label", "magnet:?xt=urn:btih:short",
                "magnet:?xt=urn:btih:" + "z".repeat(40), "magnet:?xt=urn:btmh:1220" + "a".repeat(63),
                MAGNET + "#fragment", MAGNET + "&dn=bad%", MAGNET + "&dn=bad%GG",
                MAGNET + "&dn=%C3%28", MAGNET + "&dn=%C2%80", MAGNET + "&dn=%ED%A0%80",
                MAGNET + "&xt=urn:btih:short", MAGNET + "&dn=<raw>"))
            assertFalse(link, RouteLink.valid(link));
    }

    @Test public void ed2kAcceptsOnlyCompleteFileLinksAndKeepsRawSeparators() {
        for (String link : List.of(ED2K, ED2K.toUpperCase(),
                "ed2k://|file|中文%20测试.iso|0|" + MD4 + "|/",
                "ed2k://|file|Zero.iso|18446744073709551615|" + MD4 + "|/",
                ED2K.replace("|/", "|h=ABCDEFGHIJKLMNOPQRSTUVWXYZ234567|p=" + MD4 + ":" + MD4 + "|/"),
                ED2K.replace("|/", "|s=https://download.example/file.iso|/"),
                ED2K + "|sources,192.0.2.1:4662,[2001:db8::1]:4662|/")) {
            assertTrue(link, RouteLink.valid(link));
            assertEquals("ed2k:", RouteLink.ruleKey(link));
        }
    }

    @Test public void ed2kDoesNotTreatServersTitlesOrBrokenPayloadsAsDownloads() {
        for (String link : List.of("ed2k:", "ed2k://|server|192.0.2.1|4661|/", "ed2k://|serverlist|https://example.com/list|/",
                ED2K.replace("Example.iso", ""), ED2K.replace("Example.iso", ".."),
                ED2K.replace("Example.iso", "parent%2Fname"), ED2K.replace("Example.iso", "bad%7Cname"),
                ED2K.replace("Example.iso", "bad%"), ED2K.replace("Example.iso", "%C2%80"),
                ED2K.replace("Example.iso", "%ED%A0%80"), ED2K.replace("123456", "-1"),
                ED2K.replace("123456", "1.2"), ED2K.replace("123456", "18446744073709551616"),
                ED2K.replace(MD4, "a".repeat(31)), ED2K.substring(0, ED2K.length() - 1),
                ED2K + "extra", ED2K + "|sources,192.0.2.1:0|/",
                ED2K.replace("|/", "|unknown=value|/"),
                ED2K.replace("|/", "|s=file:///sdcard/test|/")))
            assertFalse(link, RouteLink.valid(link));
    }

    @Test public void routingKeysNeverUseATrackerHostOrGuessFromATitle() {
        assertEquals("magnet:", RouteLink.ruleKey(MAGNET + "&tr=https://tracker.example/announce"));
        assertEquals("ed2k:", RouteLink.ruleKey(ED2K));
        assertEquals("sub.example.com", RouteLink.ruleKey("HTTPS://Sub.Example.Com/path"));
        assertTrue(RouteLink.protocolRule("magnet:"));
        assertTrue(RouteLink.protocolRule("ed2k:"));
        assertFalse(RouteLink.protocolRule("MAGNET:"));
        assertFalse(RouteLink.protocolRule("https:"));
        assertFalse(RouteLink.protocolRule(null));
        assertNull(RouteLink.ruleKey("title " + MAGNET));
    }

    @Test public void allPublicLinkKindsRespectLengthControlsAndSurrogateBoundaries() {
        for (String link : List.of(MAGNET, ED2K, "https://example.com/path")) {
            assertTrue(RouteLink.valid(link, link.length()));
            assertFalse(RouteLink.valid(link, link.length() - 1));
            for (String unsafe : List.of(link + "\u0000", link + "%00", link + "%0A", link + "\u007f", link + "\uD800"))
                assertFalse(unsafe, RouteLink.valid(unsafe));
        }
        assertFalse(RouteLink.valid(null));
        assertFalse(RouteLink.valid(""));
        assertFalse(RouteLink.valid(MAGNET + "&dn=" + "x".repeat(8192)));
        assertFalse(RouteLink.valid("javascript:alert(1)"));
        assertFalse(RouteLink.valid("intent://example.com#Intent;end"));
        assertFalse(RouteLink.valid("file:///sdcard/download.iso"));
    }

    @Test public void strictHttpValidationKeepsItsExistingHostAndCredentialRules() {
        for (String link : List.of("https://example.com/path?q=one%2Btwo#fragment", "http://localhost:8080/",
                "http://[2001:db8::1]/")) assertTrue(link, RouteLink.validHttp(link));
        for (String link : List.of(MAGNET, ED2K, "https://user:pass@example.com/", "http://example.com:/",
                "http://example.com:0/", "http://example.com:65536/", "http://-bad.example/", "http://example.com./",
                "http://[fe80::1%25eth0]/", "https://example.com/%0a", "https://example.com/%ZZ"))
            assertFalse(link, RouteLink.validHttp(link));
    }
}
