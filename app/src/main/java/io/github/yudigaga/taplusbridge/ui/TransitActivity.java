package io.github.yudigaga.taplusbridge.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import io.github.yudigaga.taplusbridge.R;
import io.github.yudigaga.taplusbridge.bridge.BridgeProvider;
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.runtime.ModuleProcessLifecycle;
import io.github.yudigaga.taplusbridge.thaw.ThawEngine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transparent foreground trampoline: no content window, thaw off-thread, launch once when resumed. */
public final class TransitActivity extends Activity {
    private Job job;
    private AlertDialog errorDialog;
    private String terminalError;
    private boolean resumed;

    @SuppressWarnings("deprecation")
    @Override public void onCreate(Bundle state) {
        setTheme(R.style.TransitTheme);
        super.onCreate(state);
        // Remain a real foreground Activity for background-start restrictions, without drawing
        // a module page or spinner above the source application during normal handoff.
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().setDimAmount(0f);
        overridePendingTransition(0, 0);
        job = (Job) getLastNonConfigurationInstance();
        if (job == null) {
            if (state != null) { terminalError = "流转已结束，请回到传送门重新点击。"; return; }
            Bundle request = BridgeProvider.consume(this, getIntent().getStringExtra("token"));
            if (request == null) { terminalError = "请求已过期或不来自传送门，请重新点击识别卡片。"; return; }
            job = new Job(request); job.start(getApplicationContext());
        }
        job.attach(this); render();
    }
    @Override public Object onRetainNonConfigurationInstance() { return job; }
    @Override protected void onResume() { super.onResume(); resumed = true; render(); }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onDestroy() {
        if (job != null) { job.attach(null); if (!isChangingConfigurations()) job.cancel(); }
        if (errorDialog != null) { errorDialog.dismiss(); errorDialog = null; }
        super.onDestroy();
    }
    @SuppressWarnings("deprecation")
    @Override public void finish() {
        // Do not leave an unobserved worker able to open an app after Back/cancel/fallback.
        if (job != null) job.cancel();
        super.finish();
        overridePendingTransition(0, 0);
    }
    private void render() {
        if (isFinishing() || !resumed) return;
        if (terminalError != null) { showFailure(terminalError); return; }
        if (job == null) return;
        if (job.ready && resumed && !job.cancelled && job.opened.compareAndSet(false, true)) {
            try {
                // Configuration changes during the Root prompt must not launch an outdated rule.
                LaunchPlanner.Plan fresh = LaunchPlanner.plan(this, job.request);
                if (fresh == null || job.plan == null || !fresh.targetPackage().equals(job.plan.targetPackage())
                        || !fresh.target().filterEquals(job.plan.target())) throw new IllegalStateException("流转规则已更改，请重新点击");
                Intent target = new Intent(fresh.target());
                if (Intent.ACTION_MAIN.equals(target.getAction())) {
                    target = getPackageManager().getLaunchIntentForPackage(fresh.targetPackage());
                    if (target == null) throw new IllegalStateException("目标应用没有桌面入口");
                }
                startActivity(target); finish(); return;
            } catch (Exception e) {
                job.ready = false; job.failed = true;
                job.message = e instanceof IllegalStateException && e.getMessage() != null
                        ? e.getMessage() : job.request.getBoolean("ephemeral")
                        ? "目标应用无法处理该链接，请重新识别。" : "目标应用无法处理该链接，请选择原始方式打开。";
            }
        }
        if (job.failed) showFailure(job.message);
    }
    @SuppressWarnings("deprecation")
    private void showFailure(String message) {
        if (!resumed || isFinishing() || (errorDialog != null && errorDialog.isShowing())) return;
        Intent original = job == null ? null : job.request.getParcelable("original");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("无法完成流转").setMessage(message)
                .setNegativeButton("取消", (ignored, which) -> finish())
                .setOnCancelListener(ignored -> finish());
        if (original != null && !job.fallbackStarted.get()) {
            dialog.setPositiveButton("使用原始方式打开", (ignored, which) -> openFallback(original));
        }
        errorDialog = dialog.create();
        errorDialog.show();
    }
    private void openFallback(Intent original) {
        if (job == null || !job.fallbackStarted.compareAndSet(false, true)) return;
        job.cancel();
        try {
            Intent fallback = new Intent(original); fallback.setSelector(null); fallback.setClipData(null);
            fallback.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(fallback); finish();
        } catch (RuntimeException e) {
            terminalError = "原始入口也无法打开，请回到传送门重试。";
            if (errorDialog != null) { errorDialog.dismiss(); errorDialog = null; }
            showFailure(terminalError);
        }
    }
    /** Package-visible completion seam exercises real lifecycle decisions without invoking root in tests. */
    static final class Job {
        final Bundle request;
        final AtomicBoolean opened = new AtomicBoolean();
        final AtomicBoolean fallbackStarted = new AtomicBoolean();
        final AtomicBoolean settled = new AtomicBoolean();
        volatile boolean ready, failed, cancelled;
        volatile String message = "正在解冻目标应用…";
        volatile LaunchPlanner.Plan plan;
        private TransitActivity owner;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private Future<?> future;
        private ModuleProcessLifecycle.Lease work;
        private final Runnable deadline = this::onTimeout;
        Job(Bundle request) { this.request = request; }
        synchronized void attach(TransitActivity activity) { owner = activity; }
        void onTimeout() {
            if (cancelled || !settled.compareAndSet(false, true)) return;
            failed = true; ready = false;
            message = request.getBoolean("ephemeral") ? "解冻等待超时，已停止打开，请重新识别。"
                    : "解冻等待超时，已停止自动打开。你可以使用原始方式打开。";
            if (future != null) future.cancel(true);
            executor.shutdownNow();
            releaseWork();
            synchronized (Job.this) { if (owner != null) owner.render(); }
        }
        void complete(LaunchPlanner.Plan completedPlan, String completedMessage, boolean completedSuccess) {
            if (cancelled || !settled.compareAndSet(false, true)) return;
            main.removeCallbacks(deadline);
            plan = completedPlan; message = completedMessage; ready = completedSuccess; failed = !completedSuccess;
            try { synchronized (Job.this) { if (owner != null) owner.render(); } }
            finally { releaseWork(); }
        }
        void start(android.content.Context context) {
            work = ModuleProcessLifecycle.acquire();
            main.postDelayed(deadline, 20_000L);
            try { future = executor.submit(() -> {
                LaunchPlanner.Plan found = null;
                String outcome;
                boolean success;
                try {
                    found = LaunchPlanner.plan(context, request);
                    if (found == null) throw new IllegalStateException(request.getBoolean("ephemeral")
                            ? "当前规则已失效，请重新识别" : "当前规则已失效，使用原始方式打开");
                    ThawEngine.Result result = ThawEngine.thaw(context, found.targetPackage(), new ConfigStore(context).mode());
                    outcome = result.message; success = result.success;
                } catch (Exception e) { outcome = "流转失败：" + e.getMessage(); success = false; }
                LaunchPlanner.Plan completedPlan = found;
                String completedMessage = outcome;
                boolean completedSuccess = success;
                if (!main.post(() -> complete(completedPlan, completedMessage, completedSuccess))) releaseWork();
                executor.shutdown();
            }); } catch (RuntimeException unavailable) {
                main.removeCallbacks(deadline); releaseWork(); executor.shutdownNow(); throw unavailable;
            }
        }
        void cancel() {
            cancelled = true; settled.set(true); main.removeCallbacks(deadline);
            if (future != null) future.cancel(true); executor.shutdownNow();
            releaseWork();
        }
        private synchronized void releaseWork() {
            if (work != null) { work.close(); work = null; }
        }
    }
}
