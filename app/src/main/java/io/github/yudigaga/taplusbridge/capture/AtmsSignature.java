package io.github.yudigaga.taplusbridge.capture;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Exact public ATMS entry point; overload changes disable observation rather than guessing offsets. */
public final class AtmsSignature {
    private static final String[] PARAMETERS = {
            "android.app.IApplicationThread", "java.lang.String", "java.lang.String",
            "android.content.Intent", "java.lang.String", "android.os.IBinder", "java.lang.String",
            "int", "int", "android.app.ProfilerInfo", "android.os.Bundle", "int"
    };

    private AtmsSignature() { }

    public static boolean matches(Method method) {
        if (method == null) return false;
        Class<?>[] types = method.getParameterTypes();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) names[i] = types[i].getName();
        return matches(method.getName(), method.getModifiers(), method.getReturnType().getName(), names);
    }

    public static boolean matches(String methodName, int modifiers, String returnType, String... parameterTypes) {
        if (!"startActivityAsUser".equals(methodName) || !Modifier.isPublic(modifiers)
                || Modifier.isStatic(modifiers) || !"int".equals(returnType)
                || parameterTypes == null || parameterTypes.length != PARAMETERS.length) return false;
        for (int i = 0; i < PARAMETERS.length; i++) {
            if (!PARAMETERS[i].equals(parameterTypes[i])) return false;
        }
        return true;
    }
}
