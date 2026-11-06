package io.github.yudigaga.taplusbridge.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.runtime.BridgeTransport;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.function.Supplier;

/** Replaces only explicit translation actions; recognition and recommendation content stay with the host. */
final class HostTranslationHooks {
    private static final String ROOT = "com.miui.contentextension.";
    private static final String ACTIVITY = BridgeContract.APP_PACKAGE + ".translation.TranslationActivity";
    private static final int MAX_TEXT = 131072;

    private HostTranslationHooks() { }

    static void install(XposedModule module, ClassLoader loader, Supplier<String> source) {
        try {
            Method translate = method(loader.loadClass(ROOT + "text.cardview.TaplusRecognitionExpandedTextCard"),
                    "doTranslate", false, void.class);
            module.hook(translate).setId("taplusbridge:translation-text-action")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (chain.getThisObject() instanceof View card && supported(card.getContext())) {
                            try {
                                if (dispatch(card.getContext(), selectedText(card), source(source))) return null;
                            } catch (ReflectiveOperationException | RuntimeException unavailable) { }
                        }
                        return chain.proceed();
                    });
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(4, "TaplusBridge", "translation hook stage=text_action_unavailable");
        }
        try {
            Method translate = method(loader.loadClass(ROOT + "utils.AiWindowUtilsKt"),
                    "startAiWindow", true, void.class, Context.class, String.class);
            module.hook(translate).setId("taplusbridge:translation-ai-window")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (chain.getArg(0) instanceof Context context && supported(context)
                                && chain.getArg(1) instanceof String text
                                && dispatch(context, text, source(source))) return null;
                        return chain.proceed();
                    });
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            module.log(4, "TaplusBridge", "translation hook stage=ai_window_unavailable");
        }
    }

    /** The host must pass its natural selected words, not the card getter's ||-delimited tokens. */
    static String selectedText(Object card) throws ReflectiveOperationException {
        if (card == null) return null;
        Object adapter = HostRecognitionEnhancer.findField(card.getClass(), "mSegmentAdapter").get(card);
        if (adapter == null) return null;
        return (String) method(adapter.getClass(), "getSelectedWords", false, String.class).invoke(adapter);
    }

    /** Provider authentication and the saved translation mode decide whether replacement is enabled. */
    static Intent prepare(Context context, String text, String source) {
        if (context == null || text == null || text.isBlank() || text.length() > MAX_TEXT
                || source != null && !HostPolicy.validPackage(source)) return null;
        try {
            Bundle request = new Bundle(); request.putString("text", text);
            if (source != null) request.putString("source", source);
            Bundle response = BridgeTransport.call(context, "translation_prepare", request);
            Object raw = response == null ? null : response.get("direct");
            if (!(raw instanceof Intent direct)) return null;
            ComponentName component = direct.getComponent();
            Bundle extras = direct.getExtras();
            if (component == null || !BridgeContract.APP_PACKAGE.equals(component.getPackageName())
                    || !ACTIVITY.equals(component.getClassName())
                    || direct.getPackage() != null && !BridgeContract.APP_PACKAGE.equals(direct.getPackage())
                    || direct.getData() != null || direct.getSelector() != null || direct.getClipData() != null
                    || extras == null || !Set.of("token").equals(extras.keySet())
                    || !(extras.get("token") instanceof String token) || !token.matches("[A-Za-z0-9_-]{43}")) return null;
            return new Intent().setComponent(component).putExtra("token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (RuntimeException unavailable) { return null; }
    }

    static boolean dispatch(Context context, String text, String source) {
        Intent direct = prepare(context, text, source);
        if (direct == null) return false;
        try { context.startActivity(direct); return true; }
        catch (RuntimeException unavailable) { return false; }
    }

    private static String source(Supplier<String> source) {
        try { return source == null ? null : source.get(); }
        catch (RuntimeException unavailable) { return null; }
    }

    private static boolean supported(Context context) {
        try {
            var host = context.getPackageManager().getPackageInfo(BridgeContract.HOST_PACKAGE, 0);
            return HostPolicy.supportedVersion(host.versionName, host.getLongVersionCode());
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) { return false; }
    }

    private static Method method(Class<?> type, String name, boolean isStatic, Class<?> result, Class<?>... arguments)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, arguments);
        if (Modifier.isStatic(method.getModifiers()) != isStatic || method.getReturnType() != result)
            throw new NoSuchMethodException("translation_host_contract");
        method.setAccessible(true); return method;
    }
}
