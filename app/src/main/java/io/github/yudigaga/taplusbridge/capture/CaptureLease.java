package io.github.yudigaga.taplusbridge.capture;

/**
 * One short-lived observation window. This class never changes an Intent, starts an Activity,
 * stores a URL, or authenticates the module owner; the Android broker must authenticate arm/cancel.
 */
public final class CaptureLease {
    public static final long TTL_MILLIS = 15_000L;
    public static final String WECHAT = "com.tencent.mm";
    public static final String QQ = "com.tencent.mobileqq";
    private static final int UID_RANGE = 100_000;

    /** Immutable identity for reporting even when a later arm has already replaced this lease. */
    public record Observation(long generation, String sourcePackage, int sourceUid, int userId,
                              String destinationPackage, String destinationClass,
                              long armedAt, long observedAt) { }

    private record Lease(long generation, String sourcePackage, int sourceUid, int userId, long armedAt) { }
    private long lastGeneration;
    private Lease current;

    /** Uses monotonic elapsed milliseconds. Invalid requests leave a valid existing lease intact. */
    public synchronized long arm(String sourcePackage, int sourceUid, int userId, long now) {
        if (!allowedSource(sourcePackage) || !validIdentity(sourceUid, userId) || now < 0) {
            throw new IllegalArgumentException("Invalid observation identity");
        }
        if (lastGeneration == Long.MAX_VALUE) throw new IllegalStateException("Observation generation exhausted");
        long generation = ++lastGeneration;
        current = new Lease(generation, sourcePackage, sourceUid, userId, now);
        return generation;
    }

    /**
     * Consumes the first supported launch, including a launch with no recoverable URL.
     * Calling package/UID and user must all identify the armed source; another event cannot consume it.
     */
    public synchronized Observation observe(String sourcePackage, int callingUid, int userId,
                                             String destinationPackage, String destinationClass, long now) {
        Lease lease = liveLease(now);
        if (lease == null || !validIdentity(callingUid, userId)
                || !lease.sourcePackage().equals(sourcePackage) || lease.sourceUid() != callingUid
                || lease.userId() != userId || !expectedDestination(sourcePackage, destinationPackage, destinationClass)) {
            return null;
        }
        current = null;
        return new Observation(lease.generation(), lease.sourcePackage(), lease.sourceUid(), lease.userId(),
                destinationPackage, destinationClass, lease.armedAt(), now);
    }

    /** A stale completion/cancel from an older generation must never cancel a newly armed request. */
    public synchronized boolean cancel(long generation) {
        if (current == null || current.generation() != generation) return false;
        current = null;
        return true;
    }

    /** Called only after the Android broker has authenticated its owner/app cancellation request. */
    public synchronized boolean cancelOwner(String sourcePackage, int sourceUid, int userId) {
        if (current == null || !current.sourcePackage().equals(sourcePackage)
                || current.sourceUid() != sourceUid || current.userId() != userId) return false;
        current = null;
        return true;
    }

    public synchronized boolean isArmed(long now) { return liveLease(now) != null; }

    public synchronized void clear() { current = null; }

    public static boolean allowedSource(String sourcePackage) {
        return WECHAT.equals(sourcePackage) || QQ.equals(sourcePackage);
    }

    public static boolean expectedDestination(String sourcePackage, String destinationPackage, String destinationClass) {
        if (!allowedSource(sourcePackage) || !sourcePackage.equals(destinationPackage)) return false;
        if (WECHAT.equals(sourcePackage)) {
            return "com.tencent.mm.plugin.webview.ui.tools.WebViewUI".equals(destinationClass)
                    || "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI".equals(destinationClass);
        }
        return "com.tencent.mobileqq.activity.QQBrowserActivity".equals(destinationClass);
    }

    private static boolean validIdentity(int uid, int userId) {
        return uid >= 0 && userId >= 0 && uid / UID_RANGE == userId;
    }

    private Lease liveLease(long now) {
        Lease lease = current;
        if (lease == null || now < 0 || now < lease.armedAt()) return null;
        if (now - lease.armedAt() >= TTL_MILLIS) {
            current = null;
            return null;
        }
        return lease;
    }
}
