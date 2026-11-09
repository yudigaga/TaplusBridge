package io.github.yudigaga.taplusbridge.translation;

import android.os.CancellationSignal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Synchronous worker-only transport; secrets and response bodies never enter diagnostic messages. */
public final class AiTranslationClient {
    private static final int MAX_INPUT = 32768, MAX_OUTPUT = 1024 * 1024;
    private static final ScheduledThreadPoolExecutor TIMEOUT = deadlines();
    interface ConnectionFactory { HttpURLConnection open(URL endpoint) throws IOException; }
    private AiTranslationClient() {}

    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "TaplusBridge-translation-timeout"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(3, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public static String translate(TranslationSettings settings, String text, CancellationSignal cancel) throws IOException {
        return translate(settings, text, cancel, endpoint -> (HttpsURLConnection) endpoint.openConnection());
    }

    static String translate(TranslationSettings settings, String text, CancellationSignal cancel, ConnectionFactory connections) throws IOException {
        try { settings.validate(); }
        catch (RuntimeException invalid) { throw failure("translation_configuration"); }
        if (settings.mode != TranslationSettings.Mode.AI || text == null || text.isBlank() || text.length() > MAX_INPUT)
            throw failure("translation_input");
        if (cancel != null && cancel.isCanceled() || Thread.currentThread().isInterrupted()) throw failure("translation_cancelled");
        HttpURLConnection connection;
        try { connection = connections.open(new URL(settings.endpoint)); }
        catch (IOException | RuntimeException unavailable) { throw failure("translation_network"); }
        AtomicBoolean timedOut = new AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        var timer = TIMEOUT.schedule(() -> { timedOut.set(true); connection.disconnect(); }, 30, TimeUnit.SECONDS);
        if (cancel != null) cancel.setOnCancelListener(connection::disconnect);
        try {
            check(cancel, deadline);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(30000); connection.setReadTimeout(30000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + settings.apiKey);
            byte[] body = request(settings, text).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) { output.write(body); }
            check(cancel, deadline);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw failure("translation_http_" + status);
            if (connection.getContentLengthLong() > MAX_OUTPUT) throw failure("translation_response_too_large");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                while (true) {
                    check(cancel, deadline);
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (count > MAX_OUTPUT - output.size()) throw failure("translation_response_too_large");
                    output.write(buffer, 0, count);
                }
                String response = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
                check(cancel, deadline);
                return response(response);
            }
        } catch (IOException | RuntimeException failed) {
            if (cancel != null && cancel.isCanceled() || Thread.currentThread().isInterrupted()) throw failure("translation_cancelled");
            if (timedOut.get() || System.nanoTime() >= deadline) throw failure("translation_timeout");
            String reason = failed.getMessage();
            if (reason != null && reason.matches("translation_(?:input|configuration|cancelled|timeout|network|response_too_large|response_invalid|http_[0-9]{3})"))
                throw failure(reason);
            throw failure("translation_network");
        } finally {
            timer.cancel(false);
            if (cancel != null) cancel.setOnCancelListener(null);
            connection.disconnect();
        }
    }

    private static String request(TranslationSettings settings, String text) throws IOException {
        try {
            String prompt = (settings.systemPrompt.isBlank() ? TranslationSettings.DEFAULT_PROMPT : settings.systemPrompt)
                    + "\n目标语言：" + settings.targetLanguage + "。只输出译文。";
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", prompt));
            messages.put(new JSONObject().put("role", "user").put("content", text));
            return new JSONObject().put("model", settings.model).put("messages", messages).put("stream", false).toString();
        } catch (JSONException impossible) { throw failure("translation_configuration"); }
    }

    private static String response(String raw) throws IOException {
        try {
            JSONObject choice = new JSONObject(raw).getJSONArray("choices").getJSONObject(0);
            Object finish = choice.opt("finish_reason");
            if (finish != null && finish != JSONObject.NULL && !"stop".equals(finish)) throw failure("translation_response_invalid");
            Object content = choice.getJSONObject("message").get("content");
            String value;
            if (content instanceof String text) value = text;
            else if (content instanceof JSONArray blocks) {
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < blocks.length(); i++) {
                    JSONObject block = blocks.getJSONObject(i);
                    if ("text".equals(block.optString("type")) && block.get("text") instanceof String text) joined.append(text);
                }
                value = joined.toString();
            } else throw failure("translation_response_invalid");
            if (value.isBlank() || value.length() > MAX_OUTPUT) throw failure("translation_response_invalid");
            return value;
        } catch (JSONException | RuntimeException invalid) { throw failure("translation_response_invalid"); }
    }

    private static void check(CancellationSignal cancel, long deadline) throws IOException {
        if (cancel != null && cancel.isCanceled() || Thread.currentThread().isInterrupted()) throw failure("translation_cancelled");
        if (System.nanoTime() >= deadline) throw failure("translation_timeout");
    }
    private static IOException failure(String reason) { return new IOException(reason); }
}
