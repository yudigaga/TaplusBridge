package io.github.yudigaga.taplusbridge.recognition;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class LinkExtractorTest {
    @Test public void protocolMarkupDecodesAttributeEntitiesAfterParsingAndKeepsCdataLiteral() {
        String base = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
        assertEquals(List.of(base + "&dn=John's"), LinkExtractor.extract("<a href='" + base + "&dn=John&apos;s'>下载</a>"));
        assertEquals(List.of(base + "&dn=A&amp;B"), LinkExtractor.extract("<appmsg><url><![CDATA[" + base + "&dn=A&amp;B]]></url></appmsg>"));
        String dots = base + "&dn=name...";
        assertEquals(List.of(dots), LinkExtractor.extract("Download " + dots));
        assertEquals(List.of(dots), LinkExtractor.extract("<a href='" + dots + "'>下载</a>"));
    }

    @Test public void protocolTokensKeepFilenameQuotesAndLiteralEntitiesWithoutExtractingTheirTracker() {
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=John's&amp;B&tr=https://tracker.example/announce";
        String ed2k = "ed2k://|file|John's，A&amp;B.iso|123456|0123456789abcdef0123456789abcdef|/";
        assertEquals(List.of(magnet), LinkExtractor.extract(magnet));
        assertEquals(List.of(ed2k), LinkExtractor.extract(ed2k));
        assertEquals(List.of(magnet, ed2k), LinkExtractor.extract("下载：" + magnet + "\n另一个：" + ed2k));
    }

    @Test public void validDownloadNamesAndTrackerPunctuationAreNotTrimmed() {
        for (String suffix : List.of("&dn=name.", "&dn=name;", "&tr=https://tracker.example/announce?key=abc!")) {
            String link = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" + suffix;
            assertEquals(List.of(link), LinkExtractor.extract(link));
            assertEquals(List.of(link), LinkExtractor.extract("<a href='" + link + "'>下载</a>"));
        }
    }

    @Test public void downloadLinksAreRecognizedInTextAnchorsAndCanonicalCardsWithoutRewriting() {
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=one%2Btwo";
        String ed2k = "ed2k://|file|%E6%B5%8B%E8%AF%95.iso|123456|0123456789abcdef0123456789abcdef|/";
        assertEquals(List.of(magnet, ed2k), LinkExtractor.extract("磁力：" + magnet + "\n电驴：" + ed2k));
        assertEquals(List.of(magnet), LinkExtractor.extract("<a href='" + magnet + "'>查看链接</a>"));
        assertEquals(List.of(ed2k), LinkExtractor.extract("<appmsg><url>" + ed2k + "</url><title>标题</title></appmsg>"));
        assertTrue(LinkExtractor.extract("magnet:?dn=title-only ed2k://|server|127.0.0.1|4661|/").isEmpty());
    }

    @Test public void foldedFullTextRetainsHiddenLiteralLink() {
        String full = "这是一段很长的分享文字，折叠时只显示前面的介绍。\n展开后的原始全文：\nhttps://www.bilibili.com/video/BV1xx411c7mD?share_source=copy\n结束";
        assertEquals(Collections.singletonList("https://www.bilibili.com/video/BV1xx411c7mD?share_source=copy"), LinkExtractor.extract(full));
    }

    @Test public void visibleTitlesAndTruncationNeverInventDestinations() {
        assertTrue(LinkExtractor.extract("小米传送门增强教程…展开，网页链接").isEmpty());
        assertTrue(LinkExtractor.extract("https://www.example.com/very/long/…").isEmpty());
        assertTrue(LinkExtractor.extract("https://www.example.com/very/long/...").isEmpty());
    }

    @Test public void wechatAppmsgCanonicalUrlWinsOverThumbnailsAndTitle() {
        String xml = "<msg><appmsg appid=\"wx123\"><title>标题 https://title.example.com/</title>"
                + "<type>5</type><appattach><url>https://attachment.example.com/file</url></appattach>"
                + "<url>https://mp.weixin.qq.com/s?__biz=ABC&amp;mid=123&amp;idx=1</url>"
                + "<thumburl>https://thumb.example.com/a.jpg</thumburl></appmsg>"
                + "<url>https://status.example.com/</url></msg>";
        assertEquals(Collections.singletonList("https://mp.weixin.qq.com/s?__biz=ABC&mid=123&idx=1"), LinkExtractor.extract(xml));
    }

    @Test public void canonicalCdataUsesLiteralQuery() {
        String xml = "<msg><appmsg><url><![CDATA[https://example.com/share?a=1&b=two%2Bthree]]></url>"
                + "<thumburl>https://thumbnail.example.com/</thumburl></appmsg></msg>";
        assertEquals(Collections.singletonList("https://example.com/share?a=1&b=two%2Bthree"), LinkExtractor.extract(xml));
    }

    @Test public void escapedCardAndNumericAmpersandsDecodeOnce() {
        assertEquals(List.of("https://one.example/a", "https://two.example/b"),
                LinkExtractor.extract("https://one.example/a&nbsp;https://two.example/b"));
        String html = "<a href='https://example.com/path?a=1&#38;b=2&#x26;c=3&amp;d=4'>打开</a>";
        assertEquals(Collections.singletonList("https://example.com/path?a=1&b=2&c=3&d=4"), LinkExtractor.extract(html));
        assertEquals(Collections.singletonList("https://example.com/a"), LinkExtractor.extract("&lt;appmsg&gt;&lt;url&gt;https://example.com/a&lt;/url&gt;&lt;/appmsg&gt;"));
        assertEquals(Collections.singletonList("https://example.com/?a=%252F&amp;b=x"),
                LinkExtractor.extract("https://example.com/?a=%252F&amp;amp;b=x"));
    }

    @Test public void anchorsUseHrefRatherThanVisibleTitleOrJavascriptSubstrings() {
        assertEquals(Collections.singletonList("https://target.example.com/path"),
                LinkExtractor.extract("<a href=\"https://target.example.com/path\">https://title.example.com/</a>"));
        assertTrue(LinkExtractor.extract("<a href=\"javascript:open('https://evil.example.com/')\">打开</a>").isEmpty());
    }

    @Test public void weiboUrlTagWinsButTitleOnlyDoesNotGuess() {
        assertEquals(Collections.singletonList("https://weibo.com/123/ABC"), LinkExtractor.extract(
                "<card><title>微博文章 https://title.example.com/</title><url><![CDATA[https://weibo.com/123/ABC]]></url><thumb>https://img.example.com/x</thumb></card>"));
        assertTrue(LinkExtractor.extract("<card><title>微博文章 https://title.example.com/</title><thumburl>https://img.example.com/</thumburl></card>").isEmpty());
    }

    @Test public void miniappPathsAndBadCanonicalUrlsDoNotSelectUnrelatedFields() {
        assertTrue(LinkExtractor.extract("<appmsg><title>小程序</title><url>weixin://dl/business</url><weappinfo><pagepath>pages/index/index</pagepath></weappinfo><appattach><url>https://thumb.example.com/x</url></appattach></appmsg>").isEmpty());
        assertTrue(LinkExtractor.extract("<appmsg><weappinfo><pagepath>pages/index/index</pagepath></weappinfo></appmsg>").isEmpty());
    }

    @Test public void unsafeXmlDeclarationsAreRejectedWithoutFetchingEntities() {
        String xxe = "<!DOCTYPE msg [<!ENTITY xxe SYSTEM 'https://attacker.example.com/secret'>]><msg><appmsg><url>&xxe;</url></appmsg></msg>";
        assertTrue(LinkExtractor.extract(xxe).isEmpty());
        assertTrue(LinkExtractor.extract("&lt;!DOCTYPE msg SYSTEM 'https://attacker.example.com/'&gt;<url>https://example.com/</url>").isEmpty());
    }

    @Test public void surroundingPunctuationIsRemovedButBalancedUrlParenthesesRemain() {
        assertEquals(Arrays.asList("https://example.com/a_(b)", "https://example.org/path", "https://example.net/page"),
                LinkExtractor.extract("（https://example.com/a_(b)）。点击：https://example.org/path，另见 [https://example.net/page]。"));
        assertEquals(Collections.singletonList("https://example.com/a_(b)"), LinkExtractor.extract("https://example.com/a_(b))."));
    }

    @Test public void wwwIsNormalizedAndResultsAreOrderedDistinctAndImmutable() {
        List<String> links = LinkExtractor.extract("www.example.com/a https://example.org/b www.example.com/a");
        assertEquals(Arrays.asList("https://www.example.com/a", "https://example.org/b"), links);
        assertThrows(UnsupportedOperationException.class, () -> links.add("https://example.net"));
    }

    @Test public void malformedUserinfoAndNonWebUrlsAreRejected() {
        assertTrue(LinkExtractor.extract("https://name:pass@example.com/a https://example.com:70000/a https://example.com/%xx ftp://example.com/a").isEmpty());
        assertTrue(LinkExtractor.extract("https://example.com\\@evil.org/").isEmpty());
    }

    @Test public void inputUrlAndResultCapsAreEnforced() {
        assertTrue(LinkExtractor.extract("x".repeat(LinkExtractor.MAX_INPUT_LENGTH + 1)).isEmpty());
        assertTrue(LinkExtractor.extract("https://example.com/" + "x".repeat(LinkExtractor.MAX_URL_LENGTH)).isEmpty());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 80; i++) many.append("https://example.com/").append(i).append(' ');
        List<String> urls = LinkExtractor.extract(many.toString());
        assertEquals(64, urls.size());
        assertEquals("https://example.com/0", urls.get(0));
        assertEquals("https://example.com/63", urls.get(63));
        assertTrue(LinkExtractor.extract(null).isEmpty());
    }
}
