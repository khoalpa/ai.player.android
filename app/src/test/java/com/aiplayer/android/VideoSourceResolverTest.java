package com.aiplayer.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoSourceResolverTest {
    @Test public void classifiesDirectVideo() throws Exception {
        assertEquals(VideoSourceResolver.Kind.DIRECT_FILE,
                VideoSourceResolver.classify("https://example.com/movie.mp4?token=x").kind);
    }
    @Test public void classifiesPublicTelegramPost() throws Exception {
        VideoSourceResolver.Source source = VideoSourceResolver.classify("https://t.me/xdcmdj/3076");
        assertEquals(VideoSourceResolver.Kind.TELEGRAM_PUBLIC_POST, source.kind);
        assertEquals("xdcmdj", source.channel);
        assertEquals("3076", source.postId);
        assertEquals("https://t.me/xdcmdj/3076?embed=1",
                VideoSourceResolver.publicTelegramEmbed(source).toExternalForm());
    }
    @Test public void doesNotClassifyPrivateTelegramLinks() throws Exception {
        assertEquals(VideoSourceResolver.Kind.UNSUPPORTED_PAGE,
                VideoSourceResolver.classify("https://t.me/c/123456/3076").kind);
    }
    @Test public void rejectsFakeTelegramDomain() throws Exception {
        assertEquals(VideoSourceResolver.Kind.UNSUPPORTED_PAGE,
                VideoSourceResolver.classify("https://t.me.evil.example/xdcmdj/3076").kind);
    }
    @Test public void extractsVideoTag() throws Exception {
        assertEquals("https://cdn.example.com/video.mp4?token=1&x=2",
                VideoSourceResolver.extractPublicMediaUrl("<video controls src=\"https://cdn.example.com/video.mp4?token=1&amp;x=2\"></video>"));
    }
    @Test public void rejectsNonVideoPublicEmbed() throws Exception {
        try {
            VideoSourceResolver.extractPublicMediaUrl("<a href=\"https://example.com/video.mp4\">Download</a>");
            fail("Must not scrape arbitrary links");
        } catch (IllegalArgumentException expected) { /* expected */ }
    }
}
