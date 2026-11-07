package io.github.yudigaga.taplusbridge.lens;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWebView;
import org.robolectric.shadows.ShadowPendingIntent;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** HTTP redirects drive an embedded result page; WebView never asks the user to select the image. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LensSessionNavigationAndroidTest {
    private LensSession session;
    private boolean callbackFails;
    private final List<Bundle> updates = new ArrayList<>();

    @Before public void setup() throws Exception {
        Bundle request = new Bundle(); request.putString("request_id", "navigation-request");
        request.putString("panel_session_id", "navigation-panel");
        request.putString("image_key", "1".repeat(64)); request.putString("mime_type", "image/png");
        Constructor<LensSession> constructor = LensSession.class.getDeclaredConstructor(
                Context.class, ParcelFileDescriptor.class, Bundle.class, ILensCallback.class);
        constructor.setAccessible(true);
        session = constructor.newInstance(RuntimeEnvironment.getApplication(), null, request, new ILensCallback.Stub() {
            @Override public void onUpdate(Bundle update) {
                if (callbackFails) throw new IllegalStateException("disconnected");
                updates.add(new Bundle(update));
            }
        });
    }

    @After public void cleanup() throws Exception {
        Method complete = LensSession.class.getDeclaredMethod("complete"); complete.setAccessible(true); complete.invoke(session);
    }

    @Test public void workerDoesNotLoadAnUploadFormBeforeTheHttpResult() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        assertNull(shadow.getLastLoadedUrl()); assertNull(shadow.getLastLoadDataWithBaseURL()); assertNull(shadow.getLastLoadData());
        assertFalse(web.getSettings().getAllowFileAccess()); assertFalse(web.getSettings().getAllowContentAccess());
        assertTrue(updates.isEmpty());
    }

    @Test public void webFileChooserNeverRequestsAnotherCopyOfTheSelectedImage() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        List<Uri[]> supplied = new ArrayList<>();
        assertTrue(shadow.getWebChromeClient().onShowFileChooser(web, supplied::add, null));
        ReflectionHelpers.setField(shadow, "originalUrl", "https://lens.google.com/");
        assertTrue(shadow.getWebChromeClient().onShowFileChooser(web, supplied::add, null));
        assertEquals(2, supplied.size()); assertNull(supplied.get(0)); assertNull(supplied.get(1));
    }

    @Test public void desktopPageLayoutKeepsTheInstalledChromeVersion() {
        String ua = LensSession.desktopUserAgent("Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Version/4.0 Chrome/156.0.8078.47 Mobile Safari/537.36");
        assertTrue(ua.contains("Chrome/156.0.8078.47")); assertFalse(ua.contains("Mobile")); assertFalse(ua.contains("Android"));
        assertEquals("UnknownBrowser", LensSession.desktopUserAgent("UnknownBrowser"));
    }

    @Test public void actualSearchRedirectLoadsInsideTheWorkerWithoutInteraction() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        String url = "https://www.google.com/search?udm=26&vsrid=real-upload-result";
        accept(result(url, "navigation_ready", null, 303));
        assertEquals(url, shadow.getLastLoadedUrl());
        assertEquals(url, updates.get(updates.size() - 1).getString("result_url"));
        assertFalse(updates.stream().anyMatch(update -> "needs_interaction".equals(update.getString("status"))));
        assertTrue(updates.stream().allMatch(update -> update.getParcelable("interaction") == null));
    }

    @Test public void refinementReusesTheUploadedImageWebViewAndOriginalSessionParameters() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        String base = "https://www.google.com/search?udm=26&vsrid=fixture&vsint=A%2BB%3D&p=image%2Ftoken";
        accept(result(base, "navigation_ready", null, 303));
        Method refine = LensSession.class.getDeclaredMethod("refine", String.class, String.class);
        refine.setAccessible(true); refine.invoke(session, "描述这张图片", "exact");
        String target = shadow.getLastLoadedUrl(); Uri parsed = Uri.parse(target);
        assertEquals("https://www.google.com", parsed.getScheme() + "://" + parsed.getHost());
        assertEquals("48", parsed.getQueryParameter("udm"));
        assertEquals("描述这张图片", parsed.getQueryParameter("q"));
        assertEquals("fixture", parsed.getQueryParameter("vsrid"));
        assertTrue(parsed.getEncodedQuery().contains("vsint=A%2BB%3D"));
        assertTrue(parsed.getEncodedQuery().contains("p=image%2Ftoken"));
        Field worker = LensSession.class.getDeclaredField("web"); worker.setAccessible(true); assertSame(web, worker.get(session));
        Field original = LensSession.class.getDeclaredField("baseResultUrl"); original.setAccessible(true); assertEquals(base, original.get(session));
    }

    @Test public void advancedModesShowTheLivePageWithoutParsingCardsOrDestroyingTheImageSession() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        java.io.File owned = java.io.File.createTempFile("lens-live-page-", ".png", RuntimeEnvironment.getApplication().getCacheDir());
        java.nio.file.Files.write(owned.toPath(), new byte[]{1}); setField("imageFile", owned);
        setField("baseResultUrl", "https://www.google.com/search?udm=26&p=image%2Bcontext");
        Method refine = LensSession.class.getDeclaredMethod("refine", String.class, String.class); refine.setAccessible(true);
        Handler main = (Handler) field("MAIN"); Runnable timeout = (Runnable) field("timeout");
        for (String[] mode : new String[][]{{"ai", "50"}, {"exact", "48"}}) {
            refine.invoke(session, "", mode[0]); String target = shadow.getLastLoadedUrl();
            assertEquals(mode[1], Uri.parse(target).getQueryParameter("udm")); assertTrue(main.hasCallbacks(timeout));
            shadow.getWebViewClient().onPageStarted(web, target, null);
            shadow.getWebViewClient().onPageFinished(web, target);
            Bundle ready = updates.get(updates.size() - 1);
            assertEquals("page_ready", ready.getString("status")); assertEquals(mode[0], ready.getString("search_mode"));
            assertEquals(target, ready.getString("result_url")); assertNotNull(ready.getParcelable("session_viewer"));
            assertEquals(LensPagePresentation.HIDE_CONTROLS, shadow.getLastEvaluatedJavascript()); assertNull(ready.getParcelableArrayList("items"));
            assertFalse(main.hasCallbacks(timeout)); assertFalse(main.hasCallbacks((Runnable) field("poll")));
            assertTrue((Boolean) field("sessionReady")); assertFalse((Boolean) field("refinementPending"));
            assertSame(web, field("web")); assertFalse(shadow.wasDestroyCalled());
            assertSame(owned, field("imageFile")); assertTrue(owned.exists());
        }
        assertFalse(updates.stream().anyMatch(update -> "empty".equals(update.getString("status"))
                || "query_error".equals(update.getString("status"))));
    }

    @Test public void advancedModePollAlsoBypassesTheVisualCardParser() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        Method inspect = LensSession.class.getDeclaredMethod("inspect"); inspect.setAccessible(true);
        for (String[] mode : new String[][]{{"ai", "50"}, {"exact", "48"}}) {
            String target = "https://www.google.com/search?udm=" + mode[1] + "&p=image-context";
            web.loadUrl(target); setField("currentMode", mode[0]); setField("resultUrl", target);
            inspect.invoke(session);
            assertEquals("page_ready", updates.get(updates.size() - 1).getString("status"));
            assertEquals(LensPagePresentation.HIDE_CONTROLS, shadow.getLastEvaluatedJavascript()); assertSame(web, field("web"));
        }
    }

    @Test public void confirmedEmptyResultClearsItsDeadlineAndPreservesTheOriginalPage() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        String target = "https://www.google.com/search?udm=26&p=image-context";
        web.loadUrl(target); shadow.getWebViewClient().onPageStarted(web, target, null);
        java.io.File owned = java.io.File.createTempFile("lens-empty-page-", ".png", RuntimeEnvironment.getApplication().getCacheDir());
        java.nio.file.Files.write(owned.toPath(), new byte[]{1}); setField("imageFile", owned);
        Handler main = (Handler) field("MAIN"); Runnable timeout = (Runnable) field("timeout");
        main.postDelayed(timeout, 120_000); assertTrue(main.hasCallbacks(timeout));
        Method inspect = LensSession.class.getDeclaredMethod("inspect"); inspect.setAccessible(true); inspect.invoke(session);
        assertNotNull(shadow.getLastEvaluatedJavascriptCallback());
        int generation = (Integer) field("generation");
        shadow.getLastEvaluatedJavascriptCallback().onReceiveValue("{\"request_id\":\"navigation-request\",\"page_generation\":"
                + generation + ",\"status\":\"empty\",\"message\":\"没有匹配结果\",\"items\":[]}");
        Bundle empty = updates.get(updates.size() - 1);
        assertEquals("empty", empty.getString("status")); assertNotNull(empty.getParcelable("session_viewer"));
        assertFalse(main.hasCallbacks(timeout)); assertFalse(main.hasCallbacks((Runnable) field("poll")));
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMinutes(3));
        assertEquals("empty", updates.get(updates.size() - 1).getString("status"));
        assertSame(web, field("web")); assertFalse(shadow.wasDestroyCalled()); assertFalse((Boolean) field("done"));
        assertSame(owned, field("imageFile")); assertTrue(owned.exists());
    }

    @Test public void lateBrowserDetachCannotRemoveTheNewViewOrItsUploadedImageWebView() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        FrameLayout currentRoot = new FrameLayout(RuntimeEnvironment.getApplication()); currentRoot.addView(web);
        setField("browserContainer", currentRoot); setField("browserViewId", "new-view");
        session.detachBrowser("old-view");
        assertEquals("new-view", field("browserViewId")); assertSame(currentRoot, field("browserContainer"));
        assertSame(web, currentRoot.getChildAt(0)); assertFalse(shadow.wasDestroyCalled());
        session.detachBrowser("new-view");
        assertNull(field("browserViewId")); assertNull(field("browserContainer")); assertEquals(0, currentRoot.getChildCount());
        assertSame(web, field("web")); assertFalse(shadow.wasDestroyCalled());
    }

    @Test public void leavingTheSessionViewerDoesNotCancelTheWorkerWebView() throws Exception {
        LensInteractionActivity activity = Robolectric.buildActivity(LensInteractionActivity.class).get();
        FrameLayout parent = new FrameLayout(RuntimeEnvironment.getApplication()); WebView web = openWeb();
        String url = "https://www.google.com/search?udm=26&vsrid=live";
        accept(result(url, "navigation_ready", null, 303));
        ShadowWebView shadow = Shadow.extract(web); assertTrue(session.attach(activity, parent));
        shadow.getWebViewClient().onPageStarted(web, url, null);
        session.detach(activity);
        assertEquals(0, parent.getChildCount()); assertFalse(shadow.wasDestroyCalled());
        Field active = LensSession.class.getDeclaredField("web"); active.setAccessible(true); assertSame(web, active.get(session));
        assertEquals(url, shadow.getLastLoadedUrl());
    }

    @Test public void arbitraryUploadNavigationIsRejected() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        accept(result("https://google.com.evil.example/search?udm=26", "navigation_ready", null, 303));
        assertNull(shadow.getLastLoadedUrl());
        assertEquals("network_error", updates.get(updates.size() - 1).getString("status"));
        assertNull(updates.get(updates.size() - 1).getString("result_url"));
    }

    @Test public void lateUploadCompletionCannotReviveACancelledSession() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        Method complete = LensSession.class.getDeclaredMethod("complete"); complete.setAccessible(true); complete.invoke(session);
        accept(result("https://www.google.com/search?udm=26", "navigation_ready", null, 303));
        assertNull(shadow.getLastLoadedUrl()); assertTrue(updates.isEmpty());
    }

    @Test public void callbackDeathDuringNavigationDoesNotDereferenceTheDestroyedWebView() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web); callbackFails = true;
        accept(result("https://www.google.com/search?udm=26", "navigation_ready", null, 303));
        assertNull(shadow.getLastLoadedUrl());
        Field field = LensSession.class.getDeclaredField("web"); field.setAccessible(true); assertNull(field.get(session));
    }

    @Test public void anActualGoogleChallengeResponseRemainsAvailableForDomClassification() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        Uri challenge = Uri.parse("https://www.google.com/sorry/index?continue=result");
        android.webkit.WebResourceRequest request = new android.webkit.WebResourceRequest() {
            public Uri getUrl() { return challenge; }
            public boolean isForMainFrame() { return true; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return false; }
            public String getMethod() { return "GET"; }
            public java.util.Map<String, String> getRequestHeaders() { return java.util.Map.of(); }
        };
        android.webkit.WebResourceResponse response = new android.webkit.WebResourceResponse("text/html", "UTF-8", 429,
                "Too Many Requests", java.util.Map.of(), new java.io.ByteArrayInputStream(new byte[0]));
        shadow.getWebViewClient().onReceivedHttpError(web, request, response);
        assertFalse(updates.stream().anyMatch(update -> "network_error".equals(update.getString("status"))));
        Field field = LensSession.class.getDeclaredField("web"); field.setAccessible(true); assertSame(web, field.get(session));
    }

    @Test public void challengeUrlIsLoadedBeforeClaimingThatInteractionIsNecessary() throws Exception {
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        String url = "https://www.google.com/sorry/index?continue=result";
        accept(result(url, "needs_interaction", "google_interaction", 303));
        assertEquals(url, shadow.getLastLoadedUrl());
        assertTrue(updates.stream().allMatch(update -> "loading".equals(update.getString("status"))));
        assertTrue(updates.stream().allMatch(update -> update.getParcelable("interaction") == null));
    }

    @Test public void originalPagesMustBeSupportedGoogleSearchUrls() {
        assertTrue(LensSession.isResultUrl("https://www.google.com/search?udm=26"));
        assertTrue(LensSession.isResultUrl("https://www.google.com/search?vsrid=result"));
        assertFalse(LensSession.isResultUrl("https://www.google.com/search?q=unrelated"));
        assertFalse(LensSession.isResultUrl("http://www.google.com/search?udm=26"));
        assertFalse(LensSession.isResultUrl("https://evil.example/search?udm=26"));
    }

    @Test public void creatorOptionsRemainAbsentBeforeAndroid15() {
        assertNull(LensSession.interactionCreatorOptions());
    }

    @Test public void interactionIntentStartsOnlyTheOwnActivityInANewTask() throws Exception {
        openWeb();
        Method needsInteraction = LensSession.class.getDeclaredMethod("needsInteraction", String.class);
        needsInteraction.setAccessible(true); needsInteraction.invoke(session, "需要网页确认");
        Bundle state = updates.stream().filter(update -> "needs_interaction".equals(update.getString("status")))
                .findFirst().orElseThrow();
        assertTrue(state.getBoolean("google_interaction_required"));
        PendingIntent pending = state.getParcelable("interaction"); assertNotNull(pending);
        ShadowPendingIntent shadow = Shadow.extract(pending);
        Intent intent = shadow.getSavedIntent();
        assertEquals(LensInteractionActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        assertTrue(pending.isImmutable());
    }

    @Test @Config(sdk = 35, manifest = Config.NONE)
    public void android15CreatorExplicitlyAllowsTheAuthenticatedPortalToSendItsIntent() {
        Bundle options = LensSession.interactionCreatorOptions(); assertNotNull(options);
        ActivityOptions parsed = ReflectionHelpers.callStaticMethod(ActivityOptions.class, "fromBundle",
                ReflectionHelpers.ClassParameter.from(Bundle.class, options));
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                parsed.getPendingIntentCreatorBackgroundActivityStartMode());
    }

    @Test public void activityAcknowledgementRequiresSuccessfulAttachmentOfTheExistingWebView() throws Exception {
        LensInteractionActivity activity = Robolectric.buildActivity(LensInteractionActivity.class).get();
        FrameLayout parent = new FrameLayout(RuntimeEnvironment.getApplication());
        assertFalse(session.attach(activity, parent));
        assertFalse(updates.stream().anyMatch(update -> "interaction_opened".equals(update.getString("status"))));
        WebView web = openWeb();
        assertTrue(session.attach(activity, parent));
        assertSame(web, parent.getChildAt(0));
        List<Bundle> acknowledgements = updates.stream()
                .filter(update -> "interaction_opened".equals(update.getString("status"))).toList();
        assertEquals(1, acknowledgements.size());
        assertEquals("navigation-request", acknowledgements.get(0).getString("request_id"));
        assertEquals("navigation-panel", acknowledgements.get(0).getString("panel_session_id"));
        session.detach(activity);
    }

    @Test public void returningFromSessionViewerDetachesButDoesNotDestroyTheUploadedImageWebView() throws Exception {
        LensInteractionActivity activity = Robolectric.buildActivity(LensInteractionActivity.class).get();
        FrameLayout parent = new FrameLayout(RuntimeEnvironment.getApplication());
        WebView web = openWeb(); ShadowWebView shadow = Shadow.extract(web);
        assertTrue(session.attach(activity, parent)); assertSame(web, parent.getChildAt(0));
        session.returnToSheet(activity);
        assertEquals(0, parent.getChildCount()); assertFalse(shadow.wasDestroyCalled());
        Field field = LensSession.class.getDeclaredField("web"); field.setAccessible(true); assertSame(web, field.get(session));
        assertEquals("interaction_closed", updates.get(updates.size() - 1).getString("status"));
    }

    @Test public void completionCancelsUploadAndDeletesTheOwnedImage() throws Exception {
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        LensUploadClient.CookieBridge cookies = new LensUploadClient.CookieBridge() {
            public String get(String url) { return null; }
            public void set(String url, String value) { }
            public void flush() { }
        };
        LensUploadClient uploader = new LensUploadClient(cookies, null, url -> { opened.incrementAndGet(); throw new java.io.IOException(); });
        java.io.File owned = java.io.File.createTempFile("lens-owned-", ".png", RuntimeEnvironment.getApplication().getCacheDir());
        java.io.File probe = java.io.File.createTempFile("lens-cancel-", ".png", RuntimeEnvironment.getApplication().getCacheDir());
        java.nio.file.Files.write(owned.toPath(), new byte[]{1}); java.nio.file.Files.write(probe.toPath(), new byte[]{1});
        setField("imageFile", owned); setField("uploader", uploader);
        Method complete = LensSession.class.getDeclaredMethod("complete"); complete.setAccessible(true); complete.invoke(session);
        assertFalse(owned.exists());
        assertEquals("cancelled", uploader.upload(probe, "image/png", 1, 1, java.util.Locale.US, () -> false).status);
        assertEquals(0, opened.get()); probe.delete();
    }

    private WebView openWeb() throws Exception {
        Method open = LensSession.class.getDeclaredMethod("openWebView", int.class, int.class);
        open.setAccessible(true); open.invoke(session, 374, 832);
        Field field = LensSession.class.getDeclaredField("web"); field.setAccessible(true);
        WebView web = (WebView) field.get(session); assertNotNull(web); return web;
    }
    private void setField(String name, Object value) throws Exception {
        Field field = LensSession.class.getDeclaredField(name); field.setAccessible(true); field.set(session, value);
    }
    private Object field(String name) throws Exception {
        Field field = LensSession.class.getDeclaredField(name); field.setAccessible(true); return field.get(session);
    }
    private void accept(LensUploadClient.Result result) throws Exception {
        Method method = LensSession.class.getDeclaredMethod("acceptUploadResult", LensUploadClient.Result.class);
        method.setAccessible(true); method.invoke(session, result);
    }
    private static LensUploadClient.Result result(String url, String status, String error, int code) throws Exception {
        Constructor<LensUploadClient.Result> constructor = LensUploadClient.Result.class.getDeclaredConstructor(String.class, String.class, String.class, int.class);
        constructor.setAccessible(true); return constructor.newInstance(url, status, error, code);
    }
}
