package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.recognition.LinkExtractor;
import io.github.yudigaga.taplusbridge.runtime.BridgeTransport;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns only the current clipboard UI session, separately from the long-press recognition epoch. */
final class HostClipboardLinks {
    record Policy(String identity, boolean clipboard, boolean server) { }
    interface Host {
        Context context(Object view);
        Object service(Object view);
        String content();
        boolean clipboardView(Object view);
        boolean finishing(Object view);
        default boolean ready(Object view) { return true; }
        default Object currentView(Object service) { return null; }
        void replace(Object view, List<?> recommendations);
    }
    private final HostRecognitionEnhancer enhancer;
    private final Host host;
    private final Supplier<Policy> policy;
    private long generation;
    private Session session;
    private Object ownedTask;
    private long ownedGeneration;
    private volatile boolean managed;
    private String rejectedFingerprint;
    private long rejectedUntil;
    private Consumer<Context> onBegin = ignored -> { };
    private static final class Session {
        final Object service;
        final String text, identity;
        final long generation;
        Object view;
        List<?> server = List.of(), displayed;
        Session(Object service, String text, String identity, long generation) {
            this.service = service; this.text = text; this.identity = identity; this.generation = generation;
        }
    }

    HostClipboardLinks(HostRecognitionEnhancer enhancer, Host host, Supplier<Policy> policy) {
        this.enhancer = enhancer; this.host = host; this.policy = policy;
    }

    synchronized void begin(Object service, String text) {
        if (service instanceof Context context) onBegin.accept(context);
        clear(); generation++;
        Policy current = current();
        managed = current != null;
        if (current == null || !current.clipboard() || service == null || text == null || text.isBlank()
                || text.length() > LinkExtractor.MAX_INPUT_LENGTH) return;
        if (rejectedFingerprint != null && SystemClock.elapsedRealtime() < rejectedUntil
                && rejectedFingerprint.equals(RuntimeSnapshot.digest(text))) { managed = false; return; }
        rejectedFingerprint = null;
        session = new Session(service, text, current.identity(), generation);
        enhancer.captureText(text, "clipboard");
        enhancer.diagnostic("capture_clipboard", "captured", null, text.length(), enhancer.capturedLinks().size(), null, null);
    }

    synchronized void shown(Object view) {
        Policy current = current();
        if (!validPolicy(current)) { clear(); return; }
        Session selected = session;
        if (selected == null || view == null || !host.clipboardView(view) || host.finishing(view) || !host.ready(view)
                || host.service(view) != selected.service || !Objects.equals(selected.text, host.content())) return;
        if (selected.view != null && selected.view != view) return;
        selected.view = view;
        publish(selected, current);
    }

    synchronized void afterStart(Object service) {
        if (session == null || session.service != service) return;
        Object currentView = host.currentView(service);
        if (currentView != null) shown(currentView);
    }

    synchronized void changed() { if (session != null && !validPolicy(current())) clear(); }

    synchronized void ownTask(Object task) {
        if (session == null || task == null || !validPolicy(current())) return;
        ownedTask = task; ownedGeneration = session.generation;
    }

    synchronized List<?> server(Object task, List<?> models) {
        Policy current = current();
        if (!validPolicy(current)) { clear(); return List.of(); }
        if (session == null || task == null || task != ownedTask || ownedGeneration != session.generation) return List.of();
        List<?> accepted = current.server() && models != null ? new ArrayList<>(models) : List.of();
        session.server = accepted;
        if (session.view != null) publish(session, current);
        return accepted;
    }

    synchronized void destroyed(Object owner) {
        if (session != null && (session.service == owner || session.view == owner)) clear();
    }
    synchronized List<String> links() { return enhancer.capturedLinks(); }

    synchronized List<?> ner(Context context, String text, int type, List<?> original) {
        if (type != 2 || !validPolicy(current()) || session == null || !Objects.equals(session.text, text)) return original;
        Object view = session.view == null ? host.currentView(session.service) : session.view;
        if (view == null || !host.clipboardView(view) || host.finishing(view) || host.service(view) != session.service
                || !Objects.equals(host.content(), text)) return original;
        LinkedHashSet<String> covered = new LinkedHashSet<>();
        for (Object model : enhancer.mergeClipboard(context, List.of())) covered.addAll(HostRecognitionEnhancer.batchLinks(model));
        return !enhancer.capturedLinks().isEmpty() && covered.containsAll(enhancer.capturedLinks()) ? List.of() : original;
    }

