package io.github.yudigaga.taplusbridge.rules;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure IceBridge rule input normalization, detached from its Android preference repository. */
public final class RuleSchema {
    public static final int MAX_RULES = 768, MAX_HOSTS = 96, MAX_HOSTS_PER_ROW = 20;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern HOST = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    private RuleSchema() { }

    public static boolean isValidRulePackage(String value) {
        return value != null && value.length() <= 255 && ("*".equals(value) || PACKAGE.matcher(value).matches());
    }
    public static List<String> normalizeUserHosts(String raw) {
        Set<String> hosts = new LinkedHashSet<>();
        if (raw != null) for (String item : raw.split("[,，;；\\n\\r]+")) {
            if (item.trim().isEmpty()) continue;
            String host = normalizeHostOrUrl(item);
            if (host == null) throw new IllegalArgumentException("invalid-rule-host");
            hosts.add(host);
            if (hosts.size() > MAX_HOSTS_PER_ROW) throw new IllegalArgumentException("too-many-row-hosts");
        }
        return Collections.unmodifiableList(new ArrayList<>(hosts));
    }
    public static String normalizeHostOrUrl(String value) {
        if (value == null) return null;
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        // A pasted share URL can have long query parameters; only the extracted host is stored.
        if (candidate.isEmpty() || candidate.length() > 8192) return null;
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            try { candidate = new URI(candidate).getHost(); } catch (Exception invalid) { return null; }
        } else {
            if (candidate.startsWith("//")) candidate = candidate.substring(2);
            int end = candidate.length();
            for (char c : new char[]{'/', '?', '#'}) {
                int at = candidate.indexOf(c); if (at >= 0) end = Math.min(end, at);
            }
            candidate = candidate.substring(0, end);
        }
        if (candidate == null) return null;
        while (candidate.startsWith(".")) candidate = candidate.substring(1);
        return candidate.length() <= 128 && HOST.matcher(candidate).matches() ? candidate : null;
    }
    public static LinkRuleEntry sanitize(LinkRuleEntry raw) {
        if (raw == null) return null;
        String host = normalizeHostOrUrl(raw.host);
        String source = raw.sourcePackage == null || raw.sourcePackage.trim().isEmpty() ? "*" : raw.sourcePackage.trim();
        String target = raw.targetPackage == null ? null : raw.targetPackage.trim();
        String note = raw.note == null ? "" : raw.note;
        int noteLimit = LinkRuleEntry.SOURCE_ONLINE.equals(raw.source) ? 512 : 160;
        if (host == null || !isValidRulePackage(source) || !isValidRulePackage(target) || "*".equals(target)
                || note.length() > noteLimit || !(LinkRuleEntry.SOURCE_MANUAL.equals(raw.source)
                || LinkRuleEntry.SOURCE_ONLINE.equals(raw.source) || LinkRuleEntry.SOURCE_BUILT_IN.equals(raw.source))
                || !(LinkRuleEntry.CONVERSION_NONE.equals(raw.urlConversion)
                || LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK.equals(raw.urlConversion))) return null;
        return new LinkRuleEntry(source, host, target, raw.source, raw.urlConversion, note);
    }
    public static LinkRuleEntry requireValid(LinkRuleEntry raw) {
        LinkRuleEntry result = sanitize(raw);
        if (result == null) throw new IllegalArgumentException("invalid-rule");
        return result;
    }
    public static List<LinkRuleEntry> deduplicate(List<LinkRuleEntry> raw) {
        if (raw == null) return Collections.emptyList();
        List<LinkRuleEntry> ordered = new ArrayList<>();
        for (LinkRuleEntry entry : raw) ordered.add(requireValid(entry));
        ordered.sort(Comparator.comparingInt(RuleSchema::originOrder));
        Map<String, LinkRuleEntry> unique = new LinkedHashMap<>(); Set<String> hosts = new LinkedHashSet<>();
        for (LinkRuleEntry entry : ordered) {
            hosts.add(entry.host);
            if (hosts.size() > MAX_HOSTS) throw new IllegalArgumentException("too-many-hosts");
            unique.putIfAbsent(key(entry), entry);
            if (unique.size() > MAX_RULES) throw new IllegalArgumentException("too-many-rules");
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }
    public static String key(LinkRuleEntry entry) {
        return entry.sourcePackage + '\u0000' + entry.host + '\u0000' + entry.targetPackage + '\u0000' + entry.urlConversion;
    }
    private static int originOrder(LinkRuleEntry entry) {
        return LinkRuleEntry.SOURCE_MANUAL.equals(entry.source) ? 0 : LinkRuleEntry.SOURCE_ONLINE.equals(entry.source) ? 1 : 2;
    }
}
