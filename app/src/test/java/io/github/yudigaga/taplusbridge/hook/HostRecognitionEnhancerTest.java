package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class HostRecognitionEnhancerTest {
    public static final class FakeModel {
        private String title;
        private String cat;
        private String detailUrl;
        private String packageName;
        private String intent;
    }
    public static final class FakeCatcher {
        public Object getParams(String name) {
            if (!"text_pick".equals(name)) return null;
            return Map.of("pick_mode", Map.of("viewContent", "分享标题", "miuiCE_message",
                    "<msg><appmsg><title>卡片标题</title><url>https://www.bilibili.com/video/BV123</url>"
                            + "<thumburl>https://image.example/preview.png</thumburl></appmsg></msg>"));
        }
        public Object getPropertyMap() { return Map.of("coordinate", "12,24"); }
    }
    private static class ParentCard { private String mRecommendTitle = "parent"; }
    private static final class ChildCard extends ParentCard { }

    @Test public void captureUsesCardPayloadDestinationInsteadOfThumbnailOrTitle() throws Exception {
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(FakeModel.class);
        enhancer.capture(new FakeCatcher());
        assertEquals(List.of("https://www.bilibili.com/video/BV123"), enhancer.capturedLinks());
    }

    @Test public void foldedTextAndStructuredMetadataMergeWithoutDuplicateUrls() throws Exception {
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(FakeModel.class);
        enhancer.captureText("折叠之前 https://example.com/a 后续完整内容");
        enhancer.capturePayload(Map.of("viewContent", "https://example.com/a",
                "fullText", "继续 https://example.org/b", "thumburl", "https://images.example/x.png"));
        assertTrue(enhancer.capturedLinks().contains("https://example.com/a"));
        assertTrue(enhancer.capturedLinks().contains("https://example.org/b"));
        assertEquals(2, enhancer.capturedLinks().size());
    }

    @Test public void metadataTraversalHandlesCyclesAndClearRemovesPriorSession() throws Exception {
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(FakeModel.class);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("self", map);
        map.put("text", "https://example.com/new");
        enhancer.capturePayload(map);
        assertEquals(1, enhancer.capturedLinks().size());
        enhancer.clear();
        assertTrue(enhancer.capturedLinks().isEmpty());
    }

    @Test public void injectedModelUsesActualResolvedTargetAndKeepsRawUrl() throws Exception {
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(FakeModel.class);
        FakeModel model = (FakeModel) enhancer.newModel("https://www.bilibili.com/video/BV123",
                "tv.danmaku.bili", "哔哩哔哩");
        assertEquals("在哔哩哔哩打开", model.title);
        assertEquals("URL", model.cat);
        assertEquals("tv.danmaku.bili", model.packageName);
        assertEquals("https://www.bilibili.com/video/BV123", model.detailUrl);
        assertNull(model.intent);
    }

    @Test public void unresolvedContextPreservesAllHostModelsWithoutGuessingApp() throws Exception {
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(FakeModel.class);
        enhancer.captureText("https://example.com/link");
        ArrayList<Object> original = new ArrayList<>();
        Object originalModel = new FakeModel();
        original.add(originalModel);
        ArrayList<Object> merged = enhancer.merge(null, original, "com.example.reader");
        assertNotSame(original, merged);
        assertEquals(original, merged);
        assertFalse(merged.isEmpty());
    }

    @Test public void cardFieldsAreFoundInTheirHostSuperclass() throws Exception {
        assertEquals("parent", HostRecognitionEnhancer.findField(ChildCard.class, "mRecommendTitle")
                .get(new ChildCard()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void injectedModelRejectsTitleOnlyAndInventedDestination() throws Exception {
        new HostRecognitionEnhancer(FakeModel.class).newModel("某条分享标题", "tv.danmaku.bili", "哔哩哔哩");
    }

    @Test public void diagnosticReporterNeverIncludesTextUrlsTokensOrUntrustedReasons() throws Exception {
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(FakeModel.class);
        List<String> events = new ArrayList<>();
        enhancer.setReporter(events::add);
        enhancer.captureText("私密原文 https://example.com/secret?token=private-token-value");
        enhancer.newModel("https://example.com/secret?token=private-token-value", "com.example.target",
                "仅用于界面的应用标签");
        enhancer.diagnostic("resolve", "none", "https://private.example/secret", 9999999, -20,
                IllegalArgumentException.class, "private-token-value");
        enhancer.diagnostic("https://private.example/untrusted-stage", "success", null, 0, 0, null, null);
        String combined = String.join("\n", events);
        assertFalse(combined.contains("私密原文"));
        assertFalse(combined.contains("https://"));
        assertFalse(combined.contains("secret"));
        assertFalse(combined.contains("private-token-value"));
        assertFalse(combined.contains("仅用于界面的应用标签"));
        assertTrue(combined.contains("reason=unknown"));
        assertTrue(combined.contains("exception=java.lang.IllegalArgumentException"));
        assertTrue(combined.contains("input=131072 output=0"));
    }

    @Test public void diagnosticsAreDeduplicatedAndCappedPerSession() throws Exception {
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(FakeModel.class);
        List<String> events = new ArrayList<>();
        enhancer.setReporter(events::add);
        enhancer.diagnostic("resolve", "success", "com.example.target0", 0, 0, null, null);
        enhancer.diagnostic("resolve", "success", "com.example.target0", 20, 30, null, null);
        assertEquals(1, events.size());
        for (int i = 0; i < 100; i++) enhancer.diagnostic("resolve", "success",
                "com.example.target" + i, 0, 0, null, null);
        assertEquals(64, events.size());
        enhancer.diagnostic("resolve", "success", "com.example.target0", 20, 30, null, null);
        assertEquals(64, events.size());
        enhancer.clear();
        enhancer.diagnostic("resolve", "success", "com.example.target0", 0, 0, null, null);
        assertEquals(65, events.size());
    }

    @Test public void brokenOptionalReporterCannotInterruptRecognition() throws Exception {
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(FakeModel.class);
        enhancer.setReporter(event -> { throw new IllegalStateException("reporter failed"); });
        enhancer.captureText("https://example.com/valid");
        assertEquals(List.of("https://example.com/valid"), enhancer.capturedLinks());
        enhancer.setReporter(null);
        enhancer.captureText("https://example.org/second");
        assertEquals(2, enhancer.capturedLinks().size());
    }

    @Test public void pickToUrlTimingContainsNoRecognizedText() throws Exception {
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(FakeModel.class);
        List<String> events = new ArrayList<>();
        enhancer.setReporter(events::add);
        enhancer.beginPick();
        enhancer.captureText("private message https://example.com/private-path?secret=value");
        String timing = events.stream().filter(event -> event.startsWith("recognition perf stage=pick_to_url "))
                .findFirst().orElseThrow();
        assertTrue(timing.matches("recognition perf stage=pick_to_url elapsed_us=\\d+ outcome=success"));
        assertFalse(timing.contains("example.com"));
        assertFalse(timing.contains("private-path"));
    }
}
