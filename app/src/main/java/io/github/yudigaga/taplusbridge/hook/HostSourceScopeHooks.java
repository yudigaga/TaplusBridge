package io.github.yudigaga.taplusbridge.hook;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.BuildConfig;
import io.github.yudigaga.taplusbridge.runtime.BridgeTransport;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import io.github.yudigaga.taplusbridge.runtime.SourceScopePublisher;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Host blacklist publication and pre-recognition app gating; never touches source processes. */
public final class HostSourceScopeHooks {
    public static final String CHANGED = "io.github.yudigaga.taplusbridge.SOURCE_SCOPE_CHANGED";
    private static final String ROOT = "com.miui.contentextension.";
    private final XposedModule module;
    private final Method appInstance, configInstance, update, updateList, injectorPackage;
    private final HostCaptureSettings captureSettings;
    private final Field forbidden;
    private final Handler worker;
    private volatile SourceScopePolicy cached;
    private boolean registered;
    private final ThreadLocal<Boolean> presentationGate = ThreadLocal.withInitial(() -> false);
    private final Runnable refresh = this::refresh;
    private void refresh() {
        try {
            Context context = (Context) appInstance.invoke(null);
            if (context != null && supported(context)) {
                update.invoke(configInstance.invoke(null, context));
                SourceScopePolicy policy = policy(context);
                if (captureSettings.synchronize(context, policy))
                    module.log(4, "TaplusBridge", "Source app scope capture configuration requested");
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
    }

    private HostSourceScopeHooks(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        this.module = module;
        Class<?> app = loader.loadClass(ROOT + "App");
        Class<?> config = loader.loadClass(ROOT + "data.common.ContentExtensionConfig");
        appInstance = method(app, "getInstance", true, app);
        configInstance = method(config, "getInstance", true, config, Context.class);
        update = method(config, "updateBlacklistAndProvider", false, void.class);
        updateList = method(config, "updateBlacklistConfig", false, void.class, List.class);
        injectorPackage = method(loader.loadClass(ROOT + "services.TextContentExtensionService"),
                "getInjectorPackage", true, String.class);
        captureSettings = new HostCaptureSettings(loader);
        forbidden = config.getDeclaredField("mIsForbiddenByUser");
        if (forbidden.getType() != boolean.class || Modifier.isStatic(forbidden.getModifiers()))
            throw new NoSuchFieldException("scope_forbidden_contract");
        forbidden.setAccessible(true);
        HandlerThread thread = new HandlerThread("TaplusBridge-source-scope"); thread.start();
        worker = new Handler(thread.getLooper());
    }

    public static void install(XposedModule module, ClassLoader loader) {
        try {
            HostSourceScopeHooks hooks = new HostSourceScopeHooks(module, loader);
            hooks.install(loader);
            module.log(4, "TaplusBridge", "Source app scope hooks installed");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            module.log(5, "TaplusBridge", "Source app scope unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private void install(ClassLoader loader) throws ReflectiveOperationException {
        Method create = method(loader.loadClass(ROOT + "App"), "onCreate", false, void.class);
        module.hook(create).setId("taplusbridge:source-scope-create")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof Application app && supported(app)) register(app);
                    return result;
                });
        module.hook(update).setId("taplusbridge:source-scope-update")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Context context = (Context) appInstance.invoke(null);
                    if (context == null || !supported(context)) return chain.proceed();
                    try {
                        SourceScopePolicy policy = policy(context);
                        if (!policy.managed) return chain.proceed();
                        List<String> denied = SourceScopePublisher.effectiveBlacklist(context, policy);
                        SourceScopePublisher.publish(context, denied);
                        updateList.invoke(chain.getThisObject(), denied);
                    } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
                    // Never store the derived complement in the host's original user blacklist files.
                    return null;
                });
        Class<?> blacklist = loader.loadClass(ROOT + "setting.whitelist.utils.BlacklistUtils");
        Method list = method(blacklist, "getBlacklistByAllBlacklistAndLocalInstalled", true, List.class, Context.class);
        module.hook(list).setId("taplusbridge:source-scope-list")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Context context = (Context) chain.getArg(0);
                    if (!supported(context)) return chain.proceed();
                    SourceScopePolicy policy = policy(context);
                    return policy.managed ? SourceScopePublisher.effectiveBlacklist(context, policy) : chain.proceed();
                });
        for (String name : new String[]{"saveBlacklistToSettingProviderIfNeed", "saveBlacklistToSettingProvider"}) {
            Method save = method(blacklist, name, true, void.class, Context.class, List.class);
            module.hook(save).setId("taplusbridge:source-scope-" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Context context = (Context) chain.getArg(0);
                        if (!supported(context)) return chain.proceed();
                        try {
                            SourceScopePolicy policy = policy(context);
                            if (!policy.managed) return chain.proceed();
                            List<String> denied = SourceScopePublisher.effectiveBlacklist(context, policy);
                            SourceScopePublisher.publish(context, denied);
                            updateList.invoke(configInstance.invoke(null, context), denied);
                        } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
                        return null;
                    });
        }
        Method gate = method(loader.loadClass(ROOT + "data.common.ContentExtensionConfig"), "getConfigWithUser", false,
                String.class, Context.class, String.class, String.class, Point.class);
        module.hook(gate).setId("taplusbridge:source-scope-gate")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Context context = (Context) chain.getArg(0);
                    if (!supported(context)) return chain.proceed();
                    presentationGate.set(true);
                    String source = (String) chain.getArg(1);
                    try {
                        SourceScopePolicy policy = policy(context);
                        if (!policy.managed) {
                            diagnostic("host_gate", "unmanaged", policy, source);
                            return chain.proceed();
                        }
                        boolean denied = !policy.allows(source);
                        forbidden.setBoolean(chain.getThisObject(), denied);
                        diagnostic("host_gate", denied ? "denied" : "allowed", policy, source);
                        // Managed scope replaces the old app, Activity and touch-area restriction table.
                        return SourceScopeGate.managedPresentation(policy, source);
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        diagnostic("host_gate", "unavailable", null, source);
                        report(failure); forbidden.setBoolean(chain.getThisObject(), true); return "forbidden";
                    }
                });
        Method presentation = method(loader.loadClass(ROOT + "services.TextContentExtensionService"),
                "initPresentationType", false, boolean.class, loader.loadClass("miui.contentcatcher.sdk.ClientCatcherResult"));
        module.hook(presentation).setId("taplusbridge:source-scope-presentation")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    boolean previous = presentationGate.get(); presentationGate.set(false);
                    try {
                        Object result = chain.proceed();
                        boolean reached = presentationGate.get();
                        try {
                            if (chain.getThisObject() instanceof Context context && supported(context)) {
                                SourceScopePolicy policy = policy(context);
                                if (policy.managed) diagnostic("host_presentation", Boolean.TRUE.equals(result)
                                        ? "passed" : reached ? "scope_blocked" : "property_blocked", policy,
                                        (String) injectorPackage.invoke(null));
                            }
                        } catch (ReflectiveOperationException | RuntimeException failure) { report(failure); }
                        return result;
                    } finally { presentationGate.set(previous); }
                });
    }

    private SourceScopePolicy policy(Context context) {
        boolean liveReadFailed = false;
        try {
            if (android.os.Process.myUid() / 100000 == 0 && !BridgeTransport.moduleUnavailable(context)) {
                try {
                Bundle response = BridgeTransport.call(context, "source_scope", Bundle.EMPTY);
                if (response == null || !response.containsKey("policy")) throw new IllegalStateException("scope_unavailable");
                SourceScopePolicy result = SourceScopePolicy.parse(response.getString("policy"));
                cached = result; return result;
                } catch (RuntimeException unavailable) { liveReadFailed = true; }
            }
            RuntimeSnapshot snapshot = RuntimeSnapshot.parse(module.getRemotePreferences(RuntimeSnapshot.GROUP)
                    .getString(RuntimeSnapshot.KEY, null));
            if (snapshot.user != 0 || snapshot.build != BuildConfig.VERSION_CODE
                    || (liveReadFailed && !snapshot.sourceScope.managed))
                throw new IllegalStateException("scope_snapshot_mismatch");
            cached = snapshot.sourceScope; return cached;
        } catch (RuntimeException unavailable) {
            SourceScopePolicy previous = cached;
            if (previous != null && previous.managed) return previous;
            throw unavailable;
        }
    }

    private synchronized void register(Context context) {
        if (registered) return;
        registered = true;
        BroadcastReceiver changed = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                // The broadcast carries no configuration; always read the authenticated source.
                worker.removeCallbacks(refresh); worker.post(refresh);
            }
        };
        IntentFilter settings = new IntentFilter(CHANGED);
        androidx.core.content.ContextCompat.registerReceiver(context, changed, settings, null, worker,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED); packages.addDataScheme("package");
        context.registerReceiver(changed, packages, null, worker);
        worker.post(refresh);
    }

    private static boolean supported(Context context) {
        if (context == null) return false;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(HostPolicy.HOST_PACKAGE, 0);
            return HostPolicy.supportedVersion(info.versionName, info.getLongVersionCode());
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) { return false; }
    }
    private void report(Throwable failure) {
        module.log(5, "TaplusBridge", "Source app scope preserved published state: " + failure.getClass().getSimpleName());
    }
    private void diagnostic(String stage, String outcome, SourceScopePolicy policy, String source) {
        module.log(4, "TaplusBridge", "source scope stage=" + stage + " outcome=" + outcome
                + " mode=" + (policy == null ? "UNKNOWN" : policy.mode.name())
                + " source=" + sourceKind(source));
    }
    private static String sourceKind(String source) {
        if (source == null) return "unknown";
        return switch (source) {
            case "org.lsposed.manager" -> "lsposed_manager";
            case "com.resukisu.resukisu" -> "root_manager";
            case "com.android.chrome" -> "chrome";
            default -> HostPolicy.validPackage(source) ? "other" : "unknown";
        };
    }
    private static Method method(Class<?> owner, String name, boolean isStatic, Class<?> result, Class<?>... args)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, args);
        if (Modifier.isStatic(method.getModifiers()) != isStatic || method.getReturnType() != result)
            throw new NoSuchMethodException("scope_method_contract");
        method.setAccessible(true); return method;
    }
}
