package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.os.Bundle;
import android.os.Binder;
import android.os.Process;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.translation.TranslationSettings;
import io.github.yudigaga.taplusbridge.translation.TranslationStore;
import java.util.Arrays;
import java.util.Set;

/** Narrow source-process projection. Translation credentials never enter this response. */
public final class SourceSelectionTargets {
    private SourceSelectionTargets() { }
    public static Bundle read(Context context, int callerUid, Bundle request) {
        try {
            if (context == null || callerUid < 0 || callerUid / 100000 != Process.myUid() / 100000
                    || request == null || !Set.of("source").equals(request.keySet())
                    || !(request.get("source") instanceof String source) || !HostPolicy.validTarget(source)) return Bundle.EMPTY;
            String[] packages = context.getPackageManager().getPackagesForUid(callerUid);
            if (packages == null || !Arrays.asList(packages).contains(source)) return Bundle.EMPTY;
            long identity = Binder.clearCallingIdentity();
            try {
                return ConfigStore.readCommitted(() -> {
                    Bundle result = new Bundle();
                    ConfigStore config = new ConfigStore(context);
                    boolean enabled = config.enabled() && config.sourceScope().allows(source);
                    result.putBoolean("enabled", enabled);
                    if (!enabled) return result;
                    Bundle browser = ActionTargets.presentation(context, "browser");
                    result.putBoolean("browser_configured", browser.getBoolean("configured"));
                    copyPresentation(result, browser, "browser");
                    TranslationSettings translation = new TranslationStore(context).load();
                    result.putBoolean("translation_configured", translation.mode == TranslationSettings.Mode.APP);
                    if (translation.mode == TranslationSettings.Mode.APP) {
                        Bundle presentation = ActionTargets.translationPresentation(context, source, translation);
                        if (presentation.getBoolean("available")) {
                            copyPresentation(result, presentation, "translation");
                            result.putString("translation_component", translation.appComponent);
                            result.putString("translation_action", translation.appAction);
                        }
                    }
                    return result;
                });
            } finally { Binder.restoreCallingIdentity(identity); }
        } catch (RuntimeException malformed) { return Bundle.EMPTY; }
    }

    private static void copyPresentation(Bundle result, Bundle presentation, String prefix) {
        if (!presentation.getBoolean("available")) return;
        if ("browser".equals(prefix)) result.putString("browser_package", presentation.getString("target"));
        result.putString(prefix + "_title", presentation.getString("title"));
        result.putParcelable(prefix + "_icon", presentation.getParcelable("icon"));
    }
}
