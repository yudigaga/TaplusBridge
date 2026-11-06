package io.github.yudigaga.taplusbridge.probe;

import android.content.Intent;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import io.github.yudigaga.taplusbridge.BuildConfig;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Explicit opt-in link probing. One verified physical gesture permits at most one same-point tap. */
public final class ScopeLinkProbe {
    public interface WindowCheck { void validate(ScopeCaptureWindow.Snapshot snapshot) throws ReflectiveOperationException; }
    public interface TapSender {
        boolean send(ScopeCaptureWindow.Snapshot snapshot, int action, long downAt) throws ReflectiveOperationException;
    }
    static final long CAPTURE_TIMEOUT_MILLIS = 3_000, MAX_GESTURE_MILLIS = 20_000;
    private static final long LATE_LAUNCH_MILLIS = 10_000;
    private static final int GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
    private enum Phase { ARMED, TAPPED }
    private static final class Run {
        final ScopeCaptureWindow.Snapshot snapshot;
        final RuntimeSnapshot config;
        final long downAt, gestureElapsed, threshold, recognitionAtUptime, expiresAt;
        Phase phase = Phase.ARMED;
        String url;
        boolean observed, invalid, tapAttempted;
        long tapDeadline, recognitionAt;
        Consumer<String> complete;
        Runnable timeout;
        Run(ScopeCaptureWindow.Snapshot snapshot, RuntimeSnapshot config, long downAt,
            long gestureElapsed, long threshold, long recognitionAtUptime, long expiresAt) {
            this.snapshot = snapshot; this.config = config; this.downAt = downAt;
            this.gestureElapsed = gestureElapsed; this.threshold = threshold;
            this.recognitionAtUptime = recognitionAtUptime; this.expiresAt = expiresAt;
        }
    }
    private final Handler worker;
    private final PhysicalTouchState physical;
    private final Supplier<RuntimeSnapshot> current;
    private final WindowCheck check;
    private final TapSender tap;
    private final Consumer<String> logger;
    private final Object lock = new Object();
    private final List<ScopeCaptureWindow.Snapshot> retired = new ArrayList<>();
    private record Tail(ScopeCaptureWindow.Snapshot snapshot, RuntimeSnapshot config, long gestureElapsed, long until) { }
    private Tail tail;
    private Run active;

    public ScopeLinkProbe(Handler worker, PhysicalTouchState physical, Supplier<RuntimeSnapshot> current,
                          WindowCheck check, TapSender tap, Consumer<String> logger) {
        this.worker = Objects.requireNonNull(worker); this.physical = Objects.requireNonNull(physical);
        this.current = Objects.requireNonNull(current); this.check = Objects.requireNonNull(check);
        this.tap = Objects.requireNonNull(tap); this.logger = Objects.requireNonNull(logger);
    }

    /** The caller arms after observing a real DOWN. Only single-finger candidates capture early launches. */
    public void arm(ScopeCaptureWindow.Snapshot snapshot, RuntimeSnapshot config, long downUptime,
                    long gestureElapsed, long timeoutMillis, int mode) {
        cancel();
        synchronized (lock) { tail = null; }
        long now = SystemClock.uptimeMillis();
        if (mode != 1 || timeoutMillis <= 0 || timeoutMillis > 10_000 || downUptime < 0
                || downUptime > now || now - downUptime > 300 || gestureElapsed < 0
                || gestureElapsed > SystemClock.elapsedRealtime() || !valid(config, snapshot) || !sameConfig(config, snapshot)) {
            log("rejected", "candidate_invalid"); return;
        }
        long recognitionAt = downUptime + timeoutMillis + config.drag.recognitionDelayMillis;
        Run run = new Run(snapshot, config, downUptime, gestureElapsed, timeoutMillis,
                recognitionAt, now + MAX_GESTURE_MILLIS);
        synchronized (lock) { retired.remove(snapshot); active = run; }
        scheduleTimeout(run, MAX_GESTURE_MILLIS);
        log("armed", "none");
    }

    /** Real physical input only: the caller must exclude its own injected stream before this method. */
    public void pointer(int action, int pointers, float x, float y, long downAt, float slop) {
        pointer(action, pointers, x, y, downAt, SystemClock.uptimeMillis(), slop);
    }

