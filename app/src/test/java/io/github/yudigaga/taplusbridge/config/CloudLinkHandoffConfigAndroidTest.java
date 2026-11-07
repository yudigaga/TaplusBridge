package io.github.yudigaga.taplusbridge.config;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.io.File;
import java.lang.reflect.Proxy;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class CloudLinkHandoffConfigAndroidTest {
    private Context context;
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", 0).edit().clear().commit();
    }

    @Test public void theDefaultIsEnabledButAnExplicitOffSurvivesColdDiskReload() throws Exception {
        assertTrue(new ConfigStore(context).cloudLinkHandoffEnabled());
        new ConfigStore(context).setCloudLinkHandoffEnabled(false);
        File file = new File(context.getApplicationInfo().dataDir, "shared_prefs/config.xml");
        var constructor = Class.forName("android.app.SharedPreferencesImpl").getDeclaredConstructor(File.class, int.class);
        constructor.setAccessible(true);
        SharedPreferences cold = (SharedPreferences) constructor.newInstance(file, 0);
        Context reloaded = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return "config".equals(name) ? cold : super.getSharedPreferences(name, mode);
            }
        };
        assertFalse(new ConfigStore(reloaded).cloudLinkHandoffEnabled());
        assertTrue(cold.contains(ConfigStore.CLOUD_LINK_HANDOFF_KEY));
    }

    @Test public void aMalformedSavedBooleanCannotEnableTheAdapter() {
        context.getSharedPreferences("config", 0).edit().putString(ConfigStore.CLOUD_LINK_HANDOFF_KEY, "true").commit();
        assertFalse(new ConfigStore(context).cloudLinkHandoffEnabled());
    }

    @Test public void aFailedCommitRaisesAnErrorAndDoesNotRefreshTheHost() {
        SharedPreferences original = context.getSharedPreferences("config", 0);
        SharedPreferences rejected = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!"edit".equals(method.getName())) return method.invoke(original, args);
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, values) ->
                                    "commit".equals(operation.getName()) ? false : editor);
                });
        int[] broadcasts = {0};
        Context failure = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return "config".equals(name) ? rejected : super.getSharedPreferences(name, mode);
            }
            @Override public void sendBroadcast(Intent intent) { broadcasts[0]++; }
        };
        assertThrows(IllegalStateException.class, () -> new ConfigStore(failure).setCloudLinkHandoffEnabled(false));
        assertEquals(0, broadcasts[0]);
        assertFalse(original.contains(ConfigStore.CLOUD_LINK_HANDOFF_KEY));
    }

    @Test public void successfulCommitRefreshesOnlyTheHostWithNoSettingsPayload() {
        int[] broadcasts = {0};
        Context recording = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public void sendBroadcast(Intent intent) {
                broadcasts[0]++;
                assertEquals(ConfigStore.SOURCE_SCOPE_CHANGED, intent.getAction());
                assertEquals(HostPolicy.HOST_PACKAGE, intent.getPackage());
                assertNull(intent.getExtras()); assertNull(intent.getData());
                assertFalse(getSharedPreferences("config", 0).getBoolean(ConfigStore.CLOUD_LINK_HANDOFF_KEY, true));
            }
        };
        new ConfigStore(recording).setCloudLinkHandoffEnabled(false);
        assertEquals(1, broadcasts[0]);
    }
}
