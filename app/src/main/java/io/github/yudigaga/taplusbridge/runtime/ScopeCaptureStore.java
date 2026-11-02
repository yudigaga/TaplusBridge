package io.github.yudigaga.taplusbridge.runtime;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.hook.ScopeCapturePayload;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One short-lived image or URL, transferred once through the authenticated host Binder. */
public final class ScopeCaptureStore {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TaplusBridge-scope-image"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TaplusBridge-scope-expiry"); t.setDaemon(true); return t;
    });
    private record Entry(String nonce, int owner, String identity, String source, long until,
                         Bundle metadata, String kind, byte[] png, String url) {}
    private Entry entry;

    public synchronized String issue(int owner, RuntimeSnapshot config, Bundle metadata, byte[] png) {
        return issue(owner, config, metadata, "image", png, null);
    }

    public synchronized String issueText(int owner, RuntimeSnapshot config, Bundle metadata, String url) {
        return issue(owner, config, metadata, "text", null, url);
    }

    public synchronized String issueImage(int owner, RuntimeSnapshot config, Bundle metadata, byte[] png, String url) {
        return issue(owner, config, metadata, "image", png, url);
    }

    public synchronized String issueResume(int owner, RuntimeSnapshot config, Bundle metadata) {
        return issue(owner, config, metadata, "resume", null, null);
    }

    private String issue(int owner, RuntimeSnapshot config, Bundle metadata, String kind, byte[] png, String url) {
        String source = metadata == null ? null : metadata.getString("source");
        boolean text = "text".equals(kind), image = "image".equals(kind);
        if (owner < 10000 || owner / 100000 != 0 || config == null || !config.enabled
                || (image ? !config.scopeCapture : !config.scopeLinkProbe)
                || !config.sourceScope.managed || !config.sourceScope.allows(source)
                || (url != null && (!config.scopeLinkProbe || !ScopeCapturePayload.validTextUrl(url)))
                || (text && url == null)
                || (image && (png == null || png.length < 8 || png.length > MAX_BYTES
                        || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G')))
            throw new IllegalArgumentException("scope_capture_invalid");
        byte[] token = new byte[24]; random.nextBytes(token);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        clear();
        entry = new Entry(nonce, owner, config.identity, source, SystemClock.elapsedRealtime() + 10_000,
                new Bundle(metadata), kind, image ? png.clone() : null, url);
        expiry.schedule(() -> expire(nonce), 10, TimeUnit.SECONDS);
        return nonce;
    }

    public synchronized Bundle take(int owner, Bundle request, RuntimeSnapshot config) {
        Entry current = entry;
        if (current == null || request == null || owner != current.owner
                || !current.nonce.equals(request.getString("nonce"))) return Bundle.EMPTY;
        entry = null;
        if (SystemClock.elapsedRealtime() >= current.until || config == null || !config.enabled
                || ("image".equals(current.kind) ? !config.scopeCapture : !config.scopeLinkProbe)
                || !config.identity.equals(current.identity) || !config.sourceScope.managed
                || !config.sourceScope.allows(current.source)) {
            erase(current); return Bundle.EMPTY;
        }
        if (!"image".equals(current.kind)) {
            Bundle result = new Bundle(current.metadata);
            result.putString("kind", current.kind);
            if (current.url != null) result.putString("url", current.url); else result.remove("url");
            result.putInt("bytes", 0); result.remove("fd"); result.putString("scope_identity", current.identity);
            return result;
        }
        ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException unavailable) { return Bundle.EMPTY; }
        try {
            writer.execute(() -> {
                try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(current.png);
                } catch (IOException ignored) { }
            });
            Bundle result = new Bundle(current.metadata);
            result.putString("scope_identity", current.identity);
            result.putString("kind", "image");
            if (current.url != null) result.putString("url", current.url); else result.remove("url");
            result.putParcelable("fd", pipe[0]); result.putInt("bytes", current.png.length);
            return result;
        } catch (RuntimeException rejected) {
            for (var end : pipe) try { end.close(); } catch (IOException ignored) { }
            return Bundle.EMPTY;
        }
    }

    public synchronized void nativeResult(String source) {
        if (entry != null && entry.source.equals(source)) clear();
    }
    private synchronized void expire(String nonce) { if (entry != null && entry.nonce.equals(nonce)) clear(); }
    public synchronized void clear() {
        if (entry != null) erase(entry);
        entry = null;
    }
    private static void erase(Entry entry) {
        if (entry.png != null) java.util.Arrays.fill(entry.png, (byte) 0);
    }
}
