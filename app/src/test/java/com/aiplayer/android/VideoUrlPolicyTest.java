package com.aiplayer.android;

import org.junit.Test;
import java.net.URL;
import static org.junit.Assert.*;

public class VideoUrlPolicyTest {
    @Test public void acceptsHttpsVideoWithQuery() throws Exception {
        URL url = VideoUrlPolicy.requireHttps("https://example.com/a.MP4?token=abc");
        assertEquals(".mp4", VideoUrlPolicy.extension(url));
    }
    @Test public void rejectsHttp() throws Exception {
        try { VideoUrlPolicy.requireHttps("http://example.com/a.mp4"); fail("HTTP accepted"); }
        catch (IllegalArgumentException expected) { /* expected */ }
    }
    @Test public void rejectsUrlWithCredentials() throws Exception {
        try { VideoUrlPolicy.requireHttps("https://user:pass@example.com/a.mp4"); fail("credentials accepted"); }
        catch (IllegalArgumentException expected) { /* expected */ }
    }
    @Test public void rejectsWebpage() throws Exception {
        try { VideoUrlPolicy.extension(new URL("https://example.com/watch")); fail("page accepted"); }
        catch (IllegalArgumentException expected) { /* expected */ }
    }
    @Test public void resolvesHttpsRelativeRedirect() throws Exception {
        URL next = VideoUrlPolicy.redirect(new URL("https://example.com/video.mp4"), "/new/video.mp4");
        assertEquals("https://example.com/new/video.mp4", next.toExternalForm());
    }
    @Test public void rejectsDowngradeRedirect() throws Exception {
        try { VideoUrlPolicy.redirect(new URL("https://example.com/old.mp4"), "http://example.com/new.mp4"); fail("HTTP redirect accepted"); }
        catch (IllegalArgumentException expected) { /* expected */ }
    }
}
