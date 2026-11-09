package io.github.yudigaga.taplusbridge.lens;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.widget.FrameLayout;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import io.github.yudigaga.taplusbridge.runtime.ModuleProcessLifecycle;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Automatic upload and extraction stay in the module process; foreground browsing is an explicit choice. */
final class LensSession {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String TAG = "TaplusBridge-Lens";
    private static LensSession active;
    private final ExecutorService worker = imageWorker();
    private final ModuleProcessLifecycle.Lease workLease = ModuleProcessLifecycle.acquire();
    private final Context context;
    private final Bundle request;
    private final ILensCallback callback;
    private final IBinder.DeathRecipient death = () -> MAIN.post(() -> finish("cancelled", "传送门连接已结束"));
    private final Runnable timeout = () -> finish("timeout", "Google Lens 请求超时，请检查网络后重试");
    private final Runnable poll = this::inspect;
    private volatile boolean done;
    private ParcelFileDescriptor incoming;
    private File imageFile;
    private WebView web;
    private LensUploadClient uploader;
    private LensInteractionActivity interaction;
    private PendingIntent interactionIntent, sessionViewerIntent;
    private boolean waitingForUser, pollPending, sessionReady, refinementPending;
    private int generation;
    private int pageHttpStatus;
    private long navigationStarted;
    private String resultUrl, baseResultUrl, lastDiagnostic;
    private String currentMode = "all", browserViewId;
    private SurfaceControlViewHost browserHost;
    private FrameLayout browserContainer;

