package com.aiplayer.android;

final class TranscriptCue {
    final long startMs;
    final long endMs;
    final String sourceText;
    String targetText;

    boolean hasTranslation() {
        return targetText != null && !targetText.trim().isEmpty();
    }

    TranscriptCue(long startMs, long endMs, String sourceText) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.sourceText = sourceText;
        this.targetText = "";
    }
}
