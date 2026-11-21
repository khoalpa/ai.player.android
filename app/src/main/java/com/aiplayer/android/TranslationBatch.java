package com.aiplayer.android;

import java.util.ArrayList;
import java.util.List;

/** Applies successful results immediately so interrupted work can be checkpointed. */
final class TranslationBatch {
    final List<TranscriptCue> input = new ArrayList<>();
    private final List<TranscriptCue> all;
    private final boolean replacingLanguage;
    private boolean accepted;

    TranslationBatch(List<TranscriptCue> cues, String previousTarget, String target) {
        this(cues, previousTarget, target, null);
    }

    TranslationBatch(List<TranscriptCue> cues, String previousTarget, String target, TranscriptCue selected) {
        all = cues;
        replacingLanguage = !target.equals(previousTarget);
        for (TranscriptCue cue : cues) {
            if (selected == null ? replacingLanguage || !cue.hasTranslation() : cue == selected) {
                input.add(cue);
            }
        }
    }

    void accept(int index, String text) {
        if (!accepted && replacingLanguage) {
            for (TranscriptCue cue : all) {
                cue.setTranslation(null, TranscriptCue.TranslationOrigin.AUTOMATIC);
            }
        }
        accepted = true;
        input.get(index).setTranslation(text, TranscriptCue.TranslationOrigin.AUTOMATIC);
    }
}
