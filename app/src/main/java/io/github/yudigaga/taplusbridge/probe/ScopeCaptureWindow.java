package io.github.yudigaga.taplusbridge.probe;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.WindowManager;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A foreground main-window capture lease. It never changes input, window flags or secure layers. */
public final class ScopeCaptureWindow {
    static final int MAX_EDGE = 1600, MAX_PIXELS = 3_000_000;
    private final Field windowManager, globalLock, root, focusedWindow;
    private final Field ownerUid, showUser, attributes, activityRecord, activityComponent, activityUser;
    private final Method displayContent, displayWindows, touchableWindow, frame, mainWindow, parentWindow, visible, surface, children;
    private final CaptureBackend backend;

    public static final class Snapshot {
        public final String source, activity;
        public final int uid, user, display;
        public final float x, y;
        public final Rect bounds;
        private final Rect expectedBounds;
        private final Object window, record, surface;

        private Snapshot(String source, String activity, int uid, int user, int display, float x, float y,
                         Rect bounds, Object window, Object record, Object surface) {
            this.source = source; this.activity = activity; this.uid = uid; this.user = user;
            this.display = display; this.x = x; this.y = y;
            this.bounds = new Rect(bounds); expectedBounds = new Rect(bounds);
            this.window = window; this.record = record; this.surface = surface;
        }
    }

    /** Backend seam exercises WMS checks without claiming a local JVM performed a real capture. */
    interface CaptureBackend {
        Class<?> surfaceType();
        default boolean validSurface(Object surface) throws ReflectiveOperationException { return surface != null; }
        Bitmap capture(Object surface, Object[] excluded, int uid, Rect crop, float scale)
                throws ReflectiveOperationException;
    }

    public ScopeCaptureWindow(Class<?> atmsType) throws ReflectiveOperationException {
        this(atmsType, ReflectiveCapture.create());
    }

    ScopeCaptureWindow(Class<?> atmsType, CaptureBackend backend) throws ReflectiveOperationException {
        this.backend = backend;
        windowManager = field(atmsType, "mWindowManager");
        Class<?> wms = windowManager.getType();
        globalLock = field(wms, "mGlobalLock"); root = field(wms, "mRoot");
        displayContent = method(root.getType(), "getDisplayContent", int.class);
        Class<?> display = displayContent.getReturnType();
        displayWindows = method(display, "forAllWindows", Consumer.class, boolean.class);
        if (displayWindows.getReturnType() != void.class) throw new NoSuchMethodException("capture_display_windows");
        touchableWindow = method(display, "getTouchableWinAtPointLocked", float.class, float.class);
        focusedWindow = field(display, "mCurrentFocus");
        Class<?> window = touchableWindow.getReturnType();
        ownerUid = integerField(window, "mOwnerUid"); showUser = integerField(window, "mShowUserId");
        attributes = field(window, "mAttrs"); activityRecord = field(window, "mActivityRecord");
        if (attributes.getType() != WindowManager.LayoutParams.class) throw new NoSuchFieldException("capture_attributes");
        frame = method(window, "getFrame"); parentWindow = method(window, "getParentWindow");
        visible = method(window, "isVisible"); surface = method(window, "getSurfaceControl");
        children = method(window, "forAllWindows", Consumer.class, boolean.class);
        if (frame.getReturnType() != Rect.class || visible.getReturnType() != boolean.class
                || surface.getReturnType() != backend.surfaceType() || children.getReturnType() != void.class)
            throw new NoSuchMethodException("capture_window_contract");
        Class<?> record = activityRecord.getType();
        activityComponent = field(record, "mActivityComponent"); activityUser = integerField(record, "mUserId");
        if (activityComponent.getType() != ComponentName.class) throw new NoSuchFieldException("capture_component");
        mainWindow = method(record, "findMainWindow", boolean.class);
        if (mainWindow.getReturnType() != window) throw new NoSuchMethodException("capture_main_window");
    }

