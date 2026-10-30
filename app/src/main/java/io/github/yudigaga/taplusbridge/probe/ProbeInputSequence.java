package io.github.yudigaga.taplusbridge.probe;

/** One targeted DOWN/UP attempt. Failed or interrupted delivery is cleaned up with CANCEL only. */
public final class ProbeInputSequence {
    public static final int DOWN = 0, UP = 1, CANCEL = 3;
    @FunctionalInterface public interface Check { void verify() throws ReflectiveOperationException; }
    @FunctionalInterface public interface StepCheck { void verify(int action) throws ReflectiveOperationException; }
    @FunctionalInterface public interface Sender { boolean send(int action) throws ReflectiveOperationException; }
    private ProbeInputSequence() { }
    public static void run(Check check, Sender sender) throws ReflectiveOperationException {
        run(action -> check.verify(), sender);
    }
    public static final class DeliveryFailure extends IllegalStateException {
        public final int action;
        DeliveryFailure(int action) { super(action == DOWN ? "input_down" : "input_up"); this.action = action; }
    }
    public static void run(StepCheck check, Sender sender) throws ReflectiveOperationException {
        boolean attempted = false, finished = false;
        try {
            check.verify(DOWN); attempted = true;
            if (!sender.send(DOWN)) throw new DeliveryFailure(DOWN);
            check.verify(UP);
            if (!sender.send(UP)) throw new DeliveryFailure(UP);
            finished = true;
        } finally {
            if (attempted && !finished) try { sender.send(CANCEL); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
    }
}
