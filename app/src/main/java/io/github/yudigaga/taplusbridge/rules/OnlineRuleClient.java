package io.github.yudigaga.taplusbridge.rules;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Fixed-origin, bounded HTTPS transport for the same owned manifest used by IceBridge. */
public final class OnlineRuleClient {
    public static final String PRIMARY_MANIFEST = "https://raw.githubusercontent.com/yudigaga/IceBridge/rules/manifest.json";
    public static final String FALLBACK_MANIFEST = "https://cdn.jsdelivr.net/gh/yudigaga/IceBridge@rules/manifest.json";
    static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(30);
    static final long PRIMARY_NANOS = TimeUnit.SECONDS.toNanos(18);
    interface Fetcher { String fetch(URI url, int limit, long deadline) throws IOException; }
    private final Fetcher fetcher;
    public OnlineRuleClient() { this(OnlineRuleClient::fetchHttps); }
    OnlineRuleClient(Fetcher fetcher) { this.fetcher = fetcher; }

    static final class Download {
        final OnlineRuleBundle bundle;
        final String source;
        Download(OnlineRuleBundle bundle, String source) { this.bundle = bundle; this.source = source; }
    }
    Download download(long revision, String checksum, OnlineRuleBundle.Cache cache) throws IOException {
        long now = System.nanoTime(), deadline = now + REFRESH_NANOS;
        IOException primary;
        try { return download(URI.create(PRIMARY_MANIFEST), revision, checksum, cache, Math.min(deadline, now + PRIMARY_NANOS), "github"); }
        catch (IOException failed) { primary = failed; }
        if (Thread.currentThread().isInterrupted()) throw new IOException("refresh-cancelled");
        try { return download(URI.create(FALLBACK_MANIFEST), revision, checksum, cache, deadline, "jsdelivr"); }
        catch (IOException failed) { failed.addSuppressed(primary); throw failed; }
    }
    private Download download(URI manifestUrl, long revision, String checksum, OnlineRuleBundle.Cache cache,
                              long deadline, String source) throws IOException {
        OnlineRuleBundle.Manifest manifest = OnlineRuleBundle.parseManifest(fetcher.fetch(manifestUrl, OnlineRuleBundle.MAX_MANIFEST_BYTES, deadline));
        OnlineRuleBundle.checkRevision(manifest, revision, checksum);
        if (OnlineRuleBundle.canReuse(manifest, cache)) return new Download(cache.bundle, source);
        URI bundleUrl = bundleUrl(manifestUrl, manifest.file);
        OnlineRuleBundle bundle = OnlineRuleBundle.parse(fetcher.fetch(bundleUrl, OnlineRuleBundle.MAX_BUNDLE_BYTES, deadline), manifest.checksum);
        if (bundle.revision != manifest.revision) throw new IOException("bundle-revision-mismatch");
        if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) throw new IOException("refresh-deadline");
        return new Download(bundle, source);
    }

    static URI bundleUrl(URI manifestUrl, String path) throws IOException {
        if (!trusted(manifestUrl) || path == null || path.length() > 256 || path.startsWith("/")
                || path.contains("..") || path.contains("\\") || path.contains(":") || path.contains("%")
                || path.contains("?") || path.contains("#")) throw new IOException("bundle-url-invalid");
        URI base = manifestUrl.resolve(".");
        URI resolved = base.resolve(path);
        if (!trusted(resolved) || !resolved.getRawPath().startsWith(base.getRawPath())) throw new IOException("bundle-origin");
        return resolved;
    }
    static boolean trusted(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() != -1) return false;
        String path = uri.getRawPath();
        if (path == null || path.contains("..") || path.contains("%") || path.contains("\\")) return false;
        return ("raw.githubusercontent.com".equals(uri.getHost()) && path.startsWith("/yudigaga/IceBridge/rules/"))
                || ("cdn.jsdelivr.net".equals(uri.getHost()) && path.startsWith("/gh/yudigaga/IceBridge@rules/"));
    }
    private static String fetchHttps(URI url, int limit, long deadline) throws IOException {
        if (!trusted(url) || limit <= 0) throw new IOException("request-origin");
        remainingMillis(deadline);
        HttpsURLConnection connection = (HttpsURLConnection) url.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(Math.min(12_000, remainingMillis(deadline)));
        connection.setReadTimeout(Math.min(15_000, remainingMillis(deadline)));
        connection.setRequestProperty("User-Agent", "TaplusBridge-IceBridge-rules/1");
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("http-status");
            if (connection.getContentLengthLong() > limit) throw new IOException("response-too-large");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
                byte[] buffer = new byte[4096]; int count;
                while (true) {
                    connection.setReadTimeout(Math.min(15_000, remainingMillis(deadline)));
                    count = input.read(buffer); if (count == -1) break;
                    if (count > limit - output.size()) throw new IOException("response-too-large");
                    output.write(buffer, 0, count);
                }
                return decodeUtf8(output.toByteArray(), limit);
            }
        } finally { connection.disconnect(); }
    }
    static String decodeUtf8(byte[] bytes, int limit) throws IOException {
        if (bytes == null || limit <= 0 || bytes.length > limit) throw new IOException("response-too-large");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static int remainingMillis(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("refresh-deadline");
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
}
