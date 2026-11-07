package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class EphemeralTicketStoreAndroidTest {
    @Test public void batchRequestIsClonedAndNeverPersistedOrChangedByTheCaller() {
        String first = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
        String second = "ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/";
        ArrayList<String> links = new ArrayList<>(java.util.List.of(first, second));
        Bundle request = new Bundle(); request.putStringArrayList("links", links);
        String token = issue(request); assertNotNull(token); links.clear();
        Bundle found = EphemeralTicketStore.consume(token);
        assertEquals(java.util.List.of(first, second), found.getStringArrayList("links"));
        assertTrue(found.getBoolean("ephemeral"));
    }
    @Test public void malformedBatchCannotBeRetainedInAnEphemeralTicket() {
        Bundle bad = new Bundle(); bad.putString("links", "not a list"); assertNull(issue(bad));
    }

    private final ArrayList<String> issued = new ArrayList<>();
    private Bundle request() {
        Bundle request = new Bundle(); request.putString("url", "https://www.bilibili.com/video/BVtest");
        request.putString("source", "com.tencent.mm");
        request.putParcelable("original", new Intent(Intent.ACTION_VIEW, Uri.parse("https://private.example/fallback")));
        request.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://video/test")));
        return request;
    }
    private String issue(Bundle request) { String token = EphemeralTicketStore.issue(request); if (token != null) issued.add(token); return token; }
    @After public void clearIssued() { for (String token : issued) EphemeralTicketStore.consume(token); }

    @Test public void probeTicketIsSingleUseAndHasNoOriginalFallbackOrDiskCopy() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("launch_tickets", Context.MODE_PRIVATE).edit().clear().commit();
        Bundle request = request(); String token = issue(request);
        assertTrue(token.startsWith("p_"));
        assertTrue(context.getSharedPreferences("launch_tickets", Context.MODE_PRIVATE).getAll().isEmpty());
        Bundle found = EphemeralTicketStore.consume(token);
        assertEquals(request.getString("url"), found.getString("url"));
        assertTrue(found.getBoolean("ephemeral")); assertFalse(found.containsKey("original"));
        assertNull(EphemeralTicketStore.consume(token));
    }
    @Test public void expiryDoesNotReconstructFromDiskOrReturnOriginal() {
        String token = issue(request()); ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
        assertNull(EphemeralTicketStore.consume(token));
    }
    @Test public void requestMutationCannotChangeStoredDestination() {
        Bundle request = request(); String token = issue(request);
        @SuppressWarnings("deprecation") Intent candidate = request.getParcelable("candidate");
        candidate.setData(Uri.parse("bilibili://other")); request.putString("url", "https://changed.example/");
        Bundle found = EphemeralTicketStore.consume(token);
        @SuppressWarnings("deprecation") Intent stored = found.getParcelable("candidate");
        assertEquals("bilibili://video/test", stored.getDataString());
        assertEquals("https://www.bilibili.com/video/BVtest", found.getString("url"));
    }
    @Test public void boundedCapacityRejectsFurtherRequestsWithoutEvictingLiveOnes() {
        for (int i = 0; i < TicketPolicy.CAPACITY; i++) assertNotNull(issue(request()));
        assertNull(issue(request()));
        assertNotNull(EphemeralTicketStore.consume(issued.get(0)));
        assertNotNull(issue(request()));
    }
}
