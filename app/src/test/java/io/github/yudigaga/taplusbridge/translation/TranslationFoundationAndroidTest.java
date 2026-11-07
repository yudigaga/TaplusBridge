package io.github.yudigaga.taplusbridge.translation;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.CancellationSignal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, manifest=Config.NONE)
public final class TranslationFoundationAndroidTest {
    private static Class<?> type(String name) {
        try { return Class.forName("io.github.yudigaga.taplusbridge.translation."+name); }
        catch (ClassNotFoundException missing) { throw new AssertionError("translation boundary missing: "+name,missing); }
    }
    private static Method method(Class<?> owner,String name,Class<?>...arguments) {
        try { return owner.getDeclaredMethod(name,arguments); }
        catch (NoSuchMethodException missing) { throw new AssertionError("translation contract missing: "+name,missing); }
    }

    @Test public void defaultsKeepNativeTranslationAndAnExplicitCompleteAiEndpoint() throws Exception {
        Object settings=method(type("TranslationSettings"),"defaults").invoke(null);
        assertEquals("NATIVE",settings.getClass().getField("mode").get(settings).toString());
        assertEquals("https://api.openai.com/v1/chat/completions",settings.getClass().getField("endpoint").get(settings));
        assertEquals("中文",settings.getClass().getField("targetLanguage").get(settings));
        method(settings.getClass(),"validate").invoke(settings);
    }

    @Test public void settingsRequireAnExactSchemaAndNeverExposeApiKeyThroughToString() throws Exception {
        Class<?> settings=type("TranslationSettings");
        Object defaults=method(settings,"defaults").invoke(null);
        String wire=(String)method(settings,"toJson").invoke(defaults);
        JSONObject data=new JSONObject(wire);data.put("apiKey","sentinel-private-api-key");
        Object parsed=method(settings,"parse",String.class).invoke(null,data.toString());
        assertFalse(parsed.toString().contains("sentinel-private-api-key"));
        data.put("unexpected",true);
        InvocationTargetException rejected=assertThrows(InvocationTargetException.class,
                ()->method(settings,"parse",String.class).invoke(null,data.toString()));
        assertTrue(rejected.getCause() instanceof IllegalArgumentException);
    }

    @Test public void privateStoreSavesWithoutPublishingCredentialsToSharedRuntimeConfiguration() throws Exception {
        Class<?> settings=type("TranslationSettings");Class<?> store=type("TranslationStore");
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("translation_private",0).edit().clear().commit();
        Object saved=method(settings,"defaults").invoke(null);
        JSONObject data=new JSONObject((String)method(settings,"toJson").invoke(saved));
        data.put("apiKey","sentinel-private-api-key");
        saved=method(settings,"parse",String.class).invoke(null,data.toString());
        Object repository=store.getConstructor(Context.class).newInstance(context);
        method(store,"save",settings).invoke(repository,saved);
        Object loaded=method(store,"load").invoke(repository);
        assertEquals("sentinel-private-api-key",settings.getField("apiKey").get(loaded));
        assertFalse(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.from(context).contains("sentinel-private-api-key"));
        assertFalse(context.getSharedPreferences("config",0).getAll().containsValue("sentinel-private-api-key"));
    }

    @Test public void appAndAiDispatchersExposeOnlyTheTextOperationAndCancellationBoundary() {
        Class<?> settings=type("TranslationSettings");
        assertEquals(android.content.Intent.class,method(type("TranslationApps"),"intent",Context.class,settings,String.class).getReturnType());
        assertEquals(String.class,method(type("AiTranslationClient"),"translate",settings,String.class,CancellationSignal.class).getReturnType());
    }

    @Test public void activeAiModeRejectsCleartextUserInfoFragmentsAndMissingModelWithoutRewritingTheUrl() throws Exception {
        Class<?> settings=type("TranslationSettings");
        Object defaults=method(settings,"defaults").invoke(null);
        String wire=(String)method(settings,"toJson").invoke(defaults);
        for(String endpoint:new String[]{"http://ai.example/v1/chat/completions","https://user:pass@ai.example/chat","https://ai.example/chat#secret"}) {
            JSONObject data=new JSONObject(wire);data.put("mode","AI");data.put("endpoint",endpoint);
            data.put("apiKey","private-key");data.put("model","model-id");
            Object candidate=method(settings,"parse",String.class).invoke(null,data.toString());
            assertThrows(InvocationTargetException.class,()->method(settings,"validate").invoke(candidate));
        }
        JSONObject data=new JSONObject(wire);data.put("mode","AI");data.put("endpoint","https://ai.example/custom/completion");
        data.put("apiKey","private-key");data.put("model","model-id");
        Object valid=method(settings,"parse",String.class).invoke(null,data.toString());
        method(settings,"validate").invoke(valid);
        assertEquals("https://ai.example/custom/completion",settings.getField("endpoint").get(valid));
        data.put("model","");Object invalid=method(settings,"parse",String.class).invoke(null,data.toString());
        assertThrows(InvocationTargetException.class,()->method(settings,"validate").invoke(invalid));
    }

    @Test public void malformedJsonCannotUseDuplicateFieldsOrTrailingDocuments() throws Exception {
        Class<?> settings=type("TranslationSettings");
        Object defaults=method(settings,"defaults").invoke(null);
        String wire=(String)method(settings,"toJson").invoke(defaults);
        for(String malformed:new String[]{wire+" {}",wire.replace("\"mode\":\"NATIVE\"","\"mode\":\"NATIVE\",\"mode\":\"AI\""),wire.replace("\"schema\"","'schema'")})
            assertThrows(InvocationTargetException.class,()->method(settings,"parse",String.class).invoke(null,malformed));
    }

    @Test public void failedPrivateCommitKeepsThePreviouslySavedProviderAndDoesNotPublishAnyKey() {
        Context context=RuntimeEnvironment.getApplication();
        var preferences=context.getSharedPreferences("translation_private",0);
        preferences.edit().clear().putString("settings",TranslationSettings.defaults().toJson()).commit();
        var failing=(android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences.class.getClassLoader(),new Class<?>[]{android.content.SharedPreferences.class},(target,method,args)->{
                    if(!"edit".equals(method.getName()))return method.invoke(preferences,args);
                    var editor=preferences.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{android.content.SharedPreferences.Editor.class},(proxy,write,values)->{
                                if("commit".equals(write.getName()))return false;
                                Object result=write.invoke(editor,values);
                                return result instanceof android.content.SharedPreferences.Editor?proxy:result;
                            });
                });
        Context wrapped=new android.content.ContextWrapper(context) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){
                assertEquals("translation_private",name);assertEquals(Context.MODE_PRIVATE,mode);return failing;
            }
        };
        TranslationStore store=new TranslationStore(wrapped);
        TranslationSettings ai=new TranslationSettings(TranslationSettings.Mode.AI,"","",TranslationSettings.DEFAULT_ENDPOINT,
                "private-key","model-id","中文",TranslationSettings.DEFAULT_PROMPT);
        assertThrows(IllegalStateException.class,()->store.save(ai));
        assertEquals(TranslationSettings.Mode.NATIVE,new TranslationStore(context).load().mode);
        assertFalse(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.from(context).contains("private-key"));
    }
}
