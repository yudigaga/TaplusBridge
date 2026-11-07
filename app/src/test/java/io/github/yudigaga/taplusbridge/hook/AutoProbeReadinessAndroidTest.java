package io.github.yudigaga.taplusbridge.hook;

import android.content.Intent;
import android.net.Uri;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real enhancer links feed the same readiness barrier used by RecognitionHooks and HostProbeAction. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class AutoProbeReadinessAndroidTest {
    private static final String URL="https://www.bilibili.com/video/BV1xx411c7mD";
    private HostRecognitionEnhancer enhancer;
    private AutoProbeReadiness state;
    @Before public void setup() throws Exception {
        enhancer=new HostRecognitionEnhancer(Model.class);
        state=new AutoProbeReadiness(() -> !enhancer.capturedLinks().isEmpty()); state.reset(1);
    }
    @Test public void returningFromPickTaskIsNotRecognitionCompletion() {
        assertFalse(state.ready(1)); assertFalse(state.claim(1)); assertFalse(state.current(1));
    }
    @Test public void emptySegmentWithoutNetworkIsAnExplicitCompletionAndCanClaimOnce() {
        state.segmentComplete(1); assertTrue(state.ready(1)); assertTrue(state.claim(1));
        assertFalse(state.claim(1)); assertTrue(state.current(1));
    }
    @Test public void onlineEmptyOcrCompletesWithoutWaitingForCallbacksThatNeverOccur() {
        state.localOcrComplete(1,""); assertTrue(state.ready(1));
        state.reset(2); state.localOcrComplete(2,null); assertTrue(state.ready(2));
        state.reset(3); state.localOcrComplete(3,"nonempty OCR still has asynchronous work"); assertFalse(state.ready(3));
    }
    @Test public void localSegmentCannotRaceAheadOfUnfinishedRecommendationTask() {
        long network=state.recommendationStarted(1); state.segmentComplete(1);
        assertTrue(state.awaitingRecommendation(1,network)); assertFalse(state.ready(1)); assertFalse(state.claim(1));
        assertTrue(state.recommendationFinished(1,network)); assertTrue(state.ready(1));
    }
    @Test public void networkFinishedBeforeSegmentStillWaitsForLocalCompletion() {
        long network=state.recommendationStarted(1); assertTrue(state.recommendationFinished(1,network));
        assertFalse(state.ready(1)); state.segmentComplete(1); assertTrue(state.ready(1));
        assertFalse(state.recommendationFinished(1,network));
    }
    @Test public void failedNetworkTaskCanCompleteWithoutAnyDetailCallback() {
        long network=state.recommendationStarted(1); state.segmentComplete(1);
        // AsyncTask FINISHED is observed even when onPostExecute returned for a null server response.
        assertTrue(state.recommendationFinished(1,network)); assertTrue(enhancer.capturedLinks().isEmpty());
        assertTrue(state.claim(1)); assertTrue(state.current(1));
    }
    @Test public void existingServerDetailUrlPreventsAnyProbeClaim() {
        long network=state.recommendationStarted(1); state.segmentComplete(1);
        Model model=new Model(); model.detailUrl=URL;
        enhancer.captureRecommendationLinks(List.of(model));
        assertEquals(List.of(URL),enhancer.capturedLinks()); state.recommendationFinished(1,network);
        assertFalse(state.ready(1)); assertFalse(state.claim(1));
    }
    @Test public void existingServerHttpIntentAlsoPreventsProbe() {
        Model model=new Model(); model.intent=new Intent(Intent.ACTION_VIEW,Uri.parse(URL)).toUri(0);
        enhancer.captureRecommendationLinks(List.of(model)); state.segmentComplete(1);
        assertEquals(List.of(URL),enhancer.capturedLinks()); assertFalse(state.claim(1));
    }
    @Test public void lateServerUrlRevokesAlreadyClaimedEligibilityBeforeTap() {
        state.segmentComplete(1); assertTrue(state.claim(1)); assertTrue(state.current(1));
        Model model=new Model(); model.detailUrl=URL; enhancer.captureRecommendationLinks(List.of(model));
        assertFalse(state.current(1));
        enhancer.clear(); state.reset(2); state.segmentComplete(2); assertTrue(state.claim(2)); assertTrue(state.current(2));
    }
    @Test public void replacedNetworkTaskAndOlderEpochCannotCompleteCurrentWork() {
        long stale=state.recommendationStarted(1), fresh=state.recommendationStarted(1); state.segmentComplete(1);
        assertFalse(state.recommendationFinished(1,stale)); assertFalse(state.ready(1));
        assertTrue(state.recommendationFinished(1,fresh)); assertTrue(state.ready(1));
        state.reset(2); state.segmentComplete(1); assertFalse(state.ready(2));
        assertFalse(state.recommendationFinished(1,fresh)); assertFalse(state.current(1));
    }
    @Test public void newRecognitionTaskRevokesAnAlreadyClaimedPick() {
        state.segmentComplete(1); assertTrue(state.claim(1));
        state.recommendationStarted(1); assertFalse(state.current(1));
    }
    public static final class Model {
        String title,cat,detailUrl,packageName,intent,sourceIcon;
    }
}
