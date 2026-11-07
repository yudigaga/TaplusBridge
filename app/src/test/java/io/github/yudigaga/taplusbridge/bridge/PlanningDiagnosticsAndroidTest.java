package io.github.yudigaga.taplusbridge.bridge;

import android.os.Bundle;
import io.github.yudigaga.taplusbridge.routing.RouteRule;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class PlanningDiagnosticsAndroidTest extends BridgeRobolectricFixture {
    @Test public void noMatchAndTargetMissingHaveDifferentNonContentReasons() {
        assertEquals("no_match", LaunchPlanner.planWithReason(context, web("https://no-route.example/private?token=secret")).reason());
        LaunchPlanner.PlanningResult missing = LaunchPlanner.planWithReason(context, web("https://b23.tv/private?token=secret"));
        assertNull(missing.plan()); assertEquals("target_missing", missing.reason());
        assertFalse(missing.reason().contains("private")); assertFalse(missing.reason().contains("secret"));
    }
    @Test public void ambiguousRulesRemainDeclinedWithoutNativeFallback() {
        install("com.example.one", true, true);
        install("com.example.two", true, true);
        config.saveRules(List.of(new RouteRule("route.example", "com.example.one", "*", false, false),
                new RouteRule("route.example", "com.example.two", "*", false, false)));
        LaunchPlanner.PlanningResult result = LaunchPlanner.planWithReason(context, web("https://route.example/path"));
        assertNull(result.plan()); assertEquals("ambiguous", result.reason());
    }
    @Test public void disabledAndInvalidRequestsRemainNonActionable() {
        assertEquals("invalid_request", LaunchPlanner.planWithReason(context, null).reason());
        config.setEnabled(false);
        LaunchPlanner.PlanningResult result = LaunchPlanner.planWithReason(context, new Bundle());
        assertNull(result.plan()); assertEquals("disabled", result.reason());
    }
}
