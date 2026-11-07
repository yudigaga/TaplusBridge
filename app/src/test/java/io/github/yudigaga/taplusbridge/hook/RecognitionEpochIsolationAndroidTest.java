package io.github.yudigaga.taplusbridge.hook;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Queued callbacks use their real task/observer identity, without passing the current epoch to them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RecognitionEpochIsolationAndroidTest {
    @Test public void recognizedDownloadLinksRemainAvailableToTheSameDragSnapshot() {
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
        String ed2k = "ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/";
        enhancer.captureProbeUrl(magnet); enhancer.captureProbeUrl(ed2k);
        assertEquals(List.of(magnet, ed2k), enhancer.capturedLinks());
        RecognitionHooks.DragSnapshot snapshot = new RecognitionHooks.DragSnapshot(7, false, enhancer.capturedLinks());
        assertEquals(List.of(magnet, ed2k), snapshot.links());
        reset(8);
        assertTrue(enhancer.capturedLinks().isEmpty());
        assertEquals(List.of(magnet, ed2k), snapshot.links());
    }

    private static final String URL="https://www.bilibili.com/video/BV1xx411c7mD";
    private final RecognitionEpochs origins=new RecognitionEpochs();
    private final AtomicBoolean active=new AtomicBoolean(true);
    private HostRecognitionEnhancer enhancer;
    private AutoProbeReadiness readiness;
    private ShadowLooper main;
    private int delivered;
    @Before public void setup() throws Exception {
        enhancer=new HostRecognitionEnhancer(Model.class);
        readiness=new AutoProbeReadiness(() -> !enhancer.capturedLinks().isEmpty());
        main=Shadow.extract(Looper.getMainLooper()); reset(1);
    }
    private void reset(long epoch) { origins.reset(epoch);readiness.reset(epoch);enhancer.clear(); }
    private void callback(Object owner,String url,boolean segment) {
        try {
            origins.callback(owner,active::get,() -> {
                delivered++;
                if(url!=null) { Model result=new Model();result.detailUrl=url;enhancer.captureRecommendationLinks(List.of(result)); }
                if(segment)readiness.segmentComplete(origins.scopeEpoch());
                return null;
            });
        } catch(Throwable failure) { throw new AssertionError(failure); }
    }
    private void enqueue(Object owner,String url,boolean segment) {
        new Handler(Looper.getMainLooper()).post(() -> callback(owner,url,segment));
    }
    @Test public void queuedOldTextSegmentCannotCompleteNewImagePickBeforeItsOcr() {
        FakeTask task=new FakeTask();assertTrue(origins.bind(task,1));enqueue(task,null,true);
        reset(2);main.idle();
        assertEquals(0,delivered);assertFalse(readiness.ready(2));assertFalse(readiness.claim(2));
    }
    @Test public void queuedOldNetworkDetailCannotInsertItsUrlIntoNewImagePick() {
        FakeTask task=new FakeTask();assertTrue(origins.bind(task,1));enqueue(task,URL,false);
        // The same old object may still occupy the host's static last-task field.
        FakeHost.recommendation=task;reset(2);main.idle();
        assertSame(task,FakeHost.recommendation);assertTrue(enhancer.capturedLinks().isEmpty());assertEquals(0,delivered);
    }
    @Test public void queuedOldImageObserverCannotStartWorkOrPublishUrlInNewTextPick() {
        FakeObserver old=new FakeObserver();assertTrue(origins.bind(old,1));enqueue(old,URL,true);
        reset(2);main.idle();assertEquals(0,delivered);assertFalse(readiness.ready(2));
        FakeTask current=new FakeTask();assertTrue(origins.bind(current,2));enqueue(current,null,true);main.idle();
        assertEquals(1,delivered);assertTrue(readiness.ready(2));
    }
    @Test public void queuedCurrentTaskRetainsItsScopeAndUrlCancelsProbeEligibility() {
        FakeTask current=new FakeTask();assertTrue(origins.bind(current,1));
        enqueue(current,URL,true);main.idle();
        assertEquals(List.of(URL),enhancer.capturedLinks());assertFalse(readiness.claim(1));assertEquals(-1,origins.scopeEpoch());
    }
    @Test public void untrackedObserverIsRejectedDuringPickButNormalClipboardCallbackStillRuns() {
        Object unknown=new Object();enqueue(unknown,URL,true);main.idle();assertEquals(0,delivered);
        active.set(false);enqueue(unknown,null,false);main.idle();assertEquals(1,delivered);
    }
    @Test public void oldObjectCannotBeReboundFromUnchangedHostDisposableField() {
        FakeObserver old=new FakeObserver();assertTrue(origins.bind(old,1));reset(2);
        assertFalse(origins.bind(old,2));enqueue(old,URL,true);main.idle();assertEquals(0,delivered);
    }
    @Test public void dragLinksRequireTheExactNativeServiceResultAndBoundEpoch() {
        String service = new String("service"), result = new String("result");
        RecognitionHooks.DragPick pick = new RecognitionHooks.DragPick(7, service, result, 100);
        assertTrue(pick.matches(service, result, -1));
        assertTrue(pick.matches(service, result, 7));
        assertFalse(pick.matches(new String("service"), result, 7));
        assertFalse(pick.matches(service, new String("result"), 7));
        assertFalse(pick.matches(service, result, 6));
        RecognitionHooks.DragPick replayed = new RecognitionHooks.DragPick(8, service, result, 200);
        assertFalse(replayed.matches(service, result, 7));
        assertTrue(replayed.matches(service, result, 8));
    }
    @Test public void capturedDragLinksAreIndependentBoundedAndSafeForTheCompletionBoundary() {
        ArrayList<String> links = new ArrayList<>(List.of("javascript:alert(1)", URL,
                "https://example.com/%0Ahidden"));
        for (int i = 0; i < 12; i++) links.add("https://example.com/card/" + i);
        RecognitionHooks.DragSnapshot snapshot = new RecognitionHooks.DragSnapshot(7, false, links);
        links.clear();
        assertEquals(7, snapshot.epoch()); assertFalse(snapshot.waiting());
        assertEquals(13, snapshot.links().size()); assertEquals(URL, snapshot.links().get(0));
        assertTrue(snapshot.links().stream().allMatch(io.github.yudigaga.taplusbridge.capture.IntentObservation::validHttpUrl));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.links().add("https://example.org/later"));
    }
    @SuppressWarnings("deprecation")
    @Test public void dragWaitsForAnActualCurrentRecommendationTaskButNeverAnOldTask() {
        FakeTask task = new FakeTask();
        assertTrue(RecognitionHooks.pendingRecommendation(task, 7, 7));
        assertFalse(RecognitionHooks.pendingRecommendation(task, 6, 7));
        assertFalse(RecognitionHooks.pendingRecommendation(null, 7, 7));
        task.executeOnExecutor(Runnable::run); main.idle();
        assertEquals(AsyncTask.Status.FINISHED, task.getStatus());
        assertFalse(RecognitionHooks.pendingRecommendation(task, 7, 7));
    }
    @Test public void synchronousNullBitmapResultUsesCreatorScopeAndRestoresIt() throws Throwable {
        origins.scoped(1,() -> {
            assertTrue(origins.scopeCurrent(1));readiness.segmentComplete(origins.scopeEpoch());return null;
        });
        assertTrue(readiness.ready(1));assertEquals(-1,origins.scopeEpoch());
    }
    @Test public void resetCancelsBothActualAndroidTasksDisposesOcrAndClearsBusyFlag() throws Exception {
        FakeHost.segment=new FakeTask();FakeHost.recommendation=new FakeTask();FakeHost.ocr=new FakeObserver();FakeHost.segmenting=true;
        origins.bind(FakeHost.segment,1);origins.bind(FakeHost.recommendation,1);origins.bind(FakeHost.ocr,1);
        reset(2);
        RecognitionTaskCleanup.reset(field("segment"),field("recommendation"),field("ocr"),field("segmenting"));
        assertTrue(FakeHost.segment.isCancelled());assertTrue(FakeHost.recommendation.isCancelled());
        assertTrue(FakeHost.ocr.disposed);assertFalse(FakeHost.segmenting);
        // Even an already queued typed callback that bypassed cancellation cannot enter the new pick.
        enqueue(FakeHost.segment,null,true);enqueue(FakeHost.recommendation,URL,false);enqueue(FakeHost.ocr,URL,true);
        main.idle();assertEquals(0,delivered);assertFalse(readiness.ready(2));assertTrue(enhancer.capturedLinks().isEmpty());
    }
    private static Field field(String name) throws Exception { return FakeHost.class.getDeclaredField(name); }
    public static final class FakeHost {
        public static FakeTask segment,recommendation;
        public static FakeObserver ocr;
        public static boolean segmenting;
    }
    @SuppressWarnings("deprecation") public static final class FakeTask extends AsyncTask<Void,Void,String> {
        @Override protected String doInBackground(Void... ignored) { return ""; }
    }
    public static final class FakeObserver { boolean disposed;public void dispose() { disposed=true; } }
    public static final class Model { String title,cat,detailUrl,packageName,intent,sourceIcon; }
}
