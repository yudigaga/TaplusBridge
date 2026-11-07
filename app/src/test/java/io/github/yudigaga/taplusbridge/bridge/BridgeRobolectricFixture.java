package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import org.junit.Before;
import org.robolectric.RuntimeEnvironment;

/** Shared real Android Intent, preferences and PackageManager fixture; no device or root access. */
abstract class BridgeRobolectricFixture {
    static final String BILI = "tv.danmaku.bili";
    static final String NATIVE = "com.example.reader";
    Context context;
    ConfigStore config;

    @Before
    public void resetBridgePreferences() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("launch_tickets", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("status", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(io.github.yudigaga.taplusbridge.rules.RuleRepository.PREFS,
                Context.MODE_PRIVATE).edit().clear().commit();
        config = new ConfigStore(context);
    }

    @SuppressWarnings("deprecation")
    void install(String packageName, boolean installed, boolean enabled) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.versionCode = 1;
        info.versionName = "1";
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = packageName;
        info.applicationInfo.uid = Process.myUid();
        info.applicationInfo.enabled = enabled;
        info.applicationInfo.flags = installed ? ApplicationInfo.FLAG_INSTALLED : 0;
        org.robolectric.shadows.ShadowPackageManager pm =
                org.robolectric.shadow.api.Shadow.extract(context.getPackageManager());
        if (installed) {
            pm.installPackage(info);
        } else {
            // installPackage() deliberately adds FLAG_INSTALLED. Retained package metadata
            // after a per-user uninstall must instead keep that flag clear.
            pm.addPackageNoDefaults(info);
        }
    }

    Bundle web(String url) {
        Bundle request = new Bundle();
        request.putString("url", url);
        request.putString("source", "com.example.source");
        request.putParcelable("original", new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        return request;
    }

    Bundle nativeRequest(Intent candidate) {
        Bundle request = web("https://not-a-configured-route.example/path");
        request.putParcelable("candidate", candidate);
        return request;
    }
}
