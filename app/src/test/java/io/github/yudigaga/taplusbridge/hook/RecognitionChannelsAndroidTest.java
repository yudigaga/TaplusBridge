package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Rect;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class RecognitionChannelsAndroidTest {
    @Test public void sourceDiagnosticsCountRealLinksWithoutLoggingTheirContents() throws Exception {
        var enhancer=new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
        var events=new ArrayList<String>();enhancer.setReporter(events::add);
        Method capture=find(HostRecognitionEnhancer.class,"captureText",String.class,String.class);
        capture.invoke(enhancer,"private title https://example.com/a?secret=hidden","ocr");
        capture.invoke(enhancer,"https://example.com/a?secret=hidden","server");
        String log=String.join("\n",events);
        assertTrue(log.contains("recognition source stage=links channel=ocr found=1 added=1 total=1"));
        assertTrue(log.contains("recognition source stage=links channel=server found=1 added=0 total=1"));
        assertFalse(log.contains("private title"));assertFalse(log.contains("https://"));assertFalse(log.contains("hidden"));
    }

    @Test public void nativeOffStillAcceptsAssistAndProbeWithoutReimportingNativeMetadata() throws Exception {
        var enhancer=new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
        Object channels=channels(false,true);
        Catcher result=new Catcher();
        Bundle nativeContent=new Bundle();nativeContent.putString("viewContent","https://native.example/hidden");
        result.jobs.put("text_pick",Map.of("pick_mode",nativeContent));
        result.properties.put("taplus_scope_text","正文 https://assist.example/content");
        result.properties.put("taplus_scope_link_url","https://probe.example/card");
        find(HostRecognitionEnhancer.class,"capture",Object.class,channels.getClass()).invoke(enhancer,result,channels);
        assertEquals(List.of("https://assist.example/content","https://probe.example/card"),enhancer.capturedLinks());
    }

    @Test public void missingOrInactivePolicyNeverEnablesARecognitionSource() throws Exception {
        var provider=Robolectric.buildContentProvider(PolicyProvider.class).create("io.github.yudigaga.taplusbridge.bridge").get();
        ShadowContentResolver.registerProviderInternal("io.github.yudigaga.taplusbridge.bridge",provider);
        var policy=policyClass();Method read=find(policy,"read",android.content.Context.class);
        PolicyProvider.response=Bundle.EMPTY;
        assertFalse(enabled(read.invoke(null,RuntimeEnvironment.getApplication()),"NATIVE"));
        Bundle wire=new Bundle();wire.putString("channels",json(true,true));wire.putString("identity","a".repeat(64));wire.putBoolean("active",false);
        PolicyProvider.response=wire;
        assertFalse(enabled(read.invoke(null,RuntimeEnvironment.getApplication()),"OCR"));
        wire.putBoolean("active",true);
        assertTrue(enabled(read.invoke(null,RuntimeEnvironment.getApplication()),"OCR"));
        wire.putString("channels","{}");
        assertFalse(enabled(read.invoke(null,RuntimeEnvironment.getApplication()),"OCR"));
    }

    @Test public void rawNativeDragTextCannotBypassTheDisabledNativeChannel() throws Exception {
        Catcher result=new Catcher();Bundle text=new Bundle();text.putString("viewContent","https://native.example/hidden");
        result.jobs.put("text_pick",Map.of("pick_mode",text));
        Object channels=channels(false,true);
        Method accepted=find(policyClass(),"nativeText",Object.class,channels.getClass());
        assertNull(accepted.invoke(null,result,channels));
        result.properties.put("taplus_scope_text","https://assist.example/visible");
        assertEquals("https://assist.example/visible",accepted.invoke(null,result,channels));
    }

    @Test public void nativeOnAssistOffDoesNotImportModuleOrSyntheticMetadataAsNative() throws Exception {
        var enhancer=new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
        Catcher result=new Catcher();
        Bundle synthetic=new Bundle();synthetic.putBoolean("taplus_scope_capture",true);
        synthetic.putString("viewContent","https://synthetic.example/hidden");
        result.jobs.put("text_pick",Map.of("pick_mode",synthetic));
        result.properties.put("taplus_scope_text","https://assist.example/disabled");
        result.properties.put("taplus_native_text","https://native.example/allowed");
        result.properties.put("taplus_unknown","https://metadata.example/hidden");
        enhancer.capture(result,RecognitionChannels.defaults().with(Channel.ASSIST,false));
        assertEquals(List.of("https://native.example/allowed"),enhancer.capturedLinks());
    }

    @Test public void disabledNativeTextIsFilteredBeforeTheHostCanSendItToAnotherRecognizer() throws Exception {
        Catcher result=new Catcher();Bundle nativeText=new Bundle();
        nativeText.putString("viewContent","https://native.example/hidden");
        nativeText.putStringArray("apkParamList",new String[]{"miuiCE_secret"});
        nativeText.putString("secret","https://native.example/other");
        nativeText.putParcelable("view_rect",new Rect(1,2,100,200));
        result.jobs.put("text_pick",Map.of("pick_mode",nativeText));
        result.properties.put("taplus_scope_text","允许正文 https://assist.example/allowed");
        result.properties.put("taplus_scope_link_url","https://probe.example/allowed");
        RecognitionChannels choices=RecognitionChannels.defaults().with(Channel.NATIVE,false);
        RecognitionPolicy.filterText(result,choices);
        assertEquals("允许正文 https://assist.example/allowed\nhttps://probe.example/allowed",HostScopeCaptureHooks.nativeText(result));
        Bundle filtered=(Bundle)result.getParams("text_pick").get("pick_mode");
        assertEquals(new Rect(1,2,100,200),filtered.getParcelable("view_rect"));
        assertFalse(filtered.containsKey("apkParamList"));assertFalse(filtered.containsKey("secret"));
        result.properties.clear();RecognitionPolicy.filterText(result,RecognitionChannels.disabled());
        assertNull(HostScopeCaptureHooks.nativeText(result));
    }

    @Test public void stockSegmentWordsCannotReviveNativeLinksButAllowedOcrAndAssistRemain() throws Exception {
        Catcher result=new Catcher();result.jobs.put("text_pick",Map.of("pick_mode","https://native.example/hidden"));
        var choices=RecognitionChannels.defaults().with(Channel.NATIVE,false).with(Channel.OCR,false);
        assertEquals("",RecognitionPolicy.recognizedText(result,false,"https://native.example/hidden",choices));
        result.properties.put("taplus_scope_text","允许正文 https://assist.example/allowed");
        assertEquals("允许正文 https://assist.example/allowed",RecognitionPolicy.recognizedText(result,false,"https://native.example/hidden",choices));
        assertEquals("OCR https://ocr.example/allowed",RecognitionPolicy.recognizedText(result,true,
                "OCR https://ocr.example/allowed",choices.with(Channel.OCR,true)));
    }

    @Test public void filteredDragForwardingKeepsAcceptedLinksWithoutCountingThemAsANewSource() throws Exception {
        var enhancer=new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
        var events=new ArrayList<String>();enhancer.setReporter(events::add);
        Catcher result=new Catcher();Bundle forwarded=new Bundle();forwarded.putBoolean("taplus_drag",true);
        forwarded.putString("viewContent","https://assist.example/already-filtered");
        result.jobs.put("text_pick",Map.of("pick_mode",forwarded));
        enhancer.capture(result,RecognitionChannels.disabled());
        assertEquals(List.of("https://assist.example/already-filtered"),enhancer.capturedLinks());
        assertFalse(events.stream().anyMatch(event->event.startsWith("recognition source ")));
    }

    @Test public void emptyAndRepeatedServerEventsCannotHideTheFirstLateOcrSource() throws Exception {
        var enhancer=new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
        var events=new ArrayList<String>();enhancer.setReporter(events::add);
        for(int i=0;i<100;i++)enhancer.captureText("没有链接","native");
        for(int i=0;i<100;i++)enhancer.captureText("https://server.example/same","server");
        enhancer.captureText("https://ocr.example/late","ocr");
        var sources=events.stream().filter(event->event.startsWith("recognition source ")).toList();
        assertTrue(sources.size()<=32);
        assertTrue(sources.stream().anyMatch(event->event.contains("channel=ocr found=1 added=1 total=2")));
    }

    @Test public void inactivePolicyDisablesCaptureWithoutChangingTheOriginalHostConsumer() throws Exception {
        Catcher result=new Catcher();Bundle original=new Bundle();original.putString("viewContent","https://native.example/original");
        result.jobs.put("text_pick",Map.of("pick_mode",original));
        var unmanaged=new RecognitionPolicy.State(RecognitionChannels.disabled(),false);
        RecognitionPolicy.filterText(result,unmanaged);
        assertSame(original,result.getParams("text_pick").get("pick_mode"));
        assertEquals("https://native.example/original",HostScopeCaptureHooks.nativeText(result));
        assertFalse(unmanaged.disabled(Channel.OCR));assertFalse(unmanaged.disabled(Channel.SERVER));
        assertFalse(unmanaged.channels().enabled(Channel.NATIVE));
    }

    @Test public void clipboardCommerceNeedsBothManagedSourcesButLeavesUnmanagedStockAlone() {
        assertFalse(new RecognitionPolicy.State(RecognitionChannels.defaults(),true).commerceDisabled());
        assertTrue(new RecognitionPolicy.State(RecognitionChannels.defaults().with(Channel.SERVER,false),true).commerceDisabled());
        assertTrue(new RecognitionPolicy.State(RecognitionChannels.defaults().with(Channel.CLIPBOARD,false),true).commerceDisabled());
        assertFalse(new RecognitionPolicy.State(RecognitionChannels.disabled(),false).commerceDisabled());
    }

    @Test public void disabledSegmentsNeverSkipAnEnabledImageOcrRequestOrWaitForDisabledOcr() {
        assertFalse(HostDragHooks.shouldCompleteWithoutSegments(true,false,true,false));
        assertTrue(HostDragHooks.shouldCompleteWithoutSegments(true,false,true,true));
        assertTrue(HostDragHooks.shouldCompleteWithoutSegments(true,false,false,false));
        assertTrue(HostDragHooks.shouldCompleteWithoutSegments(false,false,true,false));
        assertFalse(HostDragHooks.shouldCompleteWithoutSegments(false,true,true,true));
    }

    @Test public void rememberedOcrBodyKeepsPlainTextAndCannotExceedTheCurrentPickBudget() {
        assertEquals("图片上的普通正文",RecognitionPolicy.boundedText("图片上的普通正文"));
        assertEquals("",RecognitionPolicy.boundedText(null));
        assertEquals("a".repeat(131072),RecognitionPolicy.boundedText("a".repeat(131072)+"private tail"));
    }

    private static Class<?> policyClass() {
        try{return Class.forName("io.github.yudigaga.taplusbridge.hook.RecognitionPolicy");}
        catch(ClassNotFoundException missing){throw new AssertionError("host recognition channels must be enforced",missing);}
    }
    private static Method find(Class<?> type,String name,Class<?>...args) {
        try {Method method=type.getDeclaredMethod(name,args);method.setAccessible(true);return method;}
        catch(NoSuchMethodException missing){throw new AssertionError("missing recognition channel boundary: "+name,missing);}
    }
    private static Object channels(boolean nativeEnabled,boolean assist) throws Exception {
        Class<?> type=Class.forName("io.github.yudigaga.taplusbridge.config.RecognitionChannels");
        return find(type,"parse",String.class).invoke(null,json(nativeEnabled,assist));
    }
    private static String json(boolean nativeEnabled,boolean assist) {
        return "{\"native\":"+nativeEnabled+",\"assist\":"+assist+",\"ocr\":true,\"server\":true,\"segment\":true,\"clipboard\":true}";
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static boolean enabled(Object channels,String name) throws Exception {
        Class<? extends Enum> type=(Class<? extends Enum>)Class.forName("io.github.yudigaga.taplusbridge.config.RecognitionChannels$Channel");
        return (Boolean)find(channels.getClass(),"enabled",type).invoke(channels,Enum.valueOf(type,name));
    }
    public static final class Catcher {
        final Map<String,Object> properties=new HashMap<>();final Map<String,Map<String,Object>> jobs=new HashMap<>();
        public Map<String,Object> getParams(String name){return jobs.getOrDefault(name,Map.of());}
        public Map<String,Object> getPropertyMap(){return properties;}
        public void addContent(String job,String feature,Object value){
            Map<String,Object> values=new HashMap<>(jobs.getOrDefault(job,Map.of()));
            values.put(feature,value);jobs.put(job,values);
        }
    }
    public static final class PolicyProvider extends ContentProvider {
        static Bundle response;
        public boolean onCreate(){return true;}
        public Bundle call(String method,String arg,Bundle input){return "recognition_channels".equals(method)?response:null;}
        public Cursor query(Uri u,String[]p,String s,String[]a,String o){return null;}
        public String getType(Uri u){return null;}
        public Uri insert(Uri u,ContentValues v){return null;}
        public int delete(Uri u,String s,String[]a){return 0;}
        public int update(Uri u,ContentValues v,String s,String[]a){return 0;}
    }
}
