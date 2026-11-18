package com.aiplayer.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoDownloadsTest {
    @Test public void acceptsDirectVideos() {
        assertTrue(VideoDownloads.isDirectVideoUrl("https://example.com/video.mp4?token=abc"));
        assertTrue(VideoDownloads.isDirectVideoUrl("https://cdn.example.org/a.WEBM"));
    }
    @Test public void rejectsWebPagesAndUnsafeSchemes() {
        assertFalse(VideoDownloads.isDirectVideoUrl("https://t.me/xdcmdj/3076"));
        assertFalse(VideoDownloads.isDirectVideoUrl("https://example.com/watch?id=1"));
        assertFalse(VideoDownloads.isDirectVideoUrl("file:///sdcard/video.mp4"));
        assertFalse(VideoDownloads.isDirectVideoUrl("https://user:password@example.com/a.mp4"));
        assertFalse(VideoDownloads.isDirectVideoUrl(null));
    }
}
