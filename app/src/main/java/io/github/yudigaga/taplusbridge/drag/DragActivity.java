package io.github.yudigaga.taplusbridge.drag;

import androidx.activity.ComponentActivity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.FrameLayout;
import io.github.yudigaga.taplusbridge.R;
import io.github.yudigaga.taplusbridge.bridge.BridgeProvider;
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.runtime.RuntimeClient;
import io.github.yudigaga.taplusbridge.ui.TransitActivity;
import io.github.yudigaga.taplusbridge.ui.DragMenuUi;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A transient target for an already active global drag, launched only by system_server. */
public final class DragActivity extends ComponentActivity {
    private static final String TAG = "TaplusBridge-Drag";
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CancellationSignal cancelled = new CancellationSignal();
    private DragSettings settings;
    private DragPayload payload;
    private DragAndDropPermissions permissions;
    private Future<?> readTask;
    private DropView target;
    private boolean dropped, destroyed, reading;
    private int generation;
    private String source;

    @SuppressWarnings("deprecation")
    @Override public void onCreate(Bundle state) {
        setTheme(R.style.DragTheme); super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().setDimAmount(0); getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        overridePendingTransition(0, 0);
        String resultToken = getIntent().getStringExtra("result_token");
        settings = new ConfigStore(this).getDragSettings();
        if (resultToken != null) {
            if (state != null || !new ConfigStore(this).enabled() || !settings.enabled) {
                DragActionStore.cancel(resultToken); finish(); return;
            }
            DragActionStore.Choice choice = DragActionStore.takeChoice(resultToken);
            if (choice == null) { fail("本轮识别结果已过期，请重新拖拽"); return; }
            payload = choice.payload(); source = payload.source; dropped = true;
            Log.i(TAG, "stage=choice_ready action=" + choice.action() + " links=" + payload.links().size()
                    + " text_chars=" + choice.originalText().length() + " recognized_chars=" + choice.recognizedText().length()
                    + " image=" + (payload.image != null));
            if (!allowed()) { finish(); return; }
            showContentChoice(choice); return;
        }
        source = getIntent().getStringExtra("source");
        long began = getIntent().getLongExtra("started_elapsed", -1), now = SystemClock.elapsedRealtime();
        if (state != null || !new ConfigStore(this).enabled() || !settings.enabled || !HostPolicy.validPackage(source)
                || !allowed() || began < 0 || began > now || now - began > 30_000 || getIntent().getIntExtra("display", -1) != 0
                || DragSettings.DIRECTIONS.stream().allMatch(direction -> "none".equals(settings.action(direction)))) {
            finish(); return;
        }
        target = new DropView(this); target.setOnDragListener(this::drag); setContentView(target);
        RuntimeClient.connect(getApplicationContext(), null);
        main.postDelayed(() -> { if (!dropped && !isFinishing()) finish(); }, 30_000);
    }

