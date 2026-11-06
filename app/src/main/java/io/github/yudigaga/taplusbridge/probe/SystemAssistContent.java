package io.github.yudigaga.taplusbridge.probe;

import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.BuildConfig;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.hook.ScopeCapturePayload;
import io.github.yudigaga.taplusbridge.recognition.LinkExtractor;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional framework assist data for one already qualified source gesture; never injects input. */
public final class SystemAssistContent {
    static final int MAX_TEXT = 131072, MAX_LINKS = 8;
    static final long RESPONSE_TIMEOUT = 2000, RESULT_TTL = 30000;

    public record Result(String text, List<String> links) {
        public static final Result EMPTY = new Result("", List.of());
        public Result {
            text = bounded(text == null ? "" : text.trim(), MAX_TEXT);
            LinkedHashSet<String> accepted = new LinkedHashSet<>();
            if (links != null) for (String link : links) {
                if (ScopeCapturePayload.validTextUrl(link)) accepted.add(link);
                if (accepted.size() >= MAX_LINKS) break;
            }
            for (String link : LinkExtractor.extract(text)) {
                if (accepted.size() >= MAX_LINKS) break;
                if (ScopeCapturePayload.validTextUrl(link)) accepted.add(link);
            }
            links = List.copyOf(accepted);
        }
        public String deliveryText() {
            String body = text, tail = "";
            // Reserving a hidden href can trim a visible URL; recompute exact URL de-duplication.
            for (int pass = 0; pass <= MAX_LINKS; pass++) {
                List<String> visible = LinkExtractor.extract(body);
                StringBuilder missing = new StringBuilder();
                for (String link : links) {
                    if (visible.contains(link)) continue;
                    if (missing.length() + link.length() + (missing.length() == 0 ? 0 : 1) > MAX_TEXT) break;
                    if (missing.length() > 0) missing.append('\n');
                    missing.append(link);
                }
                tail = missing.toString();
                String trimmed = bounded(text, MAX_TEXT - tail.length() - (tail.isEmpty() ? 0 : 1));
                if (body.equals(trimmed)) break;
                body = trimmed;
            }
            return body + (body.isEmpty() || tail.isEmpty() ? "" : "\n") + tail;
        }
        private static String bounded(String value, int limit) {
            if (limit <= 0) return "";
            if (value.length() <= limit) return value;
            int end = limit;
            if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
            return value.substring(0, end);
        }
    }

    interface Backend {
        boolean request(ScopeCaptureWindow.Snapshot target, Consumer<Bundle> receiver) throws ReflectiveOperationException;
    }
    interface WindowCheck { void validate(ScopeCaptureWindow.Snapshot target) throws ReflectiveOperationException; }
    interface Extractor { Result extract(AssistStructure structure, ScopeCaptureWindow.Snapshot target); }

    private static final class Run {
        final ScopeCaptureWindow.Snapshot target;
        final RuntimeSnapshot config;
        final long down, began, recognitionAt;
        final int device;
        boolean requested, complete;
        long deadline, expires;
        Result result = Result.EMPTY;
        Run(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long down, long began, long recognitionAt, int device) {
            this.target = target; this.config = config; this.down = down; this.began = began; this.recognitionAt = recognitionAt;
            this.device = device;
        }
    }

    private final Handler worker;
    private final Supplier<RuntimeSnapshot> current;
    private final WindowCheck check;
    private final Backend backend;
    private final Extractor extractor;
    private final Executor reader;
    private final Consumer<String> logger;
    private final PhysicalTouchState physical;
    private final Object lock = new Object();
    private Run active;

    public SystemAssistContent(Handler worker, Supplier<Context> context, Supplier<Object> atms,
                               Supplier<RuntimeSnapshot> current, ScopeCaptureWindow windows, Consumer<String> logger) {
        this(worker, context, atms, current, windows, logger, null);
    }

    public SystemAssistContent(Handler worker, Supplier<Context> context, Supplier<Object> atms,
                               Supplier<RuntimeSnapshot> current, ScopeCaptureWindow windows, Consumer<String> logger,
                               PhysicalTouchState physical) {
        this(worker, current, target -> validateWindow(context.get(), atms.get(), windows, target),
                reflectedBackend(atms, windows, logger), AssistContentExtractor::extract, boundedReader(), logger, physical);
    }

    SystemAssistContent(Handler worker, Supplier<RuntimeSnapshot> current, WindowCheck check, Backend backend,
                        Extractor extractor, Executor reader, Consumer<String> logger, PhysicalTouchState physical) {
        this.worker = Objects.requireNonNull(worker); this.current = Objects.requireNonNull(current);
        this.check = Objects.requireNonNull(check); this.backend = backend;
        this.extractor = Objects.requireNonNull(extractor); this.reader = Objects.requireNonNull(reader);
        this.logger = Objects.requireNonNull(logger); this.physical = physical;
    }

    public void arm(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long downUptime,
                    long beganElapsed, long recognitionAt) {
        arm(target, config, downUptime, beganElapsed, recognitionAt, -1);
    }

