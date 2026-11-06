package io.github.yudigaga.taplusbridge.bridge;

import android.os.Bundle;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Base64;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import io.github.yudigaga.taplusbridge.recognition.BatchLinks;

/** Probe URLs never enter disk-backed launch tickets. A process restart intentionally loses them. */
final class EphemeralTicketStore {
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private record Entry(long issued, Bundle request) { }
    private EphemeralTicketStore() { }
    static synchronized String issue(Bundle request) {
        long now = SystemClock.elapsedRealtime();
        ENTRIES.entrySet().removeIf(e -> !live(e.getValue(), now));
        if (request == null || ENTRIES.size() >= TicketPolicy.CAPACITY) return null;
        ArrayList<String> links = null;
        if (request.containsKey(BatchLinks.LINKS_KEY)) {
            try {
                if (request.containsKey("url") || request.containsKey("candidate")) return null;
                links = new ArrayList<>(BatchLinks.normalize(request.get(BatchLinks.LINKS_KEY)));
            } catch (RuntimeException malformed) { return null; }
        }
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String token = "p_" + Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        Bundle copy = new Bundle(request); copy.remove("original"); copy.putBoolean("ephemeral", true);
        if (links != null) copy.putStringArrayList(BatchLinks.LINKS_KEY, links);
        @SuppressWarnings("deprecation") Intent candidate = request.getParcelable("candidate");
        if (candidate != null) copy.putParcelable("candidate", new Intent(candidate));
        ENTRIES.put(token, new Entry(now, copy)); return token;
    }
    static synchronized Bundle consume(String token) {
        Entry entry = token == null ? null : ENTRIES.remove(token);
        if (!live(entry, SystemClock.elapsedRealtime())) return null;
        Bundle copy = new Bundle(entry.request());
        if (copy.containsKey(BatchLinks.LINKS_KEY))
            copy.putStringArrayList(BatchLinks.LINKS_KEY, new ArrayList<>(BatchLinks.normalize(copy.get(BatchLinks.LINKS_KEY))));
        return copy;
    }
    private static boolean live(Entry entry, long now) {
        return entry != null && now >= entry.issued() && now - entry.issued() < 30_000;
    }
}
