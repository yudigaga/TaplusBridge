package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import android.view.WindowManager;
import io.github.yudigaga.taplusbridge.drag.RainbowDragView;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.robolectric.Robolectric;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class HostLongPressDragAndroidTest {
    private static final String SOURCE = "com.example.source", TOKEN = "a".repeat(43), IDENTITY = "b".repeat(43);

    @Before public void publishDefaultRecognitionPolicy() {
        RecordingImportProvider provider=Robolectric.buildContentProvider(RecordingImportProvider.class).create(BridgeContract.AUTHORITY).get();
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY,provider);
    }

    @Test public void propertyCoordinatesAreFiniteNonnegativeAndNeverCoercedFromArbitraryObjects() throws Exception {
        for (String input : new String[]{"12.5-24", "0-0", ".5-5.", "1e2-2E2"}) {
            NativeResult value = result(); value.properties.put("coordinate", input);
            assertNotNull(input, HostLongPressDrag.point(value));
        }
        for (Object input : new Object[]{"NaN-1", "Infinity-1", "-1-3", "1--3", " 1-2", "1-2 ", "1,2",
                "1-2-3", "1e99-2", Integer.valueOf(1), new Object() {
                    @Override public String toString() { throw new AssertionError("Do not inspect arbitrary objects"); }
                }}) {
            NativeResult value = result(); value.properties.put("coordinate", input);
            assertNull(HostLongPressDrag.point(value));
        }
    }

    @Test public void emptyNativePickStillArmsTheDragAndALateBitmapUpgradesTheSameHeld() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Object service = new Object();
        HostLongPressDrag observer = new HostLongPressDrag(null, null, (a, b, c, d) -> {});
        NativeResult pick = result(); pick.properties.put("coordinate", "50-60");
        installBridge(state("holding"));
        Field current = HostLongPressDrag.class.getDeclaredField("current"); current.setAccessible(true);
        Bitmap image = Bitmap.createBitmap(12, 14, Bitmap.Config.ARGB_8888);
        try {
            assertFalse(observer.hold(context, service, pick, true));
            Object held = current.get(observer);
            assertNotNull("late content must not prevent gesture preparation", held);
            observer.hold(context, service, pick, true, image);
            assertSame(held, current.get(observer));
            Bitmap owned = (Bitmap) held.getClass().getDeclaredField("image").get(held);
            assertNotNull(owned); assertNotSame(image, owned); assertFalse(image.isRecycled());
        } finally { observer.close(service); installBridge(null); image.recycle(); }
    }

    @Test public void preparedGestureNeedsNoNativeCallbackAndIsIdempotentForItsToken() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Object service = new Object();
        HostLongPressDrag observer = new HostLongPressDrag(null, null, (a, b, c, d) -> {});
        Bundle prepared = preparedState(); installBridge(prepared);
        var accept = HostLongPressDrag.class.getDeclaredMethod("acceptPrepared", Context.class, Object.class,
                Bundle.class, String.class); accept.setAccessible(true);
        Field current = HostLongPressDrag.class.getDeclaredField("current"); current.setAccessible(true);
        try {
            assertTrue((Boolean) accept.invoke(observer, context, service, prepared, TOKEN));
            Object held = current.get(observer); assertNotNull(held);
            assertNull(held.getClass().getDeclaredField("nativePick").get(held));
            assertTrue((Boolean) accept.invoke(observer, context, service, prepared, TOKEN));
            assertSame(held, current.get(observer));
        } finally { observer.close(service); installBridge(null); }
    }

    @Test public void preparedMetadataCannotChangeGestureSourceOriginTokenOrReleaseState() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Object service = new Object();
        HostLongPressDrag observer = new HostLongPressDrag(null, null, (a, b, c, d) -> {});
        var accept = HostLongPressDrag.class.getDeclaredMethod("acceptPrepared", Context.class, Object.class,
                Bundle.class, String.class); accept.setAccessible(true);
        for (String mutation : List.of("source", "origin", "token", "released")) {
            Bundle prepared = preparedState();
            if (mutation.equals("source")) prepared.getBundle("capture").putString("source", "com.example.other");
            if (mutation.equals("origin")) prepared.getBundle("capture").putFloat("x", 51);
            if (mutation.equals("token")) prepared.putString("token", "z".repeat(43));
            if (mutation.equals("released")) prepared.putString("state", "released");
            assertFalse(mutation, (Boolean) accept.invoke(observer, context, service, prepared, TOKEN));
        }
        observer.close(service);
    }

    @Test public void fallbackLinksAreRequestedOnceOnlyAfterTheReleasedMaskIsRemoved() throws Exception {
        assertFallbackProbeAfterRelease(null);
    }

    @Test public void coordinateOnlyNativePickDoesNotDisableTheSystemLinkFallback() throws Exception {
        NativeResult coordinateOnly = result(); coordinateOnly.properties.put("coordinate", "50-60");
        assertFallbackProbeAfterRelease(coordinateOnly);
    }

    @Test public void aReconstructedDragPayloadNeverAuthorizesANewSourceClick() throws Exception {
        NativeResult pick=result();
        Bundle image=new Bundle();image.putBoolean("taplus_drag",true);
        image.putParcelable("view_rect",new android.graphics.Rect(0,0,1080,2400));
        pick.jobs.put("image_pick",Map.of("image_pick_mode",image));
        var eligible=HostLongPressDrag.class.getDeclaredMethod("nativeProbeGeometry",Object.class);eligible.setAccessible(true);
        assertFalse("synthetic fullscreen geometry is not a source card",(Boolean)eligible.invoke(null,pick));
    }

    @Test public void nativeTitleAndActualSourceParagraphSurviveInTheSameDragPayload() throws Exception {
        Context context=RuntimeEnvironment.getApplication();Object service=new Object();NativeResult nativePick=result();
        var observer=new HostLongPressDrag(null,null,(a,b,c,d)->{});
        var type=Class.forName(HostLongPressDrag.class.getName()+"$Held");
        var constructor=type.getDeclaredConstructor(Context.class,Object.class,Object.class,String.class,Bitmap.class,HostLongPressDrag.Lease.class);
        constructor.setAccessible(true);
        Object held=constructor.newInstance(context,service,nativePick,"原生标题",null,HostLongPressDrag.lease(state("holding"),SOURCE,TOKEN,IDENTITY));
        Bundle wire=new Bundle();wire.putString("source_text","触点附近的真实完整正文");
        var apply=HostLongPressDrag.class.getDeclaredMethod("applySourceText",type,Bundle.class);apply.setAccessible(true);
        apply.invoke(observer,held,wire);
        String text=(String)type.getDeclaredField("text").get(held);
        assertTrue(text.contains("原生标题"));assertTrue(text.contains("触点附近的真实完整正文"));
        apply.invoke(observer,held,wire);assertEquals(text,type.getDeclaredField("text").get(held));
    }

    private void assertFallbackProbeAfterRelease(Object nativePick) throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Object service = new Object();
        HostLongPressDrag observer = new HostLongPressDrag(null, null, (a, b, c, d) -> {});
        Bundle state = preparedState();
        state.putLong("drag_at", SystemClock.elapsedRealtime());
        var type = Class.forName(HostLongPressDrag.class.getName() + "$Held");
        var constructor = type.getDeclaredConstructor(Context.class, Object.class, Object.class,
                String.class, Bitmap.class, HostLongPressDrag.Lease.class); constructor.setAccessible(true);
        Object held = constructor.newInstance(context, service, nativePick, null, null,
                HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY));
        type.getDeclaredField("dataReady").setBoolean(held, true);
        Field current = HostLongPressDrag.class.getDeclaredField("current"); current.setAccessible(true); current.set(observer, held);
        RainbowDragView preview = new RainbowDragView(context, 1080, 2400, 1, 50, 60,
                Map.of("up", "share", "down", "share", "left", "share", "right", "share"), "", null);
        type.getDeclaredField("preview").set(held, preview);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var checked = new java.util.concurrent.CountDownLatch(1);
        Class<?> bridgeType = Class.forName("io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge");
        Object remote = Proxy.newProxyInstance(bridgeType.getClassLoader(), new Class<?>[]{bridgeType},
                (target, method, args) -> {
                    if (!"call".equals(method.getName())) return null;
                    if ("drag_gesture_link".equals(args[0])) {
                        assertNull("mask must be gone before source input", type.getDeclaredField("preview").get(held));
                        calls.incrementAndGet(); checked.countDown();
                        Bundle accepted = new Bundle(); accepted.putBoolean("accepted", true); return accepted;
                    }
                    return null;
                });
        var install = Class.forName("io.github.yudigaga.taplusbridge.runtime.RuntimeClient")
                .getDeclaredMethod("testBridge", bridgeType); install.setAccessible(true); install.invoke(null, remote);
        var process = HostLongPressDrag.class.getDeclaredMethod("process", type, HostLongPressDrag.Lease.class);
        process.setAccessible(true);
        try {
            assertEquals(0, calls.get());
            state.putString("state", "released"); state.putString("direction", "right"); state.putString("action", "share");
            var released = HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY);
            process.invoke(observer, held, released);
            process.invoke(observer, held, released);
            assertSame(preview, type.getDeclaredField("preview").get(held));
            ShadowLooper.idleMainLooper();
            assertTrue(checked.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { observer.close(service); installBridge(null); }
    }

    private static void installBridge(Bundle response) throws Exception {
        Class<?> type = Class.forName("io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge");
        Object remote = response == null ? null : Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (target, method, args) -> "call".equals(method.getName()) ? response : null);
        var install = Class.forName("io.github.yudigaga.taplusbridge.runtime.RuntimeClient")
                .getDeclaredMethod("testBridge", type); install.setAccessible(true); install.invoke(null, remote);
    }

    private static Bundle preparedState() {
        Bundle state = state("holding"); state.putLong("recognition_at", SystemClock.elapsedRealtime());
        Bundle capture = new Bundle();
        capture.putString("source", SOURCE); capture.putString("activity", SOURCE + ".MainActivity");
        capture.putInt("uid", 10123); capture.putInt("user", 0); capture.putInt("display", 0);
        capture.putFloat("x", 50); capture.putFloat("y", 60);
        capture.putInt("left", 0); capture.putInt("top", 0); capture.putInt("right", 1080); capture.putInt("bottom", 2400);
        capture.putInt("width", 1080); capture.putInt("height", 2400);
        capture.putLong("captured_elapsed", SystemClock.elapsedRealtime());
        capture.putString("kind", "resume"); capture.putInt("bytes", 0);
        state.putBundle("capture", capture); state.putBoolean("link_probe", true); return state;
    }

    @Test public void nativeTouchPointIsCopiedOnlyFromExpectedBundleGeometry() throws Exception {
        NativeResult value = result(); PointF original = new PointF(45, 80);
        Bundle image = new Bundle(); image.putParcelableArray("touch_points", new PointF[]{original});
        value.jobs.put("image_pick", Map.of("image_pick_mode", image));
        PointF point = HostLongPressDrag.point(value);
        assertEquals(original, point); assertNotSame(original, point);
        original.x = -3; assertNull(HostLongPressDrag.point(value));
        image.putParcelableArray("touch_points", new PointF[]{new PointF(Float.NaN, 80)});
        assertNull(HostLongPressDrag.point(value));
    }

    @Test public void coordinatesPreferValidPropertyAndFallBackToGeometryIfMalformed() throws Exception {
        NativeResult value = result();
        Bundle text = new Bundle(); text.putParcelableArray("touch_points", new PointF[]{new PointF(4, 8)});
        value.jobs.put("text_pick", Map.of("pick_mode", text));
        value.properties.put("coordinate", "50-60");
        assertEquals(new PointF(50, 60), HostLongPressDrag.point(value));
        value.properties.put("coordinate", "bad");
        assertEquals(new PointF(4, 8), HostLongPressDrag.point(value));
    }

    @Test public void rejectedSecondCallbackKeepsTheFirstAuthorizedPayloadAndNeverHoldsTheNativePick() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        HostLongPressDrag observer = new HostLongPressDrag(null, null, (a, b, c, d) -> fail("No delivery expected"));
        NativeResult first = result(), second = result();
        second.properties.put("coordinate", "50-60");
        Object service = new Object();
        var heldType = Class.forName(HostLongPressDrag.class.getName() + "$Held");
        var constructor = heldType.getDeclaredConstructor(Context.class, Object.class, Object.class,
                String.class, Bitmap.class, HostLongPressDrag.Lease.class);
        constructor.setAccessible(true);
        Object held = constructor.newInstance(context, service, first, "已授权的原文", null,
                HostLongPressDrag.lease(state("holding"), SOURCE, null, null));
        Field current = HostLongPressDrag.class.getDeclaredField("current"); current.setAccessible(true); current.set(observer, held);
        Class<?> bridge = Class.forName("io.github.yudigaga.taplusbridge.runtime.IRuntimeBridge");
        var response = new java.util.concurrent.atomic.AtomicReference<>(Bundle.EMPTY);
        Object remote = Proxy.newProxyInstance(bridge.getClassLoader(), new Class<?>[]{bridge},
                (target, method, args) -> "call".equals(method.getName()) ? response.get() : null);
        var install = Class.forName("io.github.yudigaga.taplusbridge.runtime.RuntimeClient")
                .getDeclaredMethod("testBridge", bridge); install.setAccessible(true);
        install.invoke(null, remote);
        try {
            assertFalse(observer.hold(context, service, second, true));
            assertSame(held, current.get(observer));
            response.set(state("holding"));
            assertFalse(observer.hold(context, service, second, true));
            assertSame(held, current.get(observer));
            assertFalse(observer.hold(context, service, first, false));
            assertSame(held, current.get(observer));
        } finally { install.invoke(null, new Object[]{null}); current.set(observer, null); }
    }

    @Test public void sourceMustComeFromValidatedInjectorToken() throws Exception {
        assertEquals(SOURCE, HostLongPressDrag.source(result()));
        NativeResult value = result(); value.token = new NativeToken("invalid", 1);
        assertNull(HostLongPressDrag.source(value)); value.token = null;
        assertNull(HostLongPressDrag.source(value));
    }

    @Test public void onlyExpectedLeaseAndConfigurationCanContinuePolling() {
        Bundle state = state("holding"); HostLongPressDrag.Lease lease = HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY);
        assertNotNull(lease);
        assertEquals(state.getLong("recognition_at"), lease.recognitionAt());
        assertEquals(state.getLong("drag_at"), lease.dragAt());
        assertFalse(lease.recognitionReady(lease.recognitionAt() - 1));
        assertTrue(lease.recognitionReady(lease.recognitionAt()));
        assertFalse(lease.dragReady(lease.dragAt() - 1));
        assertTrue(lease.dragReady(lease.dragAt()));
        assertNull(HostLongPressDrag.lease(state, "com.example.other", TOKEN, IDENTITY));
        assertNull(HostLongPressDrag.lease(state, SOURCE, "z".repeat(43), IDENTITY));
        assertNull(HostLongPressDrag.lease(state, SOURCE, TOKEN, "z".repeat(43)));
        state.putString("identity", "bad identity"); assertNull(HostLongPressDrag.lease(state, SOURCE, null, null));
    }

    @Test public void recognitionDeadlineSurvivesHeldPayloadDisposalWithoutRetainingStrongReferences() {
        Object service = new Object(), result = new Object();
        HostLongPressDrag.StageDeadline deadline = new HostLongPressDrag.StageDeadline(service, result, 14_000, 40_000);
        assertEquals(14_000, deadline.forPick(service, result, 20_000));
        assertEquals(0, deadline.forPick(new Object(), result, 20_000));
        assertEquals(0, deadline.forPick(service, result, 40_001));
    }

    @Test public void invalidLeaseTokensCoordinatesAndStatesFailClosed() {
        for (String token : new String[]{"", "a".repeat(42), "a".repeat(44), " ".repeat(43)}) {
            Bundle state = state("holding"); state.putString("token", token);
            assertNull(HostLongPressDrag.lease(state, SOURCE, null, null));
        }
        for (String status : new String[]{"", "other"}) assertNull(HostLongPressDrag.lease(state(status), SOURCE, null, null));
        Bundle invalid = state("holding"); invalid.putFloat("x", Float.NaN);
        assertNull(HostLongPressDrag.lease(invalid, SOURCE, null, null));
        invalid = state("holding"); invalid.putInt("x", 4);
        assertNull(HostLongPressDrag.lease(invalid, SOURCE, null, null));
        invalid = state("holding"); invalid.putLong("recognition_at", invalid.getLong("drag_at") + 1);
        assertNull(HostLongPressDrag.lease(invalid, SOURCE, null, null));
    }

    @Test public void releasedLeaseKeepsTheSystemProbeGraceAfterALongHold() {
        ShadowLooper.idleMainLooper(40, java.util.concurrent.TimeUnit.SECONDS);
        long now = SystemClock.elapsedRealtime();
        Bundle state = state("released");
        state.putLong("native_at", now - 31_000);
        state.putLong("recognition_at", now - 29_000); state.putLong("drag_at", now - 27_000);
        assertNotNull("system-authorized released lease must survive its link grace", HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY));
        state.putString("state", "holding"); assertNull(HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY));
        state.putString("state", "released"); state.putLong("native_at", now - 38_000);
        assertNull(HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY));
    }

    @Test public void ordinaryReleaseHasNoDirectionAndConfiguredDirectionNeedsAnAction() {
        assertNull(HostLongPressDrag.lease(state("released"), SOURCE, null, null).direction());
        Bundle picked = state("released"); picked.putString("direction", "right"); picked.putString("action", "share");
        assertEquals("right", HostLongPressDrag.lease(picked, SOURCE, TOKEN, IDENTITY).direction());
        picked.putString("action", "unknown"); assertNull(HostLongPressDrag.lease(picked, SOURCE, TOKEN, IDENTITY));
        picked.putString("action", "none"); assertEquals("none", HostLongPressDrag.lease(picked, SOURCE, TOKEN, IDENTITY).action());
        picked.putString("action", "share"); picked.putString("direction", "diagonal");
        assertNull(HostLongPressDrag.lease(picked, SOURCE, TOKEN, IDENTITY));
    }

    @Test public void dragDeadlineRequestsTheMaskBeforeDirectionalMovement() {
        Bundle state = state("holding"); state.putFloat("x", 100); state.putFloat("y", 120);
        HostLongPressDrag.Lease lease = HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY);
        assertFalse(lease.dragging());
        assertFalse(HostLongPressDrag.revealEdges(null, false, state.getLong("drag_at")));
        assertFalse(HostLongPressDrag.revealEdges(lease, false, lease.dragAt() - 1));
        assertTrue(HostLongPressDrag.revealEdges(lease, false, lease.dragAt()));
        state.putBoolean("dragging", true);
        assertTrue(HostLongPressDrag.revealEdges(HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY), false,
                state.getLong("drag_at")));
        state.putBoolean("dragging", false);
        assertTrue(HostLongPressDrag.revealEdges(HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY), true,
                state.getLong("drag_at") - 1));
        assertTrue(HostLongPressDrag.shouldRequestOverlay(lease, false, lease.dragAt()));
        assertFalse(HostLongPressDrag.shouldRequestOverlay(lease, true, lease.dragAt()));
        state.putFloat("origin_x", 50); state.putFloat("origin_y", 60);
        HostLongPressDrag.Lease maskLease = HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY);
        assertNotNull(maskLease); assertEquals(50, maskLease.originX(), 0); assertEquals(60, maskLease.originY(), 0);
        assertEquals(100, maskLease.x(), 0); assertEquals(120, maskLease.y(), 0);
    }

    @Test public void aMatchingRecognitionPickAddsItsLinksToTheHeldDragPayload() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        HostLongPressDrag observer = new HostLongPressDrag(null, context.getClassLoader(), (a, b, c, d) -> {});
        NativeResult nativePick = result(), otherPick = result(); Object service = new Object();
        var heldType = Class.forName(HostLongPressDrag.class.getName() + "$Held");
        var constructor = heldType.getDeclaredConstructor(Context.class, Object.class, Object.class,
                String.class, Bitmap.class, HostLongPressDrag.Lease.class);
        constructor.setAccessible(true);
        Object held = constructor.newInstance(context, service, nativePick, "卡片标题", null,
                HostLongPressDrag.lease(state("holding"), SOURCE, TOKEN, IDENTITY));
        Field current = HostLongPressDrag.class.getDeclaredField("current"); current.setAccessible(true); current.set(observer, held);
        observer.offerRecognizedLinks(new Object(), nativePick, List.of("https://wrong.example/"));
        observer.offerRecognizedLinks(service, otherPick, List.of("https://wrong.example/"));
        assertEquals("卡片标题", heldType.getDeclaredField("text").get(held));
        observer.offerRecognizedLinks(service, nativePick, List.of("https://example.com/link", "https://example.com/link"));
        assertEquals("卡片标题\nhttps://example.com/link", heldType.getDeclaredField("text").get(held));
    }

    @Test public void previewWaitsForTouchableLayoutBeforeRequestingTheSystemTouchHandoff() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Object service = new Object();
        HostLongPressDrag observer = new HostLongPressDrag(null, context.getClassLoader(), (a, b, c, d) -> {});
        NativeResult nativePick = result(); var lease = HostLongPressDrag.lease(state("holding"), SOURCE, TOKEN, IDENTITY);
        var heldType = Class.forName(HostLongPressDrag.class.getName() + "$Held");
        var constructor = heldType.getDeclaredConstructor(Context.class, Object.class, Object.class,
                String.class, Bitmap.class, HostLongPressDrag.Lease.class);
        constructor.setAccessible(true);
        Object held = constructor.newInstance(context, service, nativePick, "卡片标题", null, lease);
        Field current = HostLongPressDrag.class.getDeclaredField("current"); current.setAccessible(true); current.set(observer, held);
        var preview = HostLongPressDrag.class.getDeclaredMethod("preview", heldType, HostLongPressDrag.Lease.class);
        preview.setAccessible(true); preview.invoke(observer, held, lease);
        ShadowLooper.idleMainLooper();
        RainbowDragView mask = (RainbowDragView) heldType.getDeclaredField("preview").get(held);
        assertNotNull(mask); assertTrue(mask.isAttachedToWindow());
        ShadowLooper.idleMainLooper(32, java.util.concurrent.TimeUnit.MILLISECONDS);
        mask.getViewTreeObserver().dispatchOnPreDraw();
        assertTrue(heldType.getDeclaredField("transferPending").getBoolean(held));
        assertEquals(0, ((WindowManager.LayoutParams) heldType.getDeclaredField("layout").get(held)).flags
                & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        mask.getViewTreeObserver().dispatchOnPreDraw();
        ShadowLooper.idleMainLooper();
        assertFalse(heldType.getDeclaredField("transferPending").getBoolean(held));
    }

    @Test public void linkProbeDismissesOnlyTheMatchingDragOverlayAfterRelease() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Object service = new Object();
        HostLongPressDrag observer = new HostLongPressDrag(null, context.getClassLoader(), (a, b, c, d) -> {});
        NativeResult nativePick = result(); var lease = HostLongPressDrag.lease(state("holding"), SOURCE, TOKEN, IDENTITY);
        var heldType = Class.forName(HostLongPressDrag.class.getName() + "$Held");
        var constructor = heldType.getDeclaredConstructor(Context.class, Object.class, Object.class,
                String.class, Bitmap.class, HostLongPressDrag.Lease.class);
        constructor.setAccessible(true);
        Object held = constructor.newInstance(context, service, nativePick, "卡片标题", null, lease);
        Field current = HostLongPressDrag.class.getDeclaredField("current"); current.setAccessible(true); current.set(observer, held);
        var preview = HostLongPressDrag.class.getDeclaredMethod("preview", heldType, HostLongPressDrag.Lease.class);
        preview.setAccessible(true); preview.invoke(observer, held, lease); ShadowLooper.idleMainLooper();
        RainbowDragView mask = (RainbowDragView) heldType.getDeclaredField("preview").get(held);
        assertNotNull(mask); assertTrue(mask.isAttachedToWindow());
        heldType.getDeclaredField("maskActive").setBoolean(held, true);

        assertFalse(observer.dismissPreviewForLinkProbe(service, result(), SOURCE));
        assertTrue(mask.isAttachedToWindow());
        assertTrue(observer.dismissPreviewForLinkProbe(service, nativePick, SOURCE));
        assertFalse(mask.isAttachedToWindow());
        assertFalse(heldType.getDeclaredField("maskActive").getBoolean(held));
        assertNull(heldType.getDeclaredField("preview").get(held));
        current.set(observer, null);
    }

    @Test public void failedMaskTransferCanKeepAnAlreadyReleasedDirectionalGesture() {
        Bundle state = state("released"); state.putString("direction", "right"); state.putString("action", "share");
        assertTrue(HostLongPressDrag.canFinishReleasedGesture(HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY)));
        state.putString("action", "none");
        assertFalse(HostLongPressDrag.canFinishReleasedGesture(HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY)));
        state.putString("state", "holding"); state.putString("action", "share");
        assertFalse(HostLongPressDrag.canFinishReleasedGesture(HostLongPressDrag.lease(state, SOURCE, TOKEN, IDENTITY)));
    }

    @Test public void maskBecomesTouchableOnlyForTheSystemTouchHandoff() {
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams();
        layout.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        HostLongPressDrag.prepareMaskForTransfer(layout);
        assertEquals(0, layout.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        assertNotEquals(0, layout.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    @Test public void titleOnlyDragWaitsForTheCardLinkAndAdoptsALaterUrl() {
        assertTrue(HostLongPressDrag.needsCatcherLink("rules", "YouTube"));
        assertTrue(HostLongPressDrag.waitForCardLink("rules", "YouTube", false));
        assertFalse(HostLongPressDrag.waitForCardLink("rules", "YouTube", true));
        assertTrue(HostLongPressDrag.needsCatcherLink("share", "YouTube"));
        assertFalse(HostLongPressDrag.needsCatcherLink("lens", "YouTube"));
        assertFalse(HostLongPressDrag.needsCatcherLink("none", "YouTube"));
        assertTrue(io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag.keepForCardLink(true, false, 500));
        assertFalse(io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag.keepForCardLink(true, true, 500));
        assertFalse(io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag.keepForCardLink(false, false, 500));
        assertTrue(io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag.keepForCardLink(true, false, 2_000));
        assertTrue(io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag.keepForCardLink(true, false, 3_999));
        assertFalse(io.github.yudigaga.taplusbridge.drag.SystemLongPressDrag.keepForCardLink(true, false, 4_000));
        assertFalse(HostLongPressDrag.needsCatcherLink("rules", "https://youtu.be/abc"));
        assertTrue(HostLongPressDrag.adoptRicherText("YouTube", "https://youtu.be/abc"));
        assertEquals("YouTube\nhttps://youtu.be/abc", HostLongPressDrag.withCardLink("YouTube", "https://youtu.be/abc"));
        assertEquals("https://youtu.be/abc", HostLongPressDrag.withCardLink("https://youtu.be/abc", "https://youtu.be/other"));
        assertFalse(HostLongPressDrag.adoptRicherText("https://youtu.be/abc", "https://youtu.be/other"));
        assertFalse(HostLongPressDrag.adoptRicherText("YouTube", "横屏视频"));
    }

    @Test public void importWaitsLongEnoughForAProbeStartedAtTheRecognitionStage() {
        long waitUntil = HostLongPressDrag.linkWaitDeadline(1_000);
        assertEquals(7_500, waitUntil);
        assertTrue(HostLongPressDrag.waitingForCardLink("share", "YouTube", waitUntil, 4_000));
        assertTrue(HostLongPressDrag.waitingForCardLink("share", "YouTube", waitUntil, 7_499));
        assertFalse(HostLongPressDrag.waitingForCardLink("share", "YouTube", waitUntil, 7_500));
        assertFalse(HostLongPressDrag.waitingForCardLink("lens", "YouTube", waitUntil, 4_000));
    }

    @Test public void postReleaseCardLinkFromTheProbeIsIncludedInDragImport() throws Exception {
        ShadowLooper.idleMainLooper(5, java.util.concurrent.TimeUnit.SECONDS);
        Context context = RuntimeEnvironment.getApplication();
        RecordingImportProvider provider = Robolectric.buildContentProvider(RecordingImportProvider.class)
                .create(BridgeContract.AUTHORITY).get();
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);

        HostLongPressDrag observer = new HostLongPressDrag(null, context.getClassLoader(), (a, b, c, d) -> {});
        Object service = new Object(); NativeResult nativePick = result();
        Bundle response = state("released");
        response.putString("direction", "right"); response.putString("action", "share");
        response.putString("card_link", "https://example.com/from-probe");
        long now = SystemClock.elapsedRealtime();
        response.putLong("native_at", now - 2_000);
        response.putLong("recognition_at", now - 1_000);
        response.putLong("drag_at", now - 500);
        HostLongPressDrag.Lease lease = HostLongPressDrag.lease(response, SOURCE, TOKEN, IDENTITY);
        var heldType = Class.forName(HostLongPressDrag.class.getName() + "$Held");
        var constructor = heldType.getDeclaredConstructor(Context.class, Object.class, Object.class,
                String.class, Bitmap.class, HostLongPressDrag.Lease.class);
        constructor.setAccessible(true);
        Object held = constructor.newInstance(context, service, nativePick, "卡片标题", null, lease);
        Field current = HostLongPressDrag.class.getDeclaredField("current"); current.setAccessible(true);
        current.set(observer, held);
        var process = HostLongPressDrag.class.getDeclaredMethod("process", heldType, HostLongPressDrag.Lease.class);
        process.setAccessible(true);
        try {
            process.invoke(observer, held, lease);
            assertEquals("卡片标题\nhttps://example.com/from-probe", provider.importText);
        } finally {
            current.set(observer, null);
        }
    }

    @Test public void screenshotOnlyDragImportsImmediatelyWhenLinkProbeIsDisabled() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Object service = new Object();
        RecordingImportProvider provider = Robolectric.buildContentProvider(RecordingImportProvider.class)
                .create(BridgeContract.AUTHORITY).get();
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
        HostLongPressDrag observer = new HostLongPressDrag(null, null, (a, b, c, d) -> {});
        Bundle response = preparedState(); response.putString("state", "released");
        response.putLong("drag_at", SystemClock.elapsedRealtime()); response.putBoolean("link_probe", false);
        response.putString("direction", "right"); response.putString("action", "share");
        var type = Class.forName(HostLongPressDrag.class.getName() + "$Held");
        var constructor = type.getDeclaredConstructor(Context.class, Object.class, Object.class,
                String.class, Bitmap.class, HostLongPressDrag.Lease.class); constructor.setAccessible(true);
        var lease = HostLongPressDrag.lease(response, SOURCE, TOKEN, IDENTITY);
        Object held = constructor.newInstance(context, service, null, null,
                Bitmap.createBitmap(12, 14, Bitmap.Config.ARGB_8888), lease);
        Field current = HostLongPressDrag.class.getDeclaredField("current"); current.setAccessible(true); current.set(observer, held);
        var process = HostLongPressDrag.class.getDeclaredMethod("process", type, HostLongPressDrag.Lease.class);
        process.setAccessible(true);
        try {
            process.invoke(observer, held, lease);
            assertTrue("screenshot-only mode must not wait for a disabled link probe", provider.importImage);
        } finally { observer.close(service); }
    }

    @Test public void lateTextPickCannotReplaceThePreparedImageDuringDelivery() throws Exception {
        assertMixedImageDelivery(true);
    }

    @Test public void lateTextPickCannotReplaceAnEarlierNativeImageDuringDelivery() throws Exception {
        assertMixedImageDelivery(false);
    }

    private void assertMixedImageDelivery(boolean prepared) throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Object service = new Object();
        RecordingImportProvider provider = Robolectric.buildContentProvider(RecordingImportProvider.class)
                .create(BridgeContract.AUTHORITY).get();
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
        var delivered = new java.util.concurrent.atomic.AtomicReference<Object>();
        var deliveryCount = new java.util.concurrent.atomic.AtomicInteger();
        HostLongPressDrag observer = new HostLongPressDrag(null, null, (a, b, c, result) -> {
            delivered.set(result); deliveryCount.incrementAndGet();
        });
        Bundle response = preparedState(); response.putString("state", "released");
        response.putLong("drag_at", SystemClock.elapsedRealtime());
        response.putString("direction", "right"); response.putString("action", "share");
        response.putString("card_link", "https://example.com/prepared"); installBridge(response);
        var type = Class.forName(HostLongPressDrag.class.getName() + "$Held");
        var constructor = type.getDeclaredConstructor(Context.class, Object.class, Object.class,
                String.class, Bitmap.class, HostLongPressDrag.Lease.class); constructor.setAccessible(true);
        var lease = HostLongPressDrag.lease(response, SOURCE, TOKEN, IDENTITY);
        Object held = constructor.newInstance(context, service, prepared ? null : result(), null,
                Bitmap.createBitmap(12, 14, Bitmap.Config.ARGB_8888), lease);
        Field current = HostLongPressDrag.class.getDeclaredField("current"); current.setAccessible(true); current.set(observer, held);
        NativeResult late = result(); late.jobs.put("text_pick", Map.of("pick_mode", "晚到的原生文字"));
        var adopt = HostLongPressDrag.class.getDeclaredMethod("adoptNativeContent", type, Object.class, Bitmap.class);
        adopt.setAccessible(true); adopt.invoke(observer, held, late, null);
        var process = HostLongPressDrag.class.getDeclaredMethod("process", type, HostLongPressDrag.Lease.class);
        process.setAccessible(true);
        try {
            process.invoke(observer, held, lease); ShadowLooper.idleMainLooper();
            assertTrue(provider.importImage); assertTrue(provider.importText.contains("晚到的原生文字"));
            assertEquals(1, deliveryCount.get());
            assertNull("mixed fallback content must be reconstructed from the imported payload", delivered.get());
        } finally { observer.close(service); installBridge(null); }
    }

    @Test public void snapshotIsIndependentFromTheOriginalNativeImageLifetime() {
        Bitmap original = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888);
        original.eraseColor(0xff123456);
        Bitmap owned = HostLongPressDrag.snapshot(original);
        assertNotSame(original, owned); original.recycle();
        try { assertEquals(0xff123456, owned.getPixel(0, 0)); assertEquals(20, owned.getWidth()); }
        finally { owned.recycle(); }
    }

    @Test public void bitmapExtractionUsesOnlyNativeImageBytesAndNeverImageUrls() throws Exception {
        NativeResult value = result(); Bundle image = new Bundle();
        image.putString("imgUrl", "https://example.org/image.png");
        value.jobs.put("image_pick", Map.of("image_pick_mode", image));
        assertNull(HostLongPressDrag.nativeBitmap(value));
        Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        image.putParcelable("viewBitmap", bitmap); assertSame(bitmap, HostLongPressDrag.nativeBitmap(value));
        bitmap.recycle(); assertNull(HostLongPressDrag.nativeBitmap(value));
    }

    @Test public void imageFileResizesBothEdgeAndPixelBoundsWithoutRecyclingNativeBitmap() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bitmap original = Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888);
        File file = null;
        try {
            file = HostLongPressDrag.writeImage(context, original);
            assertTrue(file.length() > 0 && file.length() <= 8 * 1024 * 1024);
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            assertTrue(options.outWidth <= 2000 && options.outHeight <= 2000);
            assertTrue((long) options.outWidth * options.outHeight <= 3_000_000);
            assertFalse(original.isRecycled()); assertEquals(3000, original.getWidth());
        } finally { original.recycle(); if (file != null) assertTrue(file.delete()); }
    }

    @Test public void outputCapRejectsOverflowBeforeWritingIt() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new HostLongPressDrag.LimitedOutput(bytes, 3)) {
            output.write(new byte[]{1, 2}); output.write(3);
            assertThrows(IOException.class, () -> output.write(4));
            assertThrows(IOException.class, () -> output.write(new byte[]{4, 5}, 0, 2));
        }
        assertArrayEquals(new byte[]{1, 2, 3}, bytes.toByteArray());
    }

    private static Bundle state(String status) {
        Bundle state = new Bundle(); state.putString("token", TOKEN); state.putString("source", SOURCE);
        state.putString("identity", IDENTITY); state.putString("state", status);
        long now = SystemClock.elapsedRealtime(); state.putLong("native_at", now);
        state.putLong("recognition_at", now + 2_000); state.putLong("drag_at", now + 4_000);
        state.putFloat("x", 50); state.putFloat("y", 60);
        state.putFloat("screen_width", 1080); state.putFloat("screen_height", 2400); state.putFloat("density", 1);
        for (String side : io.github.yudigaga.taplusbridge.config.DragSettings.DIRECTIONS) state.putString("action_" + side, "share");
        state.putFloat("origin_x", 50); state.putFloat("origin_y", 60); return state;
    }
    private static NativeResult result() { return new NativeResult(); }
    public static final class RecordingImportProvider extends ContentProvider {
        String importText;
        boolean importImage;
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if (RecognitionChannels.KEY.equals(method)) {
                Bundle policy=new Bundle();policy.putString("identity","a".repeat(64));policy.putBoolean("active",true);
                policy.putString("channels",RecognitionChannels.defaults().toJson());return policy;
            }
            if (!"drag_import".equals(method)) return Bundle.EMPTY;
            importText = extras.getString("text");
            importImage = extras.get("fd") instanceof android.os.ParcelFileDescriptor;
            Bundle response = new Bundle(); response.putString("token", "c".repeat(43)); return response;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
    public record NativeToken(String source, int session) { public String getPkgName() { return source; } }
    public static final class NativeResult {
        NativeToken token = new NativeToken(SOURCE, 1);
        final Map<String, Object> properties = new HashMap<>();
        final Map<String, Map<String, Object>> jobs = new HashMap<>();
        public NativeToken getInjectorToken() { return token; }
        public Map<String, Object> getPropertyMap() { return properties; }
        public Map<String, Object> getParams(String job) { return jobs.get(job); }
    }
}
