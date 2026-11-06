package io.github.yudigaga.taplusbridge.recognition;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates supplied public links; protocol rules never infer or fetch a download. */
public final class RouteLink {
    public static final int MAX_LENGTH = 8192;
    private static final Pattern BTIH = Pattern.compile("(?i)urn:btih:(?:[0-9a-f]{40}|[a-z2-7]{32})");
    private static final Pattern BTMH = Pattern.compile("(?i)urn:btmh:1220[0-9a-f]{64}");
    private static final Pattern MD4 = Pattern.compile("(?i)[0-9a-f]{32}");
    private static final Pattern AICH = Pattern.compile("(?i)[a-z2-7]{32}");
    private RouteLink() { }

    public static boolean protocolRule(String value) {
        return "magnet:".equals(value) || "ed2k:".equals(value);
    }

    public static boolean valid(String value) { return valid(value, MAX_LENGTH); }
    public static boolean valid(String value, int maxLength) {
        if (!validCharacters(value, maxLength)) return false;
        if (value.regionMatches(true, 0, "magnet:", 0, 7)) return validMagnet(value);
        if (value.regionMatches(true, 0, "ed2k:", 0, 5)) return validEd2k(value);
        return validHttp(value, maxLength);
    }

    public static boolean validHttp(String value) { return validHttp(value, MAX_LENGTH); }
    public static boolean validHttp(String value, int maxLength) {
        if (!validCharacters(value, maxLength)) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.isOpaque() || uri.getRawUserInfo() != null || uri.getHost() == null
                    || uri.getRawAuthority() == null || uri.getRawAuthority().endsWith(":")
                    || uri.getPort() == 0 || uri.getPort() > 65535) return false;
            return validHost(uri.getHost());
        } catch (URISyntaxException failure) { return false; }
    }

    /** Returns a matching key only; the caller retains the complete original link for delivery. */
    public static String ruleKey(String value) {
        if (!valid(value)) return null;
        if (value.regionMatches(true, 0, "magnet:", 0, 7)) return "magnet:";
        if (value.regionMatches(true, 0, "ed2k:", 0, 5)) return "ed2k:";
        return URI.create(value).getHost().toLowerCase(Locale.ROOT);
    }

    private static boolean validCharacters(String value, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 32 || Character.isISOControl(c)) return false;
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16), low = Character.digit(value.charAt(i + 2), 16);
                int decoded = high < 0 || low < 0 ? -1 : high * 16 + low;
                if (decoded >= 0 && (decoded < 32 || decoded == 127)) return false;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static boolean validHost(String value) {
        String host = value.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) return host.indexOf('%') < 0;
        if (host.isEmpty() || host.length() > 253 || host.startsWith(".") || host.endsWith(".")) return false;
        for (String label : host.split("\\.", -1))
            if (label.isEmpty() || label.length() > 63
                    || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return false;
        return true;
    }

    private static boolean validMagnet(String value) {
        if (!value.regionMatches(true, 0, "magnet:?", 0, 8) || value.indexOf('#') >= 0) return false;
        boolean exactTopic = false;
        try {
            new URI(value);
            for (String field : value.substring(8).split("&", -1)) {
                int equals = field.indexOf('=');
                String key = decode(equals < 0 ? field : field.substring(0, equals));
                String data = decode(equals < 0 ? "" : field.substring(equals + 1));
                if (!"xt".equalsIgnoreCase(key)) continue;
                if (!BTIH.matcher(data).matches() && !BTMH.matcher(data).matches()) return false;
                exactTopic = true;
            }
            return exactTopic;
        } catch (IllegalArgumentException | URISyntaxException invalid) { return false; }
    }

    private static boolean validEd2k(String value) {
        if (!value.regionMatches(true, 0, "ed2k://|file|", 0, 13)) return false;
        String[] fields = value.split("\\|", -1);
        if (fields.length < 6 || !fields[1].equalsIgnoreCase("file") || !MD4.matcher(fields[4]).matches()) return false;
        try {
            String name = decode(fields[2]);
            if (name.isBlank() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                    || name.indexOf('\\') >= 0 || name.indexOf('|') >= 0) return false;
            if (!fields[3].matches("[0-9]{1,20}")) return false;
            Long.parseUnsignedLong(fields[3]);
            int end = 5;
            Set<String> metadata = new HashSet<>();
            while (end < fields.length && !fields[end].equals("/")) {
                String parameter = fields[end++];
                if (parameter.startsWith("h=")) {
                    if (!metadata.add("h") || !AICH.matcher(parameter.substring(2)).matches()) return false;
                } else if (parameter.startsWith("p=")) {
                    if (!metadata.add("p")) return false;
                    for (String hash : parameter.substring(2).split(":", -1))
                        if (!MD4.matcher(hash).matches()) return false;
                } else if (parameter.startsWith("s=")) {
                    if (!validHttp(decode(parameter.substring(2)))) return false;
                } else return false;
            }
            if (end == fields.length) return false;
            if (end == fields.length - 1) return true;
            if (fields.length != end + 3 || !fields[end + 2].equals("/")
                    || !fields[end + 1].startsWith("sources,")) return false;
            for (String source : fields[end + 1].substring(8).split(",", -1)) {
                if (!validHttp("http://" + source)) return false;
                URI address = URI.create("http://" + source);
                if (address.getPort() <= 0 || !address.getRawPath().isEmpty()
                        || address.getRawQuery() != null || address.getRawFragment() != null) return false;
            }
            return true;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static String decode(String raw) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) != '%') { decoded.append(raw.charAt(i++)); continue; }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (i < raw.length() && raw.charAt(i) == '%') {
                if (i + 2 >= raw.length()) throw new IllegalArgumentException();
                int high = Character.digit(raw.charAt(i + 1), 16), low = Character.digit(raw.charAt(i + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException();
                bytes.write(high * 16 + low); i += 3;
            }
            try {
                decoded.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException invalid) { throw new IllegalArgumentException(); }
        }
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (Character.isISOControl(c)) throw new IllegalArgumentException();
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= decoded.length() || !Character.isLowSurrogate(decoded.charAt(++i)))
                    throw new IllegalArgumentException();
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException();
        }
        return decoded.toString();
    }
}
