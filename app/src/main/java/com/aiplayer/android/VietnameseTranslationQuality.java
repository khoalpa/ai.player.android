package com.aiplayer.android;

import java.text.Normalizer;
import java.util.Locale;

final class VietnameseTranslationQuality {
    private VietnameseTranslationQuality() {}

    static boolean accepts(String source, String target, String glossary) {
        if (target == null || target.trim().isEmpty()) return false;
        String normalized = Normalizer.normalize(target, Normalizer.Form.NFKC);
        String unchecked = normalized;
        if (glossary != null) {
            for (String line : glossary.split("\\r?\\n")) {
                int separator = line.indexOf('=');
                if (separator < 0) continue;
                String allowed = Normalizer.normalize(line.substring(separator + 1).trim(),
                        Normalizer.Form.NFKC);
                if (!allowed.isEmpty()) unchecked = unchecked.replace(allowed, "");
            }
        }
        if (unchecked.codePoints().anyMatch(VietnameseTranslationQuality::isEastAsianScript)) return false;
        // Short language-neutral values and proper names can legitimately remain unchanged.
        String canonical = canonical(unchecked);
        return !canonical.equals(canonical(source)) || canonical.split(" ").length < 4;
    }

    private static boolean isEastAsianScript(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA;
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").trim();
    }
}
