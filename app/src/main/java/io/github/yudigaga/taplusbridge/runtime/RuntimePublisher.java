package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Publish complete rule/config identities to the framework; never a transient empty fallback. */
public final class RuntimePublisher {
    private static volatile RuntimePublisher instance;
    private final Context app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"TaplusBridge-config-sync");t.setDaemon(true);return t;});
    private final AtomicLong generation=new AtomicLong();
    private volatile XposedService service;
    private volatile String message="等待框架连接，暂不可冻结本体";
    private final SharedPreferences config,rules;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed=(p,k)->schedule();
    private RuntimePublisher(Context context){
        app=context.getApplicationContext();config=app.getSharedPreferences("config",0);rules=app.getSharedPreferences(RuleRepository.PREFS,0);
        config.registerOnSharedPreferenceChangeListener(changed);rules.registerOnSharedPreferenceChangeListener(changed);
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener(){
            public synchronized void onServiceBind(XposedService value){service=value;schedule();}
            public synchronized void onServiceDied(XposedService value){if(service==value){generation.incrementAndGet();service=null;message="框架连接已断开，系统尚未确认配置";}}
        });
    }
    public static synchronized void start(Context context){if(instance==null)try{instance=new RuntimePublisher(context);}catch(RuntimeException unavailable){android.util.Log.w("TaplusBridge","runtime sync unavailable: "+unavailable.getClass().getSimpleName());}}
    public static void synchronize(Context context){start(context);RuntimePublisher active=instance;if(active!=null)active.schedule();}
    /** A user save bypasses debounce, but all remote writes stay on the same ordered worker. */
    public static void publishSavedSettings(Context context){
        start(context);RuntimePublisher active=instance;
        if(active!=null){
            active.message="本体已保存，等待系统同步";
            long expected=active.generation.incrementAndGet();
            active.worker.submit(()->active.publish(expected));
        }
    }
    public static String status(){RuntimePublisher active=instance;return active==null?"等待框架连接，暂不可冻结本体":active.message;}
    private void schedule(){
        message="配置已更改，等待系统同步";
        long expected=generation.incrementAndGet();main.postDelayed(()->{if(expected==generation.get())worker.submit(()->publish(expected));},100);
    }
    private void publish(long expected){publish(expected,0);}
    private void publish(long expected,int attempt){
        if(expected!=generation.get())return;
        XposedService current=service;
        if(android.os.Process.myUid()/100000!=0){message="独立运行仅支持主用户，系统尚未确认配置";return;}
        if(current==null){
            message=attempt<5?"本体已保存，等待框架连接；系统尚未确认":"本体已保存，系统尚未确认；请连接框架后手动重试";
            retry(expected,attempt);return;
        }
        try {
            String raw=RuntimeSnapshot.from(app);RuntimeSnapshot snapshot=RuntimeSnapshot.parse(raw);
            if(expected!=generation.get())return;
            SharedPreferences target=current.getRemotePreferences(RuntimeSnapshot.GROUP);
            store(target,raw);
            if(expected!=generation.get())return;
            message="框架已保存配置，等待系统确认";
            configurationEvent("stored",snapshot);
            // Status retries also reconnect; a pending bind must not own confirmation progress.
            confirm(expected,snapshot,0);
        }catch(RuntimeException failed){
            if(expected!=generation.get())return;
            message=attempt<5?"本体已保存，系统同步失败；等待重试":"本体已保存，系统同步失败；请手动重试";
            android.util.Log.w("TaplusBridge","runtime sync failed: "+failed.getClass().getSimpleName());
            retry(expected,attempt);
        }
    }
    private void retry(long expected,int attempt){
        if(attempt<5)main.postDelayed(()->{
            if(expected==generation.get())worker.submit(()->publish(expected,attempt+1));
        },250L<<attempt);
    }
    static void store(SharedPreferences target,String raw){
        RuntimeSnapshot.parse(raw);
        // RemotePreferences mutates its cache before the Binder commit, even when that commit fails.
        if(!target.edit().putString(RuntimeSnapshot.KEY,raw).commit())
            throw new IllegalStateException("framework_store_failed");
        if(!raw.equals(target.getString(RuntimeSnapshot.KEY,null)))throw new IllegalStateException("framework_store_mismatch");
    }
    private void confirm(long expected,RuntimeSnapshot snapshot,int attempt){
        if(expected!=generation.get())return;
        android.os.Bundle status=RuntimeClient.call(app,"status",null);
        if(expected!=generation.get())return;
        if(status!=null && snapshot.identity.equals(status.getString("identity")) && status.getBoolean("ready")
                && (!snapshot.sourceScope.managed || status.getBoolean("scope_ready"))
                && (!snapshot.scopeCapture || status.getBoolean("scope_capture_ready"))
                && (!snapshot.scopeLinkProbe || status.getBoolean("scope_link_probe_ready")))
        {
            message=snapshot.independent?"系统已确认配置，可冻结本体":"系统已确认配置，独立流转已关闭";
            configurationEvent("confirmed",snapshot);
        }
        else if(attempt<5)main.postDelayed(()->worker.submit(()->confirm(expected,snapshot,attempt+1)),250L<<attempt);
        else message="配置已保存，系统尚未就绪；更新后请重启再同步";
    }
    private static void configurationEvent(String stage,RuntimeSnapshot snapshot){
        android.util.Log.i("TaplusBridge","runtime config stage="+stage+" build="+snapshot.build
                +" supplemental_tap="+(snapshot.scopeLinkProbe?"on":"off"));
    }
}
