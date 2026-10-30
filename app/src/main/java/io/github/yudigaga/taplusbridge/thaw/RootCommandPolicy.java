package io.github.yudigaga.taplusbridge.thaw;

import java.util.regex.Pattern;

/** Fixed package operations only. URLs and Intent data never enter a root command. */
final class RootCommandPolicy {
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    private RootCommandPolicy() {}

    static boolean validPackage(String packageName) {
        return packageName != null && packageName.length() <= 255
                && PACKAGE_NAME.matcher(packageName).matches();
    }

    static String enableCommand(String packageName, int userId) {
        if (!validPackage(packageName) || userId < 0) {
            throw new IllegalArgumentException("Invalid package or Android user");
        }
        String target = " --user " + userId + " " + packageName;
        return "pm enable" + target + " && pm unhide" + target;
    }
}
