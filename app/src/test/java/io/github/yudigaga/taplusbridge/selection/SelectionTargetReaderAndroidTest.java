package io.github.yudigaga.taplusbridge.selection;

import static org.junit.Assert.*;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class SelectionTargetReaderAndroidTest {
    @Before public void setup() {
        var provider = Robolectric.buildContentProvider(TargetProvider.class).create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
        TargetProvider.entered = new CountDownLatch(1); TargetProvider.release = new CountDownLatch(1);
        TargetProvider.calls.set(0); TargetProvider.onUi = false; TargetProvider.request = null;
        TargetProvider.response = null;
    }
    @Test public void configurationReadIsOffUiSingleFlightAndOnlyInvalidatesLiveModes() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SelectionTargetReader reader = new SelectionTargetReader(context, "com.example.source");
        Mode live = new Mode(), destroyed = new Mode();
        reader.watch(live); reader.watch(destroyed); reader.watch(live);
        try {
            assertTrue("No background configuration read", TargetProvider.entered.await(3, TimeUnit.SECONDS));
            assertFalse(TargetProvider.onUi); assertEquals(1, TargetProvider.calls.get());
            assertNull(reader.get()); reader.forget(destroyed);
        } finally { TargetProvider.release.countDown(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((reader.get() == null || live.invalidated == 0) && System.nanoTime() < deadline) {
            ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle(); Thread.sleep(10);
        }
        ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
        assertNotNull(reader.get()); assertEquals(1, live.invalidated); assertEquals(0, destroyed.invalidated);
        assertEquals(Set.of("source"), TargetProvider.request.keySet());
        assertEquals("com.example.source", TargetProvider.request.getString("source"));
        reader.watch(live); assertEquals(1, TargetProvider.calls.get());
    }
    @Test public void expiredClickRefreshesOffUiAndOnlyDispatchesForStillLiveModes() throws Exception {
        SelectionTargetReader reader = new SelectionTargetReader(RuntimeEnvironment.getApplication(), "com.example.source");
        Mode live = new Mode(), dying = new Mode(); reader.watch(live); reader.watch(dying);
        try { assertTrue(TargetProvider.entered.await(3, TimeUnit.SECONDS)); }
        finally { TargetProvider.release.countDown(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (reader.get() == null && System.nanoTime() < deadline) Thread.sleep(10);
        ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle(); assertNotNull(reader.get());
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3)); assertNull(reader.get());
        TargetProvider.entered = new CountDownLatch(1); TargetProvider.release = new CountDownLatch(1);
        AtomicInteger liveClicks = new AtomicInteger(), deadClicks = new AtomicInteger();
        reader.withFresh(live, target -> { assertNotNull(target); liveClicks.incrementAndGet(); });
        reader.withFresh(dying, target -> deadClicks.incrementAndGet());
        try {
            assertEquals(0, liveClicks.get()); assertTrue(TargetProvider.entered.await(3, TimeUnit.SECONDS));
            reader.forget(dying);
        } finally { TargetProvider.release.countDown(); }
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (liveClicks.get() == 0 && System.nanoTime() < deadline) {
            ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle(); Thread.sleep(10);
        }
        assertEquals(1, liveClicks.get()); assertEquals(0, deadClicks.get()); assertEquals(2, TargetProvider.calls.get());
    }
    public static final class TargetProvider extends ContentProvider {
        static CountDownLatch entered, release; static final AtomicInteger calls = new AtomicInteger();
        static volatile boolean onUi; static volatile Bundle request, response;
        public boolean onCreate() { return true; }
        public Bundle call(String method, String arg, Bundle input) {
            if (!"selection_actions".equals(method)) throw new IllegalArgumentException();
            calls.incrementAndGet(); onUi = Looper.myLooper() == Looper.getMainLooper(); request = new Bundle(input); entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) return Bundle.EMPTY; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Bundle.EMPTY; }
            if (response != null) return new Bundle(response);
            Bundle result = new Bundle(); result.putBoolean("enabled", true);
            result.putBoolean("browser_configured", false); result.putBoolean("translation_configured", false); return result;
        }
        public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        public String getType(Uri u) { return null; } public Uri insert(Uri u, ContentValues v) { return null; }
        public int delete(Uri u, String s, String[] a) { return 0; } public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }
    private static final class Mode extends ActionMode {
        int invalidated;
        public void setTitle(CharSequence text) { } public void setSubtitle(CharSequence text) { }
        public void setTitle(int id) { } public void setSubtitle(int id) { } public void setCustomView(View view) { }
        public void invalidate() { invalidated++; } public void finish() { } public Menu getMenu() { return null; }
        public CharSequence getTitle() { return ""; } public CharSequence getSubtitle() { return ""; }
        public View getCustomView() { return null; } public MenuInflater getMenuInflater() { return null; }
    }
}
