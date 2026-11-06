package io.github.yudigaga.taplusbridge.probe;

import android.content.ComponentName;
import android.graphics.Rect;
import android.view.WindowManager;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Local WMS-only hit testing. No Binder call or event injection occurs while holding its lock. */
public final class SourceWindowAccess {
    public static final class Rejected extends IllegalStateException {
        private Rejected() { super("window_occluded"); }
    }
    public static final class Unavailable extends IllegalStateException {
        private Unavailable() { super("window_unavailable"); }
    }
    private final Field windowManager, globalLock, root;
    private final Method displayContent, touchableWindow, frame, mainWindow, parentWindow, visible;
    private final Field focusedWindow;
    private final Field ownerUid, showUser, attributes, activityRecord, activityComponent, activityUser;

    public SourceWindowAccess(Class<?> atmsType) throws ReflectiveOperationException {
        windowManager = field(atmsType, "mWindowManager");
        Class<?> wms = windowManager.getType();
        globalLock = field(wms, "mGlobalLock"); root = field(wms, "mRoot");
        displayContent = method(root.getType(), "getDisplayContent", int.class);
        touchableWindow = method(displayContent.getReturnType(), "getTouchableWinAtPointLocked", float.class, float.class);
        focusedWindow = field(displayContent.getReturnType(), "mCurrentFocus");
        Class<?> window = touchableWindow.getReturnType();
        ownerUid = integerField(window, "mOwnerUid"); showUser = integerField(window, "mShowUserId");
        attributes = field(window, "mAttrs"); activityRecord = field(window, "mActivityRecord");
        if (attributes.getType() != WindowManager.LayoutParams.class) throw new NoSuchFieldException("window_attributes");
        frame = method(window, "getFrame");
        parentWindow = method(window, "getParentWindow"); visible = method(window, "isVisible");
        if (frame.getReturnType() != Rect.class) throw new NoSuchMethodException("window_frame");
        Class<?> activity = activityRecord.getType();
        activityComponent = field(activity, "mActivityComponent"); activityUser = integerField(activity, "mUserId");
        if (activityComponent.getType() != ComponentName.class) throw new NoSuchFieldException("activity_component");
        mainWindow = method(activity, "findMainWindow", boolean.class);
        if (mainWindow.getReturnType() != window) throw new NoSuchMethodException("main_window");
    }

    public void available(Object atms) throws ReflectiveOperationException {
        Object service = atms == null ? null : windowManager.get(atms);
        if (service == null || globalLock.get(service) == null || root.get(service) == null)
            throw new Unavailable();
    }

    /** Rejects same-UID popup menus, dialogs, starting windows and an obscuring different app. */
    public void requireTarget(Object atms, String source, String activity, int uid, int user,
                              int display, float x, float y) throws ReflectiveOperationException {
        if (inspect(atms, source, activity, uid, user, display, x, y) != ProbeClickPlan.Window.MAIN)
            throw new Rejected();
    }

    /** Where a card tap may go. A covering menu is dismissed somewhere else first; the card point is not clicked while covered. */
    public static final class CardPlan {
        private final boolean dismiss, popupCovered;
        private final float dismissX, dismissY;
        private CardPlan(boolean dismiss, boolean popupCovered, float dismissX, float dismissY) {
            this.dismiss = dismiss; this.popupCovered = popupCovered;
            this.dismissX = dismissX; this.dismissY = dismissY;
        }
        public boolean dismiss() { return dismiss; }
        public boolean popupCovered() { return popupCovered; }
        public float dismissX() { return dismissX; }
        public float dismissY() { return dismissY; }
        static CardPlan tap() { return new CardPlan(false, false, 0, 0); }
        static CardPlan dismiss(float x, float y, boolean popupCovered) { return new CardPlan(true, popupCovered, x, y); }
    }

    public CardPlan planCardTap(Object atms, String source, String activity, int uid, int user,
                                int display, float x, float y, int hostUid) throws ReflectiveOperationException {
        available(atms);
        Object service = windowManager.get(atms), monitor = globalLock.get(service);
        Object window, focus;
        synchronized (monitor) {
            Object content = displayContent.invoke(root.get(service), display);
            window = content == null ? null : touchableWindow.invoke(content, x, y);
            focus = content == null ? null : focusedWindow.get(content);
            if (window == null) throw new Rejected();
            if (isHostOverlay(window, hostUid) || (focus != null && isHostOverlay(focus, hostUid)
                    && (window == focus || sourceMain(window, source, activity, uid, user, x, y))))
                return CardPlan.tap();
            if (coveringMenu(window, focus, source, activity, uid, user, x, y)) {
                return CardPlan.dismiss(x, y, true);
            }
        }
        ProbeClickPlan.Window kind = inspect(atms, source, activity, uid, user, display, x, y);
        if (kind == ProbeClickPlan.Window.MAIN) return CardPlan.tap();
        if (kind == ProbeClickPlan.Window.OUTSIDE_POPUP) return CardPlan.dismiss(x, y, false);
        throw new Rejected();
    }

