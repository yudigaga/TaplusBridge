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
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel;
import io.github.yudigaga.taplusbridge.runtime.RuntimeClient;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.github.yudigaga.taplusbridge.probe.HostProbeAction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Host-process-only recognition capture, local recommendation publication, and card presentation. */
public final class RecognitionHooks {
    private static final String TAG = "TaplusBridge";
    private static final String ROOT = "com.miui.contentextension.";
    private static volatile RecognitionHooks installed;
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
    private DragPick fallbackDeadlinePick;
    private long fallbackRecognitionDeadline;
    private boolean active;
    private final AutoProbeReadiness autoProbe;
    private final RecognitionEpochs origins=new RecognitionEpochs();
    private Field recommendationTask,segmentTask,segmenting,ocrDisposable;
    private Field hostResult, hostFinished;
    private Method requestRecommendation, requestSegments, recognizeImage, imageBitmap, recommendationUrl, publishSegments;
    private Method cloudOcrWords;
    private String scopeLinkUrl, scopeText;
    private boolean mixedImage, mixedPrepared;
    private final ProbeRecommendations scopeRecommendations = new ProbeRecommendations();
    private volatile Object pickService;
    private DragPick dragPick;
    private volatile long lastPickAt;
    private volatile boolean probeGeometry;
    private boolean keepRecognitionVisible;
    private long recognitionOnlyEpoch = -1;
    private long recommendationGeneration = -1;
    private boolean ocrFinished;
    private String ocrText = "";
    private ArrayList<?> lastServer = new ArrayList<>();
    private HostClipboardLinks clipboardLinks;

    record DragPick(long epoch, Object service, Object result, long pickedAt) {
        boolean matches(Object expectedService, Object expectedResult, long expectedEpoch) {
            return service == expectedService && result == expectedResult && (expectedEpoch < 0 || epoch == expectedEpoch);
        }
    }
    record DragSnapshot(long epoch, boolean waiting, List<String> links) {
        DragSnapshot {
            links = List.copyOf(links.stream().filter(io.github.yudigaga.taplusbridge.recognition.RouteLink::valid)
                    .limit(io.github.yudigaga.taplusbridge.recognition.LinkExtractor.MAX_RESULTS)
                    .collect(java.util.stream.Collectors.toList()));
        }
    }

    /** Read only the exact live pick; a completed or replaced pick cannot supply another drag's links. */
    DragSnapshot dragSnapshot(Object service, Object result, long expectedEpoch) {
        boolean serverEnabled = RecognitionPolicy.read(hostContext()).enabled(Channel.SERVER);
        synchronized (sessionLock) {
            if (!active || dragPick == null || !dragPick.matches(service, result, expectedEpoch)) return null;
            boolean waiting = false;
            try {
                Object task = recommendationTask == null ? null : recommendationTask.get(null);
                waiting = serverEnabled && pendingRecommendation(task, origins.owner(task), epoch);
            } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
            return new DragSnapshot(epoch, waiting, enhancer.capturedLinks());
        }
    }

    boolean nativeUrlAvailable(Object service, Object result) {
        synchronized (sessionLock) {
            return dragPick != null && dragPick.matches(service, result, -1) && !enhancer.capturedLinks().isEmpty();
        }
    }

    boolean ocrComplete(Object service, Object result, long expectedEpoch) {
        synchronized (sessionLock) {
            return active && dragPick != null && dragPick.matches(service, result, expectedEpoch) && ocrFinished;
        }
    }

    String ocrText(Object service, Object result, long expectedEpoch) {
        synchronized (sessionLock) {
            return active && ocrFinished && dragPick != null && dragPick.matches(service, result, expectedEpoch) ? ocrText : "";
        }
    }

    private void rememberOcrText(String text) {
        synchronized (sessionLock) {
            if (active && origins.scopeCurrent(epoch)) ocrText = RecognitionPolicy.boundedText(text);
        }
    }

    @SuppressWarnings("deprecation")
    static boolean pendingRecommendation(Object task, long owner, long epoch) {
        return owner == epoch && task instanceof AsyncTask<?, ?, ?> pending && pending.getStatus() != AsyncTask.Status.FINISHED;
    }

    public static RecognitionHooks install(XposedModule module, ClassLoader loader, Supplier<String> source) {
        try {
            if (module.getApiVersion() != 102) return null;
            RecognitionHooks hooks = new RecognitionHooks(module, loader, source);
            hooks.installHooks(loader); installed = hooks; return hooks;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            module.log(5, TAG, "Recognition hooks unavailable: " + failure.getClass().getSimpleName());
            return null;
        }
    }

