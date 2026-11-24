package com.aiplayer.android;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SentenceTranscriptEditsTest {
    @Test
    public void exportsSavedSentenceTranslationThenRemovesItAcrossLanguagePairs() {
        SharedPreferences preferences = inMemoryPreferences();
        String video = "content://videos/1";
        String otherVideo = "content://videos/10";
        List<TranscriptCue> original = List.of(new TranscriptCue(1000, 4000, "Hello."));
        SentenceTranscriptEdits player = new SentenceTranscriptEdits(preferences);
        player.open(otherVideo, original, "en");
        player.saveTranslation("en>vi", "Hello.", "Other video");
        List<TranscriptSentences.Row> rows = player.open(video, original, "en");
        rows.set(0, new TranscriptSentences.Row(1200, "Hello there."));
        player.save();
        player.saveTranslation("en>vi", "Hello there.", "Xin chào.");
        player.saveTranslation("en>ja", "Hello there.", "こんにちは。");

        SentenceTranscriptEdits stored = new SentenceTranscriptEdits(preferences);
        List<TranscriptCue> exported = stored.migrate(video, original, "en", "vi", null, false);
        assertEquals("1\n00:00:01,200 --> 00:00:04,000\nHello there.\nXin chào.\n\n",
                TranscriptExport.render(exported, TranscriptExport.Content.BILINGUAL, TranscriptExport.Format.SRT));
        assertFalse(original.get(0).hasTranslation());

        stored.remove(video, true);
        assertEquals("Hello there.", stored.open(video, original, "en").get(0).text);
        assertFalse(stored.migrate(video, original, "en", "vi", null, false).get(0).hasTranslation());
        assertFalse(stored.migrate(video, original, "en", "ja", null, false).get(0).hasTranslation());
        assertTrue(stored.migrate(otherVideo, original, "en", "vi", null, false).get(0).hasTranslation());

        stored.remove(video, false);
        assertEquals("Hello.", stored.open(video, original, "en").get(0).text);
    }

    @Test
    public void archivesAnAmbiguousLegacyTranslationWithoutAttachingItToOneSentence() {
        SharedPreferences preferences = inMemoryPreferences();
        TranscriptCue legacy = new TranscriptCue(0, 4_000, "Hello! Goodbye.");
        legacy.setTranslation("Both sentences", TranscriptCue.TranslationOrigin.MANUAL);
        List<TranscriptCue> migrated = new SentenceTranscriptEdits(preferences)
                .migrate("video", List.of(legacy), "en", "vi", "vi", true);
        assertEquals(2, migrated.size());
        assertTrue(migrated.stream().allMatch(cue -> cue.sentence && !cue.hasTranslation()));
        assertTrue(preferences.getAll().values().stream().anyMatch(value -> value.toString().contains("Both sentences")));
    }

    @Test
    public void migratesASingleMatchingLegacyTranslationAndKeepsItsOrigin() {
        TranscriptCue legacy = new TranscriptCue(100, 2_000, "Hello.");
        legacy.setTranslation("Xin chao.", TranscriptCue.TranslationOrigin.MANUAL);
        TranscriptCue migrated = new SentenceTranscriptEdits(inMemoryPreferences())
                .migrate("video", List.of(legacy), "en", "vi", "vi", false).get(0);
        assertEquals(100, migrated.startMs);
        assertEquals(2_000, migrated.endMs);
        assertEquals("Xin chao.", migrated.targetText);
        assertEquals(TranscriptCue.TranslationOrigin.MANUAL, migrated.translationOrigin);
        assertTrue(migrated.sentence);
    }

    private static SharedPreferences inMemoryPreferences() {
        Map<String, String> values = new HashMap<>();
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getString":
                            return values.getOrDefault((String) args[0], (String) args[1]);
                        case "contains":
                            return values.containsKey(args[0]);
                        case "getAll":
                            return new HashMap<>(values);
                        case "edit":
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                    new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, change) -> {
                                        switch (operation.getName()) {
                                            case "putString":
                                                values.put((String) change[0], (String) change[1]);
                                                return editor;
                                            case "remove":
                                                values.remove(change[0]);
                                                return editor;
                                            case "commit":
                                                return true;
                                            case "apply":
                                                return null;
                                            default:
                                                throw new UnsupportedOperationException(operation.getName());
                                        }
                                    });
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
