package io.github.yudigaga.taplusbridge.bridge;

import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;

/** Short-lived cache for the installed bit used by declarative domain routing. */
final class InstalledTargetCache {
    private static final long TTL_NANOS = 5_000_000_000L;
    private static final int MAX_ENTRIES = 128;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    synchronized boolean isInstalled(String packageName, long nowNanos, BooleanSupplier query) {
        Entry cached = entries.get(packageName);
        if (cached != null && nowNanos >= cached.atNanos() && nowNanos - cached.atNanos() < TTL_NANOS) {
            return cached.installed();
        }
        boolean installed = query.getAsBoolean();
        entries.put(packageName, new Entry(nowNanos, installed));
        while (entries.size() > MAX_ENTRIES) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
        return installed;
    }

    synchronized void invalidate(String packageName) {
        if (packageName == null || packageName.isBlank()) entries.clear();
        else entries.remove(packageName);
    }

    private record Entry(long atNanos, boolean installed) { }
}
