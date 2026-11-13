package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

/** Exercises the live drag boundary with the current policy, task owner, and exact native pick. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RecognitionDragReadinessAndroidTest {
    private static final long EPOCH = 7;
    private static final String URL = "https://example.com/first";
    private static final String SECOND_URL = "https://example.org/second";
    private RecognitionHooks hooks;
    private HostRecognitionEnhancer enhancer;
    private RecognitionEpochs origins;
    private PolicyProvider provider;
    private final Object service = new Object(), result = new Object();

    @Before public void setup() throws Exception {
        FakeHost.context = RuntimeEnvironment.getApplication();
        provider = Robolectric.buildContentProvider(PolicyProvider.class).create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
        enhancer = new HostRecognitionEnhancer(Model.class);
        hooks = new RecognitionHooks(null, () -> "com.example.source", enhancer,
                FakeHost.class.getDeclaredMethod("application"), FakeHost.class.getDeclaredMethod("publish", ArrayList.class));
        field(RecognitionHooks.class, "active").setBoolean(hooks, true);
        field(RecognitionHooks.class, "epoch").setLong(hooks, EPOCH);
        field(RecognitionHooks.class, "dragPick").set(hooks, new RecognitionHooks.DragPick(EPOCH, service, result, 1000));
        field(RecognitionHooks.class, "recommendationTask").set(hooks, FakeHost.class.getDeclaredField("task"));
        origins = (RecognitionEpochs) field(RecognitionHooks.class, "origins").get(hooks);
        recommendationFrom(EPOCH);
    }

    @After public void resetHost() {
        FakeHost.context = null;
        FakeHost.task = null;
    }

    @Test public void multipleCurrentLinksAreReadyWhileTheOnlineRecommendationIsStillPending() {
        enhancer.captureText(URL + "\n" + SECOND_URL, "assist");
        RecognitionHooks.DragSnapshot snapshot = hooks.dragSnapshot(service, result, EPOCH);
        assertNotNull(snapshot);
        assertEquals(AsyncTask.Status.PENDING, FakeHost.task.getStatus());
        assertFalse("Captured current-pick URLs must be usable before unrelated online recommendations finish", snapshot.waiting());
        assertEquals(List.of(URL, SECOND_URL), snapshot.links());
        enhancer.clear();
        assertEquals(List.of(URL, SECOND_URL), snapshot.links());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.links().add("https://example.net/later"));
    }

    @Test public void onlyInvalidCapturedEntriesStillWaitForTheCurrentOnlineRecommendation() throws Exception {
        // Inject malformed capture data to exercise the final validation boundary rather than the extractor.
        capturedEntries().addAll(List.of("javascript:alert(1)", "https://example.com/%0Ahidden"));
        RecognitionHooks.DragSnapshot snapshot = hooks.dragSnapshot(service, result, EPOCH);
        assertNotNull(snapshot);
        assertTrue(snapshot.links().isEmpty());
        assertTrue("Rejected URLs cannot count as an actionable result", snapshot.waiting());
    }

    @Test public void noCapturedLinkStillWaitsForTheCurrentOnlineRecommendation() {
        RecognitionHooks.DragSnapshot snapshot = hooks.dragSnapshot(service, result, EPOCH);
        assertNotNull(snapshot);
        assertTrue(snapshot.links().isEmpty());
        assertTrue(snapshot.waiting());
    }

    @Test public void anotherServiceResultEpochOrInactivePickCannotBorrowCapturedLinks() throws Exception {
        enhancer.captureText(URL, "assist");
        assertNull(hooks.dragSnapshot(new Object(), result, EPOCH));
        assertNull(hooks.dragSnapshot(service, new Object(), EPOCH));
        assertNull(hooks.dragSnapshot(service, result, EPOCH - 1));
        assertEquals(List.of(URL), hooks.dragSnapshot(service, result, -1).links());
        field(RecognitionHooks.class, "active").setBoolean(hooks, false);
        assertNull(hooks.dragSnapshot(service, result, EPOCH));
    }

    @Test public void anOldRecommendationTaskCannotHoldTheCurrentDrag() {
        recommendationFrom(EPOCH - 1);
        RecognitionHooks.DragSnapshot snapshot = hooks.dragSnapshot(service, result, EPOCH);
        assertNotNull(snapshot);
        assertTrue(snapshot.links().isEmpty());
        assertFalse(snapshot.waiting());
    }

    @Test public void disabledOnlineRecommendationsNeverHoldAnEmptyDrag() {
        provider.serverEnabled = false;
        RecognitionHooks.DragSnapshot snapshot = hooks.dragSnapshot(service, result, EPOCH);
        assertNotNull(snapshot);
        assertTrue(snapshot.links().isEmpty());
        assertFalse(snapshot.waiting());
    }

    @Test public void aFinishedOnlineRecommendationReleasesAnEmptyDrag() {
        FakeHost.task.executeOnExecutor(Runnable::run);
        ShadowLooper.idleMainLooper();
        assertEquals(AsyncTask.Status.FINISHED, FakeHost.task.getStatus());
        RecognitionHooks.DragSnapshot snapshot = hooks.dragSnapshot(service, result, EPOCH);
        assertNotNull(snapshot);
        assertTrue(snapshot.links().isEmpty());
        assertFalse(snapshot.waiting());
    }

    private void recommendationFrom(long taskEpoch) {
        FakeHost.task = new FakeTask();
        origins.reset(taskEpoch);
        assertTrue(origins.bind(FakeHost.task, taskEpoch));
        origins.reset(EPOCH);
    }

    @SuppressWarnings("unchecked") private Set<String> capturedEntries() throws Exception {
        return (Set<String>) field(HostRecognitionEnhancer.class, "links").get(enhancer);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public static final class FakeHost {
        static Context context;
        public static FakeTask task;
        public static Context application() { return context; }
        public static void publish(ArrayList<?> cards) { }
    }

    @SuppressWarnings("deprecation") public static final class FakeTask extends AsyncTask<Void, Void, String> {
        @Override protected String doInBackground(Void... ignored) { return ""; }
    }

    public static final class Model { public String title, cat, detailUrl, packageName, intent, sourceIcon; }

    public static final class PolicyProvider extends ContentProvider {
        boolean serverEnabled = true;
        public boolean onCreate() { return true; }
        public Bundle call(String method, String arg, Bundle input) {
            if (!RecognitionChannels.KEY.equals(method)) return null;
            Bundle response = new Bundle();
            response.putBoolean("active", true);
            response.putString("identity", "a".repeat(64));
            response.putString("channels", RecognitionChannels.defaults().with(Channel.SERVER, serverEnabled).toJson());
            return response;
        }
        public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        public String getType(Uri u) { return null; }
        public Uri insert(Uri u, ContentValues v) { return null; }
        public int delete(Uri u, String s, String[] a) { return 0; }
        public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }
}
