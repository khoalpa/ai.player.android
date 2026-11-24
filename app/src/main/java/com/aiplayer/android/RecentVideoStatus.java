package com.aiplayer.android;

import java.util.List;
import java.util.Objects;

import static com.aiplayer.android.PlayerAppearance.StatusIcon.*;

final class RecentVideoStatus {
    final String text;
    final PlayerAppearance.StatusIcon icon;

    RecentVideoStatus(String text, PlayerAppearance.StatusIcon icon) {
        this.text = text;
        this.icon = icon;
    }

    static final class Summary {
        private final String target;
        private final int total;
        private final int translated;

        Summary(List<TranscriptCue> cues, String target) {
            this.target = target;
            total = cues.size();
            translated = translatedCount(cues, target, target);
        }

        RecentVideoStatus statusFor(String selectedTarget, VideoSession.Task failure, String failedTarget) {
            if (failure == VideoSession.Task.TRANSLATION && !Objects.equals(selectedTarget, failedTarget)) {
                failure = null;
            }
            return from(failure, total, Objects.equals(selectedTarget, target) ? translated : 0);
        }
    }

    static int translatedCount(List<TranscriptCue> cues, String actualTarget, String selectedTarget) {
        if (!Objects.equals(actualTarget, selectedTarget) || actualTarget == null) return 0;
        int translated = 0;
        for (TranscriptCue cue : cues) {
            if (cue.hasTranslation()) translated++;
        }
        return translated;
    }

    static RecentVideoStatus from(VideoSession.Task failed, int total, int translated) {
        if (total == 0) {
            return failed == VideoSession.Task.TRANSCRIPT
                    ? new RecentVideoStatus("Tạo transcript lỗi", ERROR)
                    : new RecentVideoStatus("Chưa có transcript", NEUTRAL);
        }
        if (failed == VideoSession.Task.TRANSCRIPT) {
            return new RecentVideoStatus("Có transcript · Tạo lại transcript lỗi", ERROR);
        }
        if (translated == total && failed != VideoSession.Task.TRANSLATION) {
            return new RecentVideoStatus("Có transcript · Đã dịch", SUCCESS);
        }
        if (translated > 0) {
            String progress = "Có transcript · Dịch " + translated + "/" + total + " câu";
            return new RecentVideoStatus(failed == VideoSession.Task.TRANSLATION
                    ? progress + " · Lần dịch gần nhất bị lỗi" : progress,
                    failed == VideoSession.Task.TRANSLATION ? ERROR : WARNING);
        }
        if (failed == VideoSession.Task.TRANSLATION) {
            return new RecentVideoStatus("Có transcript · Dịch lỗi", ERROR);
        }
        return new RecentVideoStatus("Có transcript · Chưa dịch", SUCCESS);
    }
}
