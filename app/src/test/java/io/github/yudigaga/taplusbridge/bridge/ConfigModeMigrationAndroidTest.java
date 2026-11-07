package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, manifest=Config.NONE)
public final class ConfigModeMigrationAndroidTest extends BridgeRobolectricFixture {
    @Test public void defaultAndRemovedChannelsUseRootWithoutResettingRulesOrToggles() {
        assertEquals("ROOT", new ConfigStore(context).mode().name());
        for (String old : new String[]{"NONE","ICEBOX","future-invalid"}) {
            context.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                    .putString("engine",old).putBoolean("recognition",false).commit();
            ConfigStore migrated=new ConfigStore(context);
            assertEquals("ROOT",migrated.mode().name());
            assertFalse(migrated.recognitionEnabled());
            assertEquals("ROOT",context.getSharedPreferences("config",Context.MODE_PRIVATE).getString("engine",null));
        }
    }
    @Test public void shizukuSelectionSurvivesReopen() {
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString("engine","SHIZUKU").commit();
        assertEquals("SHIZUKU",new ConfigStore(context).mode().name());
    }
    @Test public void malformedModeDoesNotCrashTheSettingsPage() {
        context.getSharedPreferences("config",Context.MODE_PRIVATE).edit().putInt("engine",42).commit();
        assertEquals("ROOT",new ConfigStore(context).mode().name());
    }
    @Test public void removingTheMisunderstoodGlobalTargetPreservesAllRuleData() {
        config.saveRules(java.util.List.of(new io.github.yudigaga.taplusbridge.routing.RouteRule(
                "example.org","com.example.reader","*",true,false)));
        var rules=context.getSharedPreferences(io.github.yudigaga.taplusbridge.rules.RuleRepository.PREFS,Context.MODE_PRIVATE);
        var snapshot=new java.util.HashMap<>(rules.getAll());
        var feature=context.getSharedPreferences("config",Context.MODE_PRIVATE);
        feature.edit().putString("generic_target","com.example.oldglobal").putString("engine","SHIZUKU").commit();
        ConfigStore migrated=new ConfigStore(context);
        assertFalse(feature.contains("generic_target"));
        assertEquals("SHIZUKU",migrated.mode().name());
        assertEquals("example.org",migrated.rules().get(0).host());
        assertEquals(snapshot,rules.getAll());
    }
}
