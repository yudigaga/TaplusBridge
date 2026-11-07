package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.URLSpan;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class HostRecognitionAndroidPayloadTest {
    private HostRecognitionEnhancer enhancer() throws Exception {
        return new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
    }

    @Test public void invisibleLinkSpanKeepsTheActualDestination() throws Exception {
        HostRecognitionEnhancer enhancer = enhancer();
        SpannableString title = new SpannableString("哔哩哔哩视频卡片");
        title.setSpan(new URLSpan("https://www.bilibili.com/video/BV123"), 0, title.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        enhancer.capturePayload(title);
        assertEquals(List.of("https://www.bilibili.com/video/BV123"), enhancer.capturedLinks());
    }

    @Test public void uriMetadataIsRecognizedWithoutWalkingApplicationObjects() throws Exception {
        HostRecognitionEnhancer enhancer = enhancer();
        Bundle metadata = new Bundle();
        metadata.putParcelable("targetUrl", Uri.parse("https://example.com/shared"));
        metadata.putString("thumburl", "https://images.example/preview.png");
        enhancer.capturePayload(metadata);
        assertEquals(List.of("https://example.com/shared"), enhancer.capturedLinks());
    }

    @Test public void documentedIntentDataAndRichTextExtrasAreBothCaptured() throws Exception {
        HostRecognitionEnhancer enhancer = enhancer();
        Intent payload = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/landing"));
        payload.putExtra(Intent.EXTRA_TEXT, "完整内容 https://example.org/detail");
        enhancer.capturePayload(payload);
        assertEquals(List.of("https://example.com/landing", "https://example.org/detail"), enhancer.capturedLinks());
    }

    @Test public void unexposedTitleAndArbitraryObjectDoNotInventUrls() throws Exception {
        HostRecognitionEnhancer enhancer = enhancer();
        Object applicationObject = new Object() {
            @Override public String toString() { return "https://example.com/unexposed"; }
        };
        enhancer.capturePayload(applicationObject);
        enhancer.captureText("没有暴露链接的分享卡片标题");
        assertTrue(enhancer.capturedLinks().isEmpty());
    }
}
