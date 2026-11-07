package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class HostCatcherLinksTest {
    public static final class Catcher {
        final Object text;
        final Object image;
        final Object properties;
        final Object results;

        Catcher(Object text, Object image, Object properties, Object results) {
            this.text = text;
            this.image = image;
            this.properties = properties;
            this.results = results;
        }

        public Object getParams(String name) {
            return "text_pick".equals(name) ? text : "image_pick".equals(name) ? image : null;
        }
        public Object getPropertyMap() { return properties; }
        public Object getResults() { return results; }
    }

    public static final class ResultsOnlyCatcher {
        final Object results;
        ResultsOnlyCatcher(Object results) { this.results = results; }
        public Object getResults() { return results; }
    }

    @Test public void propertyOnlyLandingUrlMatchesRecognitionCapture() throws Exception {
        Object catcher = new Catcher(null, null,
                Map.of("message", Map.of("landing", "https://example.com/property")), null);
        assertSharedLinks(catcher, List.of("https://example.com/property"));
    }

    @Test public void anotherJobResultsOnlyUrlMatchesRecognitionWithoutParamsGetter() throws Exception {
        Object catcher = new ResultsOnlyCatcher(Map.of("other_job", Map.of("message",
                "<msg><appmsg><title>分享标题</title><url>https://example.com/other-job</url>"
                        + "<thumburl>https://images.example/thumb.png</thumburl></appmsg></msg>")));
        assertSharedLinks(catcher, List.of("https://example.com/other-job"));
    }

    @Test public void thumbnailKeysAndCyclicContainersNeverBecomeDestinations() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("self", properties);
        properties.put("thumburl", "https://images.example/thumb.png");
        properties.put("sourceicon", List.of("https://images.example/icon.png"));
        properties.put("payload", List.of("https://example.com/shared", properties));
        assertSharedLinks(new Catcher(null, null, properties, null),
                List.of("https://example.com/shared"));
    }

    @Test public void sourceOrderDeduplicationAndSixtyFourLinkCapMatchRecognition() throws Exception {
        List<String> otherJobLinks = new ArrayList<>();
        for (int i = 3; i < 80; i++) otherJobLinks.add("https://example.com/" + i);
        Object catcher = new Catcher(Map.of("text", "https://example.com/0"),
                Map.of("imageLink", "https://example.com/1"),
                List.of("https://example.com/0", "https://example.com/2"),
                Map.of("other_job", otherJobLinks));
        assertSharedLinks(catcher, java.util.stream.IntStream.range(0, 64)
                .mapToObj(i -> "https://example.com/" + i).toList());
    }

    @Test public void metadataDepthLimitMatchesRecognition() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("accepted", nested("https://example.com/depth-five", 4));
        properties.put("tooDeep", nested("https://example.com/depth-six", 5));
        assertSharedLinks(new Catcher(null, null, properties, null),
                List.of("https://example.com/depth-five"));
    }

    @Test public void metadataNodeBudgetMatchesRecognition() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("padding", java.util.Collections.nCopies(125, "无链接"));
        properties.put("lastAccepted", "https://example.com/node-128");
        properties.put("pastBudget", "https://example.com/node-129");
        assertSharedLinks(new Catcher(null, null, properties, null),
                List.of("https://example.com/node-128"));
    }

    @Test public void arbitraryApplicationObjectsAndNonMapResultsAreNotInspected() throws Exception {
        Object applicationObject = new Object() {
            public final String url = "https://example.com/private-field";
            public String getUrl() { throw new AssertionError("No arbitrary getter calls"); }
            @Override public String toString() { throw new AssertionError("No arbitrary stringification"); }
        };
        assertSharedLinks(new Catcher(null, null, Map.of("message", applicationObject), applicationObject),
                List.of());
    }

    static List<String> catcherLinks(Object catcher) throws Exception {
        final Method method;
        try {
            method = HostRecognitionEnhancer.class.getDeclaredMethod("catcherLinks", Object.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("Shared metadata scanner entry point is missing", missing);
        }
        method.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> result = (List<String>) method.invoke(null, catcher);
        return result;
    }

    static void assertSharedLinks(Object catcher, List<String> expected) throws Exception {
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
        enhancer.capture(catcher);
        assertEquals(expected, enhancer.capturedLinks());
        assertEquals(expected, catcherLinks(catcher));
    }

    private static Object nested(Object value, int containers) {
        for (int i = 0; i < containers; i++) value = List.of(value);
        return value;
    }
}
