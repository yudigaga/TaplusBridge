package io.github.yudigaga.taplusbridge.bridge;
import org.junit.Test;
import static org.junit.Assert.*;
public final class TicketPolicyTest {
    @Test public void expiryAndClockRollbackCannotReviveTicket() {
        assertTrue(TicketPolicy.live(1000, 1000));
        assertTrue(TicketPolicy.live(1000, 30999));
        assertFalse(TicketPolicy.live(1000, 31000));
        assertFalse(TicketPolicy.live(1000, 999));
    }
}
