package io.github.yudigaga.taplusbridge.lens;

import android.app.PendingIntent;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.http.SslError;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.SurfaceControlViewHost;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.ref.WeakReference;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Separate result window; the original image card never receives injected result views. */
public final class PortalLensPanel implements View.OnAttachStateChangeListener {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final ExecutorService SIGNALS = Executors.newSingleThreadExecutor();
    private static volatile Interaction interactionLease;
    private static volatile WeakReference<PortalLensPanel> visibleSheet = new WeakReference<>(null);
    private final View host;
    private final TextView action;
    private final RelativeLayout group;
    private final Supplier<Bitmap> selectedImage;
    private final Supplier<String> sourcePackage;
    private final Context context;
    private final String panelId = UUID.randomUUID().toString();
    private final CharSequence originalLabel;
    private final AtomicInteger epoch = new AtomicInteger();
    private boolean enabled, querying;
    private Bitmap lastImage;
    private int lastGeneration;
    private ScrollView overlay;
    private LinearLayout contents;
    private LinearLayout sourceRoot;
    private TextView sourceError;
    private WebView sourceWebView;
    private String displayedResultUrl;
    private Bitmap resultUrlImage;
    private int resultUrlGeneration, resultUrlEpoch;
    private volatile Job job;
    private LensResultSheet resultSheet;
    private FrameLayout resultArea;
    private View suppressedPortalRoot;
    private int portalRootVisibility;
    private Bitmap sheetImage;
    private int sheetImageGeneration, sheetEpoch;
    private EditText searchInput;
    private Button searchButton;
    private final ArrayList<TextView> modeButtons = new ArrayList<>();
    private String searchMode = "all", queryBaseUrl;
    private boolean browsingQuery;
    private LensEmbeddedBrowser embeddedBrowser;
    private String embeddedViewId;

    public PortalLensPanel(View host, TextView action, RelativeLayout group, Supplier<Bitmap> selectedImage,
                           Supplier<String> sourcePackage) {
        this.host = host; this.action = action; this.group = group; this.selectedImage = selectedImage;
        this.sourcePackage = sourcePackage;
        context = host.getContext().getApplicationContext(); originalLabel = action.getText();
        host.addOnAttachStateChangeListener(this);
    }

    public boolean enabled() { return enabled; }

