package io.github.yudigaga.taplusbridge.probe;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.WindowManager;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
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

    private Window dialog() {
        Window dialog = new Window(); dialog.mAttrs.type = WindowManager.LayoutParams.TYPE_APPLICATION;
        dialog.mActivityRecord = main.mActivityRecord; dialog.bounds = new Rect(10, 100, 200, 300);
        atms.mWindowManager.mRoot.display.mCurrentFocus = dialog;
        atms.mWindowManager.mRoot.display.hit = dialog;
        atms.mWindowManager.mRoot.display.additionalWindows.add(main);
        return dialog;
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

    @Test public void downMustHitAnOwnedApplicationWindowOnTheDefaultDisplay() throws Exception {
        assertThrows(IllegalStateException.class, () -> access.inspect(atms, 1, 50, 200));
        assertThrows(IllegalStateException.class, () -> access.inspect(atms, 0, Float.NaN, 200));
        assertThrows(IllegalStateException.class, () -> access.inspect(atms, 0, -1, 200));
        assertThrows(IllegalStateException.class, () -> access.inspect(atms, 0, 400, 200));
        main.mAttrs.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD;
        assertThrows(IllegalStateException.class, this::inspect);
        fresh(); popup(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        assertThrows(IllegalStateException.class, this::inspect);
        fresh(); atms.mWindowManager = null; assertThrows(IllegalStateException.class, this::inspect);
    }

    @Test public void downInsideAnExistingDialogCapturesItsOwnBoundedSurface() throws Exception {
        Window dialog = dialog();
        ScopeCaptureWindow.Snapshot original = inspect();
        assertEquals(new Rect(10, 100, 200, 300), original.bounds);
        assertSame(capture.result, access.capture(atms, original));
        assertSame(dialog.layer, capture.layer); assertNotSame(main.layer, capture.layer);
        assertEquals(new Rect(0, 0, 190, 200), capture.crop); assertEquals(21001, capture.uid);
        assertSame(dialog, atms.mWindowManager.mRoot.display.mCurrentFocus);
        assertEquals(0, dialog.mAttrs.flags);
    }

    @Test public void downInsideAnExistingPopupCapturesItsOwnBoundedSurface() throws Exception {
        Window popup = popup(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
        ScopeCaptureWindow.Snapshot original = inspect();
        assertEquals(new Rect(10, 100, 200, 300), original.bounds);
        assertSame(capture.result, access.capture(atms, original));
        assertSame(popup.layer, capture.layer); assertNotSame(main.layer, capture.layer);
        assertEquals(new Rect(0, 0, 190, 200), capture.crop); assertEquals(21001, capture.uid);
        assertSame(main, popup.parent); assertEquals(0, capture.excluded.length);
    }

    @Test public void downOnMainBehindAnOwnedDialogCapturesTheMainSurface() throws Exception {
        Window dialog = dialog(); dialog.bounds = new Rect(10, 300, 200, 600);
        atms.mWindowManager.mRoot.display.hit = main;
        ScopeCaptureWindow.Snapshot original = inspect();
        assertSame(capture.result, access.capture(atms, original));
        assertSame(main.layer, capture.layer); assertEquals(new Rect(0, 0, 400, 800), capture.crop);
        assertSame(dialog, atms.mWindowManager.mRoot.display.mCurrentFocus);
    }

    @Test public void downInsideANonFocusablePopupKeepsTheMainActivityFocus() throws Exception {
        Window popup = popup(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
        popup.mAttrs.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        ScopeCaptureWindow.Snapshot original = inspect();
        assertSame(capture.result, access.capture(atms, original));
        assertSame(popup.layer, capture.layer); assertSame(main, atms.mWindowManager.mRoot.display.mCurrentFocus);
        assertEquals(0, capture.excluded.length);
    }

    @Test public void existingPopupAndDialogStillRequireOwnedSafeActivityWindowsAtDown() throws Exception {
        for (boolean sibling : new boolean[]{false, true}) for (int invalid = 0; invalid < 12; invalid++) {
            fresh(); Window target = sibling ? dialog() : popup(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
            switch (invalid) {
                case 0 -> target.mOwnerUid++;
                case 1 -> target.mShowUserId = 10;
                case 2 -> target.mAttrs.packageName = "com.example.other";
                case 3 -> target.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
                case 4 -> target.showing = false;
                case 5 -> target.mActivityRecord = new Record();
                case 6 -> main.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
                case 7 -> main.showing = false;
                case 8 -> main.mOwnerUid++;
                case 9 -> {
                    Window ime = new Window(); ime.mAttrs.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD;
                    atms.mWindowManager.mRoot.display.additionalWindows.add(ime);
                }
                case 10 -> target.mAttrs.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                case 11 -> target.parent = new Window();
            }
            assertThrows("sibling=" + sibling + " case=" + invalid, IllegalStateException.class, this::inspect);
            assertEquals(0, capture.calls);
        }
    }

    @Test public void aLeasedPopupOrDialogCannotOutliveItsMainActivityWindow() throws Exception {
        for (boolean sibling : new boolean[]{false, true}) for (int invalid = 0; invalid < 8; invalid++) {
            fresh(); Window target = sibling ? dialog() : popup(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
            ScopeCaptureWindow.Snapshot original = inspect();
            switch (invalid) {
                case 0 -> main.mActivityRecord.main = new Window();
                case 1 -> main.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
                case 2 -> main.showing = false;
                case 3 -> target.mOwnerUid++;
                case 4 -> target.layer = new Object();
                case 5 -> target.bounds.bottom--;
                case 6 -> target.parent = new Window();
                case 7 -> target.mAttrs.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            }
            assertThrows("sibling=" + sibling + " case=" + invalid, IllegalStateException.class,
                    () -> access.capture(atms, original));
            assertEquals(0, capture.calls);
        }
    }

    @Test public void popupTouchTransfersFromTheActualHitWindowInsteadOfItsActivityMainWindow() throws Exception {
        Window popup = popup(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
        ScopeCaptureWindow.Snapshot original = inspect(); Window mask = hostOverlay(2038);
        mask.mAttrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.height = WindowManager.LayoutParams.MATCH_PARENT;
        mask.mAttrs.setTitle("TaplusBridge:DragMask:" + "a".repeat(43));
        assertTrue(access.transferTouchToMask(atms, original, HOST_UID, mask.mAttrs.getTitle().toString()));
        assertSame(popup.mInputChannel, atms.mWindowManager.mInputManager.from);
        assertSame(mask.mInputChannel, atms.mWindowManager.mInputManager.to);
    }

    private SourceWindowAccess.CardPlan planCardTap(ScopeCaptureWindow.Snapshot target) throws Exception {
        SourceWindowAccess clicks = new SourceWindowAccess(Atms.class);
        java.lang.reflect.Method method;
        try { method = SourceWindowAccess.class.getMethod("planCardTap", Object.class,
                ScopeCaptureWindow.Snapshot.class, int.class); }
        catch (NoSuchMethodException missing) {
            throw new AssertionError("A card tap must retain its original captured application window", missing);
        }
        try { return (SourceWindowAccess.CardPlan) method.invoke(clicks, atms, target, HOST_UID); }
        catch (java.lang.reflect.InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            throw wrapped;
        }
    }

    @Test public void aCardTapInsideItsOriginalDialogOrPopupDoesNotDismissTheCapturedWindow() throws Exception {
        for (boolean sibling : new boolean[]{false, true}) {
            fresh(); Window original = sibling ? dialog() : popup(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
            ScopeCaptureWindow.Snapshot target = inspect();
            access.validateBehindHost(atms, target, HOST_UID);
            SourceWindowAccess.CardPlan plan = planCardTap(target);
            assertFalse("sibling=" + sibling, plan.dismiss());
            assertSame(original, atms.mWindowManager.mRoot.display.hit);
            assertSame(original, atms.mWindowManager.mRoot.display.mCurrentFocus);
        }
    }

    @Test public void aNewPopupStillRequiresDismissalWhenTheOriginalMainWindowIsHit() throws Exception {
        ScopeCaptureWindow.Snapshot target = inspect();
        popup(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL).bounds = new Rect(10, 300, 200, 600);
        atms.mWindowManager.mRoot.display.hit = main;
        access.validateBehindHost(atms, target, HOST_UID);
        SourceWindowAccess.CardPlan plan = planCardTap(target);
        assertTrue(plan.dismiss()); assertFalse(plan.popupCovered());
        assertEquals(50f, plan.dismissX(), 0f); assertEquals(200f, plan.dismissY(), 0f);
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
        assertEquals(SOURCE, inspect().source);
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

    private String dismissRecognitionMenu(ScopeCaptureWindow.Snapshot target, BooleanSupplier current) throws Exception {
        java.lang.reflect.Method method;
        try { method = ScopeCaptureWindow.class.getMethod("dismissRecognitionMenu", Object.class,
                ScopeCaptureWindow.Snapshot.class, int.class, BooleanSupplier.class); }
        catch (NoSuchMethodException missing) {
            throw new AssertionError("Recognition must close only its newly opened source menu", missing);
        }
        try { return (String) method.invoke(access, atms, target, HOST_UID, current); }
        catch (java.lang.reflect.InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            throw wrapped;
        }
    }

    private String dismissRecognitionMenu(ScopeCaptureWindow.Snapshot target, BooleanSupplier current,
                                          BooleanSupplier beforeDispatch) throws Exception {
        java.lang.reflect.Method method;
        try { method = ScopeCaptureWindow.class.getMethod("dismissRecognitionMenu", Object.class,
                ScopeCaptureWindow.Snapshot.class, int.class, BooleanSupplier.class, BooleanSupplier.class); }
        catch (NoSuchMethodException missing) {
            throw new AssertionError("Only an actual menu dispatch may arm recognition retention", missing);
        }
        try { return (String) method.invoke(access, atms, target, HOST_UID, current, beforeDispatch); }
        catch (java.lang.reflect.InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            throw wrapped;
        }
    }

    private boolean recognitionSourceCurrent(ScopeCaptureWindow.Snapshot target) throws Exception {
        java.lang.reflect.Method method;
        try { method = ScopeCaptureWindow.class.getMethod("recognitionSourceCurrent", Object.class,
                ScopeCaptureWindow.Snapshot.class, int.class); }
        catch (NoSuchMethodException missing) {
            throw new AssertionError("Retention must validate the original source window without invoking Back", missing);
        }
        try { return (Boolean) method.invoke(access, atms, target, HOST_UID); }
        catch (java.lang.reflect.InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            throw wrapped;
        }
    }

    private FakeBackCallback backCallback(Window window) {
        FakeBackCallback callback = new FakeBackCallback(atms.mWindowManager);
        window.backInfo = new FakeBackInfo(callback); return callback;
    }

    @Test public void recognitionClosesOnlyANewDialogOrPopupAndInvokesItsCallbackOutsideTheWmsLock() throws Exception {
        for (boolean sibling : new boolean[]{false, true}) {
            fresh(); ScopeCaptureWindow.Snapshot target = inspect();
            Window menu = sibling ? dialog() : popup(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
            FakeBackCallback callback = backCallback(menu);
            assertEquals("sent", dismissRecognitionMenu(target, () -> true));
            assertEquals(1, callback.calls); assertEquals(0, capture.calls);
            assertEquals(0, atms.mWindowManager.mInputManager.calls);
        }
    }

    @Test public void platformOverlayPriorityCanDismissItsOwnNewDialogWithoutCallingTheMainCallback() throws Exception {
        for (int priority : new int[]{-1, 0, 1_000_000}) {
            fresh(); FakeBackCallback mainCallback = backCallback(main); ScopeCaptureWindow.Snapshot target = inspect();
            Window menu = dialog(); FakeBackCallback callback = backCallback(menu); menu.backInfo.priority = priority;
            assertEquals("priority=" + priority, "sent", dismissRecognitionMenu(target, () -> true));
            assertEquals(1, callback.calls); assertEquals(0, mainCallback.calls);
        }
    }

    @Test public void unverifiedBackCallbackPrioritiesRemainUnsupported() throws Exception {
        for (int priority : new int[]{-2, 1, 1_000_001}) {
            fresh(); ScopeCaptureWindow.Snapshot target = inspect(); Window menu = dialog();
            FakeBackCallback callback = backCallback(menu); menu.backInfo.priority = priority;
            assertEquals("priority=" + priority, "unsupported", dismissRecognitionMenu(target, () -> true));
            assertEquals(0, callback.calls);
        }
    }

    @Test public void beforeDispatchRunsOnceOutsideTheWmsLockImmediatelyBeforeTheMenuCallback() throws Exception {
        ScopeCaptureWindow.Snapshot target = inspect(); FakeBackCallback callback = backCallback(dialog()); int[] before = {0};
        assertEquals("sent", dismissRecognitionMenu(target, () -> true, () -> {
            assertFalse(Thread.holdsLock(atms.mWindowManager.mGlobalLock));
            assertEquals(0, callback.calls); before[0]++; return true;
        }));
        assertEquals(1, before[0]); assertEquals(1, callback.calls);
    }

    @Test public void aRejectedBeforeDispatchNeverInvokesTheSourceMenuCallback() throws Exception {
        ScopeCaptureWindow.Snapshot target = inspect(); FakeBackCallback callback = backCallback(dialog()); int[] before = {0};
        assertEquals("unsupported", dismissRecognitionMenu(target, () -> true, () -> { before[0]++; return false; }));
        assertEquals(1, before[0]); assertEquals(0, callback.calls);
    }

    @Test public void aMissingOrRejectedMenuNeverArmsRecognitionRetention() throws Exception {
        for (int failure = 0; failure < 5; failure++) {
            fresh(); ScopeCaptureWindow.Snapshot target = inspect(); int[] before = {0};
            FakeBackCallback callback = failure == 0 ? backCallback(main) : backCallback(dialog());
            if (failure == 2) atms.mWindowManager.mRoot.display.mCurrentFocus.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
            if (failure == 3) backCallback(main).binder = callback.binder;
            if (failure == 4) main.mActivityRecord.mActivityComponent = new ComponentName(SOURCE, SOURCE + ".OtherActivity");
            boolean current = failure != 1;
            String expected = switch (failure) {
                case 0 -> "none";
                case 1 -> "stale";
                case 2 -> "unsupported";
                case 3 -> "ambiguous";
                default -> "changed";
            };
            assertEquals(expected, dismissRecognitionMenu(target, () -> current, () -> { before[0]++; return true; }));
            assertEquals(0, before[0]); assertEquals(0, callback.calls);
        }
    }

    @Test public void recognitionSourceValidationAllowsOwnedMenusAndVerifiedHostUiWithoutInvokingBack() throws Exception {
        FakeBackCallback mainCallback = backCallback(main); ScopeCaptureWindow.Snapshot target = inspect();
        FakeBackCallback menuCallback = backCallback(dialog());
        assertTrue(recognitionSourceCurrent(target));
        hostOverlay(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        assertTrue(recognitionSourceCurrent(target));
        assertEquals(0, mainCallback.calls); assertEquals(0, menuCallback.calls); assertEquals(0, capture.calls);
    }

    @Test public void recognitionSourceValidationRejectsForeignFocusOrAChangedOriginalSource() throws Exception {
        for (int invalid = 0; invalid < 6; invalid++) {
            fresh(); FakeBackCallback callback = backCallback(main); ScopeCaptureWindow.Snapshot target = inspect();
            switch (invalid) {
                case 0 -> hostOverlay(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).mOwnerUid++;
                case 1 -> main.layer = new Object();
                case 2 -> main.mActivityRecord.mActivityComponent = new ComponentName(SOURCE, SOURCE + ".OtherActivity");
                case 3 -> main.mOwnerUid++;
                case 4 -> main.mActivityRecord.main = new Window();
                case 5 -> main.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
            }
            assertFalse("case=" + invalid, recognitionSourceCurrent(target));
            assertEquals(0, callback.calls); assertEquals(0, capture.calls);
        }
    }

    @Test public void recognitionPreservesAnExistingTargetDialogAndOtherVisibleWindowsFromDown() throws Exception {
        Window existing = dialog(); FakeBackCallback callback = backCallback(existing);
        ScopeCaptureWindow.Snapshot target = inspect();
        assertEquals("none", dismissRecognitionMenu(target, () -> true)); assertEquals(0, callback.calls);
        fresh(); existing = dialog(); callback = backCallback(existing);
        atms.mWindowManager.mRoot.display.hit = main; atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        atms.mWindowManager.mRoot.display.additionalWindows.add(existing);
        target = inspect();
        atms.mWindowManager.mRoot.display.hit = existing; atms.mWindowManager.mRoot.display.mCurrentFocus = existing;
        assertEquals("none", dismissRecognitionMenu(target, () -> true)); assertEquals(0, callback.calls);
    }

    @Test public void recognitionReturnsNoneUntilANewSourceMenuExists() throws Exception {
        ScopeCaptureWindow.Snapshot target = inspect(); FakeBackCallback callback = backCallback(main);
        assertEquals("none", dismissRecognitionMenu(target, () -> true)); assertEquals(0, callback.calls);
    }

    @Test public void aNewPhysicalDownOrAChangedSourceRevokesRecognitionMenuDismissal() throws Exception {
        ScopeCaptureWindow.Snapshot target = inspect(); FakeBackCallback callback = backCallback(dialog());
        assertEquals("stale", dismissRecognitionMenu(target, () -> false)); assertEquals(0, callback.calls);
        main.mActivityRecord.mActivityComponent = new ComponentName(SOURCE, SOURCE + ".OtherActivity");
        assertEquals("changed", dismissRecognitionMenu(target, () -> true)); assertEquals(0, callback.calls);
    }

    @Test public void recognitionMenuCallbacksCannotBeSharedWithTheMainTargetOrAnyWindowExistingAtDown() throws Exception {
        for (int shared = 0; shared < 3; shared++) {
            fresh(); Window previous = shared == 0 ? main : dialog();
            FakeBackCallback original = backCallback(previous);
            if (shared == 2) {
                atms.mWindowManager.mRoot.display.hit = main; atms.mWindowManager.mRoot.display.mCurrentFocus = main;
                atms.mWindowManager.mRoot.display.additionalWindows.add(previous);
            }
            ScopeCaptureWindow.Snapshot target = inspect();
            FakeBackCallback candidate = backCallback(dialog()); candidate.binder = original.binder;
            assertEquals("shared=" + shared, "ambiguous", dismissRecognitionMenu(target, () -> true));
            assertEquals(0, original.calls); assertEquals(0, candidate.calls);
        }
    }

    @Test public void aMenuCallbackSharedWithTheCurrentMainCallbackIsAmbiguousEvenIfMainChangedItAfterDown() throws Exception {
        ScopeCaptureWindow.Snapshot target = inspect(); FakeBackCallback mainCallback = backCallback(main);
        FakeBackCallback candidate = backCallback(dialog()); candidate.binder = mainCallback.binder;
        assertEquals("ambiguous", dismissRecognitionMenu(target, () -> true));
        assertEquals(0, mainCallback.calls); assertEquals(0, candidate.calls);
    }

    @Test public void aNewMenuCannotReuseTheCallbackThatBelongedToTheMainWindowAtDown() throws Exception {
        FakeBackCallback previous = backCallback(main); ScopeCaptureWindow.Snapshot target = inspect();
        FakeBackCallback current = backCallback(main);
        FakeBackCallback candidate = backCallback(dialog()); candidate.binder = previous.binder;
        assertEquals("ambiguous", dismissRecognitionMenu(target, () -> true));
        assertEquals(0, previous.calls); assertEquals(0, current.calls); assertEquals(0, candidate.calls);
    }

    @Test public void changedMenuCallbackOrFocusIsRejectedBeforeRecognitionSendsAnyBackCallback() throws Exception {
        for (boolean changeFocus : new boolean[]{false, true}) {
            fresh(); ScopeCaptureWindow.Snapshot target = inspect(); Window menu = dialog();
            FakeBackCallback first = backCallback(menu), replacement = new FakeBackCallback(atms.mWindowManager);
            menu.beforeBackRead = () -> {
                if (menu.backReads != 2) return;
                if (changeFocus) atms.mWindowManager.mRoot.display.mCurrentFocus = main;
                else menu.backInfo = new FakeBackInfo(replacement);
            };
            assertEquals("focus=" + changeFocus, "changed", dismissRecognitionMenu(target, () -> true));
            assertEquals(0, first.calls); assertEquals(0, replacement.calls);
        }
    }

    @Test public void sourceChangeDuringMenuInspectionStillPreventsTheRecognitionCallback() throws Exception {
        ScopeCaptureWindow.Snapshot target = inspect(); Window menu = dialog(); FakeBackCallback callback = backCallback(menu);
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        menu.beforeBackRead = () -> current.set(false);
        assertEquals("stale", dismissRecognitionMenu(target, current::get)); assertEquals(0, callback.calls);
    }

    @Test public void recognitionRejectsSecureForeignAnimatedOrPrivilegedMenuCallbacks() throws Exception {
        for (int invalid = 0; invalid < 11; invalid++) {
            fresh(); ScopeCaptureWindow.Snapshot target = inspect(); Window menu = dialog();
            FakeBackCallback callback = backCallback(menu);
            switch (invalid) {
                case 0 -> menu.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
                case 1 -> menu.mOwnerUid++;
                case 2 -> menu.mShowUserId = 10;
                case 3 -> menu.mAttrs.packageName = "com.example.other";
                case 4 -> menu.mActivityRecord = new Record();
                case 5 -> menu.showing = false;
                case 6 -> menu.backInfo.animation = true;
                case 7 -> menu.backInfo.priority = 1;
                case 8 -> callback.alive = false;
                case 9 -> menu.backInfo = null;
                case 10 -> callback.binder = null;
            }
            assertEquals("case=" + invalid, "unsupported", dismissRecognitionMenu(target, () -> true));
            assertEquals(0, callback.calls);
        }
    }

    @Test public void anUnavailableDownWindowBaselineDisablesOnlyMenuCleanup() throws Exception {
        atms.mWindowManager.mRoot.display.failAtVisit = 2;
        ScopeCaptureWindow.Snapshot target = inspect();
        atms.mWindowManager.mRoot.display.failAtVisit = -1;
        assertSame(capture.result, access.capture(atms, target));
        FakeBackCallback callback = backCallback(dialog());
        assertEquals("unsupported", dismissRecognitionMenu(target, () -> true)); assertEquals(0, callback.calls);
    }

    @Test public void unavailableHistoricalBackMetadataDisablesOnlyMenuCleanup() throws Exception {
        main.backFailure = new UnsupportedOperationException("optional_back_contract_unavailable");
        ScopeCaptureWindow.Snapshot target = inspect(); main.backFailure = null;
        assertSame(capture.result, access.capture(atms, target));
        FakeBackCallback callback = backCallback(dialog());
        assertEquals("unsupported", dismissRecognitionMenu(target, () -> true)); assertEquals(0, callback.calls);
    }

    @Test public void aDownWindowBaselineMissingTheOriginalSourceWindowCannotAuthorizeMenuCleanup() throws Exception {
        atms.mWindowManager.mRoot.display.omitWindows = true;
        ScopeCaptureWindow.Snapshot target = inspect(); atms.mWindowManager.mRoot.display.omitWindows = false;
        assertSame(capture.result, access.capture(atms, target));
        FakeBackCallback callback = backCallback(dialog());
        assertEquals("unsupported", dismissRecognitionMenu(target, () -> true)); assertEquals(0, callback.calls);
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
        int visits, failAtVisit = -1;
        boolean omitWindows;
        final List<Window> additionalWindows = new ArrayList<>();
        Window getTouchableWinAtPointLocked(float x, float y) { return hit; }
        void forAllWindows(Consumer<Window> action, boolean topToBottom) {
            if (++visits == failAtVisit) throw new IllegalStateException("baseline_unavailable");
            if (omitWindows) return;
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
        FakeBackInfo backInfo; int backReads; Runnable beforeBackRead = () -> {}; RuntimeException backFailure;
        Window() { mAttrs.type = 1; mAttrs.packageName = SOURCE; mAttrs.flags = 0; }
        Rect getFrame() { return bounds; } Window getParentWindow() { return parent; }
        boolean isVisible() { return showing; }
        FakeBackInfo getOnBackInvokedCallbackInfo() {
            if (backFailure != null) throw backFailure;
            backReads++; beforeBackRead.run(); return backInfo;
        }
        Object getSurfaceControl() { return surfaceGeneration == 0 ? layer : new SurfaceToken(surfaceGeneration); }
        static final class SurfaceToken { final long mNativeObject; SurfaceToken(long id) { mNativeObject = id; } }
        void forAllWindows(Consumer<Window> action, boolean topToBottom) { action.accept(this); for (Window child : descendants) action.accept(child); }
    }
    static final class Record {
        ComponentName mActivityComponent = new ComponentName(SOURCE, ACTIVITY); int mUserId = 0;
        Window main; Window findMainWindow(boolean ignored) { return main; }
    }
    static final class FakeBackInfo {
        final FakeBackCallback callback; int priority; boolean animation;
        FakeBackInfo(FakeBackCallback callback) { this.callback = callback; }
        FakeBackCallback getCallback() { return callback; }
        int getPriority() { return priority; }
        boolean isAnimationCallback() { return animation; }
    }
    static final class FakeBackCallback {
        final Wms wms; android.os.IBinder binder; int calls; boolean alive = true;
        FakeBackCallback(Wms wms) {
            this.wms = wms;
            binder = (android.os.IBinder) java.lang.reflect.Proxy.newProxyInstance(
                    android.os.IBinder.class.getClassLoader(), new Class<?>[]{android.os.IBinder.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "isBinderAlive", "pingBinder" -> alive;
                        case "equals" -> proxy == arguments[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> null;
                    });
        }
        android.os.IBinder asBinder() { return binder; }
        void onBackInvoked() {
            assertFalse("source callbacks must never run under the WMS lock", Thread.holdsLock(wms.mGlobalLock));
            calls++;
        }
    }
}
