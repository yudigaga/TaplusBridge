package io.github.yudigaga.taplusbridge.drag;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Base64;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** One recognition round retains its original content until the user chooses what to use. */
public final class DragActionStore {
    public static final long TTL = 60_000;
    private static Entry current;
    private static Ready ready;
    private record Entry(String token, String action, DragPayload payload, long issued, boolean recognize, boolean claimed) { }
    private record Ready(String token, Choice choice, long issued) { }
    public record Choice(String action, DragPayload payload, String originalText, String recognizedText) { }
    private DragActionStore() { }

    public static synchronized String issue(Context context, String action, DragPayload payload) {
        return issue(action, payload, true);
    }

    public static synchronized String issueExecute(Context context, String action, DragPayload payload) {
        if (!List.of("portal", "lens", "translate").contains(action)
                || ("lens".equals(action) && (payload == null || payload.image == null))) throw new IllegalArgumentException("drag_action");
        return issue(action, payload, false);
    }

    private static String issue(String action, DragPayload payload, boolean recognize) {
        if (!DragSettings.ACTIONS.contains(action) || "none".equals(action) || payload == null)
            throw new IllegalArgumentException("drag_action");
        if (current != null) current.payload.close();
        if (ready != null) { ready.choice.payload.close(); ready = null; }
        String token = token();
        current = new Entry(token, action, payload, SystemClock.elapsedRealtime(), recognize, false);
        new Handler(Looper.getMainLooper()).postDelayed(() -> cancel(token), TTL);
        return token;
    }

    private static String token() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    public static synchronized Bundle take(Context context, String token) {
        Entry entry = current;
        if (entry == null || token == null || !token.matches("[A-Za-z0-9_-]{43}") || !entry.token.equals(token) || entry.claimed) return null;
        boolean retained = false;
        try {
            long age = SystemClock.elapsedRealtime() - entry.issued;
            if (age < 0 || age >= TTL) return null;
            DragPayload payload = entry.payload;
            Bundle result = new Bundle(); result.putString("action", entry.action);
            result.putBoolean("recognize", entry.recognize);
            result.putString("text", payload.text); result.putString("source", payload.source);
            result.putStringArrayList("links", new ArrayList<>(payload.links()));
            result.putString("mime", payload.mime); result.putInt("width", payload.width); result.putInt("height", payload.height);
            result.putLong("issued_elapsed", entry.issued);
            if (payload.image != null) {
                if (payload.image.length() <= 0 || payload.image.length() > DragPayload.MAX_BYTES) return null;
                result.putParcelable("fd", ParcelFileDescriptor.open(payload.image, ParcelFileDescriptor.MODE_READ_ONLY));
            }
            if (entry.recognize) {
                current = new Entry(entry.token, entry.action, entry.payload, entry.issued, true, true); retained = true;
            }
            return result;
        } catch (IOException | RuntimeException unavailable) { return null; }
        finally { if (!retained) { current = null; entry.payload.close(); } }
    }

    public static synchronized String complete(Context context, String token, String text) {
        return complete(context, token, text, List.of());
    }

    public static synchronized String complete(Context context, String token, String text, List<String> links) {
        Entry entry = current;
        if (entry == null || !entry.token.equals(token) || !entry.claimed || !entry.recognize) return null;
        long age = SystemClock.elapsedRealtime() - entry.issued;
        if (age < 0 || age >= TTL) { cancel(token); return null; }
        String recognized = text == null ? "" : text;
        if (recognized.length() > DragPayload.MAX_TEXT || !DragPayload.validRecognizedLinks(links)) return null;
        DragPayload result = entry.payload.withRecognizedLinks(recognized, links);
        String resultToken = token(); current = null;
        ready = new Ready(resultToken, new Choice(entry.action, result, entry.payload.text, recognized), SystemClock.elapsedRealtime());
        new Handler(Looper.getMainLooper()).postDelayed(() -> cancel(resultToken), TTL);
        return resultToken;
    }

    public static synchronized Choice takeChoice(String token) {
        Ready result = ready;
        if (result == null || token == null || !result.token.equals(token)) return null;
        ready = null;
        long age = SystemClock.elapsedRealtime() - result.issued;
        if (age < 0 || age >= TTL) { result.choice.payload.close(); return null; }
        return result.choice;
    }

    public static synchronized void cancel(String token) {
        if (current != null && current.token.equals(token)) { current.payload.close(); current = null; }
        if (ready != null && ready.token.equals(token)) { ready.choice.payload.close(); ready = null; }
    }
}
