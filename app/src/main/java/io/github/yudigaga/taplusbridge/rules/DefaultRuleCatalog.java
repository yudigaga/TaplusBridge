package io.github.yudigaga.taplusbridge.rules;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/** Exact owned online revision bundled for the first verified cache; never a hard-coded host map. */
public final class DefaultRuleCatalog {
    public static final long SEED_REVISION = 1;
    public static final String SEED_SHA256 = "e840f50919b022b085621c34394c7d09fde1373e22467e490bbb8c8633a05203";
    public static final String MANIFEST_ASSET = "icebridge-rules/manifest.json";
    public static final String SNAPSHOT_ASSET = "icebridge-rules/revisions/rules-000001.json";
    private DefaultRuleCatalog() { }
    /** Matches current IceBridge: no built-in runtime routing candidates. */
    public static List<LinkRuleEntry> builtInRules() { return Collections.emptyList(); }
    public static byte[] seedManifest(Context context) throws IOException { return read(context, MANIFEST_ASSET, 16 * 1024); }
    public static byte[] seedSnapshot(Context context) throws IOException { return read(context, SNAPSHOT_ASSET, 1024 * 1024); }
    private static byte[] read(Context context, String asset, int limit) throws IOException {
        try (InputStream stream = context.getAssets().open(asset); ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (result.size() + count > limit) throw new IOException("bundled-rule-size");
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        }
    }
}
