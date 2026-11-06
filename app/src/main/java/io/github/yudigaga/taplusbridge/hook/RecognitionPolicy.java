package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.os.Bundle;
import android.graphics.Rect;
import android.os.Parcelable;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel;
import io.github.yudigaga.taplusbridge.runtime.BridgeTransport;
import java.util.Map;

/** Host consumers require the current published policy; missing publication disables every source. */
final class RecognitionPolicy {
    private RecognitionPolicy() {}

    record State(RecognitionChannels channels, boolean managed) {
        boolean disabled(Channel channel) { return managed && !channels.enabled(channel); }
        boolean commerceDisabled() { return disabled(Channel.CLIPBOARD) || disabled(Channel.SERVER); }
    }

    static RecognitionChannels read(Context context) {
        return current(context).channels();
    }

    static State current(Context context) {
        State unavailable = new State(RecognitionChannels.disabled(), false);
        if (context == null) return unavailable;
        try {
            Bundle result = BridgeTransport.call(context, RecognitionChannels.KEY, Bundle.EMPTY);
            if (result == null || !result.getBoolean("active")
                    || !(result.get("identity") instanceof String identity) || identity.isBlank()
                    || identity.length() > 128 || !(result.get("channels") instanceof String channels))
                return unavailable;
            return new State(RecognitionChannels.parse(channels), true);
        } catch (RuntimeException failure) { return unavailable; }
    }

    static Map<?, ?> properties(Object result) {
        if (result == null) return Map.of();
        try {
            Object raw = result.getClass().getMethod("getPropertyMap").invoke(result);
            return raw instanceof Map<?, ?> map ? map : Map.of();
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return Map.of(); }
    }

    static boolean marked(Object result, String flag) {
        if (result == null) return false;
        try {
            for (String job : new String[]{"text_pick", "image_pick"}) {
                Object raw = result.getClass().getMethod("getParams", String.class).invoke(result, job);
                Object payload = raw instanceof Map<?, ?> map ? map.get("text_pick".equals(job) ? "pick_mode" : "image_pick_mode") : null;
                if (payload instanceof Bundle bundle && bundle.getBoolean(flag)) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { }
        return false;
    }

    /** Rebuilt drag payloads already contain filtered sources and must not be reclassified as native. */
    static String nativeText(Object result, RecognitionChannels channels) throws ReflectiveOperationException {
        boolean forwarded = marked(result, "taplus_drag");
        String text = forwarded || channels.enabled(Channel.NATIVE) && !marked(result, "taplus_scope_capture")
                ? HostScopeCaptureHooks.nativeText(result) : null;
        Map<?, ?> properties = properties(result);
        if (channels.enabled(Channel.NATIVE)) text = append(text, properties.get("taplus_native_text"));
        if (forwarded || channels.enabled(Channel.ASSIST))
            text = append(text, properties.get("taplus_scope_text"));
        Object probe = properties.get("taplus_scope_link_url");
        if (probe instanceof String url && ScopeCapturePayload.validTextUrl(url)) text = append(text, url);
        return text;
    }

    /** Stock segmentation may echo native text even when local native extraction is disabled. */
    static String recognizedText(Object result, boolean image, String words, RecognitionChannels channels)
            throws ReflectiveOperationException {
        String accepted = channels.enabled(Channel.NATIVE) || image && channels.enabled(Channel.OCR)
                ? words : nativeText(result, channels);
        return accepted == null ? "" : accepted;
    }

    /** Retain pick identity and geometry while removing native text before the stock consumer reads it. */
    static void filterText(Object result, RecognitionChannels channels) throws ReflectiveOperationException {
        if (result == null || channels.enabled(Channel.NATIVE) || marked(result, "taplus_drag")) return;
        String text = nativeText(result, channels);
        Object raw = result.getClass().getMethod("getParams", String.class).invoke(result, "text_pick");
        Object original = raw instanceof Map<?, ?> map ? map.get("pick_mode") : null;
        Bundle filtered = new Bundle();
        filtered.putInt("catchType", 1);
        filtered.putString("viewContent", text == null ? "" : text);
        if (original instanceof Bundle before) {
            if (before.get("view_rect") instanceof Rect rect) filtered.putParcelable("view_rect", new Rect(rect));
            if (before.get("touch_points") instanceof Parcelable[] points) filtered.putParcelableArray("touch_points", points.clone());
            if (before.getBoolean("taplus_scope_capture")) filtered.putBoolean("taplus_scope_capture", true);
        }
        result.getClass().getMethod("addContent", String.class, String.class, Object.class)
                .invoke(result, "text_pick", "pick_mode", filtered);
    }

    static void filterText(Object result, State policy) throws ReflectiveOperationException {
        if (policy.disabled(Channel.NATIVE)) filterText(result, policy.channels());
    }

    static String boundedText(String text) {
        return text == null ? "" : text.substring(0, Math.min(text.length(), ScopeCapturePayload.MAX_CONTENT_LENGTH));
    }

    private static String append(String text, Object value) {
        if (!(value instanceof String next) || next.isBlank()) return text;
        if (next.length() > ScopeCapturePayload.MAX_CONTENT_LENGTH) next = next.substring(0, ScopeCapturePayload.MAX_CONTENT_LENGTH);
        if (text == null || text.isBlank()) return next;
        if (text.contains(next) || text.length() + next.length() + 1 > ScopeCapturePayload.MAX_CONTENT_LENGTH) return text;
        return text + "\n" + next;
    }
}
