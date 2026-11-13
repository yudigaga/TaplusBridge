package io.github.yudigaga.taplusbridge.bridge;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Process;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.translation.TranslationSettings;
import io.github.yudigaga.taplusbridge.translation.TranslationStore;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class SourceSelectionTargetsAndroidTest {
    private static final String SOURCE = "com.example.source";
    private Context context;
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        new ConfigStore(context).setEnabled(true);
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).setPackagesForUid(Process.myUid(), SOURCE);
    }
    @Test public void authenticatedSourceReceivesOnlyPublicFields() {
        Bundle result = SourceSelectionTargets.read(context, Process.myUid(), request());
        assertTrue(result.getBoolean("enabled")); assertEquals(Set.of("enabled", "browser_configured", "translation_configured"), result.keySet());
        assertFalse(result.getBoolean("browser_configured")); assertFalse(result.getBoolean("translation_configured"));
    }
    @Test public void unknownCallerCrossUserAndExtraFieldsAreRejected() {
        assertTrue(SourceSelectionTargets.read(context, Process.myUid() + 100000, request()).isEmpty());
        assertTrue(SourceSelectionTargets.read(context, Process.myUid() + 1, request()).isEmpty());
        Bundle extra = request(); extra.putString("text", "private"); assertTrue(SourceSelectionTargets.read(context, Process.myUid(), extra).isEmpty());
    }
    @Test public void disabledAndExcludedSourcesCannotReceiveActions() {
        ConfigStore store = new ConfigStore(context); store.setEnabled(false);
        assertFalse(SourceSelectionTargets.read(context, Process.myUid(), request()).getBoolean("enabled"));
        store.setEnabled(true);
        context.getSharedPreferences("config", 0).edit().putString(SourceScopePolicy.KEY,
                SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, Set.of(SOURCE), Set.of()).encode()).commit();
        assertFalse(SourceSelectionTargets.read(context, Process.myUid(), request()).getBoolean("enabled"));
    }
    @Test public void appTranslationProjectionNeverIncludesAiCredentialsOrSettingsJson() {
        String pkg = "com.example.translator";
        PackageInfo info = new PackageInfo(); info.packageName = pkg; info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = pkg; info.applicationInfo.enabled = true;
        info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED; info.applicationInfo.nonLocalizedLabel = "Translator";
        ShadowPackageManager packages = Shadow.extract(context.getPackageManager()); packages.installPackage(info);
        packages.setApplicationIcon(pkg, new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE));
        ResolveInfo resolve = new ResolveInfo(); resolve.isDefault = true; resolve.nonLocalizedLabel = "Translator";
        resolve.activityInfo = new ActivityInfo(); resolve.activityInfo.packageName = pkg; resolve.activityInfo.name = pkg + ".Translate";
        resolve.activityInfo.applicationInfo = info.applicationInfo; resolve.activityInfo.exported = true; resolve.activityInfo.enabled = true;
        packages.addResolveInfoForIntent(new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), resolve);
        new TranslationStore(context).save(new TranslationSettings(TranslationSettings.Mode.APP,
                pkg + "/" + pkg + ".Translate", Intent.ACTION_PROCESS_TEXT,
                "https://private.example/api", "super-secret-key", "private-model", "中文", "private-prompt"));
        Bundle result = SourceSelectionTargets.read(context, Process.myUid(), request());
        assertEquals(Set.of("enabled", "browser_configured", "translation_configured", "translation_component", "translation_action", "translation_title", "translation_icon"), result.keySet());
        assertEquals(Intent.ACTION_PROCESS_TEXT, result.getString("translation_action"));
        assertEquals("Translator", result.getString("translation_title"));
        Bitmap icon = result.getParcelable("translation_icon"); assertNotNull(icon); assertEquals(64, icon.getWidth());
        assertFalse(result.toString().contains("super-secret-key")); assertFalse(result.toString().contains("private-prompt"));
    }
    @Test public void nativeAndAiTranslationLeaveSourceTranslationMenuNative() {
        new TranslationStore(context).save(new TranslationSettings(TranslationSettings.Mode.AI, "", "",
                "https://private.example/api", "super-secret-key", "private-model", "中文", "private-prompt"));
        Bundle result = SourceSelectionTargets.read(context, Process.myUid(), request());
        assertTrue(result.getBoolean("enabled")); assertEquals(Set.of("enabled", "browser_configured", "translation_configured"), result.keySet());
        assertFalse(result.getBoolean("translation_configured"));
    }
    @Test public void unavailableSavedTargetsStayConfiguredWithoutExposingSettings() {
        new io.github.yudigaga.taplusbridge.browser.BrowserStore(context).save("com.example.missingbrowser");
        new TranslationStore(context).save(new TranslationSettings(TranslationSettings.Mode.APP,
                "com.example.missingtranslator/.Translate", Intent.ACTION_PROCESS_TEXT, "", "secret", "", "中文", ""));
        Bundle result = SourceSelectionTargets.read(context, Process.myUid(), request());
        assertTrue(result.getBoolean("browser_configured")); assertTrue(result.getBoolean("translation_configured"));
        assertEquals(Set.of("enabled", "browser_configured", "translation_configured"), result.keySet());
    }
    @Test public void componentActionTitleAndIconComeFromTheSameTranslationSnapshot() {
        ShadowPackageManager packages = Shadow.extract(context.getPackageManager());
        String[] apps = {"com.example.firsttranslator", "com.example.secondtranslator"};
        for (int i = 0; i < apps.length; i++) {
            String pkg = apps[i];
            PackageInfo info = new PackageInfo(); info.packageName = pkg; info.applicationInfo = new ApplicationInfo();
            info.applicationInfo.packageName = pkg; info.applicationInfo.enabled = true; info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
            info.applicationInfo.nonLocalizedLabel = i == 0 ? "First translator" : "Second translator";
            packages.installPackage(info);
            packages.setApplicationIcon(pkg, new android.graphics.drawable.ColorDrawable(i == 0 ? android.graphics.Color.RED : android.graphics.Color.BLUE));
            ResolveInfo resolve = new ResolveInfo(); resolve.isDefault = true; resolve.nonLocalizedLabel = info.applicationInfo.nonLocalizedLabel;
            resolve.activityInfo = new ActivityInfo(); resolve.activityInfo.packageName = pkg; resolve.activityInfo.name = pkg + ".Translate";
            resolve.activityInfo.applicationInfo = info.applicationInfo; resolve.activityInfo.enabled = true; resolve.activityInfo.exported = true;
            packages.addResolveInfoForIntent(new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), resolve);
        }
        TranslationSettings first = new TranslationSettings(TranslationSettings.Mode.APP, apps[0] + "/" + apps[0] + ".Translate", Intent.ACTION_PROCESS_TEXT, "", "secret", "", "中文", "");
        TranslationSettings second = new TranslationSettings(TranslationSettings.Mode.APP, apps[1] + "/" + apps[1] + ".Translate", Intent.ACTION_PROCESS_TEXT, "", "secret", "", "中文", "");
        new TranslationStore(context).save(first);
        SharedPreferences actual = context.getSharedPreferences("translation_private", 0);
        AtomicInteger reads = new AtomicInteger();
        SharedPreferences changing = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
            if ("getString".equals(method.getName()) && "settings".equals(args[0])) return reads.getAndIncrement() == 0 ? first.toJson() : second.toJson();
            return method.invoke(actual, args);
        });
        Context changedDuringRead = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return "translation_private".equals(name) ? changing : super.getSharedPreferences(name, mode);
            }
        };
        Bundle result = SourceSelectionTargets.read(changedDuringRead, Process.myUid(), request());
        assertEquals(first.appComponent, result.getString("translation_component"));
        assertEquals(Intent.ACTION_PROCESS_TEXT, result.getString("translation_action"));
        assertEquals("First translator", result.getString("translation_title"));
        assertNotNull(result.getParcelable("translation_icon"));
    }
    private static Bundle request() { Bundle data = new Bundle(); data.putString("source", SOURCE); return data; }
}
