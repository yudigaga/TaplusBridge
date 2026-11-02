package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Reapply the enabled host's existing text/image job settings without changing its master switch. */
public final class HostCaptureSettings {
    private static final String ROOT = "com.miui.contentextension.utils.";
    private final Method enabled, switchConfig;

    public HostCaptureSettings(ClassLoader loader) throws ReflectiveOperationException {
        this(loader.loadClass(ROOT + "TaplusSettingUtils"), loader.loadClass(ROOT + "ContentCatcherUtil"));
    }

    public HostCaptureSettings(Class<?> settings, Class<?> catcher) throws ReflectiveOperationException {
        enabled = method(settings, "isTaplusEnable", boolean.class, Context.class);
        switchConfig = method(catcher, "switchCatcherConfig", void.class, Context.class, boolean.class);
    }

    /** False means the original configuration is left alone; failures remain observable to the caller. */
    public boolean synchronize(Context context, SourceScopePolicy policy) throws ReflectiveOperationException {
        if (!policy.managed || !Boolean.TRUE.equals(enabled.invoke(null, context))) return false;
        switchConfig.invoke(null, context, true);
        return true;
    }

    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... arguments)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, arguments);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result)
            throw new NoSuchMethodException("capture_settings_contract");
        method.setAccessible(true);
        return method;
    }
}
