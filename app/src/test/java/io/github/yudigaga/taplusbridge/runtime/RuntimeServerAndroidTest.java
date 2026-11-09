package io.github.yudigaga.taplusbridge.runtime;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RuntimeServerAndroidTest {
    static final int HOST_UID=21001, MODULE_UID=21002;
    static final String TARGET="com.example.reader", SOURCE="com.tencent.mm", URL="https://configured.example/content/1?q=kept";
    Context context;SharedPreferences stored;RuntimeServer server;ConfigStore config;
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();
        for(String name:List.of("config",RuleRepository.PREFS,"framework_test"))context.getSharedPreferences(name,0).edit().clear().commit();
        config=new ConfigStore(context);
        new RuleRepository(context).saveManualRule(-1,"*","configured.example",TARGET,"none","");
        install(BridgeContract.HOST_PACKAGE,HOST_UID,true,"4.1.3",40013);
        install(BridgeContract.APP_PACKAGE,MODULE_UID,false,"1",1);
        install(SOURCE,22001,true,"1",1);install(TARGET,22002,false,"1",1);
        stored=context.getSharedPreferences("framework_test",0);publish();
        server=new RuntimeServer(context,new Handler(Looper.getMainLooper()),()->stored,s->{});
        foreground(SOURCE);
    }
    void publish(){stored=context.getSharedPreferences("framework_test",0);stored.edit().putString(RuntimeSnapshot.KEY,RuntimeSnapshot.from(context)).commit();}
    void install(String pkg,int uid,boolean enabled,String version,int code){
        PackageInfo info=new PackageInfo();info.packageName=pkg;info.versionName=version;info.setLongVersionCode(code);
        info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=pkg;info.applicationInfo.uid=uid;
        info.applicationInfo.flags=ApplicationInfo.FLAG_INSTALLED;info.applicationInfo.enabled=enabled;info.applicationInfo.nonLocalizedLabel=pkg;
        ((org.robolectric.shadows.ShadowPackageManager)org.robolectric.shadow.api.Shadow.extract(context.getPackageManager())).installPackage(info);
    }
    void foreground(String pkg){
        ActivityManager.RunningTaskInfo task=new ActivityManager.RunningTaskInfo();task.topActivity=new ComponentName(pkg,pkg+".Main");
        ((org.robolectric.shadows.ShadowActivityManager)org.robolectric.shadow.api.Shadow.extract(context.getSystemService(ActivityManager.class))).setTasks(List.of(task));
        ((org.robolectric.shadows.ShadowPowerManager)org.robolectric.shadow.api.Shadow.extract(context.getSystemService(PowerManager.class))).setIsInteractive(true);
        ((org.robolectric.shadows.ShadowKeyguardManager)org.robolectric.shadow.api.Shadow.extract(context.getSystemService(KeyguardManager.class))).setKeyguardLocked(false);
    }
    Bundle request(){Bundle b=new Bundle();b.putString("url",URL);b.putString("source",SOURCE);b.putBoolean("ephemeral",true);return b;}
    @Test public void configurationLoadReportsTheEffectiveTapSwitchWithoutRecordingSnapshotContents(){
        List<String> events=new ArrayList<>();
        server=new RuntimeServer(context,new Handler(Looper.getMainLooper()),()->stored,events::add);
        config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),false,true);publish();
        server.sourceCaptureConfiguration();server.sourceCaptureConfiguration();
        String prefix="runtime config stage=loaded build="+io.github.yudigaga.taplusbridge.BuildConfig.VERSION_CODE;
        assertEquals(1,events.stream().filter(s->s.equals(prefix+" supplemental_tap=on")).count());
        config.setScopeLinkProbeEnabled(false);publish();server.sourceCaptureConfiguration();
        assertEquals(1,events.stream().filter(s->s.equals(prefix+" supplemental_tap=off")).count());
        assertTrue(events.stream().noneMatch(s->s.contains("configured.example") || s.contains("owner_configuration") || s.contains("nonce=")));
    }

    @Test public void unauthorizedCallersAndOtherProfilesCannotReadConfigurationOrCreateLaunches(){
        for(int uid:new int[]{22001,121001,1000,0})assertEquals("unauthorized",server.handle(uid,"resolve",request()).getString("reason"));
        assertEquals("unauthorized",server.handle(MODULE_UID,"prepare",request()).getString("reason"));
    }
    @Test public void ownerCanVerifyTheExactSystemConfiguration(){
        Bundle status=server.handle(MODULE_UID,"status",null);assertTrue(status.getBoolean("ready"));
        assertEquals(RuntimeSnapshot.parse(stored.getString(RuntimeSnapshot.KEY,null)).identity,status.getString("identity"));
    }

    @Test public void recognitionChannelsAreHostOnly()throws Exception {
        String channels="{\"native\":false,\"assist\":true,\"ocr\":false,\"server\":true,\"segment\":false,\"clipboard\":true}";
        context.getSharedPreferences("config",0).edit().putString("recognition_channels",channels).commit();
        publish();
        Bundle response=server.handle(HOST_UID,"recognition_channels",null);
        assertNotNull(response.getString("channels"));
        assertFalse(new JSONObject(response.getString("channels")).getBoolean("native"));
        assertTrue(response.getBoolean("active"));
        assertEquals(RuntimeSnapshot.parse(stored.getString(RuntimeSnapshot.KEY,null)).identity,response.getString("identity"));
        for(int uid:new int[]{MODULE_UID,22001,121001,1000,0})
            assertEquals("unauthorized",server.handle(uid,"recognition_channels",null).getString("reason"));
        config.setRecognitionEnabled(false);publish();
        assertFalse(server.handle(HOST_UID,"recognition_channels",null).getBoolean("active",true));
        stored.edit().putString(RuntimeSnapshot.KEY,new JSONObject(RuntimeSnapshot.from(context))
                .put("build",io.github.yudigaga.taplusbridge.BuildConfig.VERSION_CODE+1).toString()).commit();
        assertNull(server.handle(HOST_UID,"recognition_channels",null).getString("channels"));
        stored.edit().putString(RuntimeSnapshot.KEY,"{}").commit();
        assertNull(server.handle(HOST_UID,"recognition_channels",null).getString("channels"));
    }

    private Bundle dragRequest() {
        Bundle data=new Bundle();data.putString("source",SOURCE);data.putString("token","A".repeat(43));return data;
    }
    private void enableDrag() {
        config.setDragSettings(new DragSettings(true,"portal","share","lens","translate",false,List.of()));publish();
    }
    private RecordingDragContext recordDragServices() {
        RecordingDragContext recording=new RecordingDragContext(context);
        server=new RuntimeServer(recording,new Handler(Looper.getMainLooper()),()->stored,s->{});return recording;
    }
    static final class RecordingDragContext extends ContextWrapper {
        final List<Intent> services=new ArrayList<>();
        final List<Intent> activities=new ArrayList<>();
        RecordingDragContext(Context context) { super(context); }
        @Override public ComponentName startService(Intent intent) { services.add(new Intent(intent));return intent.getComponent(); }
        @Override public void startActivity(Intent intent) { activities.add(new Intent(intent)); }
    }

    @Test public void dragLaunchIsRestrictedToTheModuleAndOnlyForwardsTheOpaqueToken() {
        enableDrag();RecordingDragContext recording=recordDragServices();
        for(int uid:new int[]{HOST_UID,22001,121002,1000,0})
            assertEquals("unauthorized",server.handle(uid,"drag_launch",dragRequest()).getString("reason"));
        assertTrue(recording.services.isEmpty());
        assertTrue(server.handle(MODULE_UID,"drag_launch",dragRequest()).getBoolean("accepted"));
        assertEquals(1,recording.services.size());
        Intent forwarded=recording.services.get(0);
        assertEquals("io.github.yudigaga.taplusbridge.DRAG_ACTION",forwarded.getAction());
        assertEquals(new ComponentName(BridgeContract.HOST_PACKAGE,"com.miui.contentextension.services.TextContentExtensionService"),forwarded.getComponent());
        assertEquals("A".repeat(43),forwarded.getStringExtra("drag_token"));assertEquals(1,forwarded.getExtras().size());
        assertNull(forwarded.getData());
        assertTrue(server.handle(MODULE_UID,"drag_launch",dragRequest()).getBoolean("accepted"));
        assertEquals(1,recording.services.size());
    }

    @Test public void dragLaunchRequiresBothTheDragSwitchAndModuleMasterSwitch() {
        RecordingDragContext recording=recordDragServices();
        assertEquals("disabled",server.handle(MODULE_UID,"drag_launch",dragRequest()).getString("reason"));
        enableDrag();config.setEnabled(false);publish();
        assertEquals("disabled",server.handle(MODULE_UID,"drag_launch",dragRequest()).getString("reason"));
        assertTrue(recording.services.isEmpty());
    }

    @Test public void dragLaunchHonorsTheConfiguredSourceScopeBeforeStartingTheHost() {
        enableDrag();RecordingDragContext recording=recordDragServices();
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,List.of(),List.of()));publish();
        assertEquals("invalid_request",server.handle(MODULE_UID,"drag_launch",dragRequest()).getString("reason"));
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(SOURCE),List.of()));publish();
        assertEquals("invalid_request",server.handle(MODULE_UID,"drag_launch",dragRequest()).getString("reason"));
        assertTrue(recording.services.isEmpty());
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,List.of(),List.of(SOURCE)));publish();
        assertTrue(server.handle(MODULE_UID,"drag_launch",dragRequest()).getBoolean("accepted"));assertEquals(1,recording.services.size());
    }

    @Test public void dragLaunchRejectsMalformedTokensCallerPayloadAndModuleOrHostSources() {
        enableDrag();RecordingDragContext recording=recordDragServices();
        for(String token:List.of("", "short", "https://example.test/private", " ".repeat(43), "A".repeat(44))) {
            Bundle invalid=dragRequest();invalid.putString("token",token);
            assertEquals("invalid_request",server.handle(MODULE_UID,"drag_launch",invalid).getString("reason"));
        }
        Bundle missing=dragRequest();missing.remove("token");
        assertEquals("invalid_request",server.handle(MODULE_UID,"drag_launch",missing).getString("reason"));
        Bundle wrongType=dragRequest();wrongType.putInt("token",43);
        assertEquals("invalid_request",server.handle(MODULE_UID,"drag_launch",wrongType).getString("reason"));
        Bundle injected=dragRequest();injected.putString("url",URL);
        assertEquals("invalid_request",server.handle(MODULE_UID,"drag_launch",injected).getString("reason"));
        for(String source:List.of(BridgeContract.APP_PACKAGE,BridgeContract.HOST_PACKAGE,"bad;package")) {
            Bundle invalid=dragRequest();invalid.putString("source",source);
            assertEquals("invalid_request",server.handle(MODULE_UID,"drag_launch",invalid).getString("reason"));
        }
        assertTrue(recording.services.isEmpty());
    }

    @Test public void dragActivityStateCanOnlyBeReadByTheSupportedHost() {
        for(int uid:new int[]{MODULE_UID,22001,121001,1000,0})
            assertEquals("unauthorized",server.handle(uid,"drag_active",Bundle.EMPTY).getString("reason"));
        Bundle state=server.handle(HOST_UID,"drag_active",Bundle.EMPTY);
        assertTrue(state.containsKey("active"));assertFalse(state.containsKey("reason"));
    }

    @Test public void physicalDragConfigurationDoesNotRequireScreenshotLinkProbeOrManagedSources() {
        assertNull(server.dragConfiguration());
        enableDrag();  publish();
        RuntimeSnapshot current = server.dragConfiguration();
        assertNotNull(current); assertTrue(current.drag.enabled); assertFalse(current.scopeCapture);
        assertFalse(current.scopeLinkProbe); assertFalse(current.sourceScope.managed);
        assertEquals(RuntimeSnapshot.parse(stored.getString(RuntimeSnapshot.KEY, null)).identity, current.identity);
        assertNull(server.sourceCaptureConfiguration());
    }

    @Test public void physicalDragClaimAndPollAreOnlyAvailableToTheSupportedMainUserHost() {
        enableDrag(); FakeDragGestures gestures = new FakeDragGestures(); server.setDragGestures(gestures);
        Bundle request = dragRequest();
        for (String method : List.of("drag_gesture_claim", "drag_gesture_poll")) {
            for (int uid : new int[]{MODULE_UID, 22001, 121001, 121002, 1000, 0})
                assertEquals("unauthorized", server.handle(uid, method, request).getString("reason"));
            assertEquals(0, gestures.claims + gestures.polls);
            install(BridgeContract.HOST_PACKAGE, HOST_UID, true, "5.1.6", 50016);
            assertEquals("unauthorized", server.handle(HOST_UID, method, request).getString("reason"));
            install(BridgeContract.HOST_PACKAGE, HOST_UID, true, "4.1.3", 40013);
        }
        assertEquals("claim", server.handle(HOST_UID, "drag_gesture_claim", request).getString("adapter"));
        assertEquals("poll", server.handle(HOST_UID, "drag_gesture_poll", request).getString("adapter"));
        assertEquals(1, gestures.claims); assertEquals(1, gestures.polls); assertEquals(0, gestures.consumes);
    }

    @Test public void physicalDragConsumeIsOnlyAvailableToTheSameUserModule() {
        enableDrag(); FakeDragGestures gestures = new FakeDragGestures(); server.setDragGestures(gestures);
        for (int uid : new int[]{HOST_UID, 22001, 121001, 121002, 1000, 0})
            assertEquals("unauthorized", server.handle(uid, "drag_gesture_consume", dragRequest()).getString("reason"));
        assertEquals(0, gestures.consumes);
        Bundle result = server.handle(MODULE_UID, "drag_gesture_consume", dragRequest());
        assertEquals("consume", result.getString("adapter")); assertTrue(result.getBoolean("accepted"));
        assertEquals(1, gestures.consumes); assertEquals(0, gestures.claims + gestures.polls);
    }

    @Test public void overlayTransferAndRoutedMovesAreHostOnlyAndRequireDragConfiguration() {
        enableDrag(); FakeDragGestures gestures = new FakeDragGestures(); server.setDragGestures(gestures);
        Bundle request = dragRequest();
        for (String method : List.of("drag_gesture_overlay_ready", "drag_gesture_update")) {
            for (int uid : new int[]{MODULE_UID, 22001, 121001, 1000, 0})
                assertEquals("unauthorized", server.handle(uid, method, request).getString("reason"));
        }
        assertEquals(0, gestures.transfers + gestures.updates);
        assertTrue(server.handle(HOST_UID, "drag_gesture_overlay_ready", request).getBoolean("accepted"));
        assertTrue(server.handle(HOST_UID, "drag_gesture_update", request).getBoolean("accepted"));
        assertEquals(1, gestures.transfers); assertEquals(1, gestures.updates);
        config.setDragSettings(DragSettings.defaults()); publish();
        assertEquals("disabled", server.handle(HOST_UID, "drag_gesture_overlay_ready", request).getString("reason"));
        assertEquals("disabled", server.handle(HOST_UID, "drag_gesture_update", request).getString("reason"));
        assertEquals(1, gestures.transfers); assertEquals(1, gestures.updates);
    }

    @Test public void timedPrepareAndReleasedLinkRequestsRequireTheAuthenticatedHostAndLiveDragSwitch() {
        enableDrag(); FakeDragGestures gestures = new FakeDragGestures(); server.setDragGestures(gestures);
        for (String method : List.of("drag_gesture_prepare", "drag_gesture_link")) {
            for (int uid : new int[]{MODULE_UID, 22001, 121001, 1000, 0})
                assertEquals("unauthorized", server.handle(uid, method, dragRequest()).getString("reason"));
            assertEquals(method, server.handle(HOST_UID, method, dragRequest()).getString("adapter"));
        }
        assertEquals(1, gestures.prepares); assertEquals(1, gestures.links);
        config.setEnabled(false); publish();
        assertEquals("disabled", server.handle(HOST_UID, "drag_gesture_prepare", dragRequest()).getString("reason"));
        assertEquals("disabled", server.handle(HOST_UID, "drag_gesture_link", dragRequest()).getString("reason"));
        assertEquals(1, gestures.prepares); assertEquals(1, gestures.links);
    }

    @Test public void physicalDragAdaptersAreNotCalledWhenEitherSwitchIsOffOrTheAdapterIsAbsent() {
        FakeDragGestures gestures = new FakeDragGestures(); server.setDragGestures(gestures);
        for (int disabled = 0; disabled < 3; disabled++) {
            if (disabled == 1) { enableDrag(); config.setEnabled(false); publish(); }
            if (disabled == 2) { config.setEnabled(true); publish(); server.setDragGestures(null); }
            assertEquals("disabled", server.handle(HOST_UID, "drag_gesture_claim", dragRequest()).getString("reason"));
            assertEquals("disabled", server.handle(HOST_UID, "drag_gesture_poll", dragRequest()).getString("reason"));
            assertEquals("disabled", server.handle(MODULE_UID, "drag_gesture_consume", dragRequest()).getString("reason"));
        }
        assertEquals(0, gestures.claims + gestures.polls + gestures.consumes);
    }

    @Test public void malformedWrongBuildOrOtherUserSnapshotsNeverReachThePhysicalDragAdapter() throws Exception {
        enableDrag(); FakeDragGestures gestures = new FakeDragGestures(); server.setDragGestures(gestures);
        String valid = stored.getString(RuntimeSnapshot.KEY, null);
        List<String> invalid = List.of("invalid", new JSONObject(valid).put("user", 10).toString(),
                new JSONObject(valid).put("build", io.github.yudigaga.taplusbridge.BuildConfig.VERSION_CODE + 1).toString());
        for (String raw : invalid) {
            stored.edit().putString(RuntimeSnapshot.KEY, raw).commit();
            assertNull(server.dragConfiguration());
            assertEquals("disabled", server.handle(HOST_UID, "drag_gesture_claim", dragRequest()).getString("reason"));
            assertEquals("disabled", server.handle(HOST_UID, "drag_gesture_poll", dragRequest()).getString("reason"));
            assertEquals("disabled", server.handle(MODULE_UID, "drag_gesture_consume", dragRequest()).getString("reason"));
        }
        assertEquals(0, gestures.claims + gestures.polls + gestures.consumes);
    }

    @Test public void physicalDragUsesTheNewestFrameworkIdentityAndConfiguredActionWithoutRewritingTheRequest() {
        enableDrag(); FakeDragGestures gestures = new FakeDragGestures(); server.setDragGestures(gestures);
        Bundle request = dragRequest(); request.putString("direction", "up");
        request.putString("action", "translate"); request.putString("identity", "caller-controlled");
        Bundle initial = server.handle(HOST_UID, "drag_gesture_claim", request);
        assertEquals("portal", initial.getString("action"));
        String previousIdentity = gestures.lastConfig.identity;
        config.setDragSettings(new DragSettings(true, "copy", "share", "lens", "translate", false, List.of())); publish();
        for (String method : List.of("drag_gesture_poll", "drag_gesture_consume")) {
            Bundle response = server.handle("drag_gesture_consume".equals(method) ? MODULE_UID : HOST_UID, method, request);
            assertEquals("copy", response.getString("action"));
            assertEquals(RuntimeSnapshot.parse(stored.getString(RuntimeSnapshot.KEY, null)).identity, gestures.lastConfig.identity);
            assertNotEquals(previousIdentity, gestures.lastConfig.identity);
            assertEquals("caller-controlled", gestures.lastRequest.getString("identity"));
            assertEquals("translate", gestures.lastRequest.getString("action"));
        }
        assertEquals("translate", request.getString("action")); assertEquals("caller-controlled", request.getString("identity"));
    }

    static final class FakeDragGestures implements RuntimeServer.DragGestures {
        int claims, polls, consumes, transfers, updates, prepares, links;
        Bundle lastRequest;
        RuntimeSnapshot lastConfig;
        private Bundle response(String method, Bundle request, RuntimeSnapshot config) {
            lastRequest = new Bundle(request); lastConfig = config;
            Bundle result = new Bundle(); result.putString("adapter", method); result.putBoolean("accepted", true);
            result.putString("identity", config.identity); result.putString("action", config.drag.action(request.getString("direction")));
            return result;
        }
        @Override public Bundle claim(Bundle request, RuntimeSnapshot config) { claims++; return response("claim", request, config); }
        @Override public Bundle poll(Bundle request, RuntimeSnapshot config) { polls++; return response("poll", request, config); }
        @Override public Bundle consume(Bundle request, RuntimeSnapshot config) { consumes++; return response("consume", request, config); }
        @Override public Bundle overlayReady(Bundle request, RuntimeSnapshot config) { transfers++; return response("overlayReady", request, config); }
        @Override public Bundle update(Bundle request, RuntimeSnapshot config) { updates++; return response("update", request, config); }
        public Bundle prepare(Bundle request, RuntimeSnapshot config) { prepares++; return response("drag_gesture_prepare", request, config); }
        public Bundle requestLink(Bundle request, RuntimeSnapshot config) { links++; return response("drag_gesture_link", request, config); }
    }

    private Bundle dragChoiceRequest() {
        Bundle request=new Bundle();request.putString("token","B".repeat(43));return request;
    }

    @Test public void dragChooserCannotBeStartedByTheModuleOrAnUnrelatedCaller() {
        enableDrag();RecordingDragContext recording=recordDragServices();
        for(int uid:new int[]{MODULE_UID,22001,121001,1000,0})
            assertEquals("unauthorized",server.handle(uid,"drag_choose",dragChoiceRequest()).getString("reason"));
        assertTrue(recording.activities.isEmpty());assertTrue(recording.services.isEmpty());
    }

    @Test public void dragChooserOnlyForwardsTheHostsOpaqueResultTokenToTheModuleActivity() {
        enableDrag();install(BridgeContract.APP_PACKAGE,MODULE_UID,true,"1",1);
        RecordingDragContext recording=recordDragServices();
        assertTrue(server.handle(HOST_UID,"drag_choose",dragChoiceRequest()).getBoolean("accepted"));
        assertEquals(1,recording.activities.size());assertTrue(recording.services.isEmpty());
        Intent launched=recording.activities.get(0);
        assertEquals(new ComponentName(BridgeContract.APP_PACKAGE,BridgeContract.APP_PACKAGE+".drag.DragActivity"),launched.getComponent());
        assertEquals("B".repeat(43),launched.getStringExtra("result_token"));assertEquals(1,launched.getExtras().size());
        assertNull(launched.getData());assertNull(launched.getClipData());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,launched.getFlags());
        assertTrue(server.handle(HOST_UID,"drag_choose",dragChoiceRequest()).getBoolean("accepted"));
        assertEquals(1,recording.activities.size());
    }

    @Test public void dragChooserRejectsDisabledConfigurationAndRequestsContainingContentOrActions() {
        RecordingDragContext recording=recordDragServices();
        assertEquals("disabled",server.handle(HOST_UID,"drag_choose",dragChoiceRequest()).getString("reason"));
        enableDrag();config.setEnabled(false);publish();
        assertEquals("disabled",server.handle(HOST_UID,"drag_choose",dragChoiceRequest()).getString("reason"));
        config.setEnabled(true);publish();install(BridgeContract.APP_PACKAGE,MODULE_UID,true,"1",1);
        for(String key:List.of("text","url","action","source")) {
            Bundle injected=dragChoiceRequest();injected.putString(key,"injected");
            assertEquals("invalid_request",server.handle(HOST_UID,"drag_choose",injected).getString("reason"));
        }
        Bundle malformed=dragChoiceRequest();malformed.putString("token","short");
        assertEquals("invalid_request",server.handle(HOST_UID,"drag_choose",malformed).getString("reason"));
        assertEquals("invalid_request",server.handle(HOST_UID,"drag_choose",Bundle.EMPTY).getString("reason"));
        install(BridgeContract.APP_PACKAGE,MODULE_UID,false,"1",1);
        assertEquals("disabled",server.handle(HOST_UID,"drag_choose",dragChoiceRequest()).getString("reason"));
        assertTrue(recording.activities.isEmpty());assertTrue(recording.services.isEmpty());
    }
    @Test public void snapshotParsingIsReusedButFrameworkEditsAreReadImmediately(){
        List<String> events=new ArrayList<>();
        server=new RuntimeServer(context,new Handler(Looper.getMainLooper()),()->stored,events::add);
        assertNotNull(server.handle(HOST_UID,"source_scope",null).getString("policy"));
        events.clear();
        assertNotNull(server.handle(HOST_UID,"source_scope",null).getString("policy"));
        assertFalse(events.stream().anyMatch(line->line.contains("runtime perf stage=snapshot_read ")));
        config.setEnabled(false);publish();events.clear();
        assertNotNull(server.handle(HOST_UID,"source_scope",null).getString("policy"));
        assertTrue(events.stream().anyMatch(line->line.contains("runtime perf stage=snapshot_read ")
                && line.contains("outcome=parsed")));
    }
    @Test public void captureTicketRequiresMatchingNonceUserAndOneTake(){
        config.setScopeLinkProbeEnabled(true);publish();
        Bundle origin=new Bundle();origin.putString("source",SOURCE);
        String nonce=server.handle(HOST_UID,"probe_arm",origin).getString("nonce");assertNotNull(nonce);
        Bundle data=new Bundle();data.putString("nonce",nonce);
        assertNull(server.systemCall(1,"capture_take",data));assertEquals(SOURCE,server.systemCall(0,"capture_take",data).getString("source"));
        assertTrue(server.systemCall(0,"capture_take",data).isEmpty());
    }
    @Test public void captureAndRecognitionSwitchesDisableSyntheticProbe(){
        config.setRecognitionEnabled(false);publish();Bundle b=new Bundle();b.putString("source",SOURCE);
        assertFalse(server.handle(HOST_UID,"capture_available",b).getBoolean("enabled"));
        assertNull(server.handle(HOST_UID,"probe_arm",b).getString("nonce"));
    }

    @Test public void disabledSupplementalTapDeclinesProbeButKeepsManualObservation() {
        Bundle request = new Bundle(); request.putString("source", SOURCE);
        Bundle available = server.handle(HOST_UID, "capture_available", request);
        assertFalse(available.getBoolean("enabled"));
        assertTrue(available.getInt("recognition_delay") > 0);
        assertNull(server.handle(HOST_UID, "probe_arm", request).getString("nonce"));
        String nonce = server.handle(HOST_UID, "capture_arm", request).getString("nonce");
        assertNotNull(nonce);
        Bundle take = new Bundle(); take.putString("nonce", nonce);
        assertEquals(SOURCE, server.systemCall(0, "capture_take", take).getString("source"));
        assertFalse(server.captureValid(nonce, 0));
    }
    @Test public void captureAvailabilityCarriesRecognitionDelayEvenWhenDragIsOff(){
        config.setRecognitionEnabled(true);
        config.setScopeLinkProbeEnabled(true);
        config.setDragSettings(new DragSettings(false, "portal", "share", "lens", "translate", false,
                List.of(), 1_250, 3_500));
        publish(); Bundle request = new Bundle(); request.putString("source", SOURCE);
        Bundle response = server.handle(HOST_UID, "capture_available", request);
        assertTrue(response.getBoolean("enabled"));
        assertEquals(1_250, response.getInt("recognition_delay"));
    }
    @Test public void snapshotChangesRevokeAlreadyArmedSyntheticInput(){
        config.setScopeLinkProbeEnabled(true);publish();
        Bundle origin=new Bundle();origin.putString("source",SOURCE);
        String nonce=server.handle(HOST_UID,"probe_arm",origin).getString("nonce");
        Bundle data=new Bundle();data.putString("nonce",nonce);server.systemCall(0,"capture_take",data);
        assertTrue(server.captureValid(nonce,0));config.setCaptureEnabled(false);publish();assertFalse(server.captureValid(nonce,0));
    }
    @Test public void disablingSupplementalTapRevokesAnArmedRuntimeProbe() {
        config.setScopeLinkProbeEnabled(true); publish();
        Bundle request = new Bundle(); request.putString("source", SOURCE);
        String nonce = server.handle(HOST_UID, "probe_arm", request).getString("nonce");
        Bundle data = new Bundle(); data.putString("nonce", nonce);
        Bundle ticket = server.systemCall(0, "capture_take", data);
        assertTrue(ticket.getBoolean("probe")); assertTrue(server.captureValid(nonce, 0));
        config.setScopeLinkProbeEnabled(false); publish();
        assertFalse(server.captureValid(nonce, 0));
        assertNull(server.handle(HOST_UID, "probe_arm", request).getString("nonce"));
    }
    @Test public void configurationSurvivesRuntimeObjectRestartWithoutProvider(){
        server=new RuntimeServer(context,new Handler(Looper.getMainLooper()),()->stored,s->{});
        assertNotNull(server.handle(HOST_UID,"source_scope",null).getString("policy"));
    }
    @Test public void corruptMissingOrOldBuildSnapshotsNeverUseDefaults(){
        String raw=stored.getString(RuntimeSnapshot.KEY,null);
        for(String value:List.of("broken",raw.replace("\"build\":"+io.github.yudigaga.taplusbridge.BuildConfig.VERSION_CODE,"\"build\":1"))){
            stored.edit().putString(RuntimeSnapshot.KEY,value).commit();assertEquals("runtime_unavailable",server.handle(HOST_UID,"source_scope",null).getString("reason"));
        }
    }
    @Test public void uninstalledModuleCannotProvideSystemConfiguration(){
        ((org.robolectric.shadows.ShadowPackageManager)org.robolectric.shadow.api.Shadow.extract(context.getPackageManager())).removePackage(BridgeContract.APP_PACKAGE);
        assertEquals("runtime_unavailable",server.handle(HOST_UID,"source_scope",null).getString("reason"));
    }

    @Test public void systemStatusPublishesTheAuthenticatedSnapshotInsteadOfLiveOrCallerSuppliedSelections()throws Exception {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(SOURCE),List.of()));
        publish();
        String expectedIdentity=RuntimeSnapshot.parse(stored.getString(RuntimeSnapshot.KEY,null)).identity;
        assertFalse(context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE,
                android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).enabled);
        // Change module-local data without publishing a new framework snapshot.
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(TARGET),List.of()));
        Bundle untrusted=new Bundle();untrusted.putString("policy",SourceScopePolicy.of(
                SourceScopePolicy.Mode.BLACKLIST,List.of("com.example.injected"),List.of()).encode());

        Bundle result=server.handle(MODULE_UID,"status",untrusted);

        assertTrue(result.getBoolean("scope_ready"));
        assertEquals(expectedIdentity,result.getString("identity"));
        assertEquals(List.of(SOURCE),globalSourceBlacklist());
        SourceScopePolicy hostPolicy=SourceScopePolicy.parse(server.handle(HOST_UID,"source_scope",untrusted).getString("policy"));
        assertEquals(java.util.Set.of(SOURCE),hostPolicy.blacklist);
        assertFalse(hostPolicy.allows(SOURCE));assertTrue(hostPolicy.allows(TARGET));
    }

    @Test public void unauthorizedCallersCannotReadOrPublishSourceScope()throws Exception {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(SOURCE),List.of()));publish();
        String previous="{\"taplus_blacklist\":[\"com.example.original\"]}";
        Settings.Global.putString(context.getContentResolver(),SourceScopePublisher.KEY,previous);
        Bundle injected=new Bundle();injected.putString("policy",SourceScopePolicy.of(
                SourceScopePolicy.Mode.BLACKLIST,List.of("com.example.injected"),List.of()).encode());
        for(int uid:new int[]{22001,121001,121002,1000,0}){
            for(String method:List.of("status","source_scope")){
                Bundle response=server.handle(uid,method,injected);
                assertEquals("unauthorized",response.getString("reason"));
                assertNull(response.getString("policy"));
                assertEquals(previous,Settings.Global.getString(context.getContentResolver(),SourceScopePublisher.KEY));
            }
        }
        assertEquals("unauthorized",server.handle(MODULE_UID,"source_scope",injected).getString("reason"));
        assertEquals(previous,Settings.Global.getString(context.getContentResolver(),SourceScopePublisher.KEY));
        assertTrue(server.handle(MODULE_UID,"status",injected).getBoolean("scope_ready"));
        assertEquals(List.of(SOURCE),globalSourceBlacklist());
    }

    @Test public void unmanagedAndLegacySnapshotsKeepTheExistingHostGlobalBlacklist()throws Exception {
        String previous="{\"taplus_blacklist\":[\"com.example.stock\"]}";
        Settings.Global.putString(context.getContentResolver(),SourceScopePublisher.KEY,previous);
        assertTrue(server.handle(MODULE_UID,"status",null).getBoolean("scope_ready"));
        SourceScopePolicy policy=SourceScopePolicy.parse(server.handle(HOST_UID,"source_scope",null).getString("policy"));
        assertFalse(policy.managed);
        assertEquals(previous,Settings.Global.getString(context.getContentResolver(),SourceScopePublisher.KEY));

        JSONObject legacy=new JSONObject(stored.getString(RuntimeSnapshot.KEY,null));
        legacy.remove(ConfigStore.CLOUD_LINK_HANDOFF_KEY);
        legacy.remove("routing_identity");
        legacy.put("native",true).put("independent",true).put("mode","ROOT").put("rules",new JSONArray());
        legacy.put("schema",1);legacy.remove(SourceScopePolicy.KEY);legacy.remove(ConfigStore.SCOPE_CAPTURE_KEY);legacy.remove(ConfigStore.SCOPE_LINK_PROBE_KEY);
        legacy.remove(DragSettings.KEY);legacy.remove(io.github.yudigaga.taplusbridge.config.RecognitionChannels.KEY);
        assertEquals(10,legacy.length());
        stored.edit().putString(RuntimeSnapshot.KEY,legacy.toString()).commit();
        assertTrue(server.handle(MODULE_UID,"status",null).getBoolean("scope_ready"));
        assertFalse(SourceScopePolicy.parse(server.handle(HOST_UID,"source_scope",null).getString("policy")).managed);
        assertEquals(previous,Settings.Global.getString(context.getContentResolver(),SourceScopePublisher.KEY));
    }

    @Test public void sourceScopeWorksWithoutSystemLinkRouting()throws Exception {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(SOURCE),List.of()));
        publish();

        Bundle scope=server.handle(HOST_UID,"source_scope",null);

        assertNull(scope.getString("reason"));
        assertFalse(SourceScopePolicy.parse(scope.getString("policy")).allows(SOURCE));
        assertTrue(server.handle(MODULE_UID,"status",null).getBoolean("scope_ready"));
        assertEquals(List.of(SOURCE),globalSourceBlacklist());
        assertEquals("unsupported_request",server.handle(HOST_UID,"resolve",request()).getString("reason"));
    }

    @Test public void unverifiedSnapshotsCannotPublishSourceScope()throws Exception {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(SOURCE),List.of()));publish();
        String valid=stored.getString(RuntimeSnapshot.KEY,null);
        JSONObject oldBuild=new JSONObject(valid);oldBuild.put("build",1);
        String previous="{\"taplus_blacklist\":[\"com.example.stock\"]}";
        for(String raw:List.of("not-json",oldBuild.toString())){
            Settings.Global.putString(context.getContentResolver(),SourceScopePublisher.KEY,previous);
            stored.edit().putString(RuntimeSnapshot.KEY,raw).commit();
            assertFalse(server.handle(MODULE_UID,"status",null).getBoolean("scope_ready"));
            assertEquals("runtime_unavailable",server.handle(HOST_UID,"source_scope",null).getString("reason"));
            assertEquals(previous,Settings.Global.getString(context.getContentResolver(),SourceScopePublisher.KEY));
        }
    }

    @Test public void screenshotCapabilitiesAndNativeAcknowledgementsOnlyReachTheAuthenticatedHostAdapter() {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),true));publish();
        FakeSourceCapture capture=new FakeSourceCapture();server.setSourceCapture(capture);
        Bundle forged=new Bundle();forged.putString("nonce","a-capture-capability");
        forged.putString("package",BridgeContract.HOST_PACKAGE);forged.putString("source",SOURCE);
        forged.putInt("uid",HOST_UID);forged.putInt("owner",HOST_UID);
        for(int uid:new int[]{MODULE_UID,22001,121001,1000,0}) {
            for(String method:List.of("scope_capture_take","scope_capture_native"))
                assertEquals("unauthorized",server.handle(uid,method,forged).getString("reason"));
        }
        assertEquals(0,capture.takes);assertEquals(0,capture.nativeResults);
        assertTrue(server.handle(MODULE_UID,"status",null).getBoolean("ready"));
        assertEquals(0,capture.takes);assertEquals(0,capture.nativeResults);
        // Even the actual host cannot replace the authenticated owner with request fields.
        forged.putInt("uid",MODULE_UID);forged.putInt("owner",MODULE_UID);
        assertTrue(server.handle(HOST_UID,"scope_capture_take",forged).getBoolean("adapter_reached"));
        assertEquals(1,capture.takes);assertEquals(HOST_UID,capture.lastOwner);
        assertEquals("a-capture-capability",capture.lastRequest.getString("nonce"));
        assertTrue(server.handle(HOST_UID,"scope_capture_native",forged).isEmpty());
        assertEquals(1,capture.nativeResults);assertEquals(SOURCE,capture.lastNativeRequest.getString("source"));
    }

    @Test public void screenshotAdapterReceivesTheLatestTrustedSnapshotRatherThanCallerSuppliedPolicy() {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(TARGET),List.of(),true));publish();
        FakeSourceCapture capture=new FakeSourceCapture();server.setSourceCapture(capture);
        Bundle injected=new Bundle();injected.putString("nonce","capture");
        injected.putString("policy",SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST,List.of(),List.of()).encode());
        server.handle(HOST_UID,"scope_capture_take",injected);
        assertNotNull(capture.lastConfig);assertEquals(SourceScopePolicy.Mode.BLACKLIST,capture.lastConfig.sourceScope.mode);
        assertTrue(capture.lastConfig.scopeCapture);
        assertEquals(java.util.Set.of(TARGET),capture.lastConfig.sourceScope.blacklist);
        assertEquals(RuntimeSnapshot.parse(stored.getString(RuntimeSnapshot.KEY,null)).identity,capture.lastConfig.identity);
        String previous=capture.lastConfig.identity;
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,List.of(TARGET),List.of(SOURCE)));
        publish();server.handle(HOST_UID,"scope_capture_take",injected);
        assertNotEquals(previous,capture.lastConfig.identity);
        assertEquals(SourceScopePolicy.Mode.WHITELIST,capture.lastConfig.sourceScope.mode);
        assertEquals(java.util.Set.of(SOURCE),capture.lastConfig.sourceScope.whitelist);

        config.setEnabled(false);publish();
        assertTrue(server.handle(HOST_UID,"scope_capture_take",injected).isEmpty());
        assertEquals(2,capture.takes);
    }

    @Test public void malformedFrameworkConfigurationIsRejectedBeforeTheScreenshotAdapter() {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),true));publish();
        String valid=stored.getString(RuntimeSnapshot.KEY,null);
        FakeSourceCapture capture=new FakeSourceCapture();server.setSourceCapture(capture);
        Bundle injected=new Bundle();injected.putString("configuration",valid);injected.putString("nonce","capture");
        stored.edit().putString(RuntimeSnapshot.KEY,"invalid configuration").commit();
        assertTrue(server.handle(HOST_UID,"scope_capture_take",injected).isEmpty());
        assertEquals(0,capture.takes);assertNull(capture.lastConfig);
    }

    @Test public void unsupportedHostCannotReadOrCancelScreenshotCapabilities() {
        FakeSourceCapture capture=new FakeSourceCapture();server.setSourceCapture(capture);
        install(BridgeContract.HOST_PACKAGE,HOST_UID,true,"5.1.5",50016);
        for(String method:List.of("scope_capture_take","scope_capture_native"))
            assertEquals("unauthorized",server.handle(HOST_UID,method,new Bundle()).getString("reason"));
        assertEquals(0,capture.takes);assertEquals(0,capture.nativeResults);
    }

    @Test public void managedRecognitionCanReadSourceTextWhileImageAndClickStillRequireTheirOwnSwitches()throws Exception {
        assertNull(server.sourceCaptureConfiguration());
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of()));
        publish();assertNotNull(server.sourceCaptureConfiguration());assertNotNull(server.sourceContentConfiguration());
        assertFalse(server.sourceCaptureConfiguration().scopeCapture);assertFalse(server.sourceCaptureConfiguration().scopeLinkProbe);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),true));
        publish();
        RuntimeSnapshot accepted=server.sourceCaptureConfiguration();assertNotNull(accepted);
        assertTrue(accepted.scopeCapture);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),false));
        publish();assertNotNull(server.sourceCaptureConfiguration());
        config.setRecognitionEnabled(false);publish();assertNull(server.sourceCaptureConfiguration());
        assertNull(server.sourceContentConfiguration());config.setRecognitionEnabled(true);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),true));publish();
        config.setEnabled(false);publish();assertNull(server.sourceCaptureConfiguration());
        config.setEnabled(true);publish();
        JSONObject wrongBuild=new JSONObject(stored.getString(RuntimeSnapshot.KEY,null));
        wrongBuild.put("build",io.github.yudigaga.taplusbridge.BuildConfig.VERSION_CODE+1);
        stored.edit().putString(RuntimeSnapshot.KEY,wrongBuild.toString()).commit();
        assertNull(server.sourceCaptureConfiguration());
    }

    @Test public void absentScreenshotAdapterProducesNoCapabilityAndDoesNotUseModuleComponents() {
        assertTrue(server.handle(HOST_UID,"scope_capture_take",new Bundle()).isEmpty());
        assertTrue(server.handle(HOST_UID,"scope_capture_native",new Bundle()).isEmpty());
    }

    @Test public void linkOwnershipRequiresAnEnabledCurrentOptInAndTheAuthenticatedHost() {
        FakeSourceCapture capture = new FakeSourceCapture(); server.setSourceCapture(capture);
        Bundle request = new Bundle(); request.putString("source", SOURCE); request.putLong("native_elapsed", 20L);
        assertFalse(server.handle(HOST_UID,"scope_link_probe_owns",request).getBoolean("owned"));
        assertEquals(0,capture.ownershipChecks);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),false,true)); publish();
        assertTrue(server.handle(HOST_UID,"scope_link_probe_owns",request).getBoolean("owned"));
        assertEquals(server.sourceCaptureConfiguration().identity,
                server.handle(HOST_UID,"scope_link_probe_owns",request).getString("identity"));
        assertEquals(2,capture.ownershipChecks); assertEquals(SOURCE,capture.lastRequest.getString("source"));
        for (int uid : new int[]{MODULE_UID,22001,121001,1000,0})
            assertEquals("unauthorized",server.handle(uid,"scope_link_probe_owns",request).getString("reason"));
        assertEquals(2,capture.ownershipChecks);
        config.setEnabled(false); publish();
        assertFalse(server.handle(HOST_UID,"scope_link_probe_owns",request).getBoolean("owned"));
        assertEquals(2,capture.ownershipChecks);
    }

    @Test public void linkOnlyConfigurationCanDeliverTextWithoutEnablingScreenshotCapture() {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),false,true)); publish();
        assertNotNull(server.sourceCaptureConfiguration()); assertFalse(server.sourceCaptureConfiguration().scopeCapture);
        FakeSourceCapture capture = new FakeSourceCapture(); server.setSourceCapture(capture);
        assertTrue(server.handle(HOST_UID,"scope_capture_take",new Bundle()).getBoolean("adapter_reached"));
        assertTrue(capture.lastConfig.scopeLinkProbe); assertFalse(capture.lastConfig.scopeCapture);
        Bundle scope = server.handle(HOST_UID,"source_scope",Bundle.EMPTY);
        assertTrue(scope.getBoolean("scope_link_probe_enabled")); assertFalse(scope.getBoolean("scope_capture_enabled"));
        server.setSourceCapture(null);
        assertFalse(server.handle(HOST_UID,"scope_link_probe_owns",new Bundle()).getBoolean("owned"));
    }

    static final class FakeSourceCapture implements RuntimeServer.SourceCapture {
        int takes,nativeResults,lastOwner,ownershipChecks;
        Bundle lastRequest,lastNativeRequest;
        RuntimeSnapshot lastConfig;
        public Bundle take(int owner,Bundle request,RuntimeSnapshot config) {
            takes++;lastOwner=owner;lastRequest=request==null?null:new Bundle(request);lastConfig=config;
            Bundle result=new Bundle();result.putBoolean("adapter_reached",true);return result;
        }
        public void nativeResult(Bundle request) {
            nativeResults++;lastNativeRequest=request==null?null:new Bundle(request);
        }
        public boolean ownsLinkPick(Bundle request) {
            ownershipChecks++; lastRequest = request == null ? null : new Bundle(request); return true;
        }
    }

    private List<String> globalSourceBlacklist()throws Exception {
        JSONObject data=new JSONObject(Settings.Global.getString(context.getContentResolver(),SourceScopePublisher.KEY));
        assertEquals(1,data.length());JSONArray array=data.getJSONArray(SourceScopePublisher.KEY);
        List<String> result=new ArrayList<>();for(int i=0;i<array.length();i++)result.add(array.getString(i));return result;
    }

}
