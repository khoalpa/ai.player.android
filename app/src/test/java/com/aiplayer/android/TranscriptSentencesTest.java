package com.aiplayer.android;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TranscriptSentencesTest {
    @Test
    public void sentenceCuesKeepPausesBetweenSourceBlocks() {
        List<TranscriptCue> source = List.of(new TranscriptCue(100, 900, "Hello."),
                new TranscriptCue(2_000, 3_000, "Goodbye."));
        List<TranscriptCue> sentences = TranscriptSentences.cues(TranscriptSentences.rows(source, "en"), source);
        assertEquals(2, sentences.size());
        assertEquals(900, sentences.get(0).endMs);
        assertEquals(2_000, sentences.get(1).startMs);
        assertEquals(3_000, sentences.get(1).endMs);
        assertTrue(sentences.stream().allMatch(cue -> cue.sentence));
    }

    @Test
    public void usesWordTimestampForSentenceInsideCue() {
        TranscriptCue cue = new TranscriptCue(1_000, 4_000, "Hello! Goodbye.");
        cue.wordStarts = new org.json.JSONArray()
                .put(new org.json.JSONArray().put(0).put(1_200))
                .put(new org.json.JSONArray().put(7).put(3_000));
        List<TranscriptSentences.Row> rows = TranscriptSentences.rows(List.of(cue.copy()), "en");
        assertEquals(1_200, rows.get(0).startMs);
        assertEquals(3_000, rows.get(1).startMs);
        assertEquals(1_000, cue.startMs);
    }

    @Test
    public void closesQuestionBeforeReplyAndSplitsLongUnpunctuatedSpeech() {
        List<TranscriptSentences.Row> rows = TranscriptSentences.rows(List.of(
                new TranscriptCue(22_000, 29_000,
                        "have you ever opened your bank account and thought what happened to my money oh yeah"),
                new TranscriptCue(29_000, 34_000,
                        "that feels emotional to talk about sometimes you check your balance"),
                new TranscriptCue(34_000, 41_000, "and " + "keep checking ".repeat(12))), "en");
        assertEquals("Have you ever opened your bank account and thought what happened to my money?",
                rows.get(0).text);
        assertEquals("Oh yeah that feels emotional to talk about.", rows.get(1).text);
        assertTrue(rows.get(2).text.toLowerCase(java.util.Locale.ROOT).startsWith("sometimes you check"));
        assertTrue(rows.stream().allMatch(row -> row.text.length() <= 121));
        assertTrue(rows.size() > 3);
    }

    @Test
    public void restoresQuestionAcrossCuesAndSplitsInsideCues() {
        List<TranscriptCue> cues = List.of(
                new TranscriptCue(6_000, 10_000, "it's great to have you here with us today leo you sound"),
                new TranscriptCue(10_000, 17_000, "pretty excited today what's on"),
                new TranscriptCue(17_000, 19_000, "your mind hmm i've been thinking"));
        List<TranscriptSentences.Row> rows = TranscriptSentences.rows(cues, "en");
        assertEquals(4, rows.size());
        assertEquals("Leo you sound pretty excited today.", rows.get(1).text);
        assertEquals("What's on your mind?", rows.get(2).text);
        assertTrue(rows.get(2).startMs > 10_000 && rows.get(2).startMs < 17_000);
        assertEquals("pretty excited today what's on", cues.get(1).sourceText);
    }

    @Test
    public void respectsPunctuationInsideCueAndSilenceBetweenCues() {
        List<TranscriptSentences.Row> rows = TranscriptSentences.rows(List.of(
                new TranscriptCue(0, 2_000, "Xin chào! Chào bạn"),
                new TranscriptCue(4_000, 5_000, "Tạm biệt")), "vi");
        assertEquals(3, rows.size());
        assertEquals("Xin chào!", rows.get(0).text);
        assertEquals("Chào bạn", rows.get(1).text);
        assertEquals(4_000, rows.get(2).startMs);
        assertTrue(TranscriptSentences.rows(List.of(), "en").isEmpty());
    }
}
