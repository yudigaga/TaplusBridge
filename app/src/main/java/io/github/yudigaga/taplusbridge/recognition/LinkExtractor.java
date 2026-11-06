package io.github.yudigaga.taplusbridge.recognition;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts supplied supported links; never predicts a destination from a title or expands a URL. */
public final class LinkExtractor {
    public static final int MAX_INPUT_LENGTH = 128 * 1024;
    public static final int MAX_URL_LENGTH = 8192;
    public static final int MAX_RESULTS = 64;
    private static final Pattern ENTITY = Pattern.compile("&(?:amp|lt|gt|quot|apos|nbsp|#[0-9]{1,7}|#x[0-9a-fA-F]{1,6});");
    private static final Pattern URL_TOKEN = Pattern.compile("(?i)(?<![a-z0-9_])(?:"
            + "(?:magnet:\\?|ed2k://)[^\\s<>\\\"\\p{Cc}]+"
            + "|(?:https?://|www\\.)[^\\s<>\\\"'\\p{Cc}，。！？；、‘’“”《》【】]+)");
    private static final Pattern TAG = Pattern.compile("(?s)<!\\[CDATA\\[.*?\\]\\]>|<!--.*?-->|<[^>]{0,8192}>");
    private static final Pattern TAG_NAME = Pattern.compile("^<\\s*(/?)\\s*([A-Za-z_][A-Za-z0-9_:.-]*)");
    private static final Pattern HREF = Pattern.compile("(?is)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern CARD = Pattern.compile("(?i)<\\s*(?:[a-z_][a-z0-9_.-]*:)?(?:appmsg|msg|card|weibo|statuses|status|url)\\b");
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private LinkExtractor() { }

    public static List<String> extract(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_INPUT_LENGTH) return Collections.emptyList();
        String complete = raw.trim();
        if (RouteLink.valid(complete) && !RouteLink.validHttp(complete)) return List.of(complete);
        String decoded = decodeEntities(raw);
        String text = !TAG.matcher(raw).find() && TAG.matcher(decoded).find() ? decoded : raw;
        String upper = decoded.toUpperCase(Locale.ROOT);
        // No XML parser or entity resolver is used. Declaration URLs must not become results.
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) return Collections.emptyList();
        Markup markup = readMarkup(text);
        if (markup.hasCanonical) return immutable(markup.canonical);
        if (!markup.urls.isEmpty()) return immutable(markup.urls);
        if (!markup.anchors.isEmpty()) return immutable(markup.anchors);
        if (CARD.matcher(text).find()) return Collections.emptyList();
        Set<String> links = new LinkedHashSet<>();
        Matcher matcher = URL_TOKEN.matcher(TAG.matcher(raw).replaceAll(" "));
        while (matcher.find() && links.size() < MAX_RESULTS) {
            String token = matcher.group();
            boolean download = token.regionMatches(true, 0, "magnet:", 0, 7) || token.regionMatches(true, 0, "ed2k:", 0, 5);
            if (download) add(links, token);
            else {
                Matcher parts = URL_TOKEN.matcher(decodeEntities(token));
                while (parts.find() && links.size() < MAX_RESULTS) add(links, parts.group());
            }
        }
        return immutable(links);
    }

    private static Markup readMarkup(String text) {
        Markup found = new Markup();
        Deque<String> stack = new ArrayDeque<>();
        int urlStart = -1;
        int urlDepth = -1;
        boolean canonical = false;
        Matcher tags = TAG.matcher(text);
        while (tags.find()) {
            String tag = tags.group();
            if (tag.startsWith("<!--") || tag.startsWith("<![CDATA[")) continue;
            Matcher nameMatch = TAG_NAME.matcher(tag);
            if (!nameMatch.find()) continue;
            boolean closing = !nameMatch.group(1).isEmpty();
            String name = nameMatch.group(2).toLowerCase(Locale.ROOT);
            int colon = name.lastIndexOf(':');
            if (colon >= 0) name = name.substring(colon + 1);
            if (closing) {
                if (stack.isEmpty() || !stack.peek().equals(name)) {
                    stack.clear(); urlStart = -1; continue;
                }
                if ("url".equals(name) && urlStart >= 0 && stack.size() == urlDepth) {
                    String value = text.substring(urlStart, tags.start()).trim();
                    if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) {
                        value = value.substring(9, value.length() - 3).trim();
                    } else value = decodeEntities(value);
                    add(canonical ? found.canonical : found.urls, value);
                    urlStart = -1;
                }
                stack.pop();
                continue;
            }
            if ("a".equals(name)) {
                Matcher href = HREF.matcher(tag);
                if (href.find()) {
                    String value = href.group(1) != null ? href.group(1)
                            : href.group(2) != null ? href.group(2) : href.group(3);
                    add(found.anchors, decodeEntities(value));
                }
            }
            if (tag.matches("(?s).*/\\s*>")) continue;
            if (stack.size() >= 64) { stack.clear(); urlStart = -1; continue; }
            if ("url".equals(name)) {
                canonical = "appmsg".equals(stack.peek());
                if (canonical) found.hasCanonical = true;
                urlStart = tags.end(); urlDepth = stack.size() + 1;
            }
            stack.push(name);
        }
        return found;
    }

    private static void add(Set<String> links, String raw) {
        if (links.size() >= MAX_RESULTS || raw == null) return;
        String supplied = raw.trim();
        if (RouteLink.valid(supplied) && !RouteLink.validHttp(supplied)) { links.add(supplied); return; }
        if (raw.contains("…") || raw.matches("(?s).*\\.{3,}[)\\]}.。！？，,;:]*$")) return;
        String value = trimPunctuation(supplied);
        if (value.regionMatches(true, 0, "www.", 0, 4)) value = "https://" + value;
        // An ellipsis is display truncation, not enough information to construct a destination.
        if (value.contains("…") || value.endsWith("...") || value.length() > MAX_URL_LENGTH) return;
        if (validUrl(value)) links.add(value);
    }

    private static String trimPunctuation(String value) {
        int end = value.length();
        while (end > 0) {
            char tail = value.charAt(end - 1);
            if ("，。！？；、‘’“”《》【】〉」』〕〗,.;!?".indexOf(tail) >= 0) { end--; continue; }
            char open = tail == ')' || tail == '）' ? (tail == ')' ? '(' : '（')
                    : tail == ']' ? '[' : tail == '}' ? '{' : 0;
            if (open != 0 && count(value, tail, end) > count(value, open, end)) { end--; continue; }
            break;
        }
        return value.substring(0, end);
    }

    private static int count(String text, char needle, int end) {
        int count = 0;
        for (int i = 0; i < end; i++) if (text.charAt(i) == needle) count++;
        return count;
    }

    private static boolean validUrl(String value) {
        if (RouteLink.valid(value) && !RouteLink.validHttp(value)) return true;
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 32 || Character.isISOControl(c)) return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawAuthority().endsWith(":") || uri.getPort() == 0 || uri.getPort() > 65535) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.length() > 253 || !host.contains(".")) return false;
            for (String label : host.split("\\.", -1)) if (!DNS_LABEL.matcher(label).matches()) return false;
            return true;
        } catch (URISyntaxException error) { return false; }
    }

    private static String decodeEntities(String value) {
        Matcher entities = ENTITY.matcher(value);
        StringBuffer decoded = new StringBuffer();
        while (entities.find()) {
            String entity = entities.group();
            String name = entity.substring(1, entity.length() - 1);
            String replacement = switch (name) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> numericEntity(name, entity);
            };
            entities.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        entities.appendTail(decoded);
        return decoded.toString();
    }

    private static String numericEntity(String name, String fallback) {
        try {
            boolean hex = name.startsWith("#x");
            int code = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
            if (!Character.isValidCodePoint(code) || (code >= 0xd800 && code <= 0xdfff)) return fallback;
            return new String(Character.toChars(code));
        } catch (RuntimeException error) { return fallback; }
    }

    private static List<String> immutable(Set<String> links) {
        return Collections.unmodifiableList(new ArrayList<>(links));
    }

    private static final class Markup {
        boolean hasCanonical;
        final Set<String> canonical = new LinkedHashSet<>();
        final Set<String> urls = new LinkedHashSet<>();
        final Set<String> anchors = new LinkedHashSet<>();
    }
}
