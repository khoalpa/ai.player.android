package com.aiplayer.android;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class TranscriptExportTest {
    @Test
    public void bilingualSrtPreservesOriginalTextAndTimestampsWhenTranslationIsMissing() {
        TranscriptCue translated = new TranscriptCue(1200, 2450, "Hello");
        translated.targetText = "Xin chào";
        TranscriptCue missingTranslation = new TranscriptCue(3000, 4750, "Goodbye");

        assertEquals("1\n00:00:01,200 --> 00:00:02,450\nHello\nXin chào\n\n"
                        + "2\n00:00:03,000 --> 00:00:04,750\nGoodbye\n\n",
                TranscriptExport.render(Arrays.asList(translated, missingTranslation),
                        TranscriptExport.Content.BILINGUAL, TranscriptExport.Format.SRT));
    }

    @Test
    public void translationOnlyTxtOmitsUntranslatedCues() {
        TranscriptCue translated = new TranscriptCue(0, 1000, "Hello");
        translated.targetText = "Xin chào";
        assertEquals("Xin chào\n\n", TranscriptExport.render(
                Arrays.asList(translated, new TranscriptCue(1000, 2000, "Goodbye")),
                TranscriptExport.Content.TRANSLATION, TranscriptExport.Format.TXT));
    }
}
