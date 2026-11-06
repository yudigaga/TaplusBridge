package io.github.yudigaga.taplusbridge.links;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.recognition.RouteLink;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Same-process, one-use capabilities keep recognized link collections out of exported Intents. */
public final class LinkChoiceRequests {
    public static final int MAX_LINKS = 64, MAX_CHARS = 128 * 1024;
    private static final long TTL = 30_000;
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private record Entry(long issued, List<String> links, String source, boolean clipboard, String identity) { }
    private LinkChoiceRequests() { }

    public static synchronized Intent prepare(Context context, List<String> supplied, String source, boolean clipboard) {
        try {
            List<String> links = normalize(supplied);
            RuntimeSnapshot current = active(context, source, clipboard);
            if (current == null) return null;
            long now = SystemClock.elapsedRealtime();
            ENTRIES.entrySet().removeIf(item -> !live(item.getValue(), now));
            if (ENTRIES.size() >= 16) return null;
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            String token = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
            ENTRIES.put(token, new Entry(now, links, source, clipboard, current.identity));
            new Handler(Looper.getMainLooper()).postDelayed(() -> remove(token), TTL);
            return new Intent().setComponent(new ComponentName(BridgeContract.APP_PACKAGE,
                    BridgeContract.APP_PACKAGE + ".links.LinkChoiceActivity"))
                    .putExtra("token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (RuntimeException malformed) { return null; }
    }

    public static synchronized Bundle consume(Context context, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        Entry entry = ENTRIES.remove(token);
        if (!live(entry, SystemClock.elapsedRealtime()) || !isCurrent(context, entry.source, entry.clipboard, entry.identity)) return null;
        Bundle result = new Bundle(); result.putStringArrayList("links", new ArrayList<>(entry.links));
        if (entry.source != null) result.putString("source", entry.source);
        result.putBoolean("clipboard", entry.clipboard); result.putString("identity", entry.identity);
        return result;
    }

    public static boolean isCurrent(Context context, String source, boolean clipboard, String identity) {
        RuntimeSnapshot current = active(context, source, clipboard);
        return identity != null && current != null && identity.equals(current.identity);
    }

    static List<String> normalize(Object value) {
        if (!(value instanceof List<?> links) || links.isEmpty() || links.size() > MAX_LINKS) throw invalid();
        int chars = 0; LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object item : links) {
            if (!(item instanceof String link) || !RouteLink.valid(link)) throw invalid();
            chars += link.length() + (chars == 0 ? 0 : 1); if (chars > MAX_CHARS) throw invalid();
            unique.add(link);
        }
        return List.copyOf(unique);
    }

    private static RuntimeSnapshot active(Context context, String source, boolean clipboard) {
        if (context == null || source != null && !HostPolicy.validPackage(source) || clipboard && source != null) return null;
        try {
            RuntimeSnapshot current = RuntimeSnapshot.parse(RuntimeSnapshot.from(context));
            if (!current.enabled || !current.recognition || clipboard && !current.channels.enabled(RecognitionChannels.Channel.CLIPBOARD)
                    || source != null && !current.sourceScope.allows(source)) return null;
            return current;
        } catch (RuntimeException unavailable) { return null; }
    }
    private static boolean live(Entry entry, long now) { return entry != null && now >= entry.issued && now - entry.issued < TTL; }
    private static synchronized void remove(String token) { ENTRIES.remove(token); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_link_choices"); }
}
