package io.github.yudigaga.taplusbridge.hook;

/** Stores only the origin of the current ContentCatcher session, never clipboard text. */
public final class SessionSource {
    private volatile String packageName;

    public void clear() { packageName = null; }

    public void captured(String value) {
        packageName = HostPolicy.validPackage(value) ? value : null;
    }

    public String forRecommendation(boolean clipboardShowing) {
        return clipboardShowing ? null : packageName;
    }
}
