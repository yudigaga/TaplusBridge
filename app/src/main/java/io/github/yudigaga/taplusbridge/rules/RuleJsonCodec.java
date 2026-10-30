package io.github.yudigaga.taplusbridge.rules;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.io.StringReader;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded, declarative local snapshot; executable fields and lossy projections are not accepted. */
public final class RuleJsonCodec {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
            "sourcePackage", "host", "package", "targetPackage", "source", "conversion", "note"));
    private RuleJsonCodec() { }

    public static String encode(List<LinkRuleEntry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (LinkRuleEntry entry : RuleSchema.deduplicate(entries)) array.put(item(entry, true));
            String result = array.toString(); requireSize(result); return result;
        } catch (JSONException invalid) { throw new IllegalArgumentException("rule-json", invalid); }
    }
    public static List<LinkRuleEntry> decode(String json) {
        requireSize(json);
        requireSyntax(json);
        try { return readArray(new JSONArray(withoutBom(json)), null); }
        catch (JSONException invalid) { throw new IllegalArgumentException("rule-json", invalid); }
    }
    static List<LinkRuleEntry> readArray(JSONArray array, String forcedOrigin) throws JSONException {
        if (array.length() > RuleSchema.MAX_RULES) throw new IllegalArgumentException("too-many-rules");
        List<LinkRuleEntry> entries = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object raw = array.get(i);
            if (!(raw instanceof JSONObject item)) throw new IllegalArgumentException("rule-object-required");
            requireKeys(item, FIELDS);
            String source = string(item, "sourcePackage", "*");
            String target = string(item, "package", null), alternate = string(item, "targetPackage", null);
            if (target != null && alternate != null && !target.equals(alternate)) throw new IllegalArgumentException("conflicting-target");
            String origin = forcedOrigin == null ? string(item, "source", null) : forcedOrigin;
            String declaredOrigin = string(item, "source", null);
            if (forcedOrigin != null && declaredOrigin != null && !forcedOrigin.equals(declaredOrigin))
                throw new IllegalArgumentException("invalid-transfer-origin");
            entries.add(RuleSchema.requireValid(new LinkRuleEntry(source, string(item, "host", null),
                    target == null ? alternate : target, origin,
                    string(item, "conversion", LinkRuleEntry.CONVERSION_NONE), string(item, "note", ""))));
        }
        return RuleSchema.deduplicate(entries);
    }
    static JSONObject item(LinkRuleEntry entry, boolean origin) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("sourcePackage", entry.sourcePackage); item.put("host", entry.host);
        item.put("package", entry.targetPackage); item.put("conversion", entry.urlConversion);
        item.put("note", entry.note); if (origin) item.put("source", entry.source);
        return item;
    }
    static String string(JSONObject object, String name, String fallback) throws JSONException {
        if (!object.has(name) || object.isNull(name)) return fallback;
        Object value = object.get(name);
        if (!(value instanceof String)) throw new IllegalArgumentException("string-field-required");
        return (String) value;
    }
    static void requireKeys(JSONObject object, Set<String> allowed) {
        for (Iterator<String> keys = object.keys(); keys.hasNext();) {
            if (!allowed.contains(keys.next())) throw new IllegalArgumentException("unknown-rule-field");
        }
    }
    static void requireSize(String json) {
        if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("empty-rule-json");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("rule-json-too-large");
    }
    static String withoutBom(String json) { return json.startsWith("\uFEFF") ? json.substring(1) : json; }
    /** Platform JSONObject accepts duplicate keys/comments; preflight the complete document first. */
    static void requireSyntax(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(withoutBom(json)))) {
            reader.setLenient(false);
            consume(reader, 0, new int[]{0});
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing-rule-data");
        } catch (IOException | IllegalStateException invalid) { throw new IllegalArgumentException("invalid-rule-json", invalid); }
    }
    private static void consume(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 16 || ++nodes[0] > 10000) throw new IllegalArgumentException("rule-json-complexity");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    if (!names.add(reader.nextName())) throw new IllegalArgumentException("duplicate-rule-key");
                    consume(reader, depth + 1, nodes);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); while (reader.hasNext()) consume(reader, depth + 1, nodes); reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IllegalArgumentException("invalid-rule-token");
        }
    }
}
