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
            public void onServiceBind(XposedService value){service=value;schedule();}
            public void onServiceDied(XposedService value){if(service==value){generation.incrementAndGet();service=null;message="框架连接已断开，暂不可冻结本体";}}
        });
    }
    public static synchronized void start(Context context){if(instance==null)try{instance=new RuntimePublisher(context);}catch(RuntimeException unavailable){android.util.Log.w("TaplusBridge","runtime sync unavailable: "+unavailable.getClass().getSimpleName());}}
    public static void synchronize(Context context){start(context);RuntimePublisher active=instance;if(active!=null)active.schedule();}
    public static String status(){RuntimePublisher active=instance;return active==null?"等待框架连接，暂不可冻结本体":active.message;}
    private void schedule(){
        message="配置已更改，等待系统同步";
        long expected=generation.incrementAndGet();main.postDelayed(()->{if(expected==generation.get())worker.submit(()->publish(expected));},100);
    }
    private void publish(long expected){
        if(expected!=generation.get())return;
        XposedService current=service;
        if(current==null || android.os.Process.myUid()/100000!=0){message="等待框架连接；独立运行仅支持主用户";return;}
        try {
            String raw=RuntimeSnapshot.from(app);RuntimeSnapshot snapshot=RuntimeSnapshot.parse(raw);
            if(expected!=generation.get())return;
            SharedPreferences target=current.getRemotePreferences(RuntimeSnapshot.GROUP);
            store(target,raw);
            message="框架已保存配置，等待系统确认";
            main.post(()->RuntimeClient.connect(app,()->worker.submit(()->confirm(expected,snapshot,0))));
        }catch(RuntimeException failed){message="配置同步失败，暂不可冻结本体";android.util.Log.w("TaplusBridge","runtime sync failed: "+failed.getClass().getSimpleName());}
    }
    static void store(SharedPreferences target,String raw){
        RuntimeSnapshot.parse(raw);
        if(!raw.equals(target.getString(RuntimeSnapshot.KEY,null)) && !target.edit().putString(RuntimeSnapshot.KEY,raw).commit())
            throw new IllegalStateException("framework_store_failed");
        if(!raw.equals(target.getString(RuntimeSnapshot.KEY,null)))throw new IllegalStateException("framework_store_mismatch");
    }
    private void confirm(long expected,RuntimeSnapshot snapshot,int attempt){
        android.os.Bundle status=RuntimeClient.call(app,"status",null);
        if(expected!=generation.get())return;
        if(status!=null && snapshot.identity.equals(status.getString("identity")) && status.getBoolean("ready")
                && (!snapshot.sourceScope.managed || status.getBoolean("scope_ready"))
                && (!snapshot.scopeCapture || status.getBoolean("scope_capture_ready"))
                && (!snapshot.scopeLinkProbe || status.getBoolean("scope_link_probe_ready")))
            message=snapshot.independent?"系统已确认配置，可冻结本体":"系统已确认配置，独立流转已关闭";
        else if(attempt<5)main.postDelayed(()->worker.submit(()->confirm(expected,snapshot,attempt+1)),250L<<attempt);
        else message="配置已保存，系统尚未就绪；更新后请重启再同步";
    }
}
