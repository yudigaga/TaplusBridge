package io.github.yudigaga.taplusbridge.config;

import android.content.Context;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Reflection keeps the RED phase compilable before the new public contract exists. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class RecognitionChannelsAndroidTest {
    private static final String KEY="recognition_channels";
    private static final String ALL="{\"native\":true,\"assist\":true,\"ocr\":true,\"server\":true,\"segment\":true,\"clipboard\":true}";
    private Context context;
    private ConfigStore config;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        for(String name:List.of("config",RuleRepository.PREFS))context.getSharedPreferences(name,0).edit().clear().commit();
        config=new ConfigStore(context);
    }

    @Test public void channelsDefaultOnAndWithCannotMutateAnEarlierChoice() {
        Object defaults=call(null,"defaults",new Class<?>[0]);
        Object disabled=call(null,"disabled",new Class<?>[0]);
        for(String name:List.of("NATIVE","ASSIST","OCR","SERVER","SEGMENT","CLIPBOARD")) {
            assertTrue(enabled(defaults,name));assertFalse(enabled(disabled,name));
        }
        Object changed=call(defaults,"with",new Class<?>[]{channelType(),boolean.class},channel("OCR"),false);
        assertFalse(enabled(changed,"OCR"));assertTrue(enabled(defaults,"OCR"));
        assertTrue(enabled(changed,"ASSIST"));
        Object restored=call(null,"parse",new Class<?>[]{String.class},call(changed,"toJson",new Class<?>[0]));
        assertFalse(enabled(restored,"OCR"));assertTrue(enabled(restored,"SERVER"));
    }

    @Test public void channelJsonRejectsMissingUnknownAndNonBooleanChoices()throws Exception {
        for(String malformed:List.of("invalid","{}","[]","null"))
            assertThrows(IllegalArgumentException.class,()->call(null,"parse",new Class<?>[]{String.class},malformed));
        for(String key:List.of("native","assist","ocr","server","segment","clipboard")) {
            JSONObject missing=new JSONObject(ALL);missing.remove(key);
            assertThrows(IllegalArgumentException.class,()->call(null,"parse",new Class<?>[]{String.class},missing.toString()));
            for(Object value:List.of("false",1,JSONObject.NULL,new JSONObject())) {
                String wrong=new JSONObject(ALL).put(key,value).toString();
                assertThrows(IllegalArgumentException.class,()->call(null,"parse",new Class<?>[]{String.class},wrong));
            }
        }
        String extra=new JSONObject(ALL).put("future",true).toString();
        assertThrows(IllegalArgumentException.class,()->call(null,"parse",new Class<?>[]{String.class},extra));
    }

    @Test public void independentChannelAndScreenshotSavesDoNotRewriteScopeGesturesOrOtherSwitches() {
        config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,List.of("com.example.blocked"),List.of("com.example.allowed"));
        config.setDragSettings(new DragSettings(true,"copy","share","portal","none",true,List.of()));
        config.setScopeLinkProbeEnabled(true);config.setLensEnabled(false);
        String scope=config.sourceScope().encode(),drag=config.getDragSettings().toJson();
        Object before=invoke(config,"recognitionChannels",new Class<?>[0]);
        assertTrue(enabled(before,"OCR"));
        invoke(config,"setRecognitionChannel",new Class<?>[]{channelType(),boolean.class},channel("OCR"),false);
        invoke(config,"setRecognitionChannel",new Class<?>[]{channelType(),boolean.class},channel("CLIPBOARD"),false);
        invoke(config,"setScopeCaptureEnabled",new Class<?>[]{boolean.class},true);
        config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,config.sourceScope().blacklist,config.sourceScope().whitelist);
        config.setDragSettings(config.getDragSettings());
        ConfigStore fresh=new ConfigStore(context);
        Object choices=invoke(fresh,"recognitionChannels",new Class<?>[0]);
        assertFalse(enabled(choices,"OCR"));assertFalse(enabled(choices,"CLIPBOARD"));assertTrue(enabled(choices,"ASSIST"));
        assertTrue(enabled(before,"OCR"));assertTrue(fresh.scopeCaptureEnabled());
        assertTrue(fresh.scopeLinkProbeEnabled());assertFalse(fresh.lensEnabled());assertEquals(drag,fresh.getDragSettings().toJson());
        assertEquals(List.of("com.example.allowed"),List.copyOf(fresh.sourceScope().whitelist));
        invoke(config,"setScopeCaptureEnabled",new Class<?>[]{boolean.class},false);
        assertFalse(new ConfigStore(context).scopeCaptureEnabled());
        assertFalse(enabled(invoke(config,"recognitionChannels",new Class<?>[0]),"OCR"));
    }

    @Test public void missingStoredChannelsKeepCompatibilityButDamagedChoicesFailClosed() {
        assertTrue(enabled(invoke(config,"recognitionChannels",new Class<?>[0]),"NATIVE"));
        context.getSharedPreferences("config",0).edit().putString(KEY,"{}").commit();
        for(String name:List.of("NATIVE","ASSIST","OCR","SERVER","SEGMENT","CLIPBOARD"))
            assertFalse(enabled(invoke(new ConfigStore(context),"recognitionChannels",new Class<?>[0]),name));
    }

    @Test public void snapshotCarriesIndependentChoicesAndLegacyFiveKeepsAllSourcesEnabled()throws Exception {
        invoke(config,"setRecognitionChannel",new Class<?>[]{channelType(),boolean.class},channel("SERVER"),false);
        String raw=RuntimeSnapshot.from(context);
        assertEquals(7,new JSONObject(raw).getInt("schema"));
        assertFalse(new JSONObject(raw).getJSONObject(KEY).getBoolean("server"));
        RuntimeSnapshot snapshot=RuntimeSnapshot.parse(raw);
        Object choices=RuntimeSnapshot.class.getField("channels").get(snapshot);
        assertFalse(enabled(choices,"SERVER"));assertTrue(enabled(choices,"OCR"));
        JSONObject legacy=new JSONObject(raw).put("schema",5);legacy.remove(KEY);
        legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        Object old=RuntimeSnapshot.class.getField("channels").get(RuntimeSnapshot.parse(legacy.toString()));
        for(String name:List.of("NATIVE","ASSIST","OCR","SERVER","SEGMENT","CLIPBOARD"))assertTrue(enabled(old,name));
        invoke(config,"setRecognitionChannel",new Class<?>[]{channelType(),boolean.class},channel("SERVER"),true);
        assertNotEquals(snapshot.identity,RuntimeSnapshot.parse(RuntimeSnapshot.from(context)).identity);
    }

    @Test public void schemaSixRequiresExactlySixStrictBooleanChannelChoices()throws Exception {
        JSONObject current=new JSONObject(RuntimeSnapshot.from(context)).put("schema",6).put(KEY,new JSONObject(ALL));
        current.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        RuntimeSnapshot.parse(current.toString());
        JSONObject missing=new JSONObject(current.toString());missing.remove(KEY);
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(missing.toString()));
        for(Object value:List.of(ALL,1,JSONObject.NULL,new JSONObject(),new JSONObject(ALL).put("native","true"))) {
            String damaged=new JSONObject(current.toString()).put(KEY,value).toString();
            assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(damaged));
        }
        String unversioned=new JSONObject(current.toString()).put("schema",5).toString();
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(unversioned));
    }

    private static Class<?> type() {
        try { return Class.forName("io.github.yudigaga.taplusbridge.config.RecognitionChannels"); }
        catch(ClassNotFoundException missing) { throw new AssertionError("Independent recognition channel contract is missing",missing); }
    }
    private static Class<?> channelType() {
        try { return Class.forName("io.github.yudigaga.taplusbridge.config.RecognitionChannels$Channel"); }
        catch(ClassNotFoundException missing) { throw new AssertionError("Recognition channel enum is missing",missing); }
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static Object channel(String name) {
        return Enum.valueOf((Class)channelType(),name);
    }
    private static boolean enabled(Object channels,String name) {
        return (Boolean)call(channels,"enabled",new Class<?>[]{channelType()},channel(name));
    }
    private static Object call(Object target,String method,Class<?>[] types,Object... args) {
        return invoke(target==null?type():target,target,method,types,args);
    }
    private static Object invoke(Object target,String method,Class<?>[] types,Object... args) {
        return invoke(target.getClass(),target,method,types,args);
    }
    private static Object invoke(Object typeOrTarget,Object target,String method,Class<?>[] types,Object... args) {
        Class<?> owner=typeOrTarget instanceof Class<?> clazz?clazz:typeOrTarget.getClass();
        try { return owner.getMethod(method,types).invoke(target,args); }
        catch(InvocationTargetException failed) {
            if(failed.getCause() instanceof RuntimeException invalid)throw invalid;
            throw new AssertionError(failed.getCause());
        } catch(ReflectiveOperationException missing) { throw new AssertionError("Missing contract: "+method,missing); }
    }
}