    private Policy current() {
        try {
            Policy value = policy.get();
            return value == null || value.identity() == null || value.identity().isBlank() || value.identity().length() > 128 ? null : value;
        } catch (RuntimeException unavailable) { return null; }
    }
    private boolean validPolicy(Policy value) {
        return value != null && value.clipboard() && session != null && session.identity.equals(value.identity());
    }
    private void publish(Session selected, Policy current) {
        Object view = selected.view;
        if (session != selected || view == null || host.finishing(view) || host.service(view) != selected.service
                || !Objects.equals(selected.text, host.content())) return;
        List<?> originals = current.server() ? selected.server : List.of();
        if (selected.displayed != null && selected.displayed.equals(originals)) return;
        Context context = host.context(view);
        ArrayList<Object> merged = enhancer.mergeClipboard(context, originals);
        if (!validPolicy(current()) || session != selected || !Objects.equals(selected.text, host.content())) return;
        host.replace(view, merged);
        selected.displayed = new ArrayList<>(originals);
        enhancer.diagnostic("publish_local", "published", null, originals.size(), merged.size(), null, null);
    }
    private void clear() {
        Session old = session;
        session = null; ownedTask = null; ownedGeneration = -1;
        enhancer.clear();
        if (old != null && old.view != null && !host.finishing(old.view)) host.replace(old.view, List.of());
    }

