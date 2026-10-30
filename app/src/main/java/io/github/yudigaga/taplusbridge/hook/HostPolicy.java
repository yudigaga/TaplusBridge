package io.github.yudigaga.taplusbridge.hook;

import java.util.Locale;
import java.util.regex.Pattern;

/** The host contract is deliberately limited to the APK inspected with this module. */
public final class HostPolicy {
    public static final String HOST_PACKAGE = "com.miui.contentextension";
    public static final String MODULE_PACKAGE = "io.github.yudigaga.taplusbridge";
    public static final String HOST_VERSION = "4.1.3";
    public static final long HOST_VERSION_CODE = 40013;
    private static final Pattern PACKAGE = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    private HostPolicy() {}

    public static boolean supportedVersion(String name, long code) {
        return HOST_VERSION.equals(name) && code == HOST_VERSION_CODE;
    }

    public static boolean validTarget(String value) {
        return validPackage(value)
                && !HOST_PACKAGE.equals(value) && !MODULE_PACKAGE.equals(value);
    }

    public static boolean validPackage(String value) {
        return value != null && value.length() < 256 && PACKAGE.matcher(value).matches();
    }

    public static boolean isTranslation(String category) {
        if (category == null) return false;
        return switch (category.toUpperCase(Locale.ROOT)) {
            case "EN_2_CH_PARA", "EN_2_CH_WORD", "TRANSLATION", "TRANSLATE" -> true;
            default -> false;
        };
    }

    public static boolean hostUsesLauncher(String packageName) {
        return "com.ss.android.ugc.aweme".equals(packageName)
                || "com.taobao.taobao".equals(packageName);
    }

    public static boolean supportsHandoffAction(String action) {
        return "android.intent.action.VIEW".equals(action)
                || "android.intent.action.MAIN".equals(action);
    }
}
