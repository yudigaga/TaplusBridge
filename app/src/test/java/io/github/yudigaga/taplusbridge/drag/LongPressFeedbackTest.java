package io.github.yudigaga.taplusbridge.drag;

import android.content.Context;
import android.os.Vibrator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowVibrator;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class LongPressFeedbackTest {
    @Test public void recognitionFeedbackIsIndependentOfDragAndRequiresAnEligibleStage() {
        assertTrue(LongPressFeedback.recognitionReady(true, true, false, 4_000, 4_000));
        assertTrue(LongPressFeedback.recognitionReady(true, false, true, 4_000, 4_000));
        assertFalse(LongPressFeedback.recognitionReady(false, true, false, 4_000, 4_000));
        assertFalse(LongPressFeedback.recognitionReady(true, false, false, 4_000, 4_000));
        assertFalse(LongPressFeedback.recognitionReady(true, true, false, 4_000, 3_999));
    }

    @Test public void recognitionAndSuccessfulDragUseDistinctPulseCounts() {
        assertArrayEquals(new long[]{0, 40}, LongPressFeedback.vibrationPattern(false));
        assertArrayEquals(new long[]{0, 40, 80, 40}, LongPressFeedback.vibrationPattern(true));
    }

    @Test public void dragReadyFeedbackUsesTheCallingProcessVibrator() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowVibrator shadow = Shadow.extract(context.getSystemService(Vibrator.class));
        shadow.setHasVibrator(true);
        assertTrue(LongPressFeedback.vibrate(context, true));
        assertArrayEquals(new long[]{0, 40, 80, 40}, shadow.getPattern());
    }
}
