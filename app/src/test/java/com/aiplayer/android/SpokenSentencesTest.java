package com.aiplayer.android;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SpokenSentencesTest {
    @Test public void splitsOnRealPauseAndPreservesWordTimes() {
        List<TranscriptCue> cues = new ArrayList<>();
        SpokenSentences sentences = new SpokenSentences(cues);
        sentences.add("xin", 100, 220);
        sentences.add("chào", 240, 470);
        assertTrue(cues.isEmpty());
        sentences.add("mời", 1170, 1300);
        assertEquals(1, cues.size());
        TranscriptCue first = cues.get(0);
        assertEquals("xin chào", first.sourceText);
        assertEquals(100, first.startMs);
        assertEquals(470, first.endMs);
        assertEquals(240, first.wordStarts.optJSONArray(1).optLong(1));
        sentences.finish();
        assertEquals(1170, cues.get(1).startMs);
    }

    @Test public void recognizesPunctuationAndKeepsAbbreviationsInsideSentence() {
        List<TranscriptCue> cues = new ArrayList<>();
        SpokenSentences sentences = new SpokenSentences(cues);
        sentences.add("Dr.", 0, 200);
        sentences.add("Lee.", 250, 500);
        assertTrue(cues.isEmpty());
        sentences.add("Hello!", 470, 750);
        assertEquals("Dr. Lee.", cues.get(0).sourceText);
        assertEquals(470, cues.get(0).endMs);
        sentences.finish();
        assertEquals(470, cues.get(1).startMs);
    }

    @Test public void prefersPauseBeforeLengthLimitAndPagesLongOriginalSubtitle() {
        List<TranscriptCue> cues = new ArrayList<>();
        SpokenSentences sentences = new SpokenSentences(cues);
        String token = "abcdefghijklmnopqrst";
        for (int i = 0; i < 6; i++) {
            long start = i * 300 + (i >= 3 ? 300 : 0);
            sentences.add(token, start, start + 290);
        }
        assertEquals(1, cues.size());
        assertEquals(890, cues.get(0).endMs);
        sentences.add(token, 2100, 2390);
        sentences.finish();
        TranscriptCue second = cues.get(1);
        assertEquals(String.join(" ", token, token, token), second.sourceSubtitle(2099));
        assertEquals(token, second.sourceSubtitle(2100));
        assertEquals(83, second.sourceText.length());
    }

    @Test public void keepsMatchingTranslationsAndProtectsUnmatchedManualEdits() {
        TranscriptCue old = TranscriptSentences.cue(100, 500, "Hello.");
        old.setTranslation("Xin chào", TranscriptCue.TranslationOrigin.AUTOMATIC);
        TranscriptCue changed = TranscriptSentences.cue(900, 1500, "Welcome home");
        changed.setTranslation("Chào mừng", TranscriptCue.TranslationOrigin.AUTOMATIC);
        TranscriptCue same = TranscriptSentences.cue(120, 520, "Hello");
        TranscriptCue split = TranscriptSentences.cue(900, 1150, "Welcome");
        List<TranscriptCue> generated = List.of(same, split);
        assertTrue(VideoSession.retainTranscriptEdits(List.of(old, changed), generated, true));
        assertEquals("Xin chào", same.targetText);
        assertFalse(split.hasTranslation());
        changed.sourceEdited = true;
        assertFalse(VideoSession.retainTranscriptEdits(List.of(old, changed), generated, true));
    }

    @Test public void reviewedEditsOnlyReplaceOverlappingNewCues() {
        TranscriptCue edited = TranscriptSentences.cue(500, 1500, "Edited");
        edited.setTranslation("Đã sửa", TranscriptCue.TranslationOrigin.MANUAL);
        TranscriptCue before = TranscriptSentences.cue(0, 500, "Before");
        TranscriptCue first = TranscriptSentences.cue(500, 900, "First fragment");
        TranscriptCue second = TranscriptSentences.cue(900, 1500, "Second fragment");
        TranscriptCue after = TranscriptSentences.cue(1500, 2000, "After");
        List<TranscriptCue> generated = List.of(before, first, second, after);
        assertEquals(List.of(before, edited, after),
                VideoSession.mergeReviewedTranscript(generated, List.of(edited)));
        assertEquals(generated, VideoSession.mergeReviewedTranscript(generated, List.of()));
    }
}
