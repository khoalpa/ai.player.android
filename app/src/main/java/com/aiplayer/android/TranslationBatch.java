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
            if ((selected == null || cue == selected) && (replacingLanguage || !cue.hasTranslation())) {
                input.add(cue);
            }
        }
    }

    void accept(int index, String text) {
        if (!accepted && replacingLanguage) {
            for (TranscriptCue cue : all) cue.targetText = null;
        }
        accepted = true;
        input.get(index).targetText = text;
    }
}
