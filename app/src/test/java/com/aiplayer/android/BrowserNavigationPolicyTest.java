package com.aiplayer.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.List;

import org.junit.Test;

public class BrowserNavigationPolicyTest {
    @Test
    public void incomingIntentUrlsOnlyAcceptHttpAndHttps() {
        assertTrue(BrowserNavigationPolicy.isIncomingWebUrl("https://example.com/watch"));
        assertTrue(BrowserNavigationPolicy.isIncomingWebUrl("http://example.com/watch"));
        assertFalse(BrowserNavigationPolicy.isIncomingWebUrl("file:///sdcard/video.mp4"));
        assertFalse(BrowserNavigationPolicy.isIncomingWebUrl("javascript:alert(1)"));
    }

    @Test
    public void webViewLoadsHttpsButKeepsPlainHttpOutOfBrowserMode() {
        assertTrue(BrowserNavigationPolicy.canLoadInWebView("https://example.com/watch"));
        assertFalse(BrowserNavigationPolicy.canLoadInWebView("http://example.com/watch"));
        assertTrue(BrowserNavigationPolicy.canLoadInWebView("http://example.com/movie/name/episode"));
    }

    @Test
    public void httpPagesOpenExternallyUnlessTheyAreMediaOrExtractablePages() {
        assertTrue(BrowserNavigationPolicy.shouldOpenHttpPageExternally("http://example.com/watch"));
        assertFalse(BrowserNavigationPolicy.shouldOpenHttpPageExternally("http://cdn.example.com/video.mp4"));
        assertFalse(BrowserNavigationPolicy.shouldOpenHttpPageExternally("http://example.com/video/name"));
        assertFalse(BrowserNavigationPolicy.shouldOpenHttpPageExternally("https://example.com/watch"));
    }

    @Test
    public void alternateUrlsNeverDowngradeHttpsToHttp() {
        List<String> candidates = BrowserNavigationPolicy.alternateHttpsUrls(
                URI.create("https://www.example.com/path/video?q=1#part")
        );

        assertEquals(1, candidates.size());
        assertEquals("https://example.com/path/video?q=1#part", candidates.get(0));
        assertTrue(BrowserNavigationPolicy.alternateHttpsUrls(URI.create("http://example.com/path")).isEmpty());
        assertTrue(BrowserNavigationPolicy.alternateHttpsUrls("not a valid url").isEmpty());
    }
}
