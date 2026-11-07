package io.github.yudigaga.taplusbridge.probe;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.WindowManager;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** WMS lease and capture-request tests. The backend is fake; no SurfaceFlinger capture is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class ScopeCaptureWindowAndroidTest {
    private static final String SOURCE = "com.android.chrome";
    private static final String ACTIVITY = "org.chromium.chrome.browser.ChromeTabbedActivity";
    private static final int HOST_UID = 22002;
    private Atms atms;
    private Window main;
    private FakeCapture capture;
    private ScopeCaptureWindow access;

    @Before public void setup() throws Exception { fresh(); }

    private void fresh() throws Exception {
        atms = new Atms(); main = new Window(); main.mActivityRecord.main = main;
        atms.mWindowManager.mRoot.display.hit = main;
        atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        capture = new FakeCapture(atms);
        access = new ScopeCaptureWindow(Atms.class, capture);
    }

    private ScopeCaptureWindow.Snapshot inspect() throws Exception { return access.inspect(atms, 0, 50f, 200f); }

    private Window popup(int type) {
        Window popup = new Window(); popup.mAttrs.type = type; popup.parent = main;
        popup.mActivityRecord = main.mActivityRecord; popup.bounds = new Rect(10, 100, 200, 300);
        main.descendants.add(popup);
        atms.mWindowManager.mRoot.display.mCurrentFocus = popup;
        atms.mWindowManager.mRoot.display.hit = popup;
        return popup;
    }

    private Window hostOverlay(int type) {
        Window overlay = new Window(); overlay.mOwnerUid = HOST_UID;
        overlay.mAttrs.type = type; overlay.mAttrs.packageName = HostPolicy.HOST_PACKAGE;
        overlay.mActivityRecord = null;
        atms.mWindowManager.mRoot.display.mCurrentFocus = overlay;
        atms.mWindowManager.mRoot.display.hit = overlay;
        return overlay;
    }

    @Test public void downCapturesTheActualComponentWithoutRequiringItsClassToStartWithThePackage() throws Exception {
        ScopeCaptureWindow.Snapshot snapshot = inspect();
        assertEquals(SOURCE, snapshot.source); assertEquals(ACTIVITY, snapshot.activity);
        assertEquals(21001, snapshot.uid); assertEquals(0, snapshot.user); assertEquals(0, snapshot.display);
        assertEquals(50f, snapshot.x, 0f); assertEquals(200f, snapshot.y, 0f);
        assertEquals(main.bounds, snapshot.bounds); assertNotSame(main.bounds, snapshot.bounds);
    }

    @Test public void normalCaptureUsesOnlyTheMainSurfaceAndSourceUidOutsideTheWmsLock() throws Exception {
        Bitmap bitmap = access.capture(atms, inspect());
        assertSame(capture.result, bitmap); assertEquals(1, capture.calls);
        assertSame(main.layer, capture.layer); assertEquals(21001, capture.uid);
        assertEquals(new Rect(0, 0, 400, 800), capture.crop);
        assertEquals(1f, capture.scale, 0f); assertEquals(0, capture.excluded.length);
        assertEquals(0, main.mAttrs.flags);
    }

    @Test public void secureMainHiddenMainAndAnUnknownFocusAreRejectedAtDown() throws Exception {
        main.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
        assertThrows(IllegalStateException.class, this::inspect);
        fresh(); main.showing = false;
        assertThrows(IllegalStateException.class, this::inspect);
        fresh(); atms.mWindowManager.mRoot.display.mCurrentFocus = new Window();
        assertThrows(IllegalStateException.class, this::inspect);
        assertEquals(0, capture.calls);
    }

    @Test public void otherUsersSystemUidsAndMismatchedPackagesAreRejectedAtDown() throws Exception {
        main.mOwnerUid = 121001; assertThrows(IllegalStateException.class, this::inspect);
        fresh(); main.mShowUserId = 10; assertThrows(IllegalStateException.class, this::inspect);
        fresh(); main.mActivityRecord.mUserId = 10; assertThrows(IllegalStateException.class, this::inspect);
        fresh(); main.mOwnerUid = 1000; assertThrows(IllegalStateException.class, this::inspect);
        fresh(); main.mAttrs.packageName = "com.example.other"; assertThrows(IllegalStateException.class, this::inspect);
    }

    @Test public void downMustHitANormalMainWindowOnTheDefaultDisplay() throws Exception {
        assertThrows(IllegalStateException.class, () -> access.inspect(atms, 1, 50, 200));
        assertThrows(IllegalStateException.class, () -> access.inspect(atms, 0, Float.NaN, 200));
        assertThrows(IllegalStateException.class, () -> access.inspect(atms, 0, -1, 200));
        assertThrows(IllegalStateException.class, () -> access.inspect(atms, 0, 400, 200));
        main.mAttrs.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD;
        assertThrows(IllegalStateException.class, this::inspect);
        fresh(); popup(1002); assertThrows(IllegalStateException.class, this::inspect);
        fresh(); atms.mWindowManager = null; assertThrows(IllegalStateException.class, this::inspect);
    }

    @Test public void aReplacedMainSurfaceRecordUidOrFramePreventsCapture() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); main.layer = new Object();
        ScopeCaptureWindow.Snapshot first = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, first)); assertEquals(0, capture.calls);
        fresh(); original = inspect(); main.mActivityRecord = new Record(); main.mActivityRecord.main = main;
        ScopeCaptureWindow.Snapshot second = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, second)); assertEquals(0, capture.calls);
        fresh(); original = inspect(); main.mOwnerUid++;
        ScopeCaptureWindow.Snapshot third = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, third)); assertEquals(0, capture.calls);
        fresh(); original = inspect(); main.bounds.bottom--;
        ScopeCaptureWindow.Snapshot fourth = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, fourth)); assertEquals(0, capture.calls);
    }

    @Test public void anActivityChangeBeforeCaptureCannotReuseTheOldWindowLease() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect();
        main.mActivityRecord.mActivityComponent = new ComponentName(SOURCE, "org.chromium.chrome.browser.Other");
        assertThrows(IllegalStateException.class, () -> access.capture(atms, original));
        assertEquals(0, capture.calls);
    }

    @Test public void secureFlagsAndInputMethodFocusAreCheckedAgainAtCapture() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); main.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
        ScopeCaptureWindow.Snapshot first = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, first)); assertEquals(0, capture.calls);
        fresh(); original = inspect(); popup(WindowManager.LayoutParams.TYPE_INPUT_METHOD);
        ScopeCaptureWindow.Snapshot second = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, second)); assertEquals(0, capture.calls);
    }

    @Test public void ownedOrdinaryPopupIsPreservedAndExcludedFromTheCapturedMainLayer() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); Window popup = popup(1002);
        int flags = popup.mAttrs.flags;
        assertSame(capture.result, access.capture(atms, original));
        assertArrayEquals(new Object[]{popup.layer}, capture.excluded);
        assertEquals(flags, popup.mAttrs.flags); assertSame(main, popup.parent);
        assertSame(popup, atms.mWindowManager.mRoot.display.mCurrentFocus);
    }

    @Test public void everyVisibleOwnedPopupIsExcludedEvenIfItIsNotFocusedOrHit() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); Window first = popup(1002), second = popup(1000);
        access.capture(atms, original);
        assertArrayEquals(new Object[]{first.layer, second.layer}, capture.excluded);
    }

    @Test public void crossAppCrossActivitySecureAndDetachedPopupsRejectCapture() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); Window popup = popup(1002); popup.mOwnerUid++;
        ScopeCaptureWindow.Snapshot first = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, first));
        fresh(); original = inspect(); popup = popup(1002); popup.mActivityRecord = new Record();
        ScopeCaptureWindow.Snapshot second = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, second));
        fresh(); original = inspect(); popup = popup(1002); popup.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
        ScopeCaptureWindow.Snapshot third = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, third));
        fresh(); original = inspect(); popup = popup(1002); popup.parent = null;
        assertSame(capture.result, access.capture(atms, original));
        assertTrue(java.util.Arrays.asList(capture.excluded).contains(popup.layer));
        fresh(); original = inspect(); popup = popup(1002); popup.parent = null;
        popup.mAttrs.packageName = "com.example.other";
        ScopeCaptureWindow.Snapshot foreign = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, foreign));
        assertEquals(0, capture.calls);
    }

    @Test public void aNewSurfaceControlWrapperForTheSameLayerStillCaptures() throws Exception {
        main.surfaceGeneration = 42L;
        ScopeCaptureWindow.Snapshot original = inspect();
        assertSame(capture.result, access.capture(atms, original));
        assertEquals(1, capture.calls);
        main.surfaceGeneration = 43L;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, original));
    }

    @Test public void aTargetChangeDuringTheUnlockedCaptureDiscardsAndRecyclesItsBitmap() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect();
        capture.during = () -> main.mActivityRecord.mActivityComponent = new ComponentName(SOURCE, "org.chromium.chrome.browser.Other");
        assertThrows(IllegalStateException.class, () -> access.capture(atms, original));
        assertEquals(1, capture.calls); assertTrue(capture.result.isRecycled());
    }

    @Test public void newlyAppearingPopupDuringCaptureDiscardsTheResultInsteadOfShowingItsPixels() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); capture.during = () -> popup(1002);
        assertThrows(IllegalStateException.class, () -> access.capture(atms, original));
        assertTrue(capture.result.isRecycled());
    }

    @Test public void captureRequestIsScaledToTheBoundedPixelBudget() throws Exception {
        main.bounds = new Rect(0, 0, 1440, 3200);
        access.capture(atms, inspect());
        assertEquals(.5f, capture.scale, 0.0001f);
        assertEquals(new Rect(0, 0, 1440, 3200), capture.crop);
        assertTrue((long) (capture.crop.width() * capture.scale) * (long) (capture.crop.height() * capture.scale)
                <= ScopeCaptureWindow.MAX_PIXELS);
    }

    @Test public void oversizedOrNonSoftwareBuffersAreDiscarded() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); capture.result = Bitmap.createBitmap(1601, 1, Bitmap.Config.ARGB_8888);
        ScopeCaptureWindow.Snapshot first = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, first)); assertTrue(capture.result.isRecycled());
        fresh(); original = inspect(); capture.result = Bitmap.createBitmap(10, 10, Bitmap.Config.ALPHA_8);
        ScopeCaptureWindow.Snapshot second = original;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, second)); assertTrue(capture.result.isRecycled());
    }

    @Test public void callersCannotChangeTheCaptureCropByEditingThePublicBoundsCopy() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); original.bounds.set(0, 0, 10000, 10000);
        assertThrows(IllegalStateException.class, () -> access.capture(atms, original)); assertEquals(0, capture.calls);
    }

    @Test public void aWaitingTransferCanRevalidateItsTargetWithoutCapturingAgain() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); access.validate(atms, original);
        assertEquals(0, capture.calls);
        main.mActivityRecord.mActivityComponent = new ComponentName(SOURCE, "org.chromium.chrome.browser.Other");
        assertThrows(IllegalStateException.class, () -> access.validate(atms, original));
        assertEquals(0, capture.calls);
    }

    @Test public void onlyTheSpecialValidationAcceptsTheVerifiedNativeHostOverlay() throws Exception {
        for (int type : new int[]{2038, 2002}) {
            fresh(); ScopeCaptureWindow.Snapshot original = inspect(); hostOverlay(type);
            assertThrows(IllegalStateException.class, () -> access.validate(atms, original));
            assertThrows(IllegalStateException.class, () -> access.capture(atms, original));
            access.validateBehindHost(atms, original, HOST_UID);
            assertEquals(0, capture.calls);
        }
    }

    private Bitmap captureBehindHost(ScopeCaptureWindow.Snapshot snapshot, int uid) throws Exception {
        java.lang.reflect.Method method;
        try { method = ScopeCaptureWindow.class.getMethod("captureBehindHost", Object.class, ScopeCaptureWindow.Snapshot.class, int.class); }
        catch (NoSuchMethodException missing) { throw new AssertionError("A verified host overlay must allow the source-only capture", missing); }
        try { return (Bitmap) method.invoke(access, atms, snapshot, uid); }
        catch (java.lang.reflect.InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            throw wrapped;
        }
    }

    @Test public void dragFallbackCapturesOnlyTheOriginalLayerBehindAVerifiedHostOverlay() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); hostOverlay(2038);
        assertSame(capture.result, captureBehindHost(original, HOST_UID));
        assertSame(main.layer, capture.layer); assertEquals(21001, capture.uid); assertEquals(1, capture.calls);
    }

    @Test public void dragFallbackRetainsSecureImeForeignOverlayAndOwnerChecks() throws Exception {
        for (int invalid = 0; invalid < 5; invalid++) {
            fresh(); ScopeCaptureWindow.Snapshot original = inspect(); Window overlay = hostOverlay(2038);
            int uid = HOST_UID;
            if (invalid == 0) main.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
            if (invalid == 1) overlay.mAttrs.packageName = "com.example.other";
            if (invalid == 2) overlay.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
            if (invalid == 3) {
                Window ime = new Window(); ime.mAttrs.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD;
                atms.mWindowManager.mRoot.display.additionalWindows.add(ime);
            }
            if (invalid == 4) uid = 1000;
            final int checkedUid = uid;
            assertThrows(IllegalStateException.class, () -> captureBehindHost(original, checkedUid));
            assertEquals(0, capture.calls);
        }
    }

    @Test public void hostUidPackageTypeVisibilityAndSecurityMustAllMatch() throws Exception {
        for (int invalid = 0; invalid < 8; invalid++) {
            fresh(); ScopeCaptureWindow.Snapshot original = inspect(); Window overlay = hostOverlay(2038);
            if (invalid == 0) overlay.mOwnerUid++;
            if (invalid == 1) overlay.mAttrs.packageName = "com.example.impostor";
            if (invalid == 2) overlay.mAttrs.type = WindowManager.LayoutParams.TYPE_APPLICATION;
            if (invalid == 3) overlay.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
            if (invalid == 4) overlay.showing = false;
            if (invalid == 5) overlay.mShowUserId = 10;
            if (invalid == 6) overlay.mOwnerUid = 1000;
            if (invalid == 7) overlay.mOwnerUid = 122002;
            assertThrows(IllegalStateException.class, () -> access.validateBehindHost(atms, original, HOST_UID));
            assertEquals(0, capture.calls);
        }
        fresh(); ScopeCaptureWindow.Snapshot original = inspect(); hostOverlay(2038);
        for (int invalidUid : new int[]{-1, 1000, 122002})
            assertThrows(IllegalStateException.class, () -> access.validateBehindHost(atms, original, invalidUid));
    }

    @Test public void bothFocusedAndHitWindowsMustBeTrustedOrBelongToTheOriginalActivity() throws Exception {
        for (boolean invalidFocus : new boolean[]{false, true}) {
            fresh(); ScopeCaptureWindow.Snapshot original = inspect(); Window host = hostOverlay(2038);
            Window other = new Window(); other.mOwnerUid = HOST_UID + 1;
            if (invalidFocus) atms.mWindowManager.mRoot.display.mCurrentFocus = other;
            else atms.mWindowManager.mRoot.display.hit = other;
            assertThrows(IllegalStateException.class, () -> access.validateBehindHost(atms, original, HOST_UID));
            if (invalidFocus) atms.mWindowManager.mRoot.display.mCurrentFocus = main;
            else atms.mWindowManager.mRoot.display.hit = main;
            access.validateBehindHost(atms, original, HOST_UID);
            assertTrue(host.showing); assertEquals(0, capture.calls);
        }
    }

    @Test public void aHostOverlayNeverPermitsAnImeOrAChangedSourceSnapshot() throws Exception {
        for (int invalid = 0; invalid < 8; invalid++) {
            fresh(); ScopeCaptureWindow.Snapshot original = inspect(); hostOverlay(2038);
            if (invalid == 0) {
                Window ime = new Window(); ime.mAttrs.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD;
                atms.mWindowManager.mRoot.display.additionalWindows.add(ime);
            }
            if (invalid == 1) main.mActivityRecord.mActivityComponent = new ComponentName(SOURCE, "org.chromium.chrome.browser.Other");
            if (invalid == 2) main.mOwnerUid++;
            if (invalid == 3) main.layer = new Object();
            if (invalid == 4) main.bounds.bottom--;
            if (invalid == 5) main.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
            if (invalid == 6) main.showing = false;
            if (invalid == 7) original.bounds.bottom--;
            assertThrows(IllegalStateException.class, () -> access.validateBehindHost(atms, original, HOST_UID));
            assertEquals(0, capture.calls);
        }
    }

    @Test public void activeTouchTransfersOnlyToARegisteredTouchableFullscreenHostMask() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); Window mask = hostOverlay(2038);
        mask.mAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.setTitle("TaplusBridge:DragMask:" + "a".repeat(43));
        String title = mask.mAttrs.getTitle().toString();
        assertTrue(access.transferTouchToMask(atms, original, HOST_UID, title));
        assertSame(main.mInputChannel, atms.mWindowManager.mInputManager.from);
        assertSame(mask.mInputChannel, atms.mWindowManager.mInputManager.to);
        assertEquals(1, atms.mWindowManager.mInputManager.calls);
    }

    @Test public void wrongOrUntouchableMaskNeverReceivesTheActiveTouchStream() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); Window mask = hostOverlay(2038);
        mask.mAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.setTitle("TaplusBridge:DragMask:" + "a".repeat(43));
        mask.mAttrs.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        assertEquals("drag_mask_not_touchable", assertThrows(IllegalStateException.class,
                () -> access.transferTouchToMask(atms, original, HOST_UID, mask.mAttrs.getTitle().toString())).getMessage());
        assertEquals(0, atms.mWindowManager.mInputManager.calls);
    }

    @Test public void inactiveSourceGestureReportsTransferFailureForSafeFallback() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); Window mask = hostOverlay(2038);
        mask.mAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.setTitle("TaplusBridge:DragMask:" + "a".repeat(43));
        atms.mWindowManager.mInputManager.result = false;
        assertFalse(access.transferTouchToMask(atms, original, HOST_UID, mask.mAttrs.getTitle().toString()));
        assertEquals(1, atms.mWindowManager.mInputManager.calls);
    }

    @Test public void rejectedInputTransferReturnsAFixedDiagnosticReason() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); Window mask = hostOverlay(2038);
        mask.mAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.setTitle("TaplusBridge:DragMask:" + "a".repeat(43));
        atms.mWindowManager.mInputManager.result = false;

        ScopeCaptureWindow.TransferResult result = access.transferTouchToMaskDetailed(
                atms, original, HOST_UID, mask.mAttrs.getTitle().toString());

        assertFalse(result.transferred);
        assertEquals("input_transfer_rejected", result.reason);
        assertEquals(1, atms.mWindowManager.mInputManager.calls);
    }

    @Test public void invalidMaskReturnsItsFixedPreflightReason() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); Window mask = hostOverlay(2038);
        mask.mAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.setTitle("TaplusBridge:DragMask:" + "a".repeat(43));
        mask.mAttrs.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        ScopeCaptureWindow.TransferResult result = access.transferTouchToMaskDetailed(
                atms, original, HOST_UID, mask.mAttrs.getTitle().toString());

        assertFalse(result.transferred);
        assertEquals("drag_mask_not_touchable", result.reason);
        assertEquals(0, atms.mWindowManager.mInputManager.calls);
    }

    @Test public void maskPreflightReturnsTheSpecificFailedWindowConstraint() throws Exception {
        for (int invalid = 0; invalid < 9; invalid++) {
            fresh(); ScopeCaptureWindow.Snapshot original = inspect(); Window mask = hostOverlay(2038);
            mask.mAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
            mask.mAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
            mask.mAttrs.setTitle("TaplusBridge:DragMask:" + "a".repeat(43));
            atms.mWindowManager.mRoot.display.hit = main;
            atms.mWindowManager.mRoot.display.mCurrentFocus = main;
            atms.mWindowManager.mRoot.display.additionalWindows.add(mask);
            String expected = switch (invalid) {
                case 0 -> "drag_mask_not_unique";
                case 1 -> "drag_mask_owner_uid";
                case 2 -> "drag_mask_user";
                case 3 -> "drag_mask_package";
                case 4 -> "drag_mask_type";
                case 5 -> "drag_mask_dimensions";
                case 6 -> "drag_mask_not_touchable";
                case 7 -> "drag_mask_not_visible";
                default -> "drag_mask_bounds";
            };
            switch (invalid) {
                case 0 -> {
                    Window duplicate = new Window(); duplicate.mOwnerUid = HOST_UID;
                    duplicate.mAttrs.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                    duplicate.mAttrs.packageName = HostPolicy.HOST_PACKAGE;
                    duplicate.mAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
                    duplicate.mAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
                    duplicate.mAttrs.setTitle(mask.mAttrs.getTitle());
                    duplicate.mActivityRecord = null;
                    atms.mWindowManager.mRoot.display.additionalWindows.add(duplicate);
                }
                case 1 -> mask.mOwnerUid++;
                case 2 -> mask.mShowUserId++;
                case 3 -> mask.mAttrs.packageName = "com.example.other";
                case 4 -> mask.mAttrs.type = 2003;
                case 5 -> mask.mAttrs.height = 100;
                case 6 -> mask.mAttrs.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                case 7 -> mask.showing = false;
                case 8 -> mask.bounds = new Rect(100, 300, 400, 600);
            }

            ScopeCaptureWindow.TransferResult result = access.transferTouchToMaskDetailed(
                    atms, original, HOST_UID, mask.mAttrs.getTitle().toString());

            assertFalse("case=" + invalid, result.transferred);
            assertEquals("case=" + invalid, expected, result.reason);
            assertEquals(0, atms.mWindowManager.mInputManager.calls);
        }
    }

    @Test public void missingInputTransferApiHasAnExplicitReason() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); Window mask = hostOverlay(2038);
        mask.mAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.setTitle("TaplusBridge:DragMask:" + "a".repeat(43));
        atms.mWindowManager.mInputManager = null;

        ScopeCaptureWindow.TransferResult result = access.transferTouchToMaskDetailed(
                atms, original, HOST_UID, mask.mAttrs.getTitle().toString());

        assertFalse(result.transferred);
        assertEquals("input_api_missing", result.reason);
    }

    @Test public void inputTransferExceptionNeverExposesTheExceptionMessage() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect(); Window mask = hostOverlay(2038);
        mask.mAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.setTitle("TaplusBridge:DragMask:" + "a".repeat(43));
        atms.mWindowManager.mInputManager.failure = new SecurityException("sensitive-window-detail");

        ScopeCaptureWindow.TransferResult result = access.transferTouchToMaskDetailed(
                atms, original, HOST_UID, mask.mAttrs.getTitle().toString());

        assertFalse(result.transferred);
        assertEquals("input_transfer_exception", result.reason);
        assertFalse(result.reason.contains("sensitive-window-detail"));
    }

    @Test public void windowFailureDiagnosticsKeepOnlyKnownCodes() {
        assertEquals("capture_focus", ScopeCaptureWindow.diagnosticWindowReason("capture_focus"));
        assertEquals("capture_hidden", ScopeCaptureWindow.diagnosticWindowReason("capture_hidden"));
        assertEquals("capture_bounds", ScopeCaptureWindow.diagnosticWindowReason("capture_bounds"));
        assertEquals("window_rejected", ScopeCaptureWindow.diagnosticWindowReason("sensitive-window-detail"));
    }

    @Test public void absentOrNoLongerValidMainSurfacesCannotBeCaptured() throws Exception {
        main.layer = null; assertThrows(IllegalStateException.class, this::inspect);
        fresh(); ScopeCaptureWindow.Snapshot original = inspect(); capture.surfaceValid = false;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, original));
        assertEquals(0, capture.calls);
    }

    @Test public void aVisibleImeRejectsDownAndTransferEvenWhenTheMainWindowKeepsFocus() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect();
        Window ime = new Window(); ime.mAttrs.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD;
        ime.mAttrs.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        atms.mWindowManager.mRoot.display.additionalWindows.add(ime);
        assertSame(main, atms.mWindowManager.mRoot.display.mCurrentFocus);
        assertEquals("capture_ime", assertThrows(IllegalStateException.class, this::inspect).getMessage());
        assertEquals("capture_ime", assertThrows(IllegalStateException.class,
                () -> access.validate(atms, original)).getMessage());
        assertThrows(IllegalStateException.class, () -> access.capture(atms, original));
        assertEquals(0, capture.calls);
        ime.mAttrs.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
        assertEquals("capture_ime", assertThrows(IllegalStateException.class, this::inspect).getMessage());
    }

    @Test public void anInvisibleImeWindowDoesNotBlockTheVerifiedMainWindow() throws Exception {
        Window ime = new Window(); ime.mAttrs.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD;
        ime.showing = false; atms.mWindowManager.mRoot.display.additionalWindows.add(ime);
        ScopeCaptureWindow.Snapshot original = inspect(); access.validate(atms, original);
        assertSame(capture.result, access.capture(atms, original));
        assertEquals(1, capture.calls); assertSame(main, atms.mWindowManager.mRoot.display.mCurrentFocus);
    }

    @Test public void aSameActivityOrdinaryDialogAfterDownLeavesOnlyTheOriginalMainLayerInTheCapture() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect();
        Window dialog = new Window(); dialog.mAttrs.type = WindowManager.LayoutParams.TYPE_APPLICATION;
        dialog.mActivityRecord = main.mActivityRecord;
        atms.mWindowManager.mRoot.display.mCurrentFocus = dialog;
        atms.mWindowManager.mRoot.display.hit = dialog;
        atms.mWindowManager.mRoot.display.additionalWindows.add(main);
        access.validate(atms, original);
        assertSame(capture.result, access.capture(atms, original));
        assertSame(main.layer, capture.layer); assertNotSame(dialog.layer, capture.layer);
        assertEquals(0, capture.excluded.length); assertNull(dialog.parent);
        assertSame(dialog, atms.mWindowManager.mRoot.display.mCurrentFocus);
        assertEquals(0, dialog.mAttrs.flags);
        assertThrows(IllegalStateException.class, this::inspect);
    }

    @Test public void secureOrDifferentActivitySiblingDialogsNeverAuthorizeCaptureOfTheOldMainLayer() throws Exception {
        ScopeCaptureWindow.Snapshot original = inspect();
        Window dialog = new Window(); dialog.mAttrs.type = WindowManager.LayoutParams.TYPE_APPLICATION;
        dialog.mActivityRecord = main.mActivityRecord; dialog.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
        atms.mWindowManager.mRoot.display.mCurrentFocus = dialog; atms.mWindowManager.mRoot.display.hit = dialog;
        assertThrows(IllegalStateException.class, () -> access.capture(atms, original));
        assertEquals(0, capture.calls);
        dialog.mAttrs.flags = 0; dialog.mActivityRecord = new Record();
        assertThrows(IllegalStateException.class, () -> access.capture(atms, original));
        assertEquals(0, capture.calls);
    }

    private static final class FakeCapture implements ScopeCaptureWindow.CaptureBackend {
        final Atms atms;
        Bitmap result = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888);
        int calls, uid; float scale; Object layer; Object[] excluded; Rect crop; boolean surfaceValid = true;
        Runnable during = () -> {};
        FakeCapture(Atms atms) { this.atms = atms; }
        @Override public Class<?> surfaceType() { return Object.class; }
        @Override public boolean validSurface(Object layer) { return surfaceValid && layer != null; }
        @Override public Bitmap capture(Object layer, Object[] excluded, int uid, Rect crop, float scale) {
            assertFalse(Thread.holdsLock(atms.mWindowManager.mGlobalLock));
            calls++; this.layer = layer; this.excluded = excluded; this.uid = uid; this.crop = new Rect(crop); this.scale = scale;
            during.run(); return result;
        }
    }

    static final class Atms { Wms mWindowManager = new Wms(); }
    static final class Wms {
        final Object mGlobalLock = new Object(); final Root mRoot = new Root();
        FakeInputManager mInputManager = new FakeInputManager();
    }
    static final class FakeInputManager {
        FakeInputChannel from, to; int calls; boolean result = true; RuntimeException failure;
        boolean transferTouchFocus(FakeInputChannel from, FakeInputChannel to) {
            if (failure != null) throw failure;
            this.from = from; this.to = to; calls++; return result;
        }
    }
    static final class FakeInputChannel { }
    static final class Root { final Display display = new Display(); Display getDisplayContent(int id) { return id == 0 ? display : null; } }
    static final class Display {
        Window hit, mCurrentFocus;
        final List<Window> additionalWindows = new ArrayList<>();
        Window getTouchableWinAtPointLocked(float x, float y) { return hit; }
        void forAllWindows(Consumer<Window> action, boolean topToBottom) {
            if (hit != null) hit.forAllWindows(action, topToBottom);
            if (mCurrentFocus != null && mCurrentFocus != hit) mCurrentFocus.forAllWindows(action, topToBottom);
            for (Window window : additionalWindows) action.accept(window);
        }
    }
    static final class Window {
        int mOwnerUid = 21001, mShowUserId = 0;
        final FakeInputChannel mInputChannel = new FakeInputChannel();
        final WindowManager.LayoutParams mAttrs = new WindowManager.LayoutParams();
        Record mActivityRecord = new Record(); Window parent; Rect bounds = new Rect(0, 0, 400, 800);
        Object layer = new Object(); boolean showing = true; final List<Window> descendants = new ArrayList<>();
        long surfaceGeneration;
        Window() { mAttrs.type = 1; mAttrs.packageName = SOURCE; mAttrs.flags = 0; }
        Rect getFrame() { return bounds; } Window getParentWindow() { return parent; }
        boolean isVisible() { return showing; }
        Object getSurfaceControl() { return surfaceGeneration == 0 ? layer : new SurfaceToken(surfaceGeneration); }
        static final class SurfaceToken { final long mNativeObject; SurfaceToken(long id) { mNativeObject = id; } }
        void forAllWindows(Consumer<Window> action, boolean topToBottom) { action.accept(this); for (Window child : descendants) action.accept(child); }
    }
    static final class Record {
        ComponentName mActivityComponent = new ComponentName(SOURCE, ACTIVITY); int mUserId = 0;
        Window main; Window findMainWindow(boolean ignored) { return main; }
    }
}
