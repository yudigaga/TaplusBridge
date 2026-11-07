package io.github.yudigaga.taplusbridge.lens;

import android.app.Activity;
import android.app.Dialog;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.util.Base64;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.webkit.WebView;
import org.robolectric.shadows.ShadowWebView;
import org.robolectric.shadow.api.Shadow;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PortalLensPanelAndroidTest {
    private final List<Fixture> fixtures = new ArrayList<>();

    @After public void closeResultWindows() {
        for (Fixture fixture : fixtures) {
            fixture.panel.invalidate();
            fixture.activity.finish();
        }
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    }
    @Test public void callbackRequiresRequestPanelAndActualImageAssociation() {
        Bundle update = new Bundle(); update.putString("request_id", "r1");
        update.putString("panel_session_id", "p1"); update.putString("image_key", "hash1");
        assertTrue(PortalLensPanel.matches(update, "r1", "p1", "hash1"));
        assertFalse(PortalLensPanel.matches(update, "r2", "p1", "hash1"));
        assertFalse(PortalLensPanel.matches(update, "r1", "p2", "hash1"));
        assertFalse(PortalLensPanel.matches(update, "r1", "p1", "hash2"));
        assertFalse(PortalLensPanel.matches(update, "r1", "p1", null));
    }

    @Test public void resultLinksRejectLocalAndNonWebActions() {
        assertTrue(PortalLensPanel.webUrl("https://example.com/result?q=one"));
        for (String invalid : new String[]{"javascript:alert(1)", "intent://image", "file:///tmp/image",
                "https://user:secret@example.com/", "http://localhost/", "http://127.0.0.1/", "http://[::1]/", "https:///bad"})
            assertFalse(invalid, PortalLensPanel.webUrl(invalid));
    }

    @Test public void inlineThumbnailsAcceptBoundedRasterDataOnly() {
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        assertArrayEquals(jpeg, PortalLensPanel.inlineThumbnail("data:image/jpeg;base64,"
                + Base64.encodeToString(jpeg, Base64.NO_WRAP)));
        assertNull(PortalLensPanel.inlineThumbnail("data:image/jpeg;base64,%%%"));
        assertNull(PortalLensPanel.inlineThumbnail("data:image/svg+xml;base64,PHN2Zz4="));
        assertNull(PortalLensPanel.inlineThumbnail("data:image/png;base64,"));
        assertNull(PortalLensPanel.inlineThumbnail("data:image/png;base64,"
                + Base64.encodeToString(new byte[256 * 1024 + 1], Base64.NO_WRAP)));
    }

    @Test public void closeFromBackgroundInvalidatesBeforeMainThreadCleanup() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.current());
        Thread close = new Thread(fixture.panel::invalidate); close.start(); close.join();
        assertFalse("A callback must be stale before the posted UI cleanup executes", fixture.current());
        assertFalse("The module must not recycle the host-owned image", fixture.image.get().isRecycled());
    }

    @Test public void closingPanelDisconnectsPendingThumbnailBeforeItsReadTimeout() throws Exception {
        Fixture fixture = new Fixture();
        try (SlowThumbnailServer server = new SlowThumbnailServer(1)) {
            fixture.loadThumbnail(server.url());
            server.awaitRequests();
            long started = System.nanoTime();
            fixture.panel.invalidate();
            assertTrue("Closing must not wait on HttpURLConnection.disconnect on the UI thread",
                    System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
            assertTrue("Closing the result must disconnect an active thumbnail instead of waiting for its network timeout",
                    server.awaitDisconnected());
            assertFalse("Cancellation must not recycle the host-owned image", fixture.image.get().isRecycled());
        }
    }

    @Test public void replacingImageDisconnectsPendingThumbnailBeforeItsReadTimeout() throws Exception {
        Fixture fixture = new Fixture();
        try (SlowThumbnailServer server = new SlowThumbnailServer(1)) {
            fixture.loadThumbnail(server.url());
            server.awaitRequests();
            Bitmap previous = fixture.image.getAndSet(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888));
            fixture.panel.refresh();
            assertTrue("Replacing the selected image must actively disconnect its previous thumbnail",
                    server.awaitDisconnected());
            assertFalse("Replacing a thumbnail request must not recycle the host's previous image", previous.isRecycled());
        }
    }

    @Test public void pendingThumbnailsDoNotDelayAnotherImagePreparation() throws Exception {
        Fixture previous = new Fixture();
        try (SlowThumbnailServer server = new SlowThumbnailServer(2)) {
            previous.loadThumbnail(server.url());
            previous.loadThumbnail(server.url());
            server.awaitRequests();
            Fixture next = new Fixture();
            next.panel.invalidate();
            Field enabled = PortalLensPanel.class.getDeclaredField("enabled"); enabled.setAccessible(true); enabled.setBoolean(next.panel, true);
            next.panel.search();
            Object request = next.activeJob(); assertNotNull(request);
            Field key = request.getClass().getDeclaredField("key"); key.setAccessible(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (key.get(request) == null && System.nanoTime() < deadline) Thread.sleep(10);
            assertNotNull("Preparing a new image must not wait for either older thumbnail network request", key.get(request));
        }
    }

    @Test public void changedImageAndDetachedCardRejectPreviousCallbacks() throws Exception {
        Fixture fixture = new Fixture();
        assertTrue(fixture.current());
        Bitmap original = fixture.image.get();
        fixture.image.set(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888));
        assertFalse(fixture.current());
        fixture.image.set(original);
        assertTrue(fixture.current());
        fixture.activity.setContentView(new LinearLayout(fixture.activity));
        assertFalse(fixture.current());
        assertFalse(original.isRecycled());
    }

    @Test public void explicitInteractionStaysVisibleUntilAcknowledgedAndRestoresOnCancellation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.deliver(fixture.update("loading"));
        fixture.installInteractionPackages();
        Method begin = PortalLensPanel.class.getDeclaredMethod("beginInteraction", fixture.job.getClass());
        begin.setAccessible(true);
        assertTrue((Boolean) begin.invoke(fixture.panel, fixture.job));
        View portal = fixture.activity.getWindow().getDecorView();
        View root = fixture.dialog().getWindow().getDecorView();
        assertEquals("Keep the result window visible for the explicit launch permission check", View.VISIBLE, root.getVisibility());
        assertEquals("The original portal stays suppressed while the result window owns the screen", View.INVISIBLE, portal.getVisibility());
        Field awaiting = fixture.job.getClass().getDeclaredField("awaitingInteractionOpen");
        awaiting.setAccessible(true); awaiting.setBoolean(fixture.job, true);
        Method opened = PortalLensPanel.class.getDeclaredMethod("interactionOpened", fixture.job.getClass());
        opened.setAccessible(true); opened.invoke(fixture.panel, fixture.job);
        assertEquals(View.INVISIBLE, root.getVisibility());
        assertTrue("Interaction must not detach the panel or cancel its request", fixture.current());
        fixture.panel.invalidate();
        assertEquals(View.VISIBLE, portal.getVisibility());
        assertFalse(fixture.sheetShowing());
        assertFalse(fixture.current());
    }

    @Test public void missingActivityAcknowledgementKeepsSameSessionAndOffersRetry() throws Exception {
        Fixture fixture = new Fixture(); fixture.installInteractionPackages();
        Method begin = PortalLensPanel.class.getDeclaredMethod("beginInteraction", fixture.job.getClass());
        begin.setAccessible(true); assertTrue((Boolean) begin.invoke(fixture.panel, fixture.job));
        PendingIntent pending = PendingIntent.getActivity(fixture.activity, 0,
                new Intent(fixture.activity, Activity.class), PendingIntent.FLAG_IMMUTABLE);
        Field intent = fixture.job.getClass().getDeclaredField("interactionIntent"); intent.setAccessible(true); intent.set(fixture.job, pending);
        Field awaiting = fixture.job.getClass().getDeclaredField("awaitingInteractionOpen"); awaiting.setAccessible(true); awaiting.setBoolean(fixture.job, true);
        Field watchdog = fixture.job.getClass().getDeclaredField("launchWatchdog"); watchdog.setAccessible(true);
        ((Runnable) watchdog.get(fixture.job)).run();
        assertTrue("A blocked launch must not discard or reupload the current image", fixture.current());
        assertSame(pending, intent.get(fixture.job));
        assertFalse(awaiting.getBoolean(fixture.job));
        assertEquals(View.VISIBLE, fixture.dialog().getWindow().getDecorView().getVisibility());
        assertEquals(View.INVISIBLE, fixture.activity.getWindow().getDecorView().getVisibility());
        Field contents = PortalLensPanel.class.getDeclaredField("contents"); contents.setAccessible(true);
        LinearLayout column = (LinearLayout) contents.get(fixture.panel);
        boolean hasMessage = false, hasRetry = false;
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child instanceof TextView text && text.getText().toString().contains("当前图片无需重新上传")) hasMessage = true;
            if (child instanceof Button button && "处理 Google 验证".contentEquals(button.getText())
                    && button.isEnabled() && button.hasOnClickListeners()) hasRetry = true;
        }
        assertTrue("A silent blocked launch must explain that the current image is retained", hasMessage);
        assertTrue("The native panel must offer a working same-session retry button", hasRetry);
        fixture.panel.invalidate();
    }

    @Test public void android14SenderExplicitlyOptsIntoBackgroundLaunch() {
        ActivityOptions options = ReflectionHelpers.callStaticMethod(ActivityOptions.class, "fromBundle",
                ClassParameter.from(Bundle.class, PortalLensPanel.interactionLaunchOptions()));
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                options.getPendingIntentBackgroundActivityStartMode());
    }

    @Test @Config(sdk = 28) public void olderSenderDoesNotUseNewBackgroundLaunchApi() {
        assertNull(PortalLensPanel.interactionLaunchOptions());
    }

    @Test public void originalPageStartsDisabledAndOnlyExportsActualCurrentSearchOnClick() throws Exception {
        Fixture fixture = new Fixture();
        Bundle loading = fixture.update("loading"); fixture.deliver(loading);
        Button original = fixture.originalPage();
        assertEquals("原网页", original.getText().toString()); assertFalse(original.isEnabled());
        assertTrue(original.getContentDescription().toString().contains("图片会话"));
        assertNull(applicationShadow().getNextStartedActivity());

        String result = "https://lens.google.com/search?vsrid=current-image";
        Bundle ready = fixture.update("results_ready"); ready.putString("result_url", result);
        Bundle item = new Bundle(); item.putString("title", "匹配网页"); item.putString("page_url", "https://example.org/match");
        java.util.ArrayList<Bundle> items = new java.util.ArrayList<>(); items.add(item); ready.putParcelableArrayList("items", items);
        fixture.deliver(ready);
        assertTrue("Results keep the worker WebView alive for the current image session", fixture.current());
        original = fixture.originalPage(); assertTrue(original.isEnabled());
        assertNull("Receiving results must keep native results inside the portal", applicationShadow().getNextStartedActivity());
        fixture.installInteractionPackages();
        original.performClick();
        Intent opened = applicationShadow().getNextStartedActivity();
        assertNotNull(opened); assertEquals(LensInteractionActivity.class.getName(), opened.getComponent().getClassName());
        assertTrue(opened.getBooleanExtra("return_to_sheet", false));
        fixture.panel.invalidate(); assertFalse(original.isEnabled());
    }

    @Test public void staleRequestsAndUnsupportedUrlsNeverEnableOriginalPage() throws Exception {
        Fixture fixture = new Fixture();
        Bundle stale = fixture.update("loading"); stale.putString("request_id", "another-request");
        stale.putString("result_url", "https://lens.google.com/search?vsrid=old-image"); fixture.deliver(stale);
        Bundle loading = fixture.update("loading"); loading.putString("result_url", "https://lens.google.com/v3/upload"); fixture.deliver(loading);
        assertFalse(fixture.originalPage().isEnabled());
        for (String url : new String[]{"https://lens.google.com/", "https://www.google.com/search?q=ordinary", "https://example.com/search?q=x",
                "https://lh3.google.com/photo.jpg", "http://lens.google.com/search?vsrid=x"})
            assertFalse(url, PortalLensPanel.resultPageUrl(url));
        assertTrue(PortalLensPanel.resultPageUrl("https://lens.google.com/search"));
        fixture.panel.invalidate();
    }

    @Test public void parseProblemsDoNotOfferLegacyManualUploadWorkflow() throws Exception {
        Fixture fixture = new Fixture();
        Bundle update = fixture.update("needs_interaction"); update.putString("message", "当前网页暂时无法提取结果");
        update.putParcelable("interaction", PendingIntent.getActivity(fixture.activity, 0,
                new Intent(fixture.activity, Activity.class), PendingIntent.FLAG_IMMUTABLE));
        fixture.deliver(update);
        Field contents = PortalLensPanel.class.getDeclaredField("contents"); contents.setAccessible(true);
        LinearLayout column = (LinearLayout) contents.get(fixture.panel);
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child instanceof Button button) assertFalse(button.getText().toString().contains("网页会话"));
        }
        update.putBoolean("google_interaction_required", true); fixture.deliver(update);
        column = (LinearLayout) contents.get(fixture.panel); boolean explicitVerification = false;
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child instanceof Button button && "处理 Google 验证".contentEquals(button.getText())) explicitVerification = true;
        }
        assertTrue("Only a real Google interaction flag enables the explicit verification action", explicitVerification);
        assertNull(applicationShadow().getNextStartedActivity());
        fixture.panel.invalidate();
    }

    @Test public void resultClickBrowsesInsidePanelAndBackRestoresCachedNativeCards() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliverResults();
        LinearLayout cached = fixture.contents(); View row = fixture.resultRow();
        assertNotNull(row); row.performClick();
        WebView web = fixture.sourceWebView(); assertNotNull(web);
        ShadowWebView shadow = Shadow.extract(web);
        assertEquals("https://example.org/match", shadow.getLastLoadedUrl());
        assertFalse(web.getSettings().getAllowFileAccess()); assertFalse(web.getSettings().getAllowContentAccess());
        assertNull("A source card must not launch an external app", applicationShadow().getNextStartedActivity());
        LinearLayout viewer = fixture.sourceRoot(); assertSame(fixture.resultArea(), viewer.getParent());
        assertEquals("The source browser must not replace the host's image area", 1, fixture.group.getChildCount());
        Button viewerOriginal = fixture.originalPage(); assertTrue(viewerOriginal.isEnabled());
        boolean returned = false;
        for (int i = 0; i < viewer.getChildCount(); i++) {
            if (viewer.getChildAt(i) instanceof Button back && "返回结果".contentEquals(back.getText())) {
                back.performClick(); returned = true; break;
            }
        }
        assertTrue(returned); assertNull(fixture.sourceWebView()); assertTrue(shadow.wasDestroyCalled());
        assertTrue("The persistent sheet header still exposes the same original search after returning", viewerOriginal.isEnabled());
        assertSame(cached, fixture.contents()); assertSame(row, fixture.resultRow());
        assertEquals(View.VISIBLE, fixture.contents().getParent() instanceof View parent ? parent.getVisibility() : View.GONE);
        assertTrue(fixture.originalPage().isEnabled()); fixture.panel.invalidate();
    }

    @Test public void originalPageOpensTheLiveLensSessionInsideThePortalApp() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliverResults(); fixture.installInteractionPackages();
        LinearLayout cached = fixture.contents();
        fixture.originalPage().performClick();
        Intent viewer = applicationShadow().getNextStartedActivity(); assertNotNull(viewer);
        assertEquals(LensInteractionActivity.class.getName(), viewer.getComponent().getClassName());
        assertTrue(viewer.getBooleanExtra("return_to_sheet", false));
        assertEquals("taplus-lens", viewer.getData().getScheme());
        View decor = fixture.dialog().getWindow().getDecorView();
        Bundle opened = fixture.update("session_viewer_opened"); fixture.deliver(opened);
        assertEquals(View.INVISIBLE, decor.getVisibility()); assertTrue(fixture.current());
        Bundle closed = fixture.update("interaction_closed"); fixture.deliver(closed);
        assertEquals(View.VISIBLE, decor.getVisibility()); assertTrue(fixture.current());
        assertSame("Returning from the session viewer restores the native result list", cached, fixture.contents());
        fixture.panel.invalidate();
    }

    @Test public void viewerLeaseIsPublishedBeforeQueuedUiAndSurvivesPageResults() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliverResults();
        int moduleUid = Process.myUid(), sourceUid = moduleUid + 1;
        fixture.openSessionViewer(sourceUid);
        Bundle opened = fixture.update("session_viewer_opened"); fixture.prepare(opened);
        assertTrue("The Binder callback must publish viewer visibility before UI work runs",
                (Boolean) fixture.field(fixture.job.getClass(), "sessionViewerVisible", fixture.job));
        assertTrue(PortalLensPanel.allowInteractionForeground(moduleUid, true));
        for (String status : new String[]{"results_ready", "empty", "query_error", "parse_error"}) {
            fixture.prepare(fixture.update(status));
            assertTrue(status, PortalLensPanel.allowInteractionKeyboard());
            assertTrue(status, PortalLensPanel.allowInteractionForeground(sourceUid, false));
        }
        assertFalse("Unrelated foreground apps and Home must still reach the host cancellation path",
                PortalLensPanel.allowInteractionForeground(moduleUid + 1234, true));
        fixture.deliver(opened);
        assertEquals(View.INVISIBLE, fixture.dialog().getWindow().getDecorView().getVisibility());
        Bundle closed = fixture.update("interaction_closed"); fixture.prepare(closed);
        assertFalse("The close callback must revoke keyboard ownership before the queued UI restoration",
                PortalLensPanel.allowInteractionKeyboard());
        fixture.deliver(closed);
        assertEquals(View.VISIBLE, fixture.dialog().getWindow().getDecorView().getVisibility());
        assertTrue(fixture.current());
        assertTrue(PortalLensPanel.allowInteractionForeground(sourceUid, true));
    }

    @Test public void viewerPageCallbacksKeepItsForegroundAndKeyboardOwnership() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliverResults();
        int moduleUid = Process.myUid(), sourceUid = moduleUid + 1;
        fixture.openSessionViewer(sourceUid); fixture.deliver(fixture.update("session_viewer_opened"));
        assertTrue(PortalLensPanel.allowInteractionForeground(moduleUid, true));
        fixture.deliverResults(); assertTrue(PortalLensPanel.allowInteractionKeyboard());
        fixture.deliver(fixture.update("empty")); assertTrue(PortalLensPanel.allowInteractionKeyboard());
        fixture.deliver(fixture.update("query_error")); assertTrue(PortalLensPanel.allowInteractionKeyboard());
        assertTrue(PortalLensPanel.allowInteractionForeground(sourceUid, false));
        assertTrue(fixture.originalPage().isEnabled());
        fixture.deliver(fixture.update("interaction_closed"));
        assertFalse(PortalLensPanel.allowInteractionKeyboard()); assertTrue(fixture.current());
    }

    @Test public void staleViewerEventsAndChangedImagesCannotExtendTheLease() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliverResults();
        int moduleUid = Process.myUid(), sourceUid = moduleUid + 1;
        fixture.openSessionViewer(sourceUid); fixture.deliver(fixture.update("session_viewer_opened"));
        assertTrue(PortalLensPanel.allowInteractionForeground(moduleUid, true));
        Bundle staleClose = fixture.update("interaction_closed"); staleClose.putInt("page_generation", 0);
        fixture.prepare(staleClose); assertTrue(PortalLensPanel.allowInteractionKeyboard());
        Bundle otherClose = fixture.update("interaction_closed"); otherClose.putString("request_id", "other-request");
        fixture.prepare(otherClose); assertTrue(PortalLensPanel.allowInteractionKeyboard());
        assertFalse(PortalLensPanel.allowInteractionForeground(moduleUid + 1234, true));
        fixture.image.set(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888));
        assertFalse(PortalLensPanel.allowInteractionKeyboard());
        assertFalse(PortalLensPanel.allowInteractionForeground(moduleUid, true));
        fixture.panel.invalidate(); assertFalse(PortalLensPanel.allowInteractionForeground(sourceUid, true));
    }

    @Test public void terminalWorkerFailuresStillRevokeTheViewerLease() throws Exception {
        for (String status : new String[]{"network_error", "timeout", "cancelled"}) {
            Fixture fixture = new Fixture(); fixture.deliverResults();
            fixture.openSessionViewer(Process.myUid() + 1); fixture.deliver(fixture.update("session_viewer_opened"));
            assertTrue(PortalLensPanel.allowInteractionForeground(Process.myUid(), true));
            Bundle terminal = fixture.update(status); fixture.prepare(terminal);
            assertFalse(status, PortalLensPanel.allowInteractionKeyboard());
            fixture.deliver(terminal); assertFalse(status, fixture.current());
        }
    }

    @Test public void verificationResultsStillEndTheTemporaryActivityLease() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliver(fixture.update("loading")); fixture.installInteractionPackages();
        Method begin = PortalLensPanel.class.getDeclaredMethod("beginInteraction", fixture.job.getClass());
        begin.setAccessible(true); assertTrue((Boolean) begin.invoke(fixture.panel, fixture.job));
        Field awaiting = fixture.job.getClass().getDeclaredField("awaitingInteractionOpen");
        awaiting.setAccessible(true); awaiting.setBoolean(fixture.job, true);
        fixture.deliver(fixture.update("interaction_opened"));
        assertTrue(PortalLensPanel.allowInteractionForeground(Process.myUid(), true));
        fixture.prepare(fixture.update("results_ready"));
        assertFalse("A verification activity still finishes when its result is handed back",
                PortalLensPanel.allowInteractionKeyboard());
    }

    @Test public void queryErrorReenablesTheOriginalPageAndRemovesTheRequestWatchdog() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliverResults();
        Field ready = fixture.job.getClass().getDeclaredField("searchReady"); ready.setAccessible(true); ready.setBoolean(fixture.job, false);
        fixture.deliver(fixture.update("loading"));
        Handler main = (Handler) fixture.field(PortalLensPanel.class, "MAIN", null);
        Runnable watchdog = (Runnable) fixture.field(fixture.job.getClass(), "watchdog", fixture.job);
        assertTrue(main.hasCallbacks(watchdog)); assertFalse(fixture.originalPage().isEnabled());
        fixture.deliver(fixture.update("query_error"));
        assertFalse(main.hasCallbacks(watchdog)); assertTrue(fixture.originalPage().isEnabled());
        assertTrue(fixture.searchInput().isEnabled()); assertTrue(fixture.current());
    }

    @Test public void resultWindowNeverMutatesOriginalPortalViewsAndDismissalRestoresThem() throws Exception {
        Fixture fixture = new Fixture();
        View portal = fixture.activity.getWindow().getDecorView();
        View imageControl = fixture.group.getChildAt(0);
        int hostChildren = fixture.host.getChildCount();
        fixture.deliver(fixture.update("loading"));
        LensResultSheet sheet = fixture.sheet();
        assertTrue(sheet.isShowing()); assertFalse(sheet.isExpanded());
        assertEquals(View.INVISIBLE, portal.getVisibility());
        assertEquals(hostChildren, fixture.host.getChildCount());
        assertEquals(1, fixture.group.getChildCount()); assertSame(imageControl, fixture.group.getChildAt(0));
        assertFalse(fixture.searchButton().isEnabled()); assertFalse(fixture.searchInput().isEnabled());
        fixture.deliverResults();
        assertSame(sheet, fixture.sheet()); assertTrue(sheet.isShowing());
        assertNotNull(fixture.resultRow()); assertTrue(fixture.searchButton().isEnabled());
        assertEquals(hostChildren, fixture.host.getChildCount());
        assertEquals(1, fixture.group.getChildCount()); assertSame(imageControl, fixture.group.getChildAt(0));
        Button original = fixture.originalPage();
        sheet.expand(); assertTrue(sheet.isExpanded());
        sheet.dismiss();
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
        assertFalse(sheet.isShowing()); assertFalse(original.isEnabled());
        assertEquals(View.VISIBLE, portal.getVisibility());
        assertEquals(hostChildren, fixture.host.getChildCount());
        assertEquals(1, fixture.group.getChildCount()); assertSame(imageControl, fixture.group.getChildAt(0));
        assertFalse(fixture.image.get().isRecycled());
    }

    @Test public void textSearchUsesTheExistingImageSessionWithoutStartingAnotherWorker() throws Exception {
        Fixture fixture = new Fixture();
        String base = "https://www.google.com/search?udm=26&vsrid=fixture&vsint=A%2BB%3D";
        fixture.deliverResults(base);
        View nativeCard = fixture.resultRow(); LensResultSheet sheet = fixture.sheet();
        assertNotNull(fixture.activeJob()); assertTrue(fixture.searchButton().isEnabled());
        fixture.searchInput().setText("餐馆 营业时间 &?"); fixture.searchButton().performClick();
        String[] call = fixture.awaitRefinement();
        assertEquals("餐馆 营业时间 &?", call[1]); assertEquals("all", call[2]);
        String target = LensSearchUrl.build(base, call[1], call[2]); android.net.Uri parsed = android.net.Uri.parse(target);
        assertEquals("26", parsed.getQueryParameter("udm"));
        assertEquals("餐馆 营业时间 &?", parsed.getQueryParameter("q"));
        assertEquals("fixture", parsed.getQueryParameter("vsrid"));
        assertTrue(parsed.getEncodedQuery().contains("vsint=A%2BB%3D"));
        assertSame(sheet, fixture.sheet()); assertTrue(sheet.isExpanded());
        assertNotNull("Refining the existing query must keep its uploaded-image session alive", fixture.activeJob());
        assertNull("Google query navigation must reuse the session WebView, not create a host WebView", fixture.sourceWebView());
        assertEquals(1, fixture.group.getChildCount());
        fixture.deliverResults(target);
        assertTrue(fixture.searchButton().isEnabled());
        fixture.installInteractionPackages();
        fixture.originalPage().performClick();
        Intent original = applicationShadow().getNextStartedActivity(); assertNotNull(original);
        assertEquals(LensInteractionActivity.class.getName(), original.getComponent().getClassName());
        fixture.searchInput().setText(""); fixture.mode("all").performClick();
        String[] reset = fixture.awaitRefinement();
        assertEquals("", reset[1]); assertEquals("all", reset[2]);
        assertNull(fixture.sourceWebView()); assertNotNull(fixture.activeJob());
        fixture.deliverResults(base);
        assertNotNull(nativeCard); assertNotNull(fixture.resultRow());
    }

    @Test public void actualGoogleModesNavigateWithinTheSheetAndKeepTheCurrentImageSession() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliverResults();
        View nativeCard = fixture.resultRow();
        String[][] modes = {{"ai", "50"}, {"exact", "48"}, {"visual", "44"}};
        for (String[] mode : modes) {
            fixture.mode(mode[0]).performClick();
            String[] call = fixture.awaitRefinement(); assertEquals(mode[0], call[2]);
            android.net.Uri target = android.net.Uri.parse(LensSearchUrl.build("https://lens.google.com/search?vsrid=fixture", call[1], call[2]));
            assertEquals("lens.google.com", target.getHost()); assertEquals("/search", target.getPath());
            assertEquals(mode[1], target.getQueryParameter("udm")); assertEquals("fixture", target.getQueryParameter("vsrid"));
            assertTrue(fixture.mode(mode[0]).isSelected()); assertTrue(fixture.sheet().isExpanded());
            assertNotNull("Mode navigation reuses the live image session", fixture.activeJob());
            assertNull("Mode selection stays inside the same Google WebView session", fixture.sourceWebView());
            fixture.deliverResults(target.toString());
        }
        fixture.mode("all").performClick();
        String[] all = fixture.awaitRefinement(); assertEquals("all", all[2]);
        assertNull(fixture.sourceWebView()); fixture.deliverResults("https://lens.google.com/search?vsrid=fixture");
        assertNotNull(nativeCard); assertNotNull(fixture.resultRow()); assertTrue(fixture.mode("all").isSelected());
        assertEquals(1, fixture.group.getChildCount());
    }

    @Test public void derivedModeOriginalPageIsImmediateAndBackRestoresTheNativeQueryState() throws Exception {
        Fixture fixture = new Fixture();
        String base = "https://www.google.com/search?udm=26&p=ENCODED%2BIMAGE%2F%3D";
        fixture.deliverResults(base);
        View nativeCard = fixture.resultRow(); assertNotNull(nativeCard);
        fixture.searchInput().setText("店名与营业时间"); fixture.mode("ai").performClick();
        String[] call = fixture.awaitRefinement();
        String target = LensSearchUrl.build(base, call[1], call[2]); android.net.Uri query = android.net.Uri.parse(target);
        assertEquals("50", query.getQueryParameter("udm"));
        assertEquals("店名与营业时间", query.getQueryParameter("q"));
        assertTrue(query.getEncodedQuery().contains("p=ENCODED%2BIMAGE%2F%3D"));
        assertTrue(fixture.mode("ai").isSelected()); assertNotNull(fixture.activeJob());
        assertNull(applicationShadow().getNextStartedActivity());
        fixture.deliverResults(target);
        fixture.installInteractionPackages();
        assertTrue(fixture.originalPage().isEnabled()); fixture.originalPage().performClick();
        Intent original = applicationShadow().getNextStartedActivity(); assertNotNull(original);
        assertEquals(LensInteractionActivity.class.getName(), original.getComponent().getClassName());
        assertEquals("店名与营业时间", fixture.searchInput().getText().toString());
        assertTrue(fixture.mode("ai").isSelected()); assertNotNull(fixture.activeJob());
        assertNotNull(nativeCard); assertEquals(1, fixture.group.getChildCount());
    }

    @Test public void emptyAllSearchHidesTheOwnedKeyboardBeforeReturningNativeCards() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliverResults();
        fixture.realizeSheet();
        View nativeCard = fixture.resultRow();
        android.view.inputmethod.InputMethodManager input = (android.view.inputmethod.InputMethodManager)
                org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        org.robolectric.shadows.ShadowInputMethodManager keyboard = Shadow.extract(input);
        assertNotNull(fixture.searchInput().getWindowToken());
        input.showSoftInput(fixture.searchInput(), 0); assertTrue(keyboard.isSoftInputVisible());
        fixture.searchInput().setText(""); fixture.searchButton().performClick();
        assertFalse("The all/empty early return must still hide this sheet's keyboard", keyboard.isSoftInputVisible());
        assertEquals("", fixture.awaitRefinement()[1]); assertNull(fixture.sourceWebView()); assertNotNull(fixture.activeJob());
        assertNotNull(nativeCard);
    }

    @Test public void keyboardQuitBypassRequiresTheFocusedLiveSheetAndCurrentImage() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliverResults(); fixture.realizeSheet();
        assertTrue(fixture.searchInput().requestFocus());
        assertTrue("The host must not cancel this sheet when its own search field opens the keyboard",
                PortalLensPanel.allowInteractionKeyboard());
        Bitmap original = fixture.image.get();
        fixture.image.set(Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888));
        assertTrue(fixture.sheetShowing());
        assertFalse("A stale image must not keep the host's keyboard cancellation bypass active",
                PortalLensPanel.allowInteractionKeyboard());
        fixture.panel.invalidate();
        assertFalse(PortalLensPanel.allowInteractionKeyboard());
        assertFalse(original.isRecycled());
    }

    @Test public void sourceViewerBlocksAppAndLocalSchemesAndDiesOnPanelInvalidation() throws Exception {
        Fixture fixture = new Fixture(); fixture.deliverResults(); fixture.resultRow().performClick();
        WebView web = fixture.sourceWebView(); ShadowWebView shadow = Shadow.extract(web);
        android.webkit.WebViewClient client = shadow.getWebViewClient();
        assertFalse(client.shouldOverrideUrlLoading(web, "https://example.org/next"));
        for (String address : new String[]{"intent://example", "tel:123", "file:///sdcard/private", "content://private/image", "javascript:alert(1)"})
            assertTrue(address, client.shouldOverrideUrlLoading(web, address));
        assertNull(applicationShadow().getNextStartedActivity());
        fixture.panel.invalidate(); assertTrue(shadow.wasDestroyCalled());
        assertNull(fixture.sourceWebView()); assertNull(fixture.sourceRoot());
    }

    @Test public void privilegedHostCannotCreateSourceWebView() {
        for (int uid : new int[]{0, 1000, 1001, 2000, 101000}) assertFalse(PortalLensPanel.sourceWebViewAllowed(uid));
        assertTrue(PortalLensPanel.sourceWebViewAllowed(10123)); assertTrue(PortalLensPanel.sourceWebViewAllowed(110123));
    }

    @Test public void originalPageButtonStaysDisabledWhenTheSameSessionViewerIsUnavailable() throws Exception {
        Fixture fixture = new Fixture();
        Field viewer = fixture.job.getClass().getDeclaredField("sessionViewerIntent"); viewer.setAccessible(true); viewer.set(fixture.job, null);
        fixture.deliverResults();
        assertFalse(fixture.originalPage().isEnabled()); fixture.originalPage().performClick();
        assertNull(applicationShadow().getNextStartedActivity());
    }

    private static org.robolectric.shadows.ShadowApplication applicationShadow() {
        return org.robolectric.shadow.api.Shadow.extract(org.robolectric.RuntimeEnvironment.getApplication());
    }

    /** Local sockets hold response headers open, so only client cancellation can release the request. */
    private static final class SlowThumbnailServer implements Closeable {
        private final ServerSocket server;
        private final ExecutorService workers;
        private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch requested, disconnected;
        private final String address;

        SlowThumbnailServer(int requests) throws Exception {
            InetAddress local = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .flatMap(network -> Collections.list(network.getInetAddresses()).stream())
                    .filter(value -> value instanceof Inet4Address && !value.isLoopbackAddress())
                    .findFirst().orElseThrow(() -> new IllegalStateException("A local IPv4 interface is required for this local-only HTTP fixture"));
            server = new ServerSocket(0, requests, local);
            address = "http://" + local.getHostAddress() + ":" + server.getLocalPort() + "/thumbnail";
            assertTrue("The local fixture must use the unchanged production URL validation", PortalLensPanel.webUrl(address));
            requested = new CountDownLatch(requests); disconnected = new CountDownLatch(requests);
            workers = Executors.newFixedThreadPool(requests + 1, task -> {
                Thread thread = new Thread(task, "TaplusBridge-thumbnail-fixture"); thread.setDaemon(true); return thread;
            });
            workers.execute(() -> {
                try {
                    for (int i = 0; i < requests; i++) {
                        Socket socket = server.accept();
                        synchronized (sockets) {
                            if (server.isClosed()) { socket.close(); return; }
                            sockets.add(socket); workers.execute(() -> holdResponse(socket));
                        }
                    }
                } catch (IOException stopped) { /* close() ends the local fixture. */ }
            });
        }

        String url() { return address; }
        void awaitRequests() throws InterruptedException {
            assertTrue("The thumbnail downloader must reach the local endpoint", requested.await(2, TimeUnit.SECONDS));
        }
        boolean awaitDisconnected() throws InterruptedException { return disconnected.await(2, TimeUnit.SECONDS); }
        private void holdResponse(Socket socket) {
            try (socket; InputStream input = socket.getInputStream()) {
                int tail = 0, value;
                while ((value = input.read()) >= 0) {
                    tail = tail << 8 | value;
                    if (tail == 0x0d0a0d0a) break;
                }
                if (value < 0) return;
                requested.countDown();
                while (input.read() >= 0) { /* No response is sent until the client disconnects. */ }
            } catch (IOException closed) { /* Socket reset also proves that the client released the request. */ }
            finally { disconnected.countDown(); }
        }
        @Override public void close() throws IOException {
            server.close();
            synchronized (sockets) {
                for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            }
            workers.shutdownNow();
            try { assertTrue("The local thumbnail fixture must release every socket worker", workers.awaitTermination(2, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        }
    }

    private final class Fixture {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        final AtomicReference<Bitmap> image = new AtomicReference<>(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888));
        final PortalLensPanel panel;
        final LinearLayout host;
        final RelativeLayout group;
        final Object job;
        final Method current;
        final LinkedBlockingQueue<String[]> refinements = new LinkedBlockingQueue<>();
        Fixture() throws Exception {
            fixtures.add(this);
            host = new LinearLayout(activity);
            TextView action = new TextView(activity); action.setText("识物"); host.addView(action);
            group = new RelativeLayout(activity);
            TextView originalImageControl = new TextView(activity); originalImageControl.setText("传送门原图片控件");
            group.addView(originalImageControl); host.addView(group);
            activity.setContentView(host);
            panel = new PortalLensPanel(host, action, group, image::get, () -> "test.source");
            Class<?> type = Class.forName(PortalLensPanel.class.getName() + "$Job");
            Constructor<?> constructor = type.getDeclaredConstructor(PortalLensPanel.class, Bitmap.class);
            constructor.setAccessible(true); job = constructor.newInstance(panel, image.get());
            Field field = PortalLensPanel.class.getDeclaredField("job"); field.setAccessible(true); field.set(panel, job);
            Field key = type.getDeclaredField("key"); key.setAccessible(true); key.set(job, "fixture-image-hash");
            Intent viewer = new Intent(activity, LensInteractionActivity.class)
                    .setData(android.net.Uri.parse("taplus-lens://session/view/fixture"))
                    .putExtra("request_id", (String) field(type, "id", job))
                    .putExtra("return_to_sheet", true);
            PendingIntent session = PendingIntent.getActivity(activity, 1, viewer,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Field viewerField = type.getDeclaredField("sessionViewerIntent"); viewerField.setAccessible(true); viewerField.set(job, session);
            Field serviceField = type.getDeclaredField("service"); serviceField.setAccessible(true);
            serviceField.set(job, new ILensService.Stub() {
                @Override public void search(android.os.ParcelFileDescriptor image, Bundle request, ILensCallback callback) { }
                @Override public void refine(String requestId, String query, String mode) { refinements.offer(new String[]{requestId, query, mode}); }
                @Override public void cancel(String requestId) { }
                @Override public void attachBrowser(String requestId, String viewId, android.os.IBinder token, int display,
                        int width, int height, ILensSurfaceCallback callback) { }
                @Override public void resizeBrowser(String requestId, String viewId, int width, int height) { }
                @Override public void detachBrowser(String requestId, String viewId) { }
            });
            current = PortalLensPanel.class.getDeclaredMethod("current", type); current.setAccessible(true);
            configureBrowsers("test.browser", "test.browser");
        }
        String[] awaitRefinement() throws Exception {
            String[] request = refinements.poll(2, TimeUnit.SECONDS); assertNotNull("The current worker session must receive the search", request); return request;
        }
        Object field(Class<?> owner, String name, Object target) throws Exception {
            Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target);
        }
        boolean current() throws Exception { return (Boolean) current.invoke(panel, job); }
        Bundle update(String status) throws Exception {
            Bundle update = new Bundle(); update.putString("status", status);
            Field page = job.getClass().getDeclaredField("pageGeneration"); page.setAccessible(true);
            update.putInt("page_generation", Math.max(1, page.getInt(job) + 1));
            Field id = job.getClass().getDeclaredField("id"); id.setAccessible(true); update.putString("request_id", (String) id.get(job));
            Field panelId = PortalLensPanel.class.getDeclaredField("panelId"); panelId.setAccessible(true); update.putString("panel_session_id", (String) panelId.get(panel));
            update.putString("image_key", "fixture-image-hash");
            Field viewer = job.getClass().getDeclaredField("sessionViewerIntent"); viewer.setAccessible(true);
            update.putParcelable("session_viewer", (PendingIntent) viewer.get(job)); return update;
        }
        void deliver(Bundle update) throws Exception {
            prepare(update);
            Method callback = PortalLensPanel.class.getDeclaredMethod("update", job.getClass(), Bundle.class);
            callback.setAccessible(true); callback.invoke(panel, job, update);
        }
        void prepare(Bundle update) throws Exception {
            Method callback = PortalLensPanel.class.getDeclaredMethod("prepareTerminalReturn", job.getClass(), Bundle.class);
            callback.setAccessible(true); callback.invoke(panel, job, update);
        }
        void openSessionViewer(int sourceUid) throws Exception {
            installInteractionPackages(sourceUid); originalPage().performClick();
            assertNotNull(applicationShadow().getNextStartedActivity());
        }
        Button originalPage() throws Exception {
            Field field = LensResultSheet.class.getDeclaredField("originalPage"); field.setAccessible(true); return (Button) field.get(sheet());
        }
        void deliverResults() throws Exception {
            deliverResults("https://lens.google.com/search?vsrid=fixture");
        }
        void deliverResults(String result) throws Exception {
            Bundle ready = update("results_ready"); ready.putString("result_url", result);
            Bundle item = new Bundle(); item.putString("title", "来源网页"); item.putString("page_url", "https://example.org/match");
            java.util.ArrayList<Bundle> items = new java.util.ArrayList<>(); items.add(item); ready.putParcelableArrayList("items", items);
            deliver(ready);
        }
        LinearLayout contents() throws Exception {
            Field field = PortalLensPanel.class.getDeclaredField("contents"); field.setAccessible(true); return (LinearLayout) field.get(panel);
        }
        View resultRow() throws Exception {
            return resultCard(contents());
        }
        View resultCard(View root) {
            if (root instanceof LinearLayout && root.isClickable()) return root;
            if (root instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
                View card = resultCard(group.getChildAt(i)); if (card != null) return card;
            }
            return null;
        }
        LensResultSheet sheet() throws Exception {
            Field field = PortalLensPanel.class.getDeclaredField("resultSheet"); field.setAccessible(true); return (LensResultSheet) field.get(panel);
        }
        Dialog dialog() throws Exception {
            Field field = LensResultSheet.class.getDeclaredField("dialog"); field.setAccessible(true); return (Dialog) field.get(sheet());
        }
        void realizeSheet() throws Exception {
            // Dialog.show() alone leaves its ViewRoot traversal queued under PAUSED looper mode.
            org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
            Object root = ReflectionHelpers.callInstanceMethod(dialog().getWindow().getDecorView(), "getViewRootImpl");
            assertNotNull("The result dialog must own a mounted window before keyboard assertions", root);
            org.robolectric.shadows.ShadowViewRootImpl window = Shadow.extract(root);
            window.callDispatchResized(); window.callWindowFocusChanged(true);
            org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
        }
        boolean sheetShowing() throws Exception { LensResultSheet sheet = sheet(); return sheet != null && sheet.isShowing(); }
        FrameLayout resultArea() throws Exception {
            Field field = PortalLensPanel.class.getDeclaredField("resultArea"); field.setAccessible(true); return (FrameLayout) field.get(panel);
        }
        EditText searchInput() throws Exception {
            Field field = PortalLensPanel.class.getDeclaredField("searchInput"); field.setAccessible(true); return (EditText) field.get(panel);
        }
        Button searchButton() throws Exception {
            Field field = PortalLensPanel.class.getDeclaredField("searchButton"); field.setAccessible(true); return (Button) field.get(panel);
        }
        TextView mode(String value) throws Exception {
            Field field = PortalLensPanel.class.getDeclaredField("modeButtons"); field.setAccessible(true);
            @SuppressWarnings("unchecked") List<TextView> tabs = (List<TextView>) field.get(panel);
            return tabs.stream().filter(tab -> value.equals(tab.getTag())).findFirst().orElseThrow();
        }
        Object activeJob() throws Exception {
            Field field = PortalLensPanel.class.getDeclaredField("job"); field.setAccessible(true); return field.get(panel);
        }
        void loadThumbnail(String address) throws Exception {
            ImageView target = new ImageView(activity); group.addView(target);
            Method method = PortalLensPanel.class.getDeclaredMethod("loadThumbnail", ImageView.class, String.class, job.getClass());
            method.setAccessible(true); method.invoke(panel, target, address, job);
        }
        WebView sourceWebView() throws Exception {
            Field field = PortalLensPanel.class.getDeclaredField("sourceWebView"); field.setAccessible(true); return (WebView) field.get(panel);
        }
        LinearLayout sourceRoot() throws Exception {
            Field field = PortalLensPanel.class.getDeclaredField("sourceRoot"); field.setAccessible(true); return (LinearLayout) field.get(panel);
        }
        void configureBrowsers(String preferred, String... names) {
            org.robolectric.shadows.ShadowPackageManager manager = Shadow.extract(activity.getPackageManager());
            java.util.ArrayList<android.content.pm.ResolveInfo> browsers = new java.util.ArrayList<>();
            for (String name : names) browsers.add(browserInfo(name));
            manager.setResolveInfosForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), browsers);
            manager.setResolveInfosForIntent(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE), java.util.List.of(browserInfo(preferred)));
        }
        android.content.pm.ResolveInfo browserInfo(String name) {
            android.content.pm.ResolveInfo info = new android.content.pm.ResolveInfo(); info.isDefault = true;
            info.activityInfo = new android.content.pm.ActivityInfo(); info.activityInfo.packageName = name;
            info.activityInfo.name = name + ".BrowserActivity"; info.activityInfo.enabled = true;
            info.activityInfo.applicationInfo = new ApplicationInfo(); info.activityInfo.applicationInfo.packageName = name;
            info.activityInfo.applicationInfo.enabled = true; return info;
        }
        void installInteractionPackages() {
            installInteractionPackages(Process.myUid());
        }
        void installInteractionPackages(int sourceUid) {
            for (String name : new String[]{"io.github.yudigaga.taplusbridge", "test.source"}) {
                PackageInfo info = new PackageInfo(); info.packageName = name;
                info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = name;
                info.applicationInfo.uid = "test.source".equals(name) ? sourceUid : Process.myUid();
                ((org.robolectric.shadows.ShadowPackageManager) org.robolectric.shadow.api.Shadow.extract(activity.getPackageManager()))
                        .installPackage(info);
            }
        }
    }
}
