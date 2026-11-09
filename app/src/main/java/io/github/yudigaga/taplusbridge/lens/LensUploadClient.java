package io.github.yudigaga.taplusbridge.lens;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** A single cancellable multipart upload. Only WebView can confirm that the destination contains results. */
public final class LensUploadClient {
    static final int MAX_IMAGE_BYTES = 24 * 1024 * 1024;
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 8;
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final CookieBridge cookies;
    private final String userAgent;
    private final ConnectionFactory connections;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile HttpURLConnection active;
    private volatile boolean cancelled, timedOut;

    /** set() must finish updating the WebView cookie store before it returns. */
    public interface CookieBridge {
        String get(String url) throws IOException;
        void set(String url, String setCookie) throws IOException;
        void flush() throws IOException;
    }

    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }

    public static final class Result {
        public final String url, status, error;
        public final int httpStatus;
        private Result(String url, String status, String error, int httpStatus) {
            this.url = url; this.status = status; this.error = error; this.httpStatus = httpStatus;
        }
    }

    public LensUploadClient(CookieBridge cookies) { this(cookies, null); }
    public LensUploadClient(CookieBridge cookies, String userAgent) {
        this(cookies, userAgent, url -> (HttpURLConnection) url.openConnection());
    }
    LensUploadClient(CookieBridge cookies, String userAgent, ConnectionFactory connections) {
        if (cookies == null || connections == null) throw new IllegalArgumentException("missing_upload_transport");
        if (userAgent != null && (userAgent.length() > 4096 || userAgent.indexOf('\r') >= 0 || userAgent.indexOf('\n') >= 0))
            throw new IllegalArgumentException("invalid_user_agent");
        this.cookies = cookies; this.userAgent = userAgent; this.connections = connections;
    }

    public void cancel() { cancelled = true; disconnect(); }

    /** navigation_ready means Google supplied a GET destination; it does not assert a successful search. */
    public Result upload(File image, String mime, int width, int height, Locale locale, BooleanSupplier stopped) {
        if (!started.compareAndSet(false, true)) return result(null, "upload_error", "upload_already_started", 0);
        if (image == null || !image.isFile() || image.length() <= 0 || image.length() > MAX_IMAGE_BYTES
                || width <= 0 || height <= 0 || width > 8192 || height > 8192
                || !("image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime)))
            return result(null, "upload_error", "invalid_image", 0);
        ScheduledFuture<?> deadline = DEADLINES.schedule(() -> { timedOut = true; disconnect(); }, 60, TimeUnit.SECONDS);
        URL current = null;
        int responseCode = 0;
        try {
            check(stopped);
            String language = locale == null ? Locale.getDefault().toLanguageTag() : locale.toLanguageTag();
            current = new URL("https://lens.google.com/v3/upload?ep=cntpubb&hl="
                    + URLEncoder.encode(language, "UTF-8") + "&st=" + System.currentTimeMillis()
                    + "&re=df&s=4&vph=" + height + "&vpw=" + width);
            String method = "POST";
            String boundary = "----TaplusLens" + UUID.randomUUID().toString().replace("-", "");
            String extension = "image/png".equals(mime) ? "png" : "image/webp".equals(mime) ? "webp" : "jpg";
            byte[] before = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"encoded_image\"; filename=\"selected."
                    + extension + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] after = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            long imageLength = image.length();
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                check(stopped);
                if (!trusted(current)) return result(null, "upload_error", "unsafe_redirect", responseCode);
                HttpURLConnection connection = connections.open(current);
                active = connection;
                try {
                    check(stopped);
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000);
                    connection.setRequestMethod(method);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    if (userAgent != null && !userAgent.isEmpty()) connection.setRequestProperty("User-Agent", userAgent);
                    String cookie = cookies.get(current.toExternalForm());
                    check(stopped);
                    if (cookie != null && !cookie.isEmpty()) {
                        if (cookie.length() > 65536 || cookie.indexOf('\r') >= 0 || cookie.indexOf('\n') >= 0)
                            return result(null, "upload_error", "invalid_cookie", 0);
                        connection.setRequestProperty("Cookie", cookie);
                    }
                    if ("POST".equals(method)) {
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                        connection.setFixedLengthStreamingMode(imageLength + before.length + after.length);
                        try (OutputStream output = connection.getOutputStream(); InputStream input = new FileInputStream(image)) {
                            output.write(before);
                            byte[] buffer = new byte[32768]; int read; long count = 0;
                            while ((read = input.read(buffer)) != -1) {
                                check(stopped); count += read;
                                if (count > imageLength || count > MAX_IMAGE_BYTES) throw new IOException("image_changed");
                                output.write(buffer, 0, read);
                            }
                            if (count != imageLength) throw new IOException("image_changed");
                            output.write(after); output.flush();
                        }
                    }
                    responseCode = connection.getResponseCode();
                    check(stopped);
                    storeCookies(current, connection.getHeaderFields(), stopped);
                    if (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307 || responseCode == 308) {
                        if (hop == MAX_REDIRECTS) return result(null, "upload_error", "too_many_redirects", responseCode);
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.isEmpty() || location.length() > 32768)
                            return result(null, "upload_error", "missing_redirect", responseCode);
                        URL target = new URL(current, location);
                        if (!trusted(target)) return result(null, "upload_error", "unsafe_redirect", responseCode);
                        String nextMethod = "POST".equals(method) && (responseCode == 301 || responseCode == 302 || responseCode == 303)
                                ? "GET" : method;
                        if ("POST".equals(nextMethod) && !sameUploadEndpoint(current, target))
                            return result(null, "upload_error", "unsafe_post_redirect", responseCode);
                        if ("GET".equals(nextMethod) && interactionUrl(target))
                            return result(target, "needs_interaction", "google_interaction", responseCode);
                        if ("GET".equals(nextMethod) && "/search".equals(target.getPath()))
                            return result(target, "navigation_ready", null, responseCode);
                        current = target; method = nextMethod;
                        continue;
                    }
                    if (interactionUrl(current))
                        return result(current, "needs_interaction", "google_interaction", responseCode);
                    if (responseCode == 401 || responseCode == 403 || responseCode == 429)
                        return result("POST".equals(method) ? null : current, "network_error", "http_upload_rejected", responseCode);
                    if (responseCode >= 500) return result(current, "network_error", "http_server_error", responseCode);
                    if (responseCode < 200 || responseCode >= 300)
                        return result(current, "upload_error", "http_upload_error", responseCode);
                    if ("POST".equals(method)) return result(null, "upload_error", "missing_result_redirect", responseCode);
                    consumeBounded(connection, stopped);
                    return result(current, "page_ready", null, responseCode);
                } finally {
                    connection.disconnect();
                    if (active == connection) active = null;
                }
            }
            return result(null, "upload_error", "too_many_redirects", responseCode);
        } catch (Stopped stop) {
            return result(null, stop.timeout ? "timeout" : "cancelled", stop.timeout ? "upload_timeout" : "upload_cancelled", responseCode);
        } catch (SocketTimeoutException timeout) {
            return result(null, "timeout", "upload_timeout", responseCode);
        } catch (IOException | RuntimeException failure) {
            if (cancelled || (stopped != null && stopped.getAsBoolean())) return result(null, "cancelled", "upload_cancelled", responseCode);
            if (timedOut) return result(null, "timeout", "upload_timeout", responseCode);
            String code = failure.getMessage();
            if (!"image_changed".equals(code) && !"response_too_large".equals(code)) code = "upload_connection_failed";
            return result(null, "network_error", code, responseCode);
        } finally { deadline.cancel(false); disconnect(); }
    }

    private void storeCookies(URL url, Map<String, List<String>> headers, BooleanSupplier stopped) throws IOException {
        if (headers == null) return;
        boolean changed = false; int count = 0;
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(header.getKey()) || header.getValue() == null) continue;
            for (String value : header.getValue()) {
                check(stopped);
                if (value == null) continue;
                if (++count > 64 || value.length() > 16384) throw new IOException("invalid_response_cookie");
                cookies.set(url.toExternalForm(), value); changed = true;
            }
        }
        if (changed) { cookies.flush(); check(stopped); }
    }

    private void consumeBounded(HttpURLConnection connection, BooleanSupplier stopped) throws IOException {
        long length = connection.getContentLengthLong();
        if (length > MAX_RESPONSE_BYTES) throw new IOException("response_too_large");
        try (InputStream input = connection.getInputStream()) {
            byte[] buffer = new byte[16384]; int read, count = 0;
            while ((read = input.read(buffer)) != -1) {
                check(stopped); count += read;
                if (count > MAX_RESPONSE_BYTES) throw new IOException("response_too_large");
            }
        }
    }

    private void check(BooleanSupplier stopped) throws Stopped {
        if (timedOut) throw new Stopped(true);
        if (cancelled || (stopped != null && stopped.getAsBoolean())) throw new Stopped(false);
    }
    private void disconnect() { HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    private static Result result(URL url, String status, String error, int code) {
        return new Result(url == null ? null : url.toExternalForm(), status, error, code);
    }
    static boolean trusted(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return "https".equalsIgnoreCase(url.getProtocol()) && url.getUserInfo() == null
                && (url.getPort() == -1 || url.getPort() == 443)
                && ("google.com".equals(host) || host.endsWith(".google.com"));
    }
    private static boolean sameUploadEndpoint(URL from, URL target) {
        return "lens.google.com".equalsIgnoreCase(from.getHost()) && "lens.google.com".equalsIgnoreCase(target.getHost())
                && "/v3/upload".equals(from.getPath()) && "/v3/upload".equals(target.getPath());
    }
    private static boolean interactionUrl(URL url) {
        return "accounts.google.com".equalsIgnoreCase(url.getHost()) || "consent.google.com".equalsIgnoreCase(url.getHost())
                || "/sorry".equals(url.getPath()) || url.getPath().startsWith("/sorry/");
    }
    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "TaplusBridge-Lens-upload-timeout"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(3, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
    private static final class Stopped extends IOException {
        final boolean timeout;
        Stopped(boolean timeout) { this.timeout = timeout; }
    }
}
