package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class DragTicketPrivacyAndroidTest extends BridgeRobolectricFixture {
    private static final String FIRST = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    private static final String SECOND = "ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/";
    @Before public void installRuleTarget() {
        install("com.ylmf.androidclient", true, false);
        RuleRepository rules = new RuleRepository(context);
        rules.saveManualRule(-1, "*", "magnet:", "com.ylmf.androidclient", "none", "batch");
        rules.saveManualRule(-1, "*", "ed2k:", "com.ylmf.androidclient", "none", "batch");
    }
    private Bundle request(boolean ephemeral) {
        Bundle request = new Bundle(); request.putStringArrayList("links", new ArrayList<>(List.of(FIRST, SECOND)));
        request.putBoolean("ephemeral", ephemeral); return request;
    }
    @Test public void recognizedBatchUsesAMemoryTicketWhileTargetIsNotReadyAndNeverPersistsLinks() {
        Bundle request = request(true);
        assertNull(LaunchPlanner.directIfReady(context, LaunchPlanner.plan(context, request)));
        String token = BridgeProvider.issueDrag(context, request); assertNotNull(token); assertTrue(token.startsWith("p_"));
        assertTrue(context.getSharedPreferences("launch_tickets", Context.MODE_PRIVATE).getAll().isEmpty());
        Bundle consumed = BridgeProvider.consume(context, token); assertNotNull(consumed);
        assertEquals(List.of(FIRST, SECOND), consumed.getStringArrayList("links")); assertTrue(consumed.getBoolean("ephemeral"));
        assertNull(BridgeProvider.consume(context, token));
    }
    @Test public void explicitNonEphemeralTicketRetainsItsExistingPersistentRoundTrip() {
        String token = BridgeProvider.issueDrag(context, request(false)); assertNotNull(token); assertFalse(token.startsWith("p_"));
        assertTrue(context.getSharedPreferences("launch_tickets", Context.MODE_PRIVATE).contains(token));
        Bundle consumed = BridgeProvider.consume(context, token); assertNotNull(consumed);
        assertEquals(List.of(FIRST, SECOND), consumed.getStringArrayList("links"));
        assertNull(BridgeProvider.consume(context, token));
    }
}
