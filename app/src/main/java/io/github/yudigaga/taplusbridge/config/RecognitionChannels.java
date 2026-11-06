package io.github.yudigaga.taplusbridge.config;

import java.util.Arrays;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** Saved source choices stay independent from routing, capture and gesture settings. */
public final class RecognitionChannels {
    public static final String KEY = "recognition_channels";
    public enum Channel {
        NATIVE("native"), ASSIST("assist"), OCR("ocr"), SERVER("server"), SEGMENT("segment"), CLIPBOARD("clipboard");
        public final String wire;
        Channel(String wire) { this.wire = wire; }
    }
    private final boolean[] choices;
    private RecognitionChannels(boolean[] choices) { this.choices = choices.clone(); }
    public static RecognitionChannels defaults() {
        boolean[] choices = new boolean[Channel.values().length];
        Arrays.fill(choices, true);
        return new RecognitionChannels(choices);
    }
    public static RecognitionChannels disabled() { return new RecognitionChannels(new boolean[Channel.values().length]); }
    public boolean enabled(Channel channel) { return choices[Objects.requireNonNull(channel, "channel").ordinal()]; }
    public RecognitionChannels with(Channel channel, boolean enabled) {
        boolean[] changed = choices.clone();
        changed[Objects.requireNonNull(channel, "channel").ordinal()] = enabled;
        return new RecognitionChannels(changed);
    }
    public String toJson() {
        try {
            JSONObject data = new JSONObject();
            for (Channel channel : Channel.values()) data.put(channel.wire, enabled(channel));
            return data.toString();
        } catch (JSONException impossible) { throw invalid(); }
    }
    public static RecognitionChannels parse(String raw) {
        try {
            if (raw == null || raw.length() > 1024) throw invalid();
            JSONObject data = new JSONObject(raw);
            if (data.length() != Channel.values().length) throw invalid();
            boolean[] choices = new boolean[Channel.values().length];
            for (Channel channel : Channel.values()) {
                if (!(data.get(channel.wire) instanceof Boolean enabled)) throw invalid();
                choices[channel.ordinal()] = enabled;
            }
            return new RecognitionChannels(choices);
        } catch (JSONException | RuntimeException malformed) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("recognition_channels_invalid"); }
}