    public void pointer(int action, int pointers, float x, float y, long downAt, long eventAt, float slop) {
        Run run;
        synchronized (lock) { if (action == MotionEvent.ACTION_DOWN) tail = null; run = active; }
        if (run == null) return;
        if (action == MotionEvent.ACTION_DOWN) { finish(run, null, "new_touch"); return; }
        if (action == MotionEvent.ACTION_CANCEL || pointers != 1 || downAt != run.downAt
                || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(slop) || slop < 0
                || Math.hypot(x - run.snapshot.x, y - run.snapshot.y) > slop) {
            finish(run, null, "touch_changed"); return;
        }
        if (action == MotionEvent.ACTION_UP && eventAt < run.recognitionAtUptime) {
            finish(run, null, "released_before_recognition"); return;
        }
        if (action == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - run.downAt < run.threshold)
            finish(run, null, "short_press");
    }

    /**
     * Called synchronously before any original ATMS body. Never invokes a host callback or injects
     * input here. TAPPED owns even a malformed launch from this exact source, avoiding a flash.
     */
    public boolean intercept(int uid, String source, int user, Intent intent, boolean ordinary) {
        Run run; Phase phase; long tapDeadline;
        synchronized (lock) { run = active; phase = run == null ? null : run.phase; tapDeadline = run == null ? 0 : run.tapDeadline; }
        if (run == null) return interceptTail(uid, source, user);
        if (uid != run.snapshot.uid || user != 0 || user != run.snapshot.user
                || !run.snapshot.source.equals(source)) return false;
        long now = SystemClock.uptimeMillis();
        if (!sameConfig(run.config, run.snapshot)) {
            finish(run, null, "configuration_changed"); return false;
        }
        if (now >= run.expiresAt || (phase == Phase.TAPPED && now >= tapDeadline)) {
            finish(run, null, "timeout"); return interceptTail(uid, source, user);
        }
        boolean owns;
        synchronized (lock) { owns = active == run; if (owns) phase = run.phase; }
        if (!owns) return interceptTail(uid, source, user);
        if (phase == Phase.ARMED && now < run.recognitionAtUptime) return false;
        String url = strictHttp(intent, source);
        try { check.validate(run.snapshot); }
        catch (ReflectiveOperationException | RuntimeException changed) {
            synchronized (lock) {
                owns = active == run;
                if (owns) { run.observed = true; run.invalid = true; }
            }
            if (!owns) return interceptTail(uid, source, user);
            finish(run, null, "window_changed"); return true;
        }
        boolean confirmed = false;
        synchronized (lock) {
            owns = active == run;
            if (owns) {
                if (run.observed && !Objects.equals(run.url, url)) run.invalid = true;
                run.observed = true;
                if (url == null) run.invalid = true;
                if (!run.invalid) run.url = url;
                confirmed = run.complete != null;
            }
        }
        if (!owns) return interceptTail(uid, source, user);
        if (confirmed) worker.post(() -> finishObserved(run));
        if (url == null) log("rejected", rejectionReason(intent, source));
        log("blocked", url == null ? "unsupported_launch" : "http");
        return true;
    }

    /** The caller has verified the full long-press gesture and terminal physical UP. */
    public void confirm(ScopeCaptureWindow.Snapshot snapshot, RuntimeSnapshot config, Consumer<String> complete) {
        long nowElapsed = SystemClock.elapsedRealtime();
        Run run;
        synchronized (lock) { run = active; }
        long releasedAt = physical.releasedElapsedAt();
        if (releasedAt < 0) releasedAt = nowElapsed;
        long recognitionAt = run == null ? releasedAt
                : run.gestureElapsed + run.threshold + run.config.drag.recognitionDelayMillis;
        confirm(snapshot, config, recognitionAt, releasedAt, complete);
    }

    /** The fallback tap cannot run before recognition is ready for the same physical gesture. */
    public void confirm(ScopeCaptureWindow.Snapshot snapshot, RuntimeSnapshot config, long recognitionAt,
                        long releasedAt, Consumer<String> complete) {
        confirm(snapshot, config, recognitionAt, releasedAt, complete, null, false);
    }

