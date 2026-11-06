package io.github.yudigaga.taplusbridge.capture;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** Read-only metadata for an explicitly armed browser-Activity observation. Never retains a URL. */
public final class IntentObservation {
    public static final int MAX_DEPTH = 4;
    public static final int MAX_NODES = 64;
    public static final int MAX_URL_LENGTH = 8192;
    public static final int MAX_URLS = 8;

    public static final int DATA_PRESENT = 1;
    public static final int RAW_URL_PRESENT = 2;
    public static final int URL_PRESENT = 4;
    public static final int TEXT_PRESENT = 8;
    /** At least one known top-level container held a Bundle, not its arbitrary field names. */
    public static final int NESTED_PRESENT = 16;

    private static final String[] URL_FIELDS = {"rawUrl", "url", Intent.EXTRA_TEXT};
    private static final int[] URL_FIELD_BITS = {RAW_URL_PRESENT, URL_PRESENT, TEXT_PRESENT};
    private static final UrlOrigin[] URL_ORIGINS = {UrlOrigin.RAW_URL, UrlOrigin.URL, UrlOrigin.TEXT};
    // Only these container names are traversed. Arbitrary extras, Parcelable objects, arrays,
    // serialized Intents, and objects' toString methods are deliberately not inspected.
    private static final String[] NESTED_FIELDS = {
            "extras", "bundle", "data", "params", "intent_extras", "webview_params"
    };

    public enum Outcome { URL_FOUND, NO_URL, UNREADABLE_PAYLOAD, UNSUPPORTED_COMPONENT }
    public enum UrlOrigin { DATA, RAW_URL, URL, TEXT, NESTED, NONE }

    /** Contains only allowlisted component identity, counters, flags, and fixed enums. */
    public record Observation(String componentPackage, String componentClass, Outcome outcome,
                              boolean urlFound, int urlCount, UrlOrigin urlOrigin, int fieldPresence) {
        @Override public String toString() {
            String component = supported(componentPackage, componentClass)
                    ? componentPackage + '/' + componentClass : "unsupported";
            return "IntentObservation{component=" + component + ", outcome=" + outcome
                    + ", urlFound=" + urlFound + ", urlCount=" + urlCount
                    + ", urlOrigin=" + urlOrigin + ", fieldPresence=" + fieldPresence + '}';
        }
    }

    private IntentObservation() { }

    public static boolean isSupportedComponent(ComponentName component) {
        return component != null && supported(component.getPackageName(), component.getClassName());
    }

    public static Observation inspect(Intent intent) {
        ComponentName component;
        try { component = intent == null ? null : intent.getComponent(); }
        catch (RuntimeException failure) { return unsupported(); }
        if (!isSupportedComponent(component)) return unsupported();
        ScanState state = new ScanState();
        try {
            Uri data = intent.getData();
            if (data != null) {
                state.presence |= DATA_PRESENT;
                state.add(data.toString(), UrlOrigin.DATA);
            }
        } catch (RuntimeException failure) { state.unreadable = true; }
        try { scan(intent.getExtras(), 0, state); }
        catch (RuntimeException failure) { state.unreadable = true; }
        boolean found = !state.urls.isEmpty();
        Outcome outcome = state.unreadable ? Outcome.UNREADABLE_PAYLOAD
                : found ? Outcome.URL_FOUND : Outcome.NO_URL;
        return new Observation(component.getPackageName(), component.getClassName(), outcome,
                found, state.urls.size(), found ? state.origin : UrlOrigin.NONE, state.presence);
    }

    private static Observation unsupported() {
        return new Observation(null, null, Outcome.UNSUPPORTED_COMPONENT, false, 0, UrlOrigin.NONE, 0);
    }

    private static boolean supported(String packageName, String className) {
        return CaptureLease.expectedDestination(packageName, packageName, className);
    }

    @SuppressWarnings("deprecation")
    private static void scan(Bundle bundle, int depth, ScanState state) {
        if (bundle == null || state.urls.size() >= MAX_URLS || !state.visited.add(bundle)) return;
        if (depth > MAX_DEPTH) { state.unreadable = true; return; }
        for (int i = 0; i < URL_FIELDS.length; i++) {
            if (state.urls.size() >= MAX_URLS) return;
            try {
                if (!bundle.containsKey(URL_FIELDS[i])) continue;
                if (depth == 0) state.presence |= URL_FIELD_BITS[i];
                if (!state.consumeNode()) return;
                Object value = bundle.get(URL_FIELDS[i]);
                // String only: application-specific CharSequences/Parcelables are not evaluated
                // in system_server, and a title around a URL is not silently reinterpreted.
                if (value instanceof String text) state.add(text, depth == 0 ? URL_ORIGINS[i] : UrlOrigin.NESTED);
            } catch (RuntimeException failure) { state.unreadable = true; }
        }
        for (String name : NESTED_FIELDS) {
            if (state.urls.size() >= MAX_URLS) return;
            if (state.nodes >= MAX_NODES) { state.unreadable = true; return; }
            try {
                if (!bundle.containsKey(name)) continue;
                if (!state.consumeNode()) return;
                Object value = bundle.get(name);
                if (value instanceof Bundle nested) {
                    if (depth == 0) state.presence |= NESTED_PRESENT;
                    scan(nested, depth + 1, state);
                }
            } catch (RuntimeException failure) { state.unreadable = true; }
        }
    }

    /** Validates an entire whitelisted field. No substring scanning or percent decoding. */
    public static boolean validHttpUrl(String value) {
        return io.github.yudigaga.taplusbridge.recognition.RouteLink.validHttp(value, MAX_URL_LENGTH);
    }

    private static final class ScanState {
        final Set<Bundle> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        // Temporary only. The public result contains neither these URLs nor a reference to this state.
        final Set<String> urls = new LinkedHashSet<>();
        UrlOrigin origin = UrlOrigin.NONE;
        int nodes;
        int presence;
        boolean unreadable;

        boolean consumeNode() {
            if (++nodes <= MAX_NODES) return true;
            unreadable = true;
            return false;
        }

        void add(String candidate, UrlOrigin candidateOrigin) {
            if (urls.size() >= MAX_URLS || !validHttpUrl(candidate)) return;
            if (urls.add(candidate) && origin == UrlOrigin.NONE) origin = candidateOrigin;
        }
    }
}
