package io.github.yudigaga.taplusbridge.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import io.github.yudigaga.taplusbridge.thaw.ThawEngine;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The user authorized replacing old routing data, while service/toggle choices stay owned. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class ConfigUpgradeAndroidTest extends BridgeRobolectricFixture {
    @Before public void clearNewRuleStore() {
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }
    private SharedPreferences legacy() { return context.getSharedPreferences("config", Context.MODE_PRIVATE); }
    @Test public void replacingLegacyRulesPreservesEveryFeatureToggleAndThawMode() {
        legacy().edit().putString("rules", "[{\"host\":\"old.example\",\"target\":\"com.old.reader\"}]")
                .putInt("seed_version", 2).putBoolean("enabled", false).putBoolean("native", false)
                .putBoolean("recognition", false).putBoolean("capture", false).putString("engine", "ICEBOX").commit();
        ConfigStore upgraded = new ConfigStore(context);
        assertFalse(legacy().contains("rules")); assertFalse(legacy().contains("seed_version"));
        assertEquals(1, legacy().getInt("rule_system", 0));
        assertFalse(upgraded.enabled()); assertFalse(upgraded.nativeEnabled());
        assertFalse(upgraded.recognitionEnabled()); assertFalse(upgraded.captureEnabled());
        assertEquals(ThawEngine.Mode.ROOT, upgraded.mode());
        RuleRepository repository = new RuleRepository(context);
        assertTrue(repository.manualRules().isEmpty()); assertEquals(32, repository.onlineRules().size());
        assertTrue(repository.allRules().stream().allMatch(rule -> LinkRuleEntry.SOURCE_ONLINE.equals(rule.source)));
        assertTrue(repository.allRules().stream().noneMatch(rule -> "old.example".equals(rule.host)));
    }
    @Test public void oldEmptyRuleTableDoesNotDisableTheNewOwnedBaseline() {
        legacy().edit().putString("rules", "[]").putInt("seed_version", 2).commit();
        ConfigStore upgraded = new ConfigStore(context);
        assertTrue(upgraded.rules().isEmpty());
        assertEquals(32, new RuleRepository(context).onlineRules().size());
        assertFalse(legacy().contains("rules"));
    }
    @Test public void malformedOldTableIsRemovedWithoutParsingOrChangingRootSelection() {
        legacy().edit().putString("rules", "malformed old configuration").putInt("seed_version", 1)
                .putString("engine", "ROOT").commit();
        ConfigStore upgraded = new ConfigStore(context);
        assertEquals(ThawEngine.Mode.ROOT, upgraded.mode());
        assertFalse(legacy().contains("rules")); assertTrue(upgraded.rules().isEmpty());
        assertEquals(32, new RuleRepository(context).allRules().size());
    }
    @Test public void repeatedConfigOpenNeverResetsNewManualRulesOrTargetSwitches() {
        legacy().edit().putString("rules", "old").putInt("seed_version", 2).commit();
        new ConfigStore(context);
        RuleRepository repository = new RuleRepository(context);
        LinkRuleEntry manual = new LinkRuleEntry("com.tencent.mm", "manual.example", "com.new.reader",
                LinkRuleEntry.SOURCE_MANUAL, LinkRuleEntry.CONVERSION_NONE, "保留新规则");
        repository.replaceManualRules(List.of(manual)); repository.setTargetEnabled(BILI, false);
        new ConfigStore(context); new ConfigStore(context);
        assertEquals(List.of(manual), new RuleRepository(context).manualRules());
        assertTrue(new RuleRepository(context).disabledTargets().contains(BILI));
        assertEquals(32, new RuleRepository(context).onlineRules().size());
    }
}
