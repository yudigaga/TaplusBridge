package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
import java.util.Map;

/** A system-owned menu dismissal may preserve its exact still-visible native recognition model once. */
public final class HostRecognitionMenuRetention {
    private static final Object LOCK = new Object();
    private static Saved current;
    private HostRecognitionMenuRetention() { }

    public static boolean install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> service = loader.loadClass("com.miui.contentextension.services.TextContentExtensionService");
            Class<?> callback = loader.loadClass(service.getName() + "$1");
            Class<?> result = loader.loadClass("miui.contentcatcher.sdk.ClientCatcherResult");
            Method received = callback.getDeclaredMethod("onContentReceived", result);
            Field owner = callback.getDeclaredField("this$0"), model = service.getDeclaredField("mCatcherResult"),
                    finished = service.getDeclaredField("sIsTaskFinished");
            if (Modifier.isStatic(received.getModifiers()) || received.getReturnType() != void.class
                    || Modifier.isStatic(owner.getModifiers()) || owner.getType() != service
                    || !Modifier.isStatic(model.getModifiers()) || model.getType() != result
                    || !Modifier.isStatic(finished.getModifiers()) || finished.getType() != boolean.class)
                throw new NoSuchMethodException("recognition_retention_contract");
            for (String name : new String[]{"getInjectorPackage", "getInjectorActivity"}) {
                Method getter = service.getDeclaredMethod(name);
                if (!Modifier.isStatic(getter.getModifiers()) || getter.getReturnType() != String.class)
                    throw new NoSuchMethodException("recognition_retention_source_contract");
            }
            Method[] boundaries = new Method[3];
            String[] names = {"onStartPick", "cancelTask", "onDestroy"};
            for (int i = 0; i < names.length; i++) {
                boundaries[i] = service.getDeclaredMethod(names[i]);
                if (Modifier.isStatic(boundaries[i].getModifiers()) || boundaries[i].getReturnType() != void.class)
                    throw new NoSuchMethodException("recognition_retention_boundary_contract");
                boundaries[i].setAccessible(true);
            }
            received.setAccessible(true);
            module.hook(received).setId("taplusbridge:recognition-retain-control")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        // Stock writes mCatcherResult before examining the control code.
                        if (retainControl(chain.getThisObject(), chain.getArg(0))) {
                            module.log(4, "TaplusBridge", "recognition menu stage=retained");
                            return null;
                        }
                        return chain.proceed();
                    });
            for (int i = 0; i < boundaries.length; i++) {
                module.hook(boundaries[i]).setId("taplusbridge:recognition-retain-clear:" + names[i])
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                            clear(chain.getThisObject());
                            return chain.proceed();
                        });
            }
            module.log(4, "TaplusBridge", "Recognition menu retention host hook installed");
            return true;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(5, "TaplusBridge", "Recognition menu retention host hook unavailable");
            return false;
        }
    }

    /** Returns the first visible time for these exact instances, or -1 if they cannot own retention. */
    static long rememberShown(Object splash, String source, long shownElapsed) {
        if (splash == null || Looper.myLooper() != Looper.getMainLooper() || !HostPolicy.validTarget(source)
                || shownElapsed < 0 || shownElapsed > SystemClock.elapsedRealtime()) return -1;
        try {
            Object service = value(splash, "mService"), model = staticValue(service, "mCatcherResult"),
                    root = value(splash, "mMainView"), card = value(splash, "mRecognitionCard"), params = value(splash, "wmParams");
            String activity = staticString(service, "getInjectorActivity");
            if (!(service instanceof Context context) || !supported(context) || model == null
                    || !source.equals(staticString(service, "getInjectorPackage")) || !validActivity(activity)
                    || !Boolean.FALSE.equals(staticValue(service, "sIsTaskFinished"))
                    || value(service, "mSplashFloatView") != splash
                    || !Boolean.TRUE.equals(value(splash, "mIsShowing")) || !Boolean.FALSE.equals(value(splash, "mIsClipboardView"))
                    || !(root instanceof View rootView) || !(card instanceof View cardView)
                    || !(params instanceof WindowManager.LayoutParams layout) || !visible(rootView) || !visible(cardView)
                    || !Float.isFinite(layout.alpha) || layout.alpha <= 0f
                    || !injectorMatches(model, source, activity)) return -1;
            synchronized (LOCK) {
                if (current != null && current.service.get() == service && current.splash.get() == splash
                        && current.model.get() == model && current.root.get() == root && current.card.get() == card
                        && current.params.get() == params && current.source.equals(source) && current.activity.equals(activity))
                    return current.shownElapsed;
                current = new Saved(service, splash, model, root, card, params, source, activity, shownElapsed);
                return shownElapsed;
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return -1; }
    }

    /** Binder-side admission reads only saved identities and host fields; all View reads stay on main. */
    static boolean retainControl(Object callback, Object incoming) {
        if (callback == null || incoming == null) return false;
        try {
            if (!pureBackControl(incoming)) return false;
            Saved saved;
            Context context;
            synchronized (LOCK) {
                saved = current;
                Object service = value(callback, "this$0");
                if (saved == null || saved.claimed || service != saved.service.get() || !(service instanceof Context host)
                        || !matches(saved) || !injectorMatches(incoming, saved.source, saved.activity)) return false;
                saved.claimed = true;
                context = host;
            }
            Bundle request = new Bundle(); request.putString("source", saved.source); request.putString("activity", saved.activity);
            request.putLong("shown_elapsed", saved.shownElapsed);
            Bundle response = RuntimeClient.call(context, "recognition_menu_retain", request);
            if (response == null || !Boolean.TRUE.equals(response.get("keep"))) return false;
            synchronized (LOCK) { return current == saved && matches(saved); }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    static void clear(Object service) {
        synchronized (LOCK) {
            if (current != null && (current.service.get() == null || current.service.get() == service)) current = null;
        }
    }

    private static boolean matches(Saved saved) throws ReflectiveOperationException {
        Object service = saved.service.get(), splash = saved.splash.get(), model = saved.model.get(),
                root = saved.root.get(), card = saved.card.get(), params = saved.params.get();
        return service != null && splash != null && model != null && root != null && card != null && params != null
                && value(splash, "mService") == service && value(service, "mSplashFloatView") == splash
                && staticValue(service, "mCatcherResult") == model && Boolean.FALSE.equals(staticValue(service, "sIsTaskFinished"))
                && saved.source.equals(staticString(service, "getInjectorPackage"))
                && saved.activity.equals(staticString(service, "getInjectorActivity"))
                && value(splash, "mMainView") == root && value(splash, "mRecognitionCard") == card && value(splash, "wmParams") == params
                && Boolean.TRUE.equals(value(splash, "mIsShowing")) && Boolean.FALSE.equals(value(splash, "mIsClipboardView"));
    }

    private static boolean pureBackControl(Object result) throws ReflectiveOperationException {
        Object properties = invoke(result, "getPropertyMap");
        if (!(properties instanceof Map<?, ?> map) || map.size() != 1 || !"257".equals(map.get("observe_control_event"))) return false;
        for (String job : new String[]{"text_pick", "image_pick"}) {
            Method params = result.getClass().getMethod("getParams", String.class); params.setAccessible(true);
            Object data = params.invoke(result, job);
            if (data != null && (!(data instanceof Map<?, ?> values) || !values.isEmpty())) return false;
        }
        return true;
    }

    private static boolean injectorMatches(Object result, String source, String activity) throws ReflectiveOperationException {
        Object token = invoke(result, "getInjectorToken");
        return token != null && source.equals(invoke(token, "getPkgName")) && activity.equals(invoke(token, "getActivityName"));
    }

    private static boolean visible(View view) {
        return view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE && view.getWindowVisibility() == View.VISIBLE
                && Float.isFinite(view.getAlpha()) && view.getAlpha() > 0f;
    }

    private static boolean validActivity(String activity) {
        return activity != null && activity.length() <= 256 && activity.matches("[A-Za-z_$][A-Za-z0-9_.$]*");
    }

    private static boolean supported(Context context) {
        if (android.os.Process.myUid() / 100000 != 0 || !HostPolicy.HOST_PACKAGE.equals(context.getPackageName())) return false;
        try {
            PackageInfo host = context.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE, 0);
            return HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { return false; }
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException missing) { }
        }
        throw new NoSuchFieldException("recognition_retention_host_contract");
    }

    private static Object value(Object owner, String name) throws ReflectiveOperationException { return field(owner, name).get(owner); }
    private static Object staticValue(Object owner, String name) throws ReflectiveOperationException {
        Field field = field(owner, name);
        if (!Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException("recognition_retention_static_contract");
        return field.get(null);
    }
    private static String staticString(Object owner, String name) throws ReflectiveOperationException {
        Method method = owner.getClass().getMethod(name);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != String.class)
            throw new NoSuchMethodException("recognition_retention_source_contract");
        method.setAccessible(true); return (String) method.invoke(null);
    }
    private static Object invoke(Object owner, String name) throws ReflectiveOperationException {
        Method method = owner.getClass().getMethod(name); method.setAccessible(true); return method.invoke(owner);
    }

    private static final class Saved {
        final WeakReference<Object> service, splash, model, root, card, params;
        final String source, activity;
        final long shownElapsed;
        boolean claimed;
        Saved(Object service, Object splash, Object model, Object root, Object card, Object params,
                String source, String activity, long shownElapsed) {
            this.service = new WeakReference<>(service); this.splash = new WeakReference<>(splash);
            this.model = new WeakReference<>(model); this.root = new WeakReference<>(root);
            this.card = new WeakReference<>(card); this.params = new WeakReference<>(params);
            this.source = source; this.activity = activity; this.shownElapsed = shownElapsed;
        }
    }
}
