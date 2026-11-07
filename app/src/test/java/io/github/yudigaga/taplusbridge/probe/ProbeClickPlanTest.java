package io.github.yudigaga.taplusbridge.probe;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProbeClickPlanTest {
    @Test public void mainWindowNeedsOnlyOneActualCardTap() throws Exception {
        List<Integer> events = new ArrayList<>();
        ProbeClickPlan.run(() -> ProbeClickPlan.Window.MAIN, () -> true, () -> true,
                action -> { events.add(action); return true; });
        assertEquals(List.of(0, 1), events);
    }
    @Test public void outsideMenuUsesDownCancelBeforeSingleCardTap() throws Exception {
        List<Integer> events = new ArrayList<>(); boolean[] popup = {true};
        ProbeClickPlan.run(() -> popup[0] ? ProbeClickPlan.Window.OUTSIDE_POPUP : ProbeClickPlan.Window.MAIN,
                () -> true, () -> true, action -> {
                    events.add(action); if (events.size() == 1 && action == 0) popup[0] = false;
                    return true;
                });
        assertEquals(List.of(0, 3, 0, 1), events);
    }
    @Test public void downAlreadyCapturedInRaceCancelsAndNeverClicksAgain() throws Exception {
        List<Integer> events = new ArrayList<>(); boolean[] waiting = {true};
        ProbeClickPlan.run(() -> ProbeClickPlan.Window.OUTSIDE_POPUP, () -> true, () -> waiting[0],
                action -> { events.add(action); if (action == 0) waiting[0] = false; return true; });
        assertEquals(List.of(0, 3), events);
    }
    @Test public void popupDisappearsBetweenInitialCheckAndDownStillNeverCompletesFirstClick() throws Exception {
        List<Integer> events = new ArrayList<>(); int[] checks = {0};
        ProbeClickPlan.run(() -> ++checks[0] == 1 ? ProbeClickPlan.Window.OUTSIDE_POPUP : ProbeClickPlan.Window.MAIN,
                () -> true, () -> true, action -> { events.add(action); return true; });
        assertEquals(List.of(0, 3, 0, 1), events);
    }
    @Test public void popupThatDoesNotDismissNeverReceivesUpOrRetry() throws Exception {
        List<Integer> events = new ArrayList<>();
        try { ProbeClickPlan.run(() -> ProbeClickPlan.Window.OUTSIDE_POPUP, () -> true, () -> true,
                action -> { events.add(action); return true; }); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(List.of(0, 3), events);
    }
    @Test public void insideMenuOrUnknownWindowRejectsBeforeAnyInput() throws Exception {
        List<Integer> events = new ArrayList<>();
        try { ProbeClickPlan.run(() -> { throw new IllegalStateException("window_occluded"); }, () -> true, () -> true,
                action -> { events.add(action); return true; }); fail(); }
        catch (IllegalStateException expected) { }
        assertTrue(events.isEmpty());
    }
    @Test public void failedPreparationDownGetsCancelButNeverSecondTap() throws Exception {
        List<Integer> events = new ArrayList<>();
        try { ProbeClickPlan.run(() -> ProbeClickPlan.Window.OUTSIDE_POPUP, () -> true, () -> true,
                action -> { events.add(action); return action != 0; }); fail(); }
        catch (ProbeInputSequence.DeliveryFailure expected) { }
        assertEquals(List.of(0, 3), events);
    }
    @Test public void cancelledBetweenDismissAndTapNeverClicksCard() throws Exception {
        List<Integer> events = new ArrayList<>(); boolean[] active = {true};
        try { ProbeClickPlan.run(() -> events.isEmpty() ? ProbeClickPlan.Window.OUTSIDE_POPUP : ProbeClickPlan.Window.MAIN,
                () -> active[0], () -> true, action -> { events.add(action); if (action == 3) active[0] = false; return true; }); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals(List.of(0, 3), events);
    }
}
