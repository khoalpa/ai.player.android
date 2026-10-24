package com.aiplayer.android;

import com.google.mlkit.nl.translate.TranslateLanguage;

import java.util.Locale;

final class LanguageUtils {
    private LanguageUtils() {
    }

    static String normalizeLanguage(String value, String fallback) {
        String language = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (language.isEmpty()) {
            return fallback;
        }
        if ("jp".equals(language)) {
            return "ja";
        }
        if ("cn".equals(language)) {
            return "zh";
        }
        if ("vn".equals(language)) {
            return "vi";
        }
        if ("auto".equals(language)) {
            return "auto";
        }
        String supported = TranslateLanguage.fromLanguageTag(language);
        return supported == null ? fallback : supported;
    }
}
