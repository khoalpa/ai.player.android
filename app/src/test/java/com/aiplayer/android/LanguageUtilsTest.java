package com.aiplayer.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LanguageUtilsTest {
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
