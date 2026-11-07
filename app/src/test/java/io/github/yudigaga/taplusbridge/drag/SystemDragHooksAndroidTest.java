package io.github.yudigaga.taplusbridge.drag;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.view.View;
import java.lang.reflect.Modifier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public final class SystemDragHooksAndroidTest {
    private static String[] signature(int extras) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>(java.util.List.of(
                "int", "int", "android.view.IWindow", "int", "android.view.SurfaceControl", "int"));
        for (int i = 0; i < extras; i++) values.add("int");
        values.addAll(java.util.List.of("float", "float", "float", "float", "android.content.ClipData"));
        return values.toArray(new String[0]);
    }

    @Test public void acceptsKnownGlobalDragLayouts() {
        for (int extras = 0; extras <= 2; extras++)
            assertTrue(SystemDragHooks.Signature.matches("performDrag", Modifier.FINAL, "android.os.IBinder", signature(extras)));
    }

    @Test public void rejectsEveryUnprovedArgumentPosition() {
        for (int extras = 0; extras <= 2; extras++) {
            String[] expected = signature(extras);
            for (int i = 0; i < expected.length; i++) {
                String[] changed = expected.clone(); changed[i] = "java.lang.Object";
                assertFalse("position=" + i, SystemDragHooks.Signature.matches("performDrag", 0, "android.os.IBinder", changed));
            }
        }
        assertFalse(SystemDragHooks.Signature.matches("performDrag", 0, "android.os.IBinder", signature(3)));
        assertFalse(SystemDragHooks.Signature.matches("performDrag", Modifier.STATIC, "android.os.IBinder", signature(0)));
        assertFalse(SystemDragHooks.Signature.matches("performDrag", Modifier.ABSTRACT, "android.os.IBinder", signature(0)));
        assertFalse(SystemDragHooks.Signature.matches("other", 0, "android.os.IBinder", signature(0)));
        assertFalse(SystemDragHooks.Signature.matches("performDrag", 0, "void", signature(0)));
        assertFalse(SystemDragHooks.Signature.matches((java.lang.reflect.Method) null));
    }

    @Test public void acceptsOnlyGlobalDragsOwnedByMainUserApps() {
        assertTrue(SystemDragHooks.eligible(10_123, View.DRAG_FLAG_GLOBAL));
        assertFalse(SystemDragHooks.eligible(10_123, View.DRAG_FLAG_GLOBAL_URI_READ));
        assertFalse(SystemDragHooks.eligible(110_123, View.DRAG_FLAG_GLOBAL));
        assertFalse(SystemDragHooks.eligible(1_000, View.DRAG_FLAG_GLOBAL));
        assertFalse(SystemDragHooks.eligible(10_123, View.DRAG_FLAG_GLOBAL | (1 << 12)));
    }

    @Test public void sharedUidsAndModuleOrHostSourcesAreRejected() {
        assertEquals("example.source", SystemDragHooks.uniqueSource(new String[]{"example.source"}));
        assertNull(SystemDragHooks.uniqueSource(null));
        assertNull(SystemDragHooks.uniqueSource(new String[0]));
        assertNull(SystemDragHooks.uniqueSource(new String[]{"example.first", "example.second"}));
        assertNull(SystemDragHooks.uniqueSource(new String[]{"com.miui.contentextension"}));
        assertNull(SystemDragHooks.uniqueSource(new String[]{"io.github.yudigaga.taplusbridge"}));
        assertNull(SystemDragHooks.uniqueSource(new String[]{"invalid"}));
    }

    @Test public void entryContainsOnlySourceAndTimingMetadata() {
        Intent intent = SystemDragHooks.entry("example.source", 1234L);
        assertEquals("io.github.yudigaga.taplusbridge.drag.DragActivity", intent.getComponent().getClassName());
        assertEquals(3, intent.getExtras().size());
        assertEquals("example.source", intent.getStringExtra("source"));
        assertEquals(1234L, intent.getLongExtra("started_elapsed", -1));
        assertEquals(0, intent.getIntExtra("display", -1));
        assertNull(intent.getClipData());
        assertNull(intent.getData());
        assertEquals(0, intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Test public void delayedOrFutureStartsAreRejected() {
        assertTrue(SystemDragHooks.fresh(1000, 3000));
        assertFalse(SystemDragHooks.fresh(1000, 3001));
        assertFalse(SystemDragHooks.fresh(1000, 999));
        assertFalse(SystemDragHooks.fresh(-1, 0));
    }

    @Test public void legacyStateFieldsResolveInheritedPrivateMembers() throws Exception {
        var fields = SystemDragHooks.StateFields.resolve(LegacyState.class); LegacyState state = new LegacyState();
        assertEquals("legacy", fields.layout); assertEquals("mDisplayContent", fields.current.getName());
        assertNull(fields.start); assertSame(state.mToken, fields.token.get(state));
        assertEquals(10123, fields.uid.getInt(state)); assertTrue(fields.inProgress.getBoolean(state));
        assertTrue(fields.mainDisplay(state)); state.mDisplayContent.id = 1; assertFalse(fields.mainDisplay(state));
        state.mDisplayContent = null; assertFalse(fields.mainDisplay(state));
    }

    @Test public void android16SplitFieldsRequireBothOriginAndCurrentMainDisplay() throws Exception {
        var fields = SystemDragHooks.StateFields.resolve(SplitState.class); SplitState state = new SplitState();
        assertEquals("split", fields.layout); assertEquals("mCurrentDisplayContent", fields.current.getName());
        assertEquals("mStartDragDisplayContent", fields.start.getName()); assertTrue(fields.mainDisplay(state));
        state.mCurrentDisplayContent.id = 1; assertFalse(fields.mainDisplay(state));
        state.mCurrentDisplayContent.id = 0; state.mStartDragDisplayContent.id = 1; assertFalse(fields.mainDisplay(state));
        state.mStartDragDisplayContent = null; assertFalse(fields.mainDisplay(state));
        state.mStartDragDisplayContent = new Display(0); state.mCurrentDisplayContent = null;
        assertFalse(fields.mainDisplay(state));
    }

    @Test public void currentFieldWithoutRequiredOriginNeverFallsBackToLegacyField() {
        var failure = assertThrows(SystemDragHooks.ContractFailure.class,
                () -> SystemDragHooks.StateFields.resolve(SplitWithoutOrigin.class));
        assertEquals("field", failure.stage); assertEquals("mStartDragDisplayContent", failure.member);
    }

    @Test public void currentDisplayTakesPrecedenceIfRomAlsoKeepsLegacyField() throws Exception {
        var fields = SystemDragHooks.StateFields.resolve(SplitWithLegacy.class);
        SplitWithLegacy state = new SplitWithLegacy();
        state.mCurrentDisplayContent.id = 1;
        assertEquals(0, state.mDisplayContent.id); assertEquals("split", fields.layout);
        assertFalse(fields.mainDisplay(state));
    }

    @Test public void legacyLayoutChecksAnOriginFieldWhenTheRomProvidesOne() throws Exception {
        var fields = SystemDragHooks.StateFields.resolve(LegacyWithOrigin.class);
        LegacyWithOrigin state = new LegacyWithOrigin(); assertTrue(fields.mainDisplay(state));
        state.mStartDragDisplayContent.id = 1; assertFalse(fields.mainDisplay(state));
    }

    @Test public void missingOrRetypedStateFieldsNameTheExactRejectedContract() {
        for (Object[] bad : new Object[][]{
                {MissingUid.class, "field", "mUid"}, {WrongUid.class, "type", "mUid"},
                {WrongToken.class, "type", "mToken"}, {WrongProgress.class, "type", "mDragInProgress"},
                {MissingDisplay.class, "field", "mDisplayContent"},
                {WrongOrigin.class, "type", "mStartDragDisplayContent"},
                {WrongDisplayId.class, "type", "getDisplayId"}}) {
            var failure = assertThrows(SystemDragHooks.ContractFailure.class,
                    () -> SystemDragHooks.StateFields.resolve((Class<?>) bad[0]));
            assertEquals(bad[1], failure.stage); assertEquals(bad[2], failure.member);
        }
    }

    // Exact DragState member shapes from AOSP android-16.0.0_r1 and the previous single-display layout.
    static class BaseState {
        IBinder mToken = new Binder(); int mUid = 10123; private boolean mDragInProgress = true;
    }
    static class Display { int id; Display(int id) { this.id = id; } int getDisplayId() { return id; } }
    static class LegacyState extends BaseState { Display mDisplayContent = new Display(0); }
    static class SplitState extends BaseState {
        Display mCurrentDisplayContent = new Display(0), mStartDragDisplayContent = new Display(0);
    }
    static class SplitWithoutOrigin extends BaseState {
        Display mCurrentDisplayContent = new Display(0), mDisplayContent = new Display(0);
    }
    static class SplitWithLegacy extends SplitState { Display mDisplayContent = new Display(0); }
    static class LegacyWithOrigin extends LegacyState { Display mStartDragDisplayContent = new Display(0); }
    static class MissingUid { IBinder mToken; boolean mDragInProgress; Display mDisplayContent; }
    static class WrongUid extends LegacyState { String mUid; }
    static class WrongToken extends LegacyState { Object mToken; }
    static class WrongProgress extends LegacyState { int mDragInProgress; }
    static class MissingDisplay extends BaseState { }
    static class WrongOrigin extends BaseState { Display mCurrentDisplayContent; Object mStartDragDisplayContent; }
    static class BadDisplay { String getDisplayId() { return "0"; } }
    static class WrongDisplayId extends BaseState { BadDisplay mDisplayContent; }
}
