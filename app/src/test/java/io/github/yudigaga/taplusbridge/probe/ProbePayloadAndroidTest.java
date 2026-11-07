package io.github.yudigaga.taplusbridge.probe;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class ProbePayloadAndroidTest {
    @Test public void completeDownloadLinksSurviveKnownAndGenericProbeCarriersAndRemainUnique() {
        for (String link : List.of("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=a%2Bb",
                "ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/")) {
            assertEquals(link, ProbePayload.uniqueHttpUrl(source().setData(Uri.parse(link)).putExtra("rawUrl", link)));
            Intent generic = new Intent("app.PAGE").setComponent(new ComponentName("com.example.source", "com.example.source.Page"));
            assertEquals(link, ProbePayload.uniqueSourcePageUrl(generic.putExtra("url", link), "com.example.source"));
            assertNull(ProbePayload.uniqueHttpUrl(source().setData(Uri.parse(link)).putExtra("url", "https://example.com/other")));
        }
    }

    private static final String WECHAT = "com.tencent.mm";
    private static final String WEBVIEW = "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI";
    private static final String LINK = "https://www.bilibili.com/video/BV1xx411c7mD?from=probe";

    private Intent source() { return new Intent().setComponent(new ComponentName(WECHAT, WEBVIEW)); }

    @Test public void uniqueRawUrlIsReturnedWithoutMutatingTheRequest() {
        Intent request = source().putExtra("rawUrl", LINK).putExtra("private", "unchanged");
        String before = request.toUri(0);
        ClassLoader loader = request.getExtras().getClassLoader();
        assertEquals(LINK, ProbePayload.uniqueHttpUrl(request));
        assertEquals(before, request.toUri(0));
        assertSame(loader, request.getExtras().getClassLoader());
        assertEquals("unchanged", request.getStringExtra("private"));
    }

    @Test public void allKnownCarriersAcceptADataUrl() {
        for (ComponentName component : List.of(
                new ComponentName(WECHAT, WEBVIEW),
                new ComponentName(WECHAT, "com.tencent.mm.plugin.webview.ui.tools.WebViewUI"),
                new ComponentName("com.tencent.mobileqq", "com.tencent.mobileqq.activity.QQBrowserActivity"))) {
            Intent request = new Intent(Intent.ACTION_VIEW, Uri.parse(LINK)).setComponent(component);
            assertEquals(LINK, ProbePayload.uniqueHttpUrl(request));
        }
    }

    @Test public void unsupportedRequestsDoNotReadAnyPayload() {
        Intent request = new Intent() {
            @Override public Uri getData() { throw new AssertionError("Unsupported data read"); }
            @Override public Bundle getExtras() { throw new AssertionError("Unsupported extras read"); }
        };
        request.setComponent(new ComponentName(WECHAT, "com.tencent.mm.ui.LauncherUI"));
        assertNull(ProbePayload.uniqueHttpUrl(request));
        request.setComponent(new ComponentName("com.example.fake", WEBVIEW));
        assertNull(ProbePayload.uniqueHttpUrl(request));
        assertNull(ProbePayload.uniqueHttpUrl(null));
    }

    @Test public void exactDuplicatesAcrossDataAndNestedFieldsStayUnique() {
        Bundle nested = new Bundle(); nested.putString("url", LINK);
        Intent request = source().setData(Uri.parse(LINK)).putExtra("rawUrl", LINK)
                .putExtra(Intent.EXTRA_TEXT, LINK).putExtra("webview_params", nested);
        assertEquals(LINK, ProbePayload.uniqueHttpUrl(request));
    }

    @Test public void redirectWrapperAndItsSameInnerDestinationAreOneCandidateAcrossKnownAndGenericPages() {
        String wrapper="https://bridge.example/link?url="+java.net.URLEncoder.encode(LINK,java.nio.charset.StandardCharsets.UTF_8);
        Intent known=source().setData(Uri.parse(wrapper)).putExtra("rawUrl",LINK);
        assertEquals(LINK,ProbePayload.uniqueHttpUrl(known));
        String caller="com.example.source";
        Intent generic=new Intent("app.OPEN_PAGE").setComponent(new ComponentName(caller,caller+".PageActivity"));
        generic.putExtra("url",wrapper).putExtra("landing_page",LINK);
        assertEquals(LINK,ProbePayload.uniqueSourcePageUrl(generic,caller));
        generic.putExtra("destination","https://different.example/item");
        assertNull(ProbePayload.uniqueSourcePageUrl(generic,caller));
    }

    @Test public void anAmbiguousRedirectFieldCannotHideBesideAnOtherwiseValidCandidate() {
        String encoded=java.net.URLEncoder.encode(LINK,java.nio.charset.StandardCharsets.UTF_8);
        String ambiguous="https://bridge.example/out?url="+encoded+"&target="+encoded;
        assertNull(ProbePayload.uniqueHttpUrl(source().setData(Uri.parse(LINK)).putExtra("url",ambiguous)));
    }

    @Test public void oversizedButAllowedRedirectAmbiguityStillRejectsTheWholeCandidateSet() {
        String first="https://target.example/"+"a".repeat(4200);
        String second="https://other.example/"+"b".repeat(4200);
        String wrapper="https://bridge.example/link?url="+java.net.URLEncoder.encode(first,java.nio.charset.StandardCharsets.UTF_8)
                +"&target="+java.net.URLEncoder.encode(second,java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(wrapper.length()>8192);assertTrue(wrapper.length()<16384);
        assertNull(ProbePayload.uniqueHttpUrl(source().setData(Uri.parse(LINK)).putExtra("url",wrapper)));
        String caller="com.example.source";
        Intent generic=new Intent().setComponent(new ComponentName(caller,caller+".PageActivity"));
        generic.putExtra("url",LINK).putExtra("destination",wrapper);
        assertNull(ProbePayload.uniqueSourcePageUrl(generic,caller));
    }

    @Test public void anOverlongNormalizedTargetCannotBeIgnoredBesideAShortValidUrl() {
        String target="https://target.example/"+"a".repeat(9000);
        String wrapper="https://bridge.example/link?url="+java.net.URLEncoder.encode(target,java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(wrapper.length()<16384);
        assertNull(ProbePayload.uniqueHttpUrl(source().setData(Uri.parse(LINK)).putExtra("url",wrapper)));
        String caller="com.example.source";
        Intent generic=new Intent().setComponent(new ComponentName(caller,caller+".PageActivity"));
        generic.putExtra("url",LINK).putExtra("destination",wrapper);
        assertNull(ProbePayload.uniqueSourcePageUrl(generic,caller));
        assertNull(ScopeLinkProbe.strictHttp(new Intent(Intent.ACTION_VIEW,Uri.parse(LINK)).putExtra("url",wrapper),caller));
    }

    @Test public void distinctUrlCandidatesAreNeverChosenByFieldOrder() {
        Intent request = source().putExtra("rawUrl", LINK).putExtra("url", "https://example.com/other");
        assertNull(ProbePayload.uniqueHttpUrl(request));
        Bundle nested = new Bundle(); nested.putString("url", "https://example.com/other");
        assertNull(ProbePayload.uniqueHttpUrl(source().setData(Uri.parse(LINK)).putExtra("params", nested)));
    }

    @Test public void unknownFieldsAndUnknownContainersAreNeverScanned() {
        Bundle hidden = new Bundle(); hidden.putString("url", "https://example.com/ignored");
        Intent request = source().putExtra("rawUrl", LINK).putExtra("message_object", hidden)
                .putExtra("not_whitelisted", new NoStringParcelable());
        assertEquals(LINK, ProbePayload.uniqueHttpUrl(request));
        assertNull(ProbePayload.uniqueHttpUrl(source().putExtra("message_object", hidden)));
    }

    @Test public void nonStringUrlLeavesNeverCallObjectToStringOrReturnAPartialResult() {
        assertNull(ProbePayload.uniqueHttpUrl(source().putExtra("url", new NoStringParcelable())));
        assertNull(ProbePayload.uniqueHttpUrl(source().putExtra("rawUrl", LINK)
                .putExtra("url", new NoStringParcelable())));
        assertNull(ProbePayload.uniqueHttpUrl(source().putExtra("rawUrl", LINK)
                .putExtra("params", new NoStringParcelable())));
    }

    @Test public void nullKnownFieldsDoNotInventOrInvalidateAUniqueUrl() {
        Bundle fields = new Bundle(); fields.putString("rawUrl", LINK); fields.putString("url", null);
        fields.putBundle("params", null);
        assertEquals(LINK, ProbePayload.uniqueHttpUrl(source().putExtras(fields)));
        assertNull(ProbePayload.uniqueHttpUrl(source()));
    }

    @Test public void captionsSchemesCredentialsAndControlValuesAreNotReinterpreted() {
        for (String invalid : List.of("javascript:alert(1)", "file:///private/card", "weixin://card/1",
                "title " + LINK, "https://user:pass@example.com/path", "https://example.com/%0A",
                "https://example.com/a\nb", "https://example.com:65536/", "https://example.com:/",
                "https://example.com/" + "a".repeat(IntentObservation.MAX_URL_LENGTH)))
            assertNull(ProbePayload.uniqueHttpUrl(source().putExtra("rawUrl", invalid)));
    }

    @Test public void knownNestedCyclesTerminateAndPreserveTheOriginalBundles() {
        Bundle cycle = new Bundle(); cycle.putBundle("params", cycle); cycle.putString("rawUrl", LINK);
        assertEquals(LINK, ProbePayload.uniqueHttpUrl(source().putExtra("params", cycle)));
        assertSame(cycle, cycle.getBundle("params"));
        assertEquals(LINK, cycle.getString("rawUrl"));
    }

    @Test public void depthAndNodeLimitsRejectAnOtherwiseValidPartialResult() {
        assertEquals(LINK, ProbePayload.uniqueHttpUrl(source().putExtras(chain(4))));
        assertNull(ProbePayload.uniqueHttpUrl(source().putExtras(chain(5))));
        assertNull(ProbePayload.uniqueHttpUrl(source().putExtra("rawUrl", LINK).putExtra("params", tree(4))));
    }

    @Test public void unreadableExtrasNeverReturnEvenAValidDataUrl() {
        Intent request = new Intent() {
            @Override public Bundle getExtras() { throw new BadParcelableException("private payload"); }
        };
        request.setComponent(new ComponentName(WECHAT, WEBVIEW)).setData(Uri.parse(LINK));
        assertNull(ProbePayload.uniqueHttpUrl(request));
        Intent unreadableData = new Intent() {
            @Override public Uri getData() { throw new BadParcelableException("private data"); }
        };
        unreadableData.setComponent(new ComponentName(WECHAT, WEBVIEW)).putExtra("rawUrl", LINK);
        assertNull(ProbePayload.uniqueHttpUrl(unreadableData));
    }

    @Test public void broadCandidateSetsAreAmbiguousAndRemainBounded() {
        Intent request = source();
        for (String key : List.of("extras", "bundle", "data", "params", "intent_extras", "webview_params")) {
            Bundle nested = new Bundle();
            nested.putString("rawUrl", "https://example.com/" + key + "/1");
            nested.putString("url", "https://example.com/" + key + "/2");
            request.putExtra(key, nested);
        }
        assertNull(ProbePayload.uniqueHttpUrl(request));
    }

    private Bundle chain(int depth) {
        Bundle root = new Bundle(); Bundle current = root;
        for (int i = 0; i < depth; i++) {
            Bundle next = new Bundle(); current.putBundle("params", next); current = next;
        }
        current.putString("url", LINK);
        return root;
    }

    private Bundle tree(int depth) {
        Bundle bundle = new Bundle(); bundle.putString("url", LINK);
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
