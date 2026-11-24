package com.aiplayer.android;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Converts finalized recognition words into stable, media-timed sentences. */
final class SpokenSentences {
    private static final long SENTENCE_GAP_MS = 700;
    private static final long MAX_DURATION_MS = 8_000;
    private static final int MAX_CHARS = 120;
    private static final Pattern END = Pattern.compile(
            "(?:[!?。！？]+|(?<!\\.)\\.)[\\p{Pe}\\p{Pf}\"']*$");
    private static final Pattern ABBREVIATION = Pattern.compile(
            "(?iu)(?:[a-z]|mr|mrs|ms|dr|prof|st|vs|etc|tp|ts|ths|bs|pgs|gs|(?:[a-z]\\.)+[a-z])\\.");

    private static final class Word {
        final String text;
        final long start;
        final long end;
        final boolean endsSentence;

        Word(String text, long start, long end, boolean endsSentence) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.endsSentence = endsSentence;
        }
    }

    private final List<Word> pending = new ArrayList<>();
    private final List<TranscriptCue> cues;
    private int chars;

    SpokenSentences(List<TranscriptCue> cues) {
        this.cues = cues;
    }

    void add(String text, long start, long end) {
        if (!pending.isEmpty()) {
            Word last = pending.get(pending.size() - 1);
            start = Math.max(start, last.start);
            if (start > last.start && (last.endsSentence || start - last.end >= SENTENCE_GAP_MS)) {
                emit(pending.size(), start);
            }
        }
        if (pending.isEmpty() && !cues.isEmpty()) {
            start = Math.max(start, cues.get(cues.size() - 1).endMs);
        }
        end = Math.max(start + 1, end);
        while (!pending.isEmpty() && start > pending.get(0).start
                && (end - pending.get(0).start > MAX_DURATION_MS
                || chars + 1 + text.length() > MAX_CHARS)) {
            int cut = pending.size();
            long best = score(pending.get(cut - 1), start);
            for (int i = cut - 1; i >= (pending.size() + 1) / 2; i--) {
                Word next = pending.get(i);
                long score = score(pending.get(i - 1), next.start);
                if (next.start > pending.get(0).start && score > best) {
                    best = score;
                    cut = i;
                }
            }
            emit(cut, cut == pending.size() ? start : pending.get(cut).start);
        }
        if (!pending.isEmpty()) chars++;
        chars += text.length();
        pending.add(new Word(text, start, end,
                END.matcher(text).find() && !ABBREVIATION.matcher(text).matches()));
    }

    void finish() {
        if (!pending.isEmpty()) emit(pending.size(), Long.MAX_VALUE);
    }

    void addUntimed(String text, long end) {
        if (text.isEmpty()) return;
        finish();
        long start = Math.max(0, end - 4_000);
        if (!cues.isEmpty()) start = Math.max(start, cues.get(cues.size() - 1).endMs);
        cues.add(TranscriptSentences.cue(start, Math.max(start + 1, end), text));
    }

    private static long score(Word word, long nextStart) {
        long pause = Math.max(0, nextStart - word.end);
        return pause + (word.text.matches(".*[,;:，、；：]$") ? 300 : 0);
    }

    private void emit(int count, long nextStart) {
        StringBuilder text = new StringBuilder();
        JSONArray wordStarts = new JSONArray();
        long start = pending.get(0).start;
        long end = start + 1;
        for (int i = 0; i < count; i++) {
            Word word = pending.get(i);
            if (i > 0) text.append(' ');
            wordStarts.put(new JSONArray().put(text.length()).put(word.start));
            text.append(word.text);
            end = Math.max(end, word.end);
        }
        TranscriptCue cue = TranscriptSentences.cue(start, Math.min(end, nextStart), text.toString());
        cue.wordStarts = wordStarts;
        cues.add(cue);
        pending.subList(0, count).clear();
        chars -= text.length() + (pending.isEmpty() ? 0 : 1);
    }
}
