package io.github.yudigaga.taplusbridge.drag;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.bridge.BridgeProvider;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowPackageManager;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DragProviderAuthAndroidTest {
    private Context context;
    private ConfigStore config;
    private BridgeProvider provider;
    private ShadowPackageManager pm;
    private int hostUid;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().commit();
        config = new ConfigStore(context); config.setEnabled(true); setDrag(true);
        hostUid = (Process.myUid() / 100000) * 100000 + 10123;
        pm = Shadow.extract(context.getPackageManager()); installHost(40013, "4.1.3");
        pm.setPackagesForUid(hostUid + 1, "example.unrelated");
        provider = Robolectric.buildContentProvider(BridgeProvider.class).create(BridgeContract.AUTHORITY).get();
    }

    @After public void resetCaller() { ShadowBinder.setCallingUid(Process.myUid()); }

    private void setDrag(boolean enabled) {
        config.setDragSettings(new DragSettings(enabled, "share", "portal", "lens", "translate", false, List.of()));
    }

    @SuppressWarnings("deprecation")
    private void installHost(int version, String name) {
        PackageInfo info = new PackageInfo(); info.packageName = BridgeContract.HOST_PACKAGE;
        info.versionCode = version; info.versionName = name; info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = info.packageName; info.applicationInfo.uid = hostUid;
        info.applicationInfo.enabled = true; info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        pm.installPackage(info); pm.setPackagesForUid(hostUid, info.packageName);
    }

    private String issue() {
        return DragActionStore.issue(context, "share",
                new DragPayload("原始文字", "example.source", null, "text/plain", 0, 0, ""));
    }

    private Bundle request(String token) { Bundle value = new Bundle(); value.putString("token", token); return value; }
    private Bundle completion(String token) {
        Bundle value = request(token); value.putString("text", "完整识别正文 https://example.org/actual"); return value;
    }

    private Bundle nativeImport() {
        Bundle value = new Bundle(); value.putString("lease", "A".repeat(43));
        value.putString("source", "example.source"); value.putString("direction", "up"); value.putString("text", "原生正文");
        return value;
    }

    @Test public void nativeImportRejectsUnrelatedSystemAndModuleCallersBeforeReadingThePayload() {
        for (int uid : new int[]{hostUid + 1, Process.myUid(), 1000, 0}) {
            ShadowBinder.setCallingUid(uid);
            assertTrue(provider.call("drag_import", null, nativeImport()).isEmpty());
            assertTrue(provider.call("drag_import", null, Bundle.EMPTY).isEmpty());
        }
    }

    @Test public void nativeImportRejectsTheHostPackageFromAnotherUser() {
        int other = hostUid + 100000;
        pm.setPackagesForUid(other, BridgeContract.HOST_PACKAGE); ShadowBinder.setCallingUid(other);
        assertTrue(provider.call("drag_import", null, nativeImport()).isEmpty());
        assertTrue(provider.call("drag_import", null, Bundle.EMPTY).isEmpty());
    }

    @Test public void nativeImportRejectsUnsupportedHostVersionsBeforePayloadValidation() {
        ShadowBinder.setCallingUid(hostUid); installHost(50016, "5.1.6");
        assertEquals("unsupported_host", provider.call("drag_import", null, nativeImport()).getString("reason"));
        assertEquals("unsupported_host", provider.call("drag_import", null, Bundle.EMPTY).getString("reason"));
        installHost(50015, "5.1.5");
        assertEquals("invalid_request", provider.call("drag_import", null, Bundle.EMPTY).getString("reason"));
    }

    @Test public void unrelatedCallerCannotTakeOrCompleteEvenWithTheRealToken() {
        String token = issue(); ShadowBinder.setCallingUid(hostUid + 1);
        assertTrue(provider.call("drag_take", null, request(token)).isEmpty());
        assertTrue(provider.call("drag_complete", null, completion(token)).isEmpty());
        ShadowBinder.setCallingUid(hostUid);
        assertEquals("原始文字", provider.call("drag_take", null, request(token)).getString("text"));
        ShadowBinder.setCallingUid(hostUid + 1);
        assertTrue(provider.call("drag_complete", null, completion(token)).isEmpty());
        ShadowBinder.setCallingUid(hostUid);
        String ready = provider.call("drag_complete", null, completion(token)).getString("token");
        assertNotNull(ready);
        DragActionStore.Choice choice = DragActionStore.takeChoice(ready);
        assertNotNull(choice); assertEquals("share", choice.action());
        assertEquals("原始文字", choice.originalText()); choice.payload().close();
    }

    @Test public void matchingHostPackageFromAnotherUserCannotClaimOrComplete() {
        String token = issue(); int other = hostUid + 100000;
        pm.setPackagesForUid(other, BridgeContract.HOST_PACKAGE); ShadowBinder.setCallingUid(other);
        assertTrue(provider.call("drag_take", null, request(token)).isEmpty());
        assertTrue(provider.call("drag_complete", null, completion(token)).isEmpty());
        ShadowBinder.setCallingUid(hostUid);
        assertEquals("原始文字", provider.call("drag_take", null, request(token)).getString("text"));
        DragActionStore.cancel(token);
    }

    @Test public void unsupportedHostVersionCannotClaimAndSupportedVersionStillCan() {
        String token = issue(); ShadowBinder.setCallingUid(hostUid); installHost(50016, "5.1.6");
        assertEquals("unsupported_host", provider.call("drag_take", null, request(token)).getString("reason"));
        assertEquals("unsupported_host", provider.call("drag_complete", null, completion(token)).getString("reason"));
        installHost(50015, "5.1.5");
        assertEquals("原始文字", provider.call("drag_take", null, request(token)).getString("text"));
        String ready = provider.call("drag_complete", null, completion(token)).getString("token");
        assertNotNull(ready); DragActionStore.cancel(ready);
    }

    @Test public void disablingEitherSwitchRejectsTakeAndRecognitionCompletion() {
        String token = issue(); ShadowBinder.setCallingUid(hostUid); setDrag(false);
        assertEquals("disabled", provider.call("drag_take", null, request(token)).getString("reason"));
        assertNull(provider.call("drag_complete", null, completion(token)).getString("token"));
        setDrag(true); assertEquals("原始文字", provider.call("drag_take", null, request(token)).getString("text"));
        config.setEnabled(false);
        assertNull(provider.call("drag_complete", null, completion(token)).getString("token"));
        config.setEnabled(true);
        String ready = provider.call("drag_complete", null, completion(token)).getString("token");
        assertNotNull(ready); DragActionStore.cancel(ready);
    }

    @Test public void malformedCompletionDoesNotConsumeTheOneAllowedRealCompletion() {
        String token = issue(); ShadowBinder.setCallingUid(hostUid);
        assertEquals("原始文字", provider.call("drag_take", null, request(token)).getString("text"));
        Bundle wrongType = request(token); wrongType.putInt("text", 7);
        assertNull(provider.call("drag_complete", null, wrongType).getString("token"));
        Bundle tooLong = request(token); tooLong.putString("text", "x".repeat(DragPayload.MAX_TEXT + 1));
        assertNull(provider.call("drag_complete", null, tooLong).getString("token"));
        String ready = provider.call("drag_complete", null, completion(token)).getString("token");
        assertNotNull(ready);
        assertNull(provider.call("drag_complete", null, completion(token)).getString("token"));
        assertNotNull(DragActionStore.takeChoice(ready)); assertNull(DragActionStore.takeChoice(ready));
    }
    @Test public void capturedHiddenLinksCrossOnlyTheAuthenticatedValidatedCompletion() {
        String token = issue(); ShadowBinder.setCallingUid(hostUid);
        assertNotNull(provider.call("drag_take", null, request(token)));
        for (Object malformed : List.of("https://example.com/not-a-list", new java.util.ArrayList<>(List.of(7)),
                new java.util.ArrayList<>(List.of("javascript:alert(1)")),
                new java.util.ArrayList<>(List.of("https://example.com/%0Ainjected")),
                new java.util.ArrayList<>(List.of("https://example.com/" + "x".repeat(8192))),
                new java.util.ArrayList<>(java.util.Collections.nCopies(65, "https://example.com")))) {
            Bundle invalid = completion(token); invalid.putSerializable("links", (java.io.Serializable) malformed);
            assertEquals("invalid_request", provider.call("drag_complete", null, invalid).getString("reason"));
        }
        Bundle captured = completion(token);
        captured.putStringArrayList("links", new java.util.ArrayList<>(List.of("https://example.com/card", "https://example.org/actual")));
        ShadowBinder.setCallingUid(hostUid + 1);
        assertTrue(provider.call("drag_complete", null, captured).isEmpty());
        ShadowBinder.setCallingUid(hostUid);
        String ready = provider.call("drag_complete", null, captured).getString("token");
        assertNotNull(ready);
        DragActionStore.Choice choice = DragActionStore.takeChoice(ready);
        assertEquals("原始文字", choice.originalText());
        assertEquals(List.of("https://example.org/actual", "https://example.com/card"), choice.payload().links());
        choice.payload().close();
    }
}
