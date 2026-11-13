package io.github.yudigaga.taplusbridge.probe;

import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Main-thread lease of the inspected Taplus windows; exact flags and opacity are restored. */
public final class HostProbeWindow implements AutoCloseable {
    private static final long MAX_CAPTURE_AGE_MILLIS = 30_000L;
    private static final String[] WINDOW_FIELDS = {"mSplashFloatView", "mMainFloatView"};
    private final Object service;
    private final String source;
    private final String activity;
    private final Rect content;
    private final float x;
    private final float y;
    private final long capturedElapsed;
    private final int width;
    private final int height;
    private final List<WindowState> windows;
    private boolean closed, passed;

    private HostProbeWindow(Object service, String source, String activity, Rect content,
                            float x, float y, long capturedElapsed, int width, int height,
                            List<WindowState> windows) {
        this.service = service; this.source = source; this.activity = activity;
        this.content = content; this.x = x; this.y = y; this.capturedElapsed = capturedElapsed;
        this.width = width; this.height = height; this.windows = windows;
    }

    /** System fallback owns its source point; this lease only releases the inspected host windows. */
    public static AutoCloseable passthroughOwnedWindows(Object service) throws ReflectiveOperationException {
        requireMain();
        if (!(service instanceof Context context) || !BridgeContract.HOST_PACKAGE.equals(context.getPackageName()))
            throw invalid("host_window_contract");
        OwnedWindowLease lease = new OwnedWindowLease(service, readWindows(service, false));
        try {
            for (WindowState window : lease.windows) {
                window.appliedFlags = (window.originalFlags & ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                window.appliedAlpha = 0f;
                if (window.appliedFlags == window.originalFlags && window.appliedAlpha == window.originalAlpha) continue;
                window.changed = true;
                window.params.flags = window.appliedFlags; window.params.alpha = window.appliedAlpha;
                window.manager.updateViewLayout(window.view, window.params);
            }
            return lease;
        } catch (RuntimeException failure) {
            try { lease.close(); } catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
    }

    /**
     * Only the inspected host contract is read. A stale point or obstructing opaque window fails
     * before any change; no alternate coordinate, display, or visibility workaround is guessed.
     */
    public static HostProbeWindow prepare(Object service, String expectedSource, long capturedElapsed)
            throws ReflectiveOperationException {
        requireMain();
        requireFresh(capturedElapsed);
        if (!(service instanceof Context context)
                || !BridgeContract.HOST_PACKAGE.equals(context.getPackageName())
                || !CaptureLease.allowedSource(expectedSource)
                || !expectedSource.equals(value(service, "getInjectorPackage"))) throw invalid("source_changed");
        Object rawActivity = value(service, "getInjectorActivity");
        if (!(rawActivity instanceof String activity) || activity.length() > 256
                || !activity.startsWith(expectedSource + ".")
                || !activity.matches("[A-Za-z_$][A-Za-z0-9_.$]*")) throw invalid("source_activity_missing");
        Object rawRect = value(service, "getContentRect");
        if (!(rawRect instanceof Rect rect) || rect.isEmpty()) throw invalid("card_bounds_missing");
        Rect content = new Rect(rect);
        List<WindowState> windows = readWindows(service);
        if (windows.isEmpty()) throw invalid("host_window_missing");
        Point screen = screen(windows.get(0).view);
        if (content.left < 0 || content.top < 0 || content.right > screen.x
                || content.bottom > screen.y) throw invalid("card_bounds_outside_screen");
        PointF selected = selectedPoint(service, content);
        boolean visibleWindow = false;
        for (WindowState window : windows) {
            Point other = screen(window.view);
            if (!screen.equals(other)) throw invalid("display_changed");
            window.visible = visible(window.view);
            visibleWindow |= window.visible;
            window.covers = window.visible && covers(window.view, selected.x, selected.y);
            float opacity = window.params.alpha;
            if (!Float.isFinite(opacity) || opacity < 0f || opacity > 1f) throw invalid("window_alpha_invalid");
            boolean inspectedPhone = "mMainFloatView".equals(window.serviceField)
                    && window.params.type == WindowManager.LayoutParams.TYPE_PHONE;
            if (window.covers && window.params.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    && !inspectedPhone) throw invalid("unsupported_covering_window");
            // The stock main window is full-screen TYPE_PHONE. A NOT_TOUCHABLE opaque overlay
            // cannot safely pass the source point through. Release only our two inspected windows.
            window.appliedAlpha = window.covers ? 0f : window.originalAlpha;
            window.appliedFlags = window.originalFlags & ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            if (window.covers) window.appliedFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (!visibleWindow) throw invalid("host_window_hidden");
        HostProbeWindow result = new HostProbeWindow(service, expectedSource, activity, content,
                selected.x, selected.y, capturedElapsed, screen.x, screen.y, windows);
        try {
            // Stay visible until the finger is actually up. Passthrough is applied on READY.
            result.checkCurrent();
            return result;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            try { result.close(); } catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
    }

    /** Lets the later source click pass through. The recognition panel stays up until this call. */
    public void passthrough() throws ReflectiveOperationException {
        requireMain();
        if (closed) throw invalid("probe_closed");
        if (passed) return;
        passed = true;
        try { apply(); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            try { close(); } catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
    }

    /** A fresh copy; calling code cannot alter the window lease by changing the returned Bundle. */
    public Bundle snapshot() {
        requireMain();
        try { checkCurrent(); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            try { close(); } catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw invalid("probe_context_changed", failure);
        }
        Bundle data = new Bundle();
        data.putString("source_activity", activity);
        data.putFloat("x", x); data.putFloat("y", y);
        data.putInt("left", content.left); data.putInt("top", content.top);
        data.putInt("right", content.right); data.putInt("bottom", content.bottom);
        data.putInt("width", width); data.putInt("height", height);
        data.putInt("rotation", Surface.ROTATION_0); data.putInt("display", Display.DEFAULT_DISPLAY);
        data.putLong("captured_elapsed", capturedElapsed);
        return data;
    }

    /** Restores every captured window even if another restoration fails. Idempotent on the main thread. */
    @Override public void close() {
        requireMain();
        if (closed) return;
        closed = true;
        RuntimeException firstFailure = null;
        for (int i = windows.size() - 1; i >= 0; i--) {
            WindowState window = windows.get(i);
            if (!window.changed) continue;
            window.params.flags = window.originalFlags;
            window.params.alpha = window.originalAlpha;
            try {
                if (window.view.isAttachedToWindow()) window.manager.updateViewLayout(window.view, window.params);
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw invalid("window_restore_failed", firstFailure);
    }

    private void apply() throws ReflectiveOperationException {
        for (WindowState window : windows) {
            if (!window.matchesOwner() || window.params.flags != window.originalFlags || window.params.alpha != window.originalAlpha)
                throw invalid("host_window_changed");
            if (window.appliedFlags == window.originalFlags && window.appliedAlpha == window.originalAlpha) continue;
            // Track the write before dispatch so a throwing update also gets rolled back.
            window.changed = true;
            window.params.flags = window.appliedFlags;
            window.params.alpha = window.appliedAlpha;
            window.manager.updateViewLayout(window.view, window.params);
        }
    }

    private void checkCurrent() throws ReflectiveOperationException {
        if (closed) throw invalid("probe_closed");
        requireFresh(capturedElapsed);
        if (!source.equals(value(service, "getInjectorPackage"))
                || !activity.equals(value(service, "getInjectorActivity"))
                || !content.equals(value(service, "getContentRect"))) throw invalid("source_changed");
        PointF selected = selectedPoint(service, content);
        if (Float.compare(x, selected.x) != 0 || Float.compare(y, selected.y) != 0) throw invalid("source_changed");
        List<WindowState> currentWindows = readWindows(service);
        if (currentWindows.size() != windows.size()) throw invalid("host_window_changed");
        for (int i = 0; i < windows.size(); i++) {
            WindowState before = windows.get(i), current = currentWindows.get(i);
            if (before.owner != current.owner || before.view != current.view || before.params != current.params
                    || before.manager != current.manager) throw invalid("host_window_changed");
        }
        for (WindowState window : windows) {
            if (field(service.getClass(), window.serviceField).get(service) != window.owner
                    || !window.matchesOwner() || window.params.flags != window.expectedFlags()
                    || window.params.alpha != window.expectedAlpha()
                    || window.visible != visible(window.view)) throw invalid("host_window_changed");
            Point size = screen(window.view);
            if (size.x != width || size.y != height) throw invalid("display_changed");
            if (window.covers != (window.visible && covers(window.view, x, y))) throw invalid("host_window_moved");
        }
    }

    private static List<WindowState> readWindows(Object service) throws ReflectiveOperationException {
        return readWindows(service, true);
    }

    private static List<WindowState> readWindows(Object service, boolean showingOnly) throws ReflectiveOperationException {
        List<WindowState> windows = new ArrayList<>();
        for (String name : WINDOW_FIELDS) {
            Object owner = field(service.getClass(), name).get(service);
            if (owner == null) continue;
            Object rawView = field(owner.getClass(), "mMainView").get(owner);
            Object showing = field(owner.getClass(), "mIsShowing").get(owner);
            if (!(rawView instanceof View view) || (showingOnly && !Boolean.TRUE.equals(showing))
                    || !view.isAttachedToWindow()) continue;
            Object rawParams = field(owner.getClass(), "wmParams").get(owner);
            Object rawManager = field(owner.getClass(), "mWindowManager").get(owner);
            if (!(rawParams instanceof WindowManager.LayoutParams params) || !(rawManager instanceof WindowManager manager))
                throw invalid("host_window_contract");
            windows.add(new WindowState(name, owner, view, params, manager));
        }
        return windows;
    }

    private static PointF selectedPoint(Object service, Rect rect) throws ReflectiveOperationException {
        Object first = value(service, "getFirstTouchPoint");
        if (first instanceof PointF point && contains(rect, point.x, point.y)) return new PointF(point.x, point.y);
        Object injector = value(service, "getInjectorPoint");
        if (injector instanceof Point point && contains(rect, point.x, point.y)) return new PointF(point.x, point.y);
        throw invalid("card_point_missing");
    }

    private static boolean contains(Rect rect, float x, float y) {
        return Float.isFinite(x) && Float.isFinite(y) && x >= rect.left && x < rect.right
                && y >= rect.top && y < rect.bottom;
    }

    private static boolean covers(View view, float x, float y) {
        int[] location = new int[2]; view.getLocationOnScreen(location);
        return view.getWidth() > 0 && view.getHeight() > 0 && x >= location[0] && y >= location[1]
                && x < (long) location[0] + view.getWidth() && y < (long) location[1] + view.getHeight();
    }

    private static boolean visible(View view) {
        return view.getVisibility() == View.VISIBLE && view.getWindowVisibility() == View.VISIBLE;
    }

    @SuppressWarnings("deprecation")
    private static Point screen(View view) {
        Display display = view.getDisplay();
        if (display == null || display.getDisplayId() != Display.DEFAULT_DISPLAY
                || display.getRotation() != Surface.ROTATION_0) throw invalid("unsupported_display");
        Point size = new Point(); display.getRealSize(size);
        if (size.x <= 0 || size.y <= 0 || size.x > size.y) throw invalid("unsupported_display");
        return size;
    }

    private static void requireFresh(long capturedElapsed) {
        long age = SystemClock.elapsedRealtime() - capturedElapsed;
        if (capturedElapsed < 0 || age < 0 || age > MAX_CAPTURE_AGE_MILLIS) throw invalid("card_point_expired");
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw invalid("main_thread_required");
    }

    private static Object value(Object object, String name) throws ReflectiveOperationException {
        Method method = object.getClass().getMethod(name); method.setAccessible(true); return method.invoke(object);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException("Host probe window contract");
    }

    private static IllegalStateException invalid(String reason) { return new IllegalStateException(reason); }
    private static IllegalStateException invalid(String reason, Throwable failure) { return new IllegalStateException(reason, failure); }

    private static final class OwnedWindowLease implements AutoCloseable {
        final Object service;
        final List<WindowState> windows;
        boolean closed;
        OwnedWindowLease(Object service, List<WindowState> windows) { this.service = service; this.windows = windows; }
        @Override public void close() {
            requireMain();
            if (closed) return;
            closed = true;
            RuntimeException firstFailure = null;
            for (int i = windows.size() - 1; i >= 0; i--) {
                WindowState window = windows.get(i);
                if (!window.changed) continue;
                try {
                    if (field(service.getClass(), window.serviceField).get(service) != window.owner
                            || field(window.owner.getClass(), "mMainView").get(window.owner) != window.view
                            || field(window.owner.getClass(), "wmParams").get(window.owner) != window.params
                            || field(window.owner.getClass(), "mWindowManager").get(window.owner) != window.manager
                            || window.params.flags != window.appliedFlags || window.params.alpha != window.appliedAlpha) continue;
                    window.params.flags = window.originalFlags; window.params.alpha = window.originalAlpha;
                    if (window.view.isAttachedToWindow()) window.manager.updateViewLayout(window.view, window.params);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    RuntimeException restoreFailure = invalid("window_restore_failed", failure);
                    if (firstFailure == null) firstFailure = restoreFailure;
                    else firstFailure.addSuppressed(restoreFailure);
                }
            }
            if (firstFailure != null) throw firstFailure;
        }
    }

    /** Fixed reason vocabulary only; never includes an arbitrary exception message or geometry. */
    public static String failureReason(Throwable failure) {
        String reason = failure == null ? null : failure.getMessage();
        if (reason == null) return "geometry_unavailable";
        return switch(reason) {
            case "source_changed", "source_activity_missing", "card_bounds_missing", "host_window_missing",
                    "card_bounds_outside_screen", "display_changed", "window_alpha_invalid", "unsupported_covering_window",
                    "host_window_hidden", "host_window_changed", "host_window_moved", "host_window_contract",
                    "card_point_missing", "unsupported_display", "card_point_expired", "main_thread_required",
                    "probe_context_changed", "probe_closed", "window_restore_failed" -> reason;
            default -> "geometry_unavailable";
        };
    }

    private static final class WindowState {
        final String serviceField;
        final Object owner;
        final View view;
        final WindowManager.LayoutParams params;
        final WindowManager manager;
        final int originalFlags;
        final float originalAlpha;
        int appliedFlags;
        float appliedAlpha;
        boolean changed, visible, covers;
        WindowState(String serviceField, Object owner, View view,
                    WindowManager.LayoutParams params, WindowManager manager) {
            this.serviceField = serviceField; this.owner = owner;
            this.view = view; this.params = params; this.manager = manager;
            originalFlags = params.flags;
            originalAlpha = params.alpha;
        }
        int expectedFlags() { return changed ? appliedFlags : originalFlags; }
        float expectedAlpha() { return changed ? appliedAlpha : originalAlpha; }
        boolean matchesOwner() throws ReflectiveOperationException {
            return view.isAttachedToWindow() && Boolean.TRUE.equals(field(owner.getClass(), "mIsShowing").get(owner))
                    && field(owner.getClass(), "mMainView").get(owner) == view
                    && field(owner.getClass(), "wmParams").get(owner) == params
                    && field(owner.getClass(), "mWindowManager").get(owner) == manager;
        }
    }
}
