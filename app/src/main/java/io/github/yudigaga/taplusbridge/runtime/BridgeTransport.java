package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;

/** Existing app path while available; no Provider acquisition when the module package is frozen. */
public final class BridgeTransport {
    private static volatile boolean hooked;
    private BridgeTransport(){}
    public static void install(){hooked=true;}
    static void testReset(){hooked=false;RuntimeClient.testBridge(null);}
    public static boolean moduleUnavailable(Context context) {
        try {
            ApplicationInfo app=context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            return !app.enabled || (app.flags&ApplicationInfo.FLAG_INSTALLED)==0 || (app.flags&ApplicationInfo.FLAG_SUSPENDED)!=0;
        }catch(PackageManager.NameNotFoundException|RuntimeException unavailable){return true;}
    }
    public static Bundle call(Context context,String method,Bundle input) {
        if(hooked && moduleUnavailable(context)) {
            Bundle result=RuntimeClient.call(context,method,input);
            if(result!=null)return result;
            Bundle failed=new Bundle();failed.putString("reason","runtime_unavailable");return failed;
        }
        return context.getContentResolver().call(Uri.parse("content://"+BridgeContract.AUTHORITY),method,null,input);
    }
}
