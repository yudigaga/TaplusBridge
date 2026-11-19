package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RuntimePolicyOperationAndroidTest {
    private static final int HOST_UID=21001, MODULE_UID=21002;
    // Match RuntimeServer.current(): retained metadata is queryable without implying installed.
    private static final int MODULE_QUERY_FLAGS=
            PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_UNINSTALLED_PACKAGES;
    private static final String SOURCE="com.tencent.mm";
    private Context context;
    private SharedPreferences stored;
    private ConfigStore config;
    private RuntimeServer server;
    private int preferenceReads;
    private boolean preferencesUnavailable;

    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        for(String name:List.of("config",RuleRepository.PREFS,"framework_policy_operation_test"))
            context.getSharedPreferences(name,0).edit().clear().commit();
        config=new ConfigStore(context);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),true));
        install(BridgeContract.HOST_PACKAGE,HOST_UID,true,"4.1.3",40013);
        install(BridgeContract.APP_PACKAGE,MODULE_UID,true,"1",1);
        install(SOURCE,22001,true,"1",1);
        stored=context.getSharedPreferences("framework_policy_operation_test",0);
        publish();
        server=new RuntimeServer(context,new Handler(Looper.getMainLooper()),()->{
            preferenceReads++;
            if(preferencesUnavailable)throw new IllegalStateException("framework preferences unavailable");
            return stored;
        },s->{});
    }

    @Test public void warmEntryReadsOnceMemoryQueriesReadNothingAndRevalidationReadsOnce() {
        RuntimeSnapshot original=server.sourceCaptureConfiguration();assertNotNull(original);
        preferenceReads=0;
        try(RuntimeServer.PolicyOperation operation=server.beginPolicyOperation()) {
            assertEquals(1,preferenceReads);
            assertTrue(operation.belongsTo(server));
            assertFalse(operation.belongsTo(null));
            assertFalse(operation.belongsTo(new RuntimeServer(context,new Handler(Looper.getMainLooper()),()->stored,s->{})));
            for(int i=0;i<4;i++) {
                assertSame(original,operation.configuration());
                assertTrue(operation.isCurrent());
            }
            assertEquals(1,preferenceReads);
            assertTrue(operation.revalidate());
            assertEquals(2,preferenceReads);
            assertSame(original,operation.configuration());
            assertTrue(operation.isCurrent());
            assertEquals(2,preferenceReads);
        }
    }

    @Test public void closedAndCrossThreadOperationsDenyWithoutReadingPreferences() throws Exception {
        RuntimeServer.PolicyOperation operation=server.beginPolicyOperation();
        assertNotNull(operation.configuration());
        int before=preferenceReads;
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread other=new Thread(()->{
            try {
                assertNull(operation.configuration());
                assertFalse(operation.isCurrent());
                assertFalse(operation.belongsTo(server));
                assertFalse(operation.revalidate());
            }catch(Throwable failed){failure.set(failed);}
        });
        other.setDaemon(true);other.start();other.join(5_000);
        assertFalse(other.isAlive());
        if(failure.get()!=null)throw new AssertionError(failure.get());
        assertEquals(before,preferenceReads);
        assertTrue(operation.isCurrent());
        operation.close();operation.close();
        assertNull(operation.configuration());
        assertFalse(operation.isCurrent());
        assertFalse(operation.belongsTo(server));
        assertFalse(operation.revalidate());
        assertEquals(before,preferenceReads);
    }

    @Test public void observedIdentityAbaCannotReviveAnOlderOperation() {
        String originalRaw=stored.getString(RuntimeSnapshot.KEY,null);
        try(RuntimeServer.PolicyOperation operation=server.beginPolicyOperation()) {
            RuntimeSnapshot original=operation.configuration();assertNotNull(original);
            config.setEnabled(false);publish();
            assertNull(server.sourceCaptureConfiguration());
            assertFalse(operation.isCurrent());assertNull(operation.configuration());
            int before=preferenceReads;
            assertFalse(operation.revalidate());assertEquals(before,preferenceReads);
            stored.edit().putString(RuntimeSnapshot.KEY,originalRaw).commit();
            assertNotNull(server.sourceCaptureConfiguration());
            before=preferenceReads;
            assertFalse(operation.revalidate());assertNull(operation.configuration());
            assertEquals(before,preferenceReads);
            try(RuntimeServer.PolicyOperation recovered=server.beginPolicyOperation()) {
                assertNotNull(recovered.configuration());
                assertEquals(original.identity,recovered.configuration().identity);
                assertTrue(recovered.isCurrent());assertTrue(recovered.revalidate());
            }
        }
    }

    @Test public void missingSnapshotRecoveryOfTheSameRawKeepsTheOldOperationRevoked() {
        assertSameRawRecoveryRevokes(()->stored.edit().remove(RuntimeSnapshot.KEY).commit(),()->{});
        String originalRaw=stored.getString(RuntimeSnapshot.KEY,null);
        stored.edit().remove(RuntimeSnapshot.KEY).commit();
        try(RuntimeServer.PolicyOperation operation=server.beginPolicyOperation()) {
            assertNull(operation.configuration());
            int before=preferenceReads;
            assertFalse(operation.revalidate());assertEquals(before,preferenceReads);
            stored.edit().putString(RuntimeSnapshot.KEY,originalRaw).commit();
            assertNotNull(server.sourceCaptureConfiguration());
            before=preferenceReads;
            assertFalse(operation.revalidate());assertEquals(before,preferenceReads);
        }
    }

    @Test public void malformedSnapshotRecoveryOfTheSameRawKeepsTheOldOperationRevoked() {
        assertSameRawRecoveryRevokes(()->stored.edit().putString(RuntimeSnapshot.KEY,"not-json").commit(),()->{});
    }

    @Test public void missingModuleRecoveryOfTheSameRawKeepsTheOldOperationRevoked() {
        assertSameRawRecoveryRevokes(()->{
            install(BridgeContract.APP_PACKAGE,MODULE_UID,false,"1",1);
            ApplicationInfo module;
            try {
                module=context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE,MODULE_QUERY_FLAGS);
            }catch(PackageManager.NameNotFoundException missing){
                throw new AssertionError("Uninstalled module metadata must remain queryable",missing);
            }
            assertEquals(BridgeContract.APP_PACKAGE,module.packageName);
            assertEquals(MODULE_UID,module.uid);
            assertTrue(module.enabled);
            assertEquals(0,module.flags&ApplicationInfo.FLAG_INSTALLED);
        },()->install(BridgeContract.APP_PACKAGE,MODULE_UID,true,"1",1));
    }

    @Test public void completelyMissingModuleRecoveryOfTheSameRawKeepsTheOldOperationRevoked() {
        assertSameRawRecoveryRevokes(()->{
            ((ShadowPackageManager)Shadow.extract(context.getPackageManager())).removePackage(BridgeContract.APP_PACKAGE);
            assertThrows(PackageManager.NameNotFoundException.class,
                    ()->context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE,MODULE_QUERY_FLAGS));
        },()->install(BridgeContract.APP_PACKAGE,MODULE_UID,true,"1",1));
    }

    @Test public void supplierFailureRecoveryOfTheSameRawKeepsTheOldOperationRevoked() {
        assertSameRawRecoveryRevokes(()->preferencesUnavailable=true,()->preferencesUnavailable=false);
    }

    @Test public void unauthorizedRetentionRequestsNeverBeginAPolicyRead() {
        for(int uid:new int[]{MODULE_UID,22001,121001,1000,0})
            assertEquals("unauthorized",server.handle(uid,"recognition_menu_retain",retentionRequest()).getString("reason"));
        assertEquals(0,preferenceReads);
    }

    @Test public void legacyTwoArgumentRetentionAdapterStillReceivesTheTrustedSnapshot() {
        LegacyCapture capture=new LegacyCapture();server.setSourceCapture(capture);
        RuntimeSnapshot original=server.sourceCaptureConfiguration();assertNotNull(original);
        preferenceReads=0;
        Bundle request=retentionRequest();
        assertTrue(server.handle(HOST_UID,"recognition_menu_retain",request).getBoolean("keep"));
        assertEquals(1,capture.claims);assertSame(original,capture.configuration);
        assertNotSame(request,capture.request);assertEquals(SOURCE,capture.request.getString("source"));
        assertEquals(1,preferenceReads);
    }

    @Test public void explicitRetentionOperationIsClosedAfterSuccessAndAdapterFailure() {
        RuntimeServer.PolicyOperation[] received={null};
        boolean[] fail={false};
        server.setSourceCapture(new LegacyCapture() {
            @Override public Bundle retainRecognition(Bundle request,RuntimeSnapshot config,
                                                       RuntimeServer.PolicyOperation operation) {
                received[0]=operation;
                assertSame(config,operation.configuration());assertTrue(operation.isCurrent());
                if(fail[0])throw new IllegalStateException("capture failed");
                Bundle result=new Bundle();result.putBoolean("keep",true);return result;
            }
        });
        assertTrue(server.handle(HOST_UID,"recognition_menu_retain",retentionRequest()).getBoolean("keep"));
        RuntimeServer.PolicyOperation succeeded=received[0];assertNotNull(succeeded);
        assertNull(succeeded.configuration());assertFalse(succeeded.isCurrent());
        fail[0]=true;
        assertEquals("exception",server.handle(HOST_UID,"recognition_menu_retain",retentionRequest()).getString("reason"));
        RuntimeServer.PolicyOperation failed=received[0];assertNotNull(failed);assertNotSame(succeeded,failed);
        int before=preferenceReads;
        assertNull(failed.configuration());assertFalse(failed.isCurrent());assertFalse(failed.revalidate());
        assertEquals(before,preferenceReads);
    }

    private void assertSameRawRecoveryRevokes(Runnable makeUnavailable,Runnable recover) {
        String originalRaw=stored.getString(RuntimeSnapshot.KEY,null);
        try(RuntimeServer.PolicyOperation operation=server.beginPolicyOperation()) {
            RuntimeSnapshot original=operation.configuration();assertNotNull(original);
            makeUnavailable.run();
            assertNull(server.sourceCaptureConfiguration());
            assertFalse(operation.isCurrent());assertNull(operation.configuration());
            int before=preferenceReads;
            assertFalse(operation.revalidate());assertEquals(before,preferenceReads);
            recover.run();stored.edit().putString(RuntimeSnapshot.KEY,originalRaw).commit();
            assertSame(original,server.sourceCaptureConfiguration());
            before=preferenceReads;
            assertFalse(operation.revalidate());assertNull(operation.configuration());
            assertEquals(before,preferenceReads);
            try(RuntimeServer.PolicyOperation recovered=server.beginPolicyOperation()) {
                assertSame(original,recovered.configuration());
                assertTrue(recovered.isCurrent());assertTrue(recovered.revalidate());
            }
        }
    }

    private void publish() {
        stored.edit().putString(RuntimeSnapshot.KEY,RuntimeSnapshot.from(context)).commit();
    }

    private Bundle retentionRequest() {
        Bundle request=new Bundle();request.putString("source",SOURCE);request.putString("activity",SOURCE+".MainActivity");
        request.putLong("shown_elapsed",SystemClock.elapsedRealtime());return request;
    }

    private void install(String pkg,int uid,boolean installed,String version,int code) {
        PackageInfo info=new PackageInfo();info.packageName=pkg;info.versionName=version;info.setLongVersionCode(code);
        info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=pkg;info.applicationInfo.uid=uid;
        info.applicationInfo.flags=installed?ApplicationInfo.FLAG_INSTALLED:0;
        info.applicationInfo.enabled=true;info.applicationInfo.nonLocalizedLabel=pkg;
        ShadowPackageManager packages=Shadow.extract(context.getPackageManager());
        if(installed)packages.installPackage(info);
        // installPackage() adds FLAG_INSTALLED; retained metadata must keep that flag clear.
        else packages.addPackageNoDefaults(info);
    }

    private static class LegacyCapture implements RuntimeServer.SourceCapture {
        int claims;
        RuntimeSnapshot configuration;
        Bundle request;
        @Override public Bundle take(int owner,Bundle request,RuntimeSnapshot config){return Bundle.EMPTY;}
        @Override public void nativeResult(Bundle request){}
        @Override public Bundle retainRecognition(Bundle input,RuntimeSnapshot config) {
            claims++;configuration=config;request=input;
            Bundle response=new Bundle();response.putBoolean("keep",true);return response;
        }
    }
}
