package io.github.yudigaga.taplusbridge.hook;

import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import java.util.function.Supplier;

/** Reuses one live read only inside the current presentation, including nested invocations. */
final class PresentationPolicy {
    private static final class Invocation {
        SourceScopePolicy policy;
        RuntimeException failure;
        boolean read, reached;
    }
    private final ThreadLocal<Invocation> active = new ThreadLocal<>();
    AutoCloseable begin() {
        Invocation previous = active.get(); active.set(new Invocation());
        return () -> { if (previous == null) active.remove(); else active.set(previous); };
    }
    void gateReached() { Invocation call = active.get(); if (call != null) call.reached = true; }
    boolean reached() { Invocation call = active.get(); return call != null && call.reached; }
    SourceScopePolicy read(Supplier<SourceScopePolicy> source) {
        Invocation call = active.get();
        if (call == null) return source.get();
        if (!call.read) {
            call.read = true;
            try { call.policy = source.get(); } catch (RuntimeException failure) { call.failure = failure; }
        }
        if (call.failure != null) throw call.failure;
        return call.policy;
    }
}
