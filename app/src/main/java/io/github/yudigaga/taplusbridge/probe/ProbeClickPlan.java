package io.github.yudigaga.taplusbridge.probe;

import java.util.function.BooleanSupplier;

/** A single optional modal-menu dismissal, then at most one card tap. No BACK key or retries. */
public final class ProbeClickPlan {
    public enum Window { MAIN, OUTSIDE_POPUP }
    @FunctionalInterface public interface WindowCheck { Window inspect() throws ReflectiveOperationException; }
    private static final class AlreadyClaimed extends RuntimeException { }
    private ProbeClickPlan() { }

    public static void run(WindowCheck windows, BooleanSupplier inFlight, BooleanSupplier awaitingLaunch,
                           ProbeInputSequence.Sender sender) throws ReflectiveOperationException {
        Window initial = windows.inspect();
        if (!inFlight.getAsBoolean()) throw new IllegalStateException("probe_cancelled");
        if (!awaitingLaunch.getAsBoolean()) return;
        if (initial == Window.OUTSIDE_POPUP) {
            boolean attempted = false;
            try {
                owned(inFlight, awaitingLaunch, ProbeInputSequence.DOWN);
                // Standard PopupDecorView dismisses on outside DOWN. Never finish a first click:
                // if the menu vanishes in the race and DOWN reaches the card, CANCEL follows.
                windows.inspect();
                attempted = true;
                if (!sender.send(ProbeInputSequence.DOWN)) throw new ProbeInputSequence.DeliveryFailure(ProbeInputSequence.DOWN);
            } catch (AlreadyClaimed captured) { return; }
            finally {
                if (attempted) try { sender.send(ProbeInputSequence.CANCEL); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
            if (!awaitingLaunch.getAsBoolean()) return;
            if (windows.inspect() != Window.MAIN) throw new IllegalStateException("popup_not_dismissed");
        }
        try {
            ProbeInputSequence.run(action -> {
                owned(inFlight, awaitingLaunch, action);
                if (windows.inspect() != Window.MAIN) throw new IllegalStateException("source_window_changed");
            }, sender);
        } catch (AlreadyClaimed captured) { /* An earlier owned launch already supplies the result. */ }
    }
    private static void owned(BooleanSupplier inFlight, BooleanSupplier awaitingLaunch, int action) {
        if (!inFlight.getAsBoolean()) throw new IllegalStateException("probe_cancelled");
        if (action == ProbeInputSequence.DOWN && !awaitingLaunch.getAsBoolean()) throw new AlreadyClaimed();
    }
}
