package io.github.yudigaga.taplusbridge.routing;

import java.util.Locale;
import java.util.regex.Pattern;

/** A domain route; subdomains and source wildcards must be enabled explicitly. */
public record RouteRule(String host, String targetPackage, String sourcePackage,
                        boolean includeSubdomains, boolean convert) {
    private static final Pattern PACKAGE = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    public RouteRule {
        if (host == null) throw new IllegalArgumentException("Host is required");
        host = host.toLowerCase(Locale.ROOT);
        if (!validHost(host)) throw new IllegalArgumentException("Invalid domain");
        if (!validPackage(targetPackage)) throw new IllegalArgumentException("Invalid target package");
        if (!"*".equals(sourcePackage) && !validPackage(sourcePackage)) {
            throw new IllegalArgumentException("Invalid source package");
        }
    }

    public static boolean validPackage(String value) {
        return value != null && value.length() <= 255 && PACKAGE.matcher(value).matches();
    }

    static boolean validHost(String value) {
        if (value == null || value.length() > 253 || value.indexOf('.') < 0) return false;
        String[] labels = value.split("\\.", -1);
        for (String label : labels) if (!LABEL.matcher(label).matches()) return false;
        return true;
    }
}
