package io.github.yudigaga.taplusbridge.lens;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** The factory maps already-validated Google HTTPS URLs to a local HTTP test server. */
public final class LensUploadClientTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private File image;
    private HttpServer server;
    private HttpHandler route;
    private final MemoryCookies cookies = new MemoryCookies();

    @Before public void setup() throws Exception {
        image = File.createTempFile("lens-http-", ".png"); Files.write(image.toPath(), PNG);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { route.handle(exchange); } finally { exchange.close(); }
        });
        server.start();
    }
    @After public void cleanup() { server.stop(0); image.delete(); }

    private LensUploadClient client() {
        return new LensUploadClient(cookies, "WebView-test-agent", url -> {
            assertTrue(LensUploadClient.trusted(url));
            return (HttpURLConnection) new URL("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/" + url.getHost() + url.getFile()).openConnection();
        });
    }
    private LensUploadClient.Result upload(LensUploadClient client) {
        return client.upload(image, "image/png", 374, 832, Locale.forLanguageTag("zh-CN"), () -> false);
    }
    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location); exchange.sendResponseHeaders(status, -1);
    }

    @Test public void actualMultipartUploadUsesEncodedImageThenFollowsGetRedirectWithScopedCookies() throws Exception {
        List<String> failures = new ArrayList<>(); AtomicInteger posts = new AtomicInteger(); AtomicInteger gets = new AtomicInteger();
        cookies.perHost.put("lens.google.com", "HOST_PRIVATE=first");
        route = exchange -> {
            try {
                if (exchange.getRequestURI().getPath().equals("/lens.google.com/v3/upload")) {
                    posts.incrementAndGet();
                    assertEquals("POST", exchange.getRequestMethod());
                    assertEquals("WebView-test-agent", exchange.getRequestHeaders().getFirst("User-Agent"));
                    assertEquals("HOST_PRIVATE=first", exchange.getRequestHeaders().getFirst("Cookie"));
                    String query = exchange.getRequestURI().getRawQuery();
                    assertTrue(query.contains("ep=cntpubb")); assertTrue(query.contains("hl=zh-CN"));
                    assertTrue(query.contains("vph=832")); assertTrue(query.contains("vpw=374"));
                    assertTrue(query.contains("re=df")); assertTrue(query.contains("s=4"));
                    String type = exchange.getRequestHeaders().getFirst("Content-Type");
                    assertTrue(type.startsWith("multipart/form-data; boundary="));
                    String boundary = type.substring(type.indexOf("boundary=") + 9);
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    assertEquals(body.length, Integer.parseInt(exchange.getRequestHeaders().getFirst("Content-Length")));
                    String encoded = new String(body, StandardCharsets.ISO_8859_1);
                    assertTrue(encoded.contains("name=\"encoded_image\"; filename=\"selected.png\""));
                    assertTrue(encoded.startsWith("--" + boundary + "\r\n"));
                    int dataStart = encoded.indexOf("\r\n\r\n") + 4;
                    assertArrayEquals(PNG, java.util.Arrays.copyOfRange(body, dataStart, dataStart + PNG.length));
                    assertTrue(encoded.endsWith("\r\n--" + boundary + "--\r\n"));
                    exchange.getResponseHeaders().add("Set-Cookie", "UPLOAD=one; Domain=.google.com; Path=/; Secure");
                    redirect(exchange, 303, "https://www.google.com/lens/relay");
                } else {
                    gets.incrementAndGet(); assertEquals("GET", exchange.getRequestMethod());
                    assertEquals("/www.google.com/lens/relay", exchange.getRequestURI().getPath());
                    assertEquals("UPLOAD=one", exchange.getRequestHeaders().getFirst("Cookie"));
                    exchange.getResponseHeaders().add("Set-Cookie", "SEARCH=two; Path=/; Secure");
                    redirect(exchange, 302, "/search?udm=26&vsrid=test-result");
                }
            } catch (AssertionError failure) { failures.add(failure.toString()); exchange.sendResponseHeaders(500, -1); }
        };
        LensUploadClient.Result result = upload(client());
        assertTrue(failures.toString(), failures.isEmpty());
        assertEquals("navigation_ready", result.status); assertNull(result.error);
        assertEquals("https://www.google.com/search?udm=26&vsrid=test-result", result.url);
        assertEquals(1, posts.get()); assertEquals(1, gets.get());
        assertEquals(List.of("get:lens.google.com", "set:lens.google.com", "flush", "get:www.google.com", "set:www.google.com", "flush"), cookies.events);
        assertEquals("SEARCH=two; UPLOAD=one", cookies.get("https://www.google.com/search"));
    }

    @Test public void actualChallengeRedirectReturnsTheSameSessionUrlWithoutInferringLogin() {
        route = exchange -> { exchange.getRequestBody().readAllBytes(); redirect(exchange, 303, "https://www.google.com/sorry/index?continue=result"); };
        LensUploadClient.Result result = upload(client());
        assertEquals("needs_interaction", result.status); assertEquals("google_interaction", result.error);
        assertEquals("https://www.google.com/sorry/index?continue=result", result.url);
    }

    @Test public void actualAccountsRedirectIsReportedWithoutForcingAnAccountFlow() {
        route = exchange -> { exchange.getRequestBody().readAllBytes(); redirect(exchange, 303, "https://accounts.google.com/ServiceLogin?continue=result"); };
        LensUploadClient.Result result = upload(client());
        assertEquals("needs_interaction", result.status); assertTrue(result.url.startsWith("https://accounts.google.com/"));
    }

    @Test public void unsafeRedirectCannotExfiltrateTheImageToAnotherOrigin() {
        route = exchange -> { exchange.getRequestBody().readAllBytes(); redirect(exchange, 307, "https://evil.example/collect"); };
        LensUploadClient.Result result = upload(client());
        assertEquals("upload_error", result.status); assertEquals("unsafe_redirect", result.error); assertNull(result.url);
    }

    @Test public void preservingPostToADifferentGoogleEndpointIsAlsoRejected() {
        route = exchange -> { exchange.getRequestBody().readAllBytes(); redirect(exchange, 307, "https://www.google.com/search?udm=26"); };
        LensUploadClient.Result result = upload(client());
        assertEquals("upload_error", result.status); assertEquals("unsafe_post_redirect", result.error);
    }

    @Test public void redirectLoopsAreBounded() {
        AtomicInteger count = new AtomicInteger();
        route = exchange -> { count.incrementAndGet(); exchange.getRequestBody().readAllBytes(); redirect(exchange, 307, "https://lens.google.com/v3/upload?loop=1"); };
        LensUploadClient.Result result = upload(client());
        assertEquals("too_many_redirects", result.error); assertEquals(9, count.get());
    }

    @Test public void barePostForbiddenDoesNotPretendToBeASearchOrRequireLogin() {
        route = exchange -> { exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(403, -1); };
        LensUploadClient.Result result = upload(client());
        assertEquals("network_error", result.status); assertEquals("http_upload_rejected", result.error);
        assertEquals(403, result.httpStatus); assertNull(result.url);
    }

    @Test public void postBodyWithoutAResultRedirectIsNotInventedAsAGetSearch() {
        route = exchange -> { exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, -1); };
        LensUploadClient.Result result = upload(client());
        assertEquals("upload_error", result.status); assertEquals("missing_result_redirect", result.error); assertNull(result.url);
    }

    @Test public void malformedCookieIsRejectedBeforeAnyImageIsTransmitted() {
        cookies.perHost.put("lens.google.com", "SID=secret\r\nX-Untrusted: 1");
        AtomicInteger requests = new AtomicInteger(); route = exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); };
        LensUploadClient.Result result = upload(client());
        assertEquals("invalid_cookie", result.error); assertEquals(0, requests.get());
    }

    @Test public void cancellationBeforeStartNeverOpensANetworkConnection() {
        AtomicInteger opened = new AtomicInteger();
        LensUploadClient client = new LensUploadClient(cookies, null, url -> { opened.incrementAndGet(); throw new IOException(); });
        client.cancel(); LensUploadClient.Result result = upload(client);
        assertEquals("cancelled", result.status); assertEquals(0, opened.get());
    }

    @Test public void cancelDisconnectsTheActiveConnectionAndReturnsCancelled() throws Exception {
        BlockingConnection connection = new BlockingConnection();
        LensUploadClient client = new LensUploadClient(cookies, null, url -> connection);
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<LensUploadClient.Result> result = io.submit(() -> upload(client));
            assertTrue(connection.reading.await(3, TimeUnit.SECONDS)); client.cancel();
            assertEquals("cancelled", result.get(3, TimeUnit.SECONDS).status); assertTrue(connection.disconnected);
        } finally { client.cancel(); io.shutdownNow(); }
    }

    @Test public void socketTimeoutAndOversizedResponsesAreExplicitFailures() throws Exception {
        LensUploadClient timeout = new LensUploadClient(cookies, null, url -> new FakeConnection() {
            @Override public int getResponseCode() throws IOException { throw new SocketTimeoutException("test"); }
        });
        assertEquals("timeout", upload(timeout).status);
        AtomicInteger opened = new AtomicInteger();
        LensUploadClient oversized = new LensUploadClient(cookies, null, url -> opened.getAndIncrement() == 0 ? new FakeConnection() {
            @Override public int getResponseCode() { return 303; }
            @Override public String getHeaderField(String name) { return "Location".equals(name) ? "https://www.google.com/lens/relay" : null; }
        } : new FakeConnection() {
            @Override public long getContentLengthLong() { return LensUploadClient.MAX_RESPONSE_BYTES + 1L; }
        });
        LensUploadClient.Result result = upload(oversized);
        assertEquals("network_error", result.status); assertEquals("response_too_large", result.error);
    }

    @Test public void imageLimitAndHttpsOriginAreEnforced() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        LensUploadClient invalid = new LensUploadClient(cookies, null, url -> { opened.incrementAndGet(); throw new IOException(); });
        assertEquals("invalid_image", invalid.upload(image, "text/html", 1, 1, Locale.US, () -> false).error);
        assertEquals(0, opened.get());
        assertEquals(24 * 1024 * 1024, LensUploadClient.MAX_IMAGE_BYTES);
        assertTrue(LensUploadClient.trusted(new URL("https://lens.google.com/v3/upload")));
        assertFalse(LensUploadClient.trusted(new URL("http://lens.google.com/v3/upload")));
        assertFalse(LensUploadClient.trusted(new URL("https://google.com.evil.example/")));
        assertFalse(LensUploadClient.trusted(new URL("https://user@google.com/")));
    }

    private static final class MemoryCookies implements LensUploadClient.CookieBridge {
        final Map<String, String> perHost = new HashMap<>(); final List<String> events = new ArrayList<>();
        String common;
        public String get(String raw) throws IOException {
            String host = new URL(raw).getHost(); events.add("get:" + host);
            String own = perHost.get(host);
            return own == null ? common : common == null ? own : own + "; " + common;
        }
        public void set(String raw, String value) throws IOException {
            String host = new URL(raw).getHost(); events.add("set:" + host);
            String pair = value.split(";", 2)[0];
            if (value.contains("Domain=.google.com")) common = pair; else perHost.put(host, pair);
        }
        public void flush() { events.add("flush"); }
    }
    private static class FakeConnection extends HttpURLConnection {
        FakeConnection() throws java.net.MalformedURLException { super(new URL("https://lens.google.com/v3/upload")); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int getResponseCode() throws IOException { return 200; }
        @Override public Map<String, List<String>> getHeaderFields() { return Map.of(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
    }
    private static final class BlockingConnection extends FakeConnection {
        final CountDownLatch reading = new CountDownLatch(1), released = new CountDownLatch(1);
        volatile boolean disconnected;
        BlockingConnection() throws java.net.MalformedURLException { }
        @Override public int getResponseCode() throws IOException {
            reading.countDown();
            try { if (!released.await(5, TimeUnit.SECONDS)) throw new SocketTimeoutException(); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new IOException(stop); }
            throw new IOException("disconnected");
        }
        @Override public void disconnect() { disconnected = true; released.countDown(); }
    }
}
