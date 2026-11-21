package com.aiplayer.android;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.Test;

public class WebVideoFinderTest {
    @Test public void readsVideoMetadataAndStaticPlayerSources() {
        var page = Jsoup.parse("<script type='application/ld+json'>"
                + "{\"@graph\":[{\"@type\":\"VideoObject\",\"contentUrl\":\"/movie.mp4\"}]}"
                + "</script><script type='application/json'>"
                + "{\"player\":{\"sources\":[{\"file\":\"/manifest.m3u8\"},{\"src\":\"/movie.mp4\"}]}}"
                + "</script>", "https://example.com/watch");
        assertEquals(List.of("https://example.com/movie.mp4", "https://example.com/manifest.m3u8"),
                WebVideoFinder.candidates(page));
    }

    @Test public void takesOnlyTwoDistinctHttpsFrames() {
        var page = Jsoup.parse("<iframe src='http://example.com/unsafe'></iframe>"
                + "<iframe src='/embed/1'></iframe><iframe src='/embed/1'></iframe>"
                + "<iframe src='https://player.example.com/2'></iframe><iframe src='/embed/3'></iframe>",
                "https://example.com/watch");
        assertEquals(List.of("https://example.com/embed/1", "https://player.example.com/2"),
                WebVideoFinder.frames(page));
    }

    @Test public void findsRelativeMediaAndDeduplicatesMetadata() {
        var page = Jsoup.parse("<meta property='og:video' content='/media/video.mp4?token=abc'>"
                + "<video src='/media/video.mp4?token=abc'><source src='stream.m3u8'>"
                + "<source src='blob:https://example.com/player'></video>"
                + "<img src='/cover.jpg'>", "https://example.com/watch/123");
        assertEquals(List.of("https://example.com/media/video.mp4?token=abc",
                "https://example.com/watch/stream.m3u8"), WebVideoFinder.candidates(page));
    }

    @Test public void identifiesMislabeledResponsesFromTheirBytes() {
        assertKind(WebVideoFinder.Kind.PAGE, "<!doctype html><title>Watch</title>", "video/mp4");
        assertKind(WebVideoFinder.Kind.OTHER, "{\"error\":true}", "video/mp4");
        assertKind(WebVideoFinder.Kind.OTHER, "%PDF-1.7", "application/octet-stream");
        assertKind(WebVideoFinder.Kind.HLS, "#EXTM3U\n#EXT-X-VERSION:3", "application/octet-stream");
        assertKind(WebVideoFinder.Kind.DASH, "<?xml version='1.0'?><MPD type='static'>", null);
        assertKind(WebVideoFinder.Kind.FILE, "\0\0\0\u0018ftypisom", "application/octet-stream");
        assertKind(WebVideoFinder.Kind.UNKNOWN, "unrecognized container", "video/mp4");
    }

    private static void assertKind(WebVideoFinder.Kind expected, String prefix, String mime) {
        assertEquals(expected, WebVideoFinder.inspect(prefix.getBytes(StandardCharsets.UTF_8), mime));
    }
}
