package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class TicketStoreAndroidTest extends BridgeRobolectricFixture {
    @Test public void completeBatchSurvivesDiskRoundTripWithoutSharingTheCallersMutableList() {
        String first = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
        String second = "ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/";
        java.util.ArrayList<String> links = new java.util.ArrayList<>(java.util.List.of(first, second));
        Bundle request = new Bundle(); request.putStringArrayList("links", links);
        String token = new TicketStore(context).issue(request); assertNotNull(token);
        links.clear();
        assertEquals(java.util.List.of(first, second), new TicketStore(context).consume(token).getStringArrayList("links"));
    }
    @Test public void corruptedPersistedBatchAndMalformedIssueAreRejected() throws Exception {
        Bundle bad = new Bundle(); bad.putStringArrayList("links", new java.util.ArrayList<>(java.util.List.of("javascript:alert(1)")));
        assertNull(new TicketStore(context).issue(bad));
        String token = new TicketStore(context).issue(web("https://route.example/path"));
        SharedPreferences prefs = context.getSharedPreferences("launch_tickets", Context.MODE_PRIVATE);
        JSONObject item = new JSONObject(prefs.getString(token, "")); item.put("links", new org.json.JSONArray().put(7));
        prefs.edit().putString(token, item.toString()).commit();
        assertNull(new TicketStore(context).consume(token));
    }

    @Test
    public void ticketSurvivesStoreRecreationAndCanOnlyBeConsumedOnce() {
        Bundle request = web("https://route.example/path?utf8=%E4%B8%AD%E6%96%87&key=a%2Bb");
        request.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse("reader://article/42"))
                .setPackage(NATIVE).putExtra("mode", "read"));
        String token = new TicketStore(context).issue(request);
        assertNotNull(token);
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        Bundle restored = new TicketStore(context).consume(token);
        assertNotNull(restored);
        assertEquals(request.getString("url"), restored.getString("url"));
        assertEquals(request.getString("source"), restored.getString("source"));
        Intent candidate = restored.getParcelable("candidate");
        assertNotNull(candidate);
        assertEquals(NATIVE, candidate.getPackage());
        assertEquals("reader://article/42", candidate.getDataString());
        assertEquals("read", candidate.getStringExtra("mode"));
        assertNull(new TicketStore(context).consume(token));
    }

    @Test
    public void expiredPersistedTicketIsRejectedAndDeleted() throws Exception {
        String token = new TicketStore(context).issue(web("https://route.example/path"));
        SharedPreferences prefs = context.getSharedPreferences("launch_tickets", Context.MODE_PRIVATE);
        JSONObject item = new JSONObject(prefs.getString(token, ""));
        item.put("issued", System.currentTimeMillis() - TicketPolicy.TTL_MILLIS - 1_000L);
        prefs.edit().putString(token, item.toString()).commit();
        assertNull(new TicketStore(context).consume(token));
        assertFalse(prefs.contains(token));
    }

    @Test
    public void futureTimestampAndCorruptRecordsCannotBeConsumed() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("launch_tickets", Context.MODE_PRIVATE);
        String future = new TicketStore(context).issue(web("https://route.example/path"));
        JSONObject item = new JSONObject(prefs.getString(future, ""));
        item.put("issued", System.currentTimeMillis() + 60_000L);
        prefs.edit().putString(future, item.toString()).commit();
        assertNull(new TicketStore(context).consume(future));
        String corrupt = new TicketStore(context).issue(web("https://route.example/path"));
        prefs.edit().putString(corrupt, "broken-json").commit();
        assertNull(new TicketStore(context).consume(corrupt));
    }

    @Test
    public void capacityIsBoundedAndConsumptionReleasesSlot() {
        TicketStore store = new TicketStore(context);
        String first = null;
        for (int i = 0; i < TicketPolicy.CAPACITY; i++) {
            String token = store.issue(web("https://route.example/path/" + i));
            assertNotNull(token);
            if (first == null) first = token;
        }
        assertNull(store.issue(web("https://route.example/overflow")));
        assertNotNull(store.consume(first));
        assertNotNull(store.issue(web("https://route.example/replacement")));
    }

    @Test
    public void malformedAndUnknownTokensDoNotExposeStoredRequests() {
        TicketStore store = new TicketStore(context);
        assertNull(store.consume(null));
        assertNull(store.consume("../../../config"));
        assertNull(store.consume("a".repeat(43)));
        assertNull(store.consume("a".repeat(42)));
    }
}
