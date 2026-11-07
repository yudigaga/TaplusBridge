package io.github.yudigaga.taplusbridge.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.os.SystemClock;
import io.github.libxposed.api.XposedModule;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowPackageManager;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemProbeInputGateAndroidTest {
    private static final String NONCE = "A".repeat(43);
    private SystemProbe probe;
    private ValidityProvider provider;
    private Object owner;
    private Class<?> sessionType;

    @Before public void setup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo module = new PackageInfo(); module.packageName = BridgeContract.APP_PACKAGE;
        module.applicationInfo = new ApplicationInfo(); module.applicationInfo.packageName = module.packageName;
        module.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED; module.applicationInfo.enabled = true;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).installPackage(module);
        provider = Robolectric.buildContentProvider(ValidityProvider.class).create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
        Constructor<SystemProbe> constructor = SystemProbe.class.getDeclaredConstructor(XposedModule.class);
        constructor.setAccessible(true); probe = constructor.newInstance((Object) null);
        Field system = SystemProbe.class.getDeclaredField("system"); system.setAccessible(true); system.set(probe, context);
        sessionType = Class.forName(SystemProbe.class.getName() + "$Session");
        Class<?> snapshotType = Class.forName(SystemProbe.class.getName() + "$Snapshot");
        Constructor<?> session = sessionType.getDeclaredConstructor(ProbeGate.Ticket.class, snapshotType,
                ResultReceiver.class, boolean.class, long.class);
        session.setAccessible(true);
        ProbeGate.Ticket ticket = new ProbeGate().arm(NONCE, CaptureLease.WECHAT, 21001, 0, SystemClock.elapsedRealtime());
        owner = session.newInstance(ticket, null, null, false, 0);
    }

    @After public void stopWorker() throws Exception {
        Field worker = SystemProbe.class.getDeclaredField("worker"); worker.setAccessible(true);
        ((Handler) worker.get(probe)).getLooper().quitSafely();
    }

    @Test public void providerInputBoundaryRejectsARevokedProbeBeforeInspectingTouchOrWindows() throws Exception {
        provider.enabled = false;
        Method inspect = SystemProbe.class.getDeclaredMethod("inspectInputWindow", sessionType); inspect.setAccessible(true);
        InvocationTargetException rejected = assertThrows(InvocationTargetException.class, () -> inspect.invoke(probe, owner));
        assertEquals("cancelled", rejected.getCause().getMessage());
    }

    @Test public void providerAuthorizationIsReadAgainForTheSameArmedSession() throws Exception {
        Method valid = SystemProbe.class.getDeclaredMethod("probeValid", sessionType); valid.setAccessible(true);
        assertEquals(Boolean.TRUE, valid.invoke(probe, owner));
        provider.enabled = false;
        assertEquals(Boolean.FALSE, valid.invoke(probe, owner));
    }

    public static final class ValidityProvider extends ContentProvider {
        boolean enabled = true;
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            assertEquals("probe_valid", method); assertEquals(NONCE, extras.getString("nonce"));
            assertEquals(0, extras.getInt("user", -1));
            Bundle result = new Bundle(); result.putBoolean("enabled", enabled); return result;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
