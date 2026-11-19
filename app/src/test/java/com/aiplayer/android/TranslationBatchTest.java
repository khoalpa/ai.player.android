package com.aiplayer.android;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationBatchTest {
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
}
