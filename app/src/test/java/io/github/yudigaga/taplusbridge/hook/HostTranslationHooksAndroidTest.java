package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class HostTranslationHooksAndroidTest {
    private static final String TOKEN = "t".repeat(43);
    private static final String TEXT = "Private source words";
    private static final String SOURCE = "com.example.source";
    private RecordingContext context;

    @Before public void attachProvider() throws Exception {
        Method reset = io.github.yudigaga.taplusbridge.runtime.BridgeTransport.class.getDeclaredMethod("testReset");
        reset.setAccessible(true); reset.invoke(null);
        var provider = Robolectric.buildContentProvider(TranslationProvider.class).create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
        TranslationProvider.response = Bundle.EMPTY; TranslationProvider.request = null;
        TranslationProvider.calls = 0; TranslationProvider.fail = false;
        context = new RecordingContext(RuntimeEnvironment.getApplication());
    }

    @Test public void prepareSendsOnlyTextAndSourceAndDoesNotStartAnActivity() throws Exception {
        TranslationProvider.response = response(direct());
        Intent result = prepare(TEXT, SOURCE);
        assertNotNull(result); assertEquals(0, context.starts);
        assertEquals("translation_prepare", TranslationProvider.method);
        assertEquals(Set.of("text", "source"), TranslationProvider.request.keySet());
        assertEquals(TEXT, TranslationProvider.request.getString("text"));
        assertEquals(SOURCE, TranslationProvider.request.getString("source"));
        assertEquals(Set.of("token"), result.getExtras().keySet());
        assertEquals(TOKEN, result.getStringExtra("token"));
    }

    @Test public void nativeModeOrUnavailableProviderLeavesTheOriginalActionAvailable() throws Exception {
        assertNull(prepare(TEXT, SOURCE));
        assertFalse(dispatch(TEXT, SOURCE)); assertEquals(0, context.starts);
        TranslationProvider.fail = true;
        assertNull(prepare(TEXT, SOURCE)); assertFalse(dispatch(TEXT, SOURCE));
        assertEquals(0, context.starts);
    }

    @Test public void invalidOrOversizedTextDoesNotReachTheProvider() throws Exception {
        assertNull(prepare(null, SOURCE)); assertNull(prepare(" \n\t", SOURCE));
        assertNull(prepare("x".repeat(131073), SOURCE));
        assertNull(prepare(TEXT, "invalid source"));
        assertEquals(0, TranslationProvider.calls);
    }

    @Test public void rawTextOrForeignLaunchResponsesCannotBecomeTranslationIntents() throws Exception {
        Intent withSelector = new Intent(direct()); withSelector.setSelector(new Intent(Intent.ACTION_VIEW));
        for (Intent unsafe : new Intent[]{
                new Intent(direct()).setComponent(new ComponentName("com.other.app", "com.other.app.Activity")),
                new Intent(direct()).putExtra("text", TEXT),
                new Intent(direct()).putExtra("token", "short"),
                new Intent(direct()).setData(Uri.parse("https://example.com/private")),
                withSelector}) {
            TranslationProvider.response = response(unsafe);
            assertNull(prepare(TEXT, SOURCE));
        }
        assertEquals(0, context.starts);
    }

    @Test public void dispatchStartsExactlyOnceAndFailedLaunchFallsBack() throws Exception {
        TranslationProvider.response = response(direct());
        assertTrue(dispatch(TEXT, SOURCE)); assertEquals(1, context.starts);
        assertEquals(Set.of("token"), context.last.getExtras().keySet());
        context.reject = true;
        assertFalse(dispatch(TEXT, SOURCE)); assertEquals(2, context.starts);
    }

    @Test public void textButtonUsesNaturalAdapterWordsInsteadOfSplitCardWords() throws Exception {
        Method selected = helper().getDeclaredMethod("selectedText", Object.class); selected.setAccessible(true);
        assertEquals("Hello world", selected.invoke(null, new Card()));
    }

    private Intent prepare(String text, String source) throws Exception {
        Method method = helper().getDeclaredMethod("prepare", Context.class, String.class, String.class);
        method.setAccessible(true); return (Intent) method.invoke(null, context, text, source);
    }
    private boolean dispatch(String text, String source) throws Exception {
        Method method = helper().getDeclaredMethod("dispatch", Context.class, String.class, String.class);
        method.setAccessible(true); return (Boolean) method.invoke(null, context, text, source);
    }
    private Class<?> helper() throws ClassNotFoundException {
        return Class.forName("io.github.yudigaga.taplusbridge.hook.HostTranslationHooks");
    }
    private static Intent direct() {
        return new Intent().setComponent(new ComponentName(BridgeContract.APP_PACKAGE,
                BridgeContract.APP_PACKAGE + ".translation.TranslationActivity")).putExtra("token", TOKEN);
    }
    private static Bundle response(Intent intent) { Bundle result = new Bundle(); result.putParcelable("direct", intent); return result; }
    public static final class Card {
        private final Adapter mSegmentAdapter = new Adapter();
        public String getSelectedWords() { return "Hello||world"; }
    }
    public static final class Adapter { public String getSelectedWords() { return "Hello world"; } }
    private static final class RecordingContext extends ContextWrapper {
        int starts; Intent last; boolean reject;
        RecordingContext(Context base) { super(base); }
        @Override public void startActivity(Intent intent) {
            starts++; last = intent;
            if (reject) throw new android.content.ActivityNotFoundException("unavailable");
        }
    }
    public static final class TranslationProvider extends ContentProvider {
        static Bundle response, request; static String method; static int calls; static boolean fail;
        public boolean onCreate() { return true; }
        public Bundle call(String value, String arg, Bundle input) {
            calls++; method = value; request = input == null ? null : new Bundle(input);
            if (fail) throw new SecurityException("unavailable");
            return response;
        }
        public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        public String getType(Uri u) { return null; }
        public Uri insert(Uri u, ContentValues v) { return null; }
        public int delete(Uri u, String s, String[] a) { return 0; }
        public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }
}
