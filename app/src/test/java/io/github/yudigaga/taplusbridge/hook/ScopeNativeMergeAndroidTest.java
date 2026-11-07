package io.github.yudigaga.taplusbridge.hook;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class ScopeNativeMergeAndroidTest {
    private static final String SOURCE = "com.example.source", IDENTITY = "configuration_identity_1";

    @Test public void matchingTextAndImageAreTakenTogetherOnlyOnceAtInclusiveGestureBounds() {
        var pending = new HostScopeCaptureHooks.PendingNative();
        Object text = new Object(), image = new Object();
        pending.hold(false, SOURCE, 1_000, IDENTITY, text);
        pending.hold(true, SOURCE, 1_500, IDENTITY, image);
        var picks = pending.take(SOURCE, IDENTITY, 1_000, 1_500, 1_500);
        assertSame(text, picks.text()); assertSame(image, picks.image());
        assertEmpty(pending.take(SOURCE, IDENTITY, 1_000, 1_500, 1_500));
    }

    @Test public void otherSourcesIdentitiesAndOldGestureIntervalsCannotConsumeNewPicks() {
        var pending = new HostScopeCaptureHooks.PendingNative();
        Object text = new Object(), image = new Object();
        pending.hold(false, SOURCE, 2_000, IDENTITY, text);
        pending.hold(true, SOURCE, 2_100, IDENTITY, image);
        assertEmpty(pending.take("com.example.other", IDENTITY, 2_000, 2_200, 2_200));
        assertEmpty(pending.take(SOURCE, "different_configuration", 2_000, 2_200, 2_200));
        assertEmpty(pending.take(SOURCE, IDENTITY, 1_000, 1_500, 2_200));
        var picks = pending.take(SOURCE, IDENTITY, 2_000, 2_200, 2_200);
        assertSame(text, picks.text()); assertSame(image, picks.image());
    }

    @Test public void aNewGestureCannotTakeAnOldPickAndAnOldCompletionCannotDiscardItsReplacement() {
        var pending = new HostScopeCaptureHooks.PendingNative();
        pending.hold(false, SOURCE, 1_000, IDENTITY, new Object());
        assertEmpty(pending.take(SOURCE, IDENTITY, 2_000, 2_500, 2_500));
        Object currentText = new Object();
        pending.hold(false, SOURCE, 2_100, IDENTITY, currentText);
        assertEmpty(pending.take(SOURCE, IDENTITY, 900, 1_500, 2_500));
        assertSame(currentText, pending.take(SOURCE, IDENTITY, 2_000, 2_500, 2_500).text());
    }

    @Test public void heldContentExpiresAtThirtySecondsAndEachKindHasItsOwnAge() {
        var before = new HostScopeCaptureHooks.PendingNative();
        Object text = new Object(); before.hold(false, SOURCE, 1_000, IDENTITY, text);
        assertSame(text, before.take(SOURCE, IDENTITY, 1_000, 1_500, 30_999).text());
        var boundary = new HostScopeCaptureHooks.PendingNative();
        Object image = new Object();
        boundary.hold(false, SOURCE, 1_000, IDENTITY, new Object());
        boundary.hold(true, SOURCE, 1_001, IDENTITY, image);
        var picks = boundary.take(SOURCE, IDENTITY, 1_000, 1_500, 31_000);
        assertNull(picks.text()); assertSame(image, picks.image());
        var expired = new HostScopeCaptureHooks.PendingNative();
        expired.hold(true, SOURCE, 1_000, IDENTITY, new Object());
        assertEmpty(expired.take(SOURCE, IDENTITY, 1_000, 1_500, 31_000));
    }

    @Test public void oldExpirationCallbacksDoNotClearNewPicksOfEitherKind() {
        var pending = new HostScopeCaptureHooks.PendingNative();
        var oldText = pending.hold(false, SOURCE, 1_000, IDENTITY, new Object());
        var oldImage = pending.hold(true, SOURCE, 1_010, IDENTITY, new Object());
        Object text = new Object(), image = new Object();
        pending.hold(false, SOURCE, 2_000, IDENTITY, text);
        pending.hold(true, SOURCE, 2_010, IDENTITY, image);
        pending.expire(oldText); pending.expire(oldImage);
        var picks = pending.take(SOURCE, IDENTITY, 2_000, 2_100, 2_100);
        assertSame(text, picks.text()); assertSame(image, picks.image());
    }

    @Test public void duplicateHoldsKeepOnlyTheNewestTextAndImageIndependently() {
        var pending = new HostScopeCaptureHooks.PendingNative();
        pending.hold(false, SOURCE, 1_000, IDENTITY, new Object());
        pending.hold(true, SOURCE, 1_010, IDENTITY, new Object());
        Object text = new Object(), image = new Object();
        pending.hold(false, SOURCE, 1_020, IDENTITY, text);
        pending.hold(true, SOURCE, 1_030, IDENTITY, image);
        var picks = pending.take(SOURCE, IDENTITY, 1_000, 1_100, 1_100);
        assertSame(text, picks.text()); assertSame(image, picks.image());
    }

    @Test public void changingSourceOrConfigurationDoesNotJoinTheOtherKindsOlderContent() {
        var pending = new HostScopeCaptureHooks.PendingNative();
        pending.hold(false, SOURCE, 1_000, IDENTITY, new Object());
        Object image = new Object();
        pending.hold(true, "com.example.other", 1_100, IDENTITY, image);
        assertEmpty(pending.take(SOURCE, IDENTITY, 1_000, 1_500, 1_500));
        var other = pending.take("com.example.other", IDENTITY, 1_000, 1_500, 1_500);
        assertNull(other.text()); assertSame(image, other.image());
        pending.hold(true, SOURCE, 2_000, IDENTITY, new Object());
        Object text = new Object();
        pending.hold(false, SOURCE, 2_100, "new_configuration", text);
        assertEmpty(pending.take(SOURCE, IDENTITY, 2_000, 2_500, 2_500));
        var newer = pending.take(SOURCE, "new_configuration", 2_000, 2_500, 2_500);
        assertSame(text, newer.text()); assertNull(newer.image());
    }

    @Test public void nativeCharSequenceRetainsTheWholeOriginalBody() throws Exception {
        String body = "查看源代码\n原生正文 https://example.com/in-body";
        assertEquals(body, HostScopeCaptureHooks.nativeText(new NativeResult(new StringBuilder(body))));
    }

    @Test public void viewContentRetainsOriginalTextAlongsideTheSeparateProbeUrl() throws Exception {
        Bundle text = new Bundle(); text.putString("viewContent", "加入社区，保留这段说明");
        text.putString(ScopeCapturePayload.LINK_URL_KEY, "https://example.com/community");
        assertEquals("加入社区，保留这段说明", HostScopeCaptureHooks.nativeText(new NativeResult(text)));
    }

    @Test public void richCardCharSequenceIsKeptAsOriginalText() throws Exception {
        Bundle text = new Bundle(); text.putCharSequence("viewContent", new android.text.SpannableString("富内容卡片标题"));
        assertEquals("富内容卡片标题", HostScopeCaptureHooks.nativeText(new NativeResult(text)));
    }

    @Test public void apkParamListReadsOnlyTheBoundedNativeTextKeys() throws Exception {
        Bundle text = new Bundle();
        text.putStringArray("apkParamList", new String[]{null, "unrelated", "miuiCE_", "miuiCE_body"});
        text.putCharSequence("body", new StringBuilder("原生的卡片标题和正文"));
        text.putString("unrelated", "https://example.com/unused");
        assertEquals("原生的卡片标题和正文", HostScopeCaptureHooks.nativeText(new NativeResult(text)));
        String[] beyondLimit = new String[129]; beyondLimit[128] = "miuiCE_ignored";
        text.putStringArray("apkParamList", beyondLimit); text.putString("ignored", "不应读取");
        assertNull(HostScopeCaptureHooks.nativeText(new NativeResult(text)));
    }

    @Test public void originalTextIsCappedAtOneHundredThirtyOneThousandSeventyTwoCharacters() throws Exception {
        String body = "文".repeat(131_072), tooLong = body + "超出的尾部";
        assertEquals(body, HostScopeCaptureHooks.nativeText(new NativeResult(new StringBuilder(tooLong))));
        Bundle text = new Bundle(); text.putString("viewContent", tooLong);
        assertEquals(body, HostScopeCaptureHooks.nativeText(new NativeResult(text)));
        assertEquals(body, HostScopeCaptureHooks.nativeText(new NativeResult(body)));
    }

    @Test public void missingOrEmptyNativeTextDoesNotInventContent() throws Exception {
        assertNull(HostScopeCaptureHooks.nativeText(null));
        assertNull(HostScopeCaptureHooks.nativeText(new NativeResult(null)));
        assertNull(HostScopeCaptureHooks.nativeText(new NativeResult("")));
        assertNull(HostScopeCaptureHooks.nativeText(new NativeResult(new Bundle())));
    }

    @Test public void recommendationAddsTheSeparateUrlAfterTheEntireOriginalBody() {
        String body = "查看源代码\n保留原来的说明", url = "https://example.com/source";
        assertEquals(body + "\n" + url, RecognitionHooks.recommendationText(body, url));
    }

    @Test public void recommendationDoesNotAppendTheSameUrlTwice() {
        String url = "https://example.com/community", body = "加入社区 " + url;
        assertEquals(body, RecognitionHooks.recommendationText(body, url));
        assertEquals(url, RecognitionHooks.recommendationText(url, url));
    }

    @Test public void recommendationRejectsAnInvalidUrlAndUsesAValidUrlWhenTheBodyIsMissing() {
        String body = "这段正文需要保留", url = "https://example.com/source";
        for (String invalid : new String[]{null, "", "source title", "javascript:alert(1)", "https://example.com/has space"})
            assertEquals(body, RecognitionHooks.recommendationText(body, invalid));
        assertEquals(url, RecognitionHooks.recommendationText(null, url));
        assertEquals(url, RecognitionHooks.recommendationText("", url));
    }

    @Test public void aCombinedNativeResultHeldAsTextKeepsItsImageAndBodyWithoutTextModeTakingPrecedence() throws Exception {
        CombinedNativeResult original = combinedNativeResult("原生完整正文，不被链接替换");
        Bundle image = (Bundle) original.getParams("image_pick").get("image_pick_mode");
        var contents = HostScopeCaptureHooks.nativeContents(new HostScopeCaptureHooks.PendingNative.Picks(original, null));
        assertEquals("原生完整正文，不被链接替换", contents.text());
        assertTrue(contents.image() instanceof CombinedNativeResult);
        CombinedNativeResult result = (CombinedNativeResult) contents.image();
        assertNotSame(original, result); assertNull(result.getParams("text_pick"));
        assertSame(image, result.getParams("image_pick").get("image_pick_mode"));
        assertEquals("native-image-feature", result.getParams("image_pick").get("extra_feature"));
        assertSame(original.getInjectorToken(), result.getInjectorToken());
        assertEquals(original.getPropertyMap(), result.getPropertyMap());
        assertNotSame(original.getPropertyMap(), result.getPropertyMap());
        assertNotNull(original.getParams("text_pick"));
    }

    @Test public void aCombinedNativeResultHeldAsImageStillPreservesItsOriginalText() throws Exception {
        CombinedNativeResult original = combinedNativeResult("图片回调也携带的原生文字");
        var contents = HostScopeCaptureHooks.nativeContents(new HostScopeCaptureHooks.PendingNative.Picks(null, original));
        assertEquals("图片回调也携带的原生文字", contents.text());
        CombinedNativeResult image = (CombinedNativeResult) contents.image();
        assertNotSame(original, image); assertNull(image.getParams("text_pick"));
        assertSame(original.getParams("image_pick").get("image_pick_mode"), image.getParams("image_pick").get("image_pick_mode"));
        assertSame(original.getInjectorToken(), image.getInjectorToken());
    }

    @Test public void onlyActualProbeUrlCardsSurviveALaterOcrRecommendationResponse() {
        var saved = new RecognitionHooks.ProbeRecommendations();
        Recommendation probe = new Recommendation("https://example.com/probe", "com.example.browser", "open-probe", "WEB");
        Recommendation oldNative = new Recommendation("https://example.com/old-native", "com.example.native", "open-old", "WEB");
        Recommendation ocr = new Recommendation("https://example.com/ocr", "com.example.native", "open-ocr", "WEB");
        assertEquals(List.of(probe, oldNative), saved.merge(new ArrayList<>(List.of(probe, oldNative)), value -> value == probe));
        var afterOcr = saved.merge(new ArrayList<>(List.of(ocr)), value -> false);
        assertEquals(List.of(ocr, probe), afterOcr);
        assertFalse(afterOcr.contains(oldNative));
    }

    @Test public void semanticCopiesOfTheSameNativeProbeCardAreDeduplicatedAcrossResponses() {
        var saved = new RecognitionHooks.ProbeRecommendations();
        Recommendation original = new Recommendation("https://example.com/probe", "com.example.browser", "open-probe", "WEB");
        Recommendation copy = new Recommendation("https://example.com/probe", "com.example.browser", "open-probe", "WEB");
        assertEquals(1, saved.merge(new ArrayList<>(List.of(original, copy)), value -> true).size());
        var later = saved.merge(new ArrayList<>(List.of(copy)), value -> true);
        assertEquals(1, later.size()); assertSame(copy, later.get(0));
    }

    @Test public void clearingThePickRemovesSavedProbeRecommendations() {
        var saved = new RecognitionHooks.ProbeRecommendations();
        Recommendation probe = new Recommendation("https://example.com/probe", "com.example.browser", "open-probe", "WEB");
        saved.merge(new ArrayList<>(List.of(probe)), value -> true);
        saved.clear();
        assertTrue(saved.merge(new ArrayList<>(), value -> false).isEmpty());
    }

    private static CombinedNativeResult combinedNativeResult(String body) {
        CombinedNativeResult result = new CombinedNativeResult();
        Bundle text = new Bundle(); text.putString("viewContent", body);
        Bundle image = new Bundle(); image.putString("imgUrl", "https://example.com/native-image.png");
        result.addContent("text_pick", "pick_mode", text);
        result.addContent("image_pick", "image_pick_mode", image);
        result.addContent("image_pick", "extra_feature", "native-image-feature");
        result.setInjectorToken(new Object()); result.getPropertyMap().put("coordinate", "120.0-500.0");
        result.getPropertyMap().put("isfullscreen", "0");
        return result;
    }

    private static void assertEmpty(HostScopeCaptureHooks.PendingNative.Picks picks) {
        assertNull(picks.text()); assertNull(picks.image());
    }

    public static final class NativeResult {
        private final Object value;
        public NativeResult(Object value) { this.value = value; }
        public Map<String, Object> getParams(String job) {
            if (!"text_pick".equals(job)) return null;
            Map<String, Object> params = new HashMap<>(); params.put("pick_mode", value); return params;
        }
    }

    public static final class CombinedNativeResult {
        private final Map<String, Map<String, Object>> jobs = new HashMap<>();
        private final Map<String, Object> properties = new HashMap<>();
        private Object token;
        public CombinedNativeResult() {}
        public Map<String, Object> getParams(String job) { return jobs.get(job); }
        public Object getInjectorToken() { return token; }
        public void setInjectorToken(Object value) { token = value; }
        public Map<String, Object> getPropertyMap() { return properties; }
        public void addContent(String job, String feature, Object value) {
            jobs.computeIfAbsent(job, ignored -> new HashMap<>()).put(feature, value);
        }
    }

    public static final class Recommendation {
        private final String detailUrl, packageName, intent, category;
        public Recommendation(String detailUrl, String packageName, String intent, String category) {
            this.detailUrl = detailUrl; this.packageName = packageName; this.intent = intent; this.category = category;
        }
        public String getDetailUrl() { return detailUrl; }
        public String getPackageName() { return packageName; }
        public String getIntent() { return intent; }
        public String getCategory() { return category; }
    }
}