    /** DOWN must hit the visible, focused, non-secure main application window in the owner user. */
    public Snapshot inspect(Object atms, int display, float x, float y) throws ReflectiveOperationException {
        if (display != 0 || !Float.isFinite(x) || !Float.isFinite(y)) throw rejected("capture_display");
        Object service = service(atms), monitor = globalLock.get(service);
        synchronized (monitor) {
            Object content = displayContent.invoke(root.get(service), display);
            Object hit = content == null ? null : touchableWindow.invoke(content, x, y);
            Object focus = content == null ? null : focusedWindow.get(content);
            if (hit == null || focus != hit) throw rejected("capture_focus");
            requireNoIme(content);
            Object record = activityRecord.get(hit);
            if (record == null || mainWindow.invoke(record, false) != hit) throw rejected("capture_main_window");
            WindowManager.LayoutParams params = params(hit);
            ComponentName component = (ComponentName) activityComponent.get(record);
            int uid = ownerUid.getInt(hit), user = activityUser.getInt(record);
            if (uid < 10000 || uid / 100000 != 0 || user != 0 || showUser.getInt(hit) != 0
                    || component == null || !HostPolicy.validPackage(component.getPackageName())
                    || component.getClassName().length() > 512
                    || !component.getClassName().matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                    || !component.getPackageName().equals(params.packageName)
                    || !normalMain(params.type) || parentWindow.invoke(hit) != null)
                throw rejected("capture_identity");
            requireVisibleSafe(hit);
            Rect bounds = checkedBounds(hit, x, y);
            Object layer = surface.invoke(hit);
            if (!backend.validSurface(layer)) throw rejected("capture_surface");
            Snapshot snapshot = new Snapshot(component.getPackageName(), component.getClassName(), uid, user,
                    display, x, y, bounds, hit, record, layer);
            // Any unknown or secure child means no capture; ordinary owned subwindows can be excluded.
            exclusions(snapshot);
            return snapshot;
        }
    }

    /** Only the captured main layer is read, with popup layers excluded and the source UID enforced. */
    public Bitmap capture(Object atms, Snapshot original) throws ReflectiveOperationException {
        if (original == null) throw rejected("capture_snapshot");
        Object[] excluded = validateWindows(atms, original);
        int width = original.expectedBounds.width(), height = original.expectedBounds.height();
        float scale = (float) Math.min(1d, Math.min((double) MAX_EDGE / Math.max(width, height),
                Math.sqrt((double) MAX_PIXELS / ((double) width * height))));
        Bitmap result = backend.capture(original.surface, excluded, original.uid, new Rect(0, 0, width, height), scale);
        try {
            if (result == null || result.isRecycled() || result.getConfig() != Bitmap.Config.ARGB_8888
                    || result.getWidth() > MAX_EDGE || result.getHeight() > MAX_EDGE
                    || (long) result.getWidth() * result.getHeight() > MAX_PIXELS)
                throw rejected("capture_buffer");
            Object[] after = validateWindows(atms, original);
            if (excluded.length != after.length) throw rejected("capture_window_changed");
            for (int i = 0; i < excluded.length; i++)
                if (excluded[i] != after[i]) throw rejected("capture_window_changed");
            return result;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (result != null && !result.isRecycled()) result.recycle();
            throw failure;
        }
    }

    /** Recheck a waiting transfer without taking another screenshot. */
    public void validate(Object atms, Snapshot original) throws ReflectiveOperationException {
        validateWindows(atms, original);
    }

    private Object[] validateWindows(Object atms, Snapshot original) throws ReflectiveOperationException {
        if (original == null || original.display != 0 || !original.bounds.equals(original.expectedBounds))
            throw rejected("capture_snapshot");
        Object service = service(atms), monitor = globalLock.get(service);
        synchronized (monitor) {
            Object content = displayContent.invoke(root.get(service), original.display);
            if (content == null) throw rejected("capture_focus");
            requireNoIme(content);
            Object hit = content == null ? null : touchableWindow.invoke(content, original.x, original.y);
            Object focus = content == null ? null : focusedWindow.get(content);
            Object window = original.window;
            WindowManager.LayoutParams params = params(window);
            ComponentName component = (ComponentName) activityComponent.get(original.record);
            if (activityRecord.get(window) != original.record || mainWindow.invoke(original.record, false) != window
                    || ownerUid.getInt(window) != original.uid || showUser.getInt(window) != original.user
                    || activityUser.getInt(original.record) != original.user || component == null
                    || !original.source.equals(component.getPackageName()) || !original.activity.equals(component.getClassName())
                    || !original.source.equals(params.packageName) || !normalMain(params.type)
                    || parentWindow.invoke(window) != null || surface.invoke(window) != original.surface
                    || !original.expectedBounds.equals(checkedBounds(window, original.x, original.y)))
                throw rejected("capture_window_changed");
            requireVisibleSafe(window);
            if (!backend.validSurface(original.surface)) throw rejected("capture_surface");
            if (focus == null || hit == null) throw rejected("capture_focus");
            if (focus != window) requireSameActivityForeground(original, focus);
            if (hit != window) requireSameActivityForeground(original, hit);
            return exclusions(original);
        }
    }

