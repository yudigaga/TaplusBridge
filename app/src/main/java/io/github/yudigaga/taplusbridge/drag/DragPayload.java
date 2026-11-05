package io.github.yudigaga.taplusbridge.drag;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.text.Html;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.capture.IntentObservation;
import io.github.yudigaga.taplusbridge.recognition.LinkExtractor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;

/** Copies only supplied text or a granted local image; item intents are never interpreted. */
public final class DragPayload implements AutoCloseable {
    public static final int MAX_BYTES = 8 * 1024 * 1024, MAX_TEXT = 128 * 1024, MAX_ITEMS = 16;
    public final String text, source, mime;
    public final File image;
    public final int width, height;
    private final String linkText;
    private final List<String> recognizedLinks;

    DragPayload(String text, String source, File image, String mime, int width, int height, String linkText) {
        this(text, source, image, mime, width, height, linkText, List.of());
    }

    private DragPayload(String text, String source, File image, String mime, int width, int height, String linkText,
                        List<String> recognizedLinks) {
        this.text = text; this.source = source; this.image = image; this.mime = mime;
        this.width = width; this.height = height; this.linkText = linkText; this.recognizedLinks = List.copyOf(recognizedLinks);
    }

    public static boolean supports(ClipDescription description) {
        return description != null && (description.hasMimeType("text/plain") || description.hasMimeType("text/html")
                || description.hasMimeType("text/uri-list") || description.hasMimeType("image/*"));
    }

    public static DragPayload read(Context context, ClipData data, String source, CancellationSignal cancelled) throws IOException {
        if (!HostPolicy.validPackage(source) || data == null || !supports(data.getDescription())
                || data.getItemCount() < 1 || data.getItemCount() > MAX_ITEMS) throw new IOException("拖拽内容不受支持");
        StringBuilder text = new StringBuilder(), links = new StringBuilder();
        Uri image = null;
        for (int i = 0; i < data.getItemCount(); i++) {
            cancelled.throwIfCanceled();
            ClipData.Item item = data.getItemAt(i);
            CharSequence supplied = item.getText();
            String html = item.getHtmlText();
            if (supplied != null) append(text, supplied);
            else if (html != null) {
                if (html.length() > MAX_TEXT) throw new IOException("拖拽文字过长");
                append(text, Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
            }
            if (html != null) append(links, html);
            // A card may expose its real link as data without exposing the text of its title.
            Intent embedded = item.getIntent();
            if (embedded != null) {
                String actual = embedded.getDataString();
                if (IntentObservation.validHttpUrl(actual)) {
                    append(links, actual); if (supplied == null && html == null) append(text, actual);
                }
                Object extra = embedded.hasExtra(Intent.EXTRA_TEXT) ? embedded.getExtras().get(Intent.EXTRA_TEXT) : null;
                if (extra instanceof String value && value.length() <= MAX_TEXT)
                    for (String link : LinkExtractor.extract(value)) {
                        if (!IntentObservation.validHttpUrl(link)) continue;
                        append(links, link); if (supplied == null && html == null && text.length() == 0) append(text, link);
                    }
            }
            Uri uri = item.getUri();
            if (uri != null) {
                String scheme = uri.getScheme();
                if ("content".equalsIgnoreCase(scheme) && data.getDescription().hasMimeType("image/*")) {
                    if (image != null) throw new IOException("一次仅支持拖入一张图片");
                    image = uri;
                } else if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) append(text, uri.toString());
                else if (supplied == null && html == null) throw new IOException("仅支持可读取的本地图片或网页链接");
            }
        }
        if (text.toString().isBlank() && image == null) throw new IOException("拖拽内容没有可用的文字或图片");
        String value = text.toString();
        if (image == null) return new DragPayload(value, source, null, "text/plain", 0, 0, links.toString());
        return copyImage(context, image, value, source, links.toString(), cancelled);
    }

    private static void append(StringBuilder into, CharSequence value) throws IOException {
        if (value.length() == 0) return;
        if (value.length() > MAX_TEXT || into.length() + value.length() + (into.length() == 0 ? 0 : 1) > MAX_TEXT)
            throw new IOException("拖拽文字过长");
        if (into.length() != 0) into.append('\n');
        into.append(value);
    }

