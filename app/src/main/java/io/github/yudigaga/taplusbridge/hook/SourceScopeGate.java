package io.github.yudigaga.taplusbridge.hook;

import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;

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
        if (!policy.managed) throw new IllegalArgumentException("scope_unmanaged");
        return policy.allows(source) ? "normal" : "forbidden";
    }
}
