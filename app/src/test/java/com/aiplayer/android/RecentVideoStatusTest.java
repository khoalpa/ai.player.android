package com.aiplayer.android;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RecentVideoStatusTest {
    @Test
    public void transcriptFailureStaysVisibleWhenPreviousTranslationIsComplete() {
        RecentVideoStatus status = RecentVideoStatus.from(VideoSession.Task.TRANSCRIPT, 4, 4);

        assertEquals(PlayerAppearance.StatusIcon.ERROR, status.icon);
        assertTrue(status.text.contains("Tạo lại transcript lỗi"));
    }

    @Test
    public void partialTranslationUsesWarningAndCompletedTranslationUsesSuccess() {
        assertEquals(PlayerAppearance.StatusIcon.WARNING, RecentVideoStatus.from(null, 2, 1).icon);
        assertEquals(PlayerAppearance.StatusIcon.SUCCESS, RecentVideoStatus.from(null, 2, 2).icon);
    }

    @Test
    public void savedTranslationFailureOnlyAppliesToItsTargetLanguage() {
        TranscriptCue translated = new TranscriptCue(0, 1000, "Hello");
        translated.targetText = "Xin chào";
        TranscriptCue pending = new TranscriptCue(1000, 2000, "Goodbye");
        RecentVideoStatus.Summary summary = new RecentVideoStatus.Summary(
                Arrays.asList(translated, pending), "vi");

        RecentVideoStatus vietnamese = summary.statusFor("vi", VideoSession.Task.TRANSLATION, "vi");
        RecentVideoStatus japanese = summary.statusFor("ja", VideoSession.Task.TRANSLATION, "vi");
        assertEquals(PlayerAppearance.StatusIcon.ERROR, vietnamese.icon);
        assertTrue(vietnamese.text.contains("1/2"));
        assertEquals(PlayerAppearance.StatusIcon.SUCCESS, japanese.icon);
        assertEquals(RecentVideoStatus.from(null, 2, 0).text, japanese.text);
    }
}
