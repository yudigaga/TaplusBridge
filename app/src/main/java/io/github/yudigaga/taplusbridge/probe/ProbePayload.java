package io.github.yudigaga.taplusbridge.probe;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
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
            if (!IntentObservation.validHttpUrl(value)) return;
            if (urls.size() >= IntentObservation.MAX_URLS && !urls.contains(value)) {
                incomplete = true;
                return;
            }
            urls.add(value);
        }
    }
}
