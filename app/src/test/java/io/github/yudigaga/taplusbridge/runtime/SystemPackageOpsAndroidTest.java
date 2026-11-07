package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.os.UserHandle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowProcess;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class SystemPackageOpsAndroidTest {
    private Context context;private ShadowPackageManager pm;private List<Intent> starts;private SystemPackageOps ops;
    private static final String TARGET="com.example.reader";
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();pm=Shadow.extract(context.getPackageManager());starts=new ArrayList<>();
        ShadowProcess.setUid(1000);install(TARGET,false);install(BridgeContract.APP_PACKAGE,false);
        ops=new SystemPackageOps(context,(intent,user)->starts.add(new Intent(intent)));
        ActivityInfo activity=new ActivityInfo();activity.name=TARGET+".Link";activity.packageName=TARGET;
        activity.applicationInfo=context.getPackageManager().getApplicationInfo(TARGET,android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
        activity.enabled=true;activity.exported=true;pm.addOrUpdateActivity(activity);
        IntentFilter filter=new IntentFilter(Intent.ACTION_VIEW);filter.addDataScheme("https");filter.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(new ComponentName(TARGET,activity.name),filter);
    }
    @After public void reset(){ShadowProcess.setUid(10000);}
    private void install(String pkg,boolean enabled){
        PackageInfo info=new PackageInfo();info.packageName=pkg;info.applicationInfo=new ApplicationInfo();
        info.applicationInfo.packageName=pkg;info.applicationInfo.flags=ApplicationInfo.FLAG_INSTALLED;
        info.applicationInfo.uid=10001;info.applicationInfo.enabled=enabled;pm.installPackage(info);
    }
    private LaunchPlanner.Plan plan(String pkg){return new LaunchPlanner.Plan(pkg,new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://configured.example/a"))
            .setPackage(pkg),null,"com.tencent.mm","域名规则");}
    @Test public void enablesOnlyTargetAndLeavesModuleFrozen()throws Exception {
        ops.thawAndOpen(plan(TARGET),()->true);
        assertTrue(context.getPackageManager().getApplicationInfo(TARGET,0).enabled);
        assertFalse(context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE,android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).enabled);
        assertEquals(1,starts.size());assertEquals(TARGET,starts.get(0).getPackage());
        assertEquals("https://configured.example/a",starts.get(0).getDataString());
    }
    @Test public void launchTimingEventsContainStagesButNoIntentData()throws Exception {
        List<String> events=new ArrayList<>();
        SystemPackageOps measured=new SystemPackageOps(context,
                (intent,user)->starts.add(new Intent(intent)),events::add);
        measured.thawAndOpen(plan(TARGET),()->true);
        String combined=String.join("\n",events);
        assertTrue(combined.contains("runtime perf stage=target_state_read"));
        assertTrue(combined.contains("runtime perf stage=activity_resolve"));
        assertTrue(combined.contains("runtime perf stage=activity_start"));
        assertTrue(combined.contains("runtime perf stage=thaw_open_total"));
        assertFalse(combined.contains("configured.example"));
    }
    @Test public void protectedModuleCannotBeEnabledOrOpened(){
        assertThrows(SecurityException.class,()->ops.thawAndOpen(plan(BridgeContract.APP_PACKAGE),()->true));assertTrue(starts.isEmpty());
    }
    @Test public void cancelledRequestCannotChangePackagesOrOpen(){
        assertThrows(IllegalStateException.class,()->ops.thawAndOpen(plan(TARGET),()->false));assertTrue(starts.isEmpty());
    }
    @Test public void ordinaryProcessCannotUseSystemPrivilege(){
        ShadowProcess.setUid(10000);assertThrows(SecurityException.class,()->ops.thawAndOpen(plan(TARGET),()->true));assertTrue(starts.isEmpty());
    }
    @Test public void hiddenTargetIsUnhiddenButModuleIsNot()throws Exception {
        var hide=context.getPackageManager().getClass().getMethod("setApplicationHiddenSettingAsUser",String.class,boolean.class,UserHandle.class);
        hide.invoke(context.getPackageManager(),TARGET,true,android.os.Process.myUserHandle());
        ops.thawAndOpen(plan(TARGET),()->true);assertEquals(1,starts.size());
        assertFalse(context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE,android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).enabled);
    }
}
