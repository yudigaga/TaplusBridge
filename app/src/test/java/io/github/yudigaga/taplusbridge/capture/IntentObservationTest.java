package io.github.yudigaga.taplusbridge.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class IntentObservationTest {
    private static final String WECHAT = "com.tencent.mm";
    private static final String WEBVIEW = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI";
    private static final String MM_WEBVIEW = "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI";

    private Intent supported() { return new Intent().setComponent(new ComponentName(WECHAT, WEBVIEW)); }

    @Test public void dataUrlReturnsOnlyMetadataAndDoesNotMutateOriginal() {
        Intent request = supported().setData(Uri.parse("https://example.com/private-path?token=private-token"))
                .putExtra("password", "private-password");
        String before = request.toUri(0);
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(IntentObservation.Outcome.URL_FOUND, observation.outcome());
        assertTrue(observation.urlFound());
        assertEquals(1, observation.urlCount());
        assertEquals(IntentObservation.UrlOrigin.DATA, observation.urlOrigin());
        assertEquals(IntentObservation.DATA_PRESENT, observation.fieldPresence());
        assertEquals(WECHAT, observation.componentPackage());
        assertEquals(WEBVIEW, observation.componentClass());
        assertEquals(before, request.toUri(0));
        String diagnostic = observation.toString();
        assertFalse(diagnostic.contains("https://"));
        assertFalse(diagnostic.contains("example.com"));
        assertFalse(diagnostic.contains("private-path"));
        assertFalse(diagnostic.contains("private-token"));
        assertFalse(diagnostic.contains("private-password"));
    }

    @Test public void explicitComponentWhitelistIncludesBothWechatCarriersAndQq() {
        for (String activity : List.of(WEBVIEW, MM_WEBVIEW)) {
            assertTrue(IntentObservation.isSupportedComponent(new ComponentName(WECHAT, activity)));
        }
        assertTrue(IntentObservation.isSupportedComponent(new ComponentName("com.tencent.mobileqq",
                "com.tencent.mobileqq.activity.QQBrowserActivity")));
        assertFalse(IntentObservation.isSupportedComponent(new ComponentName("com.example.fake", WEBVIEW)));
        assertFalse(IntentObservation.isSupportedComponent(new ComponentName(WECHAT, "com.tencent.mm.AppBrandUI")));
        assertFalse(IntentObservation.isSupportedComponent(null));
    }

    @Test public void unsupportedComponentsNeverReadTheirPayload() {
        Intent request = new Intent() {
            @Override public Uri getData() { throw new AssertionError("Unsupported payload read"); }
            @Override public Bundle getExtras() { throw new AssertionError("Unsupported payload read"); }
        };
        request.setComponent(new ComponentName("untrusted.example", "https://secret.example/private"));
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(IntentObservation.Outcome.UNSUPPORTED_COMPONENT, observation.outcome());
        assertNull(observation.componentPackage());
        assertNull(observation.componentClass());
        assertFalse(observation.toString().contains("secret"));
        assertEquals(IntentObservation.Outcome.UNSUPPORTED_COMPONENT, IntentObservation.inspect(null).outcome());
    }

    @Test public void whitelistedFieldPresenceDoesNotIncludeOtherKeysOrValues() {
        Intent request = supported().putExtra("rawUrl", "https://example.com/a")
                .putExtra("url", "https://example.com/b")
                .putExtra(Intent.EXTRA_TEXT, "https://example.com/c")
                .putExtra("access_token", "https://do-not-read.example/secret");
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(3, observation.urlCount());
        assertEquals(IntentObservation.UrlOrigin.RAW_URL, observation.urlOrigin());
        assertEquals(IntentObservation.RAW_URL_PRESENT | IntentObservation.URL_PRESENT
                | IntentObservation.TEXT_PRESENT, observation.fieldPresence());
        assertFalse(observation.toString().contains("access_token"));
        assertFalse(observation.toString().contains("do-not-read"));
    }

    @Test public void arbitraryTextAndPrivateIdsAreNotReinterpretedAsUrls() {
        Intent request = supported().putExtra("rawUrl", "wx-private-card-id-123")
                .putExtra("url", "weixin://dl/business/?id=123")
                .putExtra(Intent.EXTRA_TEXT, "private caption https://example.com/a")
                .putExtra("unrelated_string", "https://example.com/ignored");
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(IntentObservation.Outcome.NO_URL, observation.outcome());
        assertFalse(observation.urlFound());
        assertEquals(IntentObservation.UrlOrigin.NONE, observation.urlOrigin());
        assertEquals(0, observation.urlCount());
    }

    @Test public void knownNestedBundleReadsOnlyWhitelistedLeaves() {
        Bundle nested = new Bundle();
        nested.putString("url", "https://example.com/nested");
        nested.putString("token", "https://example.com/ignored");
        Intent request = supported().putExtra("params", nested);
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(IntentObservation.Outcome.URL_FOUND, observation.outcome());
        assertEquals(1, observation.urlCount());
        assertEquals(IntentObservation.UrlOrigin.NESTED, observation.urlOrigin());
        assertEquals(IntentObservation.NESTED_PRESENT, observation.fieldPresence());
    }

    @Test public void unknownNestedContainersAndParcelableObjectsAreIgnored() {
        Bundle hidden = new Bundle();
        hidden.putString("url", "https://example.com/not-whitelisted-container");
        Intent request = supported().putExtra("private_message_object", hidden)
                .putExtra("url", new NoStringParcelable());
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(IntentObservation.Outcome.NO_URL, observation.outcome());
        assertEquals(IntentObservation.URL_PRESENT, observation.fieldPresence());
    }

    @Test public void duplicateUrlsAreCountedOnceAndDataWinsOrigin() {
        String url = "https://example.com/shared";
        Intent request = supported().setData(Uri.parse(url)).putExtra("rawUrl", url).putExtra("url", url);
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(1, observation.urlCount());
        assertEquals(IntentObservation.UrlOrigin.DATA, observation.urlOrigin());
    }

    @Test public void nestedCyclesTerminateWithoutMutatingBundles() {
        Bundle cycle = new Bundle();
        cycle.putBundle("params", cycle);
        cycle.putString("rawUrl", "https://example.com/cycle");
        Intent request = supported().putExtra("params", cycle);
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(IntentObservation.Outcome.URL_FOUND, observation.outcome());
        assertEquals(1, observation.urlCount());
        assertNotNull(cycle.getBundle("params"));
        assertEquals("https://example.com/cycle", cycle.getString("rawUrl"));
    }

    @Test public void depthLimitAcceptsFourLevelsAndRejectsFifth() {
        Intent accepted = supported().putExtras(nested(4));
        assertEquals(IntentObservation.Outcome.URL_FOUND, IntentObservation.inspect(accepted).outcome());
        Intent tooDeep = supported().putExtras(nested(5));
        IntentObservation.Observation observation = IntentObservation.inspect(tooDeep);
        assertEquals(IntentObservation.Outcome.UNREADABLE_PAYLOAD, observation.outcome());
        assertFalse(observation.urlFound());
    }

    @Test public void broadNestedGraphStopsAtNodeLimit() {
        Intent request = supported().putExtras(tree(4));
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(IntentObservation.Outcome.UNREADABLE_PAYLOAD, observation.outcome());
        assertEquals(0, observation.urlCount());
    }

    @Test public void urlCountIsCappedAtEight() {
        Intent request = supported();
        Bundle first = values(0);
        Bundle second = values(3);
        Bundle third = values(6);
        request.putExtra("extras", first).putExtra("bundle", second).putExtra("params", third);
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(8, observation.urlCount());
        assertEquals(IntentObservation.Outcome.URL_FOUND, observation.outcome());
    }

    @Test public void payloadReadFailureIsMetadataOnlyAndPreservesValidDataCount() {
        Intent request = new Intent() {
            @Override public Bundle getExtras() { throw new BadParcelableException("private payload and token"); }
        };
        request.setComponent(new ComponentName(WECHAT, WEBVIEW));
        request.setData(Uri.parse("https://example.com/data"));
        IntentObservation.Observation observation = IntentObservation.inspect(request);
        assertEquals(IntentObservation.Outcome.UNREADABLE_PAYLOAD, observation.outcome());
        assertTrue(observation.urlFound());
        assertEquals(1, observation.urlCount());
        assertFalse(observation.toString().contains("private payload"));
        assertFalse(observation.toString().contains("token"));
    }

    @Test public void validationRejectsCredentialsControlCharactersAndUnsafeSchemes() {
        for (String invalid : List.of("file:///private/path", "javascript:alert(1)",
                "https://user:pass@example.com/path", "https://example.com/a\nb", "https://example.com/%0A",
                "https://example.com:0/", "https://example.com:65536/", "https://example.com:/",
                "https://.example.com/", "https://example..com/", "https://example.com/\u0000"))
            assertFalse(invalid, IntentObservation.validHttpUrl(invalid));
        assertTrue(IntentObservation.validHttpUrl("https://example.com/a?x=a+b&next=%2Fsafe#fragment"));
        assertTrue(IntentObservation.validHttpUrl("HTTP://example.com:80/path"));
        assertTrue(IntentObservation.validHttpUrl("https://[2001:db8::1]/path"));
        assertFalse(IntentObservation.validHttpUrl("https://example.com/" + "a".repeat(8192)));
        assertFalse(IntentObservation.validHttpUrl(null));
    }

    private Bundle nested(int depth) {
        Bundle root = new Bundle();
        Bundle current = root;
        for (int i = 0; i < depth; i++) {
            Bundle next = new Bundle();
            current.putBundle("params", next);
            current = next;
        }
        current.putString("url", "https://example.com/deep");
        return root;
    }

    private Bundle values(int start) {
        Bundle bundle = new Bundle();
        bundle.putString("rawUrl", "https://example.com/" + start);
        bundle.putString("url", "https://example.com/" + (start + 1));
        bundle.putString(Intent.EXTRA_TEXT, "https://example.com/" + (start + 2));
        return bundle;
    }

    private Bundle tree(int depth) {
        Bundle bundle = new Bundle();
        bundle.putInt("rawUrl", 42);
        if (depth > 0) for (String key : List.of("extras", "bundle", "data", "params", "intent_extras", "webview_params"))
            bundle.putBundle(key, tree(depth - 1));
        return bundle;
    }

    public static final class NoStringParcelable implements Parcelable {
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel parcel, int flags) { throw new AssertionError("Not serialized"); }
        @Override public String toString() { throw new AssertionError("Not interpreted as text"); }
        public static final Parcelable.Creator<NoStringParcelable> CREATOR = new Parcelable.Creator<>() {
            @Override public NoStringParcelable createFromParcel(Parcel source) { return new NoStringParcelable(); }
            @Override public NoStringParcelable[] newArray(int size) { return new NoStringParcelable[size]; }
        };
    }
}
