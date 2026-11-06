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

/** Offline interpretation of explicit redirect parameters; ordinary website links stay intact. */
public final class RedirectLink {
    public static final int MAX_LENGTH = 16 * 1024;
    private static final Set<String> PATHS = Set.of("redirect", "link", "go", "jump", "out");
    private static final Set<String> PARAMETERS = Set.of("url", "target", "redirect_url", "destination", "redirect_uri");
    private RedirectLink() { }

    /** Invalid outer links and ambiguous targets fail; unrecognized or malformed wrappers stay whole. */
    public static String normalize(String original) {
        if (!validInput(original)) return null;
        String current = original;
        Set<String> seen = new HashSet<>();
        for (int depth = 0; depth <= 2; depth++) {
            if (!seen.add(current)) return original;
            URI address = URI.create(current);
            String path = address.getRawPath();
            if (path == null) return current;
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (!PATHS.contains(path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT))
                    || address.getRawQuery() == null) return current;
            String rawTarget = null;
            int targets = 0;
            String[] fields = address.getRawQuery().split("&", -1);
            if (fields.length > 64) return original;
            try {
                for (String field : fields) {
                    int equals = field.indexOf('=');
                    String key = decode(equals < 0 ? field : field.substring(0, equals)).toLowerCase(Locale.ROOT);
                    if (!PARAMETERS.contains(key)) continue;
                    if (++targets > 1) return null;
                    rawTarget = equals < 0 ? "" : field.substring(equals + 1);
                }
            } catch (IllegalArgumentException malformed) { return original; }
            if (targets == 0) return current;
            String target;
            try { target = decode(rawTarget); }
            catch (IllegalArgumentException malformed) { return original; }
            if (!validInput(target) || depth == 2) return original;
            current = target;
        }
        return original;
    }

    /** Percent decoding is strict UTF-8 and deliberately does not reinterpret literal plus signs. */
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
        return decoded.toString();
    }

    public static boolean validInput(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 32 || Character.isISOControl(character)) return false;
            if (character == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16), low = Character.digit(value.charAt(i + 2), 16);
                int decoded = high < 0 || low < 0 ? -1 : high * 16 + low;
                if (decoded >= 0 && (decoded < 32 || decoded == 127)) return false;
            }
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.isOpaque() || uri.getRawUserInfo() != null || uri.getHost() == null
                    || uri.getRawAuthority() == null || uri.getRawAuthority().endsWith(":")
                    || uri.getPort() == 0 || uri.getPort() > 65535) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("[") && host.endsWith("]")) return host.indexOf('%') < 0;
            if (host.isEmpty() || host.length() > 253 || host.startsWith(".") || host.endsWith(".")) return false;
            for (String label : host.split("\\.", -1))
                if (label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return false;
            return true;
        } catch (URISyntaxException invalid) { return false; }
    }
}
