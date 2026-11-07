package io.github.yudigaga.taplusbridge.config;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class ConfigStoreSourceScopeAndroidTest {
    private Context app;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication(); app.getSharedPreferences("config",0).edit().clear().commit();
    }

    @Test public void missingPreferenceKeepsHostManagementUntilExplicitSave() {
        assertFalse(new ConfigStore(app).sourceScope().managed);
        assertFalse(new ConfigStore(app).scopeCaptureEnabled());
        assertFalse(new ConfigStore(app).scopeLinkProbeEnabled());
    }

    @Test public void wholePolicySurvivesColdDiskRead() throws Exception {
        ConfigStore store = new ConfigStore(app);
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, Set.of("com.example.blocked"), Set.of("com.example.allowed"), true, true));
        File file=new File(app.getApplicationInfo().dataDir,"shared_prefs/config.xml"); assertTrue(file.isFile());
        var ctor=Class.forName("android.app.SharedPreferencesImpl").getDeclaredConstructor(File.class,int.class); ctor.setAccessible(true);
        SharedPreferences cold=(SharedPreferences)ctor.newInstance(file,0);
        SourceScopePolicy value=SourceScopePolicy.parse(cold.getString(SourceScopePolicy.KEY,null));
        assertTrue(value.managed); assertEquals(SourceScopePolicy.Mode.WHITELIST,value.mode);
        assertEquals(Set.of("com.example.blocked"),value.blacklist); assertEquals(Set.of("com.example.allowed"),value.whitelist);
        assertTrue(cold.getBoolean(ConfigStore.SCOPE_CAPTURE_KEY,false));
        assertTrue(cold.getBoolean(ConfigStore.SCOPE_LINK_PROBE_KEY,false));
    }

    @Test public void broadcastFollowsSuccessfulCommitAndContainsNoConfiguration() {
        RecordingContext context = new RecordingContext(app, false);
        assertTrue(new ConfigStore(context).saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of("com.example.blocked"),Set.of(),true,true));
        assertEquals(1,context.sent); assertTrue(context.committedBeforeBroadcast);
        assertTrue(context.fallbackAtBroadcast);
        assertTrue(context.linkProbeAtBroadcast);
        assertEquals(ConfigStore.SOURCE_SCOPE_CHANGED,context.last.getAction());
        assertEquals(HostPolicy.HOST_PACKAGE,context.last.getPackage());
        assertNull(context.last.getExtras()); assertNull(context.last.getData());
    }

    @Test public void failedCommitDoesNotBroadcast() {
        RecordingContext context = new RecordingContext(app, true);
        assertFalse(new ConfigStore(context).saveSourceScope(SourceScopePolicy.Mode.WHITELIST,Set.of(),Set.of(),true,true));
        assertEquals(0,context.sent);
        assertFalse(new ConfigStore(app).scopeCaptureEnabled());
        assertFalse(new ConfigStore(app).scopeLinkProbeEnabled());
        assertFalse(app.getSharedPreferences("config",0).contains(SourceScopePolicy.KEY));
    }

    @Test public void secondaryUserCannotSaveACompetingGlobalPolicy() {
        RecordingContext context = new RecordingContext(app, false);
        ConfigStore store = new ConfigStore(context);
        int originalUid = android.os.Process.myUid();
        try {
            for (int user : new int[]{10, 999}) {
                org.robolectric.shadows.ShadowProcess.setUid(user * 100000 + 10000);
                IllegalStateException rejected = assertThrows(IllegalStateException.class,
                        () -> store.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, Set.of(), Set.of("com.example.allowed")));
                assertEquals("请在主用户中设置传送门的生效应用", rejected.getMessage());
                assertThrows(IllegalStateException.class,
                        () -> store.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, Set.of(), Set.of("com.example.allowed"), true));
                assertThrows(IllegalStateException.class,
                        () -> store.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, Set.of(), Set.of("com.example.allowed"), false, true));
                assertEquals(0, context.sent);
                assertFalse(app.getSharedPreferences("config", 0).contains(SourceScopePolicy.KEY));
                assertFalse(store.sourceScope().managed);
                assertFalse(store.scopeCaptureEnabled());
                assertFalse(store.scopeLinkProbeEnabled());
            }
        } finally { org.robolectric.shadows.ShadowProcess.setUid(originalUid); }
    }

    @Test public void invalidSavedSelectionDoesNotBecomeUnmanaged() {
        app.getSharedPreferences("config",0).edit().putString(SourceScopePolicy.KEY,"{bad}").commit();
        assertThrows(IllegalArgumentException.class,()->new ConfigStore(app).sourceScope());
        assertThrows(IllegalArgumentException.class,()->new ConfigStore(app).saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of("bad;package"),Set.of()));
        assertEquals("{bad}",app.getSharedPreferences("config",0).getString(SourceScopePolicy.KEY,null));
        app.getSharedPreferences("config",0).edit().putBoolean(SourceScopePolicy.KEY,true).commit();
        assertThrows(IllegalArgumentException.class,()->new ConfigStore(app).sourceScope());
    }

    @Test public void legacySelectionSavesNeverEnableOrOverwriteTheFallbackChoice() {
        ConfigStore store=new ConfigStore(app);
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of()));
        assertFalse(store.scopeCaptureEnabled());
        assertFalse(app.getSharedPreferences("config",0).contains(ConfigStore.SCOPE_CAPTURE_KEY));
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of(),true));
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,Set.of(),Set.of("com.example.allowed")));
        assertTrue(new ConfigStore(app).scopeCaptureEnabled());
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,Set.of(),Set.of("com.example.allowed"),false));
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of()));
        assertFalse(store.scopeCaptureEnabled());
    }

    @Test public void preferenceObserversSeeTheModeAndFallbackInTheSameCommittedState() {
        SharedPreferences preferences=app.getSharedPreferences("config",0);
        java.util.List<Boolean> observed=new java.util.ArrayList<>();
        SharedPreferences.OnSharedPreferenceChangeListener listener=(saved,key)->observed.add(
                saved.contains(SourceScopePolicy.KEY) && saved.getBoolean(ConfigStore.SCOPE_CAPTURE_KEY,false));
        preferences.registerOnSharedPreferenceChangeListener(listener);
        try {
            assertTrue(new ConfigStore(app).saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of(),true));
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertFalse(observed.isEmpty());assertTrue(observed.stream().allMatch(Boolean::booleanValue));
        } finally { preferences.unregisterOnSharedPreferenceChangeListener(listener); }
    }

    @Test public void aMalformedFallbackPreferenceCannotEnableTheNewCapturePath() {
        app.getSharedPreferences("config",0).edit().putString(ConfigStore.SCOPE_CAPTURE_KEY,"true").commit();
        app.getSharedPreferences("config",0).edit().putString(ConfigStore.SCOPE_LINK_PROBE_KEY,"true").commit();
        assertFalse(new ConfigStore(app).scopeCaptureEnabled());
        assertEquals("true",app.getSharedPreferences("config",0).getString(ConfigStore.SCOPE_CAPTURE_KEY,null));
        assertFalse(new ConfigStore(app).scopeLinkProbeEnabled());
        assertEquals("true",app.getSharedPreferences("config",0).getString(ConfigStore.SCOPE_LINK_PROBE_KEY,null));
    }

    @Test public void linkProbeIsIndependentAndLegacyOverloadsNeverOverwriteItsChoice() {
        ConfigStore store=new ConfigStore(app);
        assertFalse(store.scopeLinkProbeEnabled());
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of(),false,true));
        assertTrue(store.scopeLinkProbeEnabled());assertFalse(store.scopeCaptureEnabled());
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of(),true));
        assertTrue(store.scopeLinkProbeEnabled());assertTrue(store.scopeCaptureEnabled());
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,Set.of(),Set.of("com.example.allowed")));
        assertTrue(store.scopeLinkProbeEnabled());assertTrue(store.scopeCaptureEnabled());
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,Set.of(),Set.of("com.example.allowed"),true,false));
        assertFalse(new ConfigStore(app).scopeLinkProbeEnabled());assertTrue(store.scopeCaptureEnabled());
    }

    @Test public void supplementalTapSetterPreservesSavedScopeAndScreenshotChoice() throws Exception {
        RecordingContext context = new RecordingContext(app, false);
        ConfigStore store = new ConfigStore(context);
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, Set.of("com.example.blocked"), Set.of("com.example.allowed"), true));
        String scope = store.sourceScope().encode(); context.sent = 0;
        java.lang.reflect.Method setter;
        try { setter = ConfigStore.class.getMethod("setScopeLinkProbeEnabled", boolean.class); }
        catch (NoSuchMethodException missing) { throw new AssertionError("补点开关必须可以独立保存", missing); }
        setter.invoke(store, true);
        assertTrue(new ConfigStore(app).scopeLinkProbeEnabled());
        assertEquals(scope, new ConfigStore(app).sourceScope().encode());
        assertTrue(new ConfigStore(app).scopeCaptureEnabled()); assertEquals(1, context.sent);
        assertTrue(context.linkProbeAtBroadcast);
        assertTrue(store.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, Set.of(), Set.of(), false));
        assertTrue(store.scopeLinkProbeEnabled());
    }

    @Test public void gestureAndSupplementalTapSaveCommitTogether() throws Exception {
        ConfigStore store = new ConfigStore(app);
        DragSettings desired = new DragSettings(false, "share", "portal", "lens", "translate", false, java.util.List.of());
        java.lang.reflect.Method setter;
        try { setter = ConfigStore.class.getMethod("setDragSettings", DragSettings.class, boolean.class); }
        catch (NoSuchMethodException missing) { throw new AssertionError("手势保存必须同时提交补点开关", missing); }
        SharedPreferences preferences = app.getSharedPreferences("config", 0);
        java.util.List<String> observed = new java.util.ArrayList<>();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (saved, key) -> observed.add(
                saved.getString(DragSettings.KEY, "") + "|" + saved.getBoolean(ConfigStore.SCOPE_LINK_PROBE_KEY, false));
        preferences.registerOnSharedPreferenceChangeListener(listener);
        try { setter.invoke(store, desired, true); org.robolectric.shadows.ShadowLooper.idleMainLooper(); }
        finally { preferences.unregisterOnSharedPreferenceChangeListener(listener); }
        assertFalse(observed.isEmpty());
        for (String value : observed) assertEquals(desired.toJson() + "|true", value);
        store.setDragSettings(DragSettings.defaults());
        assertTrue(store.scopeLinkProbeEnabled());
    }

    @Test public void failedGestureOrSupplementalTapCommitKeepsBothStoredValuesAndDoesNotBroadcast() {
        ConfigStore original = new ConfigStore(app);
        original.setDragSettings(DragSettings.defaults());
        original.setScopeLinkProbeEnabled(true);
        String drag = original.getDragSettings().toJson();
        RecordingContext failing = new RecordingContext(app, true);
        ConfigStore store = new ConfigStore(failing);
        DragSettings changed = new DragSettings(true, "share", "portal", "lens", "translate", false, java.util.List.of());
        assertThrows(IllegalStateException.class, () -> store.setDragSettings(changed, false));
        assertThrows(IllegalStateException.class, () -> store.setScopeLinkProbeEnabled(false));
        assertEquals(drag, original.getDragSettings().toJson());
        assertTrue(original.scopeLinkProbeEnabled()); assertEquals(0, failing.sent);
    }

    @Test public void failedLensCommitKeepsTheSavedChoiceAndDoesNotBroadcast() {
        ConfigStore original = new ConfigStore(app); original.setLensEnabled(true);
        RecordingContext failing = new RecordingContext(app, true);
        ConfigStore store = new ConfigStore(failing);
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> store.setLensEnabled(false));
        assertEquals("识图设置保存失败，请重试", rejected.getMessage());
        assertTrue(original.lensEnabled()); assertEquals(0, failing.sent);
    }

    private static final class RecordingContext extends ContextWrapper {
        final boolean fail; int sent; Intent last; boolean committedBeforeBroadcast,fallbackAtBroadcast,linkProbeAtBroadcast;
        RecordingContext(Context app,boolean fail) {super(app);this.fail=fail;}
        @Override public Context getApplicationContext(){return this;}
        @Override public void sendBroadcast(Intent intent){
            sent++;last=new Intent(intent);committedBeforeBroadcast=super.getSharedPreferences("config",0).contains(SourceScopePolicy.KEY);
            fallbackAtBroadcast=super.getSharedPreferences("config",0).getBoolean(ConfigStore.SCOPE_CAPTURE_KEY,false);
            linkProbeAtBroadcast=super.getSharedPreferences("config",0).getBoolean(ConfigStore.SCOPE_LINK_PROBE_KEY,false);
        }
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            SharedPreferences original=super.getSharedPreferences(name,mode);
            if(!fail || !"config".equals(name))return original;
            return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                if(!"edit".equals(method.getName()))return method.invoke(original,args);
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,operation,input)->{
                    if("commit".equals(operation.getName()))return false;
                    return editor;
                });
            });
        }
    }
}
