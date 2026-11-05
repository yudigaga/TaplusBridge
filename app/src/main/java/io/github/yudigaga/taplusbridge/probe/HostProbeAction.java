package io.github.yudigaga.taplusbridge.probe;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.capture.CaptureBroker;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import io.github.yudigaga.taplusbridge.hook.HostDragHooks;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Automatic, silent per-pick operation. Only physical-UP-confirmed system READY permits input. */
public final class HostProbeAction {
    private static final Uri PROVIDER=Uri.parse("content://"+BridgeContract.AUTHORITY);
    private static final AtomicReference<Run> ACTIVE=new AtomicReference<>();
    private HostProbeAction() { }
    public static void start(Context context,Object service,String source,long pickedAt,long recognitionAt,
                             BooleanSupplier current,Consumer<String> captured) {
        start(context,service,source,pickedAt,recognitionAt,current,() -> true,captured);
    }
    public static void start(Context context,Object service,String source,long pickedAt,long recognitionAt,
                             BooleanSupplier current,BooleanSupplier prepareForTap,Consumer<String> captured) {
        Handler main=new Handler(Looper.getMainLooper());
        main.post(() -> {
            Run run=new Run(context,service,source,pickedAt,recognitionAt,current,prepareForTap,captured,main);
            Run previous=ACTIVE.getAndSet(run);
            if(previous!=null) previous.finish(null,"replaced");
            run.begin();
        });
    }
    public static void cancelActive() {
        Run run=ACTIVE.get();
        if(run!=null) run.main.post(() -> run.finish(null,"cancelled"));
    }
    private static final class Run {
        final Context context; final Object service; final String source; final long pickedAt, recognitionAt;
        final BooleanSupplier current,prepareForTap; final Consumer<String> captured; final Handler main;
        HostProbeWindow window; String nonce; int user=-1; boolean ready,finished,tapSent;
        final Runnable timeout=() -> finish(null,tapSent?"capture_timeout":"ready_timeout");
        Run(Context context,Object service,String source,long pickedAt,long recognitionAt,BooleanSupplier current,
            BooleanSupplier prepareForTap,Consumer<String> captured,Handler main) {
            this.context=context;this.service=service;this.source=source;this.pickedAt=pickedAt;
            this.recognitionAt=recognitionAt;
            this.current=current;this.prepareForTap=prepareForTap;this.captured=captured;this.main=main;
        }
        void begin() {
            try {
                if(recognitionAt<=0) { finish(null,"stale_session");return; }
                if(!current.getAsBoolean()) { finish(null,"stale_session");return; }
                Bundle request=new Bundle();request.putString("source",source);
                Bundle ticket=io.github.yudigaga.taplusbridge.runtime.BridgeTransport.call(context,"probe_arm",request);
                nonce=ticket==null?null:ticket.getString("nonce");
                user=ticket==null?-1:ticket.getInt("user",-1);
                if(!CaptureBroker.validNonce(nonce) || user!=android.os.Process.myUid()/100000)
                    throw new IllegalStateException("capability");
                status("waiting_release","waiting_release");
                window=HostProbeWindow.prepare(service,source,pickedAt);
                ResultReceiver callback=new ResultReceiver(main) {
                    @Override protected void onReceiveResult(int code,Bundle data) { receive(code,data); }
                };
                Bundle payload=window.snapshot();payload.putAll(envelope());
                if(recognitionAt>0)payload.putLong("recognition_at",recognitionAt);
                payload.putParcelable("ack",frameworkReceiver(callback));
                main.postDelayed(timeout,ProbeContract.TIMEOUT_MILLIS);
                context.sendBroadcast(new Intent(ProbeContract.ARM).setPackage("android").putExtras(payload));
            } catch(ReflectiveOperationException|RuntimeException failed) {
                android.util.Log.i("TaplusBridge", "probe stage=geometry_failed reason="
                        +HostProbeWindow.failureReason(failed)+" exception="+failed.getClass().getSimpleName());
                finish(null,"geometry_unavailable");
            }
        }
        Bundle envelope() {
            Bundle data=new Bundle();data.putInt("protocol",ProbeContract.VERSION);
            data.putString("mode",ProbeContract.MODE);data.putString("nonce",nonce);data.putInt("user",user);return data;
        }
        boolean authentic(Bundle data) {
            return data!=null && nonce!=null && nonce.equals(data.getString("nonce"))
                    && data.getInt("user",-2)==user && data.getInt("protocol",-1)==ProbeContract.VERSION
                    && ProbeContract.MODE.equals(data.getString("mode"));
        }
        void receive(int code,Bundle data) {
            if(finished)return;
            try {
                if(!current.getAsBoolean()) { finish(null,"stale_session");return; }
                if(!authentic(data)) { finish(null,"protocol");return; }
                if(code==ProbeContract.READY) {
                    if(ready)return;
                    if(!data.getBoolean("block_ready") || !data.getBoolean("release_confirmed")) { finish(null,"protocol");return; }
                    ready=true;
                    try { window.passthrough(); }
                    catch (ReflectiveOperationException | RuntimeException failure) {
                        finish(null,"source_changed"); return;
                    }
                    boolean tapReady;
                    try { tapReady=prepareForTap.getAsBoolean(); }
                    catch (RuntimeException unavailable) { tapReady=false; }
                    if(!tapReady) { finish(null,"window_occluded"); return; }
                    // This only lets the host window update settle; physical release is proven above
                    // and is checked again by system_server, never inferred from this delay.
                    main.postDelayed(() -> {
                        if(finished || tapSent)return;
                        if(!current.getAsBoolean()) { finish(null,"stale_session");return; }
                        try {
                            window.snapshot();tapSent=true;
                            context.sendBroadcast(new Intent(ProbeContract.TAP).setPackage("android").putExtras(envelope()));
                        } catch(RuntimeException failure) { finish(null,"source_changed"); }
                    },64);
                } else if(code==ProbeContract.CAPTURED && ready && tapSent) {
                    String url=data.getString("url");
                    if(!IntentObservation.validHttpUrl(url)) { finish(null,"no_unique_url");return; }
                    android.util.Log.i("TaplusBridge","probe stage=host_result outcome=captured");
                    finish(url,null);
                } else if(code==ProbeContract.FAILED) { finish(null,safeReason(data.getString("reason"))); }
                else finish(null,"protocol");
            } catch(RuntimeException malformed) { finish(null,"invalid_response"); }
        }
        void status(String stage,String reason) {
            if(nonce==null)return;
            try {
                Bundle data=new Bundle();data.putString("nonce",nonce);data.putString("stage",stage);data.putString("reason",reason);
                io.github.yudigaga.taplusbridge.runtime.BridgeTransport.call(context,"probe_status",data);
            } catch(RuntimeException ignored) { }
        }
        void finish(String url,String reason) {
            if(finished)return;finished=true;main.removeCallbacks(timeout);
            if(window!=null)try { window.close(); } catch(RuntimeException ignored) { }
            if(url==null) {
                String fixed=safeReason(reason);
                status("cancelled".equals(fixed)?"cancelled":"failed",fixed);
                android.util.Log.i("TaplusBridge","probe stage=auto_failed reason="+fixed);
                if(nonce!=null)try {
                    Bundle data=new Bundle();data.putString("nonce",nonce);
                    io.github.yudigaga.taplusbridge.runtime.BridgeTransport.call(context,"capture_cancel",data);
                } catch(RuntimeException ignored) { }
            }
            ACTIVE.compareAndSet(this,null);
            if(url!=null && current.getAsBoolean())captured.accept(url);
        }
    }
    private static String safeReason(String reason) {
        if(reason==null)return "unavailable";
        return switch(reason) {
            case "replaced","cancelled","stale_session","ready_timeout","capture_timeout","geometry_unavailable",
                    "protocol","source_changed","window_occluded","window_unavailable","input_unavailable",
                    "no_unique_url","unreadable","unsupported_request","capture_unavailable","touch_active","touch_unknown",
                    "snapshot","invalid_response","unavailable" -> reason;
            default -> "unavailable";
        };
    }
    private static ResultReceiver frameworkReceiver(ResultReceiver callback) {
        Parcel parcel=Parcel.obtain();
        try { callback.writeToParcel(parcel,0);parcel.setDataPosition(0);return ResultReceiver.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }
}
