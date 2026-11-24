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
    JSONArray wordStarts;
    boolean sentence;

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
        copy.wordStarts = wordStarts;
        copy.sentence = sentence;
        return copy;
    }

    TranscriptCue(long startMs, long endMs, String sourceText) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.sourceText = sourceText;
        this.targetText = "";
    }
}
