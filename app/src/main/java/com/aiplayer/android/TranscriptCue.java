package com.aiplayer.android;

import org.json.JSONArray;

final class TranscriptCue {
    enum TranslationOrigin { AUTOMATIC, ORIGINAL, MANUAL }

    final long startMs;
    final long endMs;
    String sourceText;
    String targetText;
    TranslationOrigin translationOrigin = TranslationOrigin.AUTOMATIC;
    boolean translationNeedsReview;
    boolean translationPending;
    JSONArray wordStarts;
    boolean sentence;
    boolean sourceEdited;

    String sourceSubtitle(long positionMs) {
        if (wordStarts == null || sourceText.length() <= 64) return sourceText;
        int pageStart = 0;
        for (int i = 1; i < wordStarts.length(); i++) {
            JSONArray word = wordStarts.optJSONArray(i);
            if (word == null) continue;
            int offset = word.optInt(0, -1);
            if (offset <= pageStart || offset >= sourceText.length()) continue;
            if (offset - pageStart >= 48) {
                if (positionMs < word.optLong(1, Long.MAX_VALUE)) {
                    return sourceText.substring(pageStart, offset).trim();
                }
                pageStart = offset;
            }
        }
        return sourceText.substring(pageStart).trim();
    }

    boolean hasTranslation() {
        return targetText != null && !targetText.trim().isEmpty();
    }

    boolean canEditTranslation() {
        return !hasTranslation() || translationOrigin != TranslationOrigin.AUTOMATIC;
    }

    void setTranslation(String text, TranslationOrigin origin) {
        targetText = text;
        translationOrigin = origin;
        translationNeedsReview = false;
        translationPending = false;
    }

    void syncTranslationAfterSourceEdit() {
        if (translationOrigin == TranslationOrigin.ORIGINAL) {
            setTranslation(sourceText, TranslationOrigin.ORIGINAL);
        } else if (hasTranslation()) {
            translationNeedsReview = true;
        }
    }

    TranscriptCue copy() {
        TranscriptCue copy = new TranscriptCue(startMs, endMs, sourceText);
        copy.setTranslation(targetText, translationOrigin);
        copy.translationNeedsReview = translationNeedsReview;
        copy.translationPending = translationPending;
        copy.wordStarts = wordStarts;
        copy.sentence = sentence;
        copy.sourceEdited = sourceEdited;
        return copy;
    }

    TranscriptCue(long startMs, long endMs, String sourceText) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.sourceText = sourceText;
        this.targetText = "";
    }
}
