package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Obtain an authenticated Binder from system_server without touching the module's components. */
public final class RuntimeClient {
    public static final String BIND="io.github.yudigaga.taplusbridge.RUNTIME_BIND_V1";
    private static final long SCOPE_RECORDING_RISK_WAIT_MS=16;
    private static final ThreadPoolExecutor SCOPE_RECORDING_RISK_CALLS=createScopeRecordingRiskCalls();
    private static final AtomicReference<IRuntimeBridge> remote=new AtomicReference<>();
    private static final AtomicBoolean connecting=new AtomicBoolean();
    public enum CallState {
        /** No transaction was attempted; connection discovery may be in progress. */
        NOT_CONNECTED,
        /** A non-null reply arrived, including an explicit rejection. */
        REPLIED,
        /** Invocation was attempted, but its effects cannot be established from the reply. */
        OUTCOME_UNKNOWN
    }
    public record CallResult(CallState state,Bundle response) { }
    private RuntimeClient(){}
    public static void connect(Context context,Runnable changed) {
        if(context==null || android.os.Build.VERSION.SDK_INT<33)return;
        IRuntimeBridge current=remote.get();
        if(alive(current)){notifyChanged(changed);return;}
        if(current!=null)remote.compareAndSet(current,null);
        if(!connecting.compareAndSet(false,true))return;
        try {
            Handler main=new Handler(Looper.getMainLooper());
            ResultReceiver local=new ResultReceiver(main){
                @Override protected void onReceiveResult(int code,Bundle data){
                    connecting.set(false);
                    IRuntimeBridge connected=null;
                    try{
                        if(code!=1 || data==null)return;
                        IBinder binder=data.getBinder("bridge");
                        if(binder==null || !binder.isBinderAlive())return;
                        connected=IRuntimeBridge.Stub.asInterface(binder);
                        if(!alive(connected))return;
                        remote.set(connected);
                        IRuntimeBridge installed=connected;
                        binder.linkToDeath(()->remote.compareAndSet(installed,null),0);
                        if(remote.get()!=connected)return;
                        if(!binder.isBinderAlive() || !alive(connected)){
                            remote.compareAndSet(connected,null);return;
                        }
                    }catch(android.os.RemoteException|RuntimeException unavailable){
                        if(connected!=null)remote.compareAndSet(connected,null);
                        return;
                    }
                    notifyChanged(changed);
                }
            };
            Parcel parcel=Parcel.obtain();
            try{
                local.writeToParcel(parcel,0);parcel.setDataPosition(0);
                ResultReceiver transferable=ResultReceiver.CREATOR.createFromParcel(parcel);
                context.sendBroadcast(new Intent(BIND).setPackage("android").putExtra("reply",transferable));
                main.postDelayed(()->connecting.set(false),2500);
            }finally{parcel.recycle();}
        }catch(RuntimeException unavailable){connecting.set(false);}
    }
    public static Bundle call(Context context,String method,Bundle request) {
        return callDetailed(context,method,request).response();
    }
    /**
     * Bounds caller wait only. A dispatched Binder call may ignore interruption; at most two such
     * calls can occupy this daemon pool.
     */
    public static CallResult callScopeRecordingRisk(Context context,Bundle request) {
        return callScopeRecordingRisk(context,request,SCOPE_RECORDING_RISK_WAIT_MS);
    }
    static CallResult callScopeRecordingRisk(Context context,Bundle request,long waitMillis) {
        Future<CallResult> pending;
        try{
            pending=SCOPE_RECORDING_RISK_CALLS.submit(
                    ()->callDetailed(context,"scope_recording_risk",request));
        }catch(RejectedExecutionException saturated){
            return new CallResult(CallState.OUTCOME_UNKNOWN,null);
        }
        try{
            return pending.get(waitMillis,TimeUnit.MILLISECONDS);
        }catch(InterruptedException interrupted){
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return new CallResult(CallState.OUTCOME_UNKNOWN,null);
        }catch(TimeoutException|ExecutionException|CancellationException unavailable){
            pending.cancel(true);
            return new CallResult(CallState.OUTCOME_UNKNOWN,null);
        }
    }
    /** Reports transport evidence only; callers must interpret replies and must not infer retry safety. */
    public static CallResult callDetailed(Context context,String method,Bundle request) {
        IRuntimeBridge current=remote.get();
        if(!alive(current)){
            if(current!=null)remote.compareAndSet(current,null);
            connect(context,null);return new CallResult(CallState.NOT_CONNECTED,null);
        }
        try{
            Bundle reply=current.call(method,request);
            return new CallResult(reply==null?CallState.OUTCOME_UNKNOWN:CallState.REPLIED,reply);
        }catch(android.os.RemoteException|RuntimeException failed){
            remote.compareAndSet(current,null);connect(context,null);
            return new CallResult(CallState.OUTCOME_UNKNOWN,null);
        }
    }
    private static ThreadPoolExecutor createScopeRecordingRiskCalls(){
        AtomicInteger threadIds=new AtomicInteger();
        ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),task->{
                    Thread thread=new Thread(task,"TaplusBridge-scope-recording-risk-"+threadIds.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        return executor;
    }
    private static boolean alive(IRuntimeBridge bridge){
        if(bridge==null)return false;
        try{
            IBinder binder=bridge.asBinder();
            return binder!=null && binder.isBinderAlive();
        }catch(RuntimeException unavailable){return false;}
    }
    private static void notifyChanged(Runnable changed){
        if(changed!=null)try{changed.run();}catch(RuntimeException unavailable){}
    }
    static void testBridge(IRuntimeBridge bridge){remote.set(bridge);connecting.set(false);}
}
