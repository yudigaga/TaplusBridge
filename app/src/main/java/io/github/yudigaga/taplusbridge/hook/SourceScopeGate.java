package io.github.yudigaga.taplusbridge.hook;

import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import java.util.function.BooleanSupplier;

/** The exact source-app presentation decision shared by the host hook and its regression tests. */
public final class SourceScopeGate {
    @FunctionalInterface public interface Original { String get() throws ReflectiveOperationException; }
    private SourceScopeGate() {}

    public static String presentation(SourceScopePolicy policy, String source, Original original)
            throws ReflectiveOperationException {
        if (!policy.managed) return original.get();
        return managedPresentation(policy, source);
    }

    public static String managedPresentation(SourceScopePolicy policy, String source) {
        return managedPresentation(policy, source, false);
    }

    public static String managedPresentation(SourceScopePolicy policy, String source, boolean protectedInteraction) {
        return managedPresentation(policy, source, () -> protectedInteraction);
    }

    public static String managedPresentation(SourceScopePolicy policy, String source,
                                             BooleanSupplier protectedInteraction) {
        if (!policy.managed) throw new IllegalArgumentException("scope_unmanaged");
        if (!policy.allows(source)) return "forbidden";
        return protectedInteraction != null && protectedInteraction.getAsBoolean() ? "forbidden" : "normal";
    }
}