    /** A drag keeps the scope observer's URL only when both observers saw this exact DOWN and target. */
    public void confirmDrag(ScopeCaptureWindow.Snapshot snapshot, RuntimeSnapshot config, long downUptime,
                            long recognitionAt, long releasedAt, Consumer<String> complete) {
        Objects.requireNonNull(complete);
        Run run; ScopeCaptureWindow.Snapshot target = snapshot;
        synchronized (lock) {
            run = active;
            if (retired.contains(snapshot) || (run != null && (run.downAt != downUptime || config == null
                    || !run.config.identity.equals(config.identity) || !run.snapshot.sameTarget(snapshot)))) {
                worker.post(() -> complete.accept(null)); return;
            }
            if (run != null) {
                target = run.snapshot;
                if (target != snapshot) {
                    retired.add(snapshot);
                    if (retired.size() > 8) retired.remove(0);
                }
            }
        }
        confirm(target, config, recognitionAt, releasedAt, complete, run, true);
    }

    private void confirm(ScopeCaptureWindow.Snapshot snapshot, RuntimeSnapshot config, long recognitionAt,
                         long releasedAt, Consumer<String> complete, Run expected, boolean exactRun) {
        Objects.requireNonNull(complete);
        long nowElapsed = SystemClock.elapsedRealtime();
        Run run;
        synchronized (lock) {
            if (retired.contains(snapshot)) { worker.post(() -> complete.accept(null)); return; }
            run = active;
            if (exactRun && run != expected) { worker.post(() -> complete.accept(null)); return; }
        }
        if (!valid(config, snapshot) || !sameConfig(config, snapshot) || !physical.released()) {
            if (run != null) finish(run, null, "configuration_changed");
            worker.post(() -> complete.accept(null)); return;
        }
        if (recognitionAt < 0 || releasedAt < recognitionAt || releasedAt > nowElapsed
                || (recognitionAt > nowElapsed && recognitionAt - nowElapsed > MAX_GESTURE_MILLIS)) {
            worker.post(() -> complete.accept(null)); return;
        }
        if (run != null && (run.snapshot != snapshot || !run.config.identity.equals(config.identity))) {
            finish(run, null, "candidate_changed"); worker.post(() -> complete.accept(null)); return;
        }
        if (run == null) {
            // Double-finger gestures are admitted only after the caller confirms them.
            long now = SystemClock.uptimeMillis();
            run = new Run(snapshot, config, now, SystemClock.elapsedRealtime(), 0, now,
                    now + CAPTURE_TIMEOUT_MILLIS);
            synchronized (lock) { if (active != null) { worker.post(() -> complete.accept(null)); return; } active = run; }
        }
        final Run confirmed = run;
        synchronized (lock) {
            if (active != confirmed || confirmed.complete != null) return;
            confirmed.complete = complete;
            confirmed.recognitionAt = recognitionAt;
        }
        try {
            requireCurrent(confirmed); check.validate(snapshot);
            long delay;
            boolean observed;
            synchronized (lock) {
                if (active != confirmed) return;
                observed = confirmed.observed;
                delay = Math.max(0, recognitionAt - SystemClock.elapsedRealtime());
                if (!observed) {
                    confirmed.phase = Phase.TAPPED;
                    confirmed.tapDeadline = SystemClock.uptimeMillis() + delay + CAPTURE_TIMEOUT_MILLIS;
                }
            }
            if (observed) { worker.post(() -> finishObserved(confirmed)); return; }
            scheduleTimeout(confirmed, delay + CAPTURE_TIMEOUT_MILLIS);
            Runnable tapLater = () -> sendTap(confirmed);
            if (delay == 0) tapLater.run(); else worker.postDelayed(tapLater, delay);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            finish(confirmed, null, "input_or_window_changed");
        }
    }

