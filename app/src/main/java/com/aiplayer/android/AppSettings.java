package com.aiplayer.android;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    private static final String PREFS = "ai_player_settings";
    private static final String SOURCE_LANGUAGE = "sourceLanguage";
    private static final String TARGET_LANGUAGE = "targetLanguage";
    private static final String JAPANESE_DEFAULT_MIGRATED = "japaneseDefaultMigrated";

    String sourceLanguage = "ja";
    String targetLanguage = "vi";
    boolean showTargetSubtitle = true;
    boolean dubbingEnabled = false;
    int sourceVolume = 70;
    int dubbingVolume = 100;

    static AppSettings load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AppSettings settings = new AppSettings();
        settings.sourceLanguage = prefs.getString(SOURCE_LANGUAGE, settings.sourceLanguage);
        settings.targetLanguage = prefs.getString(TARGET_LANGUAGE, settings.targetLanguage);
        settings.showTargetSubtitle = prefs.getBoolean("showTargetSubtitle", settings.showTargetSubtitle);
        settings.dubbingEnabled = prefs.getBoolean("dubbingEnabled", settings.dubbingEnabled);
        settings.sourceVolume = prefs.getInt("sourceVolume", settings.sourceVolume);
        settings.dubbingVolume = prefs.getInt("dubbingVolume", settings.dubbingVolume);
        if (!prefs.getBoolean(JAPANESE_DEFAULT_MIGRATED, false)
                && "auto".equals(settings.sourceLanguage)
                && "vi".equals(settings.targetLanguage)) {
            settings.sourceLanguage = "ja";
            prefs.edit()
                    .putString(SOURCE_LANGUAGE, settings.sourceLanguage)
                    .putBoolean(JAPANESE_DEFAULT_MIGRATED, true)
                    .apply();
        }
        return settings;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(SOURCE_LANGUAGE, sourceLanguage)
                .putString(TARGET_LANGUAGE, targetLanguage)
                .putBoolean("showTargetSubtitle", showTargetSubtitle)
                .putBoolean("dubbingEnabled", dubbingEnabled)
                .putInt("sourceVolume", sourceVolume)
                .putInt("dubbingVolume", dubbingVolume)
                .putBoolean(JAPANESE_DEFAULT_MIGRATED, true)
                .apply();
    }
}
