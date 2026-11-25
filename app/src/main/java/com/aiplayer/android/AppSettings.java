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
    static final int SUBTITLE_OPACITY_STEP = 5;
    static final int DEFAULT_SUBTITLE_OPACITY = 75;

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

    enum SubtitleBackground {
        BLACK("Đen", 0xff000000),
        CHARCOAL("Xám đậm", 0xff333333),
        NAVY("Xanh đen", 0xff172033),
        WHITE("Trắng", 0xffffffff);

        final String label;
        final int argb;

        SubtitleBackground(String label, int argb) {
            this.label = label;
            this.argb = argb;
        }

        static SubtitleBackground fromArgb(int argb) {
            for (SubtitleBackground color : values()) {
                if (color.argb == argb) return color;
            }
            return BLACK;
        }

        int withOpacity(int percent) {
            int alpha = (255 * percent + 99) / 100;
            return (alpha << 24) | (argb & 0x00ffffff);
        }
    }

    enum QwenProcessor {
        AUTO("Tự động"), GPU("Ưu tiên GPU"), CPU("CPU");

        final String label;

        QwenProcessor(String label) {
            this.label = label;
        }

        static QwenProcessor fromPreference(String name) {
            for (QwenProcessor processor : values()) {
                if (processor.name().equals(name)) return processor;
            }
            return AUTO;
        }
    }

    QwenProcessor qwenProcessor = QwenProcessor.AUTO;
    String sourceLanguage = "ja";
    String targetLanguage = "vi";
    int sourceVolume = DEFAULT_SOURCE_VOLUME;
    int dubbingVolume = DEFAULT_DUBBING_VOLUME;
    int subtitleSize = 16;
    SubtitleColor subtitleColor = SubtitleColor.WHITE;
    SubtitleBackground subtitleBackground = SubtitleBackground.BLACK;
    int subtitleOpacity = DEFAULT_SUBTITLE_OPACITY;
    int colorMode;
    boolean raisedControls = true;
    int fontSize = 1;

    static String voice(Context context, String language) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("ttsVoice." + language, "");
    }

    static void saveVoice(Context context, String language, String voice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("ttsVoice." + language, voice).apply();
    }

    int textSize(int base) {
        return base + (fontSize - 2) * 2;
    }

    int controlSpacing(int base) {
        return fontSize == 0 ? Math.max(0, base - 2) : base;
    }

    static AppSettings load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AppSettings settings = new AppSettings();
        settings.qwenProcessor = QwenProcessor.fromPreference(prefs.getString("qwenProcessor", ""));
        settings.sourceLanguage = prefs.getString(SOURCE_LANGUAGE, settings.sourceLanguage);
        settings.targetLanguage = prefs.getString(TARGET_LANGUAGE, settings.targetLanguage);
        settings.sourceVolume = prefs.getInt("sourceVolume", settings.sourceVolume);
        settings.dubbingVolume = prefs.getInt("dubbingVolume", settings.dubbingVolume);
        settings.subtitleSize = prefs.getInt("subtitleSize", settings.subtitleSize);
        settings.subtitleColor = SubtitleColor.fromArgb(prefs.getInt("subtitleColor", SubtitleColor.WHITE.argb));
        settings.subtitleBackground = SubtitleBackground.fromArgb(
                prefs.getInt("subtitleBackground", SubtitleBackground.BLACK.argb));
        int opacity = prefs.getInt("subtitleOpacity", DEFAULT_SUBTITLE_OPACITY);
        settings.subtitleOpacity = Math.round(Math.max(0, Math.min(100, opacity))
                / (float) SUBTITLE_OPACITY_STEP) * SUBTITLE_OPACITY_STEP;
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
                .putString("qwenProcessor", qwenProcessor.name())
                .putString(SOURCE_LANGUAGE, sourceLanguage)
                .putString(TARGET_LANGUAGE, targetLanguage)
                .putInt("sourceVolume", sourceVolume)
                .putInt("dubbingVolume", dubbingVolume)
                .putInt("subtitleSize", subtitleSize)
                .putInt("subtitleColor", subtitleColor.argb)
                .putInt("subtitleBackground", subtitleBackground.argb)
                .putInt("subtitleOpacity", subtitleOpacity)
                .putInt("colorMode", colorMode)
                .putBoolean("raisedControls", raisedControls)
                .putInt("fontSize", fontSize)
                .putBoolean(JAPANESE_DEFAULT_MIGRATED, true)
                .apply();
    }
}