    private void sendTap(Run run) {
        synchronized (lock) { if (active != run || run.tapAttempted) return; }
        boolean attempted = false, released = false;
        long injectedDown = SystemClock.uptimeMillis();
        try {
            requireCurrent(run); check.validate(run.snapshot);
            if (SystemClock.elapsedRealtime() < run.recognitionAt) {
                worker.postDelayed(() -> sendTap(run), run.recognitionAt - SystemClock.elapsedRealtime());
                return;
            }
            synchronized (lock) {
                if (active != run || run.tapAttempted) return;
                run.tapAttempted = true;
            }
            attempted = true;
            if (!tap.send(run.snapshot, MotionEvent.ACTION_DOWN, injectedDown)) throw new IllegalStateException("input_failed");
            requireCurrent(run); check.validate(run.snapshot);
            released = tap.send(run.snapshot, MotionEvent.ACTION_UP, injectedDown);
            if (!released) throw new IllegalStateException("input_failed");
            log("tapped", "none");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            finish(run, null, "input_or_window_changed");
        } finally {
            if (attempted && !released) {
                try { tap.send(run.snapshot, MotionEvent.ACTION_CANCEL, injectedDown); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
    }

    public void cancel() {
        Run run;
        synchronized (lock) { tail = null; run = active; }
        if (run != null) finish(run, null, "cancelled");
    }

    /** A host probe owns window passthrough and delivery; this candidate must not also click or consume. */
    public boolean tryYieldToHost(ScopeCaptureWindow.Snapshot snapshot) {
        synchronized (lock) {
            if (snapshot == null || retired.contains(snapshot)) return false;
            Run run = active;
            if (run != null && (run.snapshot != snapshot || run.tapAttempted || run.observed)) return false;
            if (run != null) finish(run, null, "host_probe_owner");
            else {
                retired.add(snapshot);
                if (retired.size() > 8) retired.remove(0);
            }
            tail = null;
            return true;
        }
    }

    /** The opted-in link mode owns this same-source native result before its UI can cover the target. */
    public boolean ownsNative(String source, long occurredElapsed) {
        Run run;
        synchronized (lock) { run = active; }
        if (run == null || !run.snapshot.source.equals(source) || occurredElapsed < run.gestureElapsed
                || occurredElapsed < 0 || occurredElapsed > SystemClock.elapsedRealtime()
                || SystemClock.uptimeMillis() >= run.expiresAt || !sameConfig(run.config, run.snapshot)) return false;
        try { check.validate(run.snapshot); }
        catch (ReflectiveOperationException | RuntimeException changed) { return false; }
        synchronized (lock) { return active == run; }
    }

    /** A native acceptance only wins over its own current gesture, never a new or other-source one. */
    public void nativeResult(String source, long occurredElapsed) {
        nativeResult(source, occurredElapsed, true);
    }

    public void nativeResult(String source, long occurredElapsed, boolean hasValidUrl) {
        if (!hasValidUrl) return;
        Run run;
        synchronized (lock) {
            if (tail != null && tail.snapshot.source.equals(source) && occurredElapsed >= tail.gestureElapsed
                    && occurredElapsed <= SystemClock.elapsedRealtime()) tail = null;
            run = active;
            if (run == null || !run.snapshot.source.equals(source) || occurredElapsed < run.gestureElapsed
                    || occurredElapsed < 0 || occurredElapsed > SystemClock.elapsedRealtime()) return;
        }
        finish(run, null, "native_won");
    }

    private boolean interceptTail(int uid, String source, int user) {
        Tail saved;
        synchronized (lock) { saved = tail; }
        if (saved == null) return false;
        if (SystemClock.uptimeMillis() >= saved.until || !sameConfig(saved.config, saved.snapshot)) {
            synchronized (lock) { if (tail == saved) tail = null; }
            return false;
        }
        if (uid != saved.snapshot.uid || user != saved.snapshot.user || user != 0 || !saved.snapshot.source.equals(source)) return false;
        synchronized (lock) { if (tail != saved || active != null) return false; }
        log("blocked", "same_gesture_tail"); return true;
    }

    private void requireCurrent(Run run) {
        synchronized (lock) { if (active != run) throw new IllegalStateException("probe_cancelled"); }
        if (SystemClock.uptimeMillis() >= run.expiresAt || !physical.released()
                || physical.releasedElapsedAt() < run.recognitionAt
                || !sameConfig(run.config, run.snapshot)) throw new IllegalStateException("probe_cancelled");
    }
    private boolean sameConfig(RuntimeSnapshot accepted, ScopeCaptureWindow.Snapshot snapshot) {
        try {
            RuntimeSnapshot latest = current.get();
            return valid(latest, snapshot) && accepted != null && accepted.identity.equals(latest.identity);
        } catch (RuntimeException unreadable) { return false; }
    }
    private static boolean valid(RuntimeSnapshot config, ScopeCaptureWindow.Snapshot snapshot) {
        return config != null && snapshot != null && config.user == 0 && config.build == BuildConfig.VERSION_CODE
                && config.enabled && config.scopeLinkProbe && config.sourceScope.managed && config.sourceScope.allows(snapshot.source)
                && snapshot.uid >= 10000 && snapshot.uid < 100000 && snapshot.user == 0 && snapshot.display == 0;
    }
    private void scheduleTimeout(Run run, long delay) {
        synchronized (lock) {
            if (active != run) return;
            if (run.timeout != null) worker.removeCallbacks(run.timeout);
            run.timeout = () -> finish(run, null, "timeout");
            worker.postDelayed(run.timeout, delay);
        }
    }
    private void finishObserved(Run run) {
        String value;
        synchronized (lock) { if (active != run || !run.observed) return; value = run.invalid ? null : run.url; }
        try {
            requireCurrent(run); check.validate(run.snapshot);
            finish(run, value, value == null ? "unsupported_launch" : "captured");
        } catch (ReflectiveOperationException | RuntimeException changed) { finish(run, null, "window_or_configuration_changed"); }
    }
    private void finish(Run run, String url, String reason) {
        Consumer<String> complete;
        synchronized (lock) {
            if (active != run) return;
            active = null; complete = run.complete; run.complete = null; run.url = null;
            if (complete != null && (run.observed || run.tapAttempted) && !List.of("cancelled", "new_touch", "touch_changed", "short_press",
                    "configuration_changed", "native_won", "candidate_changed").contains(reason)) {
                long now = SystemClock.uptimeMillis();
                // An asynchronous click may still launch after capture times out; consume it without reporting a late URL.
                long until = run.tapAttempted && !run.observed
                        ? Math.min(now, run.tapDeadline) + LATE_LAUNCH_MILLIS : now + 400;
                if (until > now) tail = new Tail(run.snapshot, run.config, run.gestureElapsed, until);
            }
            if (!retired.contains(run.snapshot)) retired.add(run.snapshot);
            if (retired.size() > 8) retired.remove(0);
            if (run.timeout != null) worker.removeCallbacks(run.timeout);
        }
        log(url == null ? "finished" : "captured", reason);
        if (complete != null) worker.post(() -> complete.accept(url));
    }
    private void log(String stage, String reason) {
        try { logger.accept("scope link stage=" + stage + " reason=" + reason); }
        catch (RuntimeException ignored) { }
    }

    @SuppressWarnings("deprecation")
    static String strictHttp(Intent intent, String source) {
        try {
            if (!plain(intent)) return null;
            ComponentName component = intent.getComponent();
            if (component != null && CaptureLease.expectedDestination(source,
                    component.getPackageName(), component.getClassName()))
                return ProbePayload.uniqueHttpUrl(intent);
            if (component != null && source.equals(component.getPackageName()))
                return ProbePayload.uniqueSourcePageUrl(intent, source);
            if (Intent.ACTION_CHOOSER.equals(intent.getAction())) {
                if (intent.getData() != null || intent.hasExtra(Intent.EXTRA_INITIAL_INTENTS)
                        || intent.hasExtra("android.intent.extra.ALTERNATE_INTENTS")
                        || intent.hasExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER)) return null;
                Object value = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (!(value instanceof Intent nested) || !plain(nested) || !Intent.ACTION_VIEW.equals(nested.getAction())) return null;
                intent = nested;
            }
            if (!Intent.ACTION_VIEW.equals(intent.getAction())) return null;
            String url = intent.getDataString();
            if (!IntentObservation.validHttpUrl(url)) return null;
            Bundle extras = intent.getExtras();
            if (extras != null) for (String key : new String[]{"url", "rawUrl", Intent.EXTRA_TEXT}) {
                Object candidate = extras.get(key);
                if (candidate instanceof String text && IntentObservation.validHttpUrl(text) && !url.equals(text)) return null;
            }
            return url;
        } catch (RuntimeException unreadable) { return null; }
    }
    private static boolean plain(Intent intent) {
        if (intent == null || intent.getSelector() != null || intent.getClipData() != null || (intent.getFlags() & GRANTS) != 0) return false;
        Bundle extras = intent.getExtras();
        return extras == null || !extras.hasFileDescriptors();
    }

    private static String rejectionReason(Intent intent, String source) {
        try {
            if (!plain(intent)) return "unsafe_intent";
            ComponentName component = intent.getComponent();
            if (component != null && CaptureLease.expectedDestination(source,
                    component.getPackageName(), component.getClassName())) return "web_payload_invalid";
            if (Intent.ACTION_CHOOSER.equals(intent.getAction())) return "chooser_invalid";
            return Intent.ACTION_VIEW.equals(intent.getAction()) ? "http_data_invalid" : "unsupported_action";
        } catch (RuntimeException unreadable) { return "unreadable_payload"; }
    }
}