    private boolean isHostOverlay(Object window, int hostUid) throws ReflectiveOperationException {
        if (hostUid < 10000 || hostUid >= 100000) return false;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) attributes.get(window);
        return params != null && ownerUid.getInt(window) == hostUid && showUser.getInt(window) == 0
                && HostPolicy.HOST_PACKAGE.equals(params.packageName)
                && (params.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY || params.type == 2002)
                && Boolean.TRUE.equals(visible.invoke(window))
                && (params.flags & WindowManager.LayoutParams.FLAG_SECURE) == 0;
    }

    private boolean sourceMain(Object window, String source, String activity, int uid, int user, float x, float y)
            throws ReflectiveOperationException {
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) attributes.get(window);
        Object record = activityRecord.get(window);
        ComponentName component = record == null ? null : (ComponentName) activityComponent.get(record);
        Rect bounds = (Rect) frame.invoke(window);
        return params != null && record != null && component != null && bounds != null
                && ownerUid.getInt(window) == uid && showUser.getInt(window) == user
                && (params.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                || params.type == WindowManager.LayoutParams.TYPE_APPLICATION)
                && source.equals(params.packageName) && source.equals(component.getPackageName())
                && activity.equals(component.getClassName()) && activityUser.getInt(record) == user
                && mainWindow.invoke(record, false) == window && Boolean.TRUE.equals(visible.invoke(window))
                && bounds.contains((int) x, (int) y);
    }

    private boolean coveringMenu(Object window, Object focus, String source, String activity, int uid, int user,
                                 float x, float y) throws ReflectiveOperationException {
        if (focus != window) return false;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) attributes.get(window);
        if (params == null || params.type < 1000 || params.type > 1999
                || (params.flags & (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_SECURE)) != 0) return false;
        Object record = activityRecord.get(window);
        ComponentName component = record == null ? null : (ComponentName) activityComponent.get(record);
        Object main = record == null ? null : mainWindow.invoke(record, false);
        Rect popupBounds = (Rect) frame.invoke(window);
        Rect mainBounds = main == null ? null : (Rect) frame.invoke(main);
        Object parent = parentWindow.invoke(window);
        return ownerUid.getInt(window) == uid && showUser.getInt(window) == user
                && source.equals(params.packageName) && component != null
                && source.equals(component.getPackageName()) && activity.equals(component.getClassName())
                && activityUser.getInt(record) == user && main != null && ownerUid.getInt(main) == uid
                && (parent == main || parent == null) && popupBounds != null && mainBounds != null
                && popupBounds.contains((int) x, (int) y) && mainBounds.contains((int) x, (int) y)
                && Boolean.TRUE.equals(visible.invoke(window)) && Boolean.TRUE.equals(visible.invoke(main));
    }

    public ProbeClickPlan.Window inspect(Object atms, String source, String activity, int uid, int user,
                                         int display, float x, float y) throws ReflectiveOperationException {
        available(atms);
        Object service = windowManager.get(atms), monitor = globalLock.get(service);
        synchronized (monitor) {
            Object content = displayContent.invoke(root.get(service), display);
            Object window = content == null ? null : touchableWindow.invoke(content, x, y);
            if (window == null) throw new Rejected();
            Object focus = focusedWindow.get(content);
            if (focus != null && Boolean.TRUE.equals(visible.invoke(focus))) {
                WindowManager.LayoutParams focusedParams = (WindowManager.LayoutParams) attributes.get(focus);
                if (focusedParams != null && focusedParams.type >= 1000 && focusedParams.type <= 1999) {
                    Object popupRecord = activityRecord.get(focus);
                    ComponentName popupComponent = popupRecord == null ? null : (ComponentName) activityComponent.get(popupRecord);
                    Object main = popupRecord == null ? null : mainWindow.invoke(popupRecord, false);
                    Rect popupBounds = (Rect) frame.invoke(focus), mainBounds = main == null ? null : (Rect) frame.invoke(main);
                    if (ownerUid.getInt(focus) != uid || showUser.getInt(focus) != user
                            || !source.equals(focusedParams.packageName) || popupComponent == null
                            || !source.equals(popupComponent.getPackageName()) || !activity.equals(popupComponent.getClassName())
                            || activityUser.getInt(popupRecord) != user || main == null || parentWindow.invoke(focus) != main
                            || ownerUid.getInt(main) != uid || showUser.getInt(main) != user
                            || popupBounds == null || popupBounds.isEmpty() || popupBounds.contains((int) x, (int) y)
                            || mainBounds == null || !mainBounds.contains((int) x, (int) y)
                            || (focusedParams.flags & (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)) != 0
                            || (window != focus && window != main))
                        throw new Rejected();
                    return ProbeClickPlan.Window.OUTSIDE_POPUP;
                }
            }
            Object record = activityRecord.get(window);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) attributes.get(window);
            ComponentName component = record == null ? null : (ComponentName) activityComponent.get(record);
            Rect bounds = (Rect) frame.invoke(window);
            if (ownerUid.getInt(window) != uid || showUser.getInt(window) != user || params == null
                    || !(params.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                    || params.type == WindowManager.LayoutParams.TYPE_APPLICATION)
                    || !source.equals(params.packageName) || component == null
                    || !source.equals(component.getPackageName()) || !activity.equals(component.getClassName())
                    || activityUser.getInt(record) != user || mainWindow.invoke(record, false) != window
                    || focus != window || !Boolean.TRUE.equals(visible.invoke(focus))
                    || bounds == null || !bounds.contains((int) x, (int) y))
                throw new Rejected();
            return ProbeClickPlan.Window.MAIN;
        }
    }
    private static Field integerField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = field(type, name);
        if (field.getType() != int.class) throw new NoSuchFieldException(name);
        return field;
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) try {
            Field field = current.getDeclaredField(name); field.setAccessible(true); return field;
        } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static Method method(Class<?> type, String name, Class<?>... arguments) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) try {
            Method method = current.getDeclaredMethod(name, arguments); method.setAccessible(true); return method;
        } catch (NoSuchMethodException ignored) { }
        throw new NoSuchMethodException(name);
    }
}
