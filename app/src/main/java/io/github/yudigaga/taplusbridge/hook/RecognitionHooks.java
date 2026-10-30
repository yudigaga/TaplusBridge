package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import io.github.yudigaga.taplusbridge.probe.HostProbeAction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Host-process-only recognition capture, local recommendation publication, and card presentation. */
public final class RecognitionHooks {
    private static final String TAG = "TaplusBridge";
    private static final String ROOT = "com.miui.contentextension.";
    private final XposedModule module;
    private final Supplier<String> source;
    private final HostRecognitionEnhancer enhancer;
    private final Method appInstance;
    private final Method publish;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object sessionLock = new Object();
    private final ThreadLocal<Boolean> synthetic = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean errorReported = new AtomicBoolean();
    private volatile Boolean supportedHost;
    private long epoch;
    private long scheduledEpoch = -1;
    private boolean active;
    private final AutoProbeReadiness autoProbe;
    private final RecognitionEpochs origins=new RecognitionEpochs();
    private Field recommendationTask,segmentTask,segmenting,ocrDisposable;
    private volatile Object pickService;
    private volatile long lastPickAt;
    private volatile boolean probeGeometry;
    private ArrayList<?> lastServer = new ArrayList<>();

    public static RecognitionHooks install(XposedModule module, ClassLoader loader, Supplier<String> source) {
        try {
            if (module.getApiVersion() != 102) return null;
            RecognitionHooks hooks = new RecognitionHooks(module, loader, source);
            hooks.installHooks(loader); return hooks;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            module.log(5, TAG, "Recognition hooks unavailable: " + failure.getClass().getSimpleName());
            return null;
        }
    }

    private RecognitionHooks(XposedModule module, ClassLoader loader, Supplier<String> source)
            throws ReflectiveOperationException {
        this.module = module;
        this.source = source;
        enhancer = new HostRecognitionEnhancer(loader);
        autoProbe = new AutoProbeReadiness(() -> !enhancer.capturedLinks().isEmpty());
        enhancer.setReporter(message -> module.log(4, TAG, message));
        appInstance = method(loader.loadClass(ROOT + "App"), "getInstance", true);
        publish = method(loader.loadClass(ROOT + "text.RecommendationMonitor"),
                "onDetailReady", true, ArrayList.class);
        requireResult(publish, void.class);
    }

