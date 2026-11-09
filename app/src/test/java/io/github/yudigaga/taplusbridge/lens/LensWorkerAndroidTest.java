package io.github.yudigaga.taplusbridge.lens;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowPackageManager;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LensWorkerAndroidTest {
    private Context context;
    private LensService service;
    private ILensService ipc;
    private int hostUid;
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        hostUid = (Process.myUid() / 100000) * 100000 + 10123;
        installHost("5.1.5", 50015, hostUid);
        service = Robolectric.buildService(LensService.class).create().get();
        ipc = ILensService.Stub.asInterface(service.onBind(new Intent()));
    }
    @After public void cleanup() {
        ShadowBinder.setCallingUid(Process.myUid()); service.onUnbind(new Intent()); service.onDestroy();
    }
    @SuppressWarnings("deprecation")
    private void installHost(String name, int code, int uid) {
        PackageInfo info = new PackageInfo(); info.packageName = LensContract.HOST;
        info.versionName = name; info.versionCode = code;
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = LensContract.HOST;
        info.applicationInfo.uid = uid; info.applicationInfo.enabled = true;
        ShadowPackageManager pm = Shadow.extract(context.getPackageManager());
        pm.installPackage(info); pm.setPackagesForUid(uid, LensContract.HOST);
    }
    private Bundle request(byte[] image) throws Exception {
        Bundle request = new Bundle(); request.putString("request_id", "request-1");
        request.putString("panel_session_id", "panel-1"); request.putString("mime_type", "image/png");
        request.putString("image_key", LensContract.hex(MessageDigest.getInstance("SHA-256").digest(image)));
        return request;
    }

    @Test public void onlySupportedHostSameUserCanUseExportedService() throws Exception {
        ShadowBinder.setCallingUid(hostUid); ipc.cancel("absent");
        int attacker = hostUid + 1;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).setPackagesForUid(attacker, "com.example.other");
        ShadowBinder.setCallingUid(attacker); assertThrows(SecurityException.class, () -> ipc.cancel("absent"));
        assertThrows(SecurityException.class, () -> ipc.refine("request-1", "query", "all"));
        installHost("5.1.5", 50015, hostUid + 100000);
        ShadowBinder.setCallingUid(hostUid + 100000); assertThrows(SecurityException.class, () -> ipc.cancel("absent"));
        assertThrows(SecurityException.class, () -> ipc.refine("request-1", "query", "all"));
        installHost("5.1.6", 50016, hostUid);
        ShadowBinder.setCallingUid(hostUid); assertThrows(SecurityException.class, () -> ipc.cancel("absent"));
    }
    @Test public void rejectedSearchClosesReceivedDescriptor() throws Exception {
        File source = File.createTempFile("lens-source-", ".png", context.getCacheDir()); Files.write(source.toPath(), PNG);
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
        ShadowBinder.setCallingUid(hostUid + 100000);
        assertThrows(SecurityException.class, () -> ipc.search(descriptor, request(PNG), null));
        assertThrows(IllegalStateException.class, descriptor::getFd); source.delete();
    }
    @Test public void malformedMetadataNeverPassesTrustBoundary() throws Exception {
        Bundle valid = request(PNG);
        assertEquals("panel-1", LensContract.validatedRequest(valid).getString("panel_session_id"));
        valid.putString("image_key", "wrong"); assertThrows(IllegalArgumentException.class, () -> LensContract.validatedRequest(valid));
        Bundle invalidMime = request(PNG); invalidMime.putString("mime_type", "text/html");
        assertThrows(IllegalArgumentException.class, () -> LensContract.validatedRequest(invalidMime));
    }
    @Test public void refinementIsBoundedToTheLiveRequestAndSupportedModes() {
        assertArrayEquals(new String[]{"request-1", "这是什么", "ai"},
                LensContract.validatedRefinement("request-1", "这是什么", "ai"));
        assertThrows(IllegalArgumentException.class, () -> LensContract.validatedRefinement("request-1", "x".repeat(513), "all"));
        assertThrows(IllegalArgumentException.class, () -> LensContract.validatedRefinement("request-1", "x\ny", "all"));
        assertThrows(IllegalArgumentException.class, () -> LensContract.validatedRefinement("request-1", "query", "other"));
        assertThrows(IllegalArgumentException.class, () -> LensContract.validatedRefinement("", "query", "all"));
    }
    @Test public void browserServiceMethodsRequireTheSameSupportedHostAndAndroidUser() throws Exception {
        int attacker = hostUid + 1;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).setPackagesForUid(attacker, "com.example.other");
        installHost("5.1.5", 50015, hostUid + 100000);
        ILensSurfaceCallback callback = new ILensSurfaceCallback.Stub() {
            @Override public void onSurface(Bundle result) { fail("Rejected callers cannot receive a surface reply"); }
        };
        for (int caller : new int[]{attacker, hostUid + 100000}) {
            ShadowBinder.setCallingUid(caller);
            assertThrows(SecurityException.class, () -> ipc.attachBrowser("request-1", "view-1", new Binder(), 0, 400, 600, callback));
            assertThrows(SecurityException.class, () -> ipc.resizeBrowser("request-1", "view-1", 400, 600));
            assertThrows(SecurityException.class, () -> ipc.detachBrowser("request-1", "view-1"));
        }
        installHost("5.1.6", 50016, hostUid); ShadowBinder.setCallingUid(hostUid);
        assertThrows(SecurityException.class, () -> ipc.attachBrowser("request-1", "view-1", new Binder(), 0, 400, 600, callback));
        assertThrows(SecurityException.class, () -> ipc.resizeBrowser("request-1", "view-1", 400, 600));
        assertThrows(SecurityException.class, () -> ipc.detachBrowser("request-1", "view-1"));
    }
    @Test public void browserRequestsRejectInvalidIdentityGeometryAndMissingHostInputs() throws Exception {
        LensContract.validateBrowser("request-1", "view-1", 1, 1);
        LensContract.validateBrowser("request-1", "view-1", 8192, 4096);
        LensContract.validateBrowser("request-1", "view-1", 2048, 16384);
        for (String invalid : new String[]{null, "", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> LensContract.validateBrowser(invalid, "view-1", 400, 600));
            assertThrows(IllegalArgumentException.class, () -> LensContract.validateBrowser("request-1", invalid, 400, 600));
        }
        ShadowBinder.setCallingUid(hostUid);
        for (int[] size : new int[][]{{0, 600}, {400, -1}, {8193, 1}, {1, 16385}, {8192, 8192}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            assertThrows(IllegalArgumentException.class, () -> ipc.resizeBrowser("request-1", "view-1", size[0], size[1]));
        }
        ILensSurfaceCallback callback = new ILensSurfaceCallback.Stub() {
            @Override public void onSurface(Bundle result) { fail("Invalid input cannot enqueue a surface reply"); }
        };
        assertThrows(IllegalArgumentException.class, () -> ipc.attachBrowser("request-1", "view-1", null, 0, 400, 600, callback));
        assertThrows(IllegalArgumentException.class, () -> ipc.attachBrowser("request-1", "view-1", new Binder(), -1, 400, 600, callback));
        assertThrows(IllegalArgumentException.class, () -> ipc.attachBrowser("request-1", "view-1", new Binder(), 0, 400, 600, null));
        assertThrows(IllegalArgumentException.class, () -> ipc.attachBrowser("request-1", "view-1", new Binder(), 0, 0, 600, callback));
        assertThrows(IllegalArgumentException.class, () -> ipc.detachBrowser("request-1", ""));
    }
    @Test public void unavailableBrowserRepliesKeepEachViewIdentityWithoutCreatingASession() throws Exception {
        ShadowBinder.setCallingUid(hostUid); List<Bundle> replies = new ArrayList<>();
        ILensSurfaceCallback callback = new ILensSurfaceCallback.Stub() {
            @Override public void onSurface(Bundle result) { replies.add(new Bundle(result)); }
        };
        ipc.attachBrowser("request-1", "old-view", new Binder(), 0, 400, 600, callback);
        ipc.attachBrowser("request-1", "new-view", new Binder(), 0, 400, 800, callback);
        assertTrue(replies.isEmpty());
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(2, replies.size());
        for (int index = 0; index < replies.size(); index++) {
            Bundle reply = replies.get(index);
            assertEquals("request-1", reply.getString("request_id"));
            assertEquals(index == 0 ? "old-view" : "new-view", reply.getString("view_id"));
            assertNull(reply.getParcelable("surface_package"));
        }
        assertNull(LensSession.current("request-1"));
    }
    @Test public void mismatchedImageDigestRemovesCopyAndClosesDescriptor() throws Exception {
        File source = File.createTempFile("lens-source-", ".png", context.getCacheDir()); Files.write(source.toPath(), PNG);
        File target = File.createTempFile("lens-copy-", ".png", context.getCacheDir());
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
        Bundle wrong = request(new byte[]{1, 2, 3});
        IOException failure = assertThrows(IOException.class, () -> LensImageStore.copyVerified(descriptor, target, wrong, () -> false));
        assertEquals("image_changed", failure.getMessage()); assertFalse(target.exists());
        assertThrows(IllegalStateException.class, descriptor::getFd); source.delete();
    }
    @Test public void cancellationRemovesPartialImageAndClosesDescriptor() throws Exception {
        File source = File.createTempFile("lens-source-", ".png", context.getCacheDir()); Files.write(source.toPath(), PNG);
        File target = File.createTempFile("lens-copy-", ".png", context.getCacheDir());
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
        IOException failure = assertThrows(IOException.class, () -> LensImageStore.copyVerified(descriptor, target, request(PNG), () -> true));
        assertEquals("cancelled", failure.getMessage()); assertFalse(target.exists());
        assertThrows(IllegalStateException.class, descriptor::getFd); source.delete();
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void verifiedImagePreservesOriginalBytesAndClosesDescriptor() throws Exception {
        File source = File.createTempFile("lens-source-", ".png", context.getCacheDir()); Files.write(source.toPath(), PNG);
        File target = File.createTempFile("lens-copy-", ".png", context.getCacheDir());
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
        android.graphics.BitmapFactory.Options bounds = LensImageStore.copyVerified(descriptor, target, request(PNG), () -> false);
        assertEquals(1, bounds.outWidth); assertEquals(1, bounds.outHeight);
        assertArrayEquals(PNG, Files.readAllBytes(target.toPath()));
        assertThrows(IllegalStateException.class, descriptor::getFd); source.delete(); target.delete();
    }
    @Test public void searchQueuedBeforeUnbindCannotCreateOrphanSession() throws Exception {
        File source = File.createTempFile("lens-source-", ".png", context.getCacheDir()); Files.write(source.toPath(), PNG);
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
        int[] updates = {0};
        ShadowBinder.setCallingUid(hostUid);
        ipc.search(descriptor, request(PNG), new ILensCallback.Stub() {
            @Override public void onUpdate(Bundle update) { updates[0]++; }
        });
        service.onUnbind(new Intent());
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(0, updates[0]); assertNull(LensSession.current("request-1"));
        assertThrows(IllegalStateException.class, descriptor::getFd); source.delete();
    }
    @Test public void rebindCannotReviveImageWorkQueuedByThePreviousBinding() throws Exception {
        File source = File.createTempFile("lens-old-binding-", ".png", context.getCacheDir()); Files.write(source.toPath(), PNG);
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
        int[] updates = {0};
        try {
            ShadowBinder.setCallingUid(hostUid);
            ipc.search(descriptor, request(PNG), new ILensCallback.Stub() {
                @Override public void onUpdate(Bundle update) { updates[0]++; }
            });
            service.onUnbind(new Intent());
            service.onBind(new Intent());
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals("The new binding must not own the old pending image", 0, updates[0]);
            assertNull(LensSession.current("request-1"));
            assertThrows(IllegalStateException.class, descriptor::getFd);
        } finally { LensService.close(descriptor); source.delete(); }
    }
    @Test public void stalePagesCannotCompleteCurrentRequest() throws Exception {
        Bundle request = request(PNG); Bundle parsed = new Bundle();
        parsed.putString("request_id", "request-1"); parsed.putInt("page_generation", 2);
        assertTrue(LensContract.matchesPage(request, 2, parsed));
        assertFalse(LensContract.matchesPage(request, 3, parsed));
        parsed.putString("request_id", "request-2"); assertFalse(LensContract.matchesPage(request, 2, parsed));
    }
    @Test public void uploadUriIsReadOnlyAndExpiresWithTask() throws Exception {
        LensImageProvider provider = Robolectric.buildContentProvider(LensImageProvider.class).create().get();
        File image = File.createTempFile("lens-image-", ".png", context.getCacheDir()); Files.write(image.toPath(), PNG);
        Uri uri = LensImageProvider.expose(image, "image/png");
        assertEquals("image/png", provider.getType(uri));
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r"))) {
            assertArrayEquals(PNG, input.readAllBytes());
        }
        assertThrows(java.io.FileNotFoundException.class, () -> provider.openFile(uri, "rw"));
        assertThrows(java.io.FileNotFoundException.class, () -> provider.openFile(Uri.parse("content://x/../" + image.getName()), "r"));
        LensImageProvider.revoke(image);
        assertThrows(java.io.FileNotFoundException.class, () -> provider.openFile(uri, "r")); image.delete();
    }
    @Test public void pageOriginAllowsOnlyHttpsGoogleWithoutCredentials() {
        assertTrue(LensSession.trustedPage("https://lens.google.com/v3/upload"));
        assertTrue(LensSession.trustedPage("https://www.google.com/search?udm=26"));
        assertFalse(LensSession.trustedPage("http://lens.google.com"));
        assertFalse(LensSession.trustedPage("https://google.com.evil.example/search"));
        assertFalse(LensSession.trustedPage("https://user@google.com/search"));
        assertFalse(LensSession.trustedPage("https://lens.google.com:8443"));
    }
}
