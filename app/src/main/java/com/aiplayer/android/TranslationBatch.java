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
        this(cues, previousTarget, target, selected, false);
    }

    TranslationBatch(List<TranscriptCue> cues, String previousTarget, String target,
                     TranscriptCue selected, boolean retranslate) {
        all = cues;
        replacingLanguage = !target.equals(previousTarget);
        for (TranscriptCue cue : cues) {
            if (selected == null ? retranslate || replacingLanguage || cue.translationPending || !cue.hasTranslation() : cue == selected) {
                input.add(cue);
            }
        }
    }

    void accept(int index, String text) {
        if (!accepted && replacingLanguage) {
            for (TranscriptCue cue : all) {
                boolean pending = cue.translationPending;
                cue.setTranslation(null, TranscriptCue.TranslationOrigin.AUTOMATIC);
                cue.translationPending = pending;
            }
        }
        accepted = true;
        input.get(index).setTranslation(text, TranscriptCue.TranslationOrigin.AUTOMATIC);
    }

    boolean acceptDraft(int index, String text, boolean sameTarget) {
        TranscriptCue cue = input.get(index);
        if (!cue.translationPending || (sameTarget && cue.hasTranslation())) return false;
        accept(index, text);
        cue.translationPending = true;
        return true;
    }
}
