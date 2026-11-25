package com.aiplayer.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LanguageUtilsTest {
    @Test
    public void translationAvoidsMlKitPivotForDistinctNonEnglishLanguages() {
        assertTrue(TranslationClient.requiresDirect("zh", "vi"));
        assertTrue(TranslationClient.requiresDirect("ja", "vi"));
        assertTrue(TranslationClient.requiresDirect("ko", "vi"));
        assertTrue(TranslationClient.requiresDirect("vi", "ja"));
        assertFalse(TranslationClient.requiresDirect("en", "vi"));
        assertFalse(TranslationClient.requiresDirect("vi", "en"));
        assertFalse(TranslationClient.requiresDirect("vi", "vi"));
        assertFalse(TranslationClient.requiresDirect("auto", "vi"));
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
