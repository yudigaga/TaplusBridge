package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class RuntimeSnapshotAndroidTest {
    @Test public void cloudAdapterSettingIsAnExplicitBooleanInTheNewFrameworkSchema() throws Exception {
        context.getSharedPreferences("config", 0).edit().putBoolean("cloud_link_handoff", false).commit();
        JSONObject snapshot = new JSONObject(RuntimeSnapshot.from(context));
        assertEquals(7, snapshot.getInt("schema"));
        assertTrue(snapshot.has("cloud_link_handoff"));
        assertEquals(Boolean.FALSE, snapshot.get("cloud_link_handoff"));
    }
    @Test public void changingOnlyTheCloudAdapterInvalidatesTheFrameworkConfigurationIdentity() {
        context.getSharedPreferences("config", 0).edit().putBoolean("cloud_link_handoff", true).commit();
        RuntimeSnapshot before = RuntimeSnapshot.parse(RuntimeSnapshot.from(context));
        context.getSharedPreferences("config", 0).edit().putBoolean("cloud_link_handoff", false).commit();
        RuntimeSnapshot after = RuntimeSnapshot.parse(RuntimeSnapshot.from(context));
        assertNotEquals(before.identity, after.identity);
    }
    @Test public void schemaSevenRequiresItsStrictBooleanAndEveryEarlierSchemaKeepsTheAdapterOff() throws Exception {
        JSONObject current = new JSONObject(RuntimeSnapshot.from(context));
        assertEquals(16, current.length());
        assertTrue(RuntimeSnapshot.parse(current.toString()).cloudLinkHandoff);
        for (Object invalid : List.of("true", 1, JSONObject.NULL, new JSONObject())) {
            String damaged = new JSONObject(current.toString()).put(ConfigStore.CLOUD_LINK_HANDOFF_KEY, invalid).toString();
            assertThrows(IllegalArgumentException.class, () -> RuntimeSnapshot.parse(damaged));
        }
        JSONObject missing = new JSONObject(current.toString()); missing.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        assertThrows(IllegalArgumentException.class, () -> RuntimeSnapshot.parse(missing.toString()));
        missing.put("unknown", false);
        assertThrows(IllegalArgumentException.class, () -> RuntimeSnapshot.parse(missing.toString()));
        for (int schema = 1; schema <= 6; schema++) {
            JSONObject legacy = new JSONObject(current.toString()).put("schema", schema);
            legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
            if (schema < 6) legacy.remove(RecognitionChannels.KEY);
            if (schema < 5) legacy.remove(DragSettings.KEY);
            if (schema < 4) legacy.remove(ConfigStore.SCOPE_LINK_PROBE_KEY);
            if (schema < 3) legacy.remove(ConfigStore.SCOPE_CAPTURE_KEY);
            if (schema < 2) legacy.remove(SourceScopePolicy.KEY);
            assertFalse("schema " + schema, RuntimeSnapshot.parse(legacy.toString()).cloudLinkHandoff);
            String unversioned = new JSONObject(legacy.toString()).put(ConfigStore.CLOUD_LINK_HANDOFF_KEY, true).toString();
            assertThrows(IllegalArgumentException.class, () -> RuntimeSnapshot.parse(unversioned));
        }
    }

    @Test public void theCloudAdapterChoiceSurvivesFrameworkStorageWithoutPrivateModulePreferences() {
        config.setCloudLinkHandoffEnabled(false);
        String raw = RuntimeSnapshot.from(context); RuntimePublisher.store(framework, raw);
        context.getSharedPreferences("config", 0).edit().clear().commit();
        RuntimeSnapshot restored = RuntimeSnapshot.parse(framework.getString(RuntimeSnapshot.KEY, null));
        assertFalse(restored.cloudLinkHandoff);
        assertEquals(RuntimeSnapshot.parse(raw).identity, restored.identity);
    }

    private Context context;private SharedPreferences framework;private ConfigStore config;
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();
        for(String name:List.of("config",RuleRepository.PREFS,"framework_snapshot_test"))context.getSharedPreferences(name,0).edit().clear().commit();
        config=new ConfigStore(context);framework=context.getSharedPreferences("framework_snapshot_test",0);
        new RuleRepository(context).saveManualRule(-1,"com.tencent.mm","https://configured.example/path?private=not-retained","com.example.reader","none","do not share private note");
    }
    @Test public void sharedSnapshotRetainsSourceRulesButNoContentOrNotes(){
        String raw=RuntimeSnapshot.from(context);RuntimeSnapshot snapshot=RuntimeSnapshot.parse(raw);
        assertFalse(snapshot.scopeCapture);
        assertFalse(snapshot.scopeLinkProbe);
        assertTrue(snapshot.usable());assertTrue(snapshot.rules.stream().anyMatch(r->r.host.equals("configured.example") && r.sourcePackage.equals("com.tencent.mm")));
        assertFalse(raw.contains("path?"));assertFalse(raw.contains("not-retained"));assertFalse(raw.contains("private note"));
    }
    @Test public void frameworkValueColdReloadDoesNotNeedModulePrivatePreferences()throws Exception {
        String raw=RuntimeSnapshot.from(context);RuntimePublisher.store(framework,raw);
        File file=new File(context.getApplicationInfo().dataDir,"shared_prefs/framework_snapshot_test.xml");assertTrue(file.isFile());
        var constructor=Class.forName("android.app.SharedPreferencesImpl").getDeclaredConstructor(File.class,int.class);constructor.setAccessible(true);
        SharedPreferences cold=(SharedPreferences)constructor.newInstance(file,0);
        context.getSharedPreferences("config",0).edit().clear().commit();context.getSharedPreferences(RuleRepository.PREFS,0).edit().clear().commit();
        RuntimeSnapshot restored=RuntimeSnapshot.parse(cold.getString(RuntimeSnapshot.KEY,null));
        assertEquals(RuntimeSnapshot.parse(raw).identity,restored.identity);assertTrue(restored.rules.stream().anyMatch(r->r.host.equals("configured.example")));
    }
    @Test public void switchAndRuleChangesReplaceTheWholeConfigurationIdentity(){
        RuntimeSnapshot before=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));config.setCaptureEnabled(false);
        RuntimeSnapshot after=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));assertNotEquals(before.identity,after.identity);assertFalse(after.capture);
        RuntimePublisher.store(framework,after.encode());assertEquals(after.identity,RuntimeSnapshot.parse(framework.getString(RuntimeSnapshot.KEY,null)).identity);
    }
    @Test public void disabledTargetGroupsAreNotReenabledBySharing(){
        new RuleRepository(context).setTargetEnabled("com.example.reader",false);
        assertTrue(RuntimeSnapshot.parse(RuntimeSnapshot.from(context)).rules.stream().noneMatch(r->r.targetPackage.equals("com.example.reader")));
    }
    @Test public void wrongUserSchemaAndCorruptValueAreRejected(){
        String raw=RuntimeSnapshot.from(context);
        for(String invalid:List.of("invalid",raw.replace("\"user\":0","\"user\":10"),raw.replace("\"schema\":"+RuntimeSnapshot.SCHEMA,"\"schema\":99")))
            assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(invalid));
    }
    @Test public void cannotClaimSuccessfulPublicationWhenFrameworkCommitFails(){
        SharedPreferences failure=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            if(!"edit".equals(method.getName()))return method.invoke(framework,args);
            var edit=framework.edit();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,operation,input)->{
                if("commit".equals(operation.getName()))return false;
                Object value=operation.invoke(edit,input);return value instanceof SharedPreferences.Editor?editor:value;
            });
        });
        assertThrows(IllegalStateException.class,()->RuntimePublisher.store(failure,RuntimeSnapshot.from(context)));
    }
    @Test public void failedRemoteCommitCannotSuppressRetryWhenItsLocalCacheAlreadyMatches(){
        config.setScopeLinkProbeEnabled(true);String on=RuntimeSnapshot.from(context);
        config.setDragSettings(config.getDragSettings(),false);String off=RuntimeSnapshot.from(context);
        String[] cached={on},durable={on};int[] commits={0};
        SharedPreferences optimistic=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            if("getString".equals(method.getName())){assertEquals(RuntimeSnapshot.KEY,args[0]);return cached[0];}
            if(!"edit".equals(method.getName()))return method.invoke(framework,args);
            String[] pending={null};
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,operation,input)->{
                if("putString".equals(operation.getName())){assertEquals(RuntimeSnapshot.KEY,input[0]);pending[0]=(String)input[1];return editor;}
                if("commit".equals(operation.getName())){
                    // libxposed updates the local cache before its framework Binder call can fail.
                    cached[0]=pending[0];commits[0]++;
                    if(commits[0]==1)return false;
                    durable[0]=pending[0];return true;
                }
                throw new UnsupportedOperationException(operation.getName());
            });
        });
        assertThrows(IllegalStateException.class,()->RuntimePublisher.store(optimistic,off));
        assertEquals(off,cached[0]);assertEquals(on,durable[0]);assertEquals(1,commits[0]);
        assertTrue(RuntimeSnapshot.parse(durable[0]).scopeLinkProbe);
        RuntimePublisher.store(optimistic,off);
        assertEquals("Retry must reach framework storage despite an already updated local cache",2,commits[0]);
        assertEquals(off,durable[0]);assertFalse(RuntimeSnapshot.parse(durable[0]).scopeLinkProbe);
        RuntimePublisher.store(optimistic,off);
        assertEquals("Each explicit publication must commit instead of trusting the cached value",3,commits[0]);
    }
    @Test public void disabledSupplementalTapSurvivesPrivatePreferenceColdReloadAndSnapshotEncoding()throws Exception {
        config.setScopeLinkProbeEnabled(true);config.setDragSettings(config.getDragSettings(),false);
        File file=new File(context.getApplicationInfo().dataDir,"shared_prefs/config.xml");assertTrue(file.isFile());
        var constructor=Class.forName("android.app.SharedPreferencesImpl").getDeclaredConstructor(File.class,int.class);constructor.setAccessible(true);
        SharedPreferences cold=(SharedPreferences)constructor.newInstance(file,0);
        assertTrue(cold.contains(ConfigStore.SCOPE_LINK_PROBE_KEY));assertFalse(cold.getBoolean(ConfigStore.SCOPE_LINK_PROBE_KEY,true));
        Context coldContext=new android.content.ContextWrapper(context){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return "config".equals(name)?cold:super.getSharedPreferences(name,mode);}
        };
        assertFalse(new ConfigStore(coldContext).scopeLinkProbeEnabled());
        String raw=RuntimeSnapshot.from(coldContext);
        assertEquals(Boolean.FALSE,new JSONObject(raw).get(ConfigStore.SCOPE_LINK_PROBE_KEY));
        assertFalse(RuntimeSnapshot.parse(raw).scopeLinkProbe);
    }
    @Test public void independentRuntimeCanBeExplicitlyRevoked(){
        config.setIndependentEnabled(false);RuntimeSnapshot off=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));assertFalse(off.usable());
    }
    @Test public void selectedSourcePolicyIsIncludedInFrameworkSnapshotAndItsIdentity(){
        RuntimeSnapshot before=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));assertFalse(before.sourceScope.managed);
        config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,Set.of("com.example.blocked"),Set.of("com.example.allowed"));
        RuntimeSnapshot saved=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));
        assertTrue(saved.sourceScope.managed);assertTrue(saved.sourceScope.allows("com.example.allowed"));
        assertFalse(saved.sourceScope.allows("com.example.blocked"));assertNotEquals(before.identity,saved.identity);
        RuntimePublisher.store(framework,saved.encode());context.getSharedPreferences("config",0).edit().clear().commit();
        RuntimeSnapshot restored=RuntimeSnapshot.parse(framework.getString(RuntimeSnapshot.KEY,null));
        assertEquals(saved.identity,restored.identity);assertEquals(Set.of("com.example.blocked"),restored.sourceScope.blacklist);
    }
    @Test public void legacySchemaOneRemainsUnmanagedAndExactFieldCountIsEnforced()throws Exception {
        JSONObject legacy=new JSONObject(RuntimeSnapshot.from(context));legacy.remove(SourceScopePolicy.KEY);
        legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        legacy.remove(DragSettings.KEY);
        legacy.remove(RecognitionChannels.KEY);
        legacy.remove(ConfigStore.SCOPE_CAPTURE_KEY);legacy.remove(ConfigStore.SCOPE_LINK_PROBE_KEY);legacy.put("schema",1);
        RuntimeSnapshot old=RuntimeSnapshot.parse(legacy.toString());assertFalse(old.sourceScope.managed);assertTrue(old.usable());assertFalse(old.scopeCapture);
        assertFalse(old.scopeLinkProbe);
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(new JSONObject(legacy.toString()).put(SourceScopePolicy.KEY,new JSONObject(SourceScopePolicy.unmanaged().encode())).toString()));
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(new JSONObject(legacy.toString()).put("schema",2).toString()));
    }
    @Test public void currentSchemaRejectsMalformedSourcePolicyRatherThanLosingItsRestrictions()throws Exception {
        String raw=RuntimeSnapshot.from(context);
        for(Object policy:List.of("invalid",new JSONObject(SourceScopePolicy.unmanaged().encode()).put("mode","UNKNOWN"),false,JSONObject.NULL)) {
            String invalid=new JSONObject(raw).put(SourceScopePolicy.KEY,policy).toString();
            assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(invalid));
        }
        JSONObject missing=new JSONObject(raw);missing.remove(SourceScopePolicy.KEY);
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(missing.toString()));
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(new JSONObject(raw).put("extra",true).toString()));
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(new JSONObject(raw).put("schema","2").toString()));
    }
    @Test public void currentSchemaCanReadAStringPolicyWithoutChangingItsMeaning()throws Exception {
        SourceScopePolicy policy=SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST,Set.of(),Set.of());
        String raw=new JSONObject(RuntimeSnapshot.from(context)).put(SourceScopePolicy.KEY,policy.encode()).toString();
        RuntimeSnapshot parsed=RuntimeSnapshot.parse(raw);assertTrue(parsed.sourceScope.managed);assertFalse(parsed.sourceScope.allows("com.example.any"));
    }

    @Test public void legacySchemaTwoRetainsItsScopeButNeverEnablesTheNewFallback()throws Exception {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,Set.of("com.example.blocked"),Set.of("com.example.allowed"),true));
        JSONObject legacy=new JSONObject(RuntimeSnapshot.from(context));legacy.put("schema",2);legacy.remove(ConfigStore.SCOPE_CAPTURE_KEY);
        legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        legacy.remove(DragSettings.KEY);
        legacy.remove(RecognitionChannels.KEY);
        legacy.remove(ConfigStore.SCOPE_LINK_PROBE_KEY);
        assertEquals(11,legacy.length());RuntimeSnapshot parsed=RuntimeSnapshot.parse(legacy.toString());
        assertTrue(parsed.sourceScope.managed);assertFalse(parsed.scopeCapture);
        assertFalse(parsed.scopeLinkProbe);
        assertEquals(Set.of("com.example.allowed"),parsed.sourceScope.whitelist);
        assertTrue(parsed.sourceScope.allows("com.example.allowed"));
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(new JSONObject(legacy.toString()).put(ConfigStore.SCOPE_CAPTURE_KEY,true).toString()));
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(new JSONObject(legacy.toString()).put("schema",3).toString()));
    }

    @Test public void schemaThreeRequiresAnExplicitStrictBooleanFallbackAndExactlyTwelveFields()throws Exception {
        JSONObject legacy=new JSONObject(RuntimeSnapshot.from(context));legacy.put("schema",3);legacy.remove(ConfigStore.SCOPE_LINK_PROBE_KEY);
        legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        legacy.remove(DragSettings.KEY);
        legacy.remove(RecognitionChannels.KEY);
        String raw=legacy.toString();assertEquals(12,new JSONObject(raw).length());
        for(Object invalid:List.of("true",1,JSONObject.NULL,new JSONObject())) {
            String changed=new JSONObject(raw).put(ConfigStore.SCOPE_CAPTURE_KEY,invalid).toString();
            assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(changed));
        }
        JSONObject missing=new JSONObject(raw);missing.remove(ConfigStore.SCOPE_CAPTURE_KEY);
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(missing.toString()));
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(new JSONObject(raw).put("unexpected",false).toString()));
    }

    @Test public void fallbackRequiresExplicitOptInAndDisablingItChangesTheSharedIdentity() {
        RuntimeSnapshot before=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));assertFalse(before.scopeCapture);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of(),true));
        RuntimeSnapshot on=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));assertTrue(on.scopeCapture);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of(),false));
        RuntimeSnapshot off=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));assertFalse(off.scopeCapture);
        assertEquals(on.sourceScope.encode(),off.sourceScope.encode());assertNotEquals(on.identity,off.identity);
        RuntimePublisher.store(framework,off.encode());
        assertFalse(RuntimeSnapshot.parse(framework.getString(RuntimeSnapshot.KEY,null)).scopeCapture);
    }

    @Test public void schemaThreeRetainsScreenshotChoiceButCannotEnableTheNewAutomaticLinkProbe()throws Exception {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of(),true,true));
        JSONObject legacy=new JSONObject(RuntimeSnapshot.from(context));legacy.put("schema",3);legacy.remove(ConfigStore.SCOPE_LINK_PROBE_KEY);
        legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        legacy.remove(DragSettings.KEY);
        legacy.remove(RecognitionChannels.KEY);
        RuntimeSnapshot parsed=RuntimeSnapshot.parse(legacy.toString());
        assertTrue(parsed.sourceScope.managed);assertTrue(parsed.scopeCapture);assertFalse(parsed.scopeLinkProbe);
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(new JSONObject(legacy.toString()).put(ConfigStore.SCOPE_LINK_PROBE_KEY,true).toString()));
    }

    @Test public void schemaFourRequiresStrictLinkProbeBooleanAndPreservesIndependentSwitches()throws Exception {
        JSONObject legacy=new JSONObject(RuntimeSnapshot.from(context));legacy.put("schema",4);legacy.remove(DragSettings.KEY);
        legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        legacy.remove(RecognitionChannels.KEY);
        String raw=legacy.toString();assertEquals(13,new JSONObject(raw).length());
        for(Object value:List.of("true",1,JSONObject.NULL,new JSONObject())) {
            String invalid=new JSONObject(raw).put(ConfigStore.SCOPE_LINK_PROBE_KEY,value).toString();
            assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(invalid));
        }
        JSONObject missing=new JSONObject(raw);missing.remove(ConfigStore.SCOPE_LINK_PROBE_KEY);
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(missing.toString()));
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of(),false,true));
        RuntimeSnapshot on=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));assertTrue(on.scopeLinkProbe);assertFalse(on.scopeCapture);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,Set.of(),Set.of(),false,false));
        RuntimeSnapshot off=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));assertFalse(off.scopeLinkProbe);
        assertNotEquals(on.identity,off.identity);assertEquals(on.sourceScope.encode(),off.sourceScope.encode());
    }

    @Test public void schemaFiveRoundTripsAllDragChoicesAndShareOrderThroughFrameworkStorage()throws Exception {
        RuntimeSnapshot before=RuntimeSnapshot.parse(RuntimeSnapshot.from(context));assertFalse(before.drag.enabled);
        config.setDragSettings(new DragSettings(true,"rules","browser","copy","none",true,
                List.of("com.example.second/.Share","com.example.first/.Share")));
        JSONObject legacy=new JSONObject(RuntimeSnapshot.from(context)).put("schema",5);legacy.remove(RecognitionChannels.KEY);
        legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        String raw=legacy.toString();assertEquals(14,new JSONObject(raw).length());
        assertEquals(5,new JSONObject(raw).getInt("schema"));
        RuntimeSnapshot selected=RuntimeSnapshot.parse(raw);
        assertTrue(selected.drag.enabled);assertTrue(selected.drag.shareOnlySelected);
        assertEquals("rules",selected.drag.up);assertEquals("browser",selected.drag.down);
        assertEquals("copy",selected.drag.left);assertEquals("none",selected.drag.right);
        assertEquals(List.of("com.example.second/com.example.second.Share","com.example.first/com.example.first.Share"),selected.drag.shareTargets);
        assertNotEquals(before.identity,selected.identity);
        RuntimePublisher.store(framework,raw);context.getSharedPreferences("config",0).edit().clear().commit();
        RuntimeSnapshot restored=RuntimeSnapshot.parse(framework.getString(RuntimeSnapshot.KEY,null));
        assertEquals(selected.drag.toJson(),restored.drag.toJson());assertEquals(selected.identity,restored.identity);
    }

    @Test public void schemaFourCannotImplicitlyEnableDragOrCarryUnversionedDragSettings()throws Exception {
        config.setDragSettings(new DragSettings(true,"share","share","share","share",true,List.of()));
        JSONObject legacy=new JSONObject(RuntimeSnapshot.from(context));legacy.put("schema",4);legacy.remove(DragSettings.KEY);
        legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        legacy.remove(RecognitionChannels.KEY);
        assertEquals(13,legacy.length());
        RuntimeSnapshot old=RuntimeSnapshot.parse(legacy.toString());
        assertFalse(old.drag.enabled);assertEquals(DragSettings.defaults().toJson(),old.drag.toJson());
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(
                new JSONObject(legacy.toString()).put(DragSettings.KEY,new JSONObject(config.getDragSettings().toJson())).toString()));
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(new JSONObject(legacy.toString()).put("schema",5).toString()));
    }

    @Test public void schemaFiveRejectsUnknownDragActionsFieldsTypesAndMissingConfiguration()throws Exception {
        JSONObject legacy=new JSONObject(RuntimeSnapshot.from(context)).put("schema",5);legacy.remove(RecognitionChannels.KEY);
        legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        String raw=legacy.toString();
        for(String direction:DragSettings.DIRECTIONS) {
            JSONObject drag=new JSONObject(DragSettings.defaults().toJson()).put(direction,"run_shell");
            String invalid=new JSONObject(raw).put(DragSettings.KEY,drag).toString();
            assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(invalid));
        }
        for(Object value:List.of("invalid",false,JSONObject.NULL,
                new JSONObject(DragSettings.defaults().toJson()).put("unexpected",true),
                new JSONObject(DragSettings.defaults().toJson()).put("enabled","true"))) {
            String invalid=new JSONObject(raw).put(DragSettings.KEY,value).toString();
            assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(invalid));
        }
        JSONObject missing=new JSONObject(raw);missing.remove(DragSettings.KEY);
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(missing.toString()));
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(new JSONObject(raw).put("unexpected",false).toString()));
        missing.put("unexpected",false);assertEquals(14,missing.length());
        assertThrows(IllegalArgumentException.class,()->RuntimeSnapshot.parse(missing.toString()));
    }
}
