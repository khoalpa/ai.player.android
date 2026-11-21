package com.aiplayer.android;

import org.junit.Test;
import java.net.URL;
import static org.junit.Assert.*;

public class VideoUrlPolicyTest {
    @Test public void directSourceKeyPreservesQueryWithoutStoringItInPlainText() throws Exception {
        URL original = new URL("https://example.com/video.mp4?token=private-value");
        URL other = new URL("https://example.com/video.mp4?token=other-value");
        String key = VideoUrlPolicy.directSourceKey(original);
        assertEquals(key, VideoUrlPolicy.directSourceKey(new URL(original.toExternalForm())));
        assertNotEquals(key, VideoUrlPolicy.directSourceKey(other));
        assertFalse(key.contains("private-value"));
    }

    @Test public void rejectsStreamingManifestsByPathAndContentType() throws Exception {
        URL playlist = new URL("https://example.com/video.M3U8?token=abc");
        URL download = new URL("https://example.com/download");
        IllegalArgumentException byPath = assertThrows(IllegalArgumentException.class,
                () -> VideoUrlPolicy.requireDownloadablePath(playlist));
        IllegalArgumentException byType = assertThrows(IllegalArgumentException.class,
                () -> VideoUrlPolicy.downloadExtension(download, download, "application/dash+xml"));
        assertEquals(byPath.getMessage(), byType.getMessage());
        VideoUrlPolicy.requireDownloadablePath(new URL("https://example.com/video.mp4?name=video.m3u8"));
    }

    @Test public void acceptsHttpsVideoWithQuery() throws Exception {
        URL url = VideoUrlPolicy.requireHttps("https://example.com/a.MP4?token=abc");
        assertEquals(".mp4", VideoUrlPolicy.downloadExtension(url, url, null));
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
        URL url = new URL("https://example.com/watch");
        assertThrows(IllegalArgumentException.class,
                () -> VideoUrlPolicy.downloadExtension(url, url, "text/html; charset=utf-8"));
    }
    @Test public void selectsExtensionFromVideoResponseOrRedirect() throws Exception {
        URL original = new URL("https://example.com/download");
        URL finalUrl = new URL("https://cdn.example.com/movie.webm");
        assertEquals(".mp4", VideoUrlPolicy.downloadExtension(original, original, "Video/MP4; charset=binary"));
        assertEquals(".webm", VideoUrlPolicy.downloadExtension(finalUrl, original, "application/octet-stream"));
        assertEquals(".video", VideoUrlPolicy.downloadExtension(original, original, "application/octet-stream"));
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
