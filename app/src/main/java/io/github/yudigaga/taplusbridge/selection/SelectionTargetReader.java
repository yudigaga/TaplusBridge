package io.github.yudigaga.taplusbridge.selection;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ActionMode;
import io.github.yudigaga.taplusbridge.runtime.BridgeTransport;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One read in flight per source process. No Binder I/O runs inside the ActionMode callbacks. */
public final class SelectionTargetReader implements SelectionActions.TargetSource {
    private static final long CACHE_MILLIS = 2000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "TaplusSelectionConfig"); thread.setDaemon(true); return thread;
    });
    private final Context context;
    private final String source;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<WeakReference<ActionMode>> modes = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private SelectionTargets targets;
    private long loadedAt;
    private boolean loaded, inFlight;
    public SelectionTargetReader(Context context, String source) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application; this.source = source;
    }
    @Override public synchronized SelectionTargets get() { return fresh() ? targets : null; }
    @Override public synchronized boolean refreshing() { return inFlight; }
    @Override public synchronized void watch(ActionMode mode) {
        if (mode == null) return;
        modes.removeIf(reference -> reference.get() == null);
        if (modes.stream().noneMatch(reference -> reference.get() == mode)) modes.add(new WeakReference<>(mode));
        if (fresh() || inFlight) return;
        inFlight = true;
        try { IO.execute(this::read); }
        catch (RuntimeException rejected) { inFlight = false; }
    }
    @Override public synchronized void forget(ActionMode mode) {
        modes.removeIf(reference -> reference.get() == null || reference.get() == mode);
        pending.removeIf(click -> click.mode.get() == null || click.mode.get() == mode);
    }
    @Override public void withFresh(ActionMode mode, Consumer<SelectionTargets> action) {
        SelectionTargets current;
        boolean immediate;
        synchronized (this) {
            if (mode == null || modes.stream().noneMatch(reference -> reference.get() == mode)) return;
            immediate = fresh(); current = targets;
            if (!immediate) {
                pending.removeIf(click -> click.mode.get() == null || click.mode.get() == mode);
                pending.add(new Pending(new WeakReference<>(mode), action));
            }
        }
        if (immediate) action.accept(current);
        else watch(mode);
    }
    private boolean fresh() { return loaded && SystemClock.elapsedRealtime() - loadedAt < CACHE_MILLIS; }
    private void read() {
        SelectionTargets next = null;
        try {
            Bundle request = new Bundle(); request.putString("source", source);
            next = SelectionTargets.parse(BridgeTransport.call(context, "selection_actions", request));
        } catch (RuntimeException unavailable) { }
        synchronized (this) {
            targets = next; loadedAt = SystemClock.elapsedRealtime(); loaded = true; inFlight = false;
        }
        main.post(() -> {
            List<WeakReference<ActionMode>> live;
            List<Pending> clicks;
            SelectionTargets ready;
            synchronized (SelectionTargetReader.this) {
                if (!fresh() || inFlight) return;
                live = new ArrayList<>(modes); clicks = new ArrayList<>(pending); pending.clear(); ready = targets;
            }
            for (Pending click : clicks) {
                ActionMode mode = click.mode.get();
                synchronized (SelectionTargetReader.this) {
                    if (mode == null || modes.stream().noneMatch(reference -> reference.get() == mode)) continue;
                }
                try { click.action.accept(ready); } catch (RuntimeException unavailable) { }
            }
            for (WeakReference<ActionMode> reference : live) {
                ActionMode mode = reference.get();
                if (mode == null) continue;
                synchronized (SelectionTargetReader.this) {
                    if (modes.stream().noneMatch(current -> current.get() == mode)) continue;
                }
                try { mode.invalidate(); } catch (RuntimeException destroyed) { }
            }
        });
    }
    private record Pending(WeakReference<ActionMode> mode, Consumer<SelectionTargets> action) { }
}
