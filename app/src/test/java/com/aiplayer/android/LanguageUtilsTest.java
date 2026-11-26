package com.aiplayer.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LanguageUtilsTest {
    @Test
    public void mlKitUsesEnglishPivotForDistinctNonEnglishLanguages() {
        assertTrue(TranslationClient.usesEnglishPivot("zh", "vi"));
        assertTrue(TranslationClient.usesEnglishPivot("ja", "vi"));
        assertTrue(TranslationClient.usesEnglishPivot("ko", "vi"));
        assertTrue(TranslationClient.usesEnglishPivot("vi", "ja"));
        assertFalse(TranslationClient.usesEnglishPivot("en", "vi"));
        assertFalse(TranslationClient.usesEnglishPivot("vi", "en"));
        assertFalse(TranslationClient.usesEnglishPivot("vi", "vi"));
        assertFalse(TranslationClient.usesEnglishPivot("auto", "vi"));
    }

    @Test
    public void normalizeLanguageMapsAliasesAndAuto() {
        assertEquals("auto", LanguageUtils.normalizeLanguage("auto", "en"));
        assertEquals("ja", LanguageUtils.normalizeLanguage("jp", "en"));
        assertEquals("zh", LanguageUtils.normalizeLanguage("cn", "en"));
        assertEquals("vi", LanguageUtils.normalizeLanguage("vn", "en"));
    }

    @Test
    public void normalizeLanguageUsesFallbackForBlankOrUnsupported() {
        assertEquals("vi", LanguageUtils.normalizeLanguage("", "vi"));
        assertEquals("vi", LanguageUtils.normalizeLanguage("not-a-language", "vi"));
    }

    @Test
    public void normalizeLanguageKeepsSupportedTags() {
        assertEquals("en", LanguageUtils.normalizeLanguage("EN", "vi"));
        assertEquals("ja", LanguageUtils.normalizeLanguage("ja", "vi"));
    }
}
