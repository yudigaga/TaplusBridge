package io.github.yudigaga.taplusbridge.selection;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.recognition.RouteLink;
import java.util.HashSet;
import java.util.Set;

/** Source side trusts only a small typed projection; it never accepts remote launch Intents. */
public final class SelectionTargets {
    private static final String TRANSLATE = "android.intent.action.TRANSLATE";
    private static final Set<String> ACTIONS = Set.of(TRANSLATE, Intent.ACTION_PROCESS_TEXT, Intent.ACTION_SEND);
    final String browserPackage, browserTitle, translationTitle, translationAction;
    final Bitmap browserIcon, translationIcon;
    final boolean browserConfigured, translationConfigured;
    private final ComponentName translationComponent;

    private SelectionTargets(String browserPackage, String browserTitle, Bitmap browserIcon,
            ComponentName translationComponent, String translationAction, String translationTitle, Bitmap translationIcon,
            boolean browserConfigured, boolean translationConfigured) {
        this.browserPackage = browserPackage; this.browserTitle = browserTitle; this.browserIcon = browserIcon;
        this.translationComponent = translationComponent; this.translationAction = translationAction;
        this.translationTitle = translationTitle; this.translationIcon = translationIcon;
        this.browserConfigured = browserConfigured; this.translationConfigured = translationConfigured;
    }
    public static SelectionTargets parse(Bundle response) {
        try {
            if (response == null || !Boolean.TRUE.equals(response.get("enabled"))) return null;
            if (!(response.get("browser_configured") instanceof Boolean browserConfigured)
                    || !(response.get("translation_configured") instanceof Boolean translationConfigured)) return null;
            Set<String> expected = new HashSet<>(Set.of("enabled", "browser_configured", "translation_configured"));
            String browser = null, browserTitle = null, translationTitle = null, action = null;
            Bitmap browserIcon = null, translationIcon = null;
            ComponentName component = null;
            if (response.containsKey("browser_package")) {
                if (!browserConfigured) return null;
                expected.addAll(Set.of("browser_package", "browser_title", "browser_icon"));
                Object raw = response.get("browser_package");
                if (!(raw instanceof String target) || !HostPolicy.validTarget(target)) return null;
                browser = target; browserTitle = title(response, "browser_title"); browserIcon = icon(response, "browser_icon");
            }
            if (response.containsKey("translation_component")) {
                if (!translationConfigured) return null;
                expected.addAll(Set.of("translation_component", "translation_action", "translation_title", "translation_icon"));
                Object raw = response.get("translation_component"), rawAction = response.get("translation_action");
                if (!(raw instanceof String value) || value.length() > 768
                        || !(rawAction instanceof String validAction) || !ACTIONS.contains(validAction)) return null;
                component = ComponentName.unflattenFromString(value);
                if (component == null || !HostPolicy.validTarget(component.getPackageName())
                        || !component.getClassName().matches("[A-Za-z_$][A-Za-z0-9_$.]*")) return null;
                action = validAction; translationTitle = title(response, "translation_title"); translationIcon = icon(response, "translation_icon");
            }
            if (!expected.equals(response.keySet())) return null;
            return new SelectionTargets(browser, browserTitle, browserIcon, component, action, translationTitle, translationIcon,
                    browserConfigured, translationConfigured);
        } catch (RuntimeException malformed) { return null; }
    }
    public Intent translationIntent(String text) {
        if (translationComponent == null || text == null || text.isBlank() || text.length() > 32768 || text.indexOf('\0') >= 0) return null;
        Intent intent = new Intent(translationAction).setComponent(translationComponent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!TRANSLATE.equals(translationAction)) intent.setType("text/plain");
        if (Intent.ACTION_PROCESS_TEXT.equals(translationAction))
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        else intent.putExtra(Intent.EXTRA_TEXT, text);
        return intent;
    }
    public Intent browserIntent(String url) {
        return browserPackage == null || !RouteLink.validHttp(url) ? null
                : new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(browserPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    boolean hasTranslation() { return translationComponent != null; }
    boolean hasBrowser() { return browserPackage != null; }
    private static String title(Bundle response, String field) {
        Object raw = response.get(field);
        if (!(raw instanceof String value) || value.isBlank() || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid title");
        return value;
    }
    private static Bitmap icon(Bundle response, String field) {
        Object raw = response.get(field);
        if (!(raw instanceof Bitmap value) || value.isRecycled() || value.getWidth() != 64 || value.getHeight() != 64)
            throw new IllegalArgumentException("Invalid icon");
        return value;
    }
}
