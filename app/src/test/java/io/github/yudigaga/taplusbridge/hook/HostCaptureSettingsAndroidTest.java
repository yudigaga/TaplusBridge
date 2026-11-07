package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class HostCaptureSettingsAndroidTest {
    private Context context;
    private HostCaptureSettings adapter;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        FakeSettings.enabled = true; FakeSettings.reads = 0; FakeSettings.context = null;
        FakeCatcher.calls = 0; FakeCatcher.context = null; FakeCatcher.enable = null;
        adapter = new HostCaptureSettings(FakeSettings.class, FakeCatcher.class);
    }

    @Test public void unmanagedPolicyNeverReadsOrChangesHostSettings() throws Exception {
        assertFalse(adapter.synchronize(context, SourceScopePolicy.unmanaged()));
        assertEquals(0, FakeSettings.reads); assertEquals(0, FakeCatcher.calls);
    }

    @Test public void disabledHostIsNotEnabledBySourceScopeManagement() throws Exception {
        FakeSettings.enabled = false;
        assertFalse(adapter.synchronize(context, managed(SourceScopePolicy.Mode.BLACKLIST)));
        assertEquals(1, FakeSettings.reads); assertSame(context, FakeSettings.context);
        assertFalse(FakeSettings.enabled); assertEquals(0, FakeCatcher.calls);
    }

    @Test public void managedEnabledHostReappliesItsExistingSwitchOnceWithTheSameContext() throws Exception {
        assertTrue(adapter.synchronize(context, managed(SourceScopePolicy.Mode.BLACKLIST)));
        assertEquals(1, FakeSettings.reads); assertSame(context, FakeSettings.context);
        assertEquals(1, FakeCatcher.calls); assertSame(context, FakeCatcher.context);
        assertEquals(Boolean.TRUE, FakeCatcher.enable); assertTrue(FakeSettings.enabled);
    }

    @Test public void whitelistModeUsesTheSameExistingCaptureConfiguration() throws Exception {
        assertTrue(adapter.synchronize(context, managed(SourceScopePolicy.Mode.WHITELIST)));
        assertEquals(1, FakeCatcher.calls); assertEquals(Boolean.TRUE, FakeCatcher.enable);
    }

    @Test public void wrongMethodContractsAreRejectedBeforeAnyInvocation() {
        assertThrows(NoSuchMethodException.class, () -> new HostCaptureSettings(InstanceSettings.class, FakeCatcher.class));
        assertThrows(NoSuchMethodException.class, () -> new HostCaptureSettings(BoxedSettings.class, FakeCatcher.class));
        assertThrows(NoSuchMethodException.class, () -> new HostCaptureSettings(FakeSettings.class, InstanceCatcher.class));
        assertThrows(NoSuchMethodException.class, () -> new HostCaptureSettings(FakeSettings.class, ReturningCatcher.class));
        assertThrows(NoSuchMethodException.class, () -> new HostCaptureSettings(FakeSettings.class, WrongArguments.class));
        assertEquals(0, FakeSettings.reads); assertEquals(0, FakeCatcher.calls);
    }

    @Test public void hostFailureCannotBeReportedAsSuccessfulSynchronization() throws Exception {
        HostCaptureSettings failure = new HostCaptureSettings(FakeSettings.class, FailingCatcher.class);
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> failure.synchronize(context, managed(SourceScopePolicy.Mode.BLACKLIST)));
        assertTrue(error.getCause() instanceof IllegalStateException);
        assertTrue(FakeSettings.enabled); assertEquals(0, FakeCatcher.calls);
    }

    private static SourceScopePolicy managed(SourceScopePolicy.Mode mode) {
        return SourceScopePolicy.of(mode, Set.of("com.example.blocked"), Set.of("com.example.allowed"));
    }

    public static final class FakeSettings {
        static boolean enabled; static int reads; static Context context;
        public static boolean isTaplusEnable(Context value) { reads++; context=value; return enabled; }
    }
    public static final class FakeCatcher {
        static int calls; static Context context; static Boolean enable;
        public static void switchCatcherConfig(Context value, boolean enabled) { calls++; context=value; enable=enabled; }
    }
    public static final class InstanceSettings { public boolean isTaplusEnable(Context value) { return true; } }
    public static final class BoxedSettings { public static Boolean isTaplusEnable(Context value) { return true; } }
    public static final class InstanceCatcher { public void switchCatcherConfig(Context value, boolean enabled) {} }
    public static final class ReturningCatcher { public static boolean switchCatcherConfig(Context value, boolean enabled) { return true; } }
    public static final class WrongArguments { public static void switchCatcherConfig(Context value, Boolean enabled) {} }
    public static final class FailingCatcher {
        public static void switchCatcherConfig(Context value, boolean enabled) { throw new IllegalStateException("unavailable"); }
    }
}
