// Adapted from yudigaga/IceBridge 3ac3baa6273cb084c2563bd34d10d1f54e5da8ee RuleConfigTransfer.
package io.github.yudigaga.taplusbridge.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Compatible icebridge-rules v1 manual transfer, bounded data only; never evaluates actions. */
public final class RuleConfigTransfer {
    public static final String FORMAT = "icebridge-rules";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = RuleJsonCodec.MAX_BYTES;
    private RuleConfigTransfer() { }

    public static String exportJson(List<LinkRuleEntry> rules) {
        List<LinkRuleEntry> manual = new ArrayList<>();
        if (rules != null) for (LinkRuleEntry rule : rules) {
            if (rule != null && LinkRuleEntry.SOURCE_MANUAL.equals(rule.source)) manual.add(RuleSchema.requireValid(rule));
        }
        try {
            JSONObject root = new JSONObject(); root.put("format", FORMAT); root.put("version", VERSION);
            root.put("exportedAt", System.currentTimeMillis());
            JSONArray array = new JSONArray();
            for (LinkRuleEntry entry : RuleSchema.deduplicate(manual)) array.put(RuleJsonCodec.item(entry, false));
            root.put("rules", array); String result = root.toString(); RuleJsonCodec.requireSize(result); return result;
        } catch (JSONException invalid) { throw new IllegalArgumentException("transfer-json", invalid); }
    }
    public static List<LinkRuleEntry> importJson(String json) throws TransferException {
        try {
            RuleJsonCodec.requireSize(json);
            RuleJsonCodec.requireSyntax(json);
            JSONObject root = new JSONObject(RuleJsonCodec.withoutBom(json));
            RuleJsonCodec.requireKeys(root, new HashSet<>(Arrays.asList("format", "version", "exportedAt", "rules")));
            if (!FORMAT.equals(RuleJsonCodec.string(root, "format", null))) throw new IllegalArgumentException("不是 IceBridge 规则文件");
            Object version = root.get("version");
            if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() != VERSION)
                throw new IllegalArgumentException("不支持的规则文件版本");
            Object array = root.get("rules");
            if (!(array instanceof JSONArray rules)) throw new IllegalArgumentException("缺少 rules 数组");
            return RuleJsonCodec.readArray(rules, LinkRuleEntry.SOURCE_MANUAL);
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new TransferException("规则文件无效：仅支持 IceBridge v1 数据规则，最多768条、96个域名、1MB", invalid);
        }
    }
    public static final class TransferException extends Exception {
        public TransferException(String message) { super(message); }
        public TransferException(String message, Throwable cause) { super(message, cause); }
    }
}