    static HostClipboardLinks install(XposedModule module, ClassLoader loader, Predicate<Context> supported,
                                      Consumer<Throwable> report) throws ReflectiveOperationException {
        ReflectionHost host = new ReflectionHost(loader);
        HostRecognitionEnhancer enhancer = new HostRecognitionEnhancer(loader);
        enhancer.setReporter(message -> module.log(4, "TaplusBridge", message));
        Method appInstance = method(loader.loadClass("com.miui.contentextension.App"), "getInstance");
        Supplier<Context> application = () -> {
            try { return appInstance.invoke(null) instanceof Context value ? value : null; }
            catch (ReflectiveOperationException unavailable) { return null; }
        };
        HostClipboardLinks links = new HostClipboardLinks(enhancer, host, () -> readPolicy(application.get(), supported));
        Handler main = new Handler(Looper.getMainLooper());
        BroadcastReceiver settings = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                links.changed(); main.postDelayed(links::changed, 150);
            }
        };
        AtomicBoolean settingsRegistered = new AtomicBoolean();
        links.onBegin = owner -> {
            if (!settingsRegistered.compareAndSet(false, true)) return;
            Context context = owner.getApplicationContext() == null ? owner : owner.getApplicationContext();
            try {
                ContextCompat.registerReceiver(context, settings,
                        new IntentFilter(ConfigStore.SOURCE_SCOPE_CHANGED), ContextCompat.RECEIVER_EXPORTED);
            } catch (RuntimeException unavailable) { settingsRegistered.set(false); report.accept(unavailable); }
        };
        Method show = method(host.viewType, "showClipboardFloatView");
        module.hook(show).setId("taplusbridge:clipboard-local-ready")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    try { if (supported.test(host.context(chain.getThisObject()))) links.shown(chain.getThisObject()); }
                    catch (RuntimeException failure) { report.accept(failure); }
                    return result;
                });
        for (Class<?> owner : new Class<?>[]{host.viewType, host.serviceType}) {
            Method destroy = method(owner, "onDestroy");
            module.hook(destroy).setId("taplusbridge:clipboard-local-destroy-" + owner.getSimpleName())
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        links.destroyed(chain.getThisObject()); return chain.proceed();
                    });
        }
        module.hook(method(host.serviceType, "onStartPick")).setId("taplusbridge:clipboard-before-pick")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    links.destroyed(chain.getThisObject()); return chain.proceed();
                });
        Class<?> cardUtils = loader.loadClass("com.miui.contentextension.clipboard.utils.ClipboardCardUtils");
        Method request = method(cardUtils, "getCommerceRecommendWithAsyncTask", Context.class, String.class, String.class);
        module.hook(request).setId("taplusbridge:recognition-clipboard-commerce-request")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Context owner = chain.getArg(0) instanceof Context value ? value : null;
                    Policy current = owner != null && supported.test(owner) ? links.current() : null;
                    if (current != null && (!current.clipboard() || !current.server())) return null;
                    Object result = chain.proceed();
                    if (current != null) links.ownTask(host.task());
                    return result;
                });
        Method ready = method(loader.loadClass("com.miui.contentextension.clipboard.ClipboardMonitor"), "onCopyRecommendReady", List.class);
        module.hook(ready).setId("taplusbridge:recognition-clipboard-commerce-result")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (!links.managed) return chain.proceed();
                    Object task = host.task();
                    List<?> cards = chain.getArg(0) instanceof List<?> values ? new ArrayList<>(values) : List.of();
                    if (Looper.myLooper() == Looper.getMainLooper()) links.server(task, cards);
                    else main.post(() -> links.server(task, cards));
                    return null;
                });
        Method entities = method(loader.loadClass("com.miui.contentextension.clipboard.utils.ClipboardUtils"),
                "generateNerEntityListByType", Context.class, String.class, int.class);
        module.hook(entities).setId("taplusbridge:clipboard-local-url-entities")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!(chain.getArg(0) instanceof Context owner) || !supported.test(owner)
                            || !(result instanceof List<?> values)) return result;
                    return links.ner(owner, chain.getArg(1) instanceof String value ? value : null, (Integer) chain.getArg(2), values);
                });
        Class<?> receiver = loader.loadClass("com.miui.contentextension.clipboard.ClipboardReceiver");
        Method segment = method(receiver, "segmentClipboardContentIfNeed", String.class);
        module.hook(segment).setId("taplusbridge:clipboard-complete-text")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    String text = chain.getArg(0) instanceof String value ? value : null;
                    Policy current = links.current();
                    if (current != null && current.clipboard() && text != null && text.length() <= LinkExtractor.MAX_INPUT_LENGTH) return text;
                    Object result = chain.proceed();
                    if (text != null && text.length() > LinkExtractor.MAX_INPUT_LENGTH && result instanceof String shortened) {
                        synchronized (links) {
                            links.rejectedFingerprint = RuntimeSnapshot.digest(shortened);
                            links.rejectedUntil = SystemClock.elapsedRealtime() + 10_000;
                        }
                    }
                    return result;
                });
        return links;
    }

    private static Policy readPolicy(Context context, Predicate<Context> supported) {
        if (context == null || !supported.test(context)) return null;
        try {
            Bundle result = BridgeTransport.call(context, RecognitionChannels.KEY, Bundle.EMPTY);
            if (result == null || !result.getBoolean("active") || !(result.get("identity") instanceof String identity)
                    || !(result.get("channels") instanceof String raw)) return null;
            RecognitionChannels channels = RecognitionChannels.parse(raw);
            return new Policy(identity, channels.enabled(RecognitionChannels.Channel.CLIPBOARD), channels.enabled(RecognitionChannels.Channel.SERVER));
        } catch (RuntimeException unavailable) { return null; }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method value = type.getDeclaredMethod(name, parameters); value.setAccessible(true); return value;
    }

    private static final class ReflectionHost implements Host {
        final Class<?> viewType, serviceType;
        final Field service, context, clipboard, finishing, cards, task, inflated, splash;
        final Method content, publish;
        ReflectionHost(ClassLoader loader) throws ReflectiveOperationException {
            viewType = loader.loadClass("com.miui.contentextension.text.floatview.TaplusSplashFloatView");
            serviceType = loader.loadClass("com.miui.contentextension.services.TextContentExtensionService");
            service = HostRecognitionEnhancer.findField(viewType, "mService");
            context = HostRecognitionEnhancer.findField(viewType, "mContext");
            clipboard = HostRecognitionEnhancer.findField(viewType, "mIsClipboardView");
            finishing = HostRecognitionEnhancer.findField(viewType, "mIsFinishing");
            cards = HostRecognitionEnhancer.findField(viewType, "mRecommendCards");
            inflated = HostRecognitionEnhancer.findField(viewType, "mIsViewStubInflated");
            splash = HostRecognitionEnhancer.findField(serviceType, "mSplashFloatView");
            task = HostRecognitionEnhancer.findField(loader.loadClass("com.miui.contentextension.clipboard.utils.ClipboardCardUtils"), "mLastCopyRecommendTask");
            content = method(serviceType, "getClipboardContent");
            publish = method(viewType, "onCopyRecommendDeviceReady", List.class);
        }
        public Context context(Object view) { return (Context) read(context, view); }
        public Object service(Object view) { return read(service, view); }
        public String content() { try { return (String) content.invoke(null); } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); } }
        public boolean clipboardView(Object view) { return Boolean.TRUE.equals(read(clipboard, view)); }
        public boolean finishing(Object view) { return Boolean.TRUE.equals(read(finishing, view)); }
        public boolean ready(Object view) { return Boolean.TRUE.equals(read(inflated, view)); }
        public Object currentView(Object owner) { return read(splash, owner); }
        Object task() { return read(task, null); }
        public void replace(Object view, List<?> recommendations) {
            Object values = read(cards, view);
            if (!(values instanceof List<?> previous)) throw new IllegalStateException("clipboard_cards_contract");
            for (Object value : new ArrayList<>(previous)) if (value instanceof View card) {
                card.animate().cancel();
                if (card.getParent() instanceof ViewGroup group) group.removeView(card);
            }
            previous.clear();
            try { publish.invoke(view, recommendations); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        private static Object read(Field field, Object owner) {
            try { return field.get(owner); } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }
    }
}
