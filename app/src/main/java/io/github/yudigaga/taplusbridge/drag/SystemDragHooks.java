package io.github.yudigaga.taplusbridge.drag;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.BuildConfig;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Observes successful global drags; ClipData stays in the platform drag-and-drop delivery path. */
public final class SystemDragHooks {
    private static final String TAG = "TaplusBridge";
    private static final long MAX_START_AGE = 2_000;
    // The platform gives this hidden flag precedence over GLOBAL for cross-UID delivery.
    private static final int GLOBAL_SAME_APPLICATION = 1 << 12;
    private final XposedModule module;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Field service, context, lock, dragState, token, ownerUid, inProgress;
    private final Method startAsUser;
    private final StateFields stateFields;
    private final UserHandle owner;
    private volatile Object ownedState;

    private SystemDragHooks(XposedModule module, Class<?> controller) throws ReflectiveOperationException {
        this.module = module;
        service = field(controller, "mService");
        context = field(service.getType(), "mContext");
        if (!Context.class.isAssignableFrom(context.getType())) throw new ContractFailure("type", "mContext", null);
        lock = field(service.getType(), "mGlobalLock");
        dragState = field(controller, "mDragState");
        stateFields = StateFields.resolve(dragState.getType());
        token = stateFields.token; ownerUid = stateFields.uid; inProgress = stateFields.inProgress;
        startAsUser = method(Context.class, "startActivityAsUser", Intent.class, UserHandle.class);
        try { owner = (UserHandle) method(UserHandle.class, "of", int.class).invoke(null, 0); }
        catch (ReflectiveOperationException unavailable) { throw new ContractFailure("user", "of", unavailable); }
    }

