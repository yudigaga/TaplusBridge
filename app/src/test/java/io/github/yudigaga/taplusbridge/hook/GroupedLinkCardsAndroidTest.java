package io.github.yudigaga.taplusbridge.hook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class GroupedLinkCardsAndroidTest {
    private Context context;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        ShadowContentResolver.registerProviderInternal("io.github.yudigaga.taplusbridge.bridge",
                Robolectric.buildContentProvider(Preview.class).create("io.github.yudigaga.taplusbridge.bridge").get());
    }
    @Test public void severalDownloadLinksForOneTargetBecomeOneCountedSelectionEntry() throws Exception {
        HostRecognitionEnhancer enhancer=new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
        enhancer.captureText("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567\n"
                +"magnet:?xt=urn:btih:1123456789abcdef0123456789abcdef01234567", "clipboard");
        ArrayList<Object> cards=enhancer.merge(context,new ArrayList<>(),null);
        assertEquals(1,cards.size());
        var title=cards.get(0).getClass().getDeclaredField("title");title.setAccessible(true);
        assertTrue(((String)title.get(cards.get(0))).contains("2"));
        var method=HostRecognitionEnhancer.class.getMethod("batchLinks",Object.class);
        assertEquals(enhancer.capturedLinks(),method.invoke(null,cards.get(0)));
        enhancer.clear();assertEquals(List.of(),method.invoke(null,cards.get(0)));
        var owned=HostRecognitionEnhancer.class.getMethod("isOwnedLinkCard",Object.class);
        assertEquals(Boolean.TRUE,owned.invoke(null,cards.get(0)));
    }
    @Test public void largeClipboardBatchIsNotSilentlyReducedToTheFormerEightLinkLimit() throws Exception {
        HostRecognitionEnhancer enhancer=new HostRecognitionEnhancer(HostRecognitionEnhancerTest.FakeModel.class);
        StringBuilder raw=new StringBuilder();
        for(int i=0;i<24;i++)raw.append("magnet:?xt=urn:btih:").append(String.format("%040x",i)).append('\n');
        enhancer.captureText(raw.toString(),"clipboard");
        assertEquals(24,enhancer.capturedLinks().size());
    }
    public static final class Preview extends ContentProvider {
        public boolean onCreate(){return true;}
        public Bundle call(String method,String arg,Bundle extras){
            Bundle value=new Bundle();value.putString("target","com.ylmf.androidclient");value.putString("title","115");return value;
        }
        public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
        public String getType(Uri u){return null;}
        public Uri insert(Uri u,ContentValues v){return null;}
        public int delete(Uri u,String s,String[] a){return 0;}
        public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
    }
}
