package io.github.yudigaga.taplusbridge.config;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import io.github.yudigaga.taplusbridge.routing.RouteRule;
import io.github.yudigaga.taplusbridge.thaw.ThawEngine;
import java.util.ArrayList;
import java.util.List;

/** Feature preferences retain their keys; all routing data now lives in the Ice rule repository. */
public final class ConfigStore {
    private final SharedPreferences prefs;
    private final Context context;
    public ConfigStore(Context context) {
        this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
        prefs = this.context.getSharedPreferences("config", Context.MODE_PRIVATE);
        // 0.6's global target was a misinterpretation; never let it override domain rules.
        if (prefs.contains("generic_target")) prefs.edit().remove("generic_target").commit();
        // User explicitly requested replacing the old Taplus rule table. Keep service/toggle choices.
        if (prefs.contains("rules") || prefs.contains("seed_version")) {
            prefs.edit().remove("rules").remove("seed_version").putInt("rule_system", 1).commit();
        }
    }
    public boolean enabled() { return prefs.getBoolean("enabled", true); }
    public void setEnabled(boolean value) { prefs.edit().putBoolean("enabled", value).commit(); }
    public boolean nativeEnabled() { return prefs.getBoolean("native", true); }
    public void setNativeEnabled(boolean value) { prefs.edit().putBoolean("native", value).commit(); }
    public boolean recognitionEnabled() { return prefs.getBoolean("recognition", true); }
    public void setRecognitionEnabled(boolean value) { prefs.edit().putBoolean("recognition", value).commit(); }
    public boolean captureEnabled() { return prefs.getBoolean("capture", true); }
    public void setCaptureEnabled(boolean value) { prefs.edit().putBoolean("capture", value).commit(); }
    public boolean independentEnabled(){return prefs.getBoolean("independent_runtime",true);}
    public void setIndependentEnabled(boolean value){prefs.edit().putBoolean("independent_runtime",value).commit();}
    public ThawEngine.Mode mode() {
        String value;
        try { value = prefs.getString("engine", "ROOT"); }
        catch (RuntimeException malformed) { value = "ROOT"; }
        if (!"ROOT".equals(value) && !"SHIZUKU".equals(value)) {
            value = "ROOT";
            prefs.edit().putString("engine", value).commit();
        }
        try { return ThawEngine.Mode.valueOf(value); }
        catch (IllegalArgumentException unavailable) { return ThawEngine.Mode.ROOT; }
    }
    public void setMode(ThawEngine.Mode mode) { prefs.edit().putString("engine", mode.name()).commit(); }
    /** Legacy Java projection for existing callers; runtime matching uses complete Ice entries. */
    public List<RouteRule> rules() {
        List<RouteRule> result = new ArrayList<>();
        for (LinkRuleEntry entry : new RuleRepository(context).manualRules()) {
            result.add(new RouteRule(entry.host, entry.targetPackage, entry.sourcePackage, true, entry.hasFixedUrlConversion()));
        }
        return result;
    }
    public void saveRules(List<RouteRule> rules) {
        List<LinkRuleEntry> result = new ArrayList<>();
        for (RouteRule rule : rules) result.add(new LinkRuleEntry(rule.sourcePackage(), rule.host(), rule.targetPackage(),
                LinkRuleEntry.SOURCE_MANUAL, rule.convert() ? LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK : LinkRuleEntry.CONVERSION_NONE, ""));
        new RuleRepository(context).replaceManualRules(result);
    }
}
