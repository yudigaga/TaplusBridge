package io.github.yudigaga.taplusbridge.lens;

import android.net.Uri;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class LensSearchUrlAndroidTest {
    private static final String BASE = "https://www.google.com/search?p=opaque%2Bimage%2Fvalue%3D&foo=one&foo=two&udm=26&q=old&q=older&vsrid=session#section";

    @Test public void preservesRawSessionParametersAndRepeatedValuesWhileReplacingQueryAndMode() {
        String built = LensSearchUrl.build(BASE, "  红苹果  ", "visual"); assertNotNull(built);
        Uri uri = Uri.parse(built);
        assertTrue(uri.getEncodedQuery().startsWith("p=opaque%2Bimage%2Fvalue%3D&foo=one&foo=two&vsrid=session&"));
        assertEquals(List.of("one", "two"), uri.getQueryParameters("foo"));
        assertEquals(List.of("红苹果"), uri.getQueryParameters("q"));
        assertEquals(List.of("44"), uri.getQueryParameters("udm"));
        assertEquals("https", uri.getScheme()); assertEquals("www.google.com", uri.getAuthority());
        assertNull(uri.getFragment());
    }

    @Test public void observedModesUseTheRequestedGoogleModeWithoutChangingTheSession() {
        for (String[] pair : new String[][]{{"all", "26"}, {"ai", "50"}, {"exact", "48"}, {"visual", "44"}}) {
            Uri uri = Uri.parse(LensSearchUrl.build(BASE, "apple", pair[0]));
            assertEquals(pair[1], uri.getQueryParameter("udm")); assertEquals("session", uri.getQueryParameter("vsrid"));
            assertEquals(List.of("one", "two"), uri.getQueryParameters("foo"));
        }
    }

    @Test public void emptyQueryRemovesAllPreviousTextWithoutInventingAnotherRequest() {
        for (String query : new String[]{null, "", "   "}) {
            Uri uri = Uri.parse(LensSearchUrl.build(BASE, query, "all"));
            assertFalse(uri.getQueryParameterNames().contains("q"));
            assertEquals("26", uri.getQueryParameter("udm")); assertEquals("session", uri.getQueryParameter("vsrid"));
        }
    }

    @Test public void queryCharactersCannotInjectParametersOrAFragment() {
        String query = "苹果 & q=other#fragment?x=1";
        Uri uri = Uri.parse(LensSearchUrl.build(BASE, query, "ai"));
        assertEquals(query, uri.getQueryParameter("q")); assertEquals(1, uri.getQueryParameters("q").size());
        assertNull(uri.getQueryParameter("x")); assertNull(uri.getFragment());
    }

    @Test public void removesEncodedParameterNamesWithoutDecodingTheRemainingImageToken() {
        String built = LensSearchUrl.build("https://www.google.com/search?vsrid=s&%71=old&q=older&%75dm=26&udm=48&p=a%23b%252Fc%2Bd", "new", "all");
        assertNotNull(built);
        assertTrue(built.contains("p=a%23b%252Fc%2Bd")); assertFalse(built.contains("%71=")); assertFalse(built.contains("%75dm="));
        assertEquals(List.of("new"), Uri.parse(built).getQueryParameters("q"));
        assertEquals(List.of("26"), Uri.parse(built).getQueryParameters("udm"));
    }

    @Test public void malformedBasesAndUnrelatedSearchesCannotBecomeImageSearchSessions() {
        for (String base : new String[]{null, "", "https://www.google.com/search?q=ordinary", "http://www.google.com/search?udm=26",
                "https://www.google.com.evil.example/search?udm=26", "https://user:pass@www.google.com/search?udm=26",
                "https://www.google.com:8443/search?udm=26", "javascript:alert(1)", "intent://google/search?udm=26",
                "https://www.google.com/search?udm=26&p=%ZZ", "https://www.google.com/search?udm=26\r\nX-Injected: 1"})
            assertNull(base, LensSearchUrl.build(base, "apple", "all"));
    }

    @Test public void invalidModesAndOversizedOrControlCharacterQueriesAreRejected() {
        for (String mode : new String[]{null, "", "AI", "unknown", "all\r\n"}) assertNull(LensSearchUrl.build(BASE, "apple", mode));
        assertNotNull(LensSearchUrl.build(BASE, "a".repeat(512), "all"));
        assertNull(LensSearchUrl.build(BASE, "a".repeat(513), "all"));
        for (String query : new String[]{"apple\r\nother", "\nleading", "tab\ttext", "zero\u0000text"})
            assertNull(LensSearchUrl.build(BASE, query, "all"));
    }

    @Test public void preservesTheOriginalLensHostAndExplicitHttpsPort() {
        Uri uri = Uri.parse(LensSearchUrl.build("https://lens.google.com:443/search?p=image%2Bpayload#old", "apple", "exact"));
        assertEquals("lens.google.com:443", uri.getAuthority()); assertEquals("/search", uri.getPath());
        assertEquals("48", uri.getQueryParameter("udm")); assertTrue(uri.getEncodedQuery().contains("p=image%2Bpayload"));
        assertNull(uri.getFragment());
    }

    @Test public void pOnlyImageSessionsProduceUsableAdvancedTargetsFromTheImmutableOriginalBase() {
        String original = "https://www.google.com/search?p=real%2Bimage%2Fcontext%3D&udm=26#original";
        assertTrue(LensPageParser.isResultUrl(original));
        for (String[] mode : new String[][]{{"ai", "50"}, {"exact", "48"}, {"visual", "44"}}) {
            String target = LensSearchUrl.build(original, "描述这张图片", mode[0]);
            assertNotNull(target); assertTrue(PortalLensPanel.webUrl(target));
            Uri uri = Uri.parse(target);
            assertEquals("www.google.com", uri.getAuthority()); assertEquals("/search", uri.getPath());
            assertEquals(mode[1], uri.getQueryParameter("udm"));
            assertTrue(uri.getEncodedQuery().startsWith("p=real%2Bimage%2Fcontext%3D&"));
            assertEquals("描述这张图片", uri.getQueryParameter("q")); assertNull(uri.getFragment());
            assertTrue(LensPageParser.isResultUrl(target));
            assertTrue(LensSession.isResultUrl(target));
        }
        assertEquals("26", Uri.parse(original).getQueryParameter("udm"));
    }

    @Test public void advancedSearchUrlsRequireANonemptyImageContextOnTheTrustedResultOrigin() {
        for (String udm : new String[]{"44", "48", "50"}) {
            String valid = "https://www.google.com/search?udm=" + udm + "&p=image%2Bcontext";
            assertTrue(valid, LensPageParser.isResultUrl(valid));
            assertFalse(LensPageParser.isResultUrl("https://www.google.com/search?udm=" + udm));
            assertFalse(LensPageParser.isResultUrl("https://www.google.com/search?udm=" + udm + "&p="));
            assertFalse(LensPageParser.isResultUrl("https://www.google.com.evil.example/search?udm=" + udm + "&p=image"));
            assertFalse(LensPageParser.isResultUrl("https://www.google.com/other?udm=" + udm + "&p=image"));
        }
        assertFalse(LensPageParser.isResultUrl("https://www.google.com/search?udm=51&p=image"));
        assertFalse(LensPageParser.isResultUrl("https://www.google.com/search?p=image"));
    }
}
