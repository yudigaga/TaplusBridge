package io.github.yudigaga.taplusbridge.hook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.util.ArrayList;
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
public final class FrozenModuleRecognitionAndroidTest {
    @org.junit.After public void resetTransport()throws Exception {
        var reset=io.github.yudigaga.taplusbridge.runtime.BridgeTransport.class.getDeclaredMethod("testReset");reset.setAccessible(true);reset.invoke(null);
    }
    @Test public void disabledModuleProviderCannotBeRequiredToProduceTheConfiguredCard() throws Exception {
        var context=RuntimeEnvironment.getApplication();
        var provider=Robolectric.buildContentProvider(UnavailableProvider.class).create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY,provider);
        install(context,BridgeContract.APP_PACKAGE,21002,false);
        install(context,BridgeContract.HOST_PACKAGE,21001,true);
        install(context,"com.example.reader",22002,false);
        context.getSharedPreferences("config",0).edit().clear().commit();
        context.getSharedPreferences(io.github.yudigaga.taplusbridge.rules.RuleRepository.PREFS,0).edit().clear().commit();
        new io.github.yudigaga.taplusbridge.rules.RuleRepository(context).saveManualRule(-1,"*","configured.example","com.example.reader","none","");
        var remote=context.getSharedPreferences("remote_test",0);
        remote.edit().putString(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY,io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.from(context)).commit();
        var server=new io.github.yudigaga.taplusbridge.runtime.RuntimeServer(context,new android.os.Handler(android.os.Looper.getMainLooper()),()->remote,new Packages(),s->{});
        var bridge=new io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge.Stub(){public Bundle call(String method,Bundle input){return server.handle(21001,method,input);}};
        var install=io.github.yudigaga.taplusbridge.runtime.RuntimeClient.class.getDeclaredMethod("testBridge",io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge.class);
        install.setAccessible(true);install.invoke(null,bridge);
        io.github.yudigaga.taplusbridge.runtime.BridgeTransport.install();
        HostRecognitionEnhancer enhancer=new HostRecognitionEnhancer(Model.class);
        enhancer.captureProbeUrl("https://configured.example/content/1");
        assertEquals(1,enhancer.merge(context,new ArrayList<>(),"com.tencent.mm").size());
    }
    private void install(android.content.Context context,String pkg,int uid,boolean enabled){
        var info=new android.content.pm.PackageInfo();info.packageName=pkg;info.versionName="4.1.3";info.setLongVersionCode(40013);
        info.applicationInfo=new android.content.pm.ApplicationInfo();info.applicationInfo.packageName=pkg;info.applicationInfo.uid=uid;
        info.applicationInfo.enabled=enabled;info.applicationInfo.flags=android.content.pm.ApplicationInfo.FLAG_INSTALLED;
        ((org.robolectric.shadows.ShadowPackageManager)org.robolectric.shadow.api.Shadow.extract(context.getPackageManager())).installPackage(info);
    }
    private static final class Packages extends io.github.yudigaga.taplusbridge.runtime.SystemPackageOps {public boolean ready(){return true;}}
    public static final class Model { public String title,cat,detailUrl,packageName,intent,sourceIcon; }
    public static final class UnavailableProvider extends ContentProvider {
        public boolean onCreate(){return true;}
        public Bundle call(String method,String arg,Bundle input){throw new SecurityException("module disabled");}
        public Cursor query(Uri u,String[]p,String s,String[]a,String o){return null;}
        public String getType(Uri u){return null;}
        public Uri insert(Uri u,ContentValues v){return null;}
        public int delete(Uri u,String s,String[]a){return 0;}
        public int update(Uri u,ContentValues v,String s,String[]a){return 0;}
    }
}
