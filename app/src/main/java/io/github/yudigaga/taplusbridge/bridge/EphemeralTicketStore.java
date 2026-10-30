package io.github.yudigaga.taplusbridge.bridge;

import android.os.Bundle;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Base64;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/** Probe URLs never enter disk-backed launch tickets. A process restart intentionally loses them. */
final class EphemeralTicketStore {
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private record Entry(long issued, Bundle request) { }
    private EphemeralTicketStore() { }
    static synchronized String issue(Bundle request) {
        long now = SystemClock.elapsedRealtime();
        ENTRIES.entrySet().removeIf(e -> !live(e.getValue(), now));
        if (request == null || ENTRIES.size() >= TicketPolicy.CAPACITY) return null;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String token = "p_" + Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        Bundle copy = new Bundle(request); copy.remove("original"); copy.putBoolean("ephemeral", true);
        @SuppressWarnings("deprecation") Intent candidate = request.getParcelable("candidate");
        if (candidate != null) copy.putParcelable("candidate", new Intent(candidate));
        ENTRIES.put(token, new Entry(now, copy)); return token;
    }
    static synchronized Bundle consume(String token) {
        Entry entry = token == null ? null : ENTRIES.remove(token);
        return live(entry, SystemClock.elapsedRealtime()) ? new Bundle(entry.request()) : null;
    }
    private static boolean live(Entry entry, long now) {
        return entry != null && now >= entry.issued() && now - entry.issued() < 30_000;
    }
}
