package io.github.yudigaga.taplusbridge.config;

import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Complete source selection. A malformed saved selection is rejected, never treated as unmanaged. */
public final class SourceScopePolicy {
    public enum Mode { BLACKLIST, WHITELIST }
    public static final String KEY = "source_scope";
    public static final int SCHEMA = 1, MAX_PACKAGES = 4096, MAX_BYTES = 300_000;
    public final boolean managed;
    public final Mode mode;
    public final Set<String> blacklist, whitelist;

    private SourceScopePolicy(boolean managed, Mode mode, Collection<String> blacklist,
                              Collection<String> whitelist) {
        if (mode == null || blacklist == null || whitelist == null
                || (long) blacklist.size() + whitelist.size() > MAX_PACKAGES)
            throw invalid();
        this.managed = managed;
        this.mode = mode;
        this.blacklist = checked(blacklist);
        this.whitelist = checked(whitelist);
        if (!managed && (mode != Mode.BLACKLIST || !this.blacklist.isEmpty() || !this.whitelist.isEmpty()))
            throw invalid();
    }

    public static SourceScopePolicy unmanaged() {
        return new SourceScopePolicy(false, Mode.BLACKLIST, Set.of(), Set.of());
    }

    public static SourceScopePolicy of(Mode mode, Collection<String> blacklist, Collection<String> whitelist) {
        return new SourceScopePolicy(true, mode, blacklist, whitelist);
    }

    public Set<String> packages() { return mode == Mode.BLACKLIST ? blacklist : whitelist; }

    public boolean allows(String packageName) {
        return HostPolicy.validPackage(packageName)
                && (!managed || (mode == Mode.BLACKLIST ? !blacklist.contains(packageName) : whitelist.contains(packageName)));
    }

    public String encode() {
        try {
            JSONObject data = new JSONObject();
            data.put("schema", SCHEMA); data.put("managed", managed); data.put("mode", mode.name());
            data.put("blacklist", new JSONArray(blacklist)); data.put("whitelist", new JSONArray(whitelist));
            String raw = data.toString();
            if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
            return raw;
        } catch (JSONException impossible) { throw invalid(); }
    }

    public static SourceScopePolicy parse(String raw) {
        try {
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
            JSONObject data = new JSONObject(raw);
            Object schema = data.get("schema"), managed = data.get("managed"), mode = data.get("mode");
            if (data.length() != 5 || !(schema instanceof Integer) || ((Integer) schema) != SCHEMA
                    || !(managed instanceof Boolean) || !(mode instanceof String)) throw invalid();
            JSONArray black = data.getJSONArray("blacklist"), white = data.getJSONArray("whitelist");
            if ((long) black.length() + white.length() > MAX_PACKAGES) throw invalid();
            return new SourceScopePolicy((Boolean) managed, Mode.valueOf((String) mode), read(black), read(white));
        } catch (JSONException | RuntimeException invalid) { throw invalid(); }
    }

    private static Set<String> read(JSONArray data) throws JSONException {
        Set<String> values = new LinkedHashSet<>();
        for (int i = 0; i < data.length(); i++) {
            Object item = data.get(i);
            if (!(item instanceof String value) || !values.add(value)) throw invalid();
        }
        return values;
    }

    private static Set<String> checked(Collection<String> packages) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : packages) {
            if (!HostPolicy.validPackage(value) || !sorted.add(value)) throw invalid();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("source_scope_invalid"); }
}