    private static DragPayload copyImage(Context context, Uri uri, String text, String source, String links,
                                         CancellationSignal cancelled) throws IOException {
        File directory = new File(context.getCacheDir(), "drag");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存拖拽图片");
        deleteExpired(directory);
        File original = File.createTempFile("incoming-", ".img", directory), normalized = null;
        Bitmap bitmap = null;
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        try {
            String declared = context.getContentResolver().getType(uri);
            if (declared != null && !declared.startsWith("image/")) throw new IOException("拖入内容不是图片");
            try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r", cancelled)) {
                if (descriptor == null || descriptor.getLength() > MAX_BYTES) throw new IOException("图片超过 8 MB 或不可读取");
                cancelled.setOnCancelListener(() -> { try { descriptor.close(); } catch (IOException ignored) { } });
                try (InputStream in = descriptor.createInputStream(); FileOutputStream out = new FileOutputStream(original)) {
                    byte[] buffer = new byte[8192]; int total = 0, count;
                    while ((count = in.read(buffer)) != -1) {
                        cancelled.throwIfCanceled();
                        if (SystemClock.elapsedRealtime() > deadline || count > MAX_BYTES - total)
                            throw new IOException("图片超过 8 MB 或读取超时");
                        out.write(buffer, 0, count); total += count;
                    }
                    if (total == 0) throw new IOException("图片为空");
                } finally { cancelled.setOnCancelListener(null); }
            }
            cancelled.throwIfCanceled();
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(original.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 32768 || bounds.outHeight > 32768)
                throw new IOException("图片格式或尺寸不受支持");
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = 1;
            while (sampled(bounds.outWidth, options.inSampleSize) > 2000 || sampled(bounds.outHeight, options.inSampleSize) > 2000
                    || (long) sampled(bounds.outWidth, options.inSampleSize) * sampled(bounds.outHeight, options.inSampleSize) > 3_000_000)
                options.inSampleSize *= 2;
            bitmap = BitmapFactory.decodeFile(original.getPath(), options);
            if (bitmap == null) throw new IOException("无法解码拖拽图片");
            cancelled.throwIfCanceled();
            normalized = File.createTempFile("image-", ".png", directory);
            try (FileOutputStream out = new FileOutputStream(normalized)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("无法保存拖拽图片");
            }
            if (normalized.length() <= 0 || normalized.length() > MAX_BYTES) throw new IOException("处理后的图片超过 8 MB");
            DragPayload payload = new DragPayload(text, source, normalized, "image/png", bitmap.getWidth(), bitmap.getHeight(), links);
            normalized = null; return payload;
        } catch (RuntimeException unavailable) { throw new IOException("无法读取拖拽图片，请检查来源应用授权", unavailable); }
        finally {
            original.delete(); if (normalized != null) normalized.delete();
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static int sampled(int edge, int sample) { return (edge + sample - 1) / sample; }

    public List<String> links() {
        LinkedHashSet<String> result = new LinkedHashSet<>(LinkExtractor.extract(text));
        result.addAll(LinkExtractor.extract(linkText));
        result.addAll(recognizedLinks);
        return List.copyOf(result);
    }

    DragPayload withRecognizedLinks(String recognized) {
        return withRecognizedLinks(recognized, List.of());
    }

    public static boolean validRecognizedLinks(Object values) {
        if (!(values instanceof List<?> links) || links.size() > LinkExtractor.MAX_RESULTS) return false;
        for (Object value : links)
            if (!(value instanceof String url) || url.length() > LinkExtractor.MAX_URL_LENGTH
                    || !IntentObservation.validHttpUrl(url)) return false;
        return true;
    }

    DragPayload withRecognizedLinks(String recognized, List<String> captured) {
        if (!validRecognizedLinks(captured)) throw new IllegalArgumentException("drag_links");
        LinkedHashSet<String> merged = new LinkedHashSet<>(recognizedLinks);
        merged.addAll(LinkExtractor.extract(recognized));
        merged.addAll(captured);
        return new DragPayload(text, source, image, mime, width, height, linkText, List.copyOf(merged));
    }

    public DragPayload select(List<String> texts, List<String> urls, boolean includeImage) throws IOException {
        if (includeImage && image == null) throw new IOException("本轮没有可用的图片");
        StringBuilder chosen = new StringBuilder();
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String value : texts) if (value != null && !value.isBlank() && distinct.add(value.trim())) append(chosen, value);
        LinkedHashSet<String> present = new LinkedHashSet<>(LinkExtractor.extract(chosen.toString()));
        for (String url : urls) {
            if (!IntentObservation.validHttpUrl(url)) throw new IOException("所选内容不是有效的真实网址");
            if (present.add(url)) append(chosen, url);
        }
        if (chosen.length() == 0 && !includeImage) throw new IOException("请至少选择一项可用内容");
        return new DragPayload(chosen.toString(), source, includeImage ? image : null, includeImage ? mime : "text/plain",
                includeImage ? width : 0, includeImage ? height : 0, "");
    }

    public String sharedText() throws IOException {
        StringBuilder result = new StringBuilder(text);
        var present = new LinkedHashSet<>(LinkExtractor.extract(text));
        for (String link : links()) {
            if (!present.add(link)) continue;
            if (result.length() + link.length() + (result.length() == 0 ? 0 : 1) > MAX_TEXT)
                throw new IOException("分享内容超过文字长度限制，无法完整分享链接");
            if (result.length() != 0) result.append('\n');
            result.append(link);
        }
        if (result.length() > MAX_TEXT) throw new IOException("分享内容超过文字长度限制，无法完整分享链接");
        return result.toString();
    }

    static void deleteExpired(File directory) {
        File[] files = directory.listFiles(); if (files == null) return;
        long cutoff = System.currentTimeMillis() - DragShareProvider.TTL;
        for (File file : files)
            if (file.isFile() && file.getName().matches("(?:incoming-|image-)[A-Za-z0-9_-]+\\.(?:img|png)")
                    && file.lastModified() < cutoff) file.delete();
    }

    @Override public void close() { if (image != null) image.delete(); }
}
