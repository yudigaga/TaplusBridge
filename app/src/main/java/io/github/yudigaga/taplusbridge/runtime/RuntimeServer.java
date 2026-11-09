package io.github.yudigaga.taplusbridge.runtime;

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
import io.github.yudigaga.taplusbridge.capture.CaptureBroker;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** System configuration, capture and gesture bridge. App launches stay in the module process. */
public final class RuntimeServer {
    private final Context context;
    private final Handler worker;
    private final Supplier<SharedPreferences> preferences;
    private final java.util.function.Consumer<String> logger;
    private final LinkedHashMap<String,Long> dragLaunches=new LinkedHashMap<>();
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
        default Bundle linkWindow(Bundle request, RuntimeSnapshot config, boolean ready) { return Bundle.EMPTY; }
    }
    private volatile SourceCapture sourceCapture;
    public void setSourceCapture(SourceCapture capture) { sourceCapture = capture; }
    public interface DragGestures {
        Bundle claim(Bundle request, RuntimeSnapshot config);
        Bundle poll(Bundle request, RuntimeSnapshot config);
        Bundle consume(Bundle request, RuntimeSnapshot config);
        default Bundle overlayReady(Bundle request, RuntimeSnapshot config) { return Bundle.EMPTY; }
        default Bundle update(Bundle request, RuntimeSnapshot config) { return Bundle.EMPTY; }
        default Bundle prepare(Bundle request, RuntimeSnapshot config) { return Bundle.EMPTY; }
        default Bundle requestLink(Bundle request, RuntimeSnapshot config) { return Bundle.EMPTY; }
        default void nativeResult(String source, long occurredElapsed, boolean hasValidUrl) { }
    }
    private volatile DragGestures dragGestures;
    public void setDragGestures(DragGestures gestures) { dragGestures = gestures; }
    public RuntimeSnapshot dragConfiguration() {
        RuntimeSnapshot config = current();
        return config != null && config.build == BuildConfig.VERSION_CODE && config.user == 0 && config.enabled
                && config.drag.enabled ? config : null;
    }
    /** Local system use only. */
    public RuntimeSnapshot sourceCaptureConfiguration() {
        RuntimeSnapshot config = current();
        return config != null && config.build == BuildConfig.VERSION_CODE && config.enabled
                && (config.recognition || config.scopeCapture || config.scopeLinkProbe) && config.sourceScope.managed
                ? config : null;
    }

    public RuntimeSnapshot sourceContentConfiguration() {
        RuntimeSnapshot config = current();
        return config != null && config.build == BuildConfig.VERSION_CODE && config.user == 0 && config.enabled
                && config.recognition && config.sourceScope.managed ? config : null;
    }
    private record Lease(String nonce,String source,int sourceUid,int owner,long issued,boolean taken,String identity,boolean probe){}
    public RuntimeServer(Context context,Handler worker,Supplier<SharedPreferences> preferences,
                         java.util.function.Consumer<String> logger){
        this.context=context;this.worker=worker;this.preferences=preferences;this.logger=logger;
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
        event("System configuration bridge ready");
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
                    event("runtime config stage=loaded build="+result.build
                            +" supplemental_tap="+(result.scopeLinkProbe?"on":"off"));
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
        boolean dragLaunch="drag_launch".equals(method);
        boolean dragConsume="drag_gesture_consume".equals(method);
        if(!isHost && !(("status".equals(method) || dragLaunch || dragConsume) && module(uid))) {
            return declined("unauthorized");
        }
        if((dragLaunch || dragConsume) && isHost)return declined("unauthorized");
        long identity=Binder.clearCallingIdentity();
        try {
            RuntimeSnapshot config=current();
            if ("scope_link_window_check".equals(method) || "scope_link_window_ready".equals(method)) {
                SourceCapture capture = sourceCapture;
                return config == null || config.build != BuildConfig.VERSION_CODE || !config.enabled
                        || !config.scopeLinkProbe || capture == null ? Bundle.EMPTY
                        : capture.linkWindow(input, config, "scope_link_window_ready".equals(method));
            }
            if ("drag_gesture_claim".equals(method) || "drag_gesture_poll".equals(method)
                    || "drag_gesture_prepare".equals(method) || "drag_gesture_link".equals(method)
                    || "drag_gesture_overlay_ready".equals(method) || "drag_gesture_update".equals(method) || dragConsume) {
                if (config == null || config.build != BuildConfig.VERSION_CODE || !config.enabled || !config.drag.enabled
                        || dragGestures == null) return declined("disabled");
                return dragConsume ? dragGestures.consume(input, config)
                        : "drag_gesture_prepare".equals(method) ? dragGestures.prepare(input, config)
                        : "drag_gesture_link".equals(method) ? dragGestures.requestLink(input, config)
                        : "drag_gesture_claim".equals(method) ? dragGestures.claim(input, config)
                        : "drag_gesture_poll".equals(method) ? dragGestures.poll(input, config)
                        : "drag_gesture_overlay_ready".equals(method) ? dragGestures.overlayReady(input, config)
                        : dragGestures.update(input, config);
            }
            if(dragLaunch)return launchDrag(input,config);
            if("drag_choose".equals(method))return chooseDrag(input,config);
            if("drag_active".equals(method)) {
                Bundle active=new Bundle();active.putBoolean("active",
                        io.github.yudigaga.taplusbridge.drag.DragGestureGate.suppressLongPress());return active;
            }
            if ("scope_link_probe_owns".equals(method)) {
                Bundle result = new Bundle(); SourceCapture capture = sourceCapture;
                result.putBoolean("owned", config != null && config.build == BuildConfig.VERSION_CODE
                        && config.enabled && config.scopeLinkProbe && config.sourceScope.managed
                        && capture != null && capture.ownsLinkPick(input));
                if (result.getBoolean("owned")) result.putString("identity", config.identity);
                return result;
            }
            if ("scope_capture_native".equals(method)) {
                SourceCapture capture = sourceCapture; if (capture != null) capture.nativeResult(input);
                DragGestures drag = dragGestures;
                if (drag != null && input != null && input.get("native_elapsed") instanceof Long at)
                    drag.nativeResult(input.getString("source"), at, input.getBoolean("has_valid_url"));
                return Bundle.EMPTY;
            }
            if ("scope_capture_take".equals(method)) {
                if (config == null || config.build != BuildConfig.VERSION_CODE || !config.enabled
                        || (!config.recognition && !config.scopeCapture && !config.scopeLinkProbe)
                        || !config.sourceScope.managed) return Bundle.EMPTY;
                SourceCapture capture = sourceCapture;
                return capture == null ? Bundle.EMPTY : capture.take(uid, input, config);
            }
            if("status".equals(method)) {
                Bundle result=new Bundle();
                result.putBoolean("scope_ready", publishSourceScope(config));
                result.putBoolean("scope_capture_ready", sourceCapture != null);
                result.putBoolean("scope_link_probe_ready", sourceCapture != null && sourceCapture.linkReady());
                result.putBoolean("ready",config!=null && config.build==BuildConfig.VERSION_CODE);
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
                result.putBoolean("source_content_enabled", config.enabled && config.recognition);
                result.putBoolean("scope_link_probe_enabled", config.enabled && config.scopeLinkProbe); return result;
            }
            if ("recognition_channels".equals(method)) {
                if (config == null || config.build != BuildConfig.VERSION_CODE) return declined("runtime_unavailable");
                Bundle result = new Bundle(); result.putString("channels", config.channels.toJson());
                result.putString("identity", config.identity);
                result.putBoolean("active", config.enabled && config.recognition); return result;
            }
            if(config==null || !config.usable()) {
                return declined("runtime_unavailable");
            }
            if(!config.enabled) {
                return declined("disabled");
            }
            Bundle request=input==null?new Bundle():new Bundle(input);
            return switch(method==null?"":method) {
                case "capture_available" -> {Bundle b=new Bundle();b.putBoolean("enabled",config.capture && config.recognition && config.scopeLinkProbe && CaptureLease.allowedSource(request.getString("source")));b.putInt("recognition_delay",config.drag.recognitionDelayMillis);yield b;}
                case "probe_arm", "capture_arm" -> arm(uid,request,config,"probe_arm".equals(method));
                case "capture_cancel" -> cancel(uid,request);
                case "recognition_status", "probe_status" -> Bundle.EMPTY;
                default -> declined("unsupported_request");
            };
        }catch(RuntimeException failed){return declined("exception");}finally{Binder.restoreCallingIdentity(identity);}
    }
    private synchronized Bundle launchDrag(Bundle request,RuntimeSnapshot config) {
        if(config==null || config.build!=BuildConfig.VERSION_CODE || !config.enabled || !config.drag.enabled)
            return declined("disabled");
        if(request==null || request.size()!=2 || !(request.get("token") instanceof String token)
                || !token.matches("[A-Za-z0-9_-]{43}") || !(request.get("source") instanceof String source)
                || !config.sourceScope.allows(source) || BridgeContract.APP_PACKAGE.equals(source)
                || BridgeContract.HOST_PACKAGE.equals(source))return declined("invalid_request");
        try {
            long now=SystemClock.elapsedRealtime();
            dragLaunches.entrySet().removeIf(entry -> now-entry.getValue()>30_000);
            if(dragLaunches.containsKey(token)) {
                Bundle result=new Bundle();result.putBoolean("accepted",true);return result;
            }
            KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
            PowerManager power=context.getSystemService(PowerManager.class);
            if(keyguard==null || keyguard.isKeyguardLocked() || power==null || !power.isInteractive())
                return declined("device_locked");
            var host=context.getPackageManager().getPackageInfo(BridgeContract.HOST_PACKAGE,0);
            if(!HostPolicy.supportedVersion(host.versionName,host.getLongVersionCode()))return declined("unsupported_host");
            var app=context.getPackageManager().getApplicationInfo(source,0);
            if(app.uid<10000 || app.uid>=100000)return declined("invalid_source");
            Intent launch=new Intent("io.github.yudigaga.taplusbridge.DRAG_ACTION")
                    .setClassName(BridgeContract.HOST_PACKAGE,"com.miui.contentextension.services.TextContentExtensionService")
                    .putExtra("drag_token",token);
            if(context.startService(launch)==null)return declined("host_unavailable");
            if(dragLaunches.size()>=8)dragLaunches.remove(dragLaunches.keySet().iterator().next());
            dragLaunches.put(token,now);
            Bundle result=new Bundle();result.putBoolean("accepted",true);return result;
        }catch(PackageManager.NameNotFoundException|RuntimeException unavailable){return declined("host_unavailable");}
    }
    private synchronized Bundle chooseDrag(Bundle request,RuntimeSnapshot config) {
        if(config==null || config.build!=BuildConfig.VERSION_CODE || !config.enabled || !config.drag.enabled)
            return declined("disabled");
        if(request==null || request.size()!=1 || !(request.get("token") instanceof String token)
                || !token.matches("[A-Za-z0-9_-]{43}"))return declined("invalid_request");
        try {
            KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
            PowerManager power=context.getSystemService(PowerManager.class);
            if(keyguard==null || keyguard.isKeyguardLocked() || power==null || !power.isInteractive())
                return declined("device_locked");
            var app=context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE,0);
            if(!app.enabled)return declined("disabled");
            long now=SystemClock.elapsedRealtime();
            dragLaunches.entrySet().removeIf(entry -> now-entry.getValue()>60_000);
            if(!dragLaunches.containsKey(token)) {
                context.startActivity(new Intent().setClassName(BridgeContract.APP_PACKAGE,
                        BridgeContract.APP_PACKAGE+".drag.DragActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        .putExtra("result_token",token));
                if(dragLaunches.size()>=8)dragLaunches.remove(dragLaunches.keySet().iterator().next());
                dragLaunches.put(token,now);
            }
            Bundle result=new Bundle();result.putBoolean("accepted",true);return result;
        }catch(PackageManager.NameNotFoundException|RuntimeException unavailable){return declined("module_unavailable");}
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
    private synchronized Bundle arm(int owner,Bundle input,RuntimeSnapshot config,boolean probe){
        String source=input.getString("source");
        if(!config.capture || !config.recognition || (probe && !config.scopeLinkProbe) || !CaptureLease.allowedSource(source))return declined("disabled");
        try {
            int uid=context.getPackageManager().getApplicationInfo(source,0).uid;if(uid/100000!=0)return declined("invalid_request");
            String nonce=token();capture=new Lease(nonce,source,uid,owner,SystemClock.elapsedRealtime(),false,config.identity,probe);
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
        if(lease.probe() && !config.scopeLinkProbe)return Bundle.EMPTY;
        if("capture_take".equals(method)){
            if(lease.taken() || now<lease.issued() || now-lease.issued()>=2500)return Bundle.EMPTY;
            capture=new Lease(lease.nonce(),lease.source(),lease.sourceUid(),lease.owner(),lease.issued(),true,lease.identity(),lease.probe());
            Bundle b=new Bundle();b.putString("source",lease.source());b.putInt("source_uid",lease.sourceUid());b.putInt("user",0);
            b.putBoolean("system_runtime",true);b.putBoolean("probe",lease.probe());return b;
        }
        if("capture_report".equals(method)){capture=null;return Bundle.EMPTY;}
        return Bundle.EMPTY;
    }
    public synchronized boolean captureValid(String nonce,int user){
        RuntimeSnapshot config=current();
        return user==0 && capture!=null && capture.probe() && capture.taken() && capture.nonce().equals(nonce)
                && config!=null && config.usable() && config.enabled && config.recognition && config.capture && config.scopeLinkProbe
                && config.identity.equals(capture.identity());
    }
    private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private void event(String value){if(logger!=null)logger.accept(value);}
    private void perf(String stage,long started,String outcome){
        long elapsed=Math.max(0,(SystemClock.elapsedRealtimeNanos()-started)/1000L);
        event("runtime perf stage="+stage+" elapsed_us="+elapsed+" outcome="+outcome);
    }
    private static Bundle declined(String reason){Bundle b=new Bundle();b.putString("reason",reason);return b;}
}
