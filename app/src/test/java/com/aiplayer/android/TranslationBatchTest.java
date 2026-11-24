package com.aiplayer.android;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationBatchTest {
    @Test public void draftRemainsPendingAndCannotOverwriteRefinement() {
        List<TranscriptCue> cues = cues();
        TranslationBatch batch = new TranslationBatch(cues, "vi", "vi");
        TranscriptCue cue = batch.input.get(0);
        cue.translationPending = true;

        assertTrue(batch.acceptDraft(0, "draft", true));
        assertTrue(cue.translationPending);
        assertTrue(new TranslationBatch(cues, "vi", "vi").input.contains(cue));
        batch.accept(0, "refined");
        assertFalse(batch.acceptDraft(0, "late draft", true));
        assertFalse(cue.translationPending);
        assertEquals("refined", cue.targetText);
    }

    private List<TranscriptCue> cues() {
        TranscriptCue first = new TranscriptCue(0, 1000, "one");
        first.targetText = "một";
        return Arrays.asList(first, new TranscriptCue(1000, 2000, "two"),
                new TranscriptCue(2000, 3000, "three"));
    }

    @Test public void interruptedRetryKeepsSuccessesAndNextRetrySelectsOnlyMissing() {
        List<TranscriptCue> cues = cues();
        TranslationBatch batch = new TranslationBatch(cues, "vi", "vi");
        assertEquals(2, batch.input.size());
        batch.accept(0, "hai");
        assertEquals("một", cues.get(0).targetText);
        assertEquals("hai", cues.get(1).targetText);
        TranslationBatch resumed = new TranslationBatch(cues, "vi", "vi");
        assertEquals(1, resumed.input.size());
        assertSame(cues.get(2), resumed.input.get(0));
    }

    @Test public void retranslateSameLanguageReplacesOnlySuccessfulResults() {
        List<TranscriptCue> cues = cues();
        cues.get(1).setTranslation("hai", TranscriptCue.TranslationOrigin.MANUAL);
        TranslationBatch batch = new TranslationBatch(cues, "vi", "vi", null, true);

        assertEquals(cues, batch.input);
        batch.accept(0, "một mới");
        assertEquals("một mới", cues.get(0).targetText);
        assertEquals("hai", cues.get(1).targetText);
        assertEquals(TranscriptCue.TranslationOrigin.MANUAL, cues.get(1).translationOrigin);
    }

    @Test public void resumeOnlyReplacesUnfinishedCuesEvenWithOldTranslations() {
        List<TranscriptCue> cues = cues();
        for (TranscriptCue cue : cues) {
            cue.setTranslation("old", TranscriptCue.TranslationOrigin.AUTOMATIC);
            cue.translationPending = true;
        }
        TranslationBatch batch = new TranslationBatch(cues, "vi", "vi", null, true);
        batch.accept(0, "new");

        List<TranscriptCue> snapshot = Arrays.asList(cues.get(0).copy(), cues.get(1).copy(), cues.get(2).copy());
        TranslationBatch resumed = new TranslationBatch(snapshot, "vi", "vi");
        assertEquals(Arrays.asList(snapshot.get(1), snapshot.get(2)), resumed.input);
        assertEquals("new", snapshot.get(0).targetText);
        assertEquals("old", snapshot.get(1).targetText);
        resumed.accept(0, "new two");
        assertFalse(snapshot.get(1).translationPending);
        assertTrue(snapshot.get(2).translationPending);
    }

    @Test public void failedLanguageChangeLeavesExistingTranslationIntact() {
        List<TranscriptCue> cues = cues();
        TranslationBatch batch = new TranslationBatch(cues, "vi", "en");
        assertEquals(3, batch.input.size());
        assertEquals("một", cues.get(0).targetText);
    }

    @Test public void repairingOneCueLeavesOtherCuesUntouched() {
        List<TranscriptCue> cues = cues();
        TranslationBatch batch = new TranslationBatch(cues, "vi", "vi", cues.get(2));
        assertEquals(1, batch.input.size());
        assertSame(cues.get(2), batch.input.get(0));
        batch.accept(0, "ba");
        assertTrue(cues.get(0).hasTranslation());
        assertFalse(cues.get(1).hasTranslation());
        assertEquals("ba", cues.get(2).targetText);
    }

    @Test public void partialLanguageChangeNeverMixesOldAndNewLanguages() {
        List<TranscriptCue> cues = cues();
        TranslationBatch batch = new TranslationBatch(cues, "vi", "en");
        batch.accept(1, "two");
        assertFalse(cues.get(0).hasTranslation());
        assertEquals("two", cues.get(1).targetText);
        batch.accept(2, "three");
        assertEquals("two", cues.get(1).targetText);
        assertEquals("three", cues.get(2).targetText);
        TranslationBatch resumed = new TranslationBatch(cues, "en", "en");
        assertEquals(1, resumed.input.size());
        assertSame(cues.get(0), resumed.input.get(0));
    }

    @Test public void retryKeepsManualAndOriginalChoicesButSelectedCueCanBeTranslatedAgain() {
        List<TranscriptCue> cues = cues();
        TranscriptCue original = cues.get(0);
        original.setTranslation(original.sourceText, TranscriptCue.TranslationOrigin.ORIGINAL);
        TranscriptCue manual = cues.get(1);
        manual.setTranslation("hai", TranscriptCue.TranslationOrigin.MANUAL);

        TranslationBatch retry = new TranslationBatch(cues, "vi", "vi");
        assertEquals(Arrays.asList(cues.get(2)), retry.input);
        assertEquals(TranscriptCue.TranslationOrigin.MANUAL, manual.copy().translationOrigin);
        assertEquals(TranscriptCue.TranslationOrigin.ORIGINAL, original.copy().translationOrigin);

        TranslationBatch selected = new TranslationBatch(cues, "vi", "vi", original);
        assertEquals(Arrays.asList(original), selected.input);
        assertEquals(original.sourceText, original.targetText);
        selected.accept(0, "mot");
        assertEquals("mot", original.targetText);
        assertEquals(TranscriptCue.TranslationOrigin.AUTOMATIC, original.translationOrigin);
        assertEquals("hai", manual.targetText);
        assertEquals(TranscriptCue.TranslationOrigin.MANUAL, manual.translationOrigin);
    }
    @Test public void onlineResumeKeepsManualTranslationAndCompletedBatch() {
        List<TranscriptCue> cues = cues();
        cues.get(0).setTranslation("manual", TranscriptCue.TranslationOrigin.MANUAL);
        TranslationBatch firstBatch = new TranslationBatch(cues, "vi", "vi");
        firstBatch.accept(0, "translated");

        TranslationBatch resume = new TranslationBatch(cues, "vi", "vi");
        assertEquals(Arrays.asList(cues.get(2)), resume.input);
        assertEquals("translated", cues.get(1).targetText);
        assertEquals("manual", cues.get(0).targetText);
        assertEquals(TranscriptCue.TranslationOrigin.MANUAL, cues.get(0).translationOrigin);
    }
}
