package io.github.yudigaga.taplusbridge.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.BuildConfig;
import io.github.yudigaga.taplusbridge.probe.SystemProbe;
import io.github.yudigaga.taplusbridge.runtime.BridgeTransport;
import io.github.yudigaga.taplusbridge.runtime.RuntimeClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Taplus routing and generation-bound automatic launch interception in the system framework. */
public final class TaplusModule extends XposedModule {
    private static final String TAG = "TaplusBridge";
    private static final String HOST_ROOT = "com.miui.contentextension.";
    private static final Uri PROVIDER = Uri.parse("content://" + BridgeContract.AUTHORITY);
    private final AtomicBoolean installAttempted = new AtomicBoolean();
    private final AtomicBoolean versionReported = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final ThreadLocal<Integer> generationDepth = ThreadLocal.withInitial(() -> 0);
    private final SessionSource sessionSource = new SessionSource();
    private volatile boolean apiSupported;
    private volatile Boolean supportedHost;
    private volatile boolean sourceTrackingReady;
    private Field clipboardShowing;
    private volatile RecognitionHooks recognitionHooks;

    @Override public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        if (apiSupported) {
            log(4, TAG, "System framework callback reached; installing invisible probe");
            SystemProbe.install(this, param.getClassLoader());
        }
    }

    @Override public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        apiSupported = getApiVersion() == 102;
        log(apiSupported ? 4 : 5, TAG, apiSupported
                ? "API 102 module loaded; build=" + BuildConfig.VERSION_NAME + "/" + BuildConfig.VERSION_CODE
                        + "; system_server=" + param.isSystemServer() + "; process=" + param.getProcessName()
                : "Unsupported framework API; hooks disabled");
    }

    @Override public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!apiSupported || !BridgeContract.HOST_PACKAGE.equals(param.getPackageName())
                || !installAttempted.compareAndSet(false, true)) return;
        ClassLoader loader = param.getClassLoader();
        BridgeTransport.install();
        HostSourceScopeHooks.install(this, loader);
        try {
            Method create=android.app.Application.class.getDeclaredMethod("onCreate");
            hook(create).setId("taplusbridge:runtime-connect").intercept(chain->{
                Object result=chain.proceed();
                if(chain.getThisObject() instanceof Context context && BridgeContract.HOST_PACKAGE.equals(context.getPackageName()))RuntimeClient.connect(context,null);
                return result;
            });
        }catch(ReflectiveOperationException unavailable){log(5,TAG,"Runtime host connection unavailable");}
        installSourceTracking(loader);
        recognitionHooks = RecognitionHooks.install(this, loader, this::recommendationSource);
        HostScopeCaptureHooks.install(this, loader);
        PortalLensHooks.install(this, loader);
        try {
            Class<?> apps = loader.loadClass(HOST_ROOT + "utils.AppsUtils");
            Class<?> info = loader.loadClass(HOST_ROOT + "data.recognition.TextRecommendationInfo");
            RecommendationAccess access = new RecommendationAccess(info);
            Method build = staticMethod(apps, "generateOpenIntent", Intent.class, Context.class, info);
            hook(build).setId("taplusbridge:recommendation")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        int depth = enterGeneration();
                        try {
                            Object original = chain.proceed();
                            if (depth != 0 || !(chain.getArg(0) instanceof Context context)
                                    || (original != null && !(original instanceof Intent))) return original;
                            try {
                                if (!supportedHost(context)) return original;
                                return routeRecommendation(context, chain.getArg(1), (Intent) original, access);
                            } catch (Throwable failure) {
                                reportFailure(failure);
                                return HostCaptureAction.isAction(chain.getArg(1))
                                        || (recognitionHooks != null && recognitionHooks.isProbeRecommendation(chain.getArg(1))) ? null : original;
                            }
                        } finally { leaveGeneration(depth); }
                    });
            log(4, TAG, "Recommendation builder hook installed; host version checked on use");
        } catch (Throwable failure) {
            log(5, TAG, "Recommendation hook unavailable: " + failure.getClass().getSimpleName());
        }
        try {
            Class<?> clipboard = loader.loadClass(HOST_ROOT + "clipboard.utils.ClipboardUtils");
            Method build = staticMethod(clipboard, "generateNerIntent", Intent.class,
                    Context.class, String.class, String.class);
            hook(build).setId("taplusbridge:clipboard-url")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        // The LOC branch calls the recommendation builder with ACTION_SEND.
                        // Suppress nested processing even when this outer branch is left untouched.
                        int depth = enterGeneration();
                        try {
                            Object original = chain.proceed();
                            if (depth != 0 || !"URL".equals(chain.getArg(1))
                                    || !(chain.getArg(0) instanceof Context context)
                                    || !(original instanceof Intent intent)) return original;
                            try {
                                if (!supportedHost(context) || !Intent.ACTION_VIEW.equals(intent.getAction()))
                                    return original;
                                return prepare(context, intent, null, intent.getDataString(), null);
                            } catch (Throwable failure) {
                                reportFailure(failure);
                                return original;
                            }
                        } finally { leaveGeneration(depth); }
                    });
            log(4, TAG, "Clipboard URL builder hook installed; host version checked on use");
        } catch (Throwable failure) {
            log(5, TAG, "Clipboard URL hook unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private Intent routeRecommendation(Context context, Object info, Intent original,
                                       RecommendationAccess access) throws ReflectiveOperationException {
        if (HostCaptureAction.isAction(info)) {
            // Discard an old cached manual-action model; automatic probing owns the pick lifecycle.
            return null;
        }
        boolean ephemeral = recognitionHooks != null && recognitionHooks.isProbeRecommendation(info);
        Intent fallback = ephemeral ? null : original;
        if (info == null || !access.type.isInstance(info)
                || HostPolicy.isTranslation(access.get(access.category, info))) return fallback;
        if (original != null && !HostPolicy.supportsHandoffAction(original.getAction())) return fallback;
        String packageName = access.get(access.packageName, info);
        String raw = access.get(access.intent, info);
        String webUrl = access.get(access.detailUrl, info);
        Intent candidate = null;
        if (raw != null && !raw.isEmpty()) {
            try {
                if (raw.length() > 32768) return fallback;
                candidate = Intent.parseUri(raw, 0);
                if (!Intent.ACTION_VIEW.equals(candidate.getAction())) return fallback;
                if (candidate.getSelector() != null || candidate.getClipData() != null) return fallback;
                if (candidate.getPackage() == null && candidate.getComponent() == null
                        && HostPolicy.validTarget(packageName)) candidate.setPackage(packageName);
            } catch (java.net.URISyntaxException invalidIntent) { return fallback; }
        } else if (HostPolicy.validTarget(packageName)
                && (original == null || Intent.ACTION_MAIN.equals(original.getAction())
                || HostPolicy.hostUsesLauncher(packageName))) {
            // Frozen Douyin/Taobao launchers disappear from the host's enabled-only query.
            candidate = new Intent(Intent.ACTION_MAIN).setPackage(packageName)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
        }
        Intent routed = prepare(context, ephemeral ? null : original, candidate, webUrl, recommendationSource(), ephemeral);
        if (routed != original && routed != null) {
            routed.putExtra("canOpenWithApp", true);
            try { info.getClass().getMethod("setCanOpenWithApp", boolean.class).invoke(info, true); }
            catch (ReflectiveOperationException ignored) { }
        }
        return routed;
    }

    private Intent prepare(Context context, Intent original, Intent candidate, String webUrl, String source) {
        return prepare(context, original, candidate, webUrl, source, false);
    }
    private Intent prepare(Context context, Intent original, Intent candidate, String webUrl, String source, boolean ephemeral) {
        Bundle extras = new Bundle();
        extras.putBoolean("ephemeral", ephemeral);
        extras.putParcelable("original", original == null ? null : new Intent(original));
        extras.putParcelable("candidate", candidate == null ? null : new Intent(candidate));
        extras.putString("url", webUrl);
        extras.putString("source", source);
        Bundle response = BridgeTransport.call(context, BridgeContract.PREPARE, extras);
        if (response != null) {
            @SuppressWarnings("deprecation") Intent direct = response.getParcelable("direct");
            if (direct != null) return direct;
            String systemToken=response.getString("system_token");
            if(io.github.yudigaga.taplusbridge.capture.CaptureBroker.validNonce(systemToken))
                return new Intent(RuntimeClient.OPEN).setPackage("android").putExtra("system_token",systemToken)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        String token = response == null ? null : response.getString("token");
        if (token == null || token.isEmpty() || token.length() > 256) {
            if (ephemeral) android.widget.Toast.makeText(context, "目标应用暂不可用，请重新识别；原网页不会打开", android.widget.Toast.LENGTH_LONG).show();
            return ephemeral ? null : original;
        }
        return new Intent().setComponent(new ComponentName(BridgeContract.APP_PACKAGE,
                BridgeContract.APP_PACKAGE + ".ui.TransitActivity"))
                .putExtra("token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void installSourceTracking(ClassLoader loader) {
        try {
            Class<?> service = loader.loadClass(HOST_ROOT + "services.TextContentExtensionService");
            Class<?> catcher = loader.loadClass("miui.contentcatcher.sdk.ClientCatcherResult");
            Method source = staticMethod(service, "getInjectorPackage", String.class);
            Method parse = instanceMethod(service, "parseInjectorToken", void.class, catcher);
            Method pick = instanceMethod(service, "onStartPick", void.class);
            Method start = instanceMethod(service, "onStartCommand", int.class,
                    Intent.class, int.class, int.class);
            Method cancel = instanceMethod(service, "cancelTask", void.class);
            Method destroy = instanceMethod(service, "onDestroy", void.class);
            Field showing = service.getDeclaredField("sIsClipboardViewShowing");
            if (!Modifier.isStatic(showing.getModifiers()) || showing.getType() != boolean.class)
                throw new NoSuchFieldException("Unexpected clipboard visibility contract");
            showing.setAccessible(true);
            clipboardShowing = showing;
            hook(pick).setId("taplusbridge:source-pick")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        sessionSource.clear();
                        return chain.proceed();
                    });
            hook(parse).setId("taplusbridge:source-token")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        sessionSource.clear();
                        Object result = chain.proceed();
                        try { sessionSource.captured((String) source.invoke(null)); }
                        catch (ReflectiveOperationException failure) { sessionSource.clear(); }
                        return result;
                    });
            hook(start).setId("taplusbridge:source-service")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (chain.getArg(0) instanceof Intent intent && intent.hasExtra("clipboard_data"))
                            sessionSource.clear();
                        return chain.proceed();
                    });
            hook(cancel).setId("taplusbridge:source-cancel")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        sessionSource.clear();
                        return chain.proceed();
                    });
            hook(destroy).setId("taplusbridge:source-destroy")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        sessionSource.clear();
                        return chain.proceed();
                    });
            sourceTrackingReady = true;
            log(4, TAG, "ContentCatcher session source tracking installed");
        } catch (Throwable failure) {
            sourceTrackingReady = false;
            sessionSource.clear();
            log(5, TAG, "Source tracking unavailable; origin unknown: " + failure.getClass().getSimpleName());
        }
    }

    private String recommendationSource() {
        if (!sourceTrackingReady) return null;
        try { return sessionSource.forRecommendation(clipboardShowing.getBoolean(null)); }
        catch (IllegalAccessException failure) { return null; }
    }

    private boolean supportedHost(Context context) throws android.content.pm.PackageManager.NameNotFoundException {
        Boolean cached = supportedHost;
        if (cached != null) return cached;
        PackageInfo info = context.getPackageManager().getPackageInfo(BridgeContract.HOST_PACKAGE, 0);
        boolean supported = HostPolicy.supportedVersion(info.versionName, info.getLongVersionCode());
        supportedHost = supported;
        if (versionReported.compareAndSet(false, true)) log(supported ? 4 : 5, TAG, supported
                ? "Host " + info.versionName + "/" + info.getLongVersionCode() + " contract active"
                : "Unsupported host version; original behavior preserved");
        return supported;
    }

    private int enterGeneration() {
        int previous = generationDepth.get();
        generationDepth.set(previous + 1);
        return previous;
    }

    private void leaveGeneration(int previous) {
        if (previous == 0) generationDepth.remove();
        else generationDepth.set(previous);
    }

    private void reportFailure(Throwable failure) {
        if (failureReported.compareAndSet(false, true))
            log(5, TAG, "Bridge unavailable; original intent preserved: " + failure.getClass().getSimpleName());
    }

    private static Method staticMethod(Class<?> type, String name, Class<?> result, Class<?>... args)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, args);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result)
            throw new NoSuchMethodException("Unexpected host method contract");
        method.setAccessible(true);
        return method;
    }

    private static Method instanceMethod(Class<?> type, String name, Class<?> result, Class<?>... args)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, args);
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result)
            throw new NoSuchMethodException("Unexpected host instance method contract");
        method.setAccessible(true);
        return method;
    }

    private static final class RecommendationAccess {
        final Class<?> type;
        final Method detailUrl;
        final Method intent;
        final Method packageName;
        final Method category;

        RecommendationAccess(Class<?> type) throws NoSuchMethodException {
            this.type = type;
            detailUrl = getter(type, "getDetailUrl");
            intent = getter(type, "getIntent");
            packageName = getter(type, "getPackageName");
            category = getter(type, "getCategory");
        }

        String get(Method getter, Object info) throws ReflectiveOperationException {
            return (String) getter.invoke(info);
        }

        private static Method getter(Class<?> type, String name) throws NoSuchMethodException {
            Method method = type.getDeclaredMethod(name);
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != String.class)
                throw new NoSuchMethodException("Unexpected host getter contract");
            method.setAccessible(true);
            return method;
        }
    }
}