    public void arm(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long downUptime,
                    long beganElapsed, long recognitionAt, int device) {
        long now = SystemClock.elapsedRealtime(), uptime = SystemClock.uptimeMillis();
        if (backend == null || !valid(target, config) || downUptime < 0 || downUptime > uptime
                || beganElapsed < 0 || beganElapsed > now || now - beganElapsed > RESULT_TTL
                || recognitionAt < beganElapsed || recognitionAt - beganElapsed > RESULT_TTL
                || physical != null && (device < 0 || !physical.soleStream(0, device, downUptime))) return;
        Run run;
        synchronized (lock) {
            if (matchesDown(active, target, config, downUptime) && active.device == device) return;
            if (active != null) active.result = Result.EMPTY;
            active = run = new Run(target, config, downUptime, beganElapsed, recognitionAt, device);
        }
        worker.postDelayed(() -> request(run), Math.max(0, recognitionAt - now));
        worker.postDelayed(() -> expire(run), Math.max(0, beganElapsed + RESULT_TTL - now));
    }

    public boolean pending(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long beganElapsed) {
        synchronized (lock) { return matches(active, target, config, beganElapsed) && live(active) && !active.complete; }
    }
    public boolean pending(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long beganElapsed, long downUptime) {
        synchronized (lock) {
            return matchesDown(active, target, config, downUptime) && live(active) && !active.complete;
        }
    }

    public Result read(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long beganElapsed) {
        return readMatching(target, config, beganElapsed, -1);
    }
    public Result read(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long beganElapsed, long downUptime) {
        return downUptime < 0 ? Result.EMPTY : readMatching(target, config, beganElapsed, downUptime);
    }
    private Result readMatching(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long beganElapsed, long downUptime) {
        Run run;
        synchronized (lock) {
            run = active;
            if (!matchesCaller(run, target, config, beganElapsed, downUptime) || !live(run) || !run.complete) return Result.EMPTY;
        }
        try { check.validate(target); }
        catch (ReflectiveOperationException | RuntimeException changed) {
            synchronized (lock) { if (active == run) { active.result = Result.EMPTY; active = null; } }
            log("window_changed"); return Result.EMPTY;
        }
        synchronized (lock) { return active == run && live(run) ? run.result : Result.EMPTY; }
    }

    /** A cancelled or short physical gesture must not issue its delayed assist request. */
    public void cancelPending(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long beganElapsed) {
        synchronized (lock) { if (matches(active, target, config, beganElapsed) && !active.requested) active = null; }
    }
    public void cancelPending(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long beganElapsed, long downUptime) {
        synchronized (lock) {
            if (matchesDown(active, target, config, downUptime) && !active.requested) active = null;
        }
    }
    public void cancel() { synchronized (lock) { if (active != null) active.result = Result.EMPTY; active = null; } }

    private void request(Run run) {
        synchronized (lock) { if (active != run || run.requested || !live(run)) return; }
        try {
            check.validate(run.target);
            synchronized (lock) {
                if (active != run || !live(run)) return;
                run.requested = true; run.deadline = SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT;
                run.expires = Math.min(run.began + RESULT_TTL, SystemClock.elapsedRealtime() + RESULT_TTL);
            }
            worker.postDelayed(() -> timeout(run), RESPONSE_TIMEOUT);
            if (!backend.request(run.target, data -> receive(run, data))) finish(run, Result.EMPTY, "unavailable");
            else log("requested");
        } catch (ReflectiveOperationException | RuntimeException unavailable) { finish(run, Result.EMPTY, "unavailable"); }
    }

    private void receive(Run run, Bundle data) {
        synchronized (lock) {
            if (active != run || run.complete || !live(run) || data == null) return;
        }
        try {
            reader.execute(() -> {
                try {
                    synchronized (lock) { if (active != run || run.complete || !live(run)) return; }
                    check.validate(run.target);
                    Object raw = data.getParcelable("structure");
                    if (!(raw instanceof AssistStructure structure)) { finish(run, Result.EMPTY, "empty"); return; }
                    ComponentName component = structure.getActivityComponent();
                    if (component == null || !run.target.source.equals(component.getPackageName())
                            || !run.target.activity.equals(component.getClassName())) {
                        finish(run, Result.EMPTY, "identity_changed"); return;
                    }
                    Result result = extractor.extract(structure, run.target);
                    check.validate(run.target);
                    finish(run, result == null ? Result.EMPTY : result, "received");
                } catch (ReflectiveOperationException | RuntimeException invalid) { finish(run, Result.EMPTY, "unavailable"); }
            });
        } catch (RuntimeException busy) { finish(run, Result.EMPTY, "reader_busy"); }
    }

