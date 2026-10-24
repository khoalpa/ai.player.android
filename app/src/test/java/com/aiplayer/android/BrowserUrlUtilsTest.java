package com.aiplayer.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrowserUrlUtilsTest {
    @Test
    public void normalizeBrowserUrlHandlesEmptyHostSearchAndUnsafeSchemes() {
        assertEquals(
                "https://www.google.com",
                BrowserUrlUtils.normalizeBrowserUrl("", "https://www.google.com")
        );
        assertEquals(
                "https://example.com/video",
                BrowserUrlUtils.normalizeBrowserUrl("example.com/video", "https://www.google.com")
        );
        assertEquals(
                "https://www.google.com/search?q=hello%20world",
                BrowserUrlUtils.normalizeBrowserUrl("hello world", "https://www.google.com")
        );
        assertEquals(
                "https://www.google.com/search?q=javascript%3Aalert%281%29",
                BrowserUrlUtils.normalizeBrowserUrl("javascript:alert(1)", "https://www.google.com")
        );
    }

    @Test
    public void extractYoutubeIdFromCommonUrlShapes() {
        assertEquals("abcDEF_1234", BrowserUrlUtils.extractYoutubeId("https://youtu.be/abcDEF_1234?t=1"));
        assertEquals("abcDEF_1234", BrowserUrlUtils.extractYoutubeId("https://www.youtube.com/watch?v=abcDEF_1234"));
        assertEquals("abcDEF_1234", BrowserUrlUtils.extractYoutubeId("https://m.youtube.com/shorts/abcDEF_1234"));
        assertEquals("abcDEF_1234", BrowserUrlUtils.extractYoutubeId("https://youtube.com/embed/abcDEF_1234"));
        assertNull(BrowserUrlUtils.extractYoutubeId("https://example.com/watch?v=abcDEF_1234"));
    }

    @Test
    public void detectsDirectMediaAndExtractablePages() {
        assertTrue(BrowserUrlUtils.isDirectMediaUrl("https://cdn.example.com/video/movie.M3U8?token=1"));
        assertTrue(BrowserUrlUtils.isDirectMediaUrl("https://cdn.example.com/audio.wav"));
        assertFalse(BrowserUrlUtils.isDirectMediaUrl("https://example.com/watch/123"));
        assertTrue(BrowserUrlUtils.isNativePlaybackUrl("rtsp://media.example.com/live"));
        assertTrue(BrowserUrlUtils.isNativePlaybackUrl("rtmp://media.example.com/live"));

        assertTrue(BrowserUrlUtils.shouldExtractMediaFromPage("https://example.com/movie/name/episode"));
        assertTrue(BrowserUrlUtils.shouldExtractMediaFromPage("https://example.com/vi/anime/name"));
        assertTrue(BrowserUrlUtils.shouldExtractMediaFromPage("https://example.com/video/name"));
        assertFalse(BrowserUrlUtils.shouldExtractMediaFromPage("https://example.com/about"));
        assertFalse(BrowserUrlUtils.shouldExtractMediaFromPage("file:///movie/name/episode"));
    }
}
