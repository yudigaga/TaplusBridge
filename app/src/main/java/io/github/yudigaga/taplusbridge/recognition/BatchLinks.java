package io.github.yudigaga.taplusbridge.recognition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A bounded, ordered snapshot of supplied download links; never silently truncates a batch. */
public final class BatchLinks {
    public static final String LINKS_KEY = "links";
    public static final int MAX_LINKS = 64, MAX_CHARS = 24 * 1024;
    private BatchLinks() { }

    public static List<String> normalize(Object value) {
        if (!(value instanceof List<?> supplied) || supplied.isEmpty() || supplied.size() > MAX_LINKS)
            throw invalid();
        Set<String> unique = new LinkedHashSet<>();
        int chars = 0;
        for (Object item : supplied) {
            if (!(item instanceof String link) || !RouteLink.valid(link) || RouteLink.validHttp(link)) throw invalid();
            chars += link.length() + (chars == 0 ? 0 : 1);
            if (chars > MAX_CHARS) throw invalid();
            unique.add(link);
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    /** The inspected 115 direct magnet/ed2k receiver preserves the whole multiline editor payload. */
    public static boolean supportedTarget(String packageName) {
        return "com.ylmf.androidclient".equals(packageName);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_batch_links"); }
}
