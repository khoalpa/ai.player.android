package com.aiplayer.android;

import java.util.List;

final class CueTimeline {
    enum Mode {
        OFF, SOURCE, TRANSLATION;

        String text(TranscriptCue cue) {
            if (cue == null || this == OFF) return "";
            if (this == SOURCE) return cue.sourceText;
            return cue.hasTranslation() ? cue.targetText : cue.sourceText;
        }
    }

    private final List<TranscriptCue> cues;
    private long[] latestEnd;

    CueTimeline(List<TranscriptCue> cues) {
        this.cues = cues;
        rebuild();
    }

    void rebuild() {
        latestEnd = new long[cues.size()];
        long end = Long.MIN_VALUE;
        for (int i = 0; i < cues.size(); i++) {
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