    public static void install(XposedModule module, ClassLoader loader) {
        String stage = "class", member = "DragDropController";
        try {
            Class<?> controller = loader.loadClass("com.android.server.wm.DragDropController");
            SystemDragHooks hooks = new SystemDragHooks(module, controller);
            java.util.ArrayList<Method> starts = new java.util.ArrayList<>();
            for (Method target : controller.getDeclaredMethods()) if (Signature.matches(target)) starts.add(target);
            if (starts.isEmpty()) throw new ContractFailure("signature", "performDrag", null);
            Method close = method(hooks.dragState.getType(), "closeLocked");
            if (Modifier.isStatic(close.getModifiers()) || close.getReturnType() != void.class)
                throw new ContractFailure("type", "closeLocked", null);
            stage = "hook"; member = "closeLocked";
            module.hook(close).setId("taplusbridge:global-drag-end")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (hooks.ownedState == chain.getThisObject()) {
                            hooks.ownedState = null; DragGestureGate.end();
                        }
                        return chain.proceed();
                    });
            int installed = 0;
            for (Method target : starts) {
                member = "performDrag";
                module.hook(target).setId("taplusbridge:global-drag-" + target.getParameterCount())
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                boolean global = chain.getArg(3) instanceof Integer dragFlags
                                        && (dragFlags & View.DRAG_FLAG_GLOBAL) != 0;
                                module.log(4, TAG, "drag stage=observed global=" + global + " accepted=" + (result instanceof IBinder));
                                if (result instanceof IBinder accepted && chain.getArg(1) instanceof Integer uid
                                        && chain.getArg(3) instanceof Integer flags
                                        && eligible(uid, flags)) {
                                    if (!(chain.getArg(target.getParameterCount() - 1) instanceof ClipData data)
                                            || !DragPayload.supports(data.getDescription())) {
                                        hooks.reject("unsupported_content"); return result;
                                    }
                                    Object instance = chain.getThisObject();
                                    long began = SystemClock.elapsedRealtime();
                                    hooks.main.post(() -> hooks.launch(instance, accepted, uid, began));
                                } else if (global && result instanceof IBinder) hooks.reject("source_not_cross_app");
                            } catch (RuntimeException failure) { hooks.report("observe", failure); }
                            return result;
                        });
                installed++;
            }
            module.log(4, TAG, "drag stage=installed signatures=" + installed + " display_layout=" + hooks.stateFields.layout);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (failure instanceof ContractFailure contract) { stage = contract.stage; member = contract.member; }
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            module.log(5, TAG, "drag stage=unavailable contract_stage=" + stage + " member=" + member
                    + " exception=" + cause.getClass().getSimpleName());
        }
    }

    private void launch(Object controller, IBinder accepted, int uid, long began) {
        long identity = Binder.clearCallingIdentity();
        try {
            if (!fresh(began, SystemClock.elapsedRealtime())) { reject("start_expired"); return; }
            RuntimeSnapshot config = RuntimeSnapshot.parse(module.getRemotePreferences(RuntimeSnapshot.GROUP)
                    .getString(RuntimeSnapshot.KEY, null));
            if (!config.enabled || !config.drag.enabled) { reject("config_disabled"); return; }
            if (config.user != 0) { reject("non_main_user"); return; }
            if (config.build != BuildConfig.VERSION_CODE) { reject("build_mismatch"); return; }
            if (io.github.yudigaga.taplusbridge.config.DragSettings.DIRECTIONS.stream()
                    .allMatch(direction -> "none".equals(config.drag.action(direction)))) { reject("no_actions"); return; }
            Object manager = service.get(controller);
            if (manager == null) { reject("no_service"); return; }
            Context system = (Context) context.get(manager);
            Object monitor = lock.get(manager);
            if (system == null || monitor == null) { reject("no_context"); return; }
            synchronized (monitor) {
                Object active = dragState.get(controller);
                if (!validState(active, accepted, uid)) return;
            }
            KeyguardManager keyguard = system.getSystemService(KeyguardManager.class);
            PowerManager power = system.getSystemService(PowerManager.class);
            if (keyguard == null || keyguard.isKeyguardLocked() || keyguard.isDeviceLocked()
                    || power == null || !power.isInteractive()) { reject("locked"); return; }
            String source = uniqueSource(system.getPackageManager().getPackagesForUid(uid));
            if (source == null || !config.sourceScope.allows(source)) { reject("source_not_allowed"); return; }
            ApplicationInfo info = system.getPackageManager().getApplicationInfo(source, 0);
            if (info.uid != uid) { reject("source_changed"); return; }
            if (!fresh(began, SystemClock.elapsedRealtime())) { reject("start_expired"); return; }
            ApplicationInfo app = system.getPackageManager().getApplicationInfo(HostPolicy.MODULE_PACKAGE,
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            if (!app.enabled || (app.flags & ApplicationInfo.FLAG_INSTALLED) == 0
                    || (app.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) { reject("module_disabled"); return; }
            var component = system.getPackageManager().getActivityInfo(entry(source, began).getComponent(),
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            if (!component.enabled) { reject("module_disabled"); return; }
            synchronized (monitor) {
                Object active = dragState.get(controller);
                if (!validState(active, accepted, uid)) return;
                ownedState = active; DragGestureGate.begin();
            }
            Intent intent = entry(source, began);
            startAsUser.invoke(system, intent, owner);
            module.log(4, TAG, "drag stage=shown display=0");
        } catch (ReflectiveOperationException | android.content.pm.PackageManager.NameNotFoundException | RuntimeException failure) {
            if (ownedState != null) { ownedState = null; DragGestureGate.end(); }
            report("show", failure);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private boolean validState(Object active, IBinder accepted, int uid) throws ReflectiveOperationException {
        if (active == null) { reject("no_state"); return false; }
        if (!inProgress.getBoolean(active)) { reject("state_ended"); return false; }
        if (!accepted.equals(token.get(active))) { reject("token_changed"); return false; }
        if (ownerUid.getInt(active) != uid) { reject("source_changed"); return false; }
        if (!stateFields.mainDisplay(active)) { reject("non_main_display"); return false; }
        return true;
    }

    private void reject(String reason) { module.log(4, TAG, "drag stage=rejected reason=" + reason); }

    static boolean eligible(int uid, int flags) {
        return uid >= 10_000 && uid < 100_000 && (flags & View.DRAG_FLAG_GLOBAL) != 0
                && (flags & GLOBAL_SAME_APPLICATION) == 0;
    }

    static String uniqueSource(String[] packages) {
        return packages != null && packages.length == 1 && HostPolicy.validTarget(packages[0]) ? packages[0] : null;
    }

    static boolean fresh(long began, long now) { return began >= 0 && now >= began && now - began <= MAX_START_AGE; }

    static Intent entry(String source, long began) {
        if (!HostPolicy.validTarget(source) || began < 0) throw new IllegalArgumentException("drag_entry");
        return new Intent().setComponent(new ComponentName(HostPolicy.MODULE_PACKAGE,
                HostPolicy.MODULE_PACKAGE + ".drag.DragActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra("source", source).putExtra("started_elapsed", began).putExtra("display", 0);
    }

    private void report(String stage, Exception failure) {
        module.log(5, TAG, "drag stage=" + stage + " exception=" + failure.getClass().getSimpleName());
    }

    private static Field field(Class<?> type, String name) throws ContractFailure {
        Field result = optionalField(type, name);
        if (result == null) throw new ContractFailure("field", name, new NoSuchFieldException(name));
        if (Modifier.isStatic(result.getModifiers())) throw new ContractFailure("type", name, null);
        return result;
    }

    private static Field optionalField(Class<?> type, String name) throws ContractFailure {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field result = current.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException ignored) { }
            catch (RuntimeException denied) { throw new ContractFailure("field", name, denied); }
        }
        return null;
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws ContractFailure {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method result = current.getDeclaredMethod(name, arguments); result.setAccessible(true); return result; }
            catch (NoSuchMethodException ignored) { }
            catch (RuntimeException denied) { throw new ContractFailure("method", name, denied); }
        }
        throw new ContractFailure("method", name, new NoSuchMethodException(name));
    }

    static final class ContractFailure extends ReflectiveOperationException {
        final String stage, member;
        ContractFailure(String stage, String member, Throwable cause) {
            super("drag_contract:" + stage + ":" + member, cause); this.stage = stage; this.member = member;
        }
    }

    // AOSP android-16.0.0_r1, frameworks/base commit 99b01a65cc4c104933788b3143285ab6bae65827:
    // https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/services/core/java/com/android/server/wm/DragState.java
    static final class StateFields {
        final Field token, uid, inProgress, current, start;
        final Method displayId;
        final String layout;

        private StateFields(Class<?> state) throws ReflectiveOperationException {
            token = field(state, "mToken"); uid = field(state, "mUid"); inProgress = field(state, "mDragInProgress");
            if (!IBinder.class.isAssignableFrom(token.getType())) throw new ContractFailure("type", "mToken", null);
            if (uid.getType() != int.class) throw new ContractFailure("type", "mUid", null);
            if (inProgress.getType() != boolean.class) throw new ContractFailure("type", "mDragInProgress", null);
            boolean split = optionalField(state, "mCurrentDisplayContent") != null;
            current = field(state, split ? "mCurrentDisplayContent" : "mDisplayContent");
            start = split ? field(state, "mStartDragDisplayContent") : optionalField(state, "mStartDragDisplayContent");
            if (start != null && (Modifier.isStatic(start.getModifiers()) || start.getType() != current.getType()))
                throw new ContractFailure("type", "mStartDragDisplayContent", null);
            displayId = method(current.getType(), "getDisplayId");
            if (Modifier.isStatic(displayId.getModifiers()) || displayId.getReturnType() != int.class)
                throw new ContractFailure("type", "getDisplayId", null);
            layout = split ? "split" : "legacy";
        }

        static StateFields resolve(Class<?> state) throws ReflectiveOperationException { return new StateFields(state); }

        boolean mainDisplay(Object state) throws ReflectiveOperationException {
            Object active = current.get(state), origin = start == null ? active : start.get(state);
            return active != null && origin != null && (Integer) displayId.invoke(active) == 0
                    && (Integer) displayId.invoke(origin) == 0;
        }
    }

    /** Known 13/14 and 15/16 layouts, with an optional vendor device id before the four coordinates. */
    static final class Signature {
        private Signature() { }

        static boolean matches(Method target) {
            if (target == null) return false;
            Class<?>[] types = target.getParameterTypes(); String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) names[i] = types[i].getName();
            return matches(target.getName(), target.getModifiers(), target.getReturnType().getName(), names);
        }

        static boolean matches(String name, int modifiers, String result, String... types) {
            if (!"performDrag".equals(name) || Modifier.isStatic(modifiers) || Modifier.isAbstract(modifiers)
                    || !"android.os.IBinder".equals(result) || types == null || types.length < 11 || types.length > 13) return false;
            String[] prefix = {"int", "int", "android.view.IWindow", "int", "android.view.SurfaceControl", "int"};
            for (int i = 0; i < prefix.length; i++) if (!prefix[i].equals(types[i])) return false;
            int coordinates = types.length - 5;
            for (int i = 6; i < coordinates; i++) if (!"int".equals(types[i])) return false;
            for (int i = coordinates; i < coordinates + 4; i++) if (!"float".equals(types[i])) return false;
            return "android.content.ClipData".equals(types[types.length - 1]);
        }
    }
}
