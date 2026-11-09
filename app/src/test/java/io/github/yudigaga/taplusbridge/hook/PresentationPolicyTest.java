package io.github.yudigaga.taplusbridge.hook;

import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PresentationPolicyTest {
    @Test public void onePresentationReadsPolicyOnceAndTheNextInvocationRefreshes() throws Exception {
        Class<?> type;
        try { type=Class.forName("io.github.yudigaga.taplusbridge.hook.PresentationPolicy"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("presentation gate and diagnostic need one invocation policy", missing); }
        var constructor=type.getDeclaredConstructor(); constructor.setAccessible(true); Object policies=constructor.newInstance();
        var begin=type.getDeclaredMethod("begin"); begin.setAccessible(true);
        var read=type.getDeclaredMethod("read",Supplier.class); read.setAccessible(true);
        var reads=new AtomicInteger();
        Supplier<SourceScopePolicy> source=()->{reads.incrementAndGet();return SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of());};
        for(int invocation=1;invocation<=2;invocation++) {
            try(AutoCloseable scope=(AutoCloseable)begin.invoke(policies)) {
                Object first=read.invoke(policies,source); assertSame(first,read.invoke(policies,source));
                assertEquals(invocation,reads.get());
            }
        }
        // Cleanup is required even when presentation fails.
        try(AutoCloseable scope=(AutoCloseable)begin.invoke(policies)) { read.invoke(policies,source); }
        read.invoke(policies,source); assertEquals(4,reads.get());
    }
}
