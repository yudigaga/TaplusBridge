package io.github.yudigaga.taplusbridge.config;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class DragSettingsAndroidTest {
    private Context app;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication(); app.getSharedPreferences("config", 0).edit().clear().commit();
    }

    @Test public void defaultsRemainOffAndEachDirectionHasAnIndependentAction() {
        DragSettings settings = new ConfigStore(app).getDragSettings();
        assertFalse(settings.enabled); assertFalse(settings.shareOnlySelected); assertTrue(settings.shareTargets.isEmpty());
        assertEquals("portal", settings.action("up")); assertEquals("share", settings.action("down"));
        assertEquals("lens", settings.action("left")); assertEquals("translate", settings.action("right"));
        assertEquals("none", settings.action("diagonal")); assertEquals("none", settings.action(null));
        assertEquals(DragSettings.DEFAULT_RECOGNITION_DELAY, settings.recognitionDelayMillis);
        assertEquals(DragSettings.DEFAULT_DRAG_DELAY, settings.dragDelayMillis);
    }

    @Test public void wholeConfigurationPersistsWithoutChangingOtherFeatures() {
        ConfigStore config = new ConfigStore(app);
        config.setLensEnabled(false);
        config.setDragSettings(new DragSettings(true, "rules", "browser", "copy", "none", true,
                List.of("com.example.second/.Share", "com.example.first/.Share")));
        DragSettings saved = new ConfigStore(app).getDragSettings();
        assertTrue(saved.enabled); assertTrue(saved.shareOnlySelected);
        assertEquals("rules", saved.up); assertEquals("browser", saved.down); assertEquals("copy", saved.left); assertEquals("none", saved.right);
        assertEquals(List.of("com.example.second/com.example.second.Share", "com.example.first/com.example.first.Share"), saved.shareTargets);
        assertFalse(config.lensEnabled()); assertTrue(config.enabled());
        String stored = app.getSharedPreferences("config", 0).getString(DragSettings.KEY, null);
        assertEquals(stored, DragSettings.parse(stored).toJson());
    }

    @Test public void shareTargetsAreCanonicalOrderedDeduplicatedAndImmutable() {
        ArrayList<String> targets = new ArrayList<>(List.of("com.example.app/.Share", "com.example.other/.Share", "com.example.app/com.example.app.Share"));
        DragSettings settings = new DragSettings(true, "portal", "share", "lens", "translate", true, targets);
        targets.clear();
        assertEquals(List.of("com.example.app/com.example.app.Share", "com.example.other/com.example.other.Share"), settings.shareTargets);
        assertThrows(UnsupportedOperationException.class, () -> settings.shareTargets.add("com.example.app/.Other"));
    }

    @Test public void selectedEmptyMenuIsPreservedInsteadOfBecomingAllTargets() {
        ConfigStore config = new ConfigStore(app);
        config.setDragSettings(new DragSettings(true, "share", "share", "share", "share", true, List.of()));
        assertTrue(config.getDragSettings().shareOnlySelected); assertTrue(config.getDragSettings().shareTargets.isEmpty());
    }

    @Test public void malformedWireAndUnknownActionsFailClosedWithoutReplacingSavedData() throws Exception {
        String valid = DragSettings.defaults().toJson();
        for (String invalid : List.of("{bad}", new JSONObject(valid).put("enabled", "true").toString(),
                new JSONObject(valid).put("up", "execute").toString(), new JSONObject(valid).put("schema", 99).toString(),
                new JSONObject(valid).put("extra", true).toString())) {
            assertThrows(IllegalArgumentException.class, () -> DragSettings.parse(invalid));
            app.getSharedPreferences("config", 0).edit().putString(DragSettings.KEY, invalid).commit();
            assertFalse(new ConfigStore(app).getDragSettings().enabled);
            assertEquals(invalid, app.getSharedPreferences("config", 0).getString(DragSettings.KEY, null));
        }
        app.getSharedPreferences("config", 0).edit().putBoolean(DragSettings.KEY, true).commit();
        assertFalse(new ConfigStore(app).getDragSettings().enabled);
    }

    @Test public void invalidComponentsAndModuleRecipientsAreRejected() {
        for (String component : List.of("https://example.test", "bad;package/.Share", "com.example.app/Bad Class",
                "io.github.yudigaga.taplusbridge/.ui.MainActivity", "com.miui.contentextension/.Main"))
            assertThrows(IllegalArgumentException.class, () -> new DragSettings(true, "portal", "share", "lens", "translate", true, List.of(component)));
    }

    @Test public void legacySettingsKeepActionsAndShareOptionsButUseDefaultStageDelays() throws Exception {
        JSONObject schemaOne = new JSONObject();
        schemaOne.put("schema", 1).put("enabled", true).put("up", "rules").put("down", "share")
                .put("left", "lens").put("right", "translate").put("shareOnlySelected", true)
                .put("shareTargets", new org.json.JSONArray(List.of("com.example.app/.Share")));
        DragSettings migratedOne = DragSettings.parse(schemaOne.toString());
        assertEquals("rules", migratedOne.up); assertEquals("share", migratedOne.down);
        assertTrue(migratedOne.shareOnlySelected);
        assertEquals(List.of("com.example.app/com.example.app.Share"), migratedOne.shareTargets);
        assertEquals(DragSettings.DEFAULT_RECOGNITION_DELAY, migratedOne.recognitionDelayMillis);
        assertEquals(DragSettings.DEFAULT_DRAG_DELAY, migratedOne.dragDelayMillis);

        JSONObject schemaTwo = new JSONObject(schemaOne.toString());
        schemaTwo.put("schema", 2).put("captureDelay", 800);
        DragSettings migratedTwo = DragSettings.parse(schemaTwo.toString());
        assertEquals("rules", migratedTwo.up); assertEquals("share", migratedTwo.down);
        assertTrue(migratedTwo.shareOnlySelected);
        assertEquals(List.of("com.example.app/com.example.app.Share"), migratedTwo.shareTargets);
        assertEquals(DragSettings.DEFAULT_RECOGNITION_DELAY, migratedTwo.recognitionDelayMillis);
        assertEquals(DragSettings.DEFAULT_DRAG_DELAY, migratedTwo.dragDelayMillis);
    }

    @Test public void customStageDelaysRoundTripAndRejectOutOfRangeValues() {
        DragSettings custom = new DragSettings(true, "rules", "share", "lens", "translate", false,
                List.of(), 1_500, 3_000);
        DragSettings restored = DragSettings.parse(custom.toJson());
        assertEquals(1_500, restored.recognitionDelayMillis);
        assertEquals(3_000, restored.dragDelayMillis);
        assertThrows(IllegalArgumentException.class, () -> new DragSettings(true, "rules", "share", "lens",
                "translate", false, List.of(), DragSettings.MIN_STAGE_DELAY - 1, 2_000));
        assertThrows(IllegalArgumentException.class, () -> new DragSettings(true, "rules", "share", "lens",
                "translate", false, List.of(), 2_000, DragSettings.MAX_STAGE_DELAY + 1));
    }
}
