package io.github.yudigaga.taplusbridge.probe;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import io.github.yudigaga.taplusbridge.recognition.RedirectLink;
import io.github.yudigaga.taplusbridge.recognition.RouteLink;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** Extracts a unique in-memory URL from an owned source-page request. Never logs or stores it. */
public final class ProbePayload {
    private static final String[] URL_FIELDS = {"rawUrl", "url", Intent.EXTRA_TEXT};
    private static final String[] CONTAINERS = {
            "extras", "bundle", "data", "params", "intent_extras", "webview_params"
    };

    private ProbePayload() { }

    static String normalizedLink(String value) {
        return RouteLink.valid(value) && !RouteLink.validHttp(value) ? value : RedirectLink.normalize(value);
    }

    static boolean invalidLink(String value, String normalized) {
        boolean download = value != null && (value.regionMatches(true, 0, "magnet:", 0, 7)
                || value.regionMatches(true, 0, "ed2k:", 0, 5));
        return (download || RedirectLink.validInput(value)) && !RouteLink.valid(normalized);
    }

    /**
     * Only known webpage components and String leaves are considered. An incomplete or ambiguous
     * scan returns null, even if one usable URL was found before the failure.
     */
    public static String uniqueHttpUrl(Intent intent) {
        if (intent == null) return null;
        try {
            if (!IntentObservation.isSupportedComponent(intent.getComponent())) return null;
            Scan scan = new Scan();
            Uri data = intent.getData();
            if (data != null) scan.add(data.toString());
            scan(intent.getExtras(), 0, scan);
            return !scan.incomplete && scan.urls.size() == 1 ? scan.urls.iterator().next() : null;
        } catch (RuntimeException unreadablePayload) {
            return null;
        }
    }

    /** Read actual URL values from a same-source page launch, without naming individual applications. */
    static String uniqueSourcePageUrl(Intent intent, String source) {
        if (intent == null || intent.getComponent() == null
                || !source.equals(intent.getComponent().getPackageName())) return null;
        try {
            GenericScan scan = new GenericScan();
            String data = intent.getDataString();
            if (data != null) scan.add(data);
            scan.visit(intent.getExtras(), 0);
            return !scan.invalid && scan.urls.size() == 1 ? scan.urls.iterator().next() : null;
        } catch (RuntimeException unreadable) { return null; }
    }

    private static final class GenericScan {
        final Set<Bundle> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> urls = new LinkedHashSet<>();
        int nodes;
        boolean invalid;
        void add(String value) {
            String target = normalizedLink(value);
            if (invalidLink(value, target)) invalid = true;
            if (RouteLink.valid(target)) urls.add(target);
            if (urls.size() > IntentObservation.MAX_URLS) invalid = true;
        }
        @SuppressWarnings("deprecation") void visit(Bundle bundle, int depth) {
            if (bundle == null || !seen.add(bundle)) return;
            if (depth > IntentObservation.MAX_DEPTH) { invalid = true; return; }
            for (String key : bundle.keySet()) {
                if (++nodes > IntentObservation.MAX_NODES) { invalid = true; return; }
                String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("thumb") || lower.contains("icon") || lower.contains("image")
                        || lower.contains("cover") || lower.contains("avatar")) continue;
                Object value = bundle.get(key);
                if (value instanceof String text && (Intent.EXTRA_TEXT.equals(key)
                        || Set.of("url","rawurl","weburl","link","href","landingpage","destination","targeturl","uri")
                        .contains(lower.replace("_","").replace("-","")))) add(text);
                else if (value instanceof Bundle nested) visit(nested, depth + 1);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void scan(Bundle bundle, int depth, Scan scan) {
        if (bundle == null || scan.incomplete || !scan.visited.add(bundle)) return;
        if (depth > IntentObservation.MAX_DEPTH) { scan.incomplete = true; return; }
        for (String key : URL_FIELDS) {
            if (!bundle.containsKey(key)) continue;
            if (!scan.consumeNode()) return;
            Object value = bundle.get(key);
            if (value instanceof String text) scan.add(text);
            else if (value != null) { scan.incomplete = true; return; }
            if (scan.incomplete) return;
        }
        for (String key : CONTAINERS) {
            if (!bundle.containsKey(key)) continue;
            if (!scan.consumeNode()) return;
            Object value = bundle.get(key);
            if (value instanceof Bundle nested) scan(nested, depth + 1, scan);
            else if (value != null) { scan.incomplete = true; return; }
            if (scan.incomplete) return;
        }
    }

    private static final class Scan {
        final Set<Bundle> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> urls = new LinkedHashSet<>();
        int nodes;
        boolean incomplete;

        boolean consumeNode() {
            if (++nodes <= IntentObservation.MAX_NODES) return true;
            incomplete = true;
            return false;
        }

        void add(String value) {
            String target = normalizedLink(value);
            if (invalidLink(value, target)) { incomplete = true; return; }
            if (!RouteLink.valid(target)) return;
            if (urls.size() >= IntentObservation.MAX_URLS && !urls.contains(target)) {
                incomplete = true;
                return;
            }
            urls.add(target);
        }
    }
}
