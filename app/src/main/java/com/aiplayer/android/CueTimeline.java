package com.aiplayer.android;

import java.util.Arrays;
import java.util.List;

final class CueTimeline {
    enum Mode {
        OFF, SOURCE, TRANSLATION;

        String text(TranscriptCue cue) {
            if (cue == null || this == OFF) return "";
            if (this == SOURCE) return cue.sourceText;
            return cue.hasTranslation() ? cue.targetText : "";
        }

        String speechLanguage(TranscriptCue cue, String source, String target) {
            if (cue == null || this == OFF) return null;
            if (this == SOURCE) return source;
            if (!cue.hasTranslation()) return null;
            return cue.translationOrigin == TranscriptCue.TranslationOrigin.ORIGINAL ? source : target;
        }
    }

    private final List<TranscriptCue> cues;
    private long[] latestEnd = new long[0];

    CueTimeline(List<TranscriptCue> cues) {
        this.cues = cues;
        rebuild();
    }

    void rebuild() {
        extendFrom(0);
    }

    void extendFrom(int first) {
        if (cues.size() > latestEnd.length) {
            latestEnd = Arrays.copyOf(latestEnd, Math.max(cues.size(), latestEnd.length * 2));
        }
        long end = first == 0 ? Long.MIN_VALUE : latestEnd[first - 1];
        for (int i = first; i < cues.size(); i++) {
            end = Math.max(end, cues.get(i).endMs);
            latestEnd[i] = end;
        }
    }

    TranscriptCue at(long positionMs) {
        int low = 0;
        int high = cues.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cues.get(mid).startMs <= positionMs) low = mid + 1;
            else high = mid;
        }
        for (int i = low - 1; i >= 0 && positionMs < latestEnd[i]; i--) {
            TranscriptCue cue = cues.get(i);
            if (positionMs < cue.endMs) return cue;
        }
        return null;
    }
}
