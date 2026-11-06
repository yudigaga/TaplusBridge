package io.github.yudigaga.taplusbridge.translation;

import android.content.Context;
import android.content.SharedPreferences;

/** A separate private file deliberately bypasses framework configuration publication. */
public final class TranslationStore {
    private final SharedPreferences preferences;
    public TranslationStore(Context context) {
        preferences = context.getSharedPreferences("translation_private", Context.MODE_PRIVATE);
    }
    public TranslationSettings load() {
        if (!preferences.contains("settings")) return TranslationSettings.defaults();
        try { return TranslationSettings.parse(preferences.getString("settings", null)); }
        catch (RuntimeException malformed) { return TranslationSettings.defaults(); }
    }
    public void save(TranslationSettings settings) {
        if (settings == null) throw new IllegalArgumentException("翻译配置无效");
        settings.validate();
        if (!preferences.edit().putString("settings", settings.toJson()).commit())
            throw new IllegalStateException("翻译设置保存失败，请重试");
    }
}
