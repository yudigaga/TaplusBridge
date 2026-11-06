package io.github.yudigaga.taplusbridge.translation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authenticated callers receive only a short-lived capability, never configuration or raw text. */
public final class TranslationRequests {
    private static final long TTL = 30_000;
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private record Entry(long issued, String text, String source, String profile) {}
    private TranslationRequests() {}

    public static synchronized Intent prepare(Context context, String text, String source) {
        if (text == null || text.isBlank() || text.length() > 32768 || source != null && !HostPolicy.validPackage(source)) return null;
        TranslationSettings settings = active(context, source);
        if (settings == null) return null;
        if (settings.mode == TranslationSettings.Mode.APP) {
            try { TranslationApps.intent(context, settings, text); }
            catch (RuntimeException unavailable) { return null; }
        }
        long now = SystemClock.elapsedRealtime();
        ENTRIES.entrySet().removeIf(item -> !live(item.getValue(), now));
        if (ENTRIES.size() >= 16) return null;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String token = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        ENTRIES.put(token, new Entry(now, text, source, settings.toJson()));
        new Handler(Looper.getMainLooper()).postDelayed(() -> remove(token), TTL);
        return new Intent().setComponent(new ComponentName(BridgeContract.APP_PACKAGE,
                BridgeContract.APP_PACKAGE + ".translation.TranslationActivity"))
                .putExtra("token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static synchronized Bundle consume(Context context, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        Entry entry = ENTRIES.remove(token);
        if (!live(entry, SystemClock.elapsedRealtime())) return null;
        TranslationSettings current = active(context, entry.source);
        if (current == null || !current.toJson().equals(entry.profile)) return null;
        Bundle result = new Bundle(); result.putString("text", entry.text);
        if (entry.source != null) result.putString("source", entry.source);
        return result;
    }

    public static boolean isCurrent(Context context, TranslationSettings settings, String source) {
        TranslationSettings current = active(context, source);
        return current != null && settings != null && current.toJson().equals(settings.toJson());
    }

    private static TranslationSettings active(Context context, String source) {
        try {
            ConfigStore config = new ConfigStore(context);
            if (!config.enabled()) return null;
            var policy = config.sourceScope();
            // Explicit host clipboard translation has no originating package; authentication still applies.
            if (source != null && policy.managed && !policy.allows(source)) return null;
            TranslationSettings settings = new TranslationStore(context).load();
            if (settings.mode == TranslationSettings.Mode.NATIVE) return null;
            settings.validate(); return settings;
        } catch (RuntimeException invalid) { return null; }
    }
    private static boolean live(Entry entry, long now) { return entry != null && now >= entry.issued && now - entry.issued < TTL; }
    private static synchronized void remove(String token) { ENTRIES.remove(token); }
}