    private RecognitionHooks(XposedModule module, ClassLoader loader, Supplier<String> source)
            throws ReflectiveOperationException {
        this(module, source, new HostRecognitionEnhancer(loader),
                method(loader.loadClass(ROOT + "App"), "getInstance", true),
                method(loader.loadClass(ROOT + "text.RecommendationMonitor"), "onDetailReady", true, ArrayList.class));
    }

    RecognitionHooks(XposedModule module, Supplier<String> source, HostRecognitionEnhancer enhancer,
                     Method appInstance, Method publish) throws NoSuchMethodException {
        this.module = module;
        this.source = source;
        this.enhancer = enhancer;
        autoProbe = new AutoProbeReadiness(() -> !enhancer.capturedLinks().isEmpty());
        if (module != null) enhancer.setReporter(message -> module.log(4, TAG, message));
        this.appInstance = appInstance;
        this.publish = publish;
        requireResult(publish, void.class);
    }

    private void installHooks(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> service = loader.loadClass(ROOT + "services.TextContentExtensionService");
        Class<?> catcher = loader.loadClass("miui.contentcatcher.sdk.ClientCatcherResult");
        hostResult = service.getDeclaredField("mCatcherResult");
        hostFinished = service.getDeclaredField("sIsTaskFinished");
        if (!Modifier.isStatic(hostResult.getModifiers()) || hostResult.getType() != catcher
                || !Modifier.isStatic(hostFinished.getModifiers()) || hostFinished.getType() != boolean.class)
            throw new NoSuchFieldException("scope_link_active_pick_contract");
        hostResult.setAccessible(true); hostFinished.setAccessible(true);
        hookBoundary(method(service, "onStartPick", false), "pick");
        hookBoundary(method(service, "cancelTask", false), "cancel");
        hookBoundary(method(service, "onDestroy", false), "destroy");
        Method otherMode = method(service, "isOtherMode", true);
        requireResult(otherMode, boolean.class);
        module.hook(otherMode).setId("taplusbridge:recognition-mixed-entries")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    synchronized (sessionLock) { if (active && mixedImage) return true; }
                    return chain.proceed();
                });
        Method start = method(service, "onStartCommand", false, Intent.class, int.class, int.class);
        requireResult(start, int.class);
        module.hook(start).setId("taplusbridge:recognition-service")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    try {
                        if (contextSupported(chain.getThisObject()) && chain.getArg(0) instanceof Intent intent
                                && intent.hasExtra("clipboard_data")) {
                            RecognitionPolicy.State policy = RecognitionPolicy.current((Context) chain.getThisObject());
                            String text = intent.getStringExtra("clipboard_data");
                            if (clipboardLinks != null) clipboardLinks.begin(chain.getThisObject(), text);
                            if (policy.disabled(Channel.CLIPBOARD))
                                return android.app.Service.START_NOT_STICKY;
                            // Clipboard has its own NER/card path. It cannot reuse an old long-press view.
                        }
                    } catch (RuntimeException failure) { report(failure); }
                    Object outcome = chain.proceed();
                    try {
                        if (clipboardLinks != null && chain.getArg(0) instanceof Intent intent && intent.hasExtra("clipboard_data"))
                            clipboardLinks.afterStart(chain.getThisObject());
                    } catch (RuntimeException failure) { report(failure); }
                    return outcome;
                });
        for (String name : new String[]{"onPickText", "onPickImage"}) {
            Method pick = method(service, name, false, catcher);
            requireResult(pick, void.class);
            module.hook(pick).setId("taplusbridge:recognition-" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        boolean emptyNativeText = false;
                        try {
                            if (contextSupported(chain.getThisObject())) {
                                RecognitionPolicy.State policy = RecognitionPolicy.current((Context) chain.getThisObject());
                                RecognitionChannels channels = policy.channels();
                                if ("onPickText".equals(name) && policy.disabled(Channel.NATIVE)
                                        && !RecognitionPolicy.marked(chain.getArg(0), "taplus_drag")) {
                                    try {
                                        RecognitionPolicy.filterText(chain.getArg(0), policy);
                                        String accepted = RecognitionPolicy.nativeText(chain.getArg(0), channels);
                                        emptyNativeText = accepted == null || accepted.isBlank();
                                    } catch (ReflectiveOperationException | RuntimeException unavailable) {
                                        report(unavailable); return null;
                                    }
                                }
                                synchronized (sessionLock) {
                                    pickService = chain.getThisObject();
                                    dragPick = new DragPick(epoch, pickService, chain.getArg(0), SystemClock.elapsedRealtime());
                                    fallbackDeadlinePick = null; fallbackRecognitionDeadline = 0;
                                }
                                captureScopeContent(chain.getArg(0), "onPickImage".equals(name), channels);
                                rememberProbePick(chain.getArg(0), "onPickImage".equals(name));
                                enhancer.capture(chain.getArg(0), channels);
                                enhancer.diagnostic("onPickImage".equals(name) ? "capture_image" : "capture_catcher",
                                        "captured", null, 0, enhancer.capturedLinks().size(), null, null);
                            }
                        } catch (RuntimeException failure) { report(failure); }
                        Object outcome = chain.proceed();
                        if (emptyNativeText) {
                            // The stock text consumer returns before task creation for an empty filtered pick.
                            synchronized (sessionLock) { active = true; autoProbe.segmentComplete(epoch); }
                            recordStatus(hostContext()); schedulePublish(); scheduleAutoProbe();
                        }
                        return outcome;
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
                            if ("startPickImageTask".equals(name)) prepareMixedImage(origin);
                            RecognitionPolicy.State policy = RecognitionPolicy.current(hostContext());
                            if (policy.disabled(Channel.SEGMENT) || "startPickImageTask".equals(name) && policy.disabled(Channel.OCR))
                                completeDisabled(Channel.SEGMENT);
                            recordStatus(hostContext());
                            schedulePublish();
                        }
                        return result;
                    });
        }
        Class<?> recommendations = loader.loadClass(ROOT + "data.recognition.TextRecommendationUtils");
        requestRecommendation = method(recommendations, "tryToGetRecommendation", true, Context.class, String.class, String.class);
        requestSegments = method(recommendations, "getTextSegmentResponse", true, Context.class, String.class, boolean.class);
        recognizeImage = method(loader.loadClass(ROOT + "data.recognition.ImageRecognition"), "doImageRecognize", true, Bitmap.class);
        imageBitmap = method(service, "getBitmap", true);
        recommendationUrl = method(loader.loadClass(ROOT + "data.http.HostConfig"), "getUrl", true, int.class);
        publishSegments = method(loader.loadClass(ROOT + "text.RecommendationMonitor"), "onSegmentReady", true, ArrayList.class, boolean.class);
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
        try {
            cloudOcrWords = method(recommendations, "parseWordListFormServer", true, String.class);
            if (!List.class.isAssignableFrom(cloudOcrWords.getReturnType())) throw new NoSuchMethodException("OCR words contract");
        } catch (ReflectiveOperationException unavailable) { cloudOcrWords = null; }
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
                        if (supported(context) && input != null) {
                            RecognitionPolicy.State policy = RecognitionPolicy.current(context);
                            boolean server = policy.channels().enabled(Channel.SERVER);
                            if (policy.disabled(Channel.SERVER)) {
                                input = new ArrayList<>();
                                replacement = input;
                                synchronized (sessionLock) { lastServer = new ArrayList<>(); }
                            }
                            if (isActive() && (synthetic.get() || currentOrigin())) {
                                if (!synthetic.get() && server) synchronized (sessionLock) {
                                    enhancer.captureRecommendationLinks(input);
                                    input = scopeLinkUrl == null ? input : scopeRecommendations.merge(input, enhancer::isProbeRecommendation);
                                    lastServer = new ArrayList<>(input);
                                }
                                replacement = synthetic.get() || recognitionReadyForCurrentPick()
                                        ? enhancer.merge(context, input, safeSource()) : new ArrayList<>(input);
                                enhancer.diagnostic(synthetic.get() ? "publish_local" : "publish_server", "published",
                                        null, input.size(), replacement.size(), null, null);
                            }
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
                    Context context = hostContext();
                    boolean enabled = !supported(context) || !RecognitionPolicy.current(context).disabled(Channel.SEGMENT);
                    Object result = enabled ? chain.proceed() : chain.proceed(new Object[]{new ArrayList<>(), true});
                    if (supported(context) && isActive() && currentOrigin()) {
                        synchronized(sessionLock) { autoProbe.segmentComplete(epoch); }
                        recordStatus(context);
                        schedulePublish();
                        scheduleAutoProbe();
                    }
                    return result;
                });
        installBindings(loader);
        installClipboardCommerce(loader);
        module.log(4, TAG, "Host-only local recognition and destination card hooks installed");
    }

    private void hookBoundary(Method boundary, String name) throws NoSuchMethodException {
        requireResult(boundary, void.class);
        module.hook(boundary).setId("taplusbridge:recognition-boundary-" + name)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (contextSupported(chain.getThisObject())) {
                        boolean preserveRecognition = false;
                        if ("pick".equals(name)) synchronized (sessionLock) {
                            preserveRecognition = keepRecognitionVisible;
                            keepRecognitionVisible = false;
                        }
                        resetSession();
                        if (preserveRecognition) synchronized (sessionLock) { recognitionOnlyEpoch = epoch; }
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
                    Context context = hostContext();
                    if (supported(context) && RecognitionPolicy.current(context).disabled(Channel.OCR)) {
                        completeDisabled(Channel.OCR); return null;
                    }
                    if(!isActive() || Looper.myLooper()!=Looper.getMainLooper())return chain.proceed();
                    long origin; synchronized(sessionLock) { origin=epoch; ocrFinished=false; ocrText=""; }
                    Object before=ocrDisposable.get(null);
                    Object result=origins.scoped(origin,chain::proceed);
                    Object after=ocrDisposable.get(null);
                    // subscribeWith returns this observer; observeOn(main) queues callbacks after return.
                    if(after!=null && after!=before)origins.bind(after,origin);
                    return result;
                });
        try {
            Class<?> splash = loader.loadClass(ROOT + "text.floatview.TaplusSplashFloatView");
            Method talkBack = method(splash, "talkBackForImageIfNeed", false);
            requireResult(talkBack, void.class);
            Field context = HostRecognitionEnhancer.findField(splash, "mContext");
            module.hook(talkBack).setId("taplusbridge:recognition-ocr-talkback")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object raw = context.get(chain.getThisObject());
                        if (raw instanceof Context owner && supported(owner)
                                && RecognitionPolicy.current(owner).disabled(Channel.OCR)) return null;
                        return chain.proceed();
                    });
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(4, TAG, "Optional OCR talkback boundary unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    private void installCallback(Method callback,String name) throws NoSuchMethodException {
        requireResult(callback,void.class);
        module.hook(callback).setId("taplusbridge:recognition-origin-"+name)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Channel channel = name.startsWith("ocr-") ? Channel.OCR
                            : "segment-result".equals(name) ? Channel.SEGMENT : Channel.SERVER;
                    Context context = hostContext();
                    RecognitionPolicy.State policy = supported(context) ? RecognitionPolicy.current(context) : null;
                    if (policy != null && policy.disabled(channel)) {
                        completeDisabled(channel); return null;
                    }
                    if(isActive() && Looper.myLooper()!=Looper.getMainLooper())return null;
                    return origins.callback(chain.getThisObject(),this::isActive,() -> {
                        if (isActive() && "ocr-result".equals(name) && policy != null
                                && policy.channels().enabled(Channel.OCR)) captureCloudOcr(chain.getArg(0));
                        Object result = chain.proceed();
                        if (channel == Channel.OCR && isActive()) {
                            synchronized (sessionLock) {
                                ocrFinished = true;
                                if ("ocr-error".equals(name)) autoProbe.localOcrComplete(epoch, null);
                            }
                            schedulePublish(); scheduleAutoProbe();
                        }
                        return result;
                    });
                });
    }

    private void captureCloudOcr(Object pair) {
        if (pair == null || cloudOcrWords == null) return;
        try {
            if (!Boolean.FALSE.equals(pair.getClass().getMethod("getFirst").invoke(pair))) return;
            Object body = pair.getClass().getMethod("getSecond").invoke(pair);
            if (!(body instanceof String json) || json.length() > ScopeCapturePayload.MAX_CONTENT_LENGTH) return;
            Object raw = cloudOcrWords.invoke(null, json);
            if (!(raw instanceof List<?> words)) return;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < Math.min(words.size(), 4096); i++) {
                Object word = words.get(i);
                if (word == null) continue;
                Object value = word.getClass().getMethod("getWord").invoke(word);
                if (!(value instanceof String part) || text.length() + part.length() + 1 > ScopeCapturePayload.MAX_CONTENT_LENGTH) break;
                if (text.length() > 0) text.append(' ');
                text.append(part);
            }
            String recognized = text.toString();
            rememberOcrText(recognized);
            enhancer.captureText(recognized, "ocr");
        } catch (ReflectiveOperationException | RuntimeException unavailable) { report(unavailable); }
    }

    private void installClipboardCommerce(ClassLoader loader) {
        try {
            clipboardLinks = HostClipboardLinks.install(module, loader, this::supported, this::report);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(4, TAG, "Optional clipboard commerce boundary unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    private boolean currentOrigin() {
        synchronized(sessionLock) { return active && origins.scopeCurrent(epoch); }
    }

    private void hookTextCapture(Method capture, int textIndex) throws NoSuchMethodException {
        requireResult(capture, void.class);
        module.hook(capture).setId("taplusbridge:recognition-text-" + capture.getName())
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Channel channel = "processOcrResult".equals(capture.getName()) ? Channel.OCR
                            : "getTextSegmentResponse".equals(capture.getName()) ? Channel.SEGMENT : Channel.SERVER;
                    RecognitionPolicy.State policy = chain.getArg(0) instanceof Context owner && supported(owner)
                            ? RecognitionPolicy.current(owner) : null;
                    if (policy != null && policy.disabled(channel)) {
                        completeDisabled(channel); return null;
                    }
                    long capturedEpoch=-1, recommendation=-1;
                    String ocrText=null;
                    boolean emptyOcr=false;
                    Field taskField=null;Object previousTask=null;
                    try {
                        if (chain.getArg(0) instanceof Context context && supported(context) && isActive()
                                && Looper.myLooper()==Looper.getMainLooper()) {
                            Object raw=chain.getArg(textIndex);
                            String text=raw instanceof String value?value:null;
                            if (channel == Channel.OCR && policy != null && policy.channels().enabled(Channel.OCR)) {
                                rememberOcrText(text);
                                enhancer.captureText(text, "ocr");
                            }
                            String stage = "processOcrResult".equals(capture.getName()) ? "capture_ocr"
                                    : "getTextSegmentResponse".equals(capture.getName())
                                    ? "capture_segment" : "capture_recommendation";
                            enhancer.diagnostic(stage, "captured", null, text==null?0:text.length(),
                                    enhancer.capturedLinks().size(), null, null);
                            synchronized(sessionLock) {
                                capturedEpoch=epoch;
                                if("tryToGetRecommendation".equals(capture.getName()) && text!=null && !text.isEmpty())
                                    recommendation=recommendationGeneration=autoProbe.recommendationStarted(epoch);
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
                    Object[] arguments = null;
                    if (capturedEpoch >= 0 && "tryToGetRecommendation".equals(capture.getName())) {
                        String link;
                        synchronized (sessionLock) { link = scopeLinkUrl; }
                        String original = chain.getArg(textIndex) instanceof String value ? value : null;
                        String merged = recommendationText(original, link);
                        if (!java.util.Objects.equals(original, merged)) {
                            arguments = new Object[capture.getParameterCount()];
                            for (int i = 0; i < arguments.length; i++) arguments[i] = chain.getArg(i);
                            arguments[textIndex] = merged;
                        }
                    }
                    Object[] replacement = arguments;
                    Object result=capturedEpoch>=0?origins.scoped(capturedEpoch,
                            () -> replacement == null ? chain.proceed() : chain.proceed(replacement)):chain.proceed();
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
                    if (channel == Channel.OCR && capturedEpoch >= 0) synchronized (sessionLock) {
                        if (epoch == capturedEpoch) ocrFinished = true;
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
            recommendationGeneration = -1;
            ocrFinished = false;
            ocrText = "";
            enhancer.clear();
            scopeLinkUrl = scopeText = null; mixedImage = mixedPrepared = false;
            scopeRecommendations.clear();
            probeGeometry = false; lastPickAt = 0;
            autoProbe.reset(epoch); pickService = null; dragPick = null;
            fallbackDeadlinePick = null; fallbackRecognitionDeadline = 0;
        }
        try {
            RecognitionTaskCleanup.reset(segmentTask,recommendationTask,ocrDisposable,segmenting);
        } catch(ReflectiveOperationException|RuntimeException failure) { report(failure); }
    }

    private boolean isActive() { synchronized (sessionLock) { return active; } }

    private void captureScopeContent(Object result, boolean image, RecognitionChannels channels) {
        try {
            Object raw = result.getClass().getMethod("getPropertyMap").invoke(result);
            if (!(raw instanceof Map<?, ?> map)) return;
            String url = map.get("taplus_scope_link_url") instanceof String value
                    && ScopeCapturePayload.validTextUrl(value) ? value : null;
            String text = RecognitionPolicy.nativeText(result, channels);
            if (text != null && text.length() > 131072) text = text.substring(0, 131072);
            synchronized (sessionLock) {
                scopeLinkUrl = url; scopeText = text;
                mixedImage = image && Boolean.TRUE.equals(map.get("taplus_scope_mixed"));
                mixedPrepared = false;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
    }

    /** Disabled stages still release the host's splash/selection barrier without starting source work. */
    private void completeDisabled(Channel channel) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> completeDisabled(channel)); return;
        }
        long origin;
        synchronized (sessionLock) {
            if (!active) return;
            origin = epoch;
            if (channel == Channel.SERVER) {
                autoProbe.recommendationFinished(origin, recommendationGeneration);
                recommendationGeneration = -1;
                lastServer = new ArrayList<>();
            } else {
                if (channel == Channel.OCR) ocrFinished = true;
                autoProbe.segmentComplete(origin);
            }
        }
        if (channel != Channel.SERVER && segmenting != null) try { segmenting.setBoolean(null, false); }
        catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
        if (channel != Channel.SERVER && publishSegments != null) try {
            origins.scoped(origin, () -> { publishSegments.invoke(null, new ArrayList<>(), true); return null; });
        } catch (Throwable failure) { report(failure); }
        schedulePublish(); scheduleAutoProbe();
    }

    /** The native image, text segmentation, OCR and URL cards share one task and one pair of views. */
    private void prepareMixedImage(long origin) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> prepareMixedImage(origin)); return; }
        String url, text;
        synchronized (sessionLock) {
            if (!active || epoch != origin || !mixedImage || mixedPrepared) return;
            mixedPrepared = true; url = scopeLinkUrl; text = scopeText;
        }
        Context context = hostContext();
        if (!supported(context)) return;
        try {
            origins.scoped(origin, () -> {
                // Splash waits for a segment event even for URL-only images; real OCR replaces this empty state later.
                publishSegments.invoke(null, new ArrayList<>(), true);
                String endpoint = (String) recommendationUrl.invoke(null, 1);
                if (text != null && !text.isEmpty()) {
                    requestRecommendation.invoke(null, context, endpoint, text);
                    requestSegments.invoke(null, context, text, false);
                } else {
                    if (url != null) requestRecommendation.invoke(null, context, endpoint, url);
                    Object bitmap = imageBitmap.invoke(null);
                    if (bitmap instanceof Bitmap image && !image.isRecycled()) recognizeImage.invoke(null, image);
                }
                return null;
            });
        } catch (Throwable failure) { report(failure); }
    }

    static String recommendationText(String original, String url) {
        if (!ScopeCapturePayload.validTextUrl(url) || url.equals(original)
                || io.github.yudigaga.taplusbridge.recognition.LinkExtractor.extract(original).contains(url)) return original;
        return original == null || original.isEmpty() ? url : original + "\n" + url;
    }

    /** Only actual cards for this probe URL survive a later OCR response in the same pick. */
    static final class ProbeRecommendations {
        private final java.util.LinkedHashMap<String, Object> saved = new java.util.LinkedHashMap<>();
        void clear() { saved.clear(); }
        ArrayList<?> merge(ArrayList<?> incoming, java.util.function.Predicate<Object> probeCard) {
            ArrayList<Object> result = new ArrayList<>();
            java.util.HashSet<String> existing = new java.util.HashSet<>();
            for (Object value : incoming) {
                String key = key(value);
                boolean probe = probeCard.test(value), first = existing.add(key);
                if (!probe || first) result.add(value);
                if (probe && saved.size() < 32) saved.put(key, value);
            }
            for (var entry : saved.entrySet()) if (existing.add(entry.getKey())) result.add(entry.getValue());
            return result;
        }
        private String key(Object value) {
            if (value == null) return "null";
            try {
                StringBuilder result = new StringBuilder(value.getClass().getName());
                for (String name : new String[]{"getDetailUrl", "getPackageName", "getIntent", "getCategory"}) {
                    Object field = value.getClass().getMethod(name).invoke(value);
                    String text = String.valueOf(field);
                    result.append('|').append(text.length()).append(':').append(text);
                }
                return result.toString();
            } catch (ReflectiveOperationException | RuntimeException unsupported) {
                return value.getClass().getName() + '@' + System.identityHashCode(value);
            }
        }
    }

    private void rememberProbePick(Object result, boolean image) {
        probeGeometry = false; lastPickAt = 0;
        synchronized (sessionLock) { if (mixedImage || scopeLinkUrl != null) return; }
        try {
            Object params = result.getClass().getMethod("getParams", String.class)
                    .invoke(result, image ? "image_pick" : "text_pick");
            Object payload = params instanceof Map<?, ?> map ? map.get(image ? "image_pick_mode" : "pick_mode") : null;
            // A window screenshot is not proof of a card rectangle; never synthesize a source click from it.
            if (payload instanceof Bundle captured && (captured.getBoolean("taplus_scope_capture")
                    || captured.getBoolean("taplus_drag"))) return;
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

    static long scopeEpoch(Object service, Object result) {
        RecognitionHooks hooks = installed;
        if (hooks == null) return -1;
        synchronized (hooks.sessionLock) {
            return hooks.dragPick != null && hooks.dragPick.matches(service, result, hooks.epoch) ? hooks.epoch : -1;
        }
    }

    static boolean supplementScopeLink(Context context, Object service, HostScopeCaptureHooks.PendingNative.Picks originals,
                                       String source, String identity, String url) {
        RecognitionHooks hooks = installed;
        return hooks != null && hooks.acceptScopeLink(context, service, originals, source, identity, url);
    }

    private boolean currentScopeLinkPolicy(Context context, String identity) {
        if (context == null || identity == null) return false;
        Bundle policy = RuntimeClient.call(context, "source_scope", Bundle.EMPTY);
        return policy != null && policy.getBoolean("scope_link_probe_enabled") && identity.equals(policy.getString("identity"));
    }

    private boolean sameScopePick(Object service, HostScopeCaptureHooks.PendingNative.Picks originals, String expectedSource)
            throws IllegalAccessException {
        return active && dragPick != null && dragPick.epoch() == epoch && pickService == service
                && dragPick.matches(service, dragPick.result(), epoch) && expectedSource != null && expectedSource.equals(safeSource())
                && hostResult != null && hostFinished != null && hostResult.get(null) == dragPick.result()
                && originals != null && originals.epochFor(dragPick.result()) == epoch;
    }

    /** A busy host ignores another callback; enrich only its exact still-active original pick instead. */
    boolean acceptScopeLink(Context context, Object service, HostScopeCaptureHooks.PendingNative.Picks originals,
                            String expectedSource, String identity, String url) {
        if (Looper.myLooper() != Looper.getMainLooper() || context == null || originals == null
                || !ScopeCapturePayload.validTextUrl(url) || !HostPolicy.validPackage(expectedSource)) return false;
        DragPick selected;
        try {
            if (!currentScopeLinkPolicy(context, identity)) return false;
            synchronized (sessionLock) {
                selected = dragPick;
                if (!sameScopePick(service, originals, expectedSource) || hostFinished.getBoolean(null)) return false;
                enhancer.captureProbeUrl(url);
                scopeLinkUrl = url;
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
        try { HostDragHooks.offerRecognizedLinks(service, selected.result(), enhancer.capturedLinks()); recordStatus(context); }
        catch (RuntimeException unavailable) { report(unavailable); }
        schedulePublish(); return true;
    }
    public boolean isProbeRecommendation(Object info) { return enhancer.isProbeRecommendation(info); }

    void keepRecognitionVisibleForNextPick() {
        synchronized (sessionLock) { keepRecognitionVisible = true; }
    }

    private void scheduleAutoProbe() {
        final long pickedEpoch;
        final Object nativeResult;
        synchronized (sessionLock) {
            if (!active || epoch == recognitionOnlyEpoch || !probeGeometry || pickService == null || !autoProbe.ready(epoch)
                    || !enhancer.capturedLinks().isEmpty() || !HostCaptureAction.eligible(safeSource())) return;
            pickedEpoch = epoch;
            nativeResult = dragPick != null && dragPick.epoch == epoch ? dragPick.result : null;
        }
        main.post(() -> {
            String origin = safeSource();
            Context context = hostContext();
            Object service = pickService;
            if (context == null || service == null || !currentProbe(pickedEpoch, origin)) return;
            if (nativeUrlAvailable(service, nativeResult)) return;
            long recognitionAt = HostDragHooks.recognitionDeadline(service, nativeResult);
            synchronized (sessionLock) {
                if (!autoProbe.ready(pickedEpoch)) return;
            }
            try {
                Bundle request = new Bundle(); request.putString("source", origin);
                Bundle available = io.github.yudigaga.taplusbridge.runtime.BridgeTransport.call(context,"capture_available",request);
                if (available == null || !available.getBoolean("enabled")) return;
                if (recognitionAt <= 0) {
                    long pickedAt = pickedAt();
                    if (pickedAt <= 0) return;
                    recognitionAt = pickedAt + available.getInt("recognition_delay",
                            io.github.yudigaga.taplusbridge.config.DragSettings.DEFAULT_RECOGNITION_DELAY);
                }
                final long deadline = recognitionAt;
                long wait = deadline - SystemClock.elapsedRealtime();
                if (wait > 0) {
                    main.postDelayed(() -> {
                        if (currentProbe(pickedEpoch, origin)) scheduleAutoProbe();
                    }, wait);
                    return;
                }
                if (!enhancer.capturedLinks().isEmpty()) return;
                synchronized (sessionLock) {
                    if (!currentProbe(pickedEpoch, origin) || !autoProbe.claim(pickedEpoch)) return;
                }
                HostProbeAction.start(context, service, origin, pickedAt(), deadline,
                        () -> currentProbe(pickedEpoch, origin) && autoProbe.current(pickedEpoch)
                                && !nativeUrlAvailable(service, nativeResult),
                        () -> HostDragHooks.dismissPreviewForLinkProbe(service, nativeResult, origin),
                        url -> acceptProbeUrl(pickedEpoch, origin, url),
                        () -> {
                            if (!currentProbe(pickedEpoch, origin)) return;
                            HostDragHooks.offerRecognizedLinks(service, nativeResult, enhancer.capturedLinks());
                            HostDragHooks.nativeProbeCompleted(service, nativeResult);
                        });
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
            synchronized (sessionLock) {
                if (!active || epoch != capturedEpoch) {
                    if (scheduledEpoch == capturedEpoch) scheduledEpoch = -1;
                    return;
                }
            }
            DragPick selected;
            synchronized (sessionLock) { selected = dragPick; }
            long recognitionAt = selected == null ? 0 : recognitionDeadline(selected);
            if (selected != null && recognitionAt <= 0) {
                synchronized (sessionLock) { if (scheduledEpoch == capturedEpoch) scheduledEpoch = -1; }
                return;
            }
            long wait = recognitionAt - SystemClock.elapsedRealtime();
            if (wait > 0) {
                main.postDelayed(() -> {
                    synchronized (sessionLock) { if (scheduledEpoch == capturedEpoch) scheduledEpoch = -1; }
                    schedulePublish();
                }, wait);
                return;
            }
            ArrayList<?> server;
            synchronized (sessionLock) {
                if (scheduledEpoch == capturedEpoch) scheduledEpoch = -1;
                if (!active || epoch != capturedEpoch || dragPick != selected) return;
                server = new ArrayList<>(lastServer);
            }
            Context context = hostContext();
            if (!supported(context)) return;
            if (selected != null && !enhancer.capturedLinks().isEmpty())
                HostDragHooks.offerRecognizedLinks(selected.service(), selected.result(), enhancer.capturedLinks());
            recordStatus(context);
            synthetic.set(true);
            try { publish.invoke(null, server); }
            catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
            finally { synthetic.remove(); }
        });
    }

    private boolean recognitionReadyForCurrentPick() {
        DragPick selected;
        synchronized (sessionLock) { selected = dragPick; }
        if (selected == null) return true;
        long deadline = recognitionDeadline(selected);
        return deadline > 0 && io.github.yudigaga.taplusbridge.drag.LongPressStageSchedule
                .isReady(deadline, SystemClock.elapsedRealtime());
    }

    private long recognitionDeadline(DragPick selected) {
        if (selected == null) return 0;
        long providedAt = providedRecognitionAt(selected.result(), selected.pickedAt());
        if (providedAt > 0) return providedAt;
        long cachedFallback;
        synchronized (sessionLock) {
            if (!active || dragPick != selected) return 0;
            cachedFallback = fallbackDeadlinePick == selected ? fallbackRecognitionDeadline : 0;
        }
        long nativeDeadline = HostDragHooks.recognitionDeadline(selected.service(), selected.result());
        if (nativeDeadline > 0) return nativeDeadline;
        if (cachedFallback > 0) return cachedFallback;
        Context context = hostContext();
        if (context == null || selected.pickedAt() < 0) return 0;
        try {
            Bundle request = new Bundle(); request.putString("source", safeSource());
            Bundle available = io.github.yudigaga.taplusbridge.runtime.BridgeTransport.call(context,
                    "capture_available", request);
            if (available == null) return 0;
            long fallback = io.github.yudigaga.taplusbridge.drag.LongPressStageSchedule.resolveRecognitionAt(
                    0, selected.pickedAt(), available.getInt("recognition_delay", -1));
            if (fallback <= 0) return 0;
            synchronized (sessionLock) {
                if (active && dragPick == selected) {
                    fallbackDeadlinePick = selected; fallbackRecognitionDeadline = fallback;
                } else return 0;
            }
            return fallback;
        } catch (RuntimeException failure) { report(failure); return 0; }
    }

    /** Framework-owned scope/drag delivery has already passed the physical recognition deadline. */
    static long providedRecognitionAt(Object result, long pickedAt) {
        if (result == null || pickedAt <= 0) return 0;
        try {
            for (String job : new String[]{"text_pick", "image_pick"}) {
                Object raw = result.getClass().getMethod("getParams", String.class).invoke(result, job);
                Object content = raw instanceof Map<?, ?> map ? map.get("image_pick".equals(job) ? "image_pick_mode" : "pick_mode") : null;
                if (content instanceof Bundle data && (data.getBoolean("taplus_scope_capture") || data.getBoolean("taplus_drag")))
                    return pickedAt;
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { }
        return 0;
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
        if (module != null && errorReported.compareAndSet(false, true))
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
