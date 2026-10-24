package com.aiplayer.android;

final class TranscriptCue {
    final long startMs;
    final long endMs;
    final String sourceText;
    String targetText;
    boolean spoken;

    TranscriptCue(long startMs, long endMs, String sourceText) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.sourceText = sourceText;
        this.targetText = "";
        this.spoken = false;
    }

    String displayText(boolean target) {
        if (target && targetText != null && !targetText.trim().isEmpty()) {
            return targetText;
        }
        return sourceText;
    }
}
