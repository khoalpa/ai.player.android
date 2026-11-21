package com.aiplayer.android;

import org.junit.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class TelegramPlaybackHandoffTest {
    @Test
    public void downloadedVideoOpensAtPositionCapturedBeforeDownload() {
        File downloaded = new File("downloaded.mp4");
        AtomicLong inlinePosition = new AtomicLong(12_345L);
        AtomicLong openedPosition = new AtomicLong(-1);
        TelegramPlaybackHandoff handoff = new TelegramPlaybackHandoff(inlinePosition.get(), 2_000L,
                (file, position) -> {
                    assertEquals(downloaded, file);
                    openedPosition.set(position);
                });

        inlinePosition.set(20_000L);
        handoff.open(downloaded);

        assertEquals(12_345L, openedPosition.get());
    }

    @Test
    public void usesRememberedPositionOnlyWhenCurrentPositionIsUnavailable() {
        File saved = new File("saved.mp4");
        new TelegramPlaybackHandoff(-1, 5_000L, (file, position) -> assertEquals(5_000L, position)).open(saved);
        new TelegramPlaybackHandoff(0, 5_000L, (file, position) -> assertEquals(0L, position)).open(saved);
        new TelegramPlaybackHandoff(-1, -1, (file, position) -> assertEquals(-1L, position)).open(saved);
    }
}
