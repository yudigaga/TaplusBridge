package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import java.security.SecureRandom;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import io.github.yudigaga.taplusbridge.recognition.BatchLinks;

final class TicketStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    TicketStore(Context context) { prefs = context.getSharedPreferences("launch_tickets", Context.MODE_PRIVATE); }
    String issue(Bundle request) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            SharedPreferences.Editor edit = prefs.edit();
            int valid = 0;
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (decode(entry.getValue().toString(), now) == null) edit.remove(entry.getKey()); else valid++;
            }
            if (valid >= TicketPolicy.CAPACITY) return null;
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            String token = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
            try {
                JSONObject item = new JSONObject(); item.put("issued", now);
                if (request.containsKey(BatchLinks.LINKS_KEY)) {
                    if (request.containsKey("url") || request.containsKey("candidate")) return null;
                    item.put(BatchLinks.LINKS_KEY, new JSONArray(BatchLinks.normalize(request.get(BatchLinks.LINKS_KEY))));
                }
                Intent original = request.getParcelable("original");
                Intent candidate = request.getParcelable("candidate");
                if (original != null) item.put("original", original.toUri(0));
                if (candidate != null) item.put("candidate", candidate.toUri(0));
                item.put("url", request.getString("url")); item.put("source", request.getString("source"));
                if (!edit.putString(token, item.toString()).commit()) return null;
                return token;
            } catch (Exception e) { return null; }
        }
    }
    Bundle consume(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        synchronized (LOCK) {
            String raw = prefs.getString(token, null);
            if (!prefs.edit().remove(token).commit()) return null;
            return raw == null ? null : decode(raw, System.currentTimeMillis());
        }
    }
    private static Bundle decode(String raw, long now) {
        try {
            JSONObject item = new JSONObject(raw);
            if (!TicketPolicy.live(item.getLong("issued"), now)) return null;
            Bundle request = new Bundle();
            if (item.has("original")) request.putParcelable("original", Intent.parseUri(item.getString("original"), 0));
            if (item.has("candidate")) request.putParcelable("candidate", Intent.parseUri(item.getString("candidate"), 0));
            if (item.has("url")) request.putString("url", item.getString("url"));
            if (item.has("source")) request.putString("source", item.getString("source"));
            if (item.has(BatchLinks.LINKS_KEY)) {
                if (item.has("url") || item.has("candidate")) return null;
                JSONArray array = item.getJSONArray(BatchLinks.LINKS_KEY);
                if (array.length() == 0 || array.length() > BatchLinks.MAX_LINKS) return null;
                ArrayList<Object> supplied = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) supplied.add(array.get(i));
                request.putStringArrayList(BatchLinks.LINKS_KEY, new ArrayList<>(BatchLinks.normalize(supplied)));
            }
            return request;
        } catch (Exception e) { return null; }
    }
}
