package com.aiplayer.android;

import android.content.Context;
import android.content.SharedPreferences;

final class AppSettings {
    private static final String PREFS = "ai_player_settings";
    private static final String SOURCE_LANGUAGE = "sourceLanguage";
    private static final String TARGET_LANGUAGE = "targetLanguage";
    private static final String JAPANESE_DEFAULT_MIGRATED = "japaneseDefaultMigrated";
    static final int DEFAULT_SOURCE_VOLUME = 70;
    static final int DEFAULT_DUBBING_VOLUME = 100;

    enum SubtitleColor {
        WHITE("Trắng", 0xffffffff),
        YELLOW("Vàng nhạt", 0xfffff176),
        BLUE("Xanh dương nhạt", 0xff81d4fa),
        GREEN("Xanh lá nhạt", 0xffa5d6a7),
        PINK("Hồng nhạt", 0xfff8bbd0);

        final String label;
        final int argb;

        SubtitleColor(String label, int argb) {
            this.label = label;
            this.argb = argb;
        }

        static SubtitleColor fromArgb(int argb) {
            for (SubtitleColor color : values()) {
                if (color.argb == argb) return color;
            }
            return WHITE;
        }
    }

    String sourceLanguage = "ja";
    String targetLanguage = "vi";
    int sourceVolume = DEFAULT_SOURCE_VOLUME;
    int dubbingVolume = DEFAULT_DUBBING_VOLUME;
    int subtitleSize = 16;
    SubtitleColor subtitleColor = SubtitleColor.WHITE;
    int colorMode;
    boolean raisedControls = true;
    int fontSize = 1;

    int textSize(int base) {
        return base + (fontSize - 1) * 2;
    }

    static AppSettings load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AppSettings settings = new AppSettings();
        settings.sourceLanguage = prefs.getString(SOURCE_LANGUAGE, settings.sourceLanguage);
        settings.targetLanguage = prefs.getString(TARGET_LANGUAGE, settings.targetLanguage);
        settings.sourceVolume = prefs.getInt("sourceVolume", settings.sourceVolume);
        settings.dubbingVolume = prefs.getInt("dubbingVolume", settings.dubbingVolume);
        settings.subtitleSize = prefs.getInt("subtitleSize", settings.subtitleSize);
        settings.subtitleColor = SubtitleColor.fromArgb(prefs.getInt("subtitleColor", SubtitleColor.WHITE.argb));
        settings.colorMode = Math.max(0, Math.min(2, prefs.getInt("colorMode", 0)));
        settings.raisedControls = prefs.getBoolean("raisedControls", true);
        settings.fontSize = Math.max(0, Math.min(2, prefs.getInt("fontSize", 1)));
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
                .putInt("sourceVolume", sourceVolume)
                .putInt("dubbingVolume", dubbingVolume)
                .putInt("subtitleSize", subtitleSize)
                .putInt("subtitleColor", subtitleColor.argb)
                .putInt("colorMode", colorMode)
                .putBoolean("raisedControls", raisedControls)
                .putInt("fontSize", fontSize)
                .putBoolean(JAPANESE_DEFAULT_MIGRATED, true)
                .apply();
    }
}
