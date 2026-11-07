package io.github.yudigaga.taplusbridge.translation;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.bridge.BridgeProvider;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, manifest=Config.NONE)
public class TranslationRequestsAndroidTest {
    Context context; ConfigStore config; BridgeProvider provider; int hostUid;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config",0).edit().clear().commit();
        context.getSharedPreferences("translation_private",0).edit().clear().commit();
        config=new ConfigStore(context); hostUid=(Process.myUid()/100000)*100000+10123;
        PackageInfo host=new PackageInfo();host.packageName=BridgeContract.HOST_PACKAGE;host.versionName="5.1.5";host.versionCode=50015;
        host.applicationInfo=new ApplicationInfo();host.applicationInfo.packageName=host.packageName;host.applicationInfo.uid=hostUid;
        host.applicationInfo.enabled=true;host.applicationInfo.flags=ApplicationInfo.FLAG_INSTALLED;
        org.robolectric.shadows.ShadowPackageManager pm=org.robolectric.shadow.api.Shadow.extract(context.getPackageManager());
        pm.installPackage(host);
        pm.setPackagesForUid(hostUid,host.packageName);
        provider=Robolectric.buildContentProvider(BridgeProvider.class).create(BridgeContract.AUTHORITY).get();
    }
    @After public void resetCaller() { ShadowBinder.setCallingUid(Process.myUid()); }
    private Class<?> requests() throws Exception {return Class.forName("io.github.yudigaga.taplusbridge.translation.TranslationRequests");}
    private Intent prepare(String text,String source) throws Exception {
        return (Intent)requests().getMethod("prepare",Context.class,String.class,String.class).invoke(null,context,text,source);
    }
    private Bundle consume(String token) throws Exception {return (Bundle)requests().getMethod("consume",Context.class,String.class).invoke(null,context,token);}
    private void ai() throws Exception {
        Class<?> type=Class.forName("io.github.yudigaga.taplusbridge.translation.TranslationSettings");
        Object defaults=type.getMethod("defaults").invoke(null);
        JSONObject data=new JSONObject((String)type.getMethod("toJson").invoke(defaults));
        data.put("mode","AI");data.put("apiKey","private-sentinel");data.put("model","model-id");
        Object settings=type.getMethod("parse",String.class).invoke(null,data.toString());
        Class<?> store=Class.forName("io.github.yudigaga.taplusbridge.translation.TranslationStore");
        store.getMethod("save",type).invoke(store.getConstructor(Context.class).newInstance(context),settings);
    }
    @Test public void defaultNativeNeverCreatesRequestsAndInvalidTokenCannotExecute() throws Exception {
        assertNull(prepare("hello","example.source")); assertNull(consume("unknown"));
    }
    @Test public void tokenIntentContainsNoTextOrConfigurationAndCanOnlyBeConsumedOnce() throws Exception {
        ai(); Intent intent=prepare("private original","example.source"); assertNotNull(intent);
        assertEquals(BridgeContract.APP_PACKAGE+".translation.TranslationActivity",intent.getComponent().getClassName());
        assertEquals(Set.of("token"),intent.getExtras().keySet()); assertNull(intent.getData());
        String token=intent.getStringExtra("token"); assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        Bundle result=consume(token);assertEquals("private original",result.getString("text"));
        assertEquals("example.source",result.getString("source"));assertNull(consume(token));
        assertFalse(result.containsKey("apiKey"));assertFalse(result.containsKey("endpoint"));
    }
    @Test public void expiredRequestCannotExecuteAndNoOriginalIsPersisted() throws Exception {
        ai();Intent intent=prepare("private original","example.source");
        assertNotNull(intent); ShadowSystemClock.advanceBy(Duration.ofSeconds(31));
        assertNull(consume(intent.getStringExtra("token")));
        for(String name:List.of("config","translation_private","tickets"))
            assertFalse(context.getSharedPreferences(name,0).getAll().toString().contains("private original"));
    }
    @Test public void disabledOrDeniedSourceCannotCreateOrConsumeTranslation() throws Exception {
        ai();config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,List.of(),List.of("example.allowed"));
        assertNull(prepare("hello","example.denied"));
        Intent intent=prepare("hello","example.allowed");assertNotNull(intent);
        config.setEnabled(false);assertNull(consume(intent.getStringExtra("token")));
        assertNull(prepare("hello","example.allowed"));
    }
    @Test public void providerChecksCallerBeforeReturningAnOpaqueTicket() throws Exception {
        ai();Bundle input=new Bundle();input.putString("text","private original");input.putString("source","example.source");
        for(int uid:new int[]{1000,Process.myUid(),hostUid+1}) {
            ShadowBinder.setCallingUid(uid);assertTrue(provider.call("translation_prepare",null,input).isEmpty());
        }
        ShadowBinder.setCallingUid(hostUid);Bundle response=provider.call("translation_prepare",null,input);
        assertNotNull(response.getParcelable("direct"));assertEquals(Set.of("direct"),response.keySet());
    }
    @Test public void providerRejectsCrossUserAndUnsupportedHostBeforeCreatingAnyTicket() throws Exception {
        ai();Bundle input=new Bundle();input.putString("text","hello");input.putString("source","example.source");
        org.robolectric.shadows.ShadowPackageManager pm=org.robolectric.shadow.api.Shadow.extract(context.getPackageManager());
        pm.setPackagesForUid(hostUid+100000,BridgeContract.HOST_PACKAGE);ShadowBinder.setCallingUid(hostUid+100000);
        assertTrue(provider.call("translation_prepare",null,input).isEmpty());
        PackageInfo unsupported=new PackageInfo();unsupported.packageName=BridgeContract.HOST_PACKAGE;
        unsupported.versionName="5.1.6";unsupported.versionCode=50016;unsupported.applicationInfo=new ApplicationInfo();
        unsupported.applicationInfo.packageName=unsupported.packageName;unsupported.applicationInfo.uid=hostUid;
        pm.installPackage(unsupported);ShadowBinder.setCallingUid(hostUid);
        assertEquals("unsupported_host",provider.call("translation_prepare",null,input).getString("reason"));
    }
    @Test public void configurationChangeInvalidatesAnAlreadyIssuedCapability() throws Exception {
        ai();Intent pending=prepare("hello","example.source");assertNotNull(pending);
        new TranslationStore(context).save(TranslationSettings.defaults());
        assertNull(consume(pending.getStringExtra("token")));
    }
    @Test public void explicitHostClipboardTranslationHasNoSourceButStillRequiresHostAuthentication() throws Exception {
        ai();config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,List.of(),List.of("example.allowed"));
        Bundle input=new Bundle();input.putString("text","clipboard words");
        ShadowBinder.setCallingUid(hostUid+1);assertTrue(provider.call("translation_prepare",null,input).isEmpty());
        ShadowBinder.setCallingUid(hostUid);Intent direct=provider.call("translation_prepare",null,input).getParcelable("direct");
        assertNotNull(direct);assertEquals("clipboard words",consume(direct.getStringExtra("token")).getString("text"));
    }
    @Test public void authorizationStopsOldConfigurationFromStartingOrRetryingAnotherRequest() throws Exception {
        ai();TranslationSettings saved=new TranslationStore(context).load();
        assertTrue(TranslationRequests.isCurrent(context,saved,"example.source"));
        new TranslationStore(context).save(TranslationSettings.defaults());
        assertFalse(TranslationRequests.isCurrent(context,saved,"example.source"));
        ai();config.setEnabled(false);assertFalse(TranslationRequests.isCurrent(context,saved,"example.source"));
    }
    @Test public void malformedAndOversizedRequestsCannotExposeCredentialsOrCreateWork() throws Exception {
        ai();assertNull(prepare(" ","example.source"));assertNull(prepare("x".repeat(32769),"example.source"));
        ShadowBinder.setCallingUid(hostUid);Bundle wrong=new Bundle();wrong.putInt("text",4);
        assertTrue(provider.call("translation_prepare",null,wrong).isEmpty());
        wrong.putString("text","hello");wrong.putString("apiKey","attacker-key");wrong.putString("endpoint","https://attacker.example/chat");
        Bundle response=provider.call("translation_prepare",null,wrong);assertEquals(Set.of("direct"),response.keySet());
        Intent direct=response.getParcelable("direct");assertNotNull(direct);
        Bundle payload=consume(direct.getStringExtra("token"));assertEquals(Set.of("text"),payload.keySet());
        assertEquals("hello",payload.getString("text"));
    }
}