    private void timeout(Run run) {
        synchronized (lock) { if (active != run || run.complete) return; }
        finish(run, Result.EMPTY, "timeout");
    }
    private void expire(Run run) {
        synchronized (lock) {
            run.result = Result.EMPTY;
            if (active == run) active = null;
        }
    }
    private void finish(Run run, Result result, String stage) {
        synchronized (lock) {
            if (active != run || run.complete) return;
            if (!live(run) && !"timeout".equals(stage)) result = Result.EMPTY;
            if ("received".equals(stage) && result.deliveryText().isBlank() && result.links().isEmpty()) stage = "empty";
            run.complete = true; run.result = result;
        }
        log(stage);
    }
    private boolean live(Run run) {
        long now = SystemClock.elapsedRealtime();
        return valid(run.target, run.config) && physicalMatches(run) && run.began <= now && now - run.began < RESULT_TTL
                && (!run.requested || run.complete ? run.expires == 0 || now <= run.expires : now <= run.deadline);
    }
    private boolean physicalMatches(Run run) {
        return physical == null || run.device >= 0 && !physical.unavailable()
                && (physical.soleStream(0, run.device, run.down) || physical.released()
                    && physical.releasedAt() >= run.down && physical.releasedElapsedAt() >= run.recognitionAt);
    }
    private boolean valid(ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config) {
        RuntimeSnapshot latest = current.get();
        return target != null && config != null && latest != null && config.enabled && config.recognition
                && config.channels.enabled(io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel.ASSIST)
                && config.build == BuildConfig.VERSION_CODE && config.user == 0 && config.sourceScope.managed
                && config.identity.equals(latest.identity) && config.sourceScope.allows(target.source)
                && target.user == 0 && target.display == 0 && target.uid >= 10000 && target.uid < 100000
                && !HostPolicy.HOST_PACKAGE.equals(target.source) && !HostPolicy.MODULE_PACKAGE.equals(target.source);
    }
    private static boolean matches(Run run, ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long began) {
        return run != null && config != null && run.began == began && run.config.identity.equals(config.identity)
                && run.target.sameTarget(target);
    }
    private static boolean matchesDown(Run run, ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config, long down) {
        return run != null && config != null && down >= 0 && run.down == down
                && run.config.identity.equals(config.identity) && run.target.sameTarget(target);
    }
    private static boolean matchesCaller(Run run, ScopeCaptureWindow.Snapshot target, RuntimeSnapshot config,
                                         long began, long down) {
        // Independent observers convert the same DOWN to elapsed time at slightly different instants.
        return down < 0 ? matches(run, target, config, began) : matchesDown(run, target, config, down);
    }
    private void log(String stage) { logger.accept("assist capture stage=" + stage); }

    private static Executor boundedReader() {
        // A framework lazy parcel may stall; at most two background readers can remain blocked.
        return new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread thread = new Thread(r, "TaplusBridge-assist-content"); thread.setDaemon(true); return thread;
        });
    }
    private static void validateWindow(Context context, Object atms, ScopeCaptureWindow windows,
                                       ScopeCaptureWindow.Snapshot target) throws ReflectiveOperationException {
        try { windows.validate(atms, target); }
        catch (IllegalStateException changed) {
            if (!"capture_focus".equals(changed.getMessage()) || context == null) throw changed;
            try {
                PackageInfo host = context.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE,
                        PackageManager.MATCH_DISABLED_COMPONENTS);
                if (host.applicationInfo == null || !host.applicationInfo.enabled
                        || !HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode())) throw changed;
                windows.validateBehindHost(atms, target, host.applicationInfo.uid);
            } catch (PackageManager.NameNotFoundException missing) { throw changed; }
        }
    }
    private static Backend reflectedBackend(Supplier<Object> atms, ScopeCaptureWindow windows, Consumer<String> logger) {
        AtomicBoolean unavailableReported = new AtomicBoolean();
        return (target, accept) -> {
            try {
                Object service = atms.get();
                if (service == null) throw new ReflectiveOperationException("assist_service_missing");
                Class<?> receiverType = Class.forName("android.app.IAssistDataReceiver", false, service.getClass().getClassLoader());
                Method request = service.getClass().getMethod("requestAssistContextExtras", int.class, receiverType,
                        Bundle.class, IBinder.class, boolean.class, boolean.class);
                if (request.getReturnType() != boolean.class) throw new NoSuchMethodException("assist_request_contract");
                request.setAccessible(true);
                Binder binder = new Binder();
                Object receiver = Proxy.newProxyInstance(receiverType.getClassLoader(), new Class<?>[]{receiverType},
                        (proxy, method, arguments) -> {
                            return switch (method.getName()) {
                                case "asBinder" -> binder;
                                case "onHandleAssistData" -> {
                                    if (arguments != null && arguments.length == 1 && arguments[0] instanceof Bundle data) accept.accept(data);
                                    yield null;
                                }
                                case "onHandleAssistScreenshot" -> null;
                                case "equals" -> proxy == (arguments == null || arguments.length == 0 ? null : arguments[0]);
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> "TaplusBridgeAssistReceiver";
                                default -> null;
                            };
                        });
                long identity = Binder.clearCallingIdentity();
                try { return Boolean.TRUE.equals(request.invoke(service, 1, receiver, Bundle.EMPTY,
                        windows.assistActivityToken(target), true, false)); }
                finally { Binder.restoreCallingIdentity(identity); }
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                if (unavailableReported.compareAndSet(false, true)) logger.accept("assist capture stage=unavailable");
                return false;
            }
        };
    }
}
