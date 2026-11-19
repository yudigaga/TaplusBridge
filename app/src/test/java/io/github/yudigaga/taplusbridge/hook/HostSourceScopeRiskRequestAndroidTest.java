package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.graphics.Point;
import android.os.Bundle;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Proxy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class HostSourceScopeRiskRequestAndroidTest {
    @Test public void fourArgumentHostGateBuildsRequestWhenContentCatcherPointIsMissing() {
        Context context = RuntimeEnvironment.getApplication();
        String source = "com.example.source";
        String activity = "com.example.source.ReaderActivity";
        XposedInterface.Chain chain = chain(context, source, activity, null);

        Bundle request = HostSourceScopeHooks.recordingRiskRequest(source, chain);

        assertNotNull(request);
        assertEquals(2, request.size());
        assertEquals(source, request.getString("source"));
        assertEquals(activity, request.getString("activity"));
    }

    @Test public void fourArgumentHostGateDoesNotBindRiskRequestToContentCatcherCoordinate() {
        Context context = RuntimeEnvironment.getApplication();
        String source = "com.example.source";
        String activity = "com.example.source.ReaderActivity";
        XposedInterface.Chain chain = chain(context, source, activity, new Point(703, 911));

        Bundle request = HostSourceScopeHooks.recordingRiskRequest(source, chain);

        assertNotNull(request);
        assertEquals(2, request.size());
        assertEquals(source, request.getString("source"));
        assertEquals(activity, request.getString("activity"));
        assertFalse(request.containsKey("x"));
        assertFalse(request.containsKey("y"));
    }

    private static XposedInterface.Chain chain(Object... arguments) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(
                XposedInterface.Chain.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, values) -> {
                    if ("getArg".equals(method.getName())) return arguments[(Integer) values[0]];
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
