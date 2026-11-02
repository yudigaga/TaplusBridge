package io.github.yudigaga.taplusbridge.runtime;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.BuildConfig;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner;
import io.github.yudigaga.taplusbridge.capture.CaptureBroker;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** Framework runtime: reads framework storage, no module Provider/Activity/process required. */
public final class RuntimeServer {
    private final Context context;
    private final Handler worker;
    private final Supplier<SharedPreferences> preferences;
    private final SystemPackageOps packages;
    private final java.util.function.Consumer<String> logger;
    private final LinkedHashMap<String,Ticket> tickets=new LinkedHashMap<>();
    private final SecureRandom random=new SecureRandom();
    private final Object snapshotLock=new Object();
    private volatile String snapshotRaw;
    private volatile RuntimeSnapshot snapshot;
    private Lease capture;
    public interface SourceCapture {
        Bundle take(int owner, Bundle request, RuntimeSnapshot config);
        void nativeResult(Bundle request);
        default boolean linkReady() { return false; }
        default boolean ownsLinkPick(Bundle request) { return false; }
    }
    private volatile SourceCapture sourceCapture;
    public void setSourceCapture(SourceCapture capture) { sourceCapture = capture; }
    /** Local system use only; capture support does not require the independent routing toggle. */
    public RuntimeSnapshot sourceCaptureConfiguration() {
        RuntimeSnapshot config = current();
        return config != null && config.build == BuildConfig.VERSION_CODE && config.enabled
                && (config.scopeCapture || config.scopeLinkProbe) && config.sourceScope.managed
                ? config : null;
    }
    private record Ticket(LaunchPlanner.Plan plan,String identity,int owner,long until,String origin){}
    private record Lease(String nonce,String source,int sourceUid,int owner,long issued,boolean taken,String identity){}
    public RuntimeServer(Context context,Handler worker,Supplier<SharedPreferences> preferences,SystemPackageOps packages,
                         java.util.function.Consumer<String> logger){
        this.context=context;this.worker=worker;this.preferences=preferences;this.packages=packages;this.logger=logger;
    }
    public void register(){
        io.github.yudigaga.taplusbridge.bridge.IceRuleRouting.registerPackageInvalidationReceiver(context,worker);
        context.registerReceiver(new BroadcastReceiver(){
            @SuppressWarnings("deprecation") public void onReceive(Context ignored,Intent command){
                try {
                    command.setExtrasClassLoader(ResultReceiver.class.getClassLoader());
                    ResultReceiver callback=command.getParcelableExtra("reply");if(callback==null)return;
                    Bundle data=new Bundle();data.putBinder("bridge",binder.asBinder());callback.send(1,data);
                }catch(RuntimeException ignoredPayload){}
            }
        },new IntentFilter(RuntimeClient.BIND),null,worker,Context.RECEIVER_EXPORTED);
        IntentFilter packageChanges = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packageChanges.addAction(Intent.ACTION_PACKAGE_REMOVED); packageChanges.addDataScheme("package");
        BroadcastReceiver scopePackages = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                publishSourceScope(current());
            }
        };
        try {
            // The setting is device-wide, so changes in work profiles must also rebuild the complement.
            var all = (android.os.UserHandle) android.os.UserHandle.class.getDeclaredField("ALL").get(null);
            var register = Context.class.getDeclaredMethod("registerReceiverAsUser", BroadcastReceiver.class,
                    android.os.UserHandle.class, IntentFilter.class, String.class, Handler.class);
            register.setAccessible(true); register.invoke(context, scopePackages, all, packageChanges, null, worker);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            event("Source app scope package observer unavailable: " + unavailable.getClass().getSimpleName());
        }
        worker.post(() -> publishSourceScope(current()));
        event("runtime channel ready; provider_required=false");
    }
    private final IRuntimeBridge.Stub binder=new IRuntimeBridge.Stub(){
        public Bundle call(String method,Bundle request){return handle(Binder.getCallingUid(),method,request);}
    };
    public IRuntimeBridge binder(){return binder;}
    private RuntimeSnapshot current(){
        long started=SystemClock.elapsedRealtimeNanos();
        try{
            var own=context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE,
                    PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_UNINSTALLED_PACKAGES);
            if((own.flags&android.content.pm.ApplicationInfo.FLAG_INSTALLED)==0 || own.uid/100000!=0){
                perf("snapshot_read",started,"module_missing");return null;
            }
            String raw=preferences.get().getString(RuntimeSnapshot.KEY,null);
            RuntimeSnapshot cached=snapshot;
            if(cached!=null && raw!=null && raw.equals(snapshotRaw)){
                return cached;
            }
            String outcome;
            RuntimeSnapshot result;
            synchronized(snapshotLock){
                raw=preferences.get().getString(RuntimeSnapshot.KEY,null);
                cached=snapshot;
                if(cached!=null && raw!=null && raw.equals(snapshotRaw)){
                    result=cached;outcome="cache_hit";
                }else{
                    result=RuntimeSnapshot.parse(raw);
                    snapshotRaw=raw;snapshot=result;outcome="parsed";
                }
            }
            if(!"cache_hit".equals(outcome))perf("snapshot_read",started,outcome);
            return result;
        }catch(PackageManager.NameNotFoundException|RuntimeException missing){
            perf("snapshot_read",started,"unavailable");return null;
        }
    }
    private boolean host(int uid){
        try {
            if(uid<10000 || uid/100000!=0)return false;
            var info=context.getPackageManager().getPackageInfo(BridgeContract.HOST_PACKAGE,PackageManager.MATCH_DISABLED_COMPONENTS);
            return info.applicationInfo!=null && info.applicationInfo.uid==uid
                    && HostPolicy.supportedVersion(info.versionName,info.getLongVersionCode());
        }catch(PackageManager.NameNotFoundException|RuntimeException missing){return false;}
    }
    private boolean module(int uid){
        try{return uid>=10000 && uid/100000==0 && context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE,
                PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_UNINSTALLED_PACKAGES).uid==uid;}
        catch(PackageManager.NameNotFoundException|RuntimeException missing){return false;}
    }
    /** Binder identity is checked before clearing it or reading private configuration. */
    public Bundle handle(int uid,String method,Bundle input){
        boolean isHost=host(uid);
        if(!isHost && !("status".equals(method) && module(uid))) {
            return declined("unauthorized");
        }
        long identity=Binder.clearCallingIdentity();
        try {
            RuntimeSnapshot config=current();
            if ("scope_link_probe_owns".equals(method)) {
                Bundle result = new Bundle(); SourceCapture capture = sourceCapture;
                result.putBoolean("owned", config != null && config.build == BuildConfig.VERSION_CODE
                        && config.enabled && config.scopeLinkProbe && config.sourceScope.managed
                        && capture != null && capture.ownsLinkPick(input));
                if (result.getBoolean("owned")) result.putString("identity", config.identity);
                return result;
            }
            if ("scope_capture_native".equals(method)) {
                SourceCapture capture = sourceCapture; if (capture != null) capture.nativeResult(input); return Bundle.EMPTY;
            }
            if ("scope_capture_take".equals(method)) {
                if (config == null || config.build != BuildConfig.VERSION_CODE || !config.enabled
                        || (!config.scopeCapture && !config.scopeLinkProbe) || !config.sourceScope.managed) return Bundle.EMPTY;
                SourceCapture capture = sourceCapture;
                return capture == null ? Bundle.EMPTY : capture.take(uid, input, config);
            }
            if("status".equals(method)) {
                Bundle result=new Bundle();
                result.putBoolean("scope_ready", publishSourceScope(config));
                result.putBoolean("scope_capture_ready", sourceCapture != null);
                result.putBoolean("scope_link_probe_ready", sourceCapture != null && sourceCapture.linkReady());
                result.putBoolean("ready",config!=null && config.build==BuildConfig.VERSION_CODE && packages.ready());
                if(config!=null) {
                    result.putString("identity",config.identity);
                }
                return result;
            }
            if ("source_scope".equals(method)) {
                if (config == null || config.build != BuildConfig.VERSION_CODE) return declined("runtime_unavailable");
                Bundle result = new Bundle(); result.putString("policy", config.sourceScope.encode());
                result.putString("identity", config.identity);
                result.putBoolean("scope_capture_enabled", config.enabled && config.scopeCapture);
                result.putBoolean("scope_link_probe_enabled", config.enabled && config.scopeLinkProbe); return result;
            }
            if(config==null || !config.usable() || !packages.ready()) {
                return declined("runtime_unavailable");
            }
            if(!config.enabled) {
                return declined("disabled");
            }
            Bundle request=input==null?new Bundle():new Bundle(input);
            return switch(method==null?"":method) {
                case "capture_available" -> {Bundle b=new Bundle();b.putBoolean("enabled",config.capture && config.recognition && CaptureLease.allowedSource(request.getString("source")));yield b;}
                case "probe_arm", "capture_arm" -> arm(uid,request,config);
                case "capture_cancel" -> cancel(uid,request);
                case "recognition_status", "probe_status" -> Bundle.EMPTY;
                case "resolve", BridgeContract.PREPARE -> route(uid,method,request,config);
                default -> declined("unsupported_request");
            };
        }catch(RuntimeException failed){return declined("exception");}finally{Binder.restoreCallingIdentity(identity);}
    }
    /** Only a validated framework snapshot can publish; no caller-supplied package list is used. */
    private synchronized boolean publishSourceScope(RuntimeSnapshot config) {
        if (config == null || config.build != BuildConfig.VERSION_CODE) return false;
        if (!config.sourceScope.managed) return true;
        try {
            var host = context.getPackageManager().getPackageInfo(BridgeContract.HOST_PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS);
            if (!HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode())) return false;
            SourceScopePublisher.publish(context, SourceScopePublisher.effectiveBlacklist(context, config.sourceScope));
            event("Source app scope published"); return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException failed) {
            event("Source app scope publication unavailable: " + failed.getClass().getSimpleName()); return false;
        }
    }
    private Bundle route(int owner,String method,Bundle request,RuntimeSnapshot config){
        if(request.containsKey("explicit_target"))return declined("invalid_request");
        if("resolve".equals(method) && request.getBoolean("local_recognition") && !config.recognition)return declined("recognition_disabled");
        boolean ephemeral=request.getBoolean("ephemeral");
        if(ephemeral){request.remove("original");request.remove("candidate");}
        long planningStarted=SystemClock.elapsedRealtimeNanos();
        LaunchPlanner.PlanningResult result=LaunchPlanner.planWithRules(context,request,config.enabled,config.nativeLinks,config.rules);
        perf("route_plan",planningStarted,result.plan()==null?"no_match":"resolved");
        if(result.plan()==null)return declined(result.reason());
        var plan=result.plan();
        if("resolve".equals(method))return preview(plan);
        Intent direct=LaunchPlanner.directIfReady(context,plan);
        Bundle response=new Bundle();if(direct!=null){response.putParcelable("direct",direct);return response;}
        String token=token();
        String origin=request.getString("source");if(origin==null)origin=foregroundPackage();
        if(origin==null)return declined("background");
        synchronized(tickets){
            long now=SystemClock.elapsedRealtime();tickets.entrySet().removeIf(e->now>=e.getValue().until());
            if(tickets.size()>=16)return declined("busy");
            tickets.put(token,new Ticket(plan,config.identity,owner,now+20_000,origin));
        }
        response.putString("system_token",token);return response;
    }
    private Bundle preview(LaunchPlanner.Plan plan){
        long started=SystemClock.elapsedRealtimeNanos();
        String outcome="missing";
        try {
            var pm=context.getPackageManager();var info=pm.getApplicationInfo(plan.targetPackage(),PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_UNINSTALLED_PACKAGES);
            Bundle result=new Bundle();result.putString("target",plan.targetPackage());result.putString("title",pm.getApplicationLabel(info).toString());
            try {
                var icon=pm.getApplicationIcon(info);var bitmap=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888);
                icon.setBounds(0,0,64,64);icon.draw(new android.graphics.Canvas(bitmap));result.putParcelable("icon",bitmap);
            }catch(RuntimeException ignored){}
            outcome="ready";return result;
        }catch(PackageManager.NameNotFoundException missing){return declined("target_missing");}
        finally{perf("target_preview",started,outcome);}
    }
    private synchronized Bundle arm(int owner,Bundle input,RuntimeSnapshot config){
        String source=input.getString("source");
        if(!config.capture || !config.recognition || !CaptureLease.allowedSource(source))return declined("disabled");
        try {
            int uid=context.getPackageManager().getApplicationInfo(source,0).uid;if(uid/100000!=0)return declined("invalid_request");
            String nonce=token();capture=new Lease(nonce,source,uid,owner,SystemClock.elapsedRealtime(),false,config.identity);
            Bundle result=new Bundle();result.putString("nonce",nonce);result.putInt("user",0);return result;
        }catch(PackageManager.NameNotFoundException missing){return declined("source_missing");}
    }
    private synchronized Bundle cancel(int owner,Bundle input){
        String nonce=input.getString("nonce");Bundle result=new Bundle();
        if(capture!=null && capture.owner()==owner && capture.nonce().equals(nonce)){
            capture=null;result.putBoolean("cancelled",true);
            context.sendBroadcast(new Intent(CaptureBroker.ACTION_CANCEL).setPackage("android").putExtra("nonce",nonce).putExtra("user",0));
        }return result;
    }
    /** Local system worker only; this is never exported through the Binder method switch. */
    public synchronized Bundle systemCall(int user,String method,Bundle data){
        RuntimeSnapshot config=current();long now=SystemClock.elapsedRealtime();String nonce=data==null?null:data.getString("nonce");
        Lease lease=capture;
        if(user!=0 || config==null || !config.usable() || !config.enabled || !config.capture || !config.recognition
                || lease==null || !lease.nonce().equals(nonce) || !lease.identity().equals(config.identity))return null;
        if("capture_take".equals(method)){
            if(lease.taken() || now<lease.issued() || now-lease.issued()>=2500)return Bundle.EMPTY;
            capture=new Lease(lease.nonce(),lease.source(),lease.sourceUid(),lease.owner(),lease.issued(),true,lease.identity());
            Bundle b=new Bundle();b.putString("source",lease.source());b.putInt("source_uid",lease.sourceUid());b.putInt("user",0);
            b.putBoolean("system_runtime",true);return b;
        }
        if("capture_report".equals(method)){capture=null;return Bundle.EMPTY;}
        return Bundle.EMPTY;
    }
    public synchronized boolean captureValid(String nonce,int user){
        RuntimeSnapshot config=current();
        return user==0 && capture!=null && capture.taken() && capture.nonce().equals(nonce)
                && config!=null && config.usable() && config.enabled && config.recognition && config.capture
                && config.identity.equals(capture.identity());
    }
    /** Called at the public ATMS entry, before any launch locks; no Activity exists for this token. */
    public boolean intercept(String callingPackage,int uid,int requestedUser,Intent marker){
        if(marker==null || !RuntimeClient.OPEN.equals(marker.getAction()) || !"android".equals(marker.getPackage()))return false;
        if(!BridgeContract.HOST_PACKAGE.equals(callingPackage) || !host(uid))return false;
        String token=marker.getStringExtra("system_token");Ticket ticket;
        synchronized(tickets){ticket=tickets.remove(token);}
        if(ticket==null || ticket.owner()!=uid || (requestedUser!=0 && requestedUser!=-2 && requestedUser!=-3)) {
            event("runtime launch declined: invalid_ticket");return true;
        }
        event("runtime launch claimed; result_callback=false");
        worker.post(()->open(ticket));return true;
    }
    private void open(Ticket ticket){
        long preflightStarted=SystemClock.elapsedRealtimeNanos();
        RuntimeSnapshot config=current();long now=SystemClock.elapsedRealtime();
        if(config==null || !config.usable() || !config.enabled || !ticket.identity().equals(config.identity)
                || now>=ticket.until() || !foreground(ticket.origin())){
            perf("open_preflight",preflightStarted,"declined");event("runtime launch declined: stale_or_background");return;
        }
        perf("open_preflight",preflightStarted,"accepted");
        long launchStarted=SystemClock.elapsedRealtimeNanos();
        try {
            packages.thawAndOpen(ticket.plan(),()->{
                RuntimeSnapshot latest=current();return latest!=null && latest.usable() && latest.enabled
                        && latest.identity.equals(ticket.identity()) && SystemClock.elapsedRealtime()<ticket.until()
                        && foreground(ticket.origin());
            });perf("thaw_and_start",launchStarted,"submitted");event("runtime launch submitted; module_started=false");
        }catch(Exception failed){
            perf("thaw_and_start",launchStarted,"failed");event("runtime launch failed: "+failed.getClass().getSimpleName());
        }
    }
    private boolean foreground(String source){
        String top=foregroundPackage();
        return top!=null && (BridgeContract.HOST_PACKAGE.equals(top) || (source!=null && source.equals(top)));
    }
    private String foregroundPackage(){
        try {
            PowerManager power=context.getSystemService(PowerManager.class);KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
            if(power==null || !power.isInteractive() || keyguard==null || keyguard.isKeyguardLocked())return null;
            var tasks=context.getSystemService(ActivityManager.class).getRunningTasks(1);
            if(tasks==null || tasks.size()!=1 || tasks.get(0).topActivity==null)return null;
            var task=tasks.get(0);
            if(task.getClass().getField("userId").getInt(task)!=0 || task.getClass().getField("displayId").getInt(task)!=0)return null;
            return task.topActivity.getPackageName();
        }catch(ReflectiveOperationException|RuntimeException unavailable){return null;}
    }
    private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private void event(String value){if(logger!=null)logger.accept(value);}
    private void perf(String stage,long started,String outcome){
        long elapsed=Math.max(0,(SystemClock.elapsedRealtimeNanos()-started)/1000L);
        event("runtime perf stage="+stage+" elapsed_us="+elapsed+" outcome="+outcome);
    }
    private static Bundle declined(String reason){Bundle b=new Bundle();b.putString("reason",reason);return b;}
}
