package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.runtime.RuntimeClient;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Reports the inspected long-press recognition card after its first visible frame. */
public final class HostRecognitionMenuHooks {
    private static final long READY_TIMEOUT_MILLIS = 1_000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "TaplusBridge-recognition-visible"); thread.setDaemon(true); return thread;
    });
    private HostRecognitionMenuHooks() { }

    public static void install(XposedModule module, ClassLoader loader) {
        try {
            Method show = loader.loadClass("com.miui.contentextension.text.floatview.TaplusSplashFloatView")
                    .getDeclaredMethod("showRecognizeFloatView");
            Method source = loader.loadClass("com.miui.contentextension.services.TextContentExtensionService")
                    .getDeclaredMethod("getInjectorPackage");
            if (Modifier.isStatic(show.getModifiers()) || show.getReturnType() != void.class
                    || !Modifier.isStatic(source.getModifiers()) || source.getReturnType() != String.class)
                throw new NoSuchMethodException("recognition_visible_contract");
            show.setAccessible(true); source.setAccessible(true);
            Supplier<String> currentSource = () -> {
                try { return (String) source.invoke(null); }
                catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
            };
            module.hook(show).setId("taplusbridge:recognition-visible")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object result = chain.proceed();
                        afterRecognizeShown(chain.getThisObject(), currentSource);
                        return result;
                    });
            module.log(4, "TaplusBridge", "Recognition visibility host hook installed");
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(5, "TaplusBridge", "Recognition visibility host hook unavailable");
        }
    }

    static void afterRecognizeShown(Object splash, Supplier<String> currentSource) {
        if (splash == null || currentSource == null || Looper.myLooper() != Looper.getMainLooper()) return;
        try {
            Object rawContext = field(splash, "mContext"), rawView = field(splash, "mMainView"),
                    rawCard = field(splash, "mRecognitionCard"), rawParams = field(splash, "wmParams");
            String source = currentSource.get();
            if (!(rawContext instanceof Context context) || !supported(context) || !HostPolicy.validTarget(source)
                    || !(rawView instanceof View view) || !(rawCard instanceof View card)
                    || !(rawParams instanceof WindowManager.LayoutParams params)
                    || !Boolean.FALSE.equals(field(splash, "mIsClipboardView"))) return;
            Context application = context.getApplicationContext();
            Pending pending = new Pending(splash, view, card, params, application == null ? context : application,
                    source, currentSource, SystemClock.elapsedRealtime() + READY_TIMEOUT_MILLIS);
            view.postOnAnimation(pending);
        } catch (ReflectiveOperationException | RuntimeException unavailable) { }
    }

    private static final class Pending implements Runnable {
        private final WeakReference<Object> splash;
        private final WeakReference<View> view;
        private final WeakReference<View> card;
        private final WeakReference<WindowManager.LayoutParams> params;
        private final Context context;
        private final String source;
        private final Supplier<String> currentSource;
        private final long deadline;
        private final Handler main = new Handler(Looper.getMainLooper());
        private long shownElapsed = -1;

        Pending(Object splash, View view, View card, WindowManager.LayoutParams params, Context context,
                String source, Supplier<String> currentSource, long deadline) {
            this.splash = new WeakReference<>(splash); this.view = new WeakReference<>(view);
            this.card = new WeakReference<>(card);
            this.params = new WeakReference<>(params); this.context = context;
            this.source = source; this.currentSource = currentSource; this.deadline = deadline;
        }

        @Override public void run() {
            if (Looper.myLooper() != Looper.getMainLooper() || SystemClock.elapsedRealtime() > deadline) return;
            Object owner = splash.get(); View shown = view.get(), recognition = card.get();
            WindowManager.LayoutParams layout = params.get();
            if (owner == null || shown == null || recognition == null || layout == null) return;
            try {
                if (!source.equals(currentSource.get()) || field(owner, "mMainView") != shown
                        || field(owner, "mRecognitionCard") != recognition || field(owner, "wmParams") != layout
                        || !Boolean.FALSE.equals(field(owner, "mIsClipboardView"))) return;
                if (Boolean.TRUE.equals(field(owner, "mIsShowing")) && shown.isAttachedToWindow()
                        && shown.getVisibility() == View.VISIBLE && shown.getWindowVisibility() == View.VISIBLE
                        && Float.isFinite(shown.getAlpha()) && shown.getAlpha() > 0f
                        && recognition.isAttachedToWindow() && recognition.getVisibility() == View.VISIBLE
                        && recognition.getWindowVisibility() == View.VISIBLE
                        && Float.isFinite(recognition.getAlpha()) && recognition.getAlpha() > 0f
                        && Float.isFinite(layout.alpha) && layout.alpha > 0f) {
                    if (shownElapsed < 0) shownElapsed = SystemClock.elapsedRealtime();
                    shownElapsed = HostRecognitionMenuRetention.rememberShown(owner, source, shownElapsed);
                    if (shownElapsed < 0) return;
                    Bundle request = new Bundle(); request.putString("source", source);
                    request.putLong("shown_elapsed", shownElapsed);
                    IO.execute(() -> {
                        try {
                            if (!source.equals(currentSource.get()) || SystemClock.elapsedRealtime() > deadline) return;
                            if (RuntimeClient.call(context, "recognition_visible", request) != null) return;
                        } catch (RuntimeException unavailable) { return; }
                        main.post(() -> {
                            View next = view.get();
                            if (next != null) next.postOnAnimation(this);
                        });
                    });
                } else shown.postOnAnimation(this);
            } catch (ReflectiveOperationException | RuntimeException unavailable) { }
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
            catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException("recognition_visible_window_contract");
    }

    private static boolean supported(Context context) {
        if (android.os.Process.myUid() / 100000 != 0 || !HostPolicy.HOST_PACKAGE.equals(context.getPackageName())) return false;
        try {
            PackageInfo host = context.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE, 0);
            return HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { return false; }
    }
}
