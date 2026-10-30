package io.github.yudigaga.taplusbridge.bridge;

/** Single-use launch tickets survive process recreation, but expire quickly and have a bounded store. */
public final class TicketPolicy {
    public static final long TTL_MILLIS = 30_000;
    public static final int CAPACITY = 32;
    public static boolean live(long issuedAt, long now) { return now >= issuedAt && now - issuedAt < TTL_MILLIS; }
    private TicketPolicy() {}
}