    private static ExecutorService imageWorker() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 3, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "TaplusBridge-Lens-image"); thread.setDaemon(true); return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private LensSession(Context context, ParcelFileDescriptor image, Bundle request, ILensCallback callback) {
        this.context = context.getApplicationContext(); this.incoming = image;
        this.request = request; this.callback = callback;
    }

    static void start(Context context, ParcelFileDescriptor image, Bundle request, ILensCallback callback) {
        cancelAll();
        LensSession session = new LensSession(context, image, request, callback);
        active = session;
        try { callback.asBinder().linkToDeath(session.death, 0); }
        catch (RemoteException dead) { session.finish("cancelled", "传送门连接已结束"); return; }
        session.send("loading", "正在准备当前图片");
        if (session.done) return;
        MAIN.postDelayed(session.timeout, 120_000);
        session.worker.execute(session::prepareImage);
    }

    static void cancel(String requestId) {
        if (active != null && active.request.getString("request_id").equals(requestId))
            active.finish("cancelled", "已取消识图");
    }
    static void refine(String requestId, String query, String mode) {
        LensSession session = active;
        if (session != null && !session.done && session.request.getString("request_id").equals(requestId))
            session.refine(query, mode);
    }
    static void cancelAll() { if (active != null) active.finish("cancelled", "本次识图已结束"); }
    static LensSession current(String id) {
        return active != null && !active.done && active.request.getString("request_id").equals(id) ? active : null;
    }

    private void prepareImage() {
        File prepared = null;
        try {
            Bundle enabled = context.getContentResolver().call(
                    Uri.parse("content://" + LensContract.PACKAGE + ".bridge"), "lens_config", null, null);
            if (enabled == null || !enabled.getBoolean("enabled")) throw new IOException("lens_disabled");
            File directory = new File(context.getCacheDir(), "lens");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("cache_unavailable");
            File[] stale = directory.listFiles();
            if (stale != null) for (File file : stale) if (file.isFile()) file.delete();
            String mime = request.getString("mime_type");
            prepared = File.createTempFile("selected-", "image/png".equals(mime) ? ".png"
                    : "image/webp".equals(mime) ? ".webp" : ".jpg", directory);
            BitmapFactory.Options bounds = LensImageStore.copyVerified(incoming, prepared, request, () -> done);
            android.util.Log.i(TAG, "image_ready width=" + bounds.outWidth + " height=" + bounds.outHeight
                    + " bytes=" + prepared.length());
            File ready = prepared;
            MAIN.post(() -> {
                if (done) { ready.delete(); return; }
                incoming = null; imageFile = ready;
                openWebView(bounds.outWidth, bounds.outHeight);
                if (!done) beginUpload(ready, mime, bounds.outWidth, bounds.outHeight);
            });
        } catch (Exception failure) {
            if (prepared != null) prepared.delete();
            String reason = failure.getMessage();
            android.util.Log.w(TAG, "image_prepare_failed type=" + failure.getClass().getSimpleName());
            MAIN.post(() -> finish("parse_error", "lens_disabled".equals(reason)
                    ? "Google Lens 识图已关闭，请在模块设置中开启" : "无法读取当前图片，请重新选择图片后重试"));
        } finally { LensService.close(incoming); }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openWebView(int width, int height) {
        if (done) return;
        try {
            WebView.setWebContentsDebuggingEnabled(false);
            web = new WebView(context);
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            web.measure(android.view.View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, android.view.View.MeasureSpec.EXACTLY));
            web.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
            settings.setUserAgentString(desktopUserAgent(settings.getUserAgentString()));
            settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSupportMultipleWindows(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
            web.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> result, FileChooserParams params) {
                    result.onReceiveValue(null); return true;
                }
            });
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                    generation++; MAIN.removeCallbacks(poll); pollPending = false;
                    pageHttpStatus = 0;
                    navigationStarted = SystemClock.elapsedRealtime();
                    if (isResultUrl(url)) {
                        resultUrl = url;
                        if (baseResultUrl == null) baseResultUrl = url;
                        String udm = Uri.parse(url).getQueryParameter("udm");
                        currentMode = "50".equals(udm) ? "ai" : "48".equals(udm) ? "exact"
                                : "44".equals(udm) ? "visual" : "all";
                    }
                    String kind = pageKind(url);
                    android.util.Log.i(TAG, "page_started generation=" + generation + " page=" + kind);
                    if ("google_results".equals(kind) && (!sessionReady || refinementPending))
                        send("loading", "Google Lens 正在识图…");
                }
                @Override public void onPageFinished(WebView view, String url) {
                    android.util.Log.i(TAG, "page_finished generation=" + generation + " page=" + pageKind(url));
                    if (!done) {
                        CookieManager.getInstance().flush();
                        if (trustedPage(url)) {
                            if (isResultUrl(url) && usesLivePage()) prepareLivePage();
                            else schedulePoll();
                        }
                    }
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!request.isForMainFrame()) return false;
                    String address = request.getUrl().toString();
                    if (trustedPage(address) || LensPageParser.http(address) != null) return false;
                    finish("parse_error", "当前页面仅支持 HTTP/HTTPS 网页"); return true;
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) android.util.Log.w(TAG, "page_network_error generation=" + generation
                            + " code=" + error.getErrorCode());
                    if (!request.isForMainFrame()) return;
                    if (trustedPage(request.getUrl().toString())) finish("network_error", "无法连接 Google Lens，请检查网络后重试");
                    else if (sessionReady) emit(LensContract.update(LensSession.this.request, generation, "parse_error", "原网页加载失败，可返回识图结果"));
                }
                @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                    if (!request.isForMainFrame()) return;
                    pageHttpStatus = response.getStatusCode();
                    android.util.Log.w(TAG, "page_http_error generation=" + generation + " page="
                            + pageKind(request.getUrl().toString()) + " status=" + pageHttpStatus);
                    if (!trustedPage(request.getUrl().toString())) {
                        if (sessionReady) emit(LensContract.update(LensSession.this.request, generation, "parse_error", "原网页返回错误：" + pageHttpStatus));
                        return;
                    }
                    // Read a possible challenge document before deciding; the status itself does not prove login.
                    String kind = pageKind(request.getUrl().toString());
                    if (pageHttpStatus == 403 || pageHttpStatus == 429 || "google_challenge".equals(kind)
                            || "google_account".equals(kind) || "google_consent".equals(kind)) schedulePoll();
                    else if (pageHttpStatus >= 400) finish("network_error", "Google Lens 返回错误：" + pageHttpStatus);
                }
                @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.cancel();
                    if (trustedPage(error.getUrl())) finish("network_error", "Google Lens 的安全连接验证失败");
                    else if (sessionReady) emit(LensContract.update(request, generation, "parse_error", "原网页的安全连接验证失败"));
                }
            });
        } catch (RuntimeException unavailable) {
            finish("parse_error", "无法创建网页会话，请启用或更新 Android System WebView");
        }
    }

    static String desktopUserAgent(String original) {
        Matcher chrome = Pattern.compile("Chrome/([0-9.]+)").matcher(original == null ? "" : original);
        if (!chrome.find()) return original;
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + chrome.group(1) + " Safari/537.36";
    }

    private void beginUpload(File image, String mime, int width, int height) {
        CookieManager cookies = CookieManager.getInstance();
        uploader = new LensUploadClient(new LensUploadClient.CookieBridge() {
            public String get(String url) throws IOException {
                if (done) throw new IOException("cancelled");
                return cookies.getCookie(url);
            }
            public void set(String url, String value) throws IOException {
                CountDownLatch applied = new CountDownLatch(1);
                AtomicBoolean accepted = new AtomicBoolean();
                MAIN.post(() -> {
                    if (done) { applied.countDown(); return; }
                    cookies.setCookie(url, value, success -> { accepted.set(Boolean.TRUE.equals(success)); applied.countDown(); });
                });
                try {
                    for (int i = 0; i < 50 && !done; i++) {
                        if (applied.await(100, TimeUnit.MILLISECONDS)) {
                            if (!accepted.get()) throw new IOException("cookie_rejected");
                            return;
                        }
                    }
                    throw new IOException(done ? "cancelled" : "cookie_timeout");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("cancelled", interrupted); }
            }
            public void flush() { cookies.flush(); }
        }, web.getSettings().getUserAgentString());
        LensUploadClient submitted = uploader;
        send("loading", "正在自动上传当前图片并搜索…");
        if (done) return;
        worker.execute(() -> {
            android.util.Log.i(TAG, "upload_started");
            LensUploadClient.Result result = submitted.upload(image, mime, width, height, Locale.getDefault(), () -> done);
            android.util.Log.i(TAG, "upload_state=" + result.status + " http=" + result.httpStatus
                    + " reason=" + (result.error == null ? "none" : result.error));
            MAIN.post(() -> acceptUploadResult(result));
        });
    }

    private void acceptUploadResult(LensUploadClient.Result result) {
        if (done) return;
        if (("navigation_ready".equals(result.status) || "page_ready".equals(result.status)
                || "needs_interaction".equals(result.status)) && trustedPage(result.url)) {
            if (isResultUrl(result.url)) {
                resultUrl = result.url;
                if (baseResultUrl == null) baseResultUrl = result.url;
            }
            send("loading", "正在读取 Google Lens 结果…");
            if (!done && web != null) web.loadUrl(result.url);
        } else if ("cancelled".equals(result.status)) finish("cancelled", "本次识图已取消");
        else if ("timeout".equals(result.status)) finish("timeout", "Google Lens 上传超时，请检查网络后重试");
        else finish("network_error", "图片自动上传未完成，请检查网络后重试（" + result.httpStatus + "）");
    }

    static boolean trustedPage(String raw) {
        if (raw == null) return false;
        Uri uri = Uri.parse(raw); String host = uri.getHost();
        return "https".equals(uri.getScheme()) && host != null
                && (host.equals("google.com") || host.endsWith(".google.com"))
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }
    static boolean isResultUrl(String raw) {
        return LensPageParser.isResultUrl(raw);
    }

    private void refine(String query, String mode) {
        if (done || web == null || baseResultUrl == null) return;
        String target = LensSearchUrl.build(baseResultUrl, query, mode);
        if (target == null) { send("query_error", "当前图片的搜索会话暂不可用"); return; }
        currentMode = mode;
        MAIN.removeCallbacks(poll); pollPending = false;
        generation++; pageHttpStatus = 0; navigationStarted = SystemClock.elapsedRealtime();
        refinementPending = true;
        resultUrl = target; lastDiagnostic = null;
        MAIN.removeCallbacks(timeout); MAIN.postDelayed(timeout, 120_000);
        Uri address = Uri.parse(target);
        android.util.Log.i(TAG, "refine mode=" + mode + " query_chars=" + query.length()
                + " page=" + pageKind(target) + " image_context="
                + (address.getQueryParameter("vsrid") != null || address.getQueryParameter("p") != null));
        send("loading", "正在同一图片会话中搜索…");
        if (!done && web != null) web.loadUrl(target);
    }

    private boolean usesLivePage() { return "ai".equals(currentMode) || "exact".equals(currentMode); }

    private void prepareLivePage() {
        if (web != null && isResultUrl(web.getUrl()) && usesLivePage())
            web.evaluateJavascript(LensPagePresentation.HIDE_CONTROLS, null);
        pageReady();
    }

    private void pageReady() {
        MAIN.removeCallbacks(timeout); MAIN.removeCallbacks(poll); pollPending = false;
        sessionReady = true; refinementPending = false;
        send("page_ready", "Google 当前图片搜索页已就绪");
    }
    private static String pageKind(String url) {
        if (!trustedPage(url)) return "unsupported_origin";
        Uri uri = Uri.parse(url);
        if (isResultUrl(url)) return "google_results";
        if (uri.getPath() != null && uri.getPath().startsWith("/sorry/")) return "google_challenge";
        if ("accounts.google.com".equals(uri.getHost())) return "google_account";
        if ("consent.google.com".equals(uri.getHost())) return "google_consent";
        return "google_page";
    }

    private void schedulePoll() {
        if (done || pollPending || (waitingForUser && interaction == null)) return;
        pollPending = true; MAIN.postDelayed(poll, 750);
    }

    private void reportParseError(String message) {
        if (!sessionReady && web != null && isResultUrl(web.getUrl())) sessionReady = true;
        if (!sessionReady) { finish("parse_error", message); return; }
        refinementPending = false;
        MAIN.removeCallbacks(timeout); MAIN.removeCallbacks(poll); pollPending = false;
        Bundle update = LensContract.update(request, generation, "query_error", message);
        update.putString("parser_version", LensPageParser.VERSION); emit(update);
    }

    private void inspect() {
        pollPending = false;
        if (done || web == null || (waitingForUser && interaction == null)) return;
        String url = web.getUrl();
        if (usesLivePage() && isResultUrl(url)) { prepareLivePage(); return; }
        if (!trustedPage(url)) {
            if (SystemClock.elapsedRealtime() - navigationStarted < 20_000) schedulePoll();
            else reportParseError("结果页面地址暂不受支持，可在传送门内打开原网页查看");
            return;
        }
        int expected = generation;
        try {
            web.evaluateJavascript(LensPageParser.script(context, request.getString("request_id"), expected), raw -> {
                if (done || expected != generation) return;
                try {
                    Bundle parsed = LensPageParser.parse(raw);
                    String diagnostic = LensPageParser.diagnostic(parsed);
                    if (!diagnostic.equals(lastDiagnostic)) {
                        lastDiagnostic = diagnostic; android.util.Log.i(TAG, "parse " + diagnostic);
                    }
                    if (parsed.getString("request_id") == null || !parsed.containsKey("page_generation")) {
                        reportParseError("Google 页面返回了无法识别的数据，可在传送门内打开原网页查看"); return;
                    }
                    if (!LensContract.matchesPage(request, expected, parsed)) { schedulePoll(); return; }
                    String status = parsed.getString("status", "loading");
                    if ("results_ready".equals(status)) {
                        sessionReady = true; refinementPending = false;
                        Bundle update = LensContract.update(request, generation, status, "Google Lens 识图完成");
                        update.putString("parser_version", parsed.getString("parser_version"));
                        update.putParcelableArrayList("items", parsed.getParcelableArrayList("items"));
                        MAIN.removeCallbacks(timeout); MAIN.removeCallbacks(poll); pollPending = false;
                        emit(update);
                    } else if ("needs_interaction".equals(status)) needsInteraction(parsed.getString("message", "Google 需要网页交互"));
                    else if ("empty".equals(status)) {
                        sessionReady = true; refinementPending = false;
                        MAIN.removeCallbacks(timeout); MAIN.removeCallbacks(poll); pollPending = false;
                        emit(LensContract.update(request, generation, "empty", parsed.getString("message", "Google Lens 未找到匹配结果")));
                    }
                    else if (SystemClock.elapsedRealtime() - navigationStarted < 20_000) schedulePoll();
                    else if (pageHttpStatus >= 400) finish("network_error", "Google Lens 返回错误：" + pageHttpStatus);
                    else reportParseError(parsed.getString("message", "当前 Google 页面暂时无法显示内容，可切换筛选方式重试"));
                } catch (RuntimeException malformed) {
                    android.util.Log.w(TAG, "parse_exception type=" + malformed.getClass().getSimpleName());
                    reportParseError("当前 Google 页面暂时无法显示内容，可切换筛选方式重试");
                }
            });
        } catch (IOException | RuntimeException parserUnavailable) { reportParseError("识图页面解析器不可用"); }
    }

    private void needsInteraction(String message) {
        if (done) return;
        MAIN.removeCallbacks(poll); pollPending = false;
        if (!waitingForUser) {
            waitingForUser = true;
            MAIN.removeCallbacks(timeout); MAIN.postDelayed(timeout, 5 * 60_000);
            Intent intent = new Intent(context, LensInteractionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setData(Uri.parse("taplus-lens://session/" + Uri.encode(request.getString("request_id"))))
                    .putExtra("request_id", request.getString("request_id"));
            interactionIntent = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, interactionCreatorOptions());
            Bundle update = LensContract.update(request, generation, "needs_interaction", message);
            update.putBoolean("google_interaction_required", true);
            update.putParcelable("interaction", interactionIntent); emit(update);
        }
        if (interaction != null) schedulePoll();
    }

    private PendingIntent sessionViewerIntent() {
        if (sessionViewerIntent != null) return sessionViewerIntent;
        Intent intent = new Intent(context, LensInteractionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(Uri.parse("taplus-lens://session/view/" + Uri.encode(request.getString("request_id"))))
                .putExtra("request_id", request.getString("request_id"))
                .putExtra("return_to_sheet", true);
        sessionViewerIntent = PendingIntent.getActivity(context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, interactionCreatorOptions());
        return sessionViewerIntent;
    }

    boolean attach(LensInteractionActivity activity, ViewGroup parent) {
        if (done || web == null) return false;
        releaseBrowser();
        web.setTranslationY(0f);
        web.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        if (web.getParent() instanceof ViewGroup old) old.removeView(web);
        interaction = activity;
        parent.addView(web, new ViewGroup.LayoutParams(-1, -1));
        web.evaluateJavascript(LensPagePresentation.RESTORE_CONTROLS, null);
        web.onResume(); send(activity.isSessionViewer() ? "session_viewer_opened" : "interaction_opened", "当前网页会话已打开");
        schedulePoll(); return true;
    }
    static Bundle interactionCreatorOptions() {
        if (android.os.Build.VERSION.SDK_INT < 35) return null;
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setPendingIntentCreatorBackgroundActivityStartMode(android.os.Build.VERSION.SDK_INT >= 36
                ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        return options.toBundle();
    }
    void detach(LensInteractionActivity activity) {
        if (interaction != activity) return;
        if (web != null && web.getParent() instanceof ViewGroup old) old.removeView(web);
        if (web != null) web.onPause();
        interaction = null;
    }
    void returnToSheet(LensInteractionActivity activity) {
        if (interaction != activity || done) return;
        detach(activity); send("interaction_closed", "已返回识图结果");
    }
    boolean goBackInViewer(LensInteractionActivity activity) {
        if (interaction != activity || web == null || !web.canGoBack()) return false;
        web.goBack(); return true;
    }

    void attachBrowser(Bundle result, String viewId, IBinder hostToken, int displayId, int width, int height) {
        if (done || web == null || interaction != null || android.os.Build.VERSION.SDK_INT < 30) return;
        DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = displays == null ? null : displays.getDisplay(displayId);
        if (display == null) return;
        releaseBrowser();
        try {
            if (web.getParent() instanceof ViewGroup old) old.removeView(web);
            browserHost = new SurfaceControlViewHost(context, display, hostToken);
            browserViewId = viewId; browserContainer = new FrameLayout(context);
            browserContainer.setClipChildren(true); browserContainer.setClipToPadding(true);
            web.setTranslationY(0f);
            browserContainer.addView(web, new FrameLayout.LayoutParams(-1, height, android.view.Gravity.TOP));
            if (usesLivePage()) web.evaluateJavascript(LensPagePresentation.HIDE_CONTROLS, null);
            browserHost.setView(browserContainer, width, height); web.onResume();
            result.putParcelable("surface_package", browserHost.getSurfacePackage());
            android.util.Log.i(TAG, "browser_attached generation=" + generation);
        } catch (RuntimeException | LinkageError unavailable) {
            releaseBrowser(); android.util.Log.w(TAG, "browser_unavailable type=" + unavailable.getClass().getSimpleName());
        }
    }

    void resizeBrowser(String viewId, int width, int height) {
        if (android.os.Build.VERSION.SDK_INT >= 30 && viewId.equals(browserViewId) && browserHost != null) {
            if (web != null && browserContainer != null) {
                ViewGroup.LayoutParams params = web.getLayoutParams();
                if (params != null) {
                    params.height = height;
                    web.setLayoutParams(params);
                }
            }
            browserHost.relayout(width, height);
        }
    }

    void detachBrowser(String viewId) {
        if (viewId.equals(browserViewId)) releaseBrowser();
    }

    private void releaseBrowser() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        if (browserContainer != null && web != null && web.getParent() == browserContainer) browserContainer.removeView(web);
        if (browserHost != null) browserHost.release();
        browserHost = null; browserContainer = null; browserViewId = null;
    }
    void userCancel() { finish("cancelled", "已取消网页识图"); }
    private void send(String status, String message) { emit(LensContract.update(request, generation, status, message)); }
    private void emit(Bundle update) {
        if (done) return;
        if (resultUrl != null) update.putString("result_url", resultUrl);
        update.putString("search_mode", currentMode);
        java.util.ArrayList<Bundle> items = update.getParcelableArrayList("items");
        android.util.Log.i(TAG, "status=" + update.getString("status") + " generation=" + generation
                + " parser=" + update.getString("parser_version", "none") + " items=" + (items == null ? 0 : items.size()));
        if (sessionViewerIntent == null && sessionReady && resultUrl != null) {
            try { sessionViewerIntent(); } catch (RuntimeException unavailable) { }
        }
        if (sessionViewerIntent != null) update.putParcelable("session_viewer", sessionViewerIntent);
        try { callback.onUpdate(update); }
        catch (RemoteException | RuntimeException disconnected) { complete(); }
    }
    private void finish(String status, String message) {
        if (done) return;
        Bundle update = LensContract.update(request, generation, status, message);
        if ("parse_error".equals(status)) update.putString("parser_version", LensPageParser.VERSION);
        emit(update); complete();
    }
    private void complete() {
        if (done) return;
        done = true; if (active == this) active = null;
        worker.shutdownNow();
        try {
            MAIN.removeCallbacks(timeout); MAIN.removeCallbacks(poll);
            if (uploader != null) { uploader.cancel(); uploader = null; }
            try { callback.asBinder().unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException ignored) { }
            if (interactionIntent != null) { interactionIntent.cancel(); interactionIntent = null; }
            if (sessionViewerIntent != null) { sessionViewerIntent.cancel(); sessionViewerIntent = null; }
            LensService.close(incoming); incoming = null;
            releaseBrowser();
            if (web != null) {
                if (web.getParent() instanceof ViewGroup old) old.removeView(web);
                web.stopLoading(); web.destroy(); web = null;
            }
            if (imageFile != null) imageFile.delete(); imageFile = null;
            if (interaction != null) { LensInteractionActivity activity = interaction; interaction = null; activity.finish(); }
            android.util.Log.i(TAG, "cleanup generation=" + generation);
        } finally { workLease.close(); }
    }
}
