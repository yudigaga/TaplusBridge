package io.github.yudigaga.taplusbridge.probe;

/** Versioned two-step protocol. Legacy observation ACKs must never permit input injection. */
public final class ProbeContract {
    public static final int VERSION = 2;
    public static final int READY = 11, CAPTURED = 12, FAILED = 13;
    public static final String MODE = "invisible";
    public static final String ARM = "io.github.yudigaga.taplusbridge.PROBE_ARM";
    public static final String TAP = "io.github.yudigaga.taplusbridge.PROBE_TAP";
    public static final long TIMEOUT_MILLIS = 6000;
    public static final long LINK_RESULT_GRACE_MILLIS = TIMEOUT_MILLIS + 500;
    private ProbeContract() { }
}
