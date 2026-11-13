package io.github.yudigaga.taplusbridge.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.R;
import io.github.yudigaga.taplusbridge.browser.BrowserApps;
import io.github.yudigaga.taplusbridge.browser.BrowserStore;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.recognition.RouteLink;
import io.github.yudigaga.taplusbridge.translation.TranslationApps;
import io.github.yudigaga.taplusbridge.translation.TranslationSettings;
import io.github.yudigaga.taplusbridge.translation.TranslationStore;

/** Module-process-only presentation projection; private settings never cross the provider boundary. */
final class ActionTargets {
    private static final String BROWSER_PROBE = "https://example.com";
    private ActionTargets() { }

    static Bundle presentation(Context context, String kind) {
        return presentation(context, kind, null);
    }

    static Bundle presentation(Context context, String kind, String source) {
        if (!"browser".equals(kind))
            return translationPresentation(context, source, new TranslationStore(context).load());
        Bundle result = new Bundle();
        result.putBoolean("configured", false); result.putBoolean("available", false);
        String target = new BrowserStore(context).selectedPackage();
        result.putBoolean("configured", !target.isEmpty());
        if (target.isEmpty() || !new ConfigStore(context).enabled()
                || BrowserApps.viewIntent(context, BROWSER_PROBE, target) == null) return result;
        try { addPackage(context, result, target); }
        catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) { return result; }
        result.putBoolean("available", true);
        return result;
    }

    static Bundle translationPresentation(Context context, String source, TranslationSettings snapshot) {
        Bundle result = new Bundle();
        result.putBoolean("configured", snapshot.mode != TranslationSettings.Mode.NATIVE);
        result.putBoolean("available", false);
        if (snapshot.mode == TranslationSettings.Mode.NATIVE || !new ConfigStore(context).enabled()) return result;
        try {
            var policy = new ConfigStore(context).sourceScope();
            // Match TranslationRequests.active; a source-less explicit clipboard action remains allowed.
            if (source != null && policy.managed && !policy.allows(source)) return result;
            snapshot.validate();
            if (snapshot.mode == TranslationSettings.Mode.APP) {
                boolean found = TranslationApps.installed(context).stream().anyMatch(target ->
                        snapshot.appComponent.equals(target.component()) && snapshot.appAction.equals(target.action()));
                if (!found) return result;
                ComponentName component = ComponentName.unflattenFromString(snapshot.appComponent);
                if (component == null) return result;
                addPackage(context, result, component.getPackageName());
            } else {
                result.putString("target", BridgeContract.APP_PACKAGE);
                result.putString("title", context.getString(R.string.app_name));
                result.putParcelable("icon", bitmap(context.getDrawable(R.drawable.ic_portal)));
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) { return result; }
        result.putBoolean("available", true);
        return result;
    }

    /** Only ordinary, unmatched web actions reach this direct path; it never issues a launch ticket. */
    static Bundle browserFallback(Context context, String method, Bundle request, String reason) {
        if (!"no_match".equals(reason) || request == null) return null;
        String url = request.getString("url");
        @SuppressWarnings("deprecation") Intent original = request.getParcelable("original");
        if (url == null && original != null && original.getData() != null) {
            Uri data = original.getData();
            url = "mibrowser".equals(data.getScheme()) ? data.getQueryParameter("web_url") : data.toString();
        }
        if (!RouteLink.validHttp(url)) return null;
        @SuppressWarnings("deprecation") Intent candidate = request.getParcelable("candidate");
        if (!browserCandidate(context, candidate)) return null;
        String target = new BrowserStore(context).selectedPackage();
        if (target.isEmpty()) return null;
        Intent direct = BrowserApps.viewIntent(context, url, target);
        Bundle result = new Bundle();
        if (direct == null) { result.putString("reason", "browser_unavailable"); return result; }
        direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if ("resolve".equals(method)) {
            try { addPackage(context, result, target); result.putBoolean("browser_fallback", true); }
            catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) {
                result.clear(); result.putString("reason", "browser_unavailable");
            }
        } else result.putParcelable("direct", direct);
        return result;
    }

    private static boolean browserCandidate(Context context, Intent candidate) {
        if (candidate == null) return true;
        if (!Intent.ACTION_VIEW.equals(candidate.getAction()) || candidate.getSelector() != null
                || candidate.getClipData() != null || !RouteLink.validHttp(candidate.getDataString())) return false;
        String target = candidate.getComponent() == null ? candidate.getPackage() : candidate.getComponent().getPackageName();
        // An explicit native app that accepts its own website remains the host's original action.
        return target == null || BrowserApps.viewIntent(context, candidate.getDataString(), target) != null;
    }

    private static void addPackage(Context context, Bundle result, String target)
            throws android.content.pm.PackageManager.NameNotFoundException {
        var packages = context.getPackageManager();
        var app = packages.getApplicationInfo(target, 0);
        Bitmap icon = bitmap(packages.getApplicationIcon(app));
        result.putString("target", target);
        result.putString("title", packages.getApplicationLabel(app).toString());
        result.putParcelable("icon", icon);
    }

    private static Bitmap bitmap(Drawable icon) {
        if (icon == null) throw new IllegalArgumentException("Missing application icon");
        Bitmap result = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        android.graphics.Rect bounds = icon.copyBounds();
        try { icon.setBounds(0, 0, 64, 64); icon.draw(new Canvas(result)); }
        finally { icon.setBounds(bounds); }
        return result;
    }
}
