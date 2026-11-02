package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.lens.PortalLensPanel;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Contracts verified against the supplied 4.1.3/40013 and 5.1.5/50015 APKs. */
public final class PortalLensHooks {
    private static final String ROOT = "com.miui.contentextension.";
    private final XposedModule module;
    private final Field objectButton, imageGroup;
    private final Method bitmap, sourcePackage;
    private final WeakHashMap<View, WeakReference<PortalLensPanel>> panels = new WeakHashMap<>();
    private Boolean supported;

    private PortalLensHooks(XposedModule module, Class<?> card, Class<?> service) throws ReflectiveOperationException {
        this.module = module;
        objectButton = field(card, "mRecognizeObject", TextView.class);
        imageGroup = field(card, "mImageGroup", RelativeLayout.class);
        bitmap = service.getDeclaredMethod("getBitmap");
        if (!Modifier.isStatic(bitmap.getModifiers()) || bitmap.getReturnType() != Bitmap.class)
            throw new NoSuchMethodException("lens_bitmap_contract");
        bitmap.setAccessible(true);
        sourcePackage = service.getDeclaredMethod("getInjectorPackage");
        if (!Modifier.isStatic(sourcePackage.getModifiers()) || sourcePackage.getReturnType() != String.class)
            throw new NoSuchMethodException("lens_source_contract");
        sourcePackage.setAccessible(true);
    }

    public static void install(XposedModule module, ClassLoader loader) {
        try {
            Class<?> card = loader.loadClass(ROOT + "text.cardview.TaplusRecognitionExpandedImageCard");
            Class<?> service = loader.loadClass(ROOT + "services.TextContentExtensionService");
            Class<?> watcher = loader.loadClass(ROOT + "services.TextContentExtensionService$AppStateWatcher");
            PortalLensHooks hooks = new PortalLensHooks(module, card, service);
            hooks.install(card, service, watcher);
            module.log(4, "TaplusBridge", "Lens native image action installed; host version/config checked on use");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            module.log(5, "TaplusBridge", "Lens host contract unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private void install(Class<?> card, Class<?> service, Class<?> watcher) throws ReflectiveOperationException {
        module.hook(watcher.getDeclaredMethod("onForegroundActivitiesChanged", int.class, int.class, boolean.class))
                .setId("taplusbridge:lens-interaction-foreground")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (PortalLensPanel.allowInteractionForeground((Integer) chain.getArg(1), (Boolean) chain.getArg(2))) return null;
                    return chain.proceed();
                });
        module.hook(service.getDeclaredMethod("quitByDefault", String.class, boolean.class, boolean.class))
                .setId("taplusbridge:lens-interaction-keyboard")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if ("other".equals(chain.getArg(0)) && Boolean.FALSE.equals(chain.getArg(1))
                            && Boolean.TRUE.equals(chain.getArg(2)) && PortalLensPanel.allowInteractionKeyboard()) return null;
                    return chain.proceed();
                });
        for (String name : new String[]{"refresh", "updateLayout"}) {
            module.hook(card.getDeclaredMethod(name)).setId("taplusbridge:lens-" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getThisObject() instanceof View view && supported(view.getContext())) {
                            try {
                                PortalLensPanel panel = panel(view);
                                if (panel != null) panel.refresh();
                            } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
                        }
                        return result;
                    });
        }
        module.hook(card.getDeclaredMethod("onClick", View.class)).setId("taplusbridge:lens-click")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (chain.getThisObject() instanceof View view && supported(view.getContext())) {
                        try {
                            if (chain.getArg(0) == objectButton.get(view)) {
                                PortalLensPanel panel = panel(view);
                                if (panel != null && panel.enabled()) {
                                    panel.search();
                                    return null;
                                }
                            }
                        } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
                    }
                    return chain.proceed();
                });
        for (String name : new String[]{"onStartPick", "cancelTask", "onDestroy"}) {
            module.hook(service.getDeclaredMethod(name)).setId("taplusbridge:lens-" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        clear();
                        return chain.proceed();
                    });
        }
        Method setBitmap = service.getDeclaredMethod("setBitmap", Bitmap.class);
        if (!Modifier.isStatic(setBitmap.getModifiers())) throw new NoSuchMethodException("lens_set_bitmap_contract");
        module.hook(setBitmap).setId("taplusbridge:lens-change-image")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    clear();
                    return chain.proceed();
                });
    }

    private synchronized PortalLensPanel panel(View view) throws IllegalAccessException {
        if (!(objectButton.get(view) instanceof TextView action)
                || !(imageGroup.get(view) instanceof RelativeLayout group)) return null;
        WeakReference<PortalLensPanel> reference = panels.get(view);
        PortalLensPanel panel = reference == null ? null : reference.get();
        if (panel == null) {
            panel = new PortalLensPanel(view, action, group, () -> {
                try { return (Bitmap) bitmap.invoke(null); }
                catch (ReflectiveOperationException | RuntimeException failure) { return null; }
            }, () -> {
                try { return (String) sourcePackage.invoke(null); }
                catch (ReflectiveOperationException | RuntimeException failure) { return null; }
            });
            panels.put(view, new WeakReference<>(panel));
        }
        return panel;
    }

    private void clear() {
        ArrayList<WeakReference<PortalLensPanel>> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(panels.values()); }
        for (WeakReference<PortalLensPanel> reference : snapshot) {
            PortalLensPanel panel = reference.get();
            if (panel != null) panel.invalidate();
        }
    }

    private boolean supported(Context context) {
        if (supported != null) return supported;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE, 0);
            supported = HostPolicy.supportedVersion(info.versionName, info.getLongVersionCode());
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
        return supported;
    }

    private void report(Throwable failure) {
        module.log(5, "TaplusBridge", "Lens panel unavailable: " + failure.getClass().getSimpleName());
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        if (Modifier.isStatic(field.getModifiers()) || field.getType() != expected)
            throw new NoSuchFieldException("lens_" + name + "_contract");
        field.setAccessible(true);
        return field;
    }
}
