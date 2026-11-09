package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;

/** Module requests always use the ordinary app Provider. */
public final class BridgeTransport {
    private BridgeTransport(){}
    public static boolean moduleUnavailable(Context context) {
        try {
            ApplicationInfo app=context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            return !app.enabled || (app.flags&ApplicationInfo.FLAG_INSTALLED)==0 || (app.flags&ApplicationInfo.FLAG_SUSPENDED)!=0;
        }catch(PackageManager.NameNotFoundException|RuntimeException unavailable){return true;}
    }
    public static Bundle call(Context context,String method,Bundle input) {
        return context.getContentResolver().call(Uri.parse("content://"+BridgeContract.AUTHORITY),method,null,input);
    }
}
