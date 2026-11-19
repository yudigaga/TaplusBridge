package io.github.yudigaga.taplusbridge.hook;

import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SourceScopeGateTest {
    @Test public void oneBlockedAppDoesNotKeepHostRestrictionsForOtherApps() throws Exception {
        var scope = SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, Set.of("com.example.blocked"), Set.of());
        AtomicInteger oldFilterCalls = new AtomicInteger();
        assertEquals("normal", SourceScopeGate.presentation(scope, "com.example.allowed", () -> {
            oldFilterCalls.incrementAndGet(); return "forbidden";
        }));
        assertEquals(0, oldFilterCalls.get());
    }

    @Test public void selectedWhitelistAppIsNotVetoedByTheOldHostPageList() throws Exception {
        var scope = SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST, Set.of(), Set.of("com.example.allowed"));
        assertEquals("normal", SourceScopeGate.presentation(scope, "com.example.allowed", () -> "forbidden"));
        assertEquals("forbidden", SourceScopeGate.presentation(scope, "com.example.other", () -> "normal"));
    }

    @Test public void excludedAppsAndInvalidSourcesCannotPassTheScopeGate() throws Exception {
        var scope = SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, Set.of("com.example.blocked"), Set.of());
        assertEquals("forbidden", SourceScopeGate.presentation(scope, "com.example.blocked", () -> { throw new AssertionError(); }));
        assertEquals("forbidden", SourceScopeGate.presentation(scope, null, () -> { throw new AssertionError(); }));
    }

    @Test public void unmanagedPolicyKeepsTheExactOriginalDecisionAndExceptions() throws Exception {
        assertEquals("forbidden", SourceScopeGate.presentation(SourceScopePolicy.unmanaged(), "com.example.any", () -> "forbidden"));
        assertThrows(ReflectiveOperationException.class, () -> SourceScopeGate.presentation(
                SourceScopePolicy.unmanaged(), "com.example.any", () -> { throw new ReflectiveOperationException(); }));
    }

    @Test public void anExactProtectedInteractionAddsOnlyAnAllowlistPreservingVeto() {
        var scope = SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST, Set.of(),
                Set.of("com.example.allowed"));
        assertEquals("forbidden", SourceScopeGate.managedPresentation(scope, "com.example.allowed", true));
        assertEquals("forbidden", SourceScopeGate.managedPresentation(scope, "com.example.other", true));
    }

    @Test public void unknownInteractionEvidenceLeavesTheExistingManagedDecisionUnchanged() {
        var scope = SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST, Set.of(),
                Set.of("com.example.allowed"));
        assertEquals("normal", SourceScopeGate.managedPresentation(scope, "com.example.allowed", false));
        assertEquals("forbidden", SourceScopeGate.managedPresentation(scope, "com.example.other", false));
    }

    @Test public void aBlockedSourceDoesNotStartTheBestEffortInteractionQuery() {
        var scope = SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, Set.of("com.example.blocked"), Set.of());
        AtomicInteger queries = new AtomicInteger();
        assertEquals("forbidden", SourceScopeGate.managedPresentation(scope, "com.example.blocked", () -> {
            queries.incrementAndGet(); return true;
        }));
        assertEquals(0, queries.get());
    }

    @Test public void unknownQueryResultPreservesAnAllowedManagedPresentation() {
        var scope = SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, Set.of(), Set.of());
        AtomicInteger queries = new AtomicInteger();
        assertEquals("normal", SourceScopeGate.managedPresentation(scope, "com.example.allowed", () -> {
            queries.incrementAndGet(); return false;
        }));
        assertEquals(1, queries.get());
    }
}
