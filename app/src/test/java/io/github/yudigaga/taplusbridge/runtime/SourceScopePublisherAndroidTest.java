package io.github.yudigaga.taplusbridge.runtime;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Settings;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplicationPackageManager;
import org.robolectric.shadows.ShadowSettings;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = {
        SourceScopePublisherAndroidTest.CatalogPackageManager.class,
        SourceScopePublisherAndroidTest.CountingGlobal.class})
public final class SourceScopePublisherAndroidTest {
    private static final String ALLOWED = "com.example.reader";
    private static final String DENIED = "com.example.chat";
    private static final String OTHER_USER = "com.example.workonly";
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        CatalogPackageManager.applications = new ArrayList<>();
        CatalogPackageManager.failure = null;
        CatalogPackageManager.calls = 0;
        CatalogPackageManager.flags = 0;
        CountingGlobal.writtenKeys.clear();
    }

    @Test public void blacklistPublishesOnlySelectedPackagesWithoutReadingTheCatalog() throws Exception {
        CatalogPackageManager.failure = new SecurityException("catalog unavailable");
        Settings.Global.putString(context.getContentResolver(), "unrelated_setting", "preserved");
        CountingGlobal.writtenKeys.clear();
        SourceScopePolicy policy = SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST,
                List.of(DENIED), List.of(ALLOWED));

        SourceScopePublisher.publish(context, SourceScopePublisher.effectiveBlacklist(context, policy));

        assertEquals(List.of(DENIED), publishedPackages());
        assertEquals(0, CatalogPackageManager.calls);
        assertEquals(List.of(SourceScopePublisher.KEY), CountingGlobal.writtenKeys);
        assertEquals("preserved", Settings.Global.getString(context.getContentResolver(), "unrelated_setting"));
    }

    @Test public void emptyWhitelistDeniesEveryCatalogPackageIncludingDisabledPackages() throws Exception {
        CatalogPackageManager.applications = List.of(application(ALLOWED, true, true, 22001),
                application(DENIED, false, true, 22002));
        SourceScopePolicy policy = SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST,
                List.of(), List.of());

        SourceScopePublisher.publish(context, SourceScopePublisher.effectiveBlacklist(context, policy));

        assertEquals(List.of(DENIED, ALLOWED), publishedPackages());
        assertEquals(1, CatalogPackageManager.calls);
        assertTrue((CatalogPackageManager.flags & 0x00400000) != 0);
        assertTrue((CatalogPackageManager.flags & PackageManager.MATCH_DISABLED_COMPONENTS) != 0);
        assertTrue((CatalogPackageManager.flags & PackageManager.MATCH_UNINSTALLED_PACKAGES) != 0);
    }

    @Test public void whitelistAllowsItsSelectionAndIgnoresTheInactiveBlacklistSelection() throws Exception {
        CatalogPackageManager.applications = List.of(application(ALLOWED, true, true, 22001),
                application(DENIED, true, true, 22002));
        SourceScopePolicy policy = SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST,
                List.of(ALLOWED), List.of(ALLOWED));

        SourceScopePublisher.publish(context, SourceScopePublisher.effectiveBlacklist(context, policy));

        assertEquals(List.of(DENIED), publishedPackages());
    }

    @Test public void whitelistComplementAcceptsTheRealAndroidPlatformPackageName() throws Exception {
        ApplicationInfo platform = application("android", true, true, Process.SYSTEM_UID);
        platform.flags |= ApplicationInfo.FLAG_SYSTEM;
        CatalogPackageManager.applications = List.of(platform,
                application(ALLOWED, true, true, 22001), application(DENIED, true, true, 22002));
        SourceScopePolicy policy = SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST,
                List.of(), List.of(ALLOWED));

        SourceScopePublisher.publish(context, SourceScopePublisher.effectiveBlacklist(context, policy));

        // PackageManager's framework package has no dot; it must not invalidate the whole catalog.
        // It is not in the selected whitelist, so it remains in the exact complement.
        assertEquals(List.of("android", DENIED), publishedPackages());
        assertEquals(List.of(SourceScopePublisher.KEY), CountingGlobal.writtenKeys);
    }

    @Test public void packagesOutsideTheOwnerInstalledViewRemainInTheWhitelistComplement() throws Exception {
        ApplicationInfo otherUser = application(OTHER_USER, true, false, 1022001);
        assertEquals(0, otherUser.flags & ApplicationInfo.FLAG_INSTALLED);
        CatalogPackageManager.applications = List.of(application(ALLOWED, true, true, 22001), otherUser);
        SourceScopePolicy policy = SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST,
                List.of(), List.of(ALLOWED));

        SourceScopePublisher.publish(context, SourceScopePublisher.effectiveBlacklist(context, policy));

        assertEquals(List.of(OTHER_USER), publishedPackages());
        assertTrue((CatalogPackageManager.flags & 0x00400000) != 0);
    }

    @Test public void aPackageManagerFailurePreservesThePreviousGlobalValue() {
        String previous = seedPreviousValue();
        CatalogPackageManager.failure = new SecurityException("cross-user query denied");
        SourceScopePolicy policy = SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST,
                List.of(), List.of(ALLOWED));

        assertThrows(SecurityException.class, () -> SourceScopePublisher.publish(context,
                SourceScopePublisher.effectiveBlacklist(context, policy)));

        assertEquals(previous, Settings.Global.getString(context.getContentResolver(), SourceScopePublisher.KEY));
        assertTrue(CountingGlobal.writtenKeys.isEmpty());
    }

    @Test public void missingOrMalformedCatalogsNeverPublishAPartialWhitelistComplement() {
        String previous = seedPreviousValue();
        SourceScopePolicy policy = SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST,
                List.of(), List.of(ALLOWED));
        for (List<ApplicationInfo> catalog : Arrays.<List<ApplicationInfo>>asList(null,
                Arrays.asList(application(DENIED, true, true, 22002), null),
                List.of(application("invalid package", true, true, 22003)))) {
            CatalogPackageManager.applications = catalog;
            assertThrows(IllegalStateException.class, () -> SourceScopePublisher.publish(context,
                    SourceScopePublisher.effectiveBlacklist(context, policy)));
            assertEquals(previous, Settings.Global.getString(context.getContentResolver(), SourceScopePublisher.KEY));
            assertTrue(CountingGlobal.writtenKeys.isEmpty());
        }
    }

    @Test public void publishingTheSameJsonDoesNotWriteTheGlobalSettingAgain() throws Exception {
        SourceScopePublisher.publish(context, List.of(DENIED));
        SourceScopePublisher.publish(context, List.of(DENIED));

        assertEquals(List.of(DENIED), publishedPackages());
        assertEquals(List.of(SourceScopePublisher.KEY), CountingGlobal.writtenKeys);
    }

    @Test public void unmanagedPolicyCannotBePublishedAsAnEmptyBlacklist() {
        String previous = seedPreviousValue();
        assertThrows(IllegalArgumentException.class, () -> SourceScopePublisher.publish(context,
                SourceScopePublisher.effectiveBlacklist(context, SourceScopePolicy.unmanaged())));
        assertEquals(previous, Settings.Global.getString(context.getContentResolver(), SourceScopePublisher.KEY));
        assertEquals(0, CatalogPackageManager.calls);
        assertTrue(CountingGlobal.writtenKeys.isEmpty());
    }

    private String seedPreviousValue() {
        String previous = "{\"taplus_blacklist\":[\"com.example.previous\"]}";
        Settings.Global.putString(context.getContentResolver(), SourceScopePublisher.KEY, previous);
        CountingGlobal.writtenKeys.clear();
        return previous;
    }

    private List<String> publishedPackages() throws Exception {
        JSONObject data = new JSONObject(Settings.Global.getString(context.getContentResolver(), SourceScopePublisher.KEY));
        assertEquals(1, data.length());
        JSONArray array = data.getJSONArray(SourceScopePublisher.KEY);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }

    private static ApplicationInfo application(String name, boolean enabled, boolean ownerInstalled, int uid) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = name; info.enabled = enabled; info.uid = uid;
        info.flags = ownerInstalled ? ApplicationInfo.FLAG_INSTALLED : 0;
        return info;
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    public static final class CatalogPackageManager extends ShadowApplicationPackageManager {
        static List<ApplicationInfo> applications = new ArrayList<>();
        static RuntimeException failure;
        static int calls, flags;

        @Implementation protected List<ApplicationInfo> getInstalledApplications(int requestedFlags) {
            calls++; flags = requestedFlags;
            if (failure != null) throw failure;
            return applications;
        }
    }

    @Implements(value = Settings.Global.class)
    public static final class CountingGlobal extends ShadowSettings.ShadowGlobal {
        static final List<String> writtenKeys = new ArrayList<>();

        @Implementation protected static boolean putString(ContentResolver resolver, String key, String value) {
            writtenKeys.add(key);
            return ShadowSettings.ShadowGlobal.putString(resolver, key, value);
        }
    }
}
