package io.github.yudigaga.taplusbridge.selection;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Selection;
import android.text.SpannableString;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = SelectionActionsAndroidTest.MenuInvokeHook.class)
public final class SelectionActionsAndroidTest {
    private static final String SOURCE = "com.example.source";
    private Activity activity;
    private RecordingContext context;
    private TextView text;
    private RecordingCallback original;
    private Mode mode;
    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        context = new RecordingContext(activity);
        text = new TextView(context); text.setTextIsSelectable(true); selected("Hello world", 0, 5);
        original = new RecordingCallback(); mode = new Mode(new PopupMenu(context, text).getMenu());
    }
    @Test public void translationUsesRealSelectionAndRetainsCopySelectAllAndCallback2ContentRect() {
        MenuItem copy = mode.menu.add(0, android.R.id.copy, 0, "Copy");
        MenuItem all = mode.menu.add(0, android.R.id.selectAll, 1, "Select all");
        MenuItem translate = mode.menu.add(0, 77, 2, "翻译");
        ActionMode.Callback wrapped = wrap(targets());
        assertNotSame(original, wrapped); assertTrue(wrapped.onCreateActionMode(mode, mode.menu));
        assertEquals("Translator", translate.getTitle().toString()); assertNotNull(translate.getIcon());
        assertSame(copy, mode.menu.findItem(android.R.id.copy)); assertSame(all, mode.menu.findItem(android.R.id.selectAll));
        Rect rect = new Rect(); ((ActionMode.Callback2) wrapped).onGetContentRect(mode, text, rect);
        assertEquals(new Rect(1, 2, 30, 40), rect);
        selected("Hello world", 6, 11);
        assertTrue(wrapped.onActionItemClicked(mode, translate));
        assertEquals("world", context.last.getStringExtra(Intent.EXTRA_PROCESS_TEXT));
        assertEquals("com.example.translator/.Translate", context.last.getComponent().flattenToShortString());
        assertEquals(Set.of(Intent.EXTRA_PROCESS_TEXT, Intent.EXTRA_PROCESS_TEXT_READONLY), context.last.getExtras().keySet());
        assertTrue(context.last.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false));
        assertFalse(wrapped.onActionItemClicked(mode, copy)); assertSame(copy, original.clicked);
        wrapped.onDestroyActionMode(mode); assertEquals(1, original.destroyed);
    }
    @Test public void intentIdentifiedTranslationIsReplacedWithoutRequiringTitleMatch() {
        MenuItem item = mode.menu.add("Other title").setIntent(new Intent("android.intent.action.TRANSLATE"));
        ActionMode.Callback wrapped = wrap(targets()); wrapped.onCreateActionMode(mode, mode.menu);
        assertEquals("Translator", item.getTitle().toString()); assertTrue(wrapped.onActionItemClicked(mode, item));
        assertEquals(1, context.starts);
    }
    @Test public void absentTranslationIsAddedButAssistantAndCopyAreNotRewritten() {
        MenuItem assistant = mode.menu.add("Ask 小爱");
        MenuItem copy = mode.menu.add(0, android.R.id.copy, 0, "翻译");
        ActionMode.Callback wrapped = wrap(targets()); wrapped.onCreateActionMode(mode, mode.menu);
        assertEquals(3, mode.menu.size()); assertEquals("Ask 小爱", assistant.getTitle());
        assertEquals("翻译", copy.getTitle());
    }
    @Test public void browserUsesIntentUrlAndNeverCopiesOriginalExtrasOrFallsBackOnFailure() {
        MenuItem item = mode.menu.add("Open").setIntent(new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://example.com/path")).putExtra("secret", "private"));
        ActionMode.Callback wrapped = wrap(targets()); wrapped.onCreateActionMode(mode, mode.menu);
        assertEquals("Browser", item.getTitle().toString()); assertTrue(wrapped.onActionItemClicked(mode, item));
        assertEquals("com.example.browser", context.last.getPackage()); assertNull(context.last.getComponent());
        assertEquals("https://example.com/path", context.last.getDataString()); assertNull(context.last.getExtras());
        context.reject = true; assertTrue(wrapped.onActionItemClicked(mode, item)); assertNull(original.clicked);
    }
    @Test public void ordinaryWordsAndUnknownClickableSpansNeverBecomeBrowserActions() {
        SpannableString words = new SpannableString("ordinary words");
        words.setSpan(new ClickableSpan() { @Override public void onClick(View view) { fail("Unknown span ran"); } }, 0, words.length(), 0);
        selected(words, 0, words.length());
        ActionMode.Callback wrapped = wrap(targets()); wrapped.onCreateActionMode(mode, mode.menu);
        assertEquals(1, mode.menu.size()); assertEquals("Translator", mode.menu.getItem(0).getTitle());
    }
    @Test public void uniqueUrlSpanAddsBrowserButAmbiguousLinksDoNot() {
        SpannableString linked = new SpannableString("click here"); linked.setSpan(new URLSpan("https://example.com/"), 0, 5, 0);
        selected(linked, 0, linked.length());
        ActionMode.Callback wrapped = wrap(targets()); wrapped.onCreateActionMode(mode, mode.menu);
        assertEquals(2, mode.menu.size());
        MenuItem browser = mode.menu.getItem(1); assertEquals("Browser", browser.getTitle());
        assertTrue(wrapped.onActionItemClicked(mode, browser)); assertEquals("https://example.com/", context.last.getDataString());
        linked.setSpan(new URLSpan("https://other.example/"), 6, 10, 0); selected(linked, 0, linked.length());
        Mode another = new Mode(new PopupMenu(context, text).getMenu());
        wrap(targets()).onCreateActionMode(another, another.menu); assertEquals(1, another.menu.size());
    }
    @Test public void editablePasswordSecureForeignAndPrimaryModesKeepOriginalCallback() {
        EditText edit = new EditText(context); assertSame(original, SelectionActions.wrap(edit, original, ActionMode.TYPE_FLOATING, SOURCE, () -> targets()));
        text.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance()); assertSame(original, wrap(targets()));
        text.setTransformationMethod(null); activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); assertSame(original, wrap(targets()));
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        WindowManager.LayoutParams securedWindow = new WindowManager.LayoutParams(); securedWindow.flags = WindowManager.LayoutParams.FLAG_SECURE;
        text.setLayoutParams(securedWindow); assertSame(original, wrap(targets()));
        text.setLayoutParams(new android.view.ViewGroup.LayoutParams(100, 100));
        assertSame(original, SelectionActions.wrap(text, original, ActionMode.TYPE_PRIMARY, SOURCE, () -> targets()));
        assertSame(original, SelectionActions.wrap(text, original, ActionMode.TYPE_FLOATING, "com.other.source", () -> targets()));
    }
    @Test public void malformedProjectionLeavesTheNativeMenuUntouched() {
        Bundle malicious = projection(); malicious.putString("apiKey", "secret"); assertNull(SelectionTargets.parse(malicious));
        malicious = projection(); malicious.putString("translation_action", Intent.ACTION_VIEW); assertNull(SelectionTargets.parse(malicious));
        malicious = projection(); malicious.putParcelable("browser_icon", Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)); assertNull(SelectionTargets.parse(malicious));
        MenuItem item = mode.menu.add("翻译"); ActionMode.Callback wrapped = wrap(null); wrapped.onCreateActionMode(mode, mode.menu);
        assertEquals("翻译", item.getTitle()); assertEquals(1, mode.menu.size());
    }
    @Test public void onlyValidWebUrlsAndWhitelistedTranslationActionsCanBeLaunched() {
        SelectionTargets targets = targets(); assertNotNull(targets);
        assertNull(targets.browserIntent("ordinary words")); assertNull(targets.browserIntent("javascript:alert(1)"));
        assertNull(targets.translationIntent(" ")); assertNull(targets.translationIntent("x".repeat(32769)));
        assertEquals("com.example.browser", targets.browserIntent("https://example.com").getPackage());
    }
    @Test public void configuredButUnavailableTargetsConsumeExistingActionsWithoutLaunchingAnythingElse() {
        Bundle unavailable = new Bundle(); unavailable.putBoolean("enabled", true);
        unavailable.putBoolean("browser_configured", true); unavailable.putBoolean("translation_configured", true);
        MenuItem translation = mode.menu.add("翻译");
        MenuItem browser = mode.menu.add("Open").setIntent(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")));
        ActionMode.Callback wrapped = wrap(SelectionTargets.parse(unavailable)); wrapped.onCreateActionMode(mode, mode.menu);
        assertTrue(wrapped.onActionItemClicked(mode, translation)); assertTrue(wrapped.onActionItemClicked(mode, browser));
        assertEquals(0, context.starts); assertNull(original.clicked);
        assertTrue(translation.getTitle().toString().contains("不可用")); assertTrue(browser.getTitle().toString().contains("不可用"));
    }
    @Test public void assistantIsNeverRepurposedEvenIfItCarriesAWebIntent() {
        MenuItem assistant = mode.menu.add("Ask 小爱").setIntent(new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://assistant.example.com/")));
        ActionMode.Callback wrapped = wrap(targets()); wrapped.onCreateActionMode(mode, mode.menu);
        assertEquals("Ask 小爱", assistant.getTitle()); assertFalse(wrapped.onActionItemClicked(mode, assistant));
        assertSame(assistant, original.clicked); assertEquals(0, context.starts);
    }
    @Test public void realMenuInvocationUsesConfiguredTargetBeforeTheOriginalItemListenerAndRestoresIt() {
        int[] originalClicks = {0};
        MenuItem translation = mode.menu.add(0, 73, 0, "翻译").setOnMenuItemClickListener(item -> { originalClicks[0]++; return true; });
        ActionMode.Callback wrapped = wrap(targets()); wrapped.onCreateActionMode(mode, mode.menu);
        assertTrue(mode.menu.performIdentifierAction(73, 0));
        assertEquals(0, originalClicks[0]); assertEquals(1, context.starts);
        assertEquals("com.example.translator", context.last.getComponent().getPackageName());
        wrapped.onDestroyActionMode(mode);
        assertTrue(mode.menu.performIdentifierAction(73, 0)); assertEquals(1, originalClicks[0]); assertEquals(1, context.starts);
    }
    @Test public void prepareDuringRefreshKeepsOwnedListenerBlockedUntilDisabledResponseArrives() throws Exception {
        var provider = Robolectric.buildContentProvider(SelectionTargetReaderAndroidTest.TargetProvider.class)
                .create(io.github.yudigaga.taplusbridge.bridge.BridgeContract.AUTHORITY).get();
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
                io.github.yudigaga.taplusbridge.bridge.BridgeContract.AUTHORITY, provider);
        SelectionTargetReaderAndroidTest.TargetProvider.entered = new java.util.concurrent.CountDownLatch(1);
        SelectionTargetReaderAndroidTest.TargetProvider.release = new java.util.concurrent.CountDownLatch(1);
        SelectionTargetReaderAndroidTest.TargetProvider.response = projection();
        int[] nativeClicks = {0};
        mode.menu.add(0, 74, 0, "翻译").setOnMenuItemClickListener(item -> { nativeClicks[0]++; return true; });
        SelectionTargetReader reader = new SelectionTargetReader(context, SOURCE);
        ActionMode.Callback wrapped = SelectionActions.wrap(text, original, ActionMode.TYPE_FLOATING, SOURCE, reader);
        wrapped.onCreateActionMode(mode, mode.menu);
        try { assertTrue(SelectionTargetReaderAndroidTest.TargetProvider.entered.await(3, java.util.concurrent.TimeUnit.SECONDS)); }
        finally { SelectionTargetReaderAndroidTest.TargetProvider.release.countDown(); }
        drainUntil(() -> reader.get() != null); wrapped.onPrepareActionMode(mode, mode.menu);
        assertEquals("Translator", mode.menu.findItem(74).getTitle());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3));
        SelectionTargetReaderAndroidTest.TargetProvider.entered = new java.util.concurrent.CountDownLatch(1);
        SelectionTargetReaderAndroidTest.TargetProvider.release = new java.util.concurrent.CountDownLatch(1);
        Bundle disabled = new Bundle(); disabled.putBoolean("enabled", false);
        SelectionTargetReaderAndroidTest.TargetProvider.response = disabled;
        int invalidated = mode.invalidated;
        try {
            wrapped.onPrepareActionMode(mode, mode.menu);
            assertTrue(SelectionTargetReaderAndroidTest.TargetProvider.entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(mode.menu.performIdentifierAction(74, 0)); assertEquals(0, nativeClicks[0]); assertEquals(0, context.starts);
        } finally { SelectionTargetReaderAndroidTest.TargetProvider.release.countDown(); }
        drainUntil(() -> mode.invalidated > invalidated); wrapped.onPrepareActionMode(mode, mode.menu);
        assertTrue(mode.menu.performIdentifierAction(74, 0)); assertEquals(1, nativeClicks[0]); assertEquals(0, context.starts);
        wrapped.onDestroyActionMode(mode);
    }
    @Test public void deferredClickIsCancelledWhenSelectionMovesToIdenticalWordsWithAnotherUrl() {
        SpannableString links = new SpannableString("link link"); links.setSpan(new URLSpan("https://first.example/"), 0, 4, 0);
        links.setSpan(new URLSpan("https://second.example/"), 5, 9, 0); selected(links, 0, 4);
        class Deferred implements SelectionActions.TargetSource {
            java.util.function.Consumer<SelectionTargets> pending;
            public SelectionTargets get() { return targets(); }
            public boolean refreshing() { return false; }
            public void watch(ActionMode mode) { } public void forget(ActionMode mode) { }
            public void withFresh(ActionMode mode, java.util.function.Consumer<SelectionTargets> action) { pending = action; }
        }
        Deferred deferred = new Deferred();
        ActionMode.Callback wrapped = SelectionActions.wrap(text, original, ActionMode.TYPE_FLOATING, SOURCE, deferred);
        wrapped.onCreateActionMode(mode, mode.menu); MenuItem browser = mode.menu.getItem(1);
        assertEquals("Browser", browser.getTitle()); assertTrue(mode.menu.performIdentifierAction(browser.getItemId(), 0));
        Selection.setSelection((android.text.Spannable) text.getText(), 5, 9);
        deferred.pending.accept(targets()); assertEquals(0, context.starts);
        wrapped.onDestroyActionMode(mode);
    }
    private static void drainUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            ((org.robolectric.shadows.ShadowLooper) Shadow.extract(android.os.Looper.getMainLooper())).idle(); Thread.sleep(10);
        }
        ((org.robolectric.shadows.ShadowLooper) Shadow.extract(android.os.Looper.getMainLooper())).idle();
        assertTrue("Background selection configuration did not finish", condition.getAsBoolean());
    }
    private ActionMode.Callback wrap(SelectionTargets targets) { return SelectionActions.wrap(text, original, ActionMode.TYPE_FLOATING, SOURCE, () -> targets); }
    private void selected(CharSequence value, int start, int end) { text.setText(value, TextView.BufferType.SPANNABLE); Selection.setSelection((android.text.Spannable) text.getText(), start, end); }
    private static SelectionTargets targets() { return SelectionTargets.parse(projection()); }
    private static Bundle projection() {
        Bundle data = new Bundle(); data.putBoolean("enabled", true);
        data.putBoolean("browser_configured", true); data.putBoolean("translation_configured", true);
        data.putString("browser_package", "com.example.browser"); data.putString("browser_title", "Browser"); data.putParcelable("browser_icon", Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888));
        data.putString("translation_component", "com.example.translator/.Translate"); data.putString("translation_action", Intent.ACTION_PROCESS_TEXT);
        data.putString("translation_title", "Translator"); data.putParcelable("translation_icon", Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)); return data;
    }
    private static final class RecordingContext extends ContextWrapper {
        Intent last; int starts; boolean reject;
        RecordingContext(Context base) { super(base); }
        @Override public String getPackageName() { return SOURCE; }
        @Override public void startActivity(Intent intent) { last = intent; starts++; if (reject) throw new android.content.ActivityNotFoundException(); }
    }
    private static final class RecordingCallback extends ActionMode.Callback2 {
        MenuItem clicked; int destroyed;
        public boolean onCreateActionMode(ActionMode mode, Menu menu) { return true; }
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) { clicked = item; return false; }
        public void onDestroyActionMode(ActionMode mode) { destroyed++; }
        @Override public void onGetContentRect(ActionMode mode, View view, Rect out) { out.set(1, 2, 30, 40); }
    }
    private static final class Mode extends ActionMode {
        final Menu menu; int invalidated; Mode(Menu menu) { this.menu = menu; }
        public void setTitle(CharSequence text) { } public void setSubtitle(CharSequence text) { }
        public void setTitle(int id) { } public void setSubtitle(int id) { } public void setCustomView(View view) { }
        public void invalidate() { invalidated++; } public void finish() { } public Menu getMenu() { return menu; }
        public CharSequence getTitle() { return ""; } public CharSequence getSubtitle() { return ""; }
        public View getCustomView() { return null; } public MenuInflater getMenuInflater() { return null; }
    }
    /** Native LSPosed cannot run on the JVM; this mirrors only its pre-invoke interception seam. */
    @Implements(className = "com.android.internal.view.menu.MenuItemImpl", isInAndroidSdk = false)
    public static final class MenuInvokeHook {
        @RealObject private Object real;
        @Implementation public boolean invoke() {
            if (SelectionActions.dispatchMenuItem((MenuItem) real)) return true;
            return Shadow.directlyOn(real, "com.android.internal.view.menu.MenuItemImpl", "invoke");
        }
    }
}
