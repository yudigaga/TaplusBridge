package io.github.yudigaga.taplusbridge.probe;

import android.content.ComponentName;
import android.graphics.Rect;
import android.view.WindowManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class SourceWindowAccessTest {
    private static final String SOURCE = "com.tencent.mm", ACTIVITY = SOURCE + ".ui.Chat";
    private Atms atms; private MainWindow main; private SourceWindowAccess access;
    @Before public void setup() throws Exception {
        atms = new Atms(); main = new MainWindow();
        main.mActivityRecord.main = main;
        atms.mWindowManager.mRoot.display.hit = main;
        atms.mWindowManager.mRoot.display.mCurrentFocus = main;
        access = new SourceWindowAccess(Atms.class);
    }
    private ProbeClickPlan.Window inspect() throws Exception { return access.inspect(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 100); }
    private MainWindow popup(int type, Rect frame) {
        MainWindow popup = new MainWindow(); popup.mAttrs.type = type; popup.bounds = frame;
        popup.mActivityRecord = main.mActivityRecord; popup.parent = main;
        atms.mWindowManager.mRoot.display.mCurrentFocus = popup;
        return popup;
    }
    private void rejected() throws Exception {
        try { inspect(); fail(); } catch (SourceWindowAccess.Rejected expected) { }
    }
    @Test public void actualFocusedMainWindowIsAllowed() throws Exception { assertEquals(ProbeClickPlan.Window.MAIN, inspect()); }
    @Test public void outsideFocusedOwnedModalPopupCanOnlyPrepareDismissal() throws Exception {
        popup(1002, new Rect(100, 150, 280, 300));
        assertEquals(ProbeClickPlan.Window.OUTSIDE_POPUP, inspect());
        try { access.requireTarget(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 100); fail(); }
        catch (SourceWindowAccess.Rejected expected) { }
    }
    @Test public void pointInsideMenuNeverGetsInjected() throws Exception { popup(1002, new Rect(0, 0, 200, 200)); rejected(); }
    @Test public void coveringMenuDismissalUsesOnlyAnOwnedOutsidePoint() throws Exception {
        MainWindow menu = popup(1002, new Rect(0, 0, 200, 200));
        atms.mWindowManager.mRoot.display.hit = menu;
        atms.mWindowManager.mRoot.display.geometricHits = true;
        SourceWindowAccess.CardPlan plan = access.planCardTap(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 100, 22002);
        assertTrue(plan.dismiss());
        assertFalse(plan.popupCovered());
        assertFalse(menu.bounds.contains((int) plan.dismissX(), (int) plan.dismissY()));
        assertTrue(main.bounds.contains((int) plan.dismissX(), (int) plan.dismissY()));
    }
    @Test public void ordinaryModalDialogCanPrepareAnOutsideDismissal() throws Exception {
        MainWindow dialog = popup(2, new Rect(0, 300, 400, 800)); dialog.parent = null;
        atms.mWindowManager.mRoot.display.hit = dialog;
        atms.mWindowManager.mRoot.display.geometricHits = true;
        SourceWindowAccess.CardPlan plan = access.planCardTap(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 500, 22002);
        assertTrue(plan.dismiss()); assertFalse(plan.popupCovered());
        assertFalse(dialog.bounds.contains((int) plan.dismissX(), (int) plan.dismissY()));
        assertTrue(main.bounds.contains((int) plan.dismissX(), (int) plan.dismissY()));
    }
    @Test public void outsideDismissPointCoveredByAnotherAppIsRejected() throws Exception {
        MainWindow menu = popup(1002, new Rect(0, 0, 200, 200));
        atms.mWindowManager.mRoot.display.hit = menu;
        atms.mWindowManager.mRoot.display.geometricHits = true;
        atms.mWindowManager.mRoot.display.outsideHit = new MainWindow();
        atms.mWindowManager.mRoot.display.outsideHit.mOwnerUid++;
        assertThrows(SourceWindowAccess.Rejected.class,
                () -> access.planCardTap(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 100, 22002));
    }
    @Test public void fullScreenMenuAndSecureActivityCannotPrepareOutsideDismissal() throws Exception {
        MainWindow menu = popup(1002, new Rect(main.bounds));
        atms.mWindowManager.mRoot.display.hit = menu;
        assertThrows(SourceWindowAccess.Rejected.class,
                () -> access.planCardTap(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 100, 22002));
        menu.bounds = new Rect(0, 0, 200, 200); main.mAttrs.flags = WindowManager.LayoutParams.FLAG_SECURE;
        assertThrows(SourceWindowAccess.Rejected.class,
                () -> access.planCardTap(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 100, 22002));
    }
    @Test public void outsideMenuDismissalKeepsTheSourcePointWithoutCoveredPopupHandling() throws Exception {
        popup(1002, new Rect(100, 150, 280, 300));
        SourceWindowAccess.CardPlan plan = access.planCardTap(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 100, 22002);
        assertTrue(plan.dismiss());
        assertFalse(plan.popupCovered());
        assertEquals(50f, plan.dismissX(), 0f);
        assertEquals(100f, plan.dismissY(), 0f);
    }
    @Test public void unsafeCoveringPopupCannotPrepareSamePointDismissal() throws Exception {
        MainWindow menu = popup(1002, new Rect(0, 0, 200, 200));
        atms.mWindowManager.mRoot.display.hit = menu;
        for (int flag : new int[]{WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                WindowManager.LayoutParams.FLAG_SECURE}) {
            menu.mAttrs.flags = flag;
            assertThrows(SourceWindowAccess.Rejected.class,
                    () -> access.planCardTap(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 100, 22002));
        }
        menu.mAttrs.flags = 0; menu.mOwnerUid++;
        assertThrows(SourceWindowAccess.Rejected.class,
                () -> access.planCardTap(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 100, 22002));
    }
    @Test public void hostOverlayDoesNotBlockTheCardTapPlan() throws Exception {
        MainWindow overlay = new MainWindow(); overlay.mOwnerUid = 22002; overlay.mActivityRecord = null;
        overlay.mAttrs.type = 2038; overlay.mAttrs.packageName = "com.miui.contentextension";
        atms.mWindowManager.mRoot.display.hit = overlay;
        atms.mWindowManager.mRoot.display.mCurrentFocus = overlay;
        SourceWindowAccess.CardPlan plan = access.planCardTap(atms, SOURCE, ACTIVITY, 21001, 0, 0, 50, 100, 22002);
        assertFalse(plan.dismiss());
    }
    @Test public void sameUidDialogOutsideItsFrameStillRejectsMainHit() throws Exception { popup(2, new Rect(100, 150, 280, 300)); rejected(); }
    @Test public void differentUidPopupCannotBeDismissed() throws Exception { popup(1002, new Rect(100, 150, 280, 300)).mOwnerUid++; rejected(); }
    @Test public void popupFromDifferentActivityIsRejected() throws Exception {
        MainWindow popup = popup(1002, new Rect(100, 150, 280, 300));
        popup.mActivityRecord = new Record(); popup.mActivityRecord.mActivityComponent = new ComponentName(SOURCE, SOURCE + ".Other");
        rejected();
    }
    @Test public void detachedOrNonModalPopupIsRejected() throws Exception {
        MainWindow popup = popup(1002, new Rect(100, 150, 280, 300)); popup.parent = null; rejected();
        popup.parent = main; popup.mAttrs.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL; rejected();
    }
    @Test public void focusAndPointCannotBelongToDifferentWindows() throws Exception {
        atms.mWindowManager.mRoot.display.mCurrentFocus = new MainWindow(); rejected();
    }
    @Test public void callerUserAndActualActivityMustMatch() throws Exception {
        main.mShowUserId = 1; rejected(); main.mShowUserId = 0;
        main.mActivityRecord.mUserId = 1; rejected();
    }
    @Test public void missingWmsCannotAdvertiseSupport() throws Exception {
        atms.mWindowManager = null;
        try { access.available(atms); fail(); } catch (SourceWindowAccess.Unavailable expected) { }
    }

    static final class Atms { Wms mWindowManager = new Wms(); }
    static final class Wms { final Object mGlobalLock = new Object(); final Root mRoot = new Root(); }
    static final class Root { final Display display = new Display(); Display getDisplayContent(int id) { return id == 0 ? display : null; } }
    static final class Display {
        MainWindow hit, mCurrentFocus, outsideHit;
        boolean geometricHits;
        MainWindow getTouchableWinAtPointLocked(float x, float y) {
            if (geometricHits && hit != null && !hit.bounds.contains((int) x, (int) y))
                return outsideHit != null ? outsideHit : hit.mActivityRecord.main;
            return hit;
        }
    }
    static final class MainWindow {
        int mOwnerUid = 21001, mShowUserId = 0;
        final WindowManager.LayoutParams mAttrs = new WindowManager.LayoutParams();
        Record mActivityRecord = new Record(); MainWindow parent; Rect bounds = new Rect(0, 0, 400, 800);
        MainWindow() { mAttrs.type = 1; mAttrs.packageName = SOURCE; mAttrs.flags = 0; }
        Rect getFrame() { return bounds; }
        MainWindow getParentWindow() { return parent; }
        boolean isVisible() { return true; }
    }
    static final class Record {
        ComponentName mActivityComponent = new ComponentName(SOURCE, ACTIVITY); int mUserId = 0;
        MainWindow main; MainWindow findMainWindow(boolean ignored) { return main; }
    }
}