    private Object[] exclusions(Snapshot snapshot) throws ReflectiveOperationException {
        List<Object> excluded = new ArrayList<>();
        ReflectiveOperationException[] failure = new ReflectiveOperationException[1];
        RuntimeException[] rejected = new RuntimeException[1];
        children.invoke(snapshot.window, (Consumer<Object>) child -> {
            if (child == snapshot.window || failure[0] != null || rejected[0] != null) return;
            try {
                if (!Boolean.TRUE.equals(visible.invoke(child))) return;
                requirePopup(snapshot, child);
                Object layer = surface.invoke(child);
                if (!backend.validSurface(layer)) throw rejected("capture_surface");
                if (excluded.size() >= 32) throw rejected("capture_popup_limit");
                excluded.add(layer);
            } catch (ReflectiveOperationException error) { failure[0] = error; }
            catch (RuntimeException error) { rejected[0] = error; }
        }, true);
        if (failure[0] != null) throw failure[0];
        if (rejected[0] != null) throw rejected[0];
        return excluded.toArray();
    }

    /** An IME commonly leaves application focus unchanged; inspect every window on this display. */
    private void requireNoIme(Object display) throws ReflectiveOperationException {
        ReflectiveOperationException[] failure = new ReflectiveOperationException[1];
        boolean[] ime = new boolean[1];
        displayWindows.invoke(display, (Consumer<Object>) window -> {
            if (failure[0] != null || ime[0]) return;
            try {
                WindowManager.LayoutParams params = params(window);
                if ((params.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD
                        || params.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG)
                        && Boolean.TRUE.equals(visible.invoke(window))) ime[0] = true;
            } catch (ReflectiveOperationException error) { failure[0] = error; }
        }, true);
        if (failure[0] != null) throw failure[0];
        if (ime[0]) throw rejected("capture_ime");
    }

    private void requirePopup(Snapshot snapshot, Object window) throws ReflectiveOperationException {
        WindowManager.LayoutParams params = params(window);
        if (params.type < WindowManager.LayoutParams.FIRST_SUB_WINDOW
                || params.type > WindowManager.LayoutParams.LAST_SUB_WINDOW
                || ownerUid.getInt(window) != snapshot.uid || showUser.getInt(window) != snapshot.user
                || activityRecord.get(window) != snapshot.record || !snapshot.source.equals(params.packageName)
                || parentWindow.invoke(window) != snapshot.window)
            throw rejected("capture_focus");
        requireVisibleSafe(window);
    }

    private void requireSameActivityForeground(Snapshot snapshot, Object window) throws ReflectiveOperationException {
        WindowManager.LayoutParams params = params(window);
        // Ordinary Dialog windows can be siblings of the original base window. They are not
        // descendants of its capture surface, so the original main-layer capture excludes them.
        if (params.type == WindowManager.LayoutParams.TYPE_APPLICATION && parentWindow.invoke(window) == null
                && ownerUid.getInt(window) == snapshot.uid && showUser.getInt(window) == snapshot.user
                && activityRecord.get(window) == snapshot.record && snapshot.source.equals(params.packageName)) {
            requireVisibleSafe(window);
            return;
        }
        requirePopup(snapshot, window);
    }

