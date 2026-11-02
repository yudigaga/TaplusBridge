package io.github.yudigaga.taplusbridge.lens;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
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
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Automatic upload and extraction stay in the module process; foreground browsing is an explicit choice. */
final class LensSession {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String TAG = "TaplusBridge-Lens";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TaplusBridge-Lens-image"); t.setDaemon(true); return t;
    });
    private static LensSession active;
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
    private PendingIntent interactionIntent;
    private boolean waitingForUser, pollPending;
    private int generation;
    private int pageHttpStatus;
    private long navigationStarted;
    private String resultUrl, lastDiagnostic;

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
        IO.execute(session::prepareImage);
    }

    static void cancel(String requestId) {
        if (active != null && active.request.getString("request_id").equals(requestId))
            active.finish("cancelled", "已取消识图");
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
                    if (isResultUrl(url)) resultUrl = url;
                    android.util.Log.i(TAG, "page_started generation=" + generation + " page=" + pageKind(url));
                    send("loading", "Google Lens 正在识图…");
                }
                @Override public void onPageFinished(WebView view, String url) {
                    if (!done) { CookieManager.getInstance().flush(); schedulePoll(); }
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!request.isForMainFrame()) return false;
                    if (trustedPage(request.getUrl().toString())) return false;
                    finish("parse_error", "识图页面跳转到不受支持的地址，请使用右上角原网页查看"); return true;
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) finish("network_error", "无法连接 Google Lens，请检查网络后重试");
                }
                @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                    if (!request.isForMainFrame()) return;
                    pageHttpStatus = response.getStatusCode();
                    // Read a possible challenge document before deciding; the status itself does not prove login.
                    String kind = pageKind(request.getUrl().toString());
                    if (pageHttpStatus == 403 || pageHttpStatus == 429 || "google_challenge".equals(kind)
                            || "google_account".equals(kind) || "google_consent".equals(kind)) schedulePoll();
                    else if (pageHttpStatus >= 400) finish("network_error", "Google Lens 返回错误：" + pageHttpStatus);
                }
                @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.cancel(); finish("network_error", "Google Lens 的安全连接验证失败");
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
        IO.execute(() -> {
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
            if (isResultUrl(result.url)) resultUrl = result.url;
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
    private void inspect() {
        pollPending = false;
        if (done || web == null || (waitingForUser && interaction == null)) return;
        String url = web.getUrl();
        if (!trustedPage(url)) {
            if (SystemClock.elapsedRealtime() - navigationStarted < 20_000) schedulePoll();
            else finish("parse_error", "结果页面地址暂不受支持，可使用右上角原网页查看");
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
                        finish("parse_error", "Google 页面返回了无法识别的数据，可使用右上角原网页查看"); return;
                    }
                    if (!LensContract.matchesPage(request, expected, parsed)) { schedulePoll(); return; }
                    String status = parsed.getString("status", "loading");
                    if ("results_ready".equals(status)) {
                        Bundle update = LensContract.update(request, generation, status, "Google Lens 识图完成");
                        update.putString("parser_version", parsed.getString("parser_version"));
                        update.putParcelableArrayList("items", parsed.getParcelableArrayList("items"));
                        emit(update); complete();
                    } else if ("needs_interaction".equals(status)) needsInteraction(parsed.getString("message", "Google 需要网页交互"));
                    else if ("empty".equals(status)) finish("empty", parsed.getString("message", "Google Lens 未找到匹配结果"));
                    else if (SystemClock.elapsedRealtime() - navigationStarted < 20_000) schedulePoll();
                    else if (pageHttpStatus >= 400) finish("network_error", "Google Lens 返回错误：" + pageHttpStatus);
                    else finish("parse_error", parsed.getString("message", "当前页面无法提取结果，可使用右上角原网页查看"));
                } catch (RuntimeException malformed) {
                    android.util.Log.w(TAG, "parse_exception type=" + malformed.getClass().getSimpleName());
                    finish("parse_error", "无法读取 Google 页面结果，可使用右上角原网页查看");
                }
            });
        } catch (IOException | RuntimeException parserUnavailable) { finish("parse_error", "识图页面解析器不可用"); }
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

    boolean attach(LensInteractionActivity activity, ViewGroup parent) {
        if (done || web == null) return false;
        if (web.getParent() instanceof ViewGroup old) old.removeView(web);
        interaction = activity;
        parent.addView(web, new ViewGroup.LayoutParams(-1, -1));
        web.onResume(); send("interaction_opened", "当前网页会话已打开");
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
        interaction = null;
    }
    void userCancel() { finish("cancelled", "已取消网页识图"); }
    private void send(String status, String message) { emit(LensContract.update(request, generation, status, message)); }
    private void emit(Bundle update) {
        if (done) return;
        if (resultUrl != null) update.putString("result_url", resultUrl);
        java.util.ArrayList<Bundle> items = update.getParcelableArrayList("items");
        android.util.Log.i(TAG, "status=" + update.getString("status") + " generation=" + generation
                + " parser=" + update.getString("parser_version", "none") + " items=" + (items == null ? 0 : items.size()));
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
        MAIN.removeCallbacks(timeout); MAIN.removeCallbacks(poll);
        if (uploader != null) uploader.cancel();
        try { callback.asBinder().unlinkToDeath(death, 0); } catch (java.util.NoSuchElementException ignored) { }
        if (interactionIntent != null) { interactionIntent.cancel(); interactionIntent = null; }
        LensService.close(incoming); incoming = null;
        if (web != null) {
            if (web.getParent() instanceof ViewGroup old) old.removeView(web);
            web.stopLoading(); web.destroy(); web = null;
        }
        if (imageFile != null) imageFile.delete(); imageFile = null;
        if (interaction != null) { LensInteractionActivity activity = interaction; interaction = null; activity.finish(); }
        android.util.Log.i(TAG, "cleanup generation=" + generation);
    }
}
