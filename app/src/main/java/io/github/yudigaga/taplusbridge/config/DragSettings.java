package io.github.yudigaga.taplusbridge.config;

import android.content.ComponentName;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The switch, four actions and ordered share targets are committed as one value. */
public final class DragSettings {
    public static final String KEY = "drag_settings";
    public static final String MASK_WINDOW_PREFIX = "TaplusBridge:DragMask:";
    public static final List<String> DIRECTIONS = List.of("up", "down", "left", "right");
    public static final List<String> ACTIONS = List.of("none", "portal", "share", "browser", "rules", "lens", "translate", "copy");
    public static final int MAX_TARGETS = 128, MAX_BYTES = 64_000;
    public static final int DEFAULT_RECOGNITION_DELAY = 2_000, DEFAULT_DRAG_DELAY = 2_000;
    public static final int MIN_STAGE_DELAY = 100, MAX_STAGE_DELAY = 10_000;
    public final boolean enabled, shareOnlySelected;
    public final String up, down, left, right;
    public final List<String> shareTargets;
    public final int recognitionDelayMillis, dragDelayMillis;

    public DragSettings(boolean enabled, String up, String down, String left, String right,
                        boolean shareOnlySelected, Collection<String> shareTargets) {
        this(enabled, up, down, left, right, shareOnlySelected, shareTargets,
                DEFAULT_RECOGNITION_DELAY, DEFAULT_DRAG_DELAY);
    }

    public DragSettings(boolean enabled, String up, String down, String left, String right,
                        boolean shareOnlySelected, Collection<String> shareTargets,
                        int recognitionDelayMillis, int dragDelayMillis) {
        if (up == null || down == null || left == null || right == null
                || !ACTIONS.contains(up) || !ACTIONS.contains(down) || !ACTIONS.contains(left) || !ACTIONS.contains(right)
                || shareTargets == null || shareTargets.size() > MAX_TARGETS
                || recognitionDelayMillis < MIN_STAGE_DELAY || recognitionDelayMillis > MAX_STAGE_DELAY
                || dragDelayMillis < MIN_STAGE_DELAY || dragDelayMillis > MAX_STAGE_DELAY) throw invalid();
        this.enabled = enabled; this.up = up; this.down = down; this.left = left; this.right = right;
        this.shareOnlySelected = shareOnlySelected;
        this.recognitionDelayMillis = recognitionDelayMillis;
        this.dragDelayMillis = dragDelayMillis;
        LinkedHashSet<String> checked = new LinkedHashSet<>();
        for (String raw : shareTargets) {
            ComponentName component = raw == null ? null : ComponentName.unflattenFromString(raw);
            if (raw == null || raw.length() > 768 || component == null || !HostPolicy.validTarget(component.getPackageName())
                    || !component.getClassName().matches("[A-Za-z_$][A-Za-z0-9_$.]*")) throw invalid();
            checked.add(component.flattenToString());
        }
        this.shareTargets = Collections.unmodifiableList(new ArrayList<>(checked));
    }

    public static DragSettings defaults() {
        return new DragSettings(false, "portal", "share", "lens", "translate", false, List.of());
    }

    public String action(String direction) {
        if (direction == null) return "none";
        return switch (direction) {
            case "up" -> up; case "down" -> down; case "left" -> left; case "right" -> right;
            default -> "none";
        };
    }

    public String toJson() {
        try {
            JSONObject data = new JSONObject();
            data.put("schema", 3); data.put("enabled", enabled);
            data.put("up", up); data.put("down", down); data.put("left", left); data.put("right", right);
            data.put("shareOnlySelected", shareOnlySelected); data.put("shareTargets", new JSONArray(shareTargets));
            data.put("recognitionDelay", recognitionDelayMillis); data.put("dragDelay", dragDelayMillis);
            String raw = data.toString();
            if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
            return raw;
        } catch (JSONException impossible) { throw invalid(); }
    }

    public static DragSettings parse(String raw) {
        try {
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
            JSONObject data = new JSONObject(raw);
            if (!(data.get("schema") instanceof Integer schema)) throw invalid();
            int recognitionDelay = DEFAULT_RECOGNITION_DELAY, dragDelay = DEFAULT_DRAG_DELAY;
            if (schema == 1 && data.length() == 8) { /* saved before delay settings existed */ }
            else if (schema == 2 && data.length() == 9 && data.get("captureDelay") instanceof Integer) { }
            else if (schema == 3 && data.length() == 10
                    && data.get("recognitionDelay") instanceof Integer recognition
                    && data.get("dragDelay") instanceof Integer drag) {
                recognitionDelay = recognition; dragDelay = drag;
            }
            else throw invalid();
            if (!(data.get("enabled") instanceof Boolean enabled)
                    || !(data.get("shareOnlySelected") instanceof Boolean selected)) throw invalid();
            for (String direction : DIRECTIONS) if (!(data.get(direction) instanceof String)) throw invalid();
            JSONArray array = data.getJSONArray("shareTargets");
            if (array.length() > MAX_TARGETS) throw invalid();
            List<String> targets = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                if (!(array.get(i) instanceof String value)) throw invalid();
                targets.add(value);
            }
            return new DragSettings(enabled, data.getString("up"), data.getString("down"), data.getString("left"),
                    data.getString("right"), selected, targets, recognitionDelay, dragDelay);
        } catch (JSONException | RuntimeException malformed) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("拖拽配置无效，请重新设置"); }
}
