package io.github.yudigaga.taplusbridge.links;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.bridge.BridgeProvider;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class LinkChoiceRequestsAndroidTest {
    private Context context; private ConfigStore config; private BridgeProvider provider; private int hostUid;
    private final List<String> tokens = new ArrayList<>();
    private static final String URL = "https://example.com/Case?value=a%2Bb";
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", 0).edit().clear().commit();
        context.getSharedPreferences("ice_rules", 0).edit().clear().commit();
        config = new ConfigStore(context); hostUid = Process.myUid() / 100000 * 100000 + 10123;
        PackageInfo host = new PackageInfo(); host.packageName = BridgeContract.HOST_PACKAGE;
        host.versionName = "5.1.5"; host.versionCode = 50015; host.applicationInfo = new ApplicationInfo();
        host.applicationInfo.packageName = host.packageName; host.applicationInfo.uid = hostUid;
        host.applicationInfo.enabled = true; host.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        ShadowPackageManager pm = Shadow.extract(context.getPackageManager()); pm.installPackage(host); pm.setPackagesForUid(hostUid, host.packageName);
        provider = Robolectric.buildContentProvider(BridgeProvider.class).create(BridgeContract.AUTHORITY).get();
    }
    private Class<?> type() throws Exception { return Class.forName("io.github.yudigaga.taplusbridge.links.LinkChoiceRequests"); }
    private Intent prepare(List<String> links, String source, boolean clipboard) throws Exception {
        Intent result = (Intent) type().getMethod("prepare", Context.class, List.class, String.class, boolean.class).invoke(null, context, links, source, clipboard);
        if (result != null) tokens.add(result.getStringExtra("token")); return result;
    }
    private Bundle consume(String token) throws Exception { return (Bundle) type().getMethod("consume", Context.class, String.class).invoke(null, context, token); }
    @After public void reset() throws Exception {
        ShadowBinder.setCallingUid(Process.myUid());
        try { for (String token : tokens) consume(token); } catch (ClassNotFoundException ignored) { }
    }
    @Test public void opaqueSingleUseCapabilitySnapshotsAllLinksAndDoesNotPersistContent() throws Exception {
        ArrayList<String> links = new ArrayList<>(List.of(URL, MAGNET, URL));
        Intent direct = prepare(links, null, true); assertNotNull(direct); links.clear();
        assertEquals(BridgeContract.APP_PACKAGE + ".links.LinkChoiceActivity", direct.getComponent().getClassName());
        assertEquals(Set.of("token"), direct.getExtras().keySet()); assertNull(direct.getData());
        Bundle found = consume(direct.getStringExtra("token"));
        assertEquals(List.of(URL, MAGNET), found.getStringArrayList("links")); assertTrue(found.getBoolean("clipboard"));
        assertFalse(found.containsKey("source")); assertTrue(found.getString("identity").matches("[a-f0-9]{64}"));
        assertNull(consume(direct.getStringExtra("token")));
        for (String name : List.of("config", "launch_tickets", "status"))
            assertFalse(context.getSharedPreferences(name, 0).getAll().toString().contains(MAGNET));
    }
    @Test public void expiredOrReconfiguredCapabilityCannotOpenOldContent() throws Exception {
        Intent expires = prepare(List.of(URL, MAGNET), null, true); assertNotNull(expires);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31)); assertNull(consume(expires.getStringExtra("token")));
        Intent stale = prepare(List.of(URL, MAGNET), null, true); assertNotNull(stale);
        config.setNativeEnabled(!config.nativeEnabled()); assertNull(consume(stale.getStringExtra("token")));
    }
    @Test public void sourceScopeAndClipboardChannelAreIndependentOfDragEnabled() throws Exception {
        config.setDragSettings(io.github.yudigaga.taplusbridge.config.DragSettings.defaults());
        config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, List.of(), List.of("example.allowed"));
        assertNull(prepare(List.of(URL), "example.denied", false));
        assertNotNull(prepare(List.of(URL), "example.allowed", false));
        assertNotNull(prepare(List.of(URL), null, true));
        assertNull(prepare(List.of(URL), "example.allowed", true));
        config.setRecognitionChannel(RecognitionChannels.Channel.CLIPBOARD, false);
        assertNull(prepare(List.of(URL), null, true));
        assertNotNull(prepare(List.of(URL), "example.allowed", false));
    }
    @Test public void malformedOversizedAndDisabledRequestsCreateNoCapability() throws Exception {
        assertNull(prepare(List.of(), null, true)); assertNull(prepare(List.of("javascript:alert(1)"), null, true));
        assertNull(prepare(java.util.Collections.nCopies(65, URL), null, true));
        config.setRecognitionEnabled(false); assertNull(prepare(List.of(URL), null, true));
        config.setRecognitionEnabled(true); config.setEnabled(false); assertNull(prepare(List.of(URL), null, true));
    }
    @Test public void boundedCapacityRetainsExistingLiveCapabilities() throws Exception {
        Intent first = null;
        for (int i = 0; i < 16; i++) { Intent next = prepare(List.of(URL), null, true); assertNotNull(next); if (first == null) first = next; }
        assertNull(prepare(List.of(URL), null, true)); assertNotNull(consume(first.getStringExtra("token")));
        assertNotNull(prepare(List.of(URL), null, true));
    }
    @Test public void providerOnlyAcceptsSupportedAuthenticatedHostWithBooleanClipboardAndNoRawIntentExtras() throws Exception {
        Bundle input = new Bundle(); input.putStringArrayList("links", new ArrayList<>(List.of(URL, MAGNET))); input.putBoolean("clipboard", true);
        for (int uid : new int[]{1000, Process.myUid(), hostUid + 1}) { ShadowBinder.setCallingUid(uid); assertTrue(provider.call("link_choice_prepare", null, input).isEmpty()); }
        ShadowBinder.setCallingUid(hostUid); Bundle response = provider.call("link_choice_prepare", null, input);
        Intent direct = response.getParcelable("direct"); assertNotNull(direct); tokens.add(direct.getStringExtra("token"));
        assertEquals(Set.of("direct"), response.keySet()); assertEquals(Set.of("token"), direct.getExtras().keySet());
        input.putString("clipboard", "true"); assertTrue(provider.call("link_choice_prepare", null, input).isEmpty());
    }
}
