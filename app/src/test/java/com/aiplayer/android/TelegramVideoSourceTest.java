package com.aiplayer.android;

import java.net.URI;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class TelegramVideoSourceTest {
    @Test
    public void normalizesPublicPostsAndRejectsPrivatePosts() {
        assertEquals("https://t.me/sample/123?embed=1&single=1", TelegramVideoSource.embedUri(
                URI.create("https://t.me/s/sample/123?single")).toString());
        assertThrows(IllegalArgumentException.class, () ->
                TelegramVideoSource.embedUri(URI.create("https://t.me/c/12345/123")));
    }

    @Test
    public void extractsDirectAndNestedVideoSources() {
        assertEquals("https://cdn.example/video?a=1&b=2", TelegramVideoSource.extractVideo(
                "<video class=\"tgme_widget_message_video\" src=\"https://cdn.example/video?a=1&amp;b=2\"></video>"));
        assertEquals("https://cdn.example/video.mp4", TelegramVideoSource.extractVideo(
                "<video><source src='https://cdn.example/video.mp4' type='video/mp4'></video>"));
    }

    @Test
    public void ignoresPreviewsWithoutAnHttpsVideo() {
        assertNull(TelegramVideoSource.extractVideo(
                "<video poster='https://cdn.example/image.jpg' src='blob:sample'></video>"));
    }
}
