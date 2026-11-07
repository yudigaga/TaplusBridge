package io.github.yudigaga.taplusbridge.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class SourceScopePolicyAndroidTest {
    @Test public void absentManagementPreservesHostSelection() {
        SourceScopePolicy value = SourceScopePolicy.parse(SourceScopePolicy.unmanaged().encode());
        assertFalse(value.managed); assertEquals(SourceScopePolicy.Mode.BLACKLIST, value.mode);
        assertTrue(value.packages().isEmpty()); assertTrue(value.allows("com.example.source"));
        assertFalse(value.allows(null)); assertFalse(value.allows("invalid;package"));
    }

    @Test public void emptyBlacklistAllowsValidSourcesAndEmptyWhitelistDeniesThem() {
        assertTrue(SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, Set.of(), Set.of()).allows("com.example.source"));
        assertFalse(SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST, Set.of(), Set.of()).allows("com.example.source"));
    }

    @Test public void onlySelectedModeDecidesAndBothSelectionsSurviveReload() {
        Set<String> black = Set.of("com.example.black"), white = Set.of("com.example.white");
        SourceScopePolicy blocked = SourceScopePolicy.parse(SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, black, white).encode());
        SourceScopePolicy allowed = SourceScopePolicy.parse(SourceScopePolicy.of(SourceScopePolicy.Mode.WHITELIST, black, white).encode());
        assertFalse(blocked.allows("com.example.black")); assertTrue(blocked.allows("com.example.other"));
        assertTrue(allowed.allows("com.example.white")); assertFalse(allowed.allows("com.example.other"));
        assertEquals(black, allowed.blacklist); assertEquals(white, blocked.whitelist);
        assertEquals(white, allowed.packages()); assertEquals(black, blocked.packages());
    }

    @Test public void policyCopiesItsInputsSortsAndCannotBeMutated() {
        List<String> input = new ArrayList<>(List.of("com.example.z", "com.example.a"));
        SourceScopePolicy value = SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, input, Set.of());
        input.clear(); assertEquals(List.of("com.example.a", "com.example.z"), new ArrayList<>(value.packages()));
        assertThrows(UnsupportedOperationException.class, () -> value.blacklist.clear());
        assertThrows(UnsupportedOperationException.class, () -> value.whitelist.add("com.example.x"));
    }

    @Test public void malformedModesTypesPackagesAndExtraKeysAreRejected() throws Exception {
        String valid = SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, Set.of("com.example.source"), Set.of()).encode();
        List<String> invalid = new ArrayList<>(List.of("", "not json", "null"));
        invalid.add(new JSONObject(valid).put("mode", "ALLOW_ALL").toString());
        invalid.add(new JSONObject(valid).put("managed", "true").toString());
        invalid.add(new JSONObject(valid).put("schema", "1").toString());
        invalid.add(new JSONObject(valid).put("schema", 99).toString());
        invalid.add(new JSONObject(valid).put("extra", true).toString());
        invalid.add(new JSONObject(valid).put("managed", false).toString());
        for (Object item : List.of("com.example;cmd", "com..example", "com.example\n", 12, JSONObject.NULL))
            invalid.add(new JSONObject(valid).put("blacklist", new JSONArray().put(item)).toString());
        invalid.add(new JSONObject(valid).put("blacklist", new JSONArray().put("com.example.same").put("com.example.same")).toString());
        for (String value : invalid) assertThrows(value, IllegalArgumentException.class, () -> SourceScopePolicy.parse(value));
        assertThrows(IllegalArgumentException.class, () -> SourceScopePolicy.parse(null));
    }

    @Test public void totalPackageBoundIncludesBothListsAndNeverTruncates() throws Exception {
        List<String> black = new ArrayList<>();
        for (int i=0; i<SourceScopePolicy.MAX_PACKAGES; i++) black.add("com.example.p"+i);
        SourceScopePolicy full = SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, black, Set.of());
        assertEquals(SourceScopePolicy.MAX_PACKAGES, SourceScopePolicy.parse(full.encode()).blacklist.size());
        assertThrows(IllegalArgumentException.class, () -> SourceScopePolicy.of(SourceScopePolicy.Mode.BLACKLIST, black, Set.of("com.example.extra")));
        JSONObject oversized = new JSONObject(full.encode());
        oversized.put("whitelist", new JSONArray().put("com.example.extra"));
        assertThrows(IllegalArgumentException.class, () -> SourceScopePolicy.parse(oversized.toString()));
        assertThrows(IllegalArgumentException.class, () -> SourceScopePolicy.parse(" ".repeat(SourceScopePolicy.MAX_BYTES+1)));
    }
}
