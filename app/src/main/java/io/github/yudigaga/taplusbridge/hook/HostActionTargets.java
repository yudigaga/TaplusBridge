package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.runtime.BridgeTransport;
import java.util.Set;

/** Host-side reader accepts only presentation data, never translation settings or input text. */
final class HostActionTargets {
    record Target(String packageName, String title, Bitmap icon) { }
    private HostActionTargets() { }

    static Target read(Context context, String kind) {
        return read(context, kind, null);
    }

    static Target read(Context context, String kind, String source) {
        if (context == null || !("browser".equals(kind) || "translate".equals(kind))
                || source != null && !HostPolicy.validPackage(source)) return null;
        try {
            Bundle request = new Bundle(); request.putString("kind", kind);
            if (source != null) request.putString("source", source);
            Bundle response = BridgeTransport.call(context, "action_target", request);
            if (response == null || !Set.of("configured", "available", "target", "title", "icon").equals(response.keySet())
                    || !Boolean.TRUE.equals(response.get("configured")) || !Boolean.TRUE.equals(response.get("available"))) return null;
            Object rawTarget = response.get("target"), rawTitle = response.get("title"), rawIcon = response.get("icon");
            if (!(rawTarget instanceof String target) || !(rawTitle instanceof String title)
                    || !(rawIcon instanceof Bitmap icon) || icon.isRecycled() || icon.getWidth() != 64 || icon.getHeight() != 64
                    || !(HostPolicy.validTarget(target) || "translate".equals(kind) && BridgeContract.APP_PACKAGE.equals(target))
                    || title.isBlank() || title.length() > 256) return null;
            return new Target(target, title, icon);
        } catch (RuntimeException unavailable) { return null; }
    }
}
