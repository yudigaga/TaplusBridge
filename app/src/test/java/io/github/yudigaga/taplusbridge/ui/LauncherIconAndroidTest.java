package io.github.yudigaga.taplusbridge.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LauncherIconAndroidTest {
    private static final String APP = BridgeContract.APP_PACKAGE;
    private static final ComponentName ALIAS = new ComponentName(APP, APP + ".ui.LauncherAlias");
    private static final ComponentName SETTINGS = new ComponentName(APP, APP + ".ui.MainActivity");
    private Context context;
    private PackageManager pm;
    private SettingsController controller;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication(); pm = context.getPackageManager();
        controller = new SettingsController(Robolectric.buildActivity(Activity.class).setup().get());
    }
    @After public void close() { controller.close(); }
    private void hidden(boolean value) throws Exception {
        SettingsController.class.getMethod("setLauncherIconHidden", boolean.class).invoke(controller, value);
    }
    private boolean hidden() throws Exception {
        return SettingsController.Snapshot.class.getField("launcherIconHidden").getBoolean(controller.snapshot());
    }

    @Test public void manifestSeparatesTheLauncherFromTheRecoverableModuleSettingsEntry() throws Exception {
        var alias = pm.getActivityInfo(ALIAS, PackageManager.MATCH_DISABLED_COMPONENTS);
        assertEquals(SETTINGS.getClassName(), alias.targetActivity);
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(APP);
        var entries = pm.queryIntentActivities(launcher, 0);
        assertEquals(1, entries.size()); assertEquals(ALIAS.getClassName(), entries.get(0).activityInfo.name);
        Intent settings = new Intent(Intent.ACTION_MAIN)
                .addCategory("de.robv.android.xposed.category.MODULE_SETTINGS").setPackage(APP);
        assertEquals(SETTINGS.getClassName(), pm.resolveActivity(settings, 0).activityInfo.name);
    }
    @Test public void hidingAndRestoringOnlyChangesTheLauncherAlias() throws Exception {
        assertFalse(hidden());
        int mainState = pm.getComponentEnabledSetting(SETTINGS);
        hidden(true);
        assertTrue(hidden()); assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(ALIAS));
        assertEquals(mainState, pm.getComponentEnabledSetting(SETTINGS));
        assertTrue(pm.getApplicationInfo(APP, 0).enabled);
        assertNotNull(pm.getActivityInfo(SETTINGS, 0));
        hidden(false); assertFalse(hidden());
        assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(ALIAS));
    }
    @Test public void snapshotReadsTheActualPackageManagerState() throws Exception {
        pm.setComponentEnabledSetting(ALIAS, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        assertTrue(hidden());
        pm.setComponentEnabledSetting(ALIAS, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
        assertFalse(hidden());
    }
    @Test public void hiddenIconKeepsModuleSettingsAndBusinessComponentsAvailable() throws Exception {
        hidden(true);
        assertNotNull(pm.getActivityInfo(SETTINGS, 0));
        assertNotNull(pm.resolveContentProvider(BridgeContract.AUTHORITY, 0));
        assertTrue(pm.getServiceInfo(new ComponentName(APP, APP + ".lens.LensService"), 0).enabled);
        Intent settings = new Intent(Intent.ACTION_MAIN)
                .addCategory("de.robv.android.xposed.category.MODULE_SETTINGS").setPackage(APP);
        assertEquals(SETTINGS.getClassName(), pm.resolveActivity(settings, 0).activityInfo.name);
    }
    @Test public void iconVisibilityDoesNotChangeRoutingOrGestureConfigurationIdentity() throws Exception {
        String identity = RuntimeSnapshot.parse(RuntimeSnapshot.from(context)).identity;
        hidden(true);
        assertEquals(identity, RuntimeSnapshot.parse(RuntimeSnapshot.from(context)).identity);
        hidden(false);
        assertEquals(identity, RuntimeSnapshot.parse(RuntimeSnapshot.from(context)).identity);
    }
}
