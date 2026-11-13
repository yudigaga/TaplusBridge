package io.github.yudigaga.taplusbridge.probe;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.WindowManager;
import io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuHooksAndroidTest.FakeSplash;
import io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuHooksAndroidTest.FakeService;
import io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuHooksAndroidTest.FakeResult;
import io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuHooksAndroidTest.FakeToken;
import io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuRetentionAndroidTest.FakeCallback;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.runtime.RuntimeServer;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.time.Duration;
import java.util.List;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

/** Real system pointer -> authenticated host notification -> exact menu callback, with fake WMS. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeMenuIntegrationAndroidTest {
    private static final int HOST_UID = 22002;
    private Context context;
    private RuntimeServer runtime;
    private SystemScopeCapture capture;
    private PhysicalTouchState physical;
    private ScopeCaptureWindowAndroidTest.Atms atms;
    private ScopeCaptureWindowAndroidTest.Window main;
    private long down;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config",0).edit().clear().commit();
        var settings = new ConfigStore(context);
        settings.setEnabled(true); settings.setRecognitionEnabled(true);
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),false,false));
        var stored = context.getSharedPreferences("menu_integration_test",0);
        stored.edit().clear().putString(RuntimeSnapshot.KEY,RuntimeSnapshot.from(context)).commit();
        ((ShadowPowerManager) Shadow.extract(context.getSystemService(PowerManager.class))).setIsInteractive(true);
        ((ShadowKeyguardManager) Shadow.extract(context.getSystemService(KeyguardManager.class))).setKeyguardLocked(false);
        ((ShadowUserManager) Shadow.extract(context.getSystemService(UserManager.class))).setUserUnlocked(true);
        Settings.System.putInt(context.getContentResolver(),"content_catcher_network_enabled_content_extension",1);
        Settings.System.putInt(context.getContentResolver(),"support_long_press_mode",1);
        Settings.System.putInt(context.getContentResolver(),"support_long_press_time_out",100);
        install(BridgeContract.HOST_PACKAGE,HOST_UID,"5.1.5",50015);
        install(BridgeContract.APP_PACKAGE,22003,"1",1);
        Handler worker = new Handler(Looper.getMainLooper());
        runtime = new RuntimeServer(context,worker,()->stored,ignored->{});
        physical = new PhysicalTouchState();
        atms = new ScopeCaptureWindowAndroidTest.Atms();
        main = new ScopeCaptureWindowAndroidTest.Window(); main.mActivityRecord.main = main;
        atms.mWindowManager.mRoot.display.hit = main;
        atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        var windows = new ScopeCaptureWindow(ScopeCaptureWindowAndroidTest.Atms.class,
                new ScopeCaptureWindow.CaptureBackend() {
                    public Class<?> surfaceType() { return Object.class; }
                    public Bitmap capture(Object layer,Object[] excluded,int uid,Rect crop,float scale) {
                        throw new AssertionError("Menu cleanup must not capture an image");
                    }
                });
        capture = new SystemScopeCapture(null,worker,()->context,()->atms,()->runtime,windows,physical);
        runtime.setSourceCapture(capture);
        capture.setMenuNavigationGuardReady(true);
    }

    @After public void clearBridge() throws Exception { bridge(false); }

    private void bridge(boolean connected) throws Exception {
        Class<?> type=Class.forName("io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge");
        Object remote=connected?Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(owner,method,args)->
                method.getName().equals("call")?runtime.handle(HOST_UID,(String)args[0],(Bundle)args[1]):null):null;
        Method setter=Class.forName("io.github.yudigaga.taplusbridge.runtime.RuntimeClient").getDeclaredMethod("testBridge",type);
        setter.setAccessible(true);setter.invoke(null,remote);
    }

    private Object hostHelper(String name,Class<?>[] types,Object...args) throws Exception {
        Method method=Class.forName("io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuRetention").getDeclaredMethod(name,types);
        method.setAccessible(true);return method.invoke(null,args);
    }

    private FakeSplash hostShown(long at) throws Exception {
        Context host=new ContextWrapper(context){@Override public String getPackageName(){return BridgeContract.HOST_PACKAGE;}};
        FakeSplash splash=ReflectionHelpers.callConstructor(FakeSplash.class,ClassParameter.from(Context.class,host));
        FakeService.source=main.mAttrs.packageName;FakeService.activity=main.mActivityRecord.mActivityComponent.getClassName();
        FakeService.mCatcherResult.token=new FakeToken(FakeService.source,FakeService.activity);
        assertEquals(at,hostHelper("rememberShown",new Class<?>[]{Object.class,String.class,long.class},splash,FakeService.source,at));
        bridge(true);return splash;
    }

    private FakeResult backControl() {
        FakeResult result=ReflectionHelpers.callConstructor(FakeResult.class,
                ClassParameter.from(String.class,FakeService.source),ClassParameter.from(String.class,FakeService.activity));
        result.properties.put("observe_control_event","257");return result;
    }

    private boolean deliverControl(FakeCallback callback,FakeResult control) throws Exception {
        boolean kept=(Boolean)hostHelper("retainControl",new Class<?>[]{Object.class,Object.class},callback,control);
        if(!kept)callback.onContentReceived(control);return kept;
    }

    private void install(String name,int uid,String version,int code) {
        PackageInfo info = new PackageInfo(); info.packageName = name;
        info.versionName = version; info.setLongVersionCode(code);
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.packageName = name;
        info.applicationInfo.uid = uid; info.applicationInfo.enabled = true;
        info.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).installPackage(info);
    }

    private void pointer(int action) {
        pointer(action,50,200);
    }

    private void pointer(int action,float x,float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) down = now;
        physical.event(0,0,down,now,action,now);
        if (action == MotionEvent.ACTION_UP) physical.markReleasedElapsed(SystemClock.elapsedRealtime());
        MotionEvent event = MotionEvent.obtain(down,now,action,x,y,0);
        try { capture.pointer(event,0,true); } finally { event.recycle(); }
        ShadowLooper.idleMainLooper();
    }

    private ScopeCaptureWindowAndroidTest.FakeBackCallback newMenu() {
        var menu = new ScopeCaptureWindowAndroidTest.Window();
        menu.mAttrs.type = WindowManager.LayoutParams.TYPE_APPLICATION;
        menu.mActivityRecord = main.mActivityRecord; menu.bounds = new Rect(10,100,200,300);
        var callback = new ScopeCaptureWindowAndroidTest.FakeBackCallback(atms.mWindowManager);
        menu.backInfo = new ScopeCaptureWindowAndroidTest.FakeBackInfo(callback);
        atms.mWindowManager.mRoot.display.additionalWindows.add(main);
        atms.mWindowManager.mRoot.display.hit = menu;
        atms.mWindowManager.mRoot.display.mCurrentFocus = menu;
        return callback;
    }

    private Bundle visible(long shownAt) {
        Bundle shown = new Bundle(); shown.putString("source",main.mAttrs.packageName);
        shown.putLong("shown_elapsed",shownAt);
        return runtime.handle(HOST_UID,"recognition_visible",shown);
    }

    @Test public void actualPointerAndHostVisibilityCloseTheNewMenuWhileTheFingerIsStillHeld() {
        pointer(MotionEvent.ACTION_DOWN);
        ShadowSystemClock.advanceBy(Duration.ofMillis(101));
        var callback = newMenu();
        assertTrue(visible(SystemClock.elapsedRealtime()).getBoolean("accepted"));
        ShadowLooper.idleMainLooper(); assertEquals("a visible recognition card must replace the new menu immediately",1,callback.calls);
        pointer(MotionEvent.ACTION_UP);
        assertEquals(1,callback.calls);
        assertFalse(visible(SystemClock.elapsedRealtime()).getBoolean("accepted"));
        ShadowLooper.idleMainLooper(); assertEquals(1,callback.calls);
    }

    @Test public void aNewDownMakesThePreviousRecognitionWindowNotificationStale() {
        pointer(MotionEvent.ACTION_DOWN);
        ShadowSystemClock.advanceBy(Duration.ofMillis(101));
        long oldShown = SystemClock.elapsedRealtime();
        pointer(MotionEvent.ACTION_DOWN);
        var callback = newMenu();
        assertFalse(visible(oldShown).getBoolean("accepted"));
        pointer(MotionEvent.ACTION_UP); assertEquals(0,callback.calls);
    }

    @Test public void physicalUpAloneCannotAuthorizeCleanupBeforeItsFinalCoordinatesAreValidated() {
        pointer(MotionEvent.ACTION_DOWN);
        ShadowSystemClock.advanceBy(Duration.ofMillis(101));
        var callback = newMenu();
        long now = SystemClock.uptimeMillis();
        physical.event(0,0,down,now,MotionEvent.ACTION_UP,now);
        physical.markReleasedElapsed(SystemClock.elapsedRealtime());
        assertTrue(visible(SystemClock.elapsedRealtime()).getBoolean("accepted"));
        ShadowLooper.idleMainLooper();
        assertEquals("the input monitor's UP alone is not a validated final pointer",0,callback.calls);
        pointer(MotionEvent.ACTION_UP,150,200);
        assertEquals("the final UP must be validated before menu cleanup",0,callback.calls);
    }

    @Test public void overlayMenuCloseEchoPreservesTheSameRecognitionModelOnce() throws Exception {
        pointer(MotionEvent.ACTION_DOWN);ShadowSystemClock.advanceBy(Duration.ofMillis(101));
        var callback=newMenu();atms.mWindowManager.mRoot.display.mCurrentFocus.backInfo.priority=1_000_000;
        long shown=SystemClock.elapsedRealtime();FakeSplash splash=hostShown(shown);
        FakeResult original=FakeService.mCatcherResult;
        assertTrue(visible(shown).getBoolean("accepted"));ShadowLooper.idleMainLooper();assertEquals(1,callback.calls);
        FakeCallback hostCallback=ReflectionHelpers.callConstructor(FakeCallback.class,ClassParameter.from(FakeService.class,splash.mService));
        assertTrue(deliverControl(hostCallback,backControl()));
        assertSame(original,FakeService.mCatcherResult);assertFalse(FakeService.sIsTaskFinished);
        assertFalse(deliverControl(hostCallback,backControl()));assertTrue(FakeService.sIsTaskFinished);
    }

    @Test public void aRealKeyAfterModuleMenuCloseCannotBeSwallowedAsItsEcho() throws Exception {
        pointer(MotionEvent.ACTION_DOWN);ShadowSystemClock.advanceBy(Duration.ofMillis(101));
        var callback=newMenu();long shown=SystemClock.elapsedRealtime();FakeSplash splash=hostShown(shown);
        assertTrue(visible(shown).getBoolean("accepted"));ShadowLooper.idleMainLooper();assertEquals(1,callback.calls);
        capture.newKeyInput();
        FakeCallback hostCallback=ReflectionHelpers.callConstructor(FakeCallback.class,ClassParameter.from(FakeService.class,splash.mService));
        assertFalse(deliverControl(hostCallback,backControl()));assertTrue(FakeService.sIsTaskFinished);
    }

    @Test public void noMenuCallbackIsDispatchedWhenTheRealKeyBarrierIsUnavailable() {
        capture.setMenuNavigationGuardReady(false);
        pointer(MotionEvent.ACTION_DOWN);ShadowSystemClock.advanceBy(Duration.ofMillis(101));
        var callback=newMenu();assertTrue(visible(SystemClock.elapsedRealtime()).getBoolean("accepted"));
        ShadowLooper.idleMainLooper();assertEquals(0,callback.calls);
    }
}