    private void installHooks(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> service = loader.loadClass(ROOT + "services.TextContentExtensionService");
        Class<?> catcher = loader.loadClass("miui.contentcatcher.sdk.ClientCatcherResult");
        hookBoundary(method(service, "onStartPick", false), "pick");
        hookBoundary(method(service, "cancelTask", false), "cancel");
        hookBoundary(method(service, "onDestroy", false), "destroy");
        Method start = method(service, "onStartCommand", false, Intent.class, int.class, int.class);
        requireResult(start, int.class);
        module.hook(start).setId("taplusbridge:recognition-service")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    try {
                        if (contextSupported(chain.getThisObject()) && chain.getArg(0) instanceof Intent intent
                                && intent.hasExtra("clipboard_data")) {
                            resetSession();
                            String text = intent.getStringExtra("clipboard_data");
                            enhancer.captureText(text);
                            enhancer.diagnostic("capture_clipboard", "captured", null,
                                    text == null ? 0 : text.length(), enhancer.capturedLinks().size(), null, null);
                            // Clipboard has its own NER/card path. It cannot reuse an old long-press view.
                        }
                    } catch (RuntimeException failure) { report(failure); }
                    return chain.proceed();
                });
        for (String name : new String[]{"onPickText", "onPickImage"}) {
            Method pick = method(service, name, false, catcher);
            requireResult(pick, void.class);
            module.hook(pick).setId("taplusbridge:recognition-" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        try {
                            if (contextSupported(chain.getThisObject())) {
                                pickService = chain.getThisObject();
                                rememberProbePick(chain.getArg(0), "onPickImage".equals(name));
                                enhancer.capture(chain.getArg(0));
                                enhancer.diagnostic("onPickImage".equals(name) ? "capture_image" : "capture_catcher",
                                        "captured", null, 0, enhancer.capturedLinks().size(), null, null);
                            }
                        } catch (RuntimeException failure) { report(failure); }
                        return chain.proceed();
                    });
        }
        for (String name : new String[]{"startPickTextTask", "startPickImageTask"}) {
            Method task = method(service, name, false);
            requireResult(task, void.class);
            module.hook(task).setId("taplusbridge:recognition-" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        boolean supported = contextSupported(chain.getThisObject());
                        if (supported) synchronized (sessionLock) { active = true; }
                        long origin;
                        synchronized(sessionLock) { origin=epoch; }
                        Object result = supported && Looper.myLooper()==Looper.getMainLooper()
                                ?origins.scoped(origin,chain::proceed):chain.proceed();
                        if (supported) {
                            recordStatus(hostContext());
                            schedulePublish();
                        }
                        return result;
                    });
        }
        Class<?> recommendations = loader.loadClass(ROOT + "data.recognition.TextRecommendationUtils");
        recommendationTask=recommendations.getDeclaredField("sLastRecommendationTask");
        if(!Modifier.isStatic(recommendationTask.getModifiers())
                || !AsyncTask.class.isAssignableFrom(recommendationTask.getType()))
            throw new NoSuchFieldException("recommendation task contract");
        recommendationTask.setAccessible(true);
        segmentTask=recommendations.getDeclaredField("sLastSegmentTask");
        if(!Modifier.isStatic(segmentTask.getModifiers()) || !AsyncTask.class.isAssignableFrom(segmentTask.getType()))
            throw new NoSuchFieldException("segment task contract");
        segmentTask.setAccessible(true);
        segmenting=recommendations.getDeclaredField("sIsSegmentingPassage");
        if(!Modifier.isStatic(segmenting.getModifiers()) || segmenting.getType()!=boolean.class)
            throw new NoSuchFieldException("segment state contract");
        segmenting.setAccessible(true);
        installAsyncCallbacks(loader);
        hookTextCapture(method(recommendations, "getTextSegmentResponse", true,
                Context.class, String.class, boolean.class), 1);
        hookTextCapture(method(recommendations, "tryToGetRecommendation", true,
                Context.class, String.class, String.class), 2);
        // Offline local OCR skips getTextSegmentResponse and goes straight to local segmentation.
        hookTextCapture(method(loader.loadClass(ROOT + "data.recognition.ImageRecognition"),
                "processOcrResult", true, Context.class, String.class), 1);
        module.hook(publish).setId("taplusbridge:recognition-detail")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    ArrayList<?> input = chain.getArg(0) instanceof ArrayList<?> list ? list : null;
                    ArrayList<?> replacement = null;
                    try {
                        Context context = hostContext();
                        if (supported(context) && input != null && isActive() && (synthetic.get() || currentOrigin())) {
                            if (!synthetic.get()) synchronized (sessionLock) {
                                lastServer = new ArrayList<>(input);
                                enhancer.captureRecommendationLinks(input);
                            }
                            replacement = enhancer.merge(context, input, safeSource());
                            enhancer.diagnostic(synthetic.get() ? "publish_local" : "publish_server", "published",
                                    null, input.size(), replacement.size(), null, null);
                        }
                    } catch (RuntimeException failure) { report(failure); }
                    // The original is called once, outside recovery catches, so a host error is never replayed.
                    Object result = replacement == null ? chain.proceed() : chain.proceed(new Object[]{replacement});
                    return result;
                });
        Method segment = method(loader.loadClass(ROOT + "text.RecommendationMonitor"),
                "onSegmentReady", true, ArrayList.class, boolean.class);
        requireResult(segment, void.class);
        module.hook(segment).setId("taplusbridge:recognition-segments")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    Context context = hostContext();
                    if (supported(context) && isActive() && currentOrigin()) {
                        synchronized(sessionLock) { autoProbe.segmentComplete(epoch); }
                        recordStatus(context);
                        schedulePublish();
                        scheduleAutoProbe();
                    }
                    return result;
                });
        installBindings(loader);
        module.log(4, TAG, "Host-only local recognition and destination card hooks installed");
    }

    private void hookBoundary(Method boundary, String name) throws NoSuchMethodException {
        requireResult(boundary, void.class);
        module.hook(boundary).setId("taplusbridge:recognition-boundary-" + name)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (contextSupported(chain.getThisObject())) {
                        resetSession();
                        if ("pick".equals(name)) enhancer.beginPick();
                    }
                    return chain.proceed();
                });
    }

    private void installAsyncCallbacks(ClassLoader loader) throws ReflectiveOperationException {
        installCallback(method(loader.loadClass(ROOT+"data.recognition.TextRecommendationUtils$1"),
                "onPostExecute",false,ArrayList.class),"segment-result");
        installCallback(method(loader.loadClass(ROOT+"data.recognition.TextRecommendationUtils$2"),
                "onPostExecute",false,String.class),"recommendation-result");
        Class<?> image=loader.loadClass(ROOT+"data.recognition.ImageRecognition");
        ocrDisposable=image.getDeclaredField("mOcrDisposable");
        if(!Modifier.isStatic(ocrDisposable.getModifiers())
                || !"io.reactivex.disposables.Disposable".equals(ocrDisposable.getType().getName()))
            throw new NoSuchFieldException("OCR disposable contract");
        ocrDisposable.setAccessible(true);
        Class<?> observer=loader.loadClass(ROOT+"data.recognition.ImageRecognition$1");
        installCallback(method(observer,"onNext",false,loader.loadClass("kotlin.Pair")),"ocr-result");
        installCallback(method(observer,"onError",false,Throwable.class),"ocr-error");
        Method recognize=method(image,"doImageRecognize",true,Bitmap.class);requireResult(recognize,void.class);
        module.hook(recognize).setId("taplusbridge:recognition-ocr-origin")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if(!isActive() || Looper.myLooper()!=Looper.getMainLooper())return chain.proceed();
                    long origin; synchronized(sessionLock) { origin=epoch; }
                    Object before=ocrDisposable.get(null);
                    Object result=origins.scoped(origin,chain::proceed);
                    Object after=ocrDisposable.get(null);
                    // subscribeWith returns this observer; observeOn(main) queues callbacks after return.
                    if(after!=null && after!=before)origins.bind(after,origin);
                    return result;
                });
    }

    private void installCallback(Method callback,String name) throws NoSuchMethodException {
        requireResult(callback,void.class);
        module.hook(callback).setId("taplusbridge:recognition-origin-"+name)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if(isActive() && Looper.myLooper()!=Looper.getMainLooper())return null;
                    return origins.callback(chain.getThisObject(),this::isActive,chain::proceed);
                });
    }

    private boolean currentOrigin() {
        synchronized(sessionLock) { return active && origins.scopeCurrent(epoch); }
    }

    private void hookTextCapture(Method capture, int textIndex) throws NoSuchMethodException {
        requireResult(capture, void.class);
        module.hook(capture).setId("taplusbridge:recognition-text-" + capture.getName())
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    long capturedEpoch=-1, recommendation=-1;
                    String ocrText=null;
                    boolean emptyOcr=false;
                    Field taskField=null;Object previousTask=null;
                    try {
                        if (chain.getArg(0) instanceof Context context && supported(context) && isActive()
                                && Looper.myLooper()==Looper.getMainLooper()) {
                            Object raw=chain.getArg(textIndex);
                            String text=raw instanceof String value?value:null;
                            enhancer.captureText(text);
                            String stage = "processOcrResult".equals(capture.getName()) ? "capture_ocr"
                                    : "getTextSegmentResponse".equals(capture.getName())
                                    ? "capture_segment" : "capture_recommendation";
                            enhancer.diagnostic(stage, "captured", null, text==null?0:text.length(),
                                    enhancer.capturedLinks().size(), null, null);
                            synchronized(sessionLock) {
                                capturedEpoch=epoch;
                                if("tryToGetRecommendation".equals(capture.getName()) && text!=null && !text.isEmpty())
                                    recommendation=autoProbe.recommendationStarted(epoch);
                            }
                            if("tryToGetRecommendation".equals(capture.getName()))taskField=recommendationTask;
                            else if("getTextSegmentResponse".equals(capture.getName()))taskField=segmentTask;
                            if(taskField!=null) {
                                previousTask=taskField.get(null);
                                // Cancelled old tasks do not execute onPostExecute to clear this flag.
                                if(taskField==segmentTask && previousTask!=null && origins.owner(previousTask)!=capturedEpoch)
                                    segmenting.setBoolean(null,false);
                            }
                            emptyOcr="processOcrResult".equals(capture.getName()) && (text==null || text.isEmpty());
                            ocrText=text;
                        }
                    } catch (ReflectiveOperationException|RuntimeException failure) { report(failure); }
                    Object result=capturedEpoch>=0?origins.scoped(capturedEpoch,chain::proceed):chain.proceed();
                    if(taskField!=null && capturedEpoch>=0)try {
                        Object after=taskField.get(null);
                        if(after!=null && after!=previousTask)origins.bind(after,capturedEpoch);
                    } catch(ReflectiveOperationException|RuntimeException failure) { report(failure); }
                    if(recommendation>=0)try {
                        Object pending=recommendationTask.get(null);
                        if(!(pending instanceof AsyncTask<?,?,?> task))throw new IllegalStateException("missing recommendation task");
                        watchRecommendation(capturedEpoch,recommendation,task,SystemClock.elapsedRealtime()+15000);
                    } catch(ReflectiveOperationException|RuntimeException unavailable) { report(unavailable); }
                    if(emptyOcr) {
                        autoProbe.localOcrComplete(capturedEpoch,ocrText);
                        scheduleAutoProbe();
                    }
                    return result;
                });
    }

    /** FINISHED is actual AsyncTask completion, including null/error responses with no detail event. */
    @SuppressWarnings("deprecation")
    private void watchRecommendation(long pickedEpoch,long taskGeneration,AsyncTask<?,?,?> task,long deadline) {
        synchronized(sessionLock) {
            if(!active || epoch!=pickedEpoch || !autoProbe.awaitingRecommendation(pickedEpoch,taskGeneration))return;
        }
        if(task.getStatus()==AsyncTask.Status.FINISHED) {
            if(autoProbe.recommendationFinished(pickedEpoch,taskGeneration))scheduleAutoProbe();
        } else if(SystemClock.elapsedRealtime()<deadline) {
            main.postDelayed(() -> watchRecommendation(pickedEpoch,taskGeneration,task,deadline),32);
        } else module.log(4,TAG,"probe stage=recognition_wait reason=no_completion; input_not_requested=true");
    }

    private void installBindings(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> baseCard = loader.loadClass(ROOT + "text.cardview.TaplusRecommendBaseCard");
        Class<?> model = loader.loadClass(ROOT + "data.recognition.TextRecommendationInfo");
        Class<?> entity = loader.loadClass(ROOT + "data.recognition.Entity");
        Class<?> device = loader.loadClass(ROOT + "clipboard.model.DeviceInfo");
        installBinding(method(baseCard, "bindRecommendInfo", false, model), 1);
        installBinding(method(baseCard, "bindNerInfo", false, entity), 2);
        installBinding(method(baseCard, "bindDeviceInfo", false, device), 0);
        Class<?> icon = loader.loadClass(ROOT + "view.SourceIcon");
        Method image = method(ImageView.class, "setImageDrawable", false, Drawable.class);
        requireResult(image, void.class);
        module.hook(image).setId("taplusbridge:recognition-app-icon")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Drawable replacement = null;
                    if (icon.isInstance(chain.getThisObject())) replacement = enhancer.iconFor(chain.getThisObject());
                    if (replacement == null) return chain.proceed();
                    Object result = chain.proceed(new Object[]{replacement});
                    enhancer.iconDisplayed(chain.getThisObject());
                    return result;
                });
    }

    private void installBinding(Method binding, int kind) throws NoSuchMethodException {
        requireResult(binding, void.class);
        module.hook(binding).setId("taplusbridge:recognition-card-" + binding.getName())
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object card = chain.getThisObject();
                    boolean supported = card instanceof View view && supported(view.getContext());
                    if (kind != 0) enhancer.diagnostic(kind == 1 ? "bind_recommend" : "bind_ner",
                            supported ? "reached" : "unsupported", null, 0, 0, null, null);
                    if (supported) enhancer.beforeBind(card);
                    Object result = chain.proceed();
                    if (supported) try {
                        if (kind == 1) enhancer.bindRecommendation(card, chain.getArg(0), safeSource());
                        else if (kind == 2) enhancer.bindNer(card, chain.getArg(0), null);
                    } catch (RuntimeException failure) { report(failure); }
                    return result;
                });
    }

    private void resetSession() {
        HostProbeAction.cancelActive();
        synchronized (sessionLock) {
            epoch++;
            origins.reset(epoch);
            active = false;
            scheduledEpoch = -1;
            lastServer = new ArrayList<>();
            enhancer.clear();
            probeGeometry = false; lastPickAt = 0;
            autoProbe.reset(epoch); pickService = null;
        }
        try {
            RecognitionTaskCleanup.reset(segmentTask,recommendationTask,ocrDisposable,segmenting);
        } catch(ReflectiveOperationException|RuntimeException failure) { report(failure); }
    }

    private boolean isActive() { synchronized (sessionLock) { return active; } }

    private void rememberProbePick(Object result, boolean image) {
        probeGeometry = false; lastPickAt = 0;
        try {
            Object params = result.getClass().getMethod("getParams", String.class)
                    .invoke(result, image ? "image_pick" : "text_pick");
            Object payload = params instanceof Map<?, ?> map ? map.get(image ? "image_pick_mode" : "pick_mode") : null;
            if (payload instanceof Bundle bundle && bundle.getParcelable("view_rect") instanceof Rect rect && !rect.isEmpty()) {
                probeGeometry = true; lastPickAt = SystemClock.elapsedRealtime();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    public long probeEpoch() { synchronized (sessionLock) { return active && probeGeometry ? epoch : -1; } }
    public long pickedAt() { return probeGeometry ? lastPickAt : -1; }
    public boolean currentProbe(long expectedEpoch, String expectedSource) {
        synchronized (sessionLock) { return active && epoch == expectedEpoch
                && expectedSource != null && expectedSource.equals(safeSource()); }
    }
    public boolean acceptProbeUrl(long expectedEpoch, String expectedSource, String url) {
        if (!currentProbe(expectedEpoch, expectedSource)) return false;
        enhancer.captureProbeUrl(url); recordStatus(hostContext()); schedulePublish(); return true;
    }
    public boolean isProbeRecommendation(Object info) { return enhancer.isProbeRecommendation(info); }

    private void scheduleAutoProbe() {
        final long pickedEpoch;
        synchronized (sessionLock) {
            if (!active || pickService == null || !autoProbe.ready(epoch)
                    || !HostCaptureAction.eligible(safeSource())) return;
            pickedEpoch = epoch;
        }
        main.post(() -> {
            String origin = safeSource();
            Context context = hostContext();
            Object service = pickService;
            if (context == null || service == null || !currentProbe(pickedEpoch, origin)) return;
            synchronized (sessionLock) {
                if (!autoProbe.ready(pickedEpoch)) return;
            }
            try {
                Bundle request = new Bundle(); request.putString("source", origin);
                Bundle available = io.github.yudigaga.taplusbridge.runtime.BridgeTransport.call(context,"capture_available",request);
                if (available == null || !available.getBoolean("enabled")) return;
                synchronized (sessionLock) {
                    if (!currentProbe(pickedEpoch, origin) || !autoProbe.claim(pickedEpoch)) return;
                }
                HostProbeAction.start(context, service, origin, pickedAt(),
                        () -> currentProbe(pickedEpoch, origin) && autoProbe.current(pickedEpoch),
                        url -> acceptProbeUrl(pickedEpoch, origin, url));
            } catch (RuntimeException failure) { report(failure); }
        });
    }

    private void schedulePublish() {
        final long capturedEpoch;
        synchronized (sessionLock) {
            if (!active || enhancer.capturedLinks().isEmpty()
                    || scheduledEpoch == epoch) return;
            capturedEpoch = epoch;
            scheduledEpoch = capturedEpoch;
        }
        // Local cards are published only after the task/segment listeners have created their views.
        main.post(() -> {
            ArrayList<?> server;
            synchronized (sessionLock) {
                if (scheduledEpoch == capturedEpoch) scheduledEpoch = -1;
                if (!active || epoch != capturedEpoch) return;
                server = new ArrayList<>(lastServer);
            }
            Context context = hostContext();
            if (!supported(context)) return;
            recordStatus(context);
            synthetic.set(true);
            try { publish.invoke(null, server); }
            catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
            finally { synthetic.remove(); }
        });
    }

    private Context hostContext() {
        try { return appInstance.invoke(null) instanceof Context context ? context : null; }
        catch (ReflectiveOperationException | RuntimeException failure) { return null; }
    }

    private boolean contextSupported(Object object) {
        return object instanceof Context context && supported(context);
    }

    private boolean supported(Context context) {
        if (context == null) return false;
        Boolean cached = supportedHost;
        if (cached != null) return cached;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(BridgeContract.HOST_PACKAGE, 0);
            boolean result = HostPolicy.supportedVersion(info.versionName, info.getLongVersionCode());
            supportedHost = result;
            return result;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException failure) {
            report(failure);
            return false;
        }
    }

    private String safeSource() {
        try { return source == null ? null : source.get(); }
        catch (RuntimeException failure) { return null; }
    }

    private void recordStatus(Context context) {
        if (context == null) return;
        try {
            Bundle counts = new Bundle();
            counts.putInt("links", enhancer.capturedLinks().size());
            io.github.yudigaga.taplusbridge.runtime.BridgeTransport.call(context,"recognition_status",counts);
        } catch (RuntimeException failure) { report(failure); }
    }

    private void report(Throwable failure) {
        if (errorReported.compareAndSet(false, true))
            module.log(5, TAG, "Recognition enhancement preserved host flow: " + failure.getClass().getSimpleName());
    }

    private static Method method(Class<?> type, String name, boolean isStatic, Class<?>... params)
            throws NoSuchMethodException {
        Method result = type.getDeclaredMethod(name, params);
        if (Modifier.isStatic(result.getModifiers()) != isStatic)
            throw new NoSuchMethodException("Unexpected host method kind");
        result.setAccessible(true);
        return result;
    }

    private static void requireResult(Method method, Class<?> expected) throws NoSuchMethodException {
        if (method.getReturnType() != expected) throw new NoSuchMethodException("Unexpected host result type");
    }
}
