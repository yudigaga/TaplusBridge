package io.github.yudigaga.taplusbridge.hook;

import android.view.ActionMode;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.selection.SelectionActions;
import io.github.yudigaga.taplusbridge.selection.SelectionTargetReader;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Source apps must be explicitly enabled in the user's LSPosed scope. */
public final class SourceSelectionHooks {
    private SourceSelectionHooks() { }
    public static void install(XposedModule module, ClassLoader loader, String source) {
        if (!HostPolicy.validTarget(source)) return;
        try {
            Method invoke = loader.loadClass("com.android.internal.view.menu.MenuItemImpl").getDeclaredMethod("invoke");
            if (invoke.getReturnType() != boolean.class) return;
            module.hook(invoke).setId("taplusbridge:source-selection-menu")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (chain.getThisObject() instanceof MenuItem item && SelectionActions.dispatchMenuItem(item)) return true;
                        return chain.proceed();
                    });
            Method entry = loader.loadClass("android.view.View").getDeclaredMethod("startActionMode", ActionMode.Callback.class, int.class);
            AtomicReference<SelectionTargetReader> reader = new AtomicReference<>();
            module.hook(entry).setId("taplusbridge:source-selection")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (!(chain.getThisObject() instanceof TextView text)
                                || !(chain.getArg(0) instanceof ActionMode.Callback callback)
                                || !(chain.getArg(1) instanceof Integer type) || type != ActionMode.TYPE_FLOATING
                                || !source.equals(text.getContext().getPackageName())) return chain.proceed();
                        SelectionTargetReader active = reader.get();
                        if (active == null) {
                            SelectionTargetReader created = new SelectionTargetReader(text.getContext(), source);
                            reader.compareAndSet(null, created); active = reader.get();
                        }
                        ActionMode.Callback wrapped = SelectionActions.wrap(text, callback, type, source, active);
                        return wrapped == callback ? chain.proceed() : chain.proceed(new Object[]{wrapped, type});
                    });
            module.log(4, "TaplusBridge", "Native source selection hook installed");
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(4, "TaplusBridge", "Native source selection hook unavailable");
        }
    }
}
