package io.github.yudigaga.taplusbridge.hook;

import android.content.Intent;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.URLSpan;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class HostCatcherLinksAndroidTest {
    @Test public void intentUriBundleAndInvisibleSpanLinksMatchRecognitionCapture() throws Exception {
        SpannableString title = new SpannableString("只有卡片标题");
        title.setSpan(new URLSpan("https://example.com/span"), 0, title.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Intent payload = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/intent"));
        payload.putExtra(Intent.EXTRA_TEXT, title);
        payload.putExtra("thumburl", "https://images.example/thumb.png");
        Object catcher = new HostCatcherLinksTest.Catcher(null, null, Map.of("payload", payload), null);
        HostCatcherLinksTest.assertSharedLinks(catcher,
                List.of("https://example.com/intent", "https://example.com/span"));
    }
}
