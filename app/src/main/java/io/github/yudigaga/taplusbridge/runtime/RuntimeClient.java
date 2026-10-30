package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import java.util.concurrent.atomic.AtomicBoolean;

/** Obtain an authenticated Binder from system_server without touching the module's components. */
public final class RuntimeClient {
    public static final String BIND="io.github.yudigaga.taplusbridge.RUNTIME_BIND_V1";
    public static final String OPEN="io.github.yudigaga.taplusbridge.RUNTIME_OPEN_V1";
    private static volatile IRuntimeBridge remote;
    private static final AtomicBoolean connecting=new AtomicBoolean();
    private RuntimeClient(){}
    public static void connect(Context context,Runnable changed) {
        if(context==null || android.os.Build.VERSION.SDK_INT<33)return;
        IRuntimeBridge current=remote;
        if(current!=null && current.asBinder().isBinderAlive()){if(changed!=null)changed.run();return;}
        if(!connecting.compareAndSet(false,true))return;
        Handler main=new Handler(Looper.getMainLooper());
        ResultReceiver local=new ResultReceiver(main){
            @Override protected void onReceiveResult(int code,Bundle data){
                connecting.set(false);
                if(code!=1 || data==null)return;
                IBinder binder=data.getBinder("bridge");
                if(binder==null || !binder.isBinderAlive())return;
                remote=IRuntimeBridge.Stub.asInterface(binder);
                try{binder.linkToDeath(()->{if(remote!=null && remote.asBinder()==binder)remote=null;},0);}catch(android.os.RemoteException ignored){remote=null;}
                if(changed!=null)changed.run();
            }
        };
        Parcel parcel=Parcel.obtain();
        try {
            local.writeToParcel(parcel,0);parcel.setDataPosition(0);
            ResultReceiver transferable=ResultReceiver.CREATOR.createFromParcel(parcel);
            context.sendBroadcast(new Intent(BIND).setPackage("android").putExtra("reply",transferable));
            main.postDelayed(()->connecting.set(false),2500);
        }catch(RuntimeException unavailable){connecting.set(false);}finally{parcel.recycle();}
    }
    public static Bundle call(Context context,String method,Bundle request) {
        IRuntimeBridge current=remote;
        if(current==null){connect(context,null);return null;}
        try{return current.call(method,request);}catch(android.os.RemoteException|RuntimeException failed){remote=null;connect(context,null);return null;}
    }
    static void testBridge(IRuntimeBridge bridge){remote=bridge;connecting.set(false);}
}