    private boolean drag(View view, DragEvent event) {
        if (destroyed || isFinishing()) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                if (dropped || !DragPayload.supports(event.getClipDescription())) { finish(); return false; }
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION:
                target.hover(event.getX(), event.getY()); return true;
            case DragEvent.ACTION_DROP:
                if (dropped) return false;
                String direction = target.direction(event.getX(), event.getY());
                DragSettings fresh = new ConfigStore(this).getDragSettings();
                String action = fresh.action(direction);
                if (!new ConfigStore(this).enabled() || !fresh.enabled || "none".equals(action) || direction == null) {
                    finish(); return false;
                }
                dropped = true; settings = fresh;
                ClipData data = event.getClipData();
                try { permissions = requestDragAndDropPermissions(event); }
                catch (RuntimeException unavailable) { fail("无法取得拖拽图片读取权限"); return false; }
                read(data, action); return true;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                if (!dropped) finish(); return true;
            default: return false;
        }
    }

    private void read(ClipData data, String action) {
        reading = true; target.busy = true; target.invalidate();
        int expected = ++generation;
        readTask = IO.submit(() -> {
            DragPayload result = null; String error = null;
            try { result = DragPayload.read(getApplicationContext(), data, source, cancelled); }
            catch (IOException | RuntimeException unavailable) { error = unavailable.getMessage(); }
            DragPayload ready = result; String message = error;
            main.post(() -> {
                releasePermissions();
                if (destroyed || isFinishing() || generation != expected) { if (ready != null) ready.close(); return; }
                reading = false;
                if (ready == null) { fail(message == null ? "无法读取拖拽内容" : message); return; }
                payload = ready; target.busy = false;
                if (!new ConfigStore(this).getDragSettings().enabled) { finish(); return; }
                handoff(action, true);
            });
        });
        main.postDelayed(() -> {
            if (!destroyed && generation == expected && reading && !isFinishing()) {
                ++generation; cancelled.cancel(); if (readTask != null) readTask.cancel(true);
                releasePermissions(); fail("读取拖拽内容超时，请重试");
            }
        }, 12_000);
    }

    private void execute(String action) {
        if (!allowed()) { finish(); return; }
        Log.i(TAG, "stage=execute action=" + action + " links=" + payload.links().size()
                + " text_chars=" + payload.text.length() + " image=" + (payload.image != null));
        try {
            switch (action) {
                case "share" -> share();
                case "browser" -> selectLink(this::browser);
                case "rules" -> selectLink(this::rules);
                case "copy" -> {
                    if (payload.text.isEmpty()) { fail("当前拖入的是图片，没有可复制的文字"); return; }
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("拖拽内容", payload.text));
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show(); finish();
                }
                case "portal", "lens", "translate" -> handoff(action, false);
                default -> finish();
            }
        } catch (IOException unavailable) { fail(unavailable.getMessage() == null ? "无法读取完整分享内容" : unavailable.getMessage()); }
        catch (RuntimeException unavailable) { fail("无法完成拖拽操作，请检查目标应用或模块设置"); }
    }

    private void selectLink(Consumer<String> action) {
        List<String> links = payload.links();
        if (links.isEmpty()) {
            Log.w(TAG, "stage=link_selection outcome=no_links");
            fail("拖拽内容没有真实网址；卡片可使用原来的长按取链接"); return;
        }
        Log.i(TAG, "stage=link_selection outcome=available count=" + links.size());
        if (links.size() == 1) { action.accept(links.get(0)); return; }
        DragMenuUi.showActions(this, "选择要打开的链接", links, index -> action.accept(links.get(index)));
    }

    private void rules(String url) {
        try {
            Bundle request = new Bundle(); request.putString("url", url); request.putString("source", source);
            request.putBoolean("ephemeral", true);
            LaunchPlanner.PlanningResult planned = LaunchPlanner.planWithReason(this, request);
            if (planned.plan() == null) {
                Log.w(TAG, "stage=rules_plan outcome=" + planned.reason());
                fail("未找到可用且唯一的链接规则，请在模块内检查对应域名与来源应用规则"); return;
            }
            Intent ready = LaunchPlanner.directIfReady(this, planned.plan());
            if (ready != null) {
                startActivity(ready); Log.i(TAG, "stage=rules_launch outcome=direct target=" + planned.plan().targetPackage());
                finish(); return;
            }
            String token = BridgeProvider.issueDrag(this, request);
            if (token == null) { Log.w(TAG, "stage=rules_launch outcome=ticket_rejected"); fail("无法创建流转请求，请重试"); return; }
            startActivity(new Intent(this, TransitActivity.class).putExtra("token", token));
            Log.i(TAG, "stage=rules_launch outcome=transit target=" + planned.plan().targetPackage()); finish();
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "stage=rules_launch outcome=exception type=" + unavailable.getClass().getSimpleName());
            fail("目标应用无法处理该链接，请检查规则或解冻授权");
        }
    }

    private void browser(String url) {
        Intent generic = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        LinkedHashMap<String, ResolveInfo> browsers = new LinkedHashMap<>();
        for (ResolveInfo info : getPackageManager().queryIntentActivities(generic,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER))
            if (info.activityInfo != null && info.activityInfo.exported && info.activityInfo.enabled && info.filter != null
                    && info.filter.countDataAuthorities() == 0 && info.filter.countDataPaths() == 0)
                browsers.putIfAbsent(info.activityInfo.packageName, info);
        Intent selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
        for (ResolveInfo info : getPackageManager().queryIntentActivities(selector, 0))
            if (info.activityInfo != null && info.activityInfo.exported && info.activityInfo.enabled)
                browsers.putIfAbsent(info.activityInfo.packageName, info);
        if (browsers.isEmpty()) { fail("未找到可用的浏览器"); return; }
        Consumer<ResolveInfo> open = info -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(info.activityInfo.packageName);
                if (getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null)
                    throw new IllegalStateException("browser_unavailable");
                startActivity(intent); finish();
            } catch (RuntimeException unavailable) { fail("浏览器无法打开该链接"); }
        };
        ResolveInfo preferred = getPackageManager().resolveActivity(generic, PackageManager.MATCH_DEFAULT_ONLY);
        if (preferred != null && preferred.activityInfo != null && browsers.containsKey(preferred.activityInfo.packageName)) {
            open.accept(browsers.get(preferred.activityInfo.packageName)); return;
        }
        if (browsers.size() == 1) { open.accept(browsers.values().iterator().next()); return; }
        choose("选择浏览器", new ArrayList<>(browsers.values()), open);
    }

    private void share() throws IOException {
        Intent send = new Intent(Intent.ACTION_SEND).setType(payload.mime);
        String text = payload.sharedText();
        if (!text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
        if (payload.image != null) {
            Uri image = DragShareProvider.expose(this, payload);
            send.putExtra(Intent.EXTRA_STREAM, image); send.setClipData(ClipData.newRawUri("图片", image));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            payload = null;
        }
        if (!settings.shareOnlySelected && settings.shareTargets.isEmpty()) {
            startActivity(Intent.createChooser(send, "分享拖拽内容")); finish(); return;
        }
        LinkedHashMap<String, ResolveInfo> compatible = new LinkedHashMap<>();
        for (ResolveInfo info : getPackageManager().queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (info.activityInfo == null || !info.activityInfo.exported || !info.activityInfo.enabled) continue;
            compatible.put(new ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToString(), info);
        }
        ArrayList<ResolveInfo> choices = new ArrayList<>();
        for (String component : settings.shareTargets) {
            ResolveInfo selected = compatible.remove(component); if (selected != null) choices.add(selected);
        }
        if (!settings.shareOnlySelected) {
            ArrayList<ResolveInfo> remaining = new ArrayList<>(compatible.values());
            remaining.sort(Comparator.comparing(info -> info.loadLabel(getPackageManager()).toString())); choices.addAll(remaining);
        }
        if (choices.isEmpty()) { fail("自定义分享菜单没有能接收当前内容的应用，请检查分享目标设置"); return; }
        choose("分享拖拽内容", choices, info -> {
            try {
                startActivity(new Intent(send).setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name)));
                finish();
            } catch (RuntimeException unavailable) { fail("分享目标暂不可用"); }
        });
    }

    private void choose(String title, List<ResolveInfo> choices, Consumer<ResolveInfo> selected) {
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).loadLabel(getPackageManager()).toString()
                + "\n" + choices.get(i).activityInfo.packageName;
        DragMenuUi.showActions(this, title, java.util.Arrays.asList(labels), index -> selected.accept(choices.get(index)));
    }

    private void handoff(String action, boolean recognize) {
        if (!allowed()) { finish(); return; }
        if (!recognize && "lens".equals(action) && payload.image == null) { fail("识图需要拖入一张可读取的图片"); return; }
        if (!recognize && "lens".equals(action) && !new ConfigStore(this).lensEnabled()) { fail("请先在模块内开启 Google Lens 识图"); return; }
        String token = recognize ? DragActionStore.issue(this, action, payload) : DragActionStore.issueExecute(this, action, payload);
        payload = null;
        Context app = getApplicationContext(); finish();
        main.postDelayed(() -> {
            AtomicBoolean attempted = new AtomicBoolean();
            Runnable request = () -> {
                if (!attempted.compareAndSet(false, true)) return;
                launchHost(app, token, source, false);
            };
            RuntimeClient.connect(app, request); main.postDelayed(request, 900);
        }, 180);
    }

    private record Content(String label, String text, String url, boolean image) { }

    private void showContentChoice(DragActionStore.Choice choice) {
        String action = choice.action();
        ArrayList<Content> contents = new ArrayList<>();
        boolean urlsOnly = "browser".equals(action) || "rules".equals(action);
        boolean imageOnly = "lens".equals(action);
        boolean allKinds = "share".equals(action) || "portal".equals(action);
        if (!urlsOnly && !imageOnly) {
            if (!choice.originalText().isBlank()) contents.add(new Content(preview("原文", choice.originalText()), choice.originalText(), null, false));
            if (!choice.recognizedText().isBlank() && !choice.recognizedText().trim().equals(choice.originalText().trim()))
                contents.add(new Content(preview("图片识别文字", choice.recognizedText()), choice.recognizedText(), null, false));
        }
        if (choice.payload().image != null && (allKinds || imageOnly
                || ("translate".equals(action) && choice.recognizedText().isBlank())))
            contents.add(new Content("图片（" + choice.payload().width + " × " + choice.payload().height + "）", null, null, true));
        if (allKinds || urlsOnly) {
            int index = 0;
            for (String url : choice.payload().links()) contents.add(new Content(preview("链接 " + (++index), url), null, url, false));
        }
        if (contents.isEmpty()) {
            Log.w(TAG, "stage=content_choice outcome=empty action=" + action + " links=" + choice.payload().links().size()
                    + " original_chars=" + choice.originalText().length() + " recognized_chars=" + choice.recognizedText().length());
            fail(urlsOnly ? "本轮识别没有真实网址；卡片可使用原来的长按取链接"
                    : imageOnly ? "本轮没有可供识图的图片" : "本轮未识别到可用于" + actionLabel(action) + "的文字"); return;
        }
        int textItems = 0, linkItems = 0, imageItems = 0;
        for (Content content : contents) {
            if (content.image) imageItems++;
            else if (content.url != null) linkItems++;
            else textItems++;
        }
        // 原文和识别文字分别计数；多个链接仍算同一种。浏览器和规则一次只能打开一个链接，所以仍要选择。
        int kinds = textItems + imageItems + (linkItems > 0 ? 1 : 0);
        if (kinds == 1 && !(urlsOnly && linkItems > 1)) {
            Log.i(TAG, "stage=content_choice outcome=single_kind action=" + action + " items=" + contents.size());
            boolean[] selected = new boolean[contents.size()];
            java.util.Arrays.fill(selected, true);
            if (!applyChoice(action, contents, selected, -1)) finish();
            return;
        }
        String title = "选择用于" + actionLabel(action) + "的内容";
        String summary = choice.payload().image != null && choice.recognizedText().isBlank()
                ? "未识别到图片文字，可选择已有内容" : urlsOnly ? "每次打开一个链接" : "可同时选择多种内容";
        DragMenuUi.showSelection(this, title, summary, java.util.Arrays.asList(contents.stream().map(Content::label).toArray(String[]::new)),
                !urlsOnly, "执行" + actionLabel(action), checked -> applyChoice(action, contents, checked, -1));
    }

    private boolean applyChoice(String action, List<Content> contents, boolean[] checked, int single) {
        ArrayList<String> texts = new ArrayList<>(), links = new ArrayList<>(); boolean image = false;
        for (int i = 0; i < contents.size(); i++) {
            if (single >= 0 ? i != single : !checked[i]) continue;
            Content content = contents.get(i);
            if (content.text != null) texts.add(content.text);
            if (content.url != null) links.add(content.url);
            image |= content.image;
        }
        try {
            DragPayload selected = payload.select(texts, links, image);
            if (!image) payload.close(); payload = selected;
            Log.i(TAG, "stage=content_selected action=" + action + " links=" + payload.links().size()
                    + " text_chars=" + payload.text.length() + " image=" + (payload.image != null));
            execute(action); return true;
        } catch (IOException invalid) { Toast.makeText(this, invalid.getMessage(), Toast.LENGTH_LONG).show(); return false; }
    }

    private static String preview(String label, String value) {
        String line = value.replace('\n', ' ');
        return label + "\n" + (line.length() > 140 ? line.substring(0, 140) + "…" : line);
    }

    private boolean allowed() {
        try {
            ConfigStore config = new ConfigStore(this); settings = config.getDragSettings();
            return config.enabled() && settings.enabled && config.sourceScope().allows(source);
        } catch (RuntimeException malformed) { return false; }
    }

    private static void launchHost(Context app, String token, String source, boolean retried) {
        Bundle request = new Bundle(); request.putString("token", token); request.putString("source", source);
        Bundle result = RuntimeClient.call(app, "drag_launch", request);
        if (result != null && (result.getBoolean("accepted") || result.getBoolean("started"))) return;
        if (result == null && !retried) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> launchHost(app, token, source, true), 1000); return;
        }
        DragActionStore.cancel(token);
        Toast.makeText(app, "无法启动传送门拖拽操作，请确认模块和系统框架作用域已启用并重启", Toast.LENGTH_LONG).show();
    }

    private void fail(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); finish(); }
    private void releasePermissions() { if (permissions != null) { permissions.release(); permissions = null; } }
    @Override protected void onStop() { super.onStop(); if (!isFinishing()) finish(); }
    @Override protected void onDestroy() {
        destroyed = true; ++generation; cancelled.cancel();
        if (readTask != null) readTask.cancel(true);
        if (target != null) target.dispose();
        releasePermissions(); if (payload != null) { payload.close(); payload = null; }
        super.onDestroy();
    }
    @SuppressWarnings("deprecation")
    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }

    private final class DropView extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private RainbowDragView rainbow;
        private boolean busy;
        DropView(Context context) {
            super(context); setContentDescription("拖向屏幕四边彩虹，接触震动后松手；回到中央取消");
        }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            dispose(); removeAllViews();
            if (width <= 0 || height <= 0) return;
            java.util.Map<String, String> actions = new java.util.HashMap<>();
            for (String side : DragSettings.DIRECTIONS) actions.put(side, settings.action(side));
            rainbow = new RainbowDragView(getContext(), width, height, getResources().getDisplayMetrics().density,
                    width / 2f, height / 2f, actions, null, null);
            // Android already draws the source's drag shadow for this path.
            rainbow.setShowPreview(false);
            addView(rainbow, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        String direction(float x, float y) {
            return EdgeDragTarget.at(x, y, getWidth() / 2f, getHeight() / 2f,
                    getWidth(), getHeight(), getResources().getDisplayMetrics().density).touching();
        }
        void hover(float x, float y) { if (!busy && rainbow != null) rainbow.update(x, y, true); }
        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (busy) {
                canvas.drawColor(0x99080d19);
                label(canvas, "正在读取拖拽内容…", getWidth() / 2f, getHeight() / 2f, Color.WHITE, 19);
            }
        }
        private void label(Canvas canvas, String text, float x, float y, int color, int size) {
            paint.setColor(color); paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
            paint.setTextAlign(Paint.Align.CENTER); canvas.drawText(text, x, y, paint);
        }
        void dispose() { if (rainbow != null) { rainbow.dispose(); rainbow = null; } }
    }

    static String actionLabel(String action) {
        return switch (action) {
            case "portal" -> "传送门"; case "share" -> "分享"; case "browser" -> "浏览器打开"; case "rules" -> "按规则打开";
            case "lens" -> "识图"; case "translate" -> "翻译"; case "copy" -> "复制文字"; default -> "不执行";
        };
    }
}