    public void refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(this::refresh); return; }
        Bitmap image = selectedImage.get();
        int generation = image == null || image.isRecycled() ? 0 : image.getGenerationId();
        if (image != lastImage || generation != lastGeneration) invalidate();
        lastImage = image; lastGeneration = generation;
        if (enabled) action.setText("识图");
        if (querying) return;
        querying = true;
        IO.execute(() -> {
            boolean allowed = false;
            try {
                Bundle response = context.getContentResolver().call(Uri.parse("content://" + BridgeContract.AUTHORITY),
                        "lens_config", null, null);
                allowed = response != null && response.getBoolean("enabled", false);
            } catch (RuntimeException unavailable) { /* Preserve the host action when the module is unavailable. */ }
            boolean result = allowed;
            MAIN.post(() -> {
                querying = false; enabled = result;
                action.setText(result ? "识图" : originalLabel);
                if (!result) invalidate();
            });
        });
    }

    public void search() {
        if (!enabled) return;
        if (job != null && current(job)) return;
        invalidate();
        Bitmap image = selectedImage.get();
        if (image == null || image.isRecycled()) { showMessage("选中图片已失效，请重新长按图片", false); return; }
        if (!ensureResultSheet()) return;
        Job next = new Job(image);
        job = next;
        showMessage("正在向 Google Lens 上传选中图片…", false);
        armTimeout(next, 120_000);
        IO.execute(() -> prepare(next));
    }

    private void armTimeout(Job next, long duration) {
        MAIN.removeCallbacks(next.watchdog);
        MAIN.postDelayed(next.watchdog, duration);
    }

    public void invalidate() {
        int cancelledEpoch = epoch.incrementAndGet();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> invalidateOnMain(cancelledEpoch)); return;
        }
        invalidateOnMain(cancelledEpoch);
    }

    private void invalidateOnMain(int cancelledEpoch) {
        if (epoch.get() != cancelledEpoch) return;
        closeSourcePage();
        if (job != null) stop(job);
        displayedResultUrl = null; resultUrlImage = null;
        updateOriginalPageButton();
        if (overlay != null) overlay.setVisibility(View.GONE);
        if (contents != null) contents.removeAllViews();
        closeResultSheet();
    }

    private void prepare(Job next) {
        File file = null;
        Bitmap scaled = null;
        try {
            if (next.cancelled) return;
            Bitmap image = next.image;
            int width = image.getWidth(), height = image.getHeight();
            float scale = Math.min(1f, 2048f / Math.max(width, height));
            if (scale < 1f) scaled = Bitmap.createScaledBitmap(image,
                    Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), true);
            Bitmap upload = scaled == null ? image : scaled;
            file = File.createTempFile("taplus-lens-", ".png", context.getCacheDir());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestOutputStream out = new DigestOutputStream(new FileOutputStream(file), digest)) {
                if (!upload.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("encode");
            }
            if (next.cancelled) return;
            if (file.length() > 20L * 1024 * 1024) throw new IOException("image_size");
            StringBuilder key = new StringBuilder();
            for (byte value : digest.digest()) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            next.key = key.toString();
            next.hold(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY));
            MAIN.post(() -> bind(next));
        } catch (Exception failure) {
            MAIN.post(() -> fail(next, "无法读取当前图片，请重新长按后重试"));
        } finally {
            if (scaled != null && scaled != next.image) scaled.recycle();
            if (file != null) file.delete();
        }
    }

    private void bind(Job next) {
        if (!current(next)) { next.closeImage(); return; }
        Intent intent = new Intent().setComponent(new ComponentName(BridgeContract.APP_PACKAGE,
                BridgeContract.APP_PACKAGE + ".lens.LensService"));
        try {
            next.bound = context.bindService(intent, next, Context.BIND_AUTO_CREATE);
            if (!next.bound) fail(next, "无法启动识图服务，请确认模块应用未被冻结");
        } catch (RuntimeException unavailable) { fail(next, "无法连接识图服务，请打开模块应用后重试"); }
    }

    private boolean current(Job candidate) {
        return job == candidate && !candidate.cancelled && displayedImage(candidate);
    }

    private boolean displayedImage(Job candidate) {
        return epoch.get() == candidate.epochValue && host.isAttachedToWindow()
                && selectedImage.get() == candidate.image && !candidate.image.isRecycled()
                && candidate.image.getGenerationId() == candidate.generation;
    }

    static boolean matches(Bundle update, String request, String panel, String image) {
        return update != null && image != null && request.equals(update.getString("request_id"))
                && panel.equals(update.getString("panel_session_id")) && image.equals(update.getString("image_key"));
    }

    private void update(Job next, Bundle update) {
        if (!current(next) || !matches(update, next.id, panelId, next.key)) return;
        @SuppressWarnings("deprecation") PendingIntent viewer = update.getParcelable("session_viewer");
        if (viewer != null) next.sessionViewerIntent = viewer;
        int generation = update.getInt("page_generation", -1);
        if (generation < 0 || generation < next.pageGeneration) return;
        next.pageGeneration = generation;
        String resultUrl = update.getString("result_url");
        if (resultPageUrl(resultUrl)) {
            if (queryBaseUrl == null) queryBaseUrl = resultUrl;
            next.resultUrl = resultUrl; displayedResultUrl = resultUrl;
            resultUrlImage = next.image; resultUrlGeneration = next.generation; resultUrlEpoch = next.epochValue;
            updateOriginalPageButton();
        }
        String status = update.getString("status", "parse_error");
        String mode = update.getString("search_mode");
        if (mode != null && java.util.Set.of("all", "visual", "ai", "exact").contains(mode)) searchMode = mode;
        switch (status) {
            case "loading" -> { armTimeout(next, 120_000); showMessage("Google Lens 正在识图…", false); }
            case "needs_interaction" -> {
                armTimeout(next, 300_000);
                String message = update.getString("message", "Google 需要额外验证，本次识图已暂停");
                @SuppressWarnings("deprecation") PendingIntent interaction = update.getParcelable("interaction");
                if (!update.getBoolean("google_interaction_required", false)) interaction = null;
                showInteraction(next, message.length() <= 300 ? message : message.substring(0, 300), interaction);
            }
            case "interaction_opened" -> interactionOpened(next);
            case "session_viewer_opened" -> { if (next.sessionViewerVisible) interactionOpened(next); }
            case "interaction_closed" -> interactionClosed(next);
            case "page_ready" -> {
                MAIN.removeCallbacks(next.watchdog); next.searchReady = true; next.livePageReady = true;
                updateOriginalPageButton(); updateSearchControls();
                if (!next.sessionViewerVisible && !next.awaitingInteractionOpen) showLivePage(next);
            }
            case "results_ready" -> {
                next.livePageReady = false;
                @SuppressWarnings("deprecation") ArrayList<Bundle> items = update.getParcelableArrayList("items");
                if (items == null || items.isEmpty()) { fail(next, "返回的结果无法识别，请重试"); return; }
                if (!showResults(next, items)) { fail(next, "结果链接无法打开，请重试"); return; }
                MAIN.removeCallbacks(next.watchdog); next.searchReady = true; updateOriginalPageButton(); updateSearchControls();
            }
            case "empty" -> {
                next.livePageReady = false;
                showMessage("Google Lens 未找到匹配内容，可切换匹配方式或修改搜索词", false);
                MAIN.removeCallbacks(next.watchdog); next.searchReady = true; updateOriginalPageButton(); updateSearchControls();
            }
            case "network_error" -> fail(next, "Google Lens 连接失败，请检查手机网络后重试");
            case "timeout" -> fail(next, "识图超时，请重试");
            case "cancelled" -> { showMessage("本次识图已取消", true); release(next, false); }
            case "query_error" -> {
                showMessage(update.getString("message", "当前图片的搜索会话暂不可用"), false);
                MAIN.removeCallbacks(next.watchdog); next.searchReady = true;
                updateOriginalPageButton(); updateSearchControls();
            }
            case "parse_error" -> {
                if (next.searchReady) {
                    showMessage(update.getString("message", "Google 未返回可显示内容，可调整搜索词或筛选方式"), false);
                    updateSearchControls();
                } else fail(next, "Google 页面已返回，但暂时无法提取结果，请重试或更新模块");
            }
            default -> fail(next, "Google 页面已返回，但暂时无法提取结果，请重试或更新模块");
        }
    }

    private void fail(Job next, String message) {
        if (!current(next)) { next.closeImage(); return; }
        showMessage(message, true); stop(next);
    }

    private void stop(Job next) { release(next, true); }

    private void showInteraction(Job next, String message, PendingIntent interaction) {
        next.interactionIntent = interaction;
        showMessage(message, false);
        if (interaction != null) button("处理 Google 验证", () -> launchInteraction(next));
    }

    private void launchInteraction(Job next) {
        if (!current(next) || next.awaitingInteractionOpen || next.interactionIntent == null) return;
        if (!beginInteraction(next)) { fail(next, "无法确认当前应用身份，请重新长按图片后重试"); return; }
        next.openingSessionViewer = false; next.awaitingInteractionOpen = true;
        showMessage("正在打开 Google 验证…", false);
        MAIN.postDelayed(next.launchWatchdog, LensInteractionLease.LAUNCH_TIMEOUT_MILLIS);
        try {
            next.interactionIntent.send(context, 0, null, null, null, null, interactionLaunchOptions());
        } catch (PendingIntent.CanceledException | RuntimeException unavailable) {
            interactionLaunchFailed(next, "Google 验证窗口未能打开，请重试");
        }
    }

    static Bundle interactionLaunchOptions() {
        if (Build.VERSION.SDK_INT < 34) return null;
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setPendingIntentBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        return options.toBundle();
    }

    private void interactionOpened(Job next) {
        Interaction held = interactionLease;
        if (!next.awaitingInteractionOpen || held == null || held.owner.get() != this
                || !held.id.equals(next.id) || !held.valid() || !held.lease.active()) return;
        MAIN.removeCallbacks(next.launchWatchdog); next.awaitingInteractionOpen = false;
        if (resultSheet != null) resultSheet.setInteractionHidden(true);
    }

    private void interactionClosed(Job next) {
        if (!current(next) || !next.sessionViewerReturnPending) return;
        next.sessionViewerReturnPending = false; MAIN.removeCallbacks(next.launchWatchdog); next.awaitingInteractionOpen = false;
        endInteraction(next); updateSearchControls();
        if (next.livePageReady) showLivePage(next);
    }

    private void interactionLaunchFailed(Job next, String message) {
        if (!current(next) || !next.awaitingInteractionOpen) return;
        endInteraction(next);
        showInteraction(next, message, next.interactionIntent);
    }

    private void release(Job next, boolean cancel) {
        MAIN.removeCallbacks(next.watchdog);
        if (job == next) closeEmbeddedBrowser();
        endInteraction(next);
        next.cancelled = true; next.closeImage();
        ILensService service = next.service;
        next.service = null;
        if (cancel && service != null) SIGNALS.execute(() -> {
            try { service.cancel(next.id); } catch (RemoteException | RuntimeException ignored) { }
        });
        if (next.bound) {
            next.bound = false;
            try { context.unbindService(next); } catch (RuntimeException ignored) { }
        }
        if (job == next) job = null;
        updateSearchControls();
    }

    private boolean beginInteraction(Job next) {
        if (!current(next)) return false;
        try {
            String source = sourcePackage.get();
            if (source == null || source.trim().isEmpty()) return false;
            int moduleUid = context.getPackageManager().getApplicationInfo(BridgeContract.APP_PACKAGE, 0).uid;
            int sourceUid = context.getPackageManager().getApplicationInfo(source, 0).uid;
            if (moduleUid / 100000 != android.os.Process.myUid() / 100000) return false;
            interactionLease = new Interaction(this, next, new LensInteractionLease(moduleUid, sourceUid, SystemClock.elapsedRealtime()));
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) { return false; }
    }

    private void endInteraction(Job next) {
        MAIN.removeCallbacks(next.launchWatchdog); next.awaitingInteractionOpen = false;
        next.openingSessionViewer = false; next.sessionViewerVisible = false; next.sessionViewerReturnPending = false;
        Interaction held = interactionLease;
        if (held != null && held.owner.get() == this && held.id.equals(next.id)) held.lease.end(SystemClock.elapsedRealtime());
        if (resultSheet != null) resultSheet.setInteractionHidden(false);
    }

    private void prepareTerminalReturn(Job next, Bundle update) {
        if (job != next || next.cancelled || epoch.get() != next.epochValue
                || !matches(update, next.id, panelId, next.key)) return;
        int generation = update.getInt("page_generation", -1);
        synchronized (next) {
            if (generation < 0 || generation < next.pageGeneration || generation < next.callbackGeneration) return;
            next.callbackGeneration = generation;
            String status = update.getString("status", "");
            Interaction held = interactionLease;
            if (held == null || held.owner.get() != this || !held.id.equals(next.id) || !held.valid()) return;
            // Binder must publish the viewer lifetime before queued UI work or another page result arrives.
            if ("session_viewer_opened".equals(status)) {
                if (next.openingSessionViewer && next.awaitingInteractionOpen && held.lease.active())
                    next.sessionViewerVisible = true;
                return;
            }
            if ("interaction_closed".equals(status)) {
                if (next.sessionViewerVisible) {
                    next.sessionViewerVisible = false; next.sessionViewerReturnPending = true;
                    held.lease.end(SystemClock.elapsedRealtime());
                }
                return;
            }
            if (next.sessionViewerVisible && java.util.Set.of("results_ready", "page_ready", "empty", "query_error", "parse_error").contains(status)) return;
            if (!java.util.Set.of("results_ready", "page_ready", "empty", "network_error", "parse_error", "timeout", "cancelled").contains(status)) return;
            next.sessionViewerVisible = false; next.openingSessionViewer = false;
            held.lease.end(SystemClock.elapsedRealtime());
        }
    }

    /** Called from the host's Binder observer; this never modifies a View from that thread. */
    public static boolean allowInteractionForeground(int uid, boolean foreground) {
        Interaction held = interactionLease;
        if (held == null || !held.valid()) return false;
        return held.lease.foreground(uid, foreground, SystemClock.elapsedRealtime());
    }

    public static boolean allowInteractionKeyboard() {
        Interaction held = interactionLease;
        if (held != null && held.valid() && held.lease.keyboard()) return true;
        PortalLensPanel panel = visibleSheet.get();
        return panel != null && panel.resultSheet != null && panel.resultSheet.isShowing()
                && panel.sheetImageValid() && ((panel.searchInput != null && panel.searchInput.hasFocus())
                || (panel.sourceWebView != null && panel.sourceWebView.hasFocus())
                || (panel.embeddedBrowser != null && panel.embeddedBrowser.hasFocus()));
    }

    private static final class Interaction {
        final WeakReference<PortalLensPanel> owner;
        final String id;
        final int epoch;
        final WeakReference<Bitmap> image;
        final int imageGeneration;
        final LensInteractionLease lease;
        Interaction(PortalLensPanel owner, PortalLensPanel.Job job, LensInteractionLease lease) {
            this.owner = new WeakReference<>(owner); this.id = job.id; this.epoch = job.epochValue; this.lease = lease;
            this.image = new WeakReference<>(job.image); this.imageGeneration = job.generation;
        }
        boolean valid() {
            PortalLensPanel panel = owner.get();
            Bitmap selected = image.get();
            if (panel == null || selected == null || panel.epoch.get() != epoch || panel.selectedImage.get() != selected
                    || selected.isRecycled() || selected.getGenerationId() != imageGeneration) return false;
            if (!lease.active()) return true;
            PortalLensPanel.Job current = panel.job;
            return current != null && current.id.equals(id) && !current.cancelled;
        }
    }

    private boolean ensureResultSheet() {
        if (resultSheet != null && resultSheet.isShowing()) return true;
        Bitmap image = selectedImage.get();
        LensResultSheet created = new LensResultSheet(host, image, () -> {
            if (resultSheet != null) invalidate();
        });
        LinearLayout page = new LinearLayout(host.getContext()); page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true); page.requestFocus();
        buildSearchControls(page, image);
        resultArea = new FrameLayout(host.getContext());
        page.addView(resultArea, new LinearLayout.LayoutParams(-1, 0, 1));
        created.contentContainer().addView(page, new FrameLayout.LayoutParams(-1, -1));
        created.setBackHandler(() -> {
            if (sourceRoot == null) return false;
            if (sourceWebView != null && sourceWebView.canGoBack()) sourceWebView.goBack();
            else returnToResults();
            return true;
        });
        if (!created.show()) {
            created.dismiss(); resultArea = null;
            android.widget.Toast.makeText(host.getContext(), "无法打开识图结果页，请重新长按图片", android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        resultSheet = created; sheetImage = image;
        sheetImageGeneration = image == null || image.isRecycled() ? 0 : image.getGenerationId(); sheetEpoch = epoch.get();
        suppressedPortalRoot = host.getRootView(); portalRootVisibility = suppressedPortalRoot.getVisibility();
        suppressedPortalRoot.setVisibility(View.INVISIBLE);
        visibleSheet = new WeakReference<>(this);
        updateOriginalPageButton(); updateSearchControls();
        return true;
    }

    private void buildSearchControls(LinearLayout page, Bitmap image) {
        LinearLayout row = new LinearLayout(host.getContext()); row.setGravity(Gravity.CENTER_VERTICAL); row.setBaselineAligned(false);
        row.setPadding(dp(16), dp(6), dp(12), dp(6));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        boolean dark = (host.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        background.setColor(dark ? 0xff303134 : 0xfff2f3f5); background.setCornerRadius(dp(30)); row.setBackground(background);
        if (image != null && !image.isRecycled()) {
            ImageView thumbnail = new ImageView(host.getContext()); thumbnail.setImageBitmap(image);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP); thumbnail.setContentDescription("本次识图图片");
            row.addView(thumbnail, new LinearLayout.LayoutParams(dp(42), dp(48)));
        }
        searchInput = new EditText(host.getContext()); searchInput.setSingleLine(true);
        searchInput.setTextSize(16); searchInput.setHint("询问关于此图片的问题"); searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setPadding(dp(8), 0, dp(4), 0);
        searchInput.setTextColor(dark ? Color.WHITE : 0xff202124);
        searchInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(512)});
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            submitQuery(); return true;
        });
        searchInput.setOnFocusChangeListener((view, focused) -> { if (focused && resultSheet != null) resultSheet.expand(); });
        row.addView(searchInput, new LinearLayout.LayoutParams(0, dp(56), 1));
        searchButton = new Button(host.getContext()); searchButton.setText("搜索"); searchButton.setAllCaps(false);
        searchButton.setBackgroundColor(Color.TRANSPARENT); searchButton.setElevation(0); searchButton.setStateListAnimator(null);
        searchButton.setTextColor(new android.content.res.ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{dark ? 0xff777777 : 0xffaaaaaa, dark ? 0xff8ab4f8 : 0xff1967d2}));
        searchButton.setMinWidth(dp(56)); searchButton.setOnClickListener(view -> submitQuery());
        row.addView(searchButton, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(dp(16), 0, dp(16), dp(8)); page.addView(row, rowParams);
        HorizontalScrollView strip = new HorizontalScrollView(host.getContext()); strip.setHorizontalScrollBarEnabled(false);
        LinearLayout modes = new LinearLayout(host.getContext()); modes.setGravity(Gravity.CENTER_VERTICAL);
        strip.addView(modes); modeButtons.clear();
        String[] labels = {"AI 模式", "全部", "完全匹配", "外观匹配"};
        String[] values = {"ai", "all", "exact", "visual"};
        for (int i = 0; i < labels.length; i++) {
            TextView tab = text(labels[i], 16); tab.setTag(values[i]); tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(16), dp(12), dp(16), dp(12)); tab.setMinimumHeight(dp(48));
            String mode = values[i]; tab.setOnClickListener(view -> { searchMode = mode; submitQuery(); });
            modes.addView(tab, new LinearLayout.LayoutParams(-2, -2)); modeButtons.add(tab);
        }
        page.addView(strip, new LinearLayout.LayoutParams(-1, -2));
    }

    private void updateSearchControls() {
        boolean available = queryBaseUrl != null && hasOriginalPage() && job != null && job.searchReady;
        if (searchButton != null) searchButton.setEnabled(available);
        if (searchInput != null) {
            searchInput.setEnabled(available);
            searchInput.setHint(switch (searchMode) {
                case "ai" -> "询问 AI 关于这张图片的问题";
                case "exact" -> "输入要查找的精确内容";
                case "visual" -> "描述图片的外观特征";
                default -> "搜索此图片相关内容";
            });
        }
        if (searchButton != null) searchButton.setText("ai".equals(searchMode) ? "提问" : "搜索");
        for (TextView tab : modeButtons) {
            tab.setEnabled(available); boolean selected = searchMode.equals(tab.getTag());
            tab.setSelected(selected); tab.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            tab.setPaintFlags(selected ? tab.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    : tab.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        }
    }

    private void submitQuery() {
        Job current = job;
        if (queryBaseUrl == null || current == null || !current.searchReady || current.service == null) return;
        String query = searchInput == null ? "" : searchInput.getText().toString();
        hideOwnedKeyboard(searchInput);
        if ("all".equals(searchMode) && query.trim().isEmpty()) {
            closeSourcePage(); browsingQuery = true; query = "";
        }
        String target = LensSearchUrl.build(queryBaseUrl, query, searchMode);
        if (target == null) {
            android.widget.Toast.makeText(host.getContext(), "当前图片的搜索会话暂不可用", android.widget.Toast.LENGTH_SHORT).show(); return;
        }
        if (resultSheet != null) resultSheet.expand();
        displayedResultUrl = target; updateOriginalPageButton();
        showMessage("正在同一图片会话中搜索…", false); browsingQuery = true;
        current.searchReady = false; armTimeout(current, 120_000); updateSearchControls();
        String submitted = query, mode = searchMode;
        ILensService service = current.service;
        SIGNALS.execute(() -> {
            try { service.refine(current.id, submitted, mode); }
            catch (RemoteException | RuntimeException disconnected) {
                MAIN.post(() -> fail(current, "Google Lens 图片会话已断开，请重新识图"));
            }
        });
    }

    private boolean sheetImageValid() {
        return sheetImage != null && !sheetImage.isRecycled() && selectedImage.get() == sheetImage
                && sheetImage.getGenerationId() == sheetImageGeneration && sheetEpoch == epoch.get() && host.isAttachedToWindow();
    }

    private void returnToResults() {
        closeSourcePage(); browsingQuery = false;
        updateOriginalPageButton(); updateSearchControls();
    }

    private void hideOwnedKeyboard(View view) {
        if (view == null) return;
        InputMethodManager input = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null && view.getWindowToken() != null) input.hideSoftInputFromWindow(view.getWindowToken(), 0);
        view.clearFocus();
    }

    private void closeResultSheet() {
        closeEmbeddedBrowser();
        hideOwnedKeyboard(searchInput);
        LensResultSheet previous = resultSheet; resultSheet = null;
        if (visibleSheet.get() == this) visibleSheet = new WeakReference<>(null);
        if (previous != null) previous.dismiss();
        if (suppressedPortalRoot != null) suppressedPortalRoot.setVisibility(portalRootVisibility);
        suppressedPortalRoot = null; resultArea = null; overlay = null; contents = null;
        searchInput = null; searchButton = null; modeButtons.clear(); queryBaseUrl = null; searchMode = "all"; sheetImage = null;
    }

    private void base() {
        if (!ensureResultSheet()) return;
        closeEmbeddedBrowser();
        closeSourcePage();
        if (overlay == null) {
            overlay = new ScrollView(host.getContext()); overlay.setFillViewport(true); overlay.setClipToPadding(false);
            contents = new LinearLayout(host.getContext()); contents.setOrientation(LinearLayout.VERTICAL);
            contents.setPadding(dp(16), dp(8), dp(16), dp(16));
            overlay.addView(contents, new ScrollView.LayoutParams(-1, -2));
            resultArea.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        }
        overlay.setVisibility(View.VISIBLE); overlay.bringToFront(); contents.removeAllViews();
        updateOriginalPageButton(); updateSearchControls();
    }

    private void showMessage(String message, boolean retry) {
        base(); if (contents == null) return;
        TextView status = text(message, 15); status.setPadding(0, dp(12), 0, dp(12));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); contents.addView(status);
        if (retry) button("重试识图", this::search);
    }

    private boolean hasOriginalPage() {
        return displayedResultUrl != null && resultUrlEpoch == epoch.get() && resultUrlImage != null
                && selectedImage.get() == resultUrlImage && !resultUrlImage.isRecycled()
                && resultUrlImage.getGenerationId() == resultUrlGeneration;
    }

    private void updateOriginalPageButton() {
        Job current = job;
        boolean available = hasOriginalPage() && current != null && current.searchReady && current.sessionViewerIntent != null;
        if (resultSheet != null) resultSheet.setOriginalPage(available, this::openOriginalPage);
    }

    private void openOriginalPage() {
        Job current = job;
        if (!hasOriginalPage() || current == null || !current.searchReady || current.sessionViewerIntent == null) {
            updateOriginalPageButton();
            android.widget.Toast.makeText(host.getContext(), "当前图片会话暂不可用，请重新识图", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!beginInteraction(current)) {
            android.widget.Toast.makeText(host.getContext(), "无法打开当前图片会话，请重新识图", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        closeEmbeddedBrowser();
        current.openingSessionViewer = true; current.awaitingInteractionOpen = true;
        MAIN.postDelayed(current.launchWatchdog, LensInteractionLease.LAUNCH_TIMEOUT_MILLIS);
        try {
            current.sessionViewerIntent.send(context, 0, null, null, null, null, interactionLaunchOptions());
        } catch (RuntimeException unavailable) {
            current.awaitingInteractionOpen = false; endInteraction(current);
            if (current.livePageReady) showLivePage(current);
            android.widget.Toast.makeText(host.getContext(), "无法打开 Google Lens 原网页", android.widget.Toast.LENGTH_SHORT).show();
        } catch (PendingIntent.CanceledException unavailable) {
            current.awaitingInteractionOpen = false; endInteraction(current);
            if (current.livePageReady) showLivePage(current);
            android.widget.Toast.makeText(host.getContext(), "Google Lens 图片会话已结束，请重新识图", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    static boolean resultPageUrl(String value) {
        return LensPageParser.isResultUrl(value);
    }

    private boolean showResults(Job next, ArrayList<Bundle> items) {
        base();
        if (contents == null) return false;
        return LensResultGrid.append(host.getContext(), contents, items,
                url -> { if (displayedImage(next)) openSourcePage(url); },
                (view, address) -> loadThumbnail(view, address, next)) > 0;
    }

    private void showLivePage(Job next) {
        if (!current(next) || !next.livePageReady || next.sessionViewerVisible || next.awaitingInteractionOpen) return;
        if (embeddedBrowser != null && embeddedBrowser.isShowing()) return;
        showMessage(Build.VERSION.SDK_INT >= 30 ? "正在显示 Google 当前图片搜索页…"
                : "当前系统不支持内嵌此模式，可点击右上角原网页继续查看同一图片", false);
        if (Build.VERSION.SDK_INT < 30 || resultArea == null || next.service == null) return;
        // Wait for the dialog traversal rather than creating a second WebView or uploading again.
        FrameLayout area = resultArea;
        area.post(() -> mountLivePage(next, area));
    }

    private void mountLivePage(Job next, FrameLayout area) {
        if (Build.VERSION.SDK_INT < 30 || !current(next) || !next.livePageReady || resultArea != area
                || next.sessionViewerVisible || next.awaitingInteractionOpen || embeddedBrowser != null) return;
        String viewId = UUID.randomUUID().toString();
        ILensService service = next.service;
        if (service == null) return;
        LensEmbeddedBrowser browser = new LensEmbeddedBrowser(host, new LensEmbeddedBrowser.Listener() {
            @Override public void onSurfaceReady(IBinder token, int display, int width, int height) {
                SIGNALS.execute(() -> {
                    try {
                        service.attachBrowser(next.id, viewId, token, display, width, height, new ILensSurfaceCallback.Stub() {
                            @Override public void onSurface(Bundle result) {
                                MAIN.post(() -> receiveBrowser(next, viewId, result));
                            }
                        });
                    } catch (RemoteException | RuntimeException unavailable) {
                        MAIN.post(() -> browserUnavailable(next, viewId));
                    }
                });
            }
            @Override public void onResize(int width, int height) {
                SIGNALS.execute(() -> {
                    try { service.resizeBrowser(next.id, viewId, width, height); }
                    catch (RemoteException | RuntimeException unavailable) { MAIN.post(() -> browserUnavailable(next, viewId)); }
                });
            }
            @Override public void onClosed() {
                boolean unexpected = viewId.equals(embeddedViewId);
                if (unexpected) { embeddedBrowser = null; embeddedViewId = null; }
                SIGNALS.execute(() -> {
                    try { service.detachBrowser(next.id, viewId); } catch (RemoteException | RuntimeException ignored) { }
                });
                if (unexpected) MAIN.post(() -> {
                    if (current(next) && embeddedBrowser == null && next.livePageReady
                            && !next.sessionViewerVisible && !next.awaitingInteractionOpen)
                        showMessage("当前页面显示已暂停，可切换模式或点击右上角原网页继续查看", false);
                });
            }
        });
        embeddedBrowser = browser; embeddedViewId = viewId;
        if (!browser.mount(area)) browserUnavailable(next, viewId);
    }

    private void receiveBrowser(Job next, String viewId, Bundle result) {
        if (Build.VERSION.SDK_INT < 30) return;
        @SuppressWarnings("deprecation") SurfaceControlViewHost.SurfacePackage surface = result == null ? null
                : result.getParcelable("surface_package");
        boolean matches = result != null && next.id.equals(result.getString("request_id"))
                && viewId.equals(result.getString("view_id"));
        if (!current(next) || !viewId.equals(embeddedViewId) || embeddedBrowser == null
                || next.sessionViewerVisible || next.awaitingInteractionOpen || !matches) {
            if (surface != null) surface.release();
            return;
        }
        if (surface == null) { browserUnavailable(next, viewId); return; }
        if (!embeddedBrowser.attachSurface(surface)) {
            closeEmbeddedBrowser();
            showMessage("当前页面暂无法嵌入，可点击右上角原网页查看同一图片的结果", false); return;
        }
        if (overlay != null) overlay.setVisibility(View.GONE);
    }

    private void browserUnavailable(Job next, String viewId) {
        if (!current(next) || !viewId.equals(embeddedViewId)) return;
        closeEmbeddedBrowser();
        showMessage("当前页面暂无法嵌入，可点击右上角原网页查看同一图片的结果", false);
    }

    private void closeEmbeddedBrowser() {
        LensEmbeddedBrowser previous = embeddedBrowser;
        embeddedBrowser = null; embeddedViewId = null;
        if (previous != null) previous.destroy();
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void openSourcePage(String url) {
        browsingQuery = false;
        openEmbeddedPage(url);
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void openEmbeddedPage(String url) {
        if (!webUrl(url) || overlay == null || !host.isAttachedToWindow()) return;
        closeSourcePage();
        sourceRoot = new LinearLayout(host.getContext()); sourceRoot.setOrientation(LinearLayout.VERTICAL);
        sourceRoot.setPadding(dp(12), dp(8), dp(12), dp(12));
        boolean dark = (host.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        sourceRoot.setBackgroundColor(dark ? Color.rgb(24, 24, 24) : Color.WHITE);
        Button back = new Button(host.getContext()); back.setText("返回结果"); back.setAllCaps(false);
        back.setOnClickListener(view -> returnToResults()); sourceRoot.addView(back, new LinearLayout.LayoutParams(-1, -2));
        sourceError = text("", 14); sourceError.setVisibility(View.GONE);
        sourceError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sourceRoot.addView(sourceError, new LinearLayout.LayoutParams(-1, -2));
        resultArea.addView(sourceRoot, new FrameLayout.LayoutParams(-1, -1)); overlay.setVisibility(View.GONE);
        if (!sourceWebViewAllowed(android.os.Process.myUid())) {
            showSourceError("当前传送门进程不能创建网页组件，请返回结果；可点击原网页选择浏览器查看"); return;
        }
        try {
            WebView web = new WebView(host.getContext()); sourceWebView = web;
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
            settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSupportMultipleWindows(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setGeolocationEnabled(false); settings.setMediaPlaybackRequiresUserGesture(true);
            web.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> result,
                                                           FileChooserParams params) {
                    result.onReceiveValue(null); return true;
                }
                @Override public void onPermissionRequest(android.webkit.PermissionRequest request) { request.deny(); }
                @Override public boolean onJsAlert(WebView view, String address, String message, android.webkit.JsResult result) {
                    result.cancel(); return true;
                }
                @Override public boolean onJsConfirm(WebView view, String address, String message, android.webkit.JsResult result) {
                    result.cancel(); return true;
                }
                @Override public boolean onJsBeforeUnload(WebView view, String address, String message, android.webkit.JsResult result) {
                    result.cancel(); return true;
                }
                @Override public boolean onJsPrompt(WebView view, String address, String message, String value,
                                                    android.webkit.JsPromptResult result) {
                    result.cancel(); return true;
                }
            });
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return blockSourceNavigation(view, request.getUrl().toString());
                }
                @SuppressWarnings("deprecation")
                @Override public boolean shouldOverrideUrlLoading(WebView view, String address) {
                    return blockSourceNavigation(view, address);
                }
                @Override public void onPageStarted(WebView view, String address, Bitmap icon) {
                    if (view == sourceWebView && sourceError != null) sourceError.setVisibility(View.GONE);
                    if (view == sourceWebView && browsingQuery && resultPageUrl(address)) {
                        displayedResultUrl = address; updateOriginalPageButton();
                    }
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (view == sourceWebView && request.isForMainFrame()) showSourceError("来源网页加载失败，请返回结果后重试");
                }
                @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                    if (view == sourceWebView && request.isForMainFrame() && response.getStatusCode() >= 400)
                        showSourceError("来源网页返回错误：" + response.getStatusCode());
                }
                @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.cancel();
                    if (view == sourceWebView) showSourceError("来源网页的安全连接验证失败，已停止加载");
                }
                @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                    if (view == sourceWebView) {
                        destroySourceWebView(); showSourceError("网页组件已停止，请返回结果后重试");
                    }
                    return true;
                }
            });
            web.setDownloadListener((address, userAgent, disposition, mime, length) -> {
                if (web == sourceWebView) showSourceError("面板内暂不下载文件，请返回结果继续浏览");
            });
            web.setOnKeyListener((view, key, event) -> {
                if (key != android.view.KeyEvent.KEYCODE_BACK || web != sourceWebView) return false;
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    if (web.canGoBack()) web.goBack(); else returnToResults();
                }
                return true;
            });
            sourceRoot.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
            web.loadUrl(url);
        } catch (RuntimeException | LinkageError unavailable) {
            destroySourceWebView(); showSourceError("无法创建网页组件，请启用或更新 Android System WebView 后重试");
        }
    }

    static boolean sourceWebViewAllowed(int uid) { return uid >= 0 && uid % 100000 >= 10000; }

    private boolean blockSourceNavigation(WebView view, String url) {
        if (view != sourceWebView) return true;
        if (webUrl(url)) return false;
        showSourceError("面板内仅支持 HTTP/HTTPS 网页，已阻止打开其他应用或本地内容");
        return true;
    }

    private void showSourceError(String message) {
        if (sourceError == null) return;
        sourceError.setText(message); sourceError.setVisibility(View.VISIBLE);
    }

    private void destroySourceWebView() {
        WebView web = sourceWebView; sourceWebView = null;
        if (web == null) return;
        try {
            hideOwnedKeyboard(web);
            if (web.getParent() instanceof android.view.ViewGroup parent) parent.removeView(web);
            web.stopLoading(); web.destroy();
        } catch (RuntimeException ignored) { }
    }

    private void closeSourcePage() {
        destroySourceWebView();
        if (sourceRoot != null && resultArea != null) resultArea.removeView(sourceRoot);
        sourceRoot = null; sourceError = null;
        if (overlay != null) overlay.setVisibility(View.VISIBLE);
    }

    private void loadThumbnail(ImageView target, String address, Job next) {
        IO.execute(() -> {
            HttpURLConnection connection = null;
            try {
                if (epoch.get() != next.epochValue) return;
                byte[] bytes = inlineThumbnail(address);
                if (bytes == null) {
                    if (!webUrl(address)) return;
                    connection = (HttpURLConnection) new URL(address).openConnection();
                    connection.setConnectTimeout(6000); connection.setReadTimeout(6000); connection.setInstanceFollowRedirects(false);
                    if (connection.getResponseCode() != 200) return;
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream data = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192]; int read;
                        while ((read = input.read(buffer)) >= 0) {
                            if (epoch.get() != next.epochValue) return;
                            if (data.size() + read > 1024 * 1024) return;
                            data.write(buffer, 0, read);
                        }
                        bytes = data.toByteArray();
                    }
                }
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) return;
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 256 || options.outHeight / options.inSampleSize > 256)
                    options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap thumbnail = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                MAIN.post(() -> {
                    if (thumbnail == null) return;
                    if (target.isAttachedToWindow() && epoch.get() == next.epochValue
                            && selectedImage.get() == next.image && !next.image.isRecycled()
                            && next.image.getGenerationId() == next.generation) target.setImageBitmap(thumbnail);
                    else thumbnail.recycle();
                });
            } catch (IOException | RuntimeException unavailable) { /* A missing thumbnail does not discard its source link. */ }
            finally { if (connection != null) connection.disconnect(); }
        });
    }

    static byte[] inlineThumbnail(String value) {
        if (value == null || value.length() > 350_000) return null;
        for (String mime : new String[]{"jpeg", "png", "webp"}) {
            String prefix = "data:image/" + mime + ";base64,";
            if (!value.startsWith(prefix)) continue;
            try {
                byte[] bytes = Base64.decode(value.substring(prefix.length()), Base64.DEFAULT);
                return bytes.length > 0 && bytes.length <= 256 * 1024 ? bytes : null;
            } catch (IllegalArgumentException invalid) { return null; }
        }
        return null;
    }

    static boolean webUrl(String value) {
        if (value == null || value.length() > 8192) return false;
        try {
            Uri uri = Uri.parse(value); String hostname = uri.getHost();
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && hostname != null && !hostname.trim().isEmpty() && uri.getUserInfo() == null
                    && !hostname.equalsIgnoreCase("localhost") && !hostname.startsWith("127.")
                    && !hostname.equals("::1") && !hostname.equals("[::1]");
        } catch (RuntimeException malformed) { return false; }
    }

    private TextView text(CharSequence value, int size) {
        TextView view = new TextView(host.getContext()); view.setText(value); view.setTextSize(size);
        boolean dark = (host.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        view.setTextColor(dark ? Color.WHITE : Color.rgb(24, 24, 24)); return view;
    }

    private void button(String title, Runnable click) {
        Button button = new Button(host.getContext()); button.setText(title); button.setAllCaps(false);
        button.setOnClickListener(view -> click.run()); contents.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }
    private int dp(int value) { return Math.round(value * host.getResources().getDisplayMetrics().density); }
    @Override public void onViewAttachedToWindow(View view) { refresh(); }
    @Override public void onViewDetachedFromWindow(View view) { invalidate(); }

    private final class Job implements ServiceConnection {
        final String id = UUID.randomUUID().toString();
        final Bitmap image;
        final int generation;
        final int epochValue;
        volatile boolean cancelled;
        volatile String key;
        boolean bound;
        volatile int pageGeneration = -1;
        volatile int callbackGeneration = -1;
        volatile ILensService service;
        volatile boolean searchReady;
        boolean livePageReady;
        volatile PendingIntent sessionViewerIntent;
        String resultUrl;
        PendingIntent interactionIntent;
        volatile boolean awaitingInteractionOpen;
        volatile boolean openingSessionViewer;
        volatile boolean sessionViewerVisible;
        volatile boolean sessionViewerReturnPending;
        private ParcelFileDescriptor descriptor;
        final Runnable launchWatchdog = () -> interactionLaunchFailed(this,
                "Google 验证窗口尚未打开，请重试；当前图片无需重新上传");
        final Runnable watchdog = () -> {
            if (current(this)) { showMessage("识图超时，请检查网络后重试", true); stop(this); }
        };
        Job(Bitmap image) { this.image = image; generation = image.getGenerationId(); epochValue = epoch.get(); }
        synchronized void hold(ParcelFileDescriptor input) throws IOException {
            if (cancelled) input.close(); else descriptor = input;
        }
        synchronized ParcelFileDescriptor takeImage() { ParcelFileDescriptor result = descriptor; descriptor = null; return result; }
        synchronized void closeImage() {
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
            descriptor = null;
        }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!current(this)) { release(this, true); return; }
            service = ILensService.Stub.asInterface(binder);
            Bundle request = new Bundle(); request.putString("request_id", id); request.putString("panel_session_id", panelId);
            request.putString("image_key", key); request.putString("mime_type", "image/png");
            ILensService connected = service;
            IO.execute(() -> {
                try (ParcelFileDescriptor imageFd = takeImage()) {
                    if (cancelled || imageFd == null) return;
                    connected.search(imageFd, request, new ILensCallback.Stub() {
                        @Override public void onUpdate(Bundle update) {
                            // The worker finishes its activity after this synchronous Binder callback returns.
                            prepareTerminalReturn(Job.this, update);
                            MAIN.post(() -> update(Job.this, update));
                        }
                    });
                    if (cancelled) connected.cancel(id);
                } catch (RemoteException | IOException | RuntimeException failure) {
                    MAIN.post(() -> fail(this, "识图服务断开，请重试"));
                }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { fail(this, "识图服务已停止，请重试"); }
        @Override public void onNullBinding(ComponentName name) { fail(this, "识图服务不可用，请确认模块设置"); }
        @Override public void onBindingDied(ComponentName name) { fail(this, "识图服务连接已失效，请重试"); }
    }
}
