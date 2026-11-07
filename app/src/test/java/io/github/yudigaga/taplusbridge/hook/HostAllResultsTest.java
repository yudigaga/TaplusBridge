package io.github.yudigaga.taplusbridge.hook;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class HostAllResultsTest {
    private static final String LANDING = "https://www.bilibili.com/video/BV1xx411c7mD";

    public static final class AllJobsCatcher {
        public Object getParams(String job) {
            return "text_pick".equals(job) ? Map.of("pick_mode", Map.of("viewContent", "只有卡片标题")) : null;
        }
        public Object getPropertyMap() { return Map.of("coordinate", "12,24"); }
        public Object getResults() {
            return Map.of("other_job", Map.of("message", "<msg><appmsg><title>分享的视频</title>"
                    + "<url><![CDATA[" + LANDING + "]]></url>"
                    + "<thumburl>https://image.example/preview.png</thumburl></appmsg></msg>"));
        }
    }

    public static class LegacyCatcher {
        public Object getParams(String job) {
            return "text_pick".equals(job) ? Map.of("viewContent", "https://example.com/existing") : null;
        }
        public Object getPropertyMap() { return Map.of(); }
    }

    public static final class ThrowingResultsCatcher extends LegacyCatcher {
        public Object getResults() { throw new IllegalStateException("synthetic SDK failure"); }
    }

    public static class ResultsOnlyCatcher {
        final Map<String, Object> results;
        public ResultsOnlyCatcher(Map<String, Object> results) { this.results = results; }
        public Object getResults() { return results; }
    }

    public static final class ThrowingParamsCatcher extends ResultsOnlyCatcher {
        public ThrowingParamsCatcher() { super(Map.of("other_job", Map.of("url", LANDING))); }
        public Object getParams(String job) { throw new IllegalStateException("synthetic pick failure"); }
    }

    public static final class NonMapResultsCatcher extends LegacyCatcher {
        public Object getResults() {
            return new Object() {
                @Override public String toString() { throw new AssertionError("Arbitrary objects must not be inspected"); }
            };
        }
    }

    private HostRecognitionEnhancer enhancer() throws Exception {
        return new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
    }

    @Test
    public void anotherJobCanExposeLandingUrlWhenTextPickHasOnlyTitle() throws Exception {
        HostRecognitionEnhancer enhancer = enhancer();
        enhancer.capture(new AllJobsCatcher());
        assertEquals(List.of(LANDING), enhancer.capturedLinks());
    }

    @Test
    public void optionalResultsGetterMissingPreservesLegacyText() throws Exception {
        HostRecognitionEnhancer enhancer = enhancer();
        enhancer.capture(new LegacyCatcher());
        assertEquals(List.of("https://example.com/existing"), enhancer.capturedLinks());
    }

    @Test
    public void optionalResultsGetterThrowingPreservesLegacyText() throws Exception {
        HostRecognitionEnhancer enhancer = enhancer();
        enhancer.capture(new ThrowingResultsCatcher());
        assertEquals(List.of("https://example.com/existing"), enhancer.capturedLinks());
    }

    @Test
    public void resultsAreReadEvenWhenParamsGetterIsMissing() throws Exception {
        HostRecognitionEnhancer enhancer = enhancer();
        enhancer.capture(new ResultsOnlyCatcher(Map.of("other_job", Map.of("url", LANDING))));
        assertEquals(List.of(LANDING), enhancer.capturedLinks());
    }

    @Test
    public void resultsAreReadEvenWhenParamsGetterThrows() throws Exception {
        HostRecognitionEnhancer enhancer = enhancer();
        enhancer.capture(new ThrowingParamsCatcher());
        assertEquals(List.of(LANDING), enhancer.capturedLinks());
    }

    @Test
    public void duplicateJobUrlsAreDeduplicatedAndImageMetadataIsExcluded() throws Exception {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("text_pick", Map.of("viewContent", LANDING, "thumburl", "https://images.example/thumb.png"));
        results.put("other_job", Map.of("url", LANDING, "imgUrl", "https://images.example/source.png",
                "sourceicon", "https://images.example/icon.png"));
        results.put("image_pick", Map.of("coverurl", "https://images.example/cover.png"));
        HostRecognitionEnhancer enhancer = enhancer();
        enhancer.capture(new ResultsOnlyCatcher(results));
        assertEquals(List.of(LANDING), enhancer.capturedLinks());
    }

    @Test
    public void nonMapResultsAreNotInspectedAsApplicationObjects() throws Exception {
        HostRecognitionEnhancer enhancer = enhancer();
        enhancer.capture(new NonMapResultsCatcher());
        assertEquals(List.of("https://example.com/existing"), enhancer.capturedLinks());
    }

    @Test
    public void allJobScanKeepsAvailableLinksWithinTheBoundAndStopsCyclicContainers() throws Exception {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("cycle", results);
        for (int i = 0; i < 30; i++) results.put("job_" + i, Map.of("url", "https://example.com/" + i));
        HostRecognitionEnhancer enhancer = enhancer();
        enhancer.capture(new ResultsOnlyCatcher(results));
        assertEquals(30, enhancer.capturedLinks().size());
        assertEquals("https://example.com/0", enhancer.capturedLinks().get(0));
        assertTrue(enhancer.capturedLinks().contains("https://example.com/7"));
    }
}
