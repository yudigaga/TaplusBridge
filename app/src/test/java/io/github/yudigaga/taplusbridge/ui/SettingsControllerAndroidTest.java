package io.github.yudigaga.taplusbridge.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.capture.CaptureBroker;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.routing.RouteRule;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import io.github.yudigaga.taplusbridge.thaw.ThawEngine;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import static org.junit.Assert.*;

/** The Compose-facing adapter still uses real preferences, PM, matcher and cancellation broker. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SettingsControllerAndroidTest {
    private static final String BILI = "tv.danmaku.bili";
    private static final String WECHAT = "com.tencent.mm";
    private static final String URL = "https://www.bilibili.com/video/BV1xx411c7mD";
    private Context context;
    private ConfigStore config;
    private ActivityController<Activity> owner;
    private SettingsController controller;
    private QueuedExecutor worker;
    private ShadowLooper main;
    private FakeAuthorization authorization;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().putInt("seed_version", 2).commit();
        context.getSharedPreferences("capture", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("status", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        config = new ConfigStore(context);
        config.saveRules(new ArrayList<>());
        owner = Robolectric.buildActivity(Activity.class).setup();
        worker = new QueuedExecutor();
        ThawEngine.Result inertResult = ThawEngine.thaw(context, null, ThawEngine.Mode.ROOT);
        authorization = new FakeAuthorization();
        controller = new SettingsController(owner.get(), worker, () -> inertResult, null, authorization);
        main = Shadow.extract(Looper.getMainLooper());
    }
    @After public void tearDown() {
        controller.close();
        if (!owner.get().isDestroyed()) owner.pause().stop().destroy();
    }

    @Test public void settingsJobsHoldTheProcessOnlyWhileTheirWorkIsRunning() throws Exception {
        Class<?> lifecycle=io.github.yudigaga.taplusbridge.runtime.ModuleProcessLifecycle.class;
        Method install=lifecycle.getDeclaredMethod("installForTest",android.os.Handler.class,Runnable.class);install.setAccessible(true);
        Method count=lifecycle.getDeclaredMethod("activeLeaseCountForTest");count.setAccessible(true);
        Method reset=lifecycle.getDeclaredMethod("resetForTest");reset.setAccessible(true);
        install.invoke(null,new android.os.Handler(Looper.getMainLooper()),(Runnable)()->{});
        try {
            controller.close();worker=new QueuedExecutor();AtomicInteger duringWork=new AtomicInteger();
            controller=new SettingsController(owner.get(),worker,()->{
                try { duringWork.set((Integer)count.invoke(null)); }
                catch(Exception failure){throw new AssertionError(failure);}
                return ThawEngine.thaw(context,null,ThawEngine.Mode.ROOT);
            });
            controller.probeRoot(ignored->{});
            assertEquals("Queued work has no hold to leak if cancelled",0,((Integer)count.invoke(null)).intValue());
            worker.runNext();
            assertEquals("An active settings request must survive page backgrounding",1,duringWork.get());
            assertEquals("Completed settings work releases its hold",0,((Integer)count.invoke(null)).intValue());
        } finally { reset.invoke(null); }
    }

    @Test public void theCloudAdapterSwitchUpdatesTheSnapshotAndCannotBeSavedAfterClosing() {
        assertTrue(controller.snapshot().cloudLinkHandoffEnabled);
        controller.setCloudLinkHandoffEnabled(false);
        assertFalse(controller.snapshot().cloudLinkHandoffEnabled);
        assertFalse(new ConfigStore(context).cloudLinkHandoffEnabled());
        controller.setCloudLinkHandoffEnabled(true);
        assertTrue(controller.snapshot().cloudLinkHandoffEnabled);
        controller.close();
        assertThrows(IllegalStateException.class, () -> controller.setCloudLinkHandoffEnabled(false));
    }

    @SuppressWarnings("deprecation")
    private void install(String pkg, String label, boolean enabled, boolean installed, long version, String versionName) {
        PackageInfo info = new PackageInfo(); info.packageName = pkg; info.setLongVersionCode(version); info.versionName = versionName;
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = pkg;
        info.applicationInfo.nonLocalizedLabel = label; info.applicationInfo.uid = Process.myUid() / 100000 * 100000 + 21001;
        info.applicationInfo.enabled = enabled; info.applicationInfo.flags = installed ? ApplicationInfo.FLAG_INSTALLED : 0;
        ShadowPackageManager pm = Shadow.extract(context.getPackageManager());
        if (installed) pm.installPackage(info); else pm.addPackageNoDefaults(info);
    }
    private void install(String pkg, String label, boolean enabled) { install(pkg, label, enabled, true, 1, "1"); }

    @Test public void snapshotReportsInspectedHostCompatibilityWithoutClaimingFrameworkActivation() {
        install(BridgeContract.HOST_PACKAGE, "传送门", true, true, 40013, "4.1.3");
        // Obsolete diagnostic preferences are no longer read or exposed by the settings snapshot.
        context.getSharedPreferences("status", Context.MODE_PRIVATE).edit()
                .putString("last_request", "legacy metadata").putString("recognition_links", "legacy metadata").commit();
        SettingsController.Snapshot found = controller.snapshot();
        assertTrue(found.hostInstalled); assertTrue(found.hostSupported);
        assertEquals("4.1.3 / 40013", found.hostVersion);
        assertEquals("Shizuku 未运行", found.shizukuStatus); assertFalse(found.shizukuGranted);
        assertEquals(0, authorization.requests);
        install(BridgeContract.HOST_PACKAGE, "传送门", true, true, 40014, "4.1.4");
        assertTrue(controller.snapshot().hostInstalled); assertFalse(controller.snapshot().hostSupported);
    }
    @Test public void missingHostStillShowsAuthorizationStateWithoutRequestingIt() {
        SettingsController.Snapshot found = controller.snapshot();
        assertFalse(found.hostInstalled); assertFalse(found.hostSupported);
        assertEquals("未安装传送门", found.hostVersion);
        assertEquals("Shizuku 未运行", found.shizukuStatus); assertFalse(found.shizukuGranted);
        assertEquals(0, authorization.requests);
    }
    @Test public void disablingMasterCancelsTheRealPendingCaptureAndPersistsSwitch() {
        install(WECHAT, "微信", true);
        String nonce = CaptureBroker.arm(context, WECHAT).getString("nonce");
        assertTrue(CaptureBroker.validNonce(nonce));
        controller.setEnabled(false);
        assertFalse(controller.snapshot().enabled); assertFalse(new ConfigStore(context).enabled());
        assertEquals("捕获已取消", CaptureBroker.status(context));
        assertTrue(CaptureBroker.take(context, nonce).isEmpty());
    }
    @Test public void disablingCaptureDoesNotChangeMasterButCancelsCapture() {
        install(WECHAT, "微信", true);
        String nonce = CaptureBroker.arm(context, WECHAT).getString("nonce");
        controller.setCaptureEnabled(false);
        assertTrue(controller.snapshot().enabled); assertFalse(controller.snapshot().captureEnabled);
        assertTrue(CaptureBroker.take(context, nonce).isEmpty());
        assertEquals("捕获已取消", CaptureBroker.status(context));
    }
    @Test public void disablingRecognitionAlsoCancelsThePendingAutomaticCapture() {
        install(WECHAT, "微信", true);
        String nonce = CaptureBroker.arm(context, WECHAT).getString("nonce");
        controller.setRecognitionEnabled(false);
        assertTrue(controller.snapshot().enabled); assertFalse(controller.snapshot().recognitionEnabled);
        assertTrue(CaptureBroker.take(context, nonce).isEmpty());
        assertEquals("捕获已取消", CaptureBroker.status(context));
    }
    @Test public void switchesAndThawModeKeepTheExistingConfigurationKeys() {
        controller.setNativeEnabled(false); controller.setRecognitionEnabled(false); controller.setMode(ThawEngine.Mode.SHIZUKU);
        SettingsController.Snapshot state = controller.snapshot();
        assertFalse(state.nativeEnabled); assertFalse(state.recognitionEnabled); assertEquals(ThawEngine.Mode.SHIZUKU, state.mode);
        ConfigStore reloaded = new ConfigStore(context);
        assertFalse(reloaded.nativeEnabled()); assertFalse(reloaded.recognitionEnabled()); assertEquals(ThawEngine.Mode.SHIZUKU, reloaded.mode());
    }

    @Test public void lensTogglePersistsSeparatelyFromLinkEnhancement() {
        assertTrue(controller.snapshot().lensEnabled);
        controller.setLensEnabled(false);
        assertFalse(controller.snapshot().lensEnabled);
        assertFalse(new ConfigStore(context).lensEnabled());
        assertTrue(controller.snapshot().recognitionEnabled);
        controller.setLensEnabled(true);
        controller.setEnabled(false);
        assertTrue(controller.snapshot().lensEnabled);
    }
    @Test public void shareTargetsMergeMimeTypesWithoutDuplicatingAnExactComponent() {
        ShadowPackageManager pm = Shadow.extract(context.getPackageManager());
        ResolveInfo both = shareTarget("com.example.app", "com.example.app.Share", "Both", true);
        ResolveInfo anotherActivity = shareTarget("com.example.app", "com.example.app.Story", "Story", true);
        ResolveInfo privateTarget = shareTarget("com.example.privateapp", "com.example.privateapp.Share", "Private", false);
        ResolveInfo module = shareTarget(BridgeContract.APP_PACKAGE, BridgeContract.APP_PACKAGE + ".Share", "Module", true);
        Intent text = new Intent(Intent.ACTION_SEND).setType("text/plain"), image = new Intent(Intent.ACTION_SEND).setType("image/*");
        pm.addResolveInfoForIntent(text, both); pm.addResolveInfoForIntent(image, both);
        pm.addResolveInfoForIntent(image, anotherActivity); pm.addResolveInfoForIntent(text, privateTarget); pm.addResolveInfoForIntent(text, module);
        List<SettingsController.ShareTarget> found = controller.installedShareTargets();
        assertEquals(List.of("com.example.app/com.example.app.Share", "com.example.app/com.example.app.Story"),
                found.stream().map(value -> value.component).toList());
    }

    private ResolveInfo shareTarget(String pkg, String activity, String label, boolean exported) {
        install(pkg, label, true);
        ResolveInfo target = new ResolveInfo(); target.isDefault = true; target.nonLocalizedLabel = label;
        target.activityInfo = new ActivityInfo(); target.activityInfo.packageName = pkg; target.activityInfo.name = activity;
        target.activityInfo.exported = exported; target.activityInfo.enabled = true;
        try { target.activityInfo.applicationInfo = context.getPackageManager().getApplicationInfo(pkg, 0); }
        catch (android.content.pm.PackageManager.NameNotFoundException impossible) { throw new AssertionError(impossible); }
        return target;
    }

    @Test public void closedControllerCannotSaveDragSettings() {
        controller.close();
        assertThrows(IllegalStateException.class, () -> controller.setDragSettings(
                new io.github.yudigaga.taplusbridge.config.DragSettings(true, "portal", "share", "lens", "translate", false, List.of())));
        assertFalse(config.getDragSettings().enabled);
    }
    @Test public void sourceScopeSaveKeepsBothListsIncludingUninstalledApplications() {
        assertFalse(controller.sourceScope().managed);
        controller.saveSourceScope(SourceScopePolicy.Mode.WHITELIST,
                List.of(WECHAT), List.of(BILI, "com.example.notinstalled"));
        SourceScopePolicy reloaded = new ConfigStore(context).sourceScope();
        assertTrue(reloaded.managed);
        assertEquals(SourceScopePolicy.Mode.WHITELIST, reloaded.mode);
        assertEquals(Set.of(WECHAT), reloaded.blacklist);
        assertEquals(Set.of(BILI, "com.example.notinstalled"), reloaded.whitelist);
        controller.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, reloaded.blacklist, reloaded.whitelist);
        assertEquals(Set.of(WECHAT), controller.sourceScope().packages());
        assertEquals(reloaded.whitelist, controller.sourceScope().whitelist);
    }
    @Test public void closedPageCannotSaveSourceScope() {
        controller.close();
        try {
            controller.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, List.of(), List.of(WECHAT));
            fail("Closed page must not save source scope");
        } catch (IllegalStateException expected) { }
        assertFalse(new ConfigStore(context).sourceScope().managed);
    }
    @Test public void sourceScopeAndScreenshotFallbackCommitAsOneConfigurationAndCancelCapture() {
        assertFalse(controller.scopeCaptureEnabled());
        install(WECHAT, "微信", true);
        String nonce = CaptureBroker.arm(context, WECHAT).getString("nonce");
        assertTrue(CaptureBroker.validNonce(nonce));
        SharedPreferences preferences = context.getSharedPreferences("config", Context.MODE_PRIVATE);
        List<String> observed = new ArrayList<>();
        String confirmedBeforeSave = new ConfigStore(context).sourceScope().encode() + "|" + new ConfigStore(context).scopeCaptureEnabled();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (ignored, key) -> observed.add(
                new ConfigStore(context).sourceScope().encode() + "|" + new ConfigStore(context).scopeCaptureEnabled());
        preferences.registerOnSharedPreferenceChangeListener(listener);
        try {
            controller.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, List.of(WECHAT), List.of(BILI), true);
        } finally { preferences.unregisterOnSharedPreferenceChangeListener(listener); }
        ConfigStore restored = new ConfigStore(context);
        assertTrue(restored.scopeCaptureEnabled()); assertTrue(controller.scopeCaptureEnabled());
        assertEquals(SourceScopePolicy.Mode.WHITELIST, restored.sourceScope().mode);
        assertEquals(Set.of(WECHAT), restored.sourceScope().blacklist); assertEquals(Set.of(BILI), restored.sourceScope().whitelist);
        assertFalse(observed.isEmpty());
        // Android notifies before commit returns; readers keep the confirmed pair until its disk result is known.
        for (String value : observed) assertEquals(confirmedBeforeSave, value);
        assertEquals("捕获已取消", CaptureBroker.status(context)); assertTrue(CaptureBroker.take(context, nonce).isEmpty());
        controller.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(WECHAT), List.of(BILI));
        assertTrue(new ConfigStore(context).scopeCaptureEnabled());
        controller.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(WECHAT), List.of(BILI), false);
        assertFalse(new ConfigStore(context).scopeCaptureEnabled());
        assertEquals(Set.of(WECHAT), new ConfigStore(context).sourceScope().blacklist);
    }
    @Test public void automaticLinkProbeCanBeSavedWithoutScreenshotAndLegacyEditsKeepItsChoice() {
        assertFalse(controller.scopeLinkProbeEnabled());
        controller.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), false, true);
        assertTrue(controller.scopeLinkProbeEnabled());assertFalse(controller.scopeCaptureEnabled());
        controller.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, List.of(), List.of(WECHAT));
        assertTrue(controller.scopeLinkProbeEnabled());assertFalse(controller.scopeCaptureEnabled());
        controller.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(WECHAT), true);
        assertTrue(controller.scopeLinkProbeEnabled());assertTrue(controller.scopeCaptureEnabled());
        controller.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(WECHAT), true, false);
        assertFalse(new ConfigStore(context).scopeLinkProbeEnabled());assertTrue(controller.scopeCaptureEnabled());
    }
    @Test public void disablingSupplementalTapCancelsOnlySyntheticCaptureAndPreservesRecognition() {
        install(WECHAT, "微信", true);
        controller.setScopeLinkProbeEnabled(true);
        String nonce = CaptureBroker.armProbe(context, WECHAT).getString("nonce");
        assertTrue(CaptureBroker.validNonce(nonce));
        controller.setScopeLinkProbeEnabled(false);
        assertTrue(CaptureBroker.take(context, nonce).isEmpty());
        assertEquals("捕获已取消", CaptureBroker.status(context));
        assertTrue(controller.snapshot().recognitionEnabled); assertTrue(controller.snapshot().captureEnabled);
        String manual = CaptureBroker.arm(context, WECHAT).getString("nonce");
        controller.setScopeLinkProbeEnabled(false);
        assertFalse(CaptureBroker.take(context, manual).isEmpty());
    }

    @Test public void gestureSaveSharesTheSupplementalTapTransactionAndLegacySavePreservesIt() {
        controller.setDragSettings(controller.getDragSettings(), true);
        assertTrue(new ConfigStore(context).scopeLinkProbeEnabled());
        controller.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, List.of(), List.of(WECHAT), false);
        controller.setDragSettings(controller.getDragSettings());
        assertTrue(controller.scopeLinkProbeEnabled());
        controller.setDragSettings(controller.getDragSettings(), false);
        assertFalse(controller.scopeLinkProbeEnabled());
        controller.close();
        assertThrows(IllegalStateException.class, () -> controller.setScopeLinkProbeEnabled(true));
        assertThrows(IllegalStateException.class, () -> controller.setDragSettings(config.getDragSettings(), true));
    }
    @Test public void failedSourceScopeCommitKeepsFallbackAndListsWithoutCancellingThePendingCapture() throws Exception {
        controller.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(WECHAT), List.of(BILI), true);
        install(WECHAT, "微信", true);
        String nonce = CaptureBroker.arm(context, WECHAT).getString("nonce");
        assertTrue(CaptureBroker.validNonce(nonce));
        SharedPreferences preferences = context.getSharedPreferences("config", Context.MODE_PRIVATE);
        String before = preferences.getString(SourceScopePolicy.KEY, null);
        SharedPreferences failure = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!"edit".equals(method.getName())) return method.invoke(preferences, args);
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, input) ->
                                    "commit".equals(operation.getName()) ? false : editor);
                });
        Field controllerConfig = SettingsController.class.getDeclaredField("config"); controllerConfig.setAccessible(true);
        Field storedPreferences = ConfigStore.class.getDeclaredField("prefs"); storedPreferences.setAccessible(true);
        storedPreferences.set(controllerConfig.get(controller), failure);
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> controller.saveSourceScope(
                SourceScopePolicy.Mode.WHITELIST, List.of(), List.of(BILI), false));
        assertEquals("应用范围保存失败，请重试", rejected.getMessage());
        assertEquals(before, preferences.getString(SourceScopePolicy.KEY, null));
        assertTrue(new ConfigStore(context).scopeCaptureEnabled()); assertTrue(controller.scopeCaptureEnabled());
        assertFalse(CaptureBroker.take(context, nonce).isEmpty());
    }
    @Test public void corruptedSourceScopeIsOnlyRepairedByAnExplicitSuccessfulSave() {
        var preferences = context.getSharedPreferences("config", Context.MODE_PRIVATE);
        String damaged = "{broken source scope";
        assertTrue(preferences.edit().putString(SourceScopePolicy.KEY, damaged).commit());
        SourceScopePolicy draft = controller.sourceScope();
        assertTrue(draft.managed); assertEquals(SourceScopePolicy.Mode.WHITELIST, draft.mode);
        assertTrue(draft.blacklist.isEmpty()); assertTrue(draft.whitelist.isEmpty());
        assertEquals("应用范围配置损坏，请重新选择名单并保存", controller.sourceScopeError());
        assertEquals(damaged, preferences.getString(SourceScopePolicy.KEY, null));
        try { new ConfigStore(context).sourceScope(); fail("Saved malformed policy must still be rejected"); }
        catch (IllegalArgumentException expected) { }
        controller.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, List.of(), List.of(WECHAT));
        assertNull(controller.sourceScopeError());
        assertEquals(Set.of(WECHAT), new ConfigStore(context).sourceScope().whitelist);
    }
    @Test public void editRoundTripNormalizesInputsAndPreservesAnEmptyRulesetAfterDeletion() {
        controller.saveRule(-1, " WWW.BILIBILI.COM ", " tv.danmaku.bili ", "  ", true, false);
        RouteRule added = controller.rules().get(0);
        assertEquals("www.bilibili.com", added.host()); assertEquals("*", added.sourcePackage());
        assertTrue(added.includeSubdomains()); assertFalse(added.convert());
        controller.saveRule(0, "b23.tv", BILI, WECHAT, false, true);
        assertEquals(WECHAT, controller.rules().get(0).sourcePackage());
        assertFalse(controller.rules().get(0).convert());
        controller.deleteRule(0);
        assertTrue(controller.rules().isEmpty()); assertTrue(new ConfigStore(context).rules().isEmpty());
    }
    @Test public void invalidEditorInputNeverMutatesTheStoredRules() {
        controller.saveRule(-1, "b23.tv", BILI, "*", false, false);
        for (String[] invalid : new String[][] {
                {"https://b23.tv/path", BILI, "*"}, {"bad..domain", BILI, "*"},
                {"b23.tv", BridgeContract.APP_PACKAGE, "*"}, {"b23.tv", BridgeContract.HOST_PACKAGE, "*"},
                {"b23.tv", "not a package", "*"}, {"b23.tv", BILI, "not a package"}}) {
            try { controller.saveRule(0, invalid[0], invalid[1], invalid[2], false, false); fail(); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
            assertEquals(1, controller.rules().size()); assertEquals("b23.tv", controller.rules().get(0).host());
        }
        try { controller.deleteRule(4); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(1, controller.rules().size());
    }
    @Test public void restoringDefaultsIsExplicitAndManualFutureTargetsRemainAllowed() {
        controller.saveRule(-1, "future.example", "com.example.future", "*", false, true);
        assertEquals("com.example.future", controller.rules().get(0).targetPackage());
        controller.restoreDefaults();
        assertTrue(controller.ruleEntries().stream().anyMatch(rule -> BILI.equals(rule.targetPackage)));
        assertFalse(controller.rules().stream().anyMatch(rule -> "future.example".equals(rule.host())));
    }
    @Test public void previewDistinguishesConflictsInsteadOfPretendingToUseOriginalBrowser() {
        install(BILI, "哔哩哔哩", true); install("com.example.other", "另一目标", true);
        controller.saveRule(-1, "www.bilibili.com", BILI, "*", false, true);
        controller.saveRule(-1, "www.bilibili.com", "com.example.other", "*", false, true);
        SettingsController.Preview preview = controller.matchPreview(URL, "");
        assertEquals(SettingsController.PreviewStatus.AMBIGUOUS, preview.status);
        assertNull(preview.targetPackage); assertNull(preview.rule); assertNull(preview.convertedUrl);
        assertTrue(preview.title.contains("多个已安装目标")); assertTrue(preview.detail.contains("不会自动打开"));
        assertNull(((ShadowApplication) Shadow.extract((Application) context)).getNextStartedActivity());
    }
    @Test public void previewPreservesSourcePriorityAndShowsOriginalUrlWithoutOpeningOrThawing() {
        install(BILI, "哔哩哔哩", false);
        install("com.example.other", "另一目标", true);
        controller.saveRule(-1, "www.bilibili.com", BILI, "*", false, true);
        controller.saveRule(-1, "www.bilibili.com", "com.example.other", WECHAT, false, false);
        SettingsController.Preview unknown = controller.matchPreview("  " + URL + "  ", "*");
        assertEquals(SettingsController.PreviewStatus.MATCHED, unknown.status); assertEquals(BILI, unknown.targetPackage);
        assertEquals("已匹配到哔哩哔哩", unknown.title); assertEquals(URL, unknown.convertedUrl);
        assertEquals("com.example.other", controller.matchPreview(URL, WECHAT).targetPackage);
        try { assertFalse(context.getPackageManager().getApplicationInfo(BILI, PackageManagerFlags.ALL).enabled); }
        catch (android.content.pm.PackageManager.NameNotFoundException impossible) { throw new AssertionError(impossible); }
        assertNull(((ShadowApplication) Shadow.extract((Application) context)).getNextStartedActivity());
    }
    @Test public void previewInvalidAndNoMatchKeepDifferentUserMessages() {
        assertEquals(SettingsController.PreviewStatus.INVALID, controller.matchPreview("javascript:alert(1)", "").status);
        assertEquals(SettingsController.PreviewStatus.INVALID, controller.matchPreview(URL, "not a package").status);
        SettingsController.Preview none = controller.matchPreview(URL, "");
        assertEquals(SettingsController.PreviewStatus.NO_MATCH, none.status);
        assertTrue(none.detail.contains("无感识别结束，不回放原网页"));
    }
    @Test public void installedPickerKeepsDisabledAndHiddenButDropsUninstalledMetadata() throws Exception {
        install("com.example.hidden", "A hidden", true);
        install("com.example.disabled", "B disabled", false);
        install("com.example.enabled", "C enabled", true);
        install("com.example.removed", "D removed", true, false, 1, "1");
        Method hide = context.getPackageManager().getClass().getMethod("setApplicationHiddenSettingAsUser", String.class, boolean.class, UserHandle.class);
        hide.setAccessible(true);
        assertEquals(Boolean.TRUE, hide.invoke(context.getPackageManager(), "com.example.hidden", true, Process.myUserHandle()));
        List<SettingsController.AppEntry> entries = controller.installedApps().stream()
                .filter(app -> app.packageName.startsWith("com.example.")).toList();
        assertEquals(List.of("com.example.hidden", "com.example.disabled", "com.example.enabled"), entries.stream().map(app -> app.packageName).toList());
        assertFalse(entries.get(1).enabled);
    }
    @Test public void queuedRootResultIsDeliveredOnMainOnlyWhileThePageIsAlive() {
        AtomicInteger callbacks = new AtomicInteger();
        controller.probeRoot(result -> { assertSame(Looper.getMainLooper(), Looper.myLooper()); callbacks.incrementAndGet(); });
        worker.runNext(); assertEquals(0, callbacks.get());
        main.idle(); assertEquals(1, callbacks.get());
        controller.probeRoot(result -> callbacks.incrementAndGet()); worker.runNext();
        controller.close(); main.idle(); assertEquals(1, callbacks.get()); assertTrue(worker.isShutdown());
    }

    @Test public void installedAppRequestsShareOneScanAndCachedResults() {
        install("com.example.cached", "Cached", true);
        AtomicInteger obsolete = new AtomicInteger(), latest = new AtomicInteger();
        controller.loadInstalledApps(entries -> obsolete.incrementAndGet());
        controller.loadInstalledApps(entries -> latest.incrementAndGet());
        assertEquals("Repeated page requests must share one installed-app scan", 1, worker.jobs.size());
        worker.runNext(); main.idle();
        assertEquals(0, obsolete.get()); assertEquals(1, latest.get());
        controller.loadInstalledApps(entries -> {
            assertTrue(entries.stream().anyMatch(app -> app.packageName.equals("com.example.cached")));
            latest.incrementAndGet();
        });
        assertTrue("Returning to an unchanged page must reuse its application list", worker.jobs.isEmpty());
        main.idle(); assertEquals(2, latest.get());
    }

    @Test public void packageChangesInvalidateCachedApplicationsAndCannotDeliverAnOldScan() {
        install("com.example.before", "Before", true);
        List<List<SettingsController.AppEntry>> delivered = new ArrayList<>();
        controller.loadInstalledApps(delivered::add);
        install("com.example.after", "After", false);
        context.sendBroadcast(new Intent(Intent.ACTION_PACKAGE_ADDED,
                android.net.Uri.parse("package:com.example.after")));
        worker.runNext();
        main.idle();
        assertTrue("A queued result from before the package change must be discarded", delivered.isEmpty());
        assertEquals("One replacement scan must serve the waiting page", 1, worker.jobs.size());
        worker.runNext(); main.idle();
        assertEquals(1, delivered.size());
        assertTrue(delivered.get(0).stream().anyMatch(app -> app.packageName.equals("com.example.after") && !app.enabled));
        controller.loadInstalledApps(delivered::add); main.idle();
        assertTrue(worker.jobs.isEmpty()); assertEquals(2, delivered.size());
        controller.close();
        context.sendBroadcast(new Intent(Intent.ACTION_PACKAGE_CHANGED,
                android.net.Uri.parse("package:com.example.after")));
        main.idle(); assertTrue(worker.jobs.isEmpty());
    }

    @Test @Config(shadows = IconCountingPackageManager.class)
    public void installedScanLoadsOnlyTextAndVisibleIconsAreCachedAndInvalidated() throws Exception {
        IconCountingPackageManager.icons.set(0);
        install("com.example.visible", "Visible", false);
        controller.loadInstalledApps(entries -> {}); worker.runNext(); main.idle();
        assertEquals("Scanning app labels must not decode every installed icon", 0, IconCountingPackageManager.icons.get());
        Method load = SettingsController.class.getMethod("loadAppIcon", String.class, java.util.function.Consumer.class);
        List<android.graphics.drawable.Drawable> images = new ArrayList<>();
        load.invoke(controller, "com.example.visible", (java.util.function.Consumer<android.graphics.drawable.Drawable>) images::add);
        worker.runNext(); main.idle();
        assertEquals(1, images.size()); assertNotNull(images.get(0));
        load.invoke(controller, "com.example.visible", (java.util.function.Consumer<android.graphics.drawable.Drawable>) images::add);
        main.idle();
        assertEquals(1, IconCountingPackageManager.icons.get()); assertEquals(2, images.size());
        context.sendBroadcast(new Intent(Intent.ACTION_PACKAGE_CHANGED,
                android.net.Uri.parse("package:com.example.visible")));
        main.idle();
        load.invoke(controller, "com.example.visible", (java.util.function.Consumer<android.graphics.drawable.Drawable>) images::add);
        worker.runNext(); main.idle();
        assertEquals(2, IconCountingPackageManager.icons.get());
    }

    @org.robolectric.annotation.Implements(className = "android.app.ApplicationPackageManager")
    public static final class IconCountingPackageManager extends org.robolectric.shadows.ShadowApplicationPackageManager {
        static final AtomicInteger icons = new AtomicInteger();
        @org.robolectric.annotation.Implementation
        protected android.graphics.drawable.Drawable getApplicationIcon(ApplicationInfo app) {
            icons.incrementAndGet();
            return new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE);
        }
    }

    @Test @Config(shadows = IconCountingPackageManager.class)
    public void leavingAnIconRowCanCancelItsQueuedDecodeBeforeItRuns() throws Exception {
        IconCountingPackageManager.icons.set(0);
        install("com.example.offscreen", "Offscreen", true);
        AtomicInteger delivered = new AtomicInteger();
        Method load = SettingsController.class.getMethod("loadAppIcon", String.class, java.util.function.Consumer.class);
        Object request = load.invoke(controller, "com.example.offscreen",
                (java.util.function.Consumer<android.graphics.drawable.Drawable>) icon -> delivered.incrementAndGet());
        assertTrue("An icon row must be able to cancel work when it leaves composition", request instanceof java.util.concurrent.Future<?>);
        ((java.util.concurrent.Future<?>) request).cancel(true);
        worker.runNext(); main.idle();
        assertEquals(0, IconCountingPackageManager.icons.get()); assertEquals(0, delivered.get());
        load.invoke(controller, "com.example.offscreen",
                (java.util.function.Consumer<android.graphics.drawable.Drawable>) icon -> delivered.incrementAndGet());
        worker.runNext(); main.idle();
        assertEquals(1, IconCountingPackageManager.icons.get()); assertEquals(1, delivered.get());
    }
    @Test public void replacedAndDestroyedPageCallbacksCannotUpdateTheUi() {
        AtomicInteger old = new AtomicInteger(), latest = new AtomicInteger();
        controller.probeRoot(result -> old.incrementAndGet()); controller.probeRoot(result -> latest.incrementAndGet());
        worker.runNext(); worker.runNext(); main.idle();
        assertEquals(0, old.get()); assertEquals(1, latest.get());
        controller.loadInstalledApps(entries -> latest.incrementAndGet()); worker.runNext();
        owner.pause().stop().destroy(); main.idle();
        assertEquals(1, latest.get());
    }

    @Test public void unexpectedOnlineWorkerFailureStillDeliversOneFailureOnMain() {
        controller.close(); worker = new QueuedExecutor();
        controller = new SettingsController(owner.get(), worker, () -> ThawEngine.thaw(context, null, ThawEngine.Mode.ROOT),
                () -> { throw new IllegalStateException("storage unavailable"); }, authorization);
        AtomicInteger callbacks = new AtomicInteger();
        controller.refreshOnlineRules(result -> {
            assertSame(Looper.getMainLooper(), Looper.myLooper()); assertFalse(result.success);
            assertTrue(result.message.contains("保存状态未确认")); assertNotNull(result.state); callbacks.incrementAndGet();
        });
        worker.runNext(); assertEquals(0, callbacks.get()); main.idle(); assertEquals(1, callbacks.get());
    }
    @Test public void supersededOrClosedOnlineFailureDoesNotNotifyTheOldPage() {
        controller.close(); worker = new QueuedExecutor();
        controller = new SettingsController(owner.get(), worker, () -> ThawEngine.thaw(context, null, ThawEngine.Mode.ROOT),
                () -> { throw new IllegalStateException("storage unavailable"); }, authorization);
        AtomicInteger obsolete = new AtomicInteger(), latest = new AtomicInteger();
        controller.refreshOnlineRules(result -> obsolete.incrementAndGet());
        controller.refreshOnlineRules(result -> latest.incrementAndGet());
        worker.runNext(); worker.runNext(); main.idle();
        assertEquals(0, obsolete.get()); assertEquals(1, latest.get());
        controller.refreshOnlineRules(result -> latest.incrementAndGet()); worker.runNext();
        controller.close(); main.idle(); assertEquals(1, latest.get());
    }

    @Test public void selectingShizukuOnlyReadsStateUntilTheUserRequestsPermission() {
        controller.setMode(ThawEngine.Mode.SHIZUKU);
        SettingsController.Snapshot pending = controller.snapshot();
        assertEquals(ThawEngine.Mode.SHIZUKU, pending.mode);
        assertFalse(pending.shizukuGranted); assertEquals("Shizuku 未运行", pending.shizukuStatus);
        assertEquals(0, authorization.requests);
        controller.requestShizukuPermission();
        assertEquals(1, authorization.requests); assertEquals(SettingsController.SHIZUKU_PERMISSION_REQUEST, authorization.requestCode);
        authorization.granted = true;
        assertTrue(controller.snapshot().shizukuGranted); assertEquals("Shizuku 已授权", controller.snapshot().shizukuStatus);
    }
    @Test public void fullWebsiteRuleMatchesAnyPathAndPassesTheOriginalLinkToTheChosenFrozenApp() {
        install("com.example.reader", "阅读器", false);
        controller.saveIceRule(-1, "*", "https://site.example/start?ref=setup", "com.example.reader", "fixed_deep_link", "网站规则");
        var entry = controller.manualRuleEntries().get(0);
        assertEquals("site.example", entry.host); assertEquals("none", entry.urlConversion);
        String url = "https://m.site.example/another/path?x=%2F%2B&part=2";
        SettingsController.Preview preview = controller.matchPreview(url, "com.tencent.mm");
        assertEquals(SettingsController.PreviewStatus.MATCHED, preview.status);
        assertEquals("com.example.reader", preview.targetPackage); assertEquals(url, preview.convertedUrl);
        assertFalse(preview.rule.convert()); assertTrue(preview.detail.contains("链接保持原样"));
        assertEquals(SettingsController.PreviewStatus.NO_MATCH, controller.matchPreview("https://different.example/path", "").status);
        assertNull(((ShadowApplication) Shadow.extract((Application) context)).getNextStartedActivity());
        assertEquals(0, authorization.requests);
    }
    @Test public void importedConversionMetadataRemainsStoredWhilePreviewUsesTheRealRawHttpPlan() {
        install("com.baidu.searchbox", "百度", true);
        new RuleRepository(context).replaceManualRules(List.of(new io.github.yudigaga.taplusbridge.rules.LinkRuleEntry(
                "*", "baidu.com", "com.baidu.searchbox", "manual", "fixed_deep_link", "已导入的备注")));
        String url = "https://www.baidu.com/s?wd=original%20query";
        SettingsController.Preview preview = controller.matchPreview(url, "");
        assertEquals(SettingsController.PreviewStatus.MATCHED, preview.status); assertEquals(url, preview.convertedUrl);
        assertFalse(preview.rule.convert()); assertEquals("已导入的备注", controller.manualRuleEntries().get(0).note);
        assertEquals("fixed_deep_link", controller.manualRuleEntries().get(0).urlConversion);
        assertNull(((ShadowApplication) Shadow.extract((Application) context)).getNextStartedActivity());
    }
    @Test public void onlineCacheSurvivesDisabledUpdatesAndSettingsControllerRecreation() {
        var before = controller.onlineRulesState(); int count = controller.ruleEntries().size();
        controller.setOnlineUpdatesEnabled(true); controller.setOnlineUpdatesEnabled(false);
        controller.close(); worker = new QueuedExecutor();
        controller = new SettingsController(owner.get(), worker, () -> ThawEngine.thaw(context, null, ThawEngine.Mode.ROOT), null, authorization);
        var reopened = controller.onlineRulesState();
        assertFalse(reopened.enabled); assertEquals(before.revision, reopened.revision);
        assertEquals(before.checksum, reopened.checksum); assertEquals(before.ruleCount, reopened.ruleCount);
        assertEquals(count, controller.ruleEntries().size()); assertTrue(count > 0);
    }
    @Test public void authorizationResultRefreshesOnlyTheCurrentLiveObserverOnMain() {
        AtomicInteger stale = new AtomicInteger(), current = new AtomicInteger();
        controller.setAuthorizationRefresh(stale::incrementAndGet);
        authorization.emit(); assertEquals(0, stale.get());
        controller.setAuthorizationRefresh(() -> {
            assertSame(Looper.getMainLooper(), Looper.myLooper()); current.incrementAndGet();
        });
        main.idle(); assertEquals(0, stale.get()); assertEquals(0, current.get());
        authorization.emit(); main.idle(); assertEquals(1, current.get());
        assertEquals(1, authorization.observations);
        Runnable queuedPermissionResult = authorization.listener;
        controller.setAuthorizationRefresh(null);
        assertEquals(1, authorization.removals);
        queuedPermissionResult.run(); main.idle(); assertEquals(1, current.get());
        controller.setAuthorizationRefresh(current::incrementAndGet);
        queuedPermissionResult = authorization.listener;
        authorization.emit(); controller.close(); queuedPermissionResult.run(); main.idle();
        assertEquals(1, current.get()); assertEquals(2, authorization.removals);
    }
    @Test public void destroyedPageCannotRequestShizukuPermission() {
        owner.pause().stop().destroy();
        try { controller.requestShizukuPermission(); fail(); }
        catch (IllegalStateException expected) { assertEquals("页面已关闭", expected.getMessage()); }
        assertEquals(0, authorization.requests);
    }

    private static final class FakeAuthorization implements SettingsController.ShizukuAuthorization {
        boolean granted; int requests, requestCode, observations, removals; Runnable listener;
        public boolean granted() { return granted; }
        public String status() { return granted ? "Shizuku 已授权" : "Shizuku 未运行"; }
        public void request(int requestCode) { this.requestCode = requestCode; requests++; }
        public void observe(Runnable listener) { this.listener = listener; observations++; }
        public void unobserve(Runnable listener) { assertSame(this.listener, listener); this.listener = null; removals++; }
        void emit() { if (listener != null) listener.run(); }
    }

    private static final class PackageManagerFlags {
        static final int ALL = android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS | android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
    }
    private static final class QueuedExecutor extends AbstractExecutorService {
        private final Queue<Runnable> jobs = new ArrayDeque<>(); private boolean shutdown;
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; List<Runnable> abandoned = new ArrayList<>(jobs); jobs.clear(); return abandoned; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && jobs.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        @Override public void execute(Runnable job) { jobs.add(job); }
        void runNext() { Runnable job = jobs.poll(); assertNotNull(job); job.run(); }
    }
}
