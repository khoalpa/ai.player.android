package com.aiplayer.android;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

final class SentenceTranscriptEdits {
    static final String PREFERENCES = "sentence_transcript";
    private final SharedPreferences preferences;
    private String key;
    private List<TranscriptSentences.Row> rows;

    SentenceTranscriptEdits(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    List<TranscriptSentences.Row> open(String video, List<TranscriptCue> cues, String language) {
        StringBuilder signature = new StringBuilder();
        for (TranscriptCue cue : cues) {
            signature.append(cue.startMs).append(':').append(cue.endMs).append(':')
                    .append(cue.sourceText.length()).append(':').append(cue.sourceText);
        }
        key = video + "\n" + java.util.UUID.nameUUIDFromBytes(
                signature.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String saved = preferences.getString(key, null);
        if (saved != null) {
            try {
                JSONArray data = new JSONArray(saved);
                rows = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    JSONArray row = data.getJSONArray(i);
                    rows.add(new TranscriptSentences.Row(row.getLong(0), row.getString(1)));
                }
                return rows;
            } catch (JSONException ignored) {
                // Rebuild from the original cues if the saved reading view is invalid.
            }
        }
        rows = TranscriptSentences.rows(cues, language);
        return rows;
    }

    void save() {
        JSONArray data = new JSONArray();
        for (TranscriptSentences.Row row : rows) {
            data.put(new JSONArray().put(row.startMs).put(row.text));
        }
        preferences.edit().putString(key, data.toString()).apply();
    }

    String translation(String pair, String text) {
        return preferences.getString(translationKey(pair, text), "");
    }

    List<TranscriptCue> migrate(String video, List<TranscriptCue> cues, String source,
                                String legacyTarget, String configuredTarget, boolean keepBackup) {
        if (cues.stream().allMatch(cue -> cue.sentence)) return cues;
        List<TranscriptCue> sentences = TranscriptSentences.cues(open(video, cues, source), cues);
        String target = legacyTarget == null ? configuredTarget : legacyTarget;
        String pair = source + ">" + target;
        for (TranscriptCue sentence : sentences) {
            String saved = translation(pair, sentence.sourceText);
            if (!saved.trim().isEmpty()) {
                sentence.setTranslation(saved, TranscriptCue.TranslationOrigin.AUTOMATIC);
            }
        }
        JSONArray backup = new JSONArray();
        int first = 0;
        for (TranscriptCue cue : cues) {
            if (!cue.hasTranslation()) continue;
            while (first < sentences.size() && sentences.get(first).endMs <= cue.startMs) first++;
            if (first < sentences.size()) {
                TranscriptCue sentence = sentences.get(first);
                boolean single = first + 1 == sentences.size()
                        || sentences.get(first + 1).startMs >= cue.endMs;
                if (single && sentence.startMs < cue.endMs && !sentence.hasTranslation()
                        && TranscriptSentences.sameText(cue.sourceText, sentence.sourceText)) {
                    sentence.setTranslation(cue.targetText, cue.translationOrigin);
                    sentence.translationNeedsReview = cue.translationNeedsReview;
                    sentence.translatedDirectly = cue.translatedDirectly;
                    continue;
                }
            }
            if (!keepBackup) continue;
            try {
                backup.put(new org.json.JSONObject().put("start", cue.startMs).put("end", cue.endMs)
                        .put("source", cue.sourceText).put("target", cue.targetText)
                        .put("origin", cue.translationOrigin.name()));
            } catch (JSONException ex) {
                throw new IllegalStateException(ex);
            }
        }
        if (backup.length() > 0) {
            String backupKey = key + "\nlegacy>" + legacyTarget;
            // Keep the first archived copy if the old snapshot is opened again.
            if (!preferences.contains(backupKey)) {
                preferences.edit().putString(backupKey, backup.toString()).commit();
            }
        }
        return sentences;
    }

    void remove(String video, boolean translationOnly) {
        String prefix = video + "\n";
        SharedPreferences.Editor editor = preferences.edit();
        for (String storedKey : preferences.getAll().keySet()) {
            if (storedKey.startsWith(prefix)
                    && (!translationOnly || storedKey.indexOf('\n', prefix.length()) >= 0)) {
                editor.remove(storedKey);
            }
        }
        editor.apply();
    }

    void saveTranslation(String pair, String text, String translated) {
        preferences.edit().putString(translationKey(pair, text), translated).apply();
    }

    private String translationKey(String pair, String text) {
        return key + "\n" + pair + "\n" + text;
    }
}