    private void requireVisibleSafe(Object window) throws ReflectiveOperationException {
        if (!Boolean.TRUE.equals(visible.invoke(window))) throw rejected("capture_hidden");
        if ((params(window).flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) throw rejected("capture_secure");
    }

    private Rect checkedBounds(Object window, float x, float y) throws ReflectiveOperationException {
        Rect bounds = (Rect) frame.invoke(window);
        if (bounds == null || bounds.left < 0 || bounds.top < 0 || bounds.width() <= 0 || bounds.height() <= 0
                || bounds.width() > 16000 || bounds.height() > 16000
                || x < bounds.left || y < bounds.top || x >= bounds.right || y >= bounds.bottom)
            throw rejected("capture_bounds");
        return new Rect(bounds);
    }

    private WindowManager.LayoutParams params(Object window) throws IllegalAccessException {
        Object params = attributes.get(window);
        if (!(params instanceof WindowManager.LayoutParams value)) throw rejected("capture_attributes");
        return value;
    }

    private Object service(Object atms) throws IllegalAccessException {
        Object service = atms == null ? null : windowManager.get(atms);
        if (service == null || globalLock.get(service) == null || root.get(service) == null)
            throw rejected("capture_window_unavailable");
        return service;
    }

    private static boolean normalMain(int type) {
        return type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION || type == WindowManager.LayoutParams.TYPE_APPLICATION;
    }
    private static IllegalStateException rejected(String reason) { return new IllegalStateException(reason); }
    private static Field integerField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = field(type, name);
        if (field.getType() != int.class) throw new NoSuchFieldException(name);
        return field;
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Method method(Class<?> type, String name, Class<?>... arguments) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method method = current.getDeclaredMethod(name, arguments); method.setAccessible(true); return method; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    private static final class ReflectiveCapture implements CaptureBackend {
        private final Class<?> surfaceClass;
        private final java.lang.reflect.Constructor<?> builder;
        private final Method crop, scale, uid, secure, protectedContent, exclude, childrenOnly, build, capture;
        private final Method buffer, containsSecure, asBitmap, close, surfaceValid;

        static ReflectiveCapture create() throws ReflectiveOperationException {
            ReflectiveOperationException failure = null;
            for (String name : new String[]{"android.window.ScreenCaptureInternal", "android.window.ScreenCapture"}) {
                try { return new ReflectiveCapture(name); }
                catch (ReflectiveOperationException unavailable) { failure = unavailable; }
            }
            throw failure == null ? new ClassNotFoundException("capture_layers_unavailable") : failure;
        }

        ReflectiveCapture(String name) throws ReflectiveOperationException {
            Class<?> api = Class.forName(name), args = Class.forName(name + "$LayerCaptureArgs");
            Class<?> builderClass = Class.forName(name + "$LayerCaptureArgs$Builder");
            surfaceClass = Class.forName("android.view.SurfaceControl");
            surfaceValid = method(surfaceClass, "isValid");
            builder = builderClass.getDeclaredConstructor(surfaceClass); builder.setAccessible(true);
            crop = method(builderClass, "setSourceCrop", Rect.class); scale = method(builderClass, "setFrameScale", float.class);
            uid = method(builderClass, "setUid", long.class); secure = method(builderClass, "setCaptureSecureLayers", boolean.class);
            protectedContent = method(builderClass, "setAllowProtected", boolean.class);
            exclude = method(builderClass, "setExcludeLayers", Array.newInstance(surfaceClass, 0).getClass());
            childrenOnly = method(builderClass, "setChildrenOnly", boolean.class);
            build = method(builderClass, "build"); capture = method(api, "captureLayers", args);
            Class<?> shot = capture.getReturnType();
            buffer = method(shot, "getHardwareBuffer"); containsSecure = method(shot, "containsSecureLayers");
            asBitmap = method(shot, "asBitmap"); close = method(buffer.getReturnType(), "close");
            if (containsSecure.getReturnType() != boolean.class || asBitmap.getReturnType() != Bitmap.class)
                throw new NoSuchMethodException("capture_buffer_contract");
        }

        @Override public Class<?> surfaceType() { return surfaceClass; }
        @Override public boolean validSurface(Object surface) throws ReflectiveOperationException {
            return surface != null && Boolean.TRUE.equals(surfaceValid.invoke(surface));
        }

        @Override public Bitmap capture(Object surface, Object[] excluded, int sourceUid, Rect sourceCrop, float frameScale)
                throws ReflectiveOperationException {
            Object arguments = builder.newInstance(surface);
            childrenOnly.invoke(arguments, false);
            crop.invoke(arguments, sourceCrop); scale.invoke(arguments, frameScale); uid.invoke(arguments, (long) sourceUid);
            secure.invoke(arguments, false); protectedContent.invoke(arguments, false);
            Object layers = Array.newInstance(surfaceClass, excluded.length);
            for (int i = 0; i < excluded.length; i++) Array.set(layers, i, excluded[i]);
            exclude.invoke(arguments, layers);
            Object shot = capture.invoke(null, build.invoke(arguments));
            if (shot == null) throw rejected("capture_unavailable");
            Object hardware = buffer.invoke(shot);
            Bitmap wrapped = null, copy = null;
            try {
                if (hardware == null || Boolean.TRUE.equals(containsSecure.invoke(shot))) throw rejected("capture_secure_buffer");
                wrapped = (Bitmap) asBitmap.invoke(shot);
                if (wrapped == null || wrapped.isRecycled() || wrapped.getWidth() > MAX_EDGE
                        || wrapped.getHeight() > MAX_EDGE || (long) wrapped.getWidth() * wrapped.getHeight() > MAX_PIXELS)
                    throw rejected("capture_buffer");
                copy = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                if (copy == null) throw rejected("capture_buffer");
                return copy;
            } finally {
                if (wrapped != null && !wrapped.isRecycled()) wrapped.recycle();
                if (hardware != null) {
                    try { close.invoke(hardware); }
                    catch (ReflectiveOperationException | RuntimeException failure) {
                        if (copy != null && !copy.isRecycled()) copy.recycle();
                        throw failure;
                    }
                }
            }
        }
    }
}
