package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Executes only inside system_server. Never enables/unhides the protected module or source host. */
public class SystemPackageOps {
    private final Context context;
    private final Method hidden,setHidden,start;
    private final Starter starter;
    private final Consumer<String> perfLogger;
    interface Starter{void open(Intent intent,UserHandle user)throws Exception;}
    public SystemPackageOps(Context context)throws ReflectiveOperationException {
        this(context,null,null);
    }
    public SystemPackageOps(Context context,Consumer<String> perfLogger)throws ReflectiveOperationException {
        this(context,null,perfLogger);
    }
    SystemPackageOps(Context context,Starter starter)throws ReflectiveOperationException {
        this(context,starter,null);
    }
    SystemPackageOps(Context context,Starter starter,Consumer<String> perfLogger)throws ReflectiveOperationException {
        this.context=context;
        this.starter=starter;
        this.perfLogger=perfLogger;
        hidden=context.getPackageManager().getClass().getMethod("getApplicationHiddenSettingAsUser",String.class,UserHandle.class);
        setHidden=context.getPackageManager().getClass().getMethod("setApplicationHiddenSettingAsUser",String.class,boolean.class,UserHandle.class);
        start=Context.class.getMethod("startActivityAsUser",Intent.class,UserHandle.class);
        hidden.setAccessible(true);setHidden.setAccessible(true);start.setAccessible(true);
    }
    public boolean ready(){return Process.myUid()==Process.SYSTEM_UID;}
    protected SystemPackageOps(){context=null;hidden=setHidden=start=null;starter=null;perfLogger=null;}
    public void thawAndOpen(LaunchPlanner.Plan plan,java.util.function.BooleanSupplier allowed)throws Exception {
        long totalStarted=SystemClock.elapsedRealtimeNanos();
        String outcome="failed";
        String pkg=plan.targetPackage();
        if(!ready() || !LaunchPlanner.validPackage(pkg) || BridgeContract.APP_PACKAGE.equals(pkg)
                || BridgeContract.HOST_PACKAGE.equals(pkg))throw new SecurityException("invalid_target");
        long identity=Binder.clearCallingIdentity();
        try {
            if(!allowed.getAsBoolean())throw new IllegalStateException("request_cancelled");
            PackageManager pm=context.getPackageManager();
            long readStarted=SystemClock.elapsedRealtimeNanos();
            ApplicationInfo info=pm.getApplicationInfo(pkg,PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_UNINSTALLED_PACKAGES);
            if((info.flags&ApplicationInfo.FLAG_INSTALLED)==0 || (info.flags&ApplicationInfo.FLAG_SUSPENDED)!=0)
                throw new IllegalStateException("target_unavailable");
            perf("target_state_read",readStarted,"ready");
            UserHandle user=Process.myUserHandle();
            if(!allowed.getAsBoolean())throw new IllegalStateException("request_cancelled");
            long unhideStarted=SystemClock.elapsedRealtimeNanos();
            String unhideOutcome="failed";
            try {
                if(Boolean.TRUE.equals(hidden.invoke(pm,pkg,user))) {
                    if(!Boolean.TRUE.equals(setHidden.invoke(pm,pkg,false,user)))throw new SecurityException("unhide_denied");
                    unhideOutcome="requested";
                }else unhideOutcome="already_ready";
            } finally { perf("target_unhide",unhideStarted,unhideOutcome); }
            if(!allowed.getAsBoolean())throw new IllegalStateException("request_cancelled");
            long enableStarted=SystemClock.elapsedRealtimeNanos();
            String enableOutcome="failed";
            try {
                if(!info.enabled) {
                    pm.setApplicationEnabledSetting(pkg,PackageManager.COMPONENT_ENABLED_STATE_ENABLED,0);
                    enableOutcome="requested";
                }else enableOutcome="already_ready";
            } finally { perf("target_enable",enableStarted,enableOutcome); }
            long verifyStarted=SystemClock.elapsedRealtimeNanos();
            ApplicationInfo after=pm.getApplicationInfo(pkg,PackageManager.MATCH_DISABLED_COMPONENTS);
            boolean stillHidden=Boolean.TRUE.equals(hidden.invoke(pm,pkg,user));
            if(!after.enabled || (after.flags&ApplicationInfo.FLAG_INSTALLED)==0 || (after.flags&ApplicationInfo.FLAG_SUSPENDED)!=0
                    || stillHidden)throw new IllegalStateException("target_not_ready");
            perf("target_state_verify",verifyStarted,"ready");
            long resolveStarted=SystemClock.elapsedRealtimeNanos();
            Intent target=new Intent(plan.target());
            if(Intent.ACTION_MAIN.equals(target.getAction()))target=pm.getLaunchIntentForPackage(pkg);
            if(target==null || pm.resolveActivity(target,0)==null)throw new IllegalStateException("no_handler");
            perf("activity_resolve",resolveStarted,"ready");
            if(!allowed.getAsBoolean())throw new IllegalStateException("request_cancelled");
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);
            long startStarted=SystemClock.elapsedRealtimeNanos();
            if(starter==null)start.invoke(context,target,user);else starter.open(target,user);
            outcome="submitted";
            perf("activity_start",startStarted,"submitted");
        }finally{
            Binder.restoreCallingIdentity(identity);
            perf("thaw_open_total",totalStarted,outcome);
        }
    }
    private void perf(String stage,long started,String outcome){
        if(perfLogger==null)return;
        long elapsed=Math.max(0,(SystemClock.elapsedRealtimeNanos()-started)/1000L);
        try{perfLogger.accept("runtime perf stage="+stage+" elapsed_us="+elapsed+" outcome="+outcome);}
        catch(RuntimeException ignored){}
    }
}
