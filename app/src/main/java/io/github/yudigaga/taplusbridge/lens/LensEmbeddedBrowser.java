package io.github.yudigaga.taplusbridge.lens;

import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Hosts one surface lifetime of the worker's existing WebView without copying its session. */
public final class LensEmbeddedBrowser implements SurfaceHolder.Callback, View.OnAttachStateChangeListener {
    public interface Listener {
        void onSurfaceReady(IBinder hostToken, int displayId, int width, int height);
        void onResize(int width, int height);
        void onClosed();
    }

    private final View anchor;
    private final Listener listener;
    private SurfaceView surface;
    private FrameLayout container;
    private boolean created, requested, closed;
    private int width, height, sentWidth, sentHeight;

    public LensEmbeddedBrowser(View anchor, Listener listener) {
        this.anchor = anchor; this.listener = listener;
    }

    public boolean mount(FrameLayout parent) {
        if (Build.VERSION.SDK_INT < 30 || closed || surface != null || !anchor.isAttachedToWindow()
                || parent == null || !parent.isAttachedToWindow()) return false;
        container = parent;
        surface = new SurfaceView(anchor.getContext());
        surface.setZOrderOnTop(true);
        surface.setFocusable(true); surface.setFocusableInTouchMode(true);
        surface.setContentDescription("Google Lens 当前图片会话");
        surface.getHolder().addCallback(this);
        surface.addOnAttachStateChangeListener(this); anchor.addOnAttachStateChangeListener(this);
        surface.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            width = right - left; height = bottom - top; sendGeometry();
        });
        parent.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        return true;
    }

    public boolean isShowing() {
        return !closed && surface != null && surface.getParent() == container;
    }

    public boolean hasFocus() { return isShowing() && surface.hasFocus(); }

    public boolean attachSurface(SurfaceControlViewHost.SurfacePackage incoming) {
        if (incoming == null) return false;
        if (Build.VERSION.SDK_INT < 30) return false;
        if (closed || !requested || surface == null
                || !surface.isAttachedToWindow()) {
            incoming.release(); return false;
        }
        try {
            // SurfaceView owns the accepted package and releases it when detached from its window.
            surface.setChildSurfacePackage(incoming);
            surface.requestFocus();
            return true;
        } catch (RuntimeException unavailable) {
            incoming.release(); destroy(); return false;
        }
    }

    public void destroy() {
        destroy(true);
    }

    private void destroy(boolean removeSurface) {
        if (closed) return;
        closed = true;
        anchor.removeOnAttachStateChangeListener(this);
        SurfaceView previous = surface; surface = null;
        if (previous != null) {
            previous.getHolder().removeCallback(this); previous.removeOnAttachStateChangeListener(this);
            previous.clearFocus();
            if (removeSurface && previous.getParent() instanceof ViewGroup parent) parent.removeView(previous);
        }
        container = null;
        listener.onClosed();
    }

    private void sendGeometry() {
        if (Build.VERSION.SDK_INT < 30 || closed || !created || surface == null || width <= 0 || height <= 0) return;
        if (!requested) {
            IBinder token = surface.getHostToken();
            Display display = surface.getDisplay();
            if (token == null || display == null) return;
            requested = true; sentWidth = width; sentHeight = height;
            listener.onSurfaceReady(token, display.getDisplayId(), width, height);
        } else if (width != sentWidth || height != sentHeight) {
            sentWidth = width; sentHeight = height;
            listener.onResize(width, height);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (closed) return;
        created = true;
        if (surface != null) { width = surface.getWidth(); height = surface.getHeight(); }
        sendGeometry();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (closed) return;
        this.width = width; this.height = height; sendGeometry();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        SurfaceView previous = surface;
        destroy(false);
        // Detach callbacks can run before ViewGroup finishes removing this child.
        if (previous != null && previous.getParent() instanceof ViewGroup parent)
            new Handler(Looper.getMainLooper()).post(() -> {
                if (previous.getParent() == parent) parent.removeView(previous);
            });
    }
    @Override public void onViewAttachedToWindow(View view) { sendGeometry(); }
    @Override public void onViewDetachedFromWindow(View view) { destroy(false); }
}
