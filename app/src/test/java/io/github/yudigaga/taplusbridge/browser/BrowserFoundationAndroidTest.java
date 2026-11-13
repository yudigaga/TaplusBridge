package io.github.yudigaga.taplusbridge.browser;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class BrowserFoundationAndroidTest {
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("taplus_bridge_browser", 0).edit().clear().commit();
    }

    @Test public void selectionSurvivesUninstallUntilExplicitClearAndInvalidUpdatesKeepIt() {
        BrowserStore store = new BrowserStore(context);
        assertEquals("", store.selectedPackage());
        assertTrue(store.save("example.uninstalled"));
        assertEquals("example.uninstalled", new BrowserStore(context).selectedPackage());
        for (String invalid : new String[] {null, "example", " example.browser", "example/browser",
                HostPolicy.MODULE_PACKAGE, HostPolicy.HOST_PACKAGE}) {
            assertFalse(store.save(invalid));
            assertEquals("example.uninstalled", store.selectedPackage());
        }
        assertTrue(store.save(""));
        assertEquals("", new BrowserStore(context).selectedPackage());
    }

    @Test public void failedCommitRestoresThePreviousSelectionEvenAfterMemoryAlreadyChanged() {
        BrowserStore original = new BrowserStore(context);
        assertTrue(original.save("example.original"));
        SharedPreferences actual = context.getSharedPreferences("taplus_bridge_browser", 0);
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class}, (target, read, args) -> {
                    if (!"edit".equals(read.getName())) return read.invoke(actual, args);
                    SharedPreferences.Editor editor = actual.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[] {SharedPreferences.Editor.class}, (proxy, write, values) -> {
                                if ("commit".equals(write.getName())) { editor.apply(); return false; }
                                Object result = write.invoke(editor, values);
                                return result instanceof SharedPreferences.Editor ? proxy : result;
                            });
                });
        Context wrapped = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                assertEquals("taplus_bridge_browser", name);
                return failing;
            }
        };
        BrowserStore store = new BrowserStore(wrapped);
        assertFalse(store.save("example.replacement"));
        assertEquals("example.original", store.selectedPackage());
        assertEquals("example.original", original.selectedPackage());
    }

    @Test public void anotherStoreCannotReadTheRejectedDraftDuringFailedCommitRollback() throws Exception {
        BrowserStore readerStore = new BrowserStore(context);
        assertTrue(readerStore.save("example.original"));
        SharedPreferences actual = context.getSharedPreferences("taplus_bridge_browser", 0);
        CountDownLatch draftVisible = new CountDownLatch(1), allowRollback = new CountDownLatch(1);
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class}, (target, read, args) -> {
                    if (!"edit".equals(read.getName())) return read.invoke(actual, args);
                    SharedPreferences.Editor editor = actual.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[] {SharedPreferences.Editor.class}, (proxy, write, values) -> {
                                if ("commit".equals(write.getName())) {
                                    editor.apply(); draftVisible.countDown();
                                    if (!allowRollback.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("rollback not released");
                                    return false;
                                }
                                Object result = write.invoke(editor, values);
                                return result instanceof SharedPreferences.Editor ? proxy : result;
                            });
                });
        Context wrapped = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return failing; }
        };
        BrowserStore writerStore = new BrowserStore(wrapped);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var saved = threads.submit(() -> writerStore.save("example.rejected"));
            assertTrue("Writer must reach the in-memory update", draftVisible.await(5, TimeUnit.SECONDS));
            CountDownLatch reading = new CountDownLatch(1);
            var selected = threads.submit(() -> { reading.countDown(); return readerStore.selectedPackage(); });
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            try {
                assertThrows("Readers must wait until the failed save restores the old value", TimeoutException.class,
                        () -> selected.get(100, TimeUnit.MILLISECONDS));
            } finally { allowRollback.countDown(); }
            assertFalse(saved.get(5, TimeUnit.SECONDS));
            assertEquals("example.original", selected.get(5, TimeUnit.SECONDS));
        } finally { allowRollback.countDown(); threads.shutdownNow(); }
    }

    @Test public void browserCandidatesExcludeDomainOnlyLinksAndUnavailableOrInternalApps() {
        ResolveInfo generic = target("example.generic");
        ResolveInfo declared = target("example.declared");
        ResolveInfo domainOnly = target("example.domain");
        domainOnly.filter.addDataAuthority("example.com", null);
        ResolveInfo disabled = target("example.disabled");
        disabled.activityInfo.enabled = false;
        ResolveInfo unexported = target("example.unexported");
        unexported.activityInfo.exported = false;
        ResolveInfo permission = target("example.privatebrowser");
        permission.activityInfo.permission = "example.private.PERMISSION";
        packages().setResolveInfosForIntent(genericIntent(),
                List.of(generic, domainOnly, disabled, unexported, permission, target(HostPolicy.HOST_PACKAGE), target(HostPolicy.MODULE_PACKAGE)));
        packages().setResolveInfosForIntent(selector(), List.of(generic, declared));
        List<String> packages = BrowserApps.installed(context).stream().map(BrowserApps.Target::packageName).sorted().toList();
        assertEquals(List.of("example.declared", "example.generic"), packages);
    }

    @Test public void webIntentUsesOnlyTheSavedBrowserAndRechecksExactUrlCapability() {
        String target = "example.browser", url = "https://article.example/read/123";
        ResolveInfo browser = target(target);
        var manager = packages();
        manager.setResolveInfosForIntent(selector(), List.of(browser));
        Intent request = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(target);
        manager.setResolveInfosForIntent(request, List.of(browser));
        Intent ready = BrowserApps.viewIntent(context, url, target);
        assertNotNull(ready);
        assertEquals(Intent.ACTION_VIEW, ready.getAction());
        assertEquals(url, ready.getDataString());
        assertEquals(target, ready.getPackage());
        assertNull(ready.getComponent());
        assertTrue(ready.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertNull(((ShadowApplication) Shadow.extract(context)).getNextStartedActivity());
        for (String invalid : new String[] {"intent://article.example", "javascript:alert(1)", "file:///private", "https://user:secret@article.example/read"})
            assertNull(BrowserApps.viewIntent(context, invalid, target));
        manager.setResolveInfosForIntent(request, List.of());
        assertNull(BrowserApps.viewIntent(context, url, target));
        assertNull(BrowserApps.viewIntent(context, url, HostPolicy.HOST_PACKAGE));
        assertNull(BrowserApps.viewIntent(context, url, HostPolicy.MODULE_PACKAGE));
    }

    @SuppressWarnings("deprecation") private ResolveInfo target(String packageName) {
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = packageName; application.enabled = true; application.flags = ApplicationInfo.FLAG_INSTALLED;
        PackageInfo installed = new PackageInfo(); installed.packageName = packageName; installed.applicationInfo = application;
        packages().installPackage(installed);
        ResolveInfo info = new ResolveInfo(); info.isDefault = true; info.nonLocalizedLabel = packageName;
        info.activityInfo = new ActivityInfo(); info.activityInfo.packageName = packageName;
        info.activityInfo.name = packageName + ".Browser"; info.activityInfo.exported = true; info.activityInfo.enabled = true;
        info.activityInfo.applicationInfo = application;
        info.filter = new IntentFilter(Intent.ACTION_VIEW);
        info.filter.addCategory(Intent.CATEGORY_DEFAULT); info.filter.addCategory(Intent.CATEGORY_BROWSABLE);
        info.filter.addDataScheme("https");
        return info;
    }

    private Intent genericIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE);
    }
    private Intent selector() { return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER); }
    private ShadowPackageManager packages() { return Shadow.extract(context.getPackageManager()); }
}
