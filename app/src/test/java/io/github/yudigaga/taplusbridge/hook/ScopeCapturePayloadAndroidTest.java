package io.github.yudigaga.taplusbridge.hook;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ScopeCapturePayloadAndroidTest {
    @Test public void scopeTextLinkGateAcceptsOnlyCompleteSupportedDownloadLinks() {
        assertTrue(ScopeCapturePayload.validTextUrl("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        assertTrue(ScopeCapturePayload.validTextUrl("ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/"));
        assertFalse(ScopeCapturePayload.validTextUrl("magnet:?dn=title-only"));
        assertFalse(ScopeCapturePayload.validTextUrl("ed2k://|file|test.iso|-1|bad|/"));
        assertFalse(ScopeCapturePayload.validTextUrl("intent://open#Intent;scheme=magnet;end"));
    }

    private static final long NOW = 40_000;

    @Test public void validSystemMetadataKeepsOriginalCoordinatesAndAllowsChromiumActivityName() {
        ScopeCapturePayload.Metadata data = ScopeCapturePayload.validate(metadata(), NOW);
        assertEquals("com.android.chrome",data.source());
        assertEquals("org.chromium.chrome.browser.ChromeTabbedActivity",data.activity());
        assertEquals(new Rect(0,24,1080,2300),data.rect());
        Rect mutable=data.rect();mutable.setEmpty();assertFalse(data.rect().isEmpty());
        assertEquals(2400,data.height());
    }

    @Test public void malformedSourceUserDisplayCoordinatesAndFreshnessAreRejected() {
        Bundle[] invalid = new Bundle[15];
        for(int i=0;i<invalid.length;i++)invalid[i]=metadata();
        invalid[0].putString("source","com.example;command");
        invalid[1].putString("activity","not/a/class");
        invalid[2].putInt("uid",1010000); invalid[3].putInt("user",10);
        invalid[4].putInt("display",1); invalid[5].putFloat("x",Float.NaN);
        invalid[6].putFloat("y",2401f); invalid[7].putInt("right",1100);
        invalid[8].putInt("left",1080); invalid[9].putLong("captured_elapsed",NOW+1);
        invalid[10].putLong("captured_elapsed",NOW-ScopeCapturePayload.MAX_AGE_MILLIS-1);
        invalid[11].putInt("bytes",ScopeCapturePayload.MAX_BYTES+1);
        invalid[12].putInt("bytes",0); invalid[13].putString("user","0");
        invalid[14].remove("activity");
        for(Bundle data:invalid)assertThrows(IllegalArgumentException.class,()->ScopeCapturePayload.validate(data,NOW));
    }

    @Test public void pipeReaderRequiresExactLengthAndNeverAcceptsExcessData() throws Exception {
        byte[] input = {1,2,3,4};
        assertArrayEquals(input,ScopeCapturePayload.read(new ByteArrayInputStream(input),4));
        assertThrows(IOException.class,()->ScopeCapturePayload.read(new ByteArrayInputStream(input),5));
        assertThrows(IOException.class,()->ScopeCapturePayload.read(new ByteArrayInputStream(input),3));
        assertThrows(IllegalArgumentException.class,()->ScopeCapturePayload.read(new ByteArrayInputStream(input),ScopeCapturePayload.MAX_BYTES+1));
    }

    @Test public void pngHeaderAndBoundsAreCheckedBeforeAllocatingTheDecodedImage() {
        byte[] png = png(100,80); Bitmap decoded = ScopeCapturePayload.decode(png);
        try {assertEquals(100,decoded.getWidth());assertEquals(80,decoded.getHeight());}
        finally {decoded.recycle();}
        assertThrows(IllegalArgumentException.class,()->ScopeCapturePayload.decode(new byte[24]));
        assertThrows(IllegalArgumentException.class,()->ScopeCapturePayload.decode(png(2001,1)));
        assertThrows(IllegalArgumentException.class,()->ScopeCapturePayload.decode(png(1501,2000)));
    }

    @Test public void sdkAdapterBuildsTheOriginalImageJobWithTokenAndTouchGeometry() throws Exception {
        ScopeCapturePayload.Factory factory = new ScopeCapturePayload.Factory(FakeResult.class,FakeToken.class);
        Bitmap image=Bitmap.createBitmap(100,80,Bitmap.Config.ARGB_8888);
        try {
            FakeResult result=(FakeResult)factory.create(ScopeCapturePayload.validate(metadata(),NOW),image,77,"nonce_fixture_123456");
            assertEquals("com.android.chrome",result.token.source);
            assertEquals("org.chromium.chrome.browser.ChromeTabbedActivity",result.token.activity);
            assertEquals("nonce_fixture_123456",result.token.identity);assertEquals(77,result.token.version);
            assertEquals("image_pick",result.job);assertEquals("image_pick_mode",result.feature);
            assertEquals(5,result.properties.size());assertFalse(result.properties.containsKey("observe_control_event"));
            assertEquals("120.0-500.0",result.properties.get("coordinate"));
            for(String key:new String[]{"isfullscreen","isedittext","actionbarvisible","ismultiscreen"})assertEquals("0",result.properties.get(key));
            Bundle payload=(Bundle)result.value;assertSame(image,payload.getParcelable("viewBitmap"));
            assertTrue("A whole-window capture cannot authorize a simulated card click",payload.getBoolean("taplus_scope_capture"));
            assertEquals(2,payload.getInt("catchType"));assertEquals(new Rect(0,24,1080,2300),payload.getParcelable("view_rect"));
            android.os.Parcelable[] points=payload.getParcelableArray("touch_points");assertEquals(2,points.length);
            assertEquals(new PointF(120,500),points[0]);assertEquals(new PointF(120,500),points[1]);
            assertNotSame(points[0],points[1]);
        } finally {image.recycle();}
    }

    @Test public void wrongSdkContractsAndRecycledImagesAreRejected() throws Exception {
        assertThrows(NoSuchMethodException.class,()->new ScopeCapturePayload.Factory(StaticResult.class,FakeToken.class));
        assertThrows(NoSuchMethodException.class,()->new ScopeCapturePayload.Factory(FakeResult.class,WrongToken.class));
        Bitmap image=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);image.recycle();
        ScopeCapturePayload.Factory factory=new ScopeCapturePayload.Factory(FakeResult.class,FakeToken.class);
        assertThrows(IllegalArgumentException.class,()->factory.create(ScopeCapturePayload.validate(metadata(),NOW),image,77,"nonce_fixture_123456"));
    }

    @Test public void textResultUsesTheOriginalTextJobAndNeverFabricatesAnImage() throws Exception {
        String url="https://example.org/project?from=about#source";
        Bundle wire=metadata();wire.putString("kind","text");wire.putInt("bytes",0);wire.putString("url",url);
        ScopeCapturePayload.Metadata validated=ScopeCapturePayload.validate(wire,NOW);
        assertEquals("text",validated.kind());assertEquals(0,validated.bytes());
        ScopeCapturePayload.Factory factory=new ScopeCapturePayload.Factory(FakeResult.class,FakeToken.class);
        FakeResult result=(FakeResult)factory.createText(validated,url,77,"nonce_fixture_123456");
        assertEquals("com.android.chrome",result.token.source);assertEquals(77,result.token.version);
        assertEquals("text_pick",result.job);assertEquals("pick_mode",result.feature);
        Bundle payload=(Bundle)result.value;
        assertEquals(url,payload.getString("viewContent"));assertEquals(1,payload.getInt("catchType"));
        assertFalse(payload.containsKey("viewBitmap"));assertTrue(payload.getBoolean("taplus_scope_capture"));
        assertEquals(new Rect(0,24,1080,2300),payload.getParcelable("view_rect"));
        assertEquals(2,payload.getParcelableArray("touch_points").length);
        assertEquals(url,result.properties.get(ScopeCapturePayload.LINK_URL_KEY));
    }

    @Test public void actualSourceContentCanCarryPlainTextWithoutInventingAUrl() throws Exception {
        Bundle wire=metadata(); wire.putString("kind", "content"); wire.putInt("bytes", 0);
        wire.putString("text", "这是触点处提供的真实文字");
        var validated=ScopeCapturePayload.validate(wire,NOW);
        var factory=new ScopeCapturePayload.Factory(FakeResult.class,FakeToken.class);
        var create=ScopeCapturePayload.Factory.class.getDeclaredMethod("createContent", ScopeCapturePayload.Metadata.class,
                String.class,String.class,int.class,String.class); create.setAccessible(true);
        FakeResult result=(FakeResult)create.invoke(factory,validated,wire.getString("text"),null,77,"nonce_fixture_123456");
        assertEquals("这是触点处提供的真实文字", ((Bundle)result.getParams("text_pick").get("pick_mode")).getString("viewContent"));
        assertFalse(result.properties.containsKey(ScopeCapturePayload.LINK_URL_KEY));
        assertEquals("120.0-500.0",result.properties.get("coordinate"));
    }

    @Test public void verifiedScopeContentDoesNotRepeatTheRecognitionStageAfterHostDelivery() throws Exception {
        Bundle wire=metadata();wire.putString("kind","content");wire.putInt("bytes",0);wire.putString("text","实际正文");
        var factory=new ScopeCapturePayload.Factory(FakeResult.class,FakeToken.class);
        Object provided=factory.createContent(ScopeCapturePayload.validate(wire,NOW),"实际正文",null,77,"nonce_fixture_123456");
        var ready=RecognitionHooks.class.getDeclaredMethod("providedRecognitionAt",Object.class,long.class);ready.setAccessible(true);
        assertEquals(5000L,ready.invoke(null,provided,5000L));
        FakeResult nativePick=new FakeResult();Bundle original=new Bundle();original.putParcelable("view_rect",new Rect(0,0,100,100));
        nativePick.addContent("text_pick","pick_mode",original);
        assertEquals(0L,ready.invoke(null,nativePick,5000L));
    }

    @Test public void sourceContentRequiresBoundedTextAndCannotSmuggleDescriptorsOrSyntheticUrls() {
        Bundle base=metadata(); base.putString("kind", "content"); base.putInt("bytes",0); base.putString("text","正文");
        for (Object invalid : new Object[]{"", " ", "x".repeat(131073), 7}) {
            Bundle wire=new Bundle(base);
            if(invalid instanceof String text)wire.putString("text",text);else wire.putInt("text",7);
            assertThrows(IllegalArgumentException.class,()->ScopeCapturePayload.validate(wire,NOW));
        }
        Bundle badUrl=new Bundle(base);badUrl.putString("url","标题不是链接");
        assertThrows(IllegalArgumentException.class,()->ScopeCapturePayload.validate(badUrl,NOW));
        Bundle badBytes=new Bundle(base);badBytes.putInt("bytes",1);
        assertThrows(IllegalArgumentException.class,()->ScopeCapturePayload.validate(badBytes,NOW));
    }

    @Test public void imageCaptureCanCarryTheRealLinkWithoutReplacingItsBitmapOrImageJob() throws Exception {
        String url = "https://example.org/actual-destination";
        Bundle wire = metadata(); wire.putString("kind", "image"); wire.putString("url", url);
        ScopeCapturePayload.Metadata validated = ScopeCapturePayload.validate(wire, NOW);
        ScopeCapturePayload.Factory factory = new ScopeCapturePayload.Factory(FakeResult.class, FakeToken.class);
        Bitmap image = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
        try {
            FakeResult result = (FakeResult) factory.createImage(validated, image, url, 77, "nonce_fixture_123456");
            assertEquals("image_pick", result.job); assertNull(result.getParams("text_pick"));
            assertSame(image, ((Bundle) result.getParams("image_pick").get("image_pick_mode")).getParcelable("viewBitmap"));
            assertEquals(url, result.properties.get(ScopeCapturePayload.LINK_URL_KEY));
            HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
            enhancer.capture(result); assertEquals(List.of(url), enhancer.capturedLinks());
        } finally { image.recycle(); }
        for (Object invalid : new Object[]{"not a link", "javascript:open()", "https://user:pass@example.org/", 17}) {
            Bundle bad = new Bundle(wire);
            if (invalid instanceof String text) bad.putString("url", text); else bad.putInt("url", (Integer) invalid);
            assertThrows(IllegalArgumentException.class, () -> ScopeCapturePayload.validate(bad, NOW));
        }
    }

    @Test public void enrichingANativePickPreservesBothTextAndImageObjectsAndOtherContent() throws Exception {
        String url = "https://example.org/hidden-link";
        ScopeCapturePayload.Factory factory = new ScopeCapturePayload.Factory(FakeResult.class, FakeToken.class);
        FakeResult result = new FakeResult(); FakeToken token = new FakeToken("com.example.source", "com.example.Main", "native", 12);
        result.setInjectorToken(token); result.properties.put("existing", "kept");
        Bundle text = new Bundle(); text.putString("viewContent", "原始文字");
        Bitmap image = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        Bundle picture = new Bundle(); picture.putParcelable("viewBitmap", image);
        Object extra = new Object();
        try {
            result.addContent("text_pick", "pick_mode", text);
            result.addContent("image_pick", "image_pick_mode", picture);
            result.addContent("other_native_job", "extra", extra);
            Map<String, Object> textJob = result.getParams("text_pick"), imageJob = result.getParams("image_pick");
            assertSame(result, factory.enrich(result, url));
            assertSame(token, result.token); assertSame(textJob, result.getParams("text_pick"));
            assertSame(imageJob, result.getParams("image_pick"));
            assertSame(text, textJob.get("pick_mode")); assertEquals("原始文字", text.getString("viewContent"));
            assertSame(picture, imageJob.get("image_pick_mode")); assertSame(image, picture.getParcelable("viewBitmap"));
            assertSame(extra, result.getParams("other_native_job").get("extra")); assertFalse(image.isRecycled());
            assertEquals("kept", result.properties.get("existing")); assertEquals(url, result.properties.get(ScopeCapturePayload.LINK_URL_KEY));
            HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
            enhancer.capture(result); assertEquals(List.of(url), enhancer.capturedLinks());
            assertSame(result, factory.enrich(result, null));
            assertThrows(IllegalArgumentException.class, () -> factory.enrich(result, "not a link"));
            assertThrows(IllegalArgumentException.class, () -> factory.enrich(new Object(), url));
            assertThrows(IllegalArgumentException.class, () -> factory.enrich(null, url));
        } finally { image.recycle(); }
    }

    @Test public void resumeIsOnlyAMetadataNotificationAndCannotCarryAnyContentOrDescriptor() {
        Bundle resume = metadata(); resume.putString("kind", "resume"); resume.putInt("bytes", 0);
        ScopeCapturePayload.Metadata validated = ScopeCapturePayload.validate(resume, NOW);
        assertEquals("resume", validated.kind()); assertEquals(0, validated.bytes());
        Bundle withBytes = new Bundle(resume); withBytes.putInt("bytes", 1);
        Bundle withUrl = new Bundle(resume); withUrl.putString("url", "https://example.org/");
        Bundle withNullUrl = new Bundle(resume); withNullUrl.putString("url", null);
        Bundle withFd = new Bundle(resume); withFd.putString("fd", "forbidden");
        Bundle old = new Bundle(resume); old.putLong("captured_elapsed", NOW - ScopeCapturePayload.MAX_AGE_MILLIS - 1);
        for (Bundle bad : new Bundle[]{withBytes, withUrl, withNullUrl, withFd, old})
            assertThrows(IllegalArgumentException.class, () -> ScopeCapturePayload.validate(bad, NOW));
    }

    @Test public void textUriMustBeOneCompleteBoundedHttpUrlAndCannotCarryADescriptor() {
        String prefix="https://example.org/";
        assertTrue(ScopeCapturePayload.validTextUrl(prefix+"a".repeat(ScopeCapturePayload.MAX_URL_LENGTH-prefix.length())));
        for(String url:new String[]{"https://example.org/ https://other.example/", "javascript:run()", "file:///tmp/file",
                "https://user:password@example.org/", "https:///missing-host", "https://example.org/%0d%0a",
                prefix+"a".repeat(ScopeCapturePayload.MAX_URL_LENGTH)}) {
            assertFalse(ScopeCapturePayload.validTextUrl(url));
            Bundle invalid=metadata();invalid.putString("kind","text");invalid.putInt("bytes",0);invalid.putString("url",url);
            assertThrows(IllegalArgumentException.class,()->ScopeCapturePayload.validate(invalid,NOW));
        }
        Bundle valid=metadata();valid.putString("kind","text");valid.putInt("bytes",0);valid.putString("url",prefix);
        Bundle bytes=new Bundle(valid);bytes.putInt("bytes",1);
        Bundle descriptor=new Bundle(valid);descriptor.putString("fd","not accepted");
        Bundle kind=new Bundle(valid);kind.putString("kind","unknown");
        for(Bundle invalid:new Bundle[]{bytes,descriptor,kind})
            assertThrows(IllegalArgumentException.class,()->ScopeCapturePayload.validate(invalid,NOW));
        assertEquals("image",ScopeCapturePayload.validate(metadata(),NOW).kind());
    }

    @Test public void gestureTimestampCannotBeFutureOrOlderThanTheCaptureLease() {
        Bundle valid = metadata(); valid.putLong("gesture_elapsed", NOW - 1500);
        ScopeCapturePayload.validate(valid, NOW);
        Bundle future = metadata(); future.putLong("gesture_elapsed", NOW);
        assertThrows(IllegalArgumentException.class, () -> ScopeCapturePayload.validate(future, NOW));
        Bundle stale = metadata(); stale.putLong("gesture_elapsed", 0);
        assertThrows(IllegalArgumentException.class, () -> ScopeCapturePayload.validate(stale, NOW + ScopeCapturePayload.MAX_AGE_MILLIS + 1));
        Bundle wrongType = metadata(); wrongType.putString("gesture_elapsed", "hidden");
        assertThrows(IllegalArgumentException.class, () -> ScopeCapturePayload.validate(wrongType, NOW));
    }

    private static Bundle metadata() {
        Bundle data=new Bundle();data.putString("source","com.android.chrome");
        data.putString("activity","org.chromium.chrome.browser.ChromeTabbedActivity");
        data.putInt("uid",10045);data.putInt("user",0);data.putInt("display",0);
        data.putFloat("x",120f);data.putFloat("y",500f);
        data.putInt("left",0);data.putInt("top",24);data.putInt("right",1080);data.putInt("bottom",2300);
        data.putInt("width",1080);data.putInt("height",2400);data.putInt("bytes",100);
        data.putLong("captured_elapsed",NOW-1000);return data;
    }

    private static byte[] png(int width,int height) {
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try {ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,bytes));return bytes.toByteArray();}
        finally {image.recycle();}
    }

    public static final class FakeToken {
        final String source,activity,identity;final int version;
        public FakeToken(String source,String activity,String identity,int version) {
            this.source=source;this.activity=activity;this.identity=identity;this.version=version;
        }
    }
    public static final class FakeResult {
        FakeToken token;final Map<String,Object> properties=new HashMap<>();String job,feature;Object value;
        final Map<String,Map<String,Object>> results=new HashMap<>();
        public FakeResult() {}
        public void setInjectorToken(FakeToken token){this.token=token;}
        public Map<String,Object> getPropertyMap(){return properties;}
        public void addContent(String job,String feature,Object value){this.job=job;this.feature=feature;this.value=value;results.computeIfAbsent(job,ignored->new HashMap<>()).put(feature,value);}
        public Map<String,Object> getParams(String job){return results.get(job);}
        public Map<String,Map<String,Object>> getResults(){return results;}
    }
    public static final class StaticResult {
        public StaticResult() {}
        public static void setInjectorToken(FakeToken token) {}
        public Map<String,Object> getPropertyMap(){return new HashMap<>();}
        public void addContent(String job,String feature,Object value){}
    }
    public static final class WrongToken {
        public WrongToken(String source,String activity,String identity,long version) {}
    }
}
