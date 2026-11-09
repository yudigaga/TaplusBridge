package io.github.yudigaga.taplusbridge.probe;

import android.app.KeyguardManager;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.view.Display;
import android.view.MotionEvent;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.hook.ScopeCapturePayload;
import io.github.yudigaga.taplusbridge.runtime.RuntimeServer;
import io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.shadows.ShadowKeyguardManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowUserManager;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Exercises assist text through scope delivery and the authenticated, single-use runtime take. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeAssistDeliveryAndroidTest {
    private static final int HOST_UID = 22002, DEVICE = 1;
    private static final String SOURCE = "com.android.chrome";
    private static final String TEXT = "页面本轮实际正文", URL = "https://example.com/selected-card";
    private final List<Intent> starts = new ArrayList<>();
    private final List<Consumer<Bundle>> replies = new ArrayList<>();
    private final List<Integer> injected = new ArrayList<>();
    private ConfigStore settings;
    private SharedPreferences stored;
    private RuntimeServer runtime;
    private SystemScopeCapture capture;
    private SystemAssistContent assist;
    private PhysicalTouchState physical;
    private ScopeCaptureWindow windows;
    private ScopeCaptureWindowAndroidTest.Atms atms;
    private ScopeCaptureWindow.Snapshot target;
    private SystemAssistContent.Result supplied = new SystemAssistContent.Result(TEXT, List.of());
    private long down, began, readyAt, releasedAt;

    @Before public void setup() throws Exception {
        Context application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("config", 0).edit().clear().commit();
        stored = application.getSharedPreferences("scope_assist_delivery_test", 0);
        stored.edit().clear().commit();
        settings = new ConfigStore(application);
        settings.setEnabled(true); settings.setRecognitionEnabled(true);
        settings.setDragSettings(new DragSettings(false, "portal", "share", "lens", "translate",
                false, List.of(), 100, 100));
        configureLinkProbe(false);
        Context context = new ContextWrapper(application) {
            @Override public ComponentName startService(Intent intent) {
                starts.add(new Intent(intent)); return intent.getComponent();
            }
        };
        ((ShadowPowerManager) Shadow.extract(context.getSystemService(PowerManager.class))).setIsInteractive(true);
        ((ShadowKeyguardManager) Shadow.extract(context.getSystemService(KeyguardManager.class))).setKeyguardLocked(false);
        ((ShadowUserManager) Shadow.extract(context.getSystemService(UserManager.class))).setUserUnlocked(true);
        Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        ShadowDisplay shadowDisplay = Shadow.extract(display);
        shadowDisplay.setRealWidth(400); shadowDisplay.setRealHeight(800);
        PackageInfo host = new PackageInfo(); host.packageName = HostPolicy.HOST_PACKAGE;
        host.versionName = "4.1.3"; host.setLongVersionCode(40013);
        host.applicationInfo = new ApplicationInfo(); host.applicationInfo.packageName = host.packageName;
        host.applicationInfo.uid = HOST_UID; host.applicationInfo.enabled = true;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).installPackage(host);
        Handler worker = new Handler(Looper.getMainLooper());
        runtime = new RuntimeServer(context, worker, () -> stored, ignored -> {});
        physical = new PhysicalTouchState();
        atms = new ScopeCaptureWindowAndroidTest.Atms();
        var main = new ScopeCaptureWindowAndroidTest.Window(); main.mActivityRecord.main = main;
        atms.mWindowManager.mRoot.display.hit = main; atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        windows = new ScopeCaptureWindow(ScopeCaptureWindowAndroidTest.Atms.class,
                new ScopeCaptureWindow.CaptureBackend() {
                    public Class<?> surfaceType() { return Object.class; }
                    public Bitmap capture(Object layer, Object[] excluded, int uid, Rect crop, float scale) {
                        throw new AssertionError("Text-only assist delivery must not capture pixels");
                    }
                });
        target = windows.inspect(atms, 0, 50, 200);
        capture = new SystemScopeCapture(null, worker, () -> context, () -> atms, () -> runtime, windows, physical);
        runtime.setSourceCapture(capture);
        assist = new SystemAssistContent(worker, runtime::sourceContentConfiguration,
                picked -> windows.validate(atms, picked),
                (picked, receiver) -> { replies.add(receiver); return true; },
                (structure, picked) -> supplied, Runnable::run, ignored -> {}, physical);
        capture.setAssistContent(assist);
    }

    private void configureLinkProbe(boolean enabled) {
        assertTrue(settings.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), false, enabled));
        stored.edit().putString(RuntimeSnapshot.KEY, RuntimeSnapshot.from(RuntimeEnvironment.getApplication())).commit();
    }

    private void beginAssist() {
        down = SystemClock.uptimeMillis(); began = SystemClock.elapsedRealtime(); readyAt = began + 200;
        physical.event(0, DEVICE, down, down, MotionEvent.ACTION_DOWN, down);
        ReflectionHelpers.setField(capture,"sourceDown",down);
        assist.arm(target, runtime.sourceContentConfiguration(), down, began, readyAt, DEVICE);
    }

    private void receiveAssistAndRelease() {
        ShadowLooper.idleMainLooper(readyAt - SystemClock.elapsedRealtime(), TimeUnit.MILLISECONDS);
        assertEquals(1, replies.size());
        AssistStructure structure = new AssistStructure();
        ReflectionHelpers.setField(structure, "mActivityComponent", new ComponentName(target.source, target.activity));
        Bundle data = new Bundle(); data.putParcelable("structure", structure);
        replies.get(0).accept(data);
        assertEquals(TEXT, assist.read(target, runtime.sourceContentConfiguration(), began).text());
        long now = SystemClock.uptimeMillis();
        physical.event(0, DEVICE, down, now, MotionEvent.ACTION_UP, now);
        releasedAt = SystemClock.elapsedRealtime(); physical.markReleasedElapsed(releasedAt);
    }

    private void captureReleasedSource() throws Exception {
        Method method = SystemScopeCapture.class.getDeclaredMethod("capture", long.class,
                ScopeCaptureWindow.Snapshot.class, RuntimeSnapshot.class, long.class, long.class, long.class);
        method.setAccessible(true);
        long generation = ReflectionHelpers.getField(capture, "generation");
        method.invoke(capture, generation, target, runtime.sourceCaptureConfiguration(), began, readyAt, releasedAt);
        ShadowLooper.idleMainLooper();
    }

    private Bundle takeSubmitted() {
        assertEquals("The collected source content must reach the host", 1, starts.size());
        Intent start = starts.get(0);
        assertEquals(SystemScopeCapture.ACTION, start.getAction());
        assertEquals(HostPolicy.HOST_PACKAGE, start.getComponent().getPackageName());
        Bundle request = new Bundle(); request.putString("nonce", start.getStringExtra("scope_nonce"));
        assertEquals("unauthorized", runtime.handle(HOST_UID + 1, "scope_capture_take", request).getString("reason"));
        Bundle result = runtime.handle(HOST_UID, "scope_capture_take", request);
        assertEquals("content", ScopeCapturePayload.validate(result, SystemClock.elapsedRealtime()).kind());
        assertTrue(runtime.handle(HOST_UID, "scope_capture_take", request).isEmpty());
        assertFalse(result.containsKey("fd"));
        return result;
    }

    @Test public void emptyNativePickCannotSuppressActualSourceTextWhenSyntheticLinkProbeIsOff() throws Exception {
        beginAssist(); receiveAssistAndRelease();
        Bundle nativePick = new Bundle(); nativePick.putString("source", SOURCE);
        nativePick.putLong("native_elapsed", SystemClock.elapsedRealtime()); nativePick.putBoolean("has_valid_url", false);
        capture.nativeResult(nativePick);
        captureReleasedSource();
        Bundle delivered = takeSubmitted();
        assertEquals(TEXT, delivered.getString("text")); assertFalse(delivered.containsKey("url"));
    }

    @Test public void structuredSourceUrlIsDeliveredWithoutClickingTheOriginalCardAgain() throws Exception {
        configureLinkProbe(true); supplied = new SystemAssistContent.Result(TEXT, List.of(URL));
        ScopeLinkProbe link = new ScopeLinkProbe(new Handler(Looper.getMainLooper()), physical,
                runtime::sourceCaptureConfiguration, picked -> windows.validate(atms, picked),
                (picked, action, at) -> { injected.add(action); return true; }, ignored -> {});
        capture.setLinkProbe(link);
        beginAssist(); link.arm(target, runtime.sourceCaptureConfiguration(), down, began, 100, 1);
        receiveAssistAndRelease();
        link.pointer(MotionEvent.ACTION_UP, 1, target.x, target.y, down, SystemClock.uptimeMillis(), 20);
        captureReleasedSource();
        Bundle delivered = takeSubmitted();
        assertEquals(TEXT + "\n" + URL, delivered.getString("text"));
        assertTrue("A real structured URL makes a source click unnecessary", injected.isEmpty());
        assertFalse(delivered.containsKey("url"));
    }

    @Test public void validStructureResponseAtOnePointFiveSecondsStillCompletesReleasedRecognition() throws Exception {
        beginAssist();ShadowLooper.idleMainLooper(200,TimeUnit.MILLISECONDS);
        assertEquals(1,replies.size());long now=SystemClock.uptimeMillis();
        physical.event(0,DEVICE,down,now,MotionEvent.ACTION_UP,now);
        releasedAt=SystemClock.elapsedRealtime();physical.markReleasedElapsed(releasedAt);
        captureReleasedSource();ShadowLooper.idleMainLooper(1500,TimeUnit.MILLISECONDS);
        assertTrue(starts.isEmpty());
        AssistStructure structure=new AssistStructure();
        ReflectionHelpers.setField(structure,"mActivityComponent",new ComponentName(target.source,target.activity));
        Bundle data=new Bundle();data.putParcelable("structure",structure);replies.get(0).accept(data);
        ShadowLooper.idleMainLooper(100,TimeUnit.MILLISECONDS);
        assertEquals(TEXT,takeSubmitted().getString("text"));
    }

    @Test public void aNewPhysicalDownRevokesTextAlreadySubmittedToAColdHost() throws Exception {
        beginAssist();receiveAssistAndRelease();captureReleasedSource();assertEquals(1,starts.size());
        Bundle request=new Bundle();request.putString("nonce",starts.get(0).getStringExtra("scope_nonce"));
        ShadowLooper.idleMainLooper(1,TimeUnit.MILLISECONDS);long next=SystemClock.uptimeMillis();
        MotionEvent touch=MotionEvent.obtain(next,next,MotionEvent.ACTION_DOWN,target.x,target.y,0);
        try {capture.pointer(touch,0,true);}finally{touch.recycle();}
        assertTrue("a new touch must not consume the previous selection after the host wakes",
                runtime.handle(HOST_UID,"scope_capture_take",request).isEmpty());
    }

    @Test public void fallbackClickWaitsForTheHostWindowsToBecomePassthrough() throws Exception {
        configureLinkProbe(true);
        ScopeLinkProbe link=new ScopeLinkProbe(new Handler(Looper.getMainLooper()),physical,
                runtime::sourceCaptureConfiguration,picked->windows.validate(atms,picked),
                (picked,action,at)->{injected.add(action);return true;},ignored->{});
        capture.setLinkProbe(link);beginAssist();
        link.arm(target,runtime.sourceCaptureConfiguration(),down,began,100,1);
        receiveAssistAndRelease();captureReleasedSource();
        assertTrue("source input cannot run while portal windows still cover it",injected.isEmpty());
        assertEquals(1,starts.size());
        assertEquals("io.github.yudigaga.taplusbridge.SCOPE_LINK_PREPARE",starts.get(0).getAction());
        Bundle ready=new Bundle();ready.putString("nonce",starts.get(0).getStringExtra("link_nonce"));
        assertEquals("unauthorized",runtime.handle(HOST_UID+1,"scope_link_window_ready",ready).getString("reason"));
        assertTrue(injected.isEmpty());
        assertTrue(runtime.handle(HOST_UID,"scope_link_window_ready",ready).getBoolean("accepted"));
        ShadowLooper.idleMainLooper();
        assertEquals(List.of(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP),injected);
    }

    @Test public void anOldHostReadyReplyAfterANewTouchCannotInjectOrDeliverPreviousContent() throws Exception {
        configureLinkProbe(true);
        ScopeLinkProbe link = new ScopeLinkProbe(new Handler(Looper.getMainLooper()),physical,
                runtime::sourceCaptureConfiguration,picked->windows.validate(atms,picked),
                (picked,action,at)->{injected.add(action);return true;},ignored->{});
        capture.setLinkProbe(link);beginAssist();
        link.arm(target,runtime.sourceCaptureConfiguration(),down,began,100,1);
        receiveAssistAndRelease();captureReleasedSource();
        Bundle ready = new Bundle();ready.putString("nonce",starts.get(0).getStringExtra("link_nonce"));
        ShadowLooper.idleMainLooper(1,TimeUnit.MILLISECONDS);long next = SystemClock.uptimeMillis();
        physical.event(0,DEVICE,next,next,MotionEvent.ACTION_DOWN,next);
        MotionEvent touch = MotionEvent.obtain(next,next,MotionEvent.ACTION_DOWN,target.x,target.y,0);
        try {capture.pointer(touch,0,true);} finally {touch.recycle();}
        assertFalse(runtime.handle(HOST_UID,"scope_link_window_ready",ready).getBoolean("accepted"));
        ShadowLooper.idleMainLooper(8_001,TimeUnit.MILLISECONDS);
        assertTrue(injected.isEmpty());assertEquals(1,starts.size());
    }

    @Test public void unavailableHostPassthroughStillDeliversRealTextWithoutInjectingAnything() throws Exception {
        configureLinkProbe(true);
        ScopeLinkProbe link = new ScopeLinkProbe(new Handler(Looper.getMainLooper()),physical,
                runtime::sourceCaptureConfiguration,picked->windows.validate(atms,picked),
                (picked,action,at)->{injected.add(action);return true;},ignored->{});
        capture.setLinkProbe(link);
        beginAssist();link.arm(target,runtime.sourceCaptureConfiguration(),down,began,100,1);
        receiveAssistAndRelease();captureReleasedSource();
        assertEquals(1,starts.size());assertTrue(injected.isEmpty());
        ShadowLooper.idleMainLooper(8_001,TimeUnit.MILLISECONDS);
        assertEquals("a failed optional click must not discard actual content",2,starts.size());
        assertEquals(SystemScopeCapture.ACTION,starts.get(1).getAction());assertTrue(injected.isEmpty());
        Intent ordinary = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(URL));
        assertFalse("failed preparation must release ordinary source launches immediately",
                link.intercept(target.uid,SOURCE,0,ordinary,true));
    }
}
