package io.github.yudigaga.taplusbridge.probe;

import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

/** Stops owned web launches at the public ATMS boundary before an Activity can be created. */
public final class ScopeLinkLaunchHooks {
    private ScopeLinkLaunchHooks() {}
    @FunctionalInterface
    public interface HostLaunch {
        boolean intercept(int uid, String source, int user, Intent intent, boolean forResult);
    }

    // These callbacks run in system_server; application target-SDK hidden API limits do not apply.
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    public static int install(XposedModule module, Class<?> atms, Supplier<ScopeLinkProbe> probe, HostLaunch host) {
        int count = 0;
        for (Method entry : atms.getDeclaredMethods()) {
            String name = entry.getName();
            if (!Modifier.isPublic(entry.getModifiers()) || Modifier.isStatic(entry.getModifiers())
                    || Modifier.isSynchronized(entry.getModifiers()) || entry.getReturnType() != int.class
                    || !(name.equals("startActivity") || name.equals("startActivityAsUser")
                    || name.equals("startActivityWithFeature") || name.equals("startActivityAsUserWithFeature")
                    || name.equals("startActivities") || name.equals("startActivitiesWithFeature"))) continue;
            Class<?>[] types = entry.getParameterTypes();
            int intent = -1, caller = -1, resultTo = -1, requestCode = -1, profile = -1, user = -1;
            boolean array = false;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == String.class && caller < 0) caller = i;
                if (types[i] == Intent.class || types[i] == Intent[].class) { intent = i; array = types[i] == Intent[].class; }
                if (types[i] == IBinder.class && resultTo < 0) resultTo = i;
                if (types[i].getName().equals("android.app.ProfilerInfo")) profile = i;
            }
            if (intent < 0 || caller < 0) continue;
            if (resultTo >= 0 && resultTo + 2 < types.length && types[resultTo + 1] == String.class
                    && types[resultTo + 2] == int.class) requestCode = resultTo + 2;
            if (types[types.length - 1] == int.class
                    && (name.contains("AsUser") || name.startsWith("startActivities"))) user = types.length - 1;
            final int intentAt = intent, callerAt = caller, resultAt = resultTo, requestAt = requestCode,
                    profileAt = profile, userAt = user;
            final boolean multiple = array;
            entry.setAccessible(true);
            module.hook(entry).setId("taplusbridge:scope-link:" + name + ":" + types.length)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        ScopeLinkProbe active = probe.get();
                        int uid = Binder.getCallingUid(), requestedUser = userAt < 0 ? uid / 100000 : (Integer) chain.getArg(userAt);
                        if (requestedUser == -2 || requestedUser == -3) requestedUser = uid / 100000;
                        Intent candidate = null;
                        boolean ordinary = profileAt < 0 || chain.getArg(profileAt) == null;
                        boolean forResult = resultAt >= 0 && requestAt >= 0 && chain.getArg(resultAt) != null
                                && (Integer) chain.getArg(requestAt) >= 0;
                        if (forResult) ordinary = false;
                        Object input = chain.getArg(intentAt);
                        if (multiple) {
                            Intent[] inputs = input instanceof Intent[] values ? values : null;
                            if (inputs != null && inputs.length == 1) candidate = inputs[0];
                            else ordinary = false;
                        } else if (input instanceof Intent value) candidate = value;
                        boolean consumed;
                        long identity = Binder.clearCallingIdentity();
                        try {
                            String source = (String) chain.getArg(callerAt);
                            consumed = host.intercept(uid, source, requestedUser, candidate, forResult)
                                    || active != null && active.intercept(uid, source, requestedUser, candidate, ordinary);
                        }
                        finally { Binder.restoreCallingIdentity(identity); }
                        if (!consumed) return chain.proceed();
                        // No original ATMS body or browser Activity runs for this owned request.
                        for (int i = 0; i < types.length; i++) if (types[i] == Bundle.class && chain.getArg(i) != null) {
                            try {
                                Class<?> options = Class.forName("android.app.ActivityOptions");
                                Method from = options.getDeclaredMethod("fromBundle", Bundle.class);
                                Method abort = options.getDeclaredMethod("abort");
                                from.setAccessible(true); abort.setAccessible(true);
                                Object decoded = from.invoke(null, chain.getArg(i));
                                if (decoded != null) abort.invoke(decoded);
                            } catch (ReflectiveOperationException | RuntimeException limited) {
                                module.log(5, "TaplusBridge", "Scope link options cancellation limited");
                            }
                        }
                        return 0;
                    });
            count++;
        }
        module.log(count > 0 ? 4 : 5, "TaplusBridge", "Scope link launch hooks installed; count=" + count);
        return count;
    }
}
