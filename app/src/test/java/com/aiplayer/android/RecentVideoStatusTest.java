package com.aiplayer.android;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecentVideoStatusTest {
    @Test
    public void transcriptFailureStaysVisibleWhenPreviousTranslationIsComplete() {
        RecentVideoStatus status = RecentVideoStatus.from(VideoSession.Task.TRANSCRIPT, 4, 4);

        assertTrue(status.warning);
        assertTrue(status.text.contains("Tạo lại transcript lỗi"));
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
        assertTrue(vietnamese.warning);
        assertTrue(vietnamese.text.contains("1/2"));
        assertFalse(japanese.warning);
        assertEquals(RecentVideoStatus.from(null, 2, 0).text, japanese.text);
    }
}
