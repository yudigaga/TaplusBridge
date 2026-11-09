package io.github.yudigaga.taplusbridge.runtime;

import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class ModuleChannelAndroidTest {
    private Context context;
    private SharedPreferences stored;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config",0).edit().clear().commit();
        install(BridgeContract.APP_PACKAGE,21002,false,"1",1);
        install(BridgeContract.HOST_PACKAGE,21001,true,"5.1.5",50015);
        stored=context.getSharedPreferences("channel_test",0);
        stored.edit().clear().putString(RuntimeSnapshot.KEY,RuntimeSnapshot.from(context)).commit();
    }
    @After public void cleanup() { RuntimeClient.testBridge(null); }
    private void install(String pkg,int uid,boolean enabled,String version,int code) {
        PackageInfo info=new PackageInfo();info.packageName=pkg;info.versionName=version;info.setLongVersionCode(code);
        info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=pkg;info.applicationInfo.uid=uid;
        info.applicationInfo.enabled=enabled;info.applicationInfo.flags=ApplicationInfo.FLAG_INSTALLED;
        ((org.robolectric.shadows.ShadowPackageManager)org.robolectric.shadow.api.Shadow.extract(context.getPackageManager())).installPackage(info);
    }
    @Test public void routingUsesTheModuleProviderEvenIfThePackageIsDisabled() {
        ContentProvider provider=new ContentProvider() {
            public boolean onCreate(){return true;}
            public Bundle call(String method,String arg,Bundle extras){Bundle result=new Bundle();result.putString("origin","module");return result;}
            public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){return null;}
            public String getType(Uri uri){return null;}
            public Uri insert(Uri uri,android.content.ContentValues values){return null;}
            public int delete(Uri uri,String selection,String[] args){return 0;}
            public int update(Uri uri,android.content.ContentValues values,String selection,String[] args){return 0;}
        };
        ProviderInfo info=new ProviderInfo();info.authority=BridgeContract.AUTHORITY;
        provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY,provider);
        RuntimeClient.testBridge(new IRuntimeBridge.Stub() {
            public Bundle call(String method,Bundle request){fail("Module routing must never fall back to system runtime");return null;}
        });
        assertEquals("module",BridgeTransport.call(context,"prepare",new Bundle()).getString("origin"));
    }
    @Test public void systemRuntimeNeverPlansOrTicketsTargetAppLaunches() {
        RuntimeServer server=new RuntimeServer(context,new Handler(Looper.getMainLooper()),()->stored,ignored->{});
        Bundle request=new Bundle();request.putString("url","https://example.com/a");
        for(String method:List.of("prepare","resolve")) {
            Bundle reply=server.handle(21001,method,request);
            assertEquals("unsupported_request",reply.getString("reason"));
            assertFalse(reply.containsKey("direct"));assertFalse(reply.containsKey("system_token"));
        }
    }
    @Test public void normalConfigurationCanBeConfirmedWithoutTargetPackageOperations() {
        RuntimeServer server=new RuntimeServer(context,new Handler(Looper.getMainLooper()),()->stored,ignored->{});
        Bundle status=server.handle(21002,"status",null);
        assertTrue(status.getBoolean("ready"));
        assertEquals(RuntimeSnapshot.parse(stored.getString(RuntimeSnapshot.KEY,null)).identity,status.getString("identity"));
    }
    @Test public void newConfigurationDoesNotPublishIndependentRoutingState() throws Exception {
        JSONObject snapshot=new JSONObject(RuntimeSnapshot.from(context));
        assertEquals(8,snapshot.getInt("schema"));
        for(String key:List.of("independent","native","mode","rules","cloud_link_handoff")) assertFalse(key,snapshot.has(key));
    }
}
