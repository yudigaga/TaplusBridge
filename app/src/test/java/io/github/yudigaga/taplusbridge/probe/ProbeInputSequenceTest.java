package io.github.yudigaga.taplusbridge.probe;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProbeInputSequenceTest {
    @Test public void successfulAttemptVerifiesBothEventsAndNeverRepeatsTap() throws Exception {
        List<Integer> actions = new ArrayList<>(); int[] checks = {0};
        ProbeInputSequence.run(() -> checks[0]++, action -> { actions.add(action); return true; });
        assertEquals(List.of(0, 1), actions); assertEquals(2, checks[0]);
    }
    @Test public void staleForegroundBeforeDownNeverInjectsAnything() throws Exception {
        List<Integer> actions = new ArrayList<>();
        try { ProbeInputSequence.run(() -> { throw new IllegalStateException(); }, action -> { actions.add(action); return true; }); fail(); }
        catch (IllegalStateException expected) { }
        assertTrue(actions.isEmpty());
    }
    @Test public void failedDownIsCancelledEvenIfDeliveryStatusWasUncertain() throws Exception {
        List<Integer> actions = new ArrayList<>();
        try { ProbeInputSequence.run(() -> { }, action -> { actions.add(action); return action == 3; }); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(List.of(0, 3), actions);
    }
    @Test public void foregroundChangeAfterDownSendsCancelWithoutUpOrRetry() throws Exception {
        List<Integer> actions = new ArrayList<>(); int[] checks = {0};
        try { ProbeInputSequence.run(() -> { if (++checks[0] == 2) throw new IllegalStateException(); },
                action -> { actions.add(action); return true; }); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(List.of(0, 3), actions);
    }
    @Test public void failedUpIsCancelledWithoutRepeatingDown() throws Exception {
        List<Integer> actions = new ArrayList<>();
        try { ProbeInputSequence.run(() -> { }, action -> { actions.add(action); return action != 1; }); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(List.of(0, 1, 3), actions);
    }
    @Test public void cancelFailureDoesNotHideTheOriginalInputFailure() throws Exception {
        List<Integer> actions = new ArrayList<>();
        try { ProbeInputSequence.run(() -> { }, action -> {
            actions.add(action); if (action == 3) throw new IllegalArgumentException("cleanup");
            throw new IllegalStateException("original");
        }); fail(); } catch (IllegalStateException expected) { assertEquals("original", expected.getMessage()); }
        assertEquals(List.of(0, 3), actions);
    }
}
