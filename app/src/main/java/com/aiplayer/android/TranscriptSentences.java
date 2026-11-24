package com.aiplayer.android;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds reading rows from source text without modifying subtitle cues. */
final class TranscriptSentences {
    private static final long SENTENCE_GAP_MS = 700;
    private static final long MAX_UNPUNCTUATED_MS = 8_000;
    private static final int MAX_UNPUNCTUATED_CHARS = 120;
    private static final Pattern WORD_GAP = Pattern.compile("\\s+");
    private static final Pattern PUNCTUATION = Pattern.compile(
            "[.!?]+[\\p{Pe}\\p{Pf}\"']*(?=\\s|$)|[\\u3002\\uFF01\\uFF1F]+[\\p{Pe}\\p{Pf}]*");
    private static final Pattern ENGLISH_START = Pattern.compile(
            "(?i)(?<!\\S)(?:(?<question>what['\\u2019]s on your mind|have you ever|"
                    + "what do you think)|"
                    + "(?:oh yeah|yeah that['\\u2019]s|sometimes you|hmm+|welcome back|"
                    + "it['\\u2019]s (?:great|good) to|let me ask you)\\b|"
                    + "[\\p{L}]+,? you sound\\b)");

    private TranscriptSentences() {}

    static final class Row {
        final long startMs;
        final String text;

        Row(long startMs, String text) {
            this.startMs = startMs;
            this.text = text;
        }
    }

    static List<TranscriptCue> cues(List<Row> rows, List<TranscriptCue> source) {
        List<TranscriptCue> result = new ArrayList<>(rows.size());
        int nextCue = 0;
        long end = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            StringBuilder text = new StringBuilder(row.text);
            // The recognizer can place two boundaries at the same millisecond.
            while (i + 1 < rows.size() && rows.get(i + 1).startMs <= row.startMs) {
                text.append(' ').append(rows.get(++i).text);
            }
            long nextStart = i + 1 < rows.size() ? rows.get(i + 1).startMs : Long.MAX_VALUE;
            while (nextCue < source.size() && source.get(nextCue).startMs < nextStart) {
                end = Math.max(end, source.get(nextCue++).endMs);
            }
            result.add(cue(row.startMs, Math.max(row.startMs + 1, Math.min(nextStart, end)), text.toString()));
        }
        return result;
    }

    static TranscriptCue cue(long startMs, long endMs, String text) {
        TranscriptCue cue = new TranscriptCue(startMs, endMs, text);
        cue.sentence = true;
        return cue;
    }

    static boolean sameText(String left, String right) {
        return comparableText(left).equalsIgnoreCase(comparableText(right));
    }

    private static String comparableText(String text) {
        return WORD_GAP.matcher(text.trim()).replaceAll(" ").replaceFirst("[.!?。！？]+$", "");
    }

    static List<Row> rows(List<TranscriptCue> cues, String language) {
        List<Row> rows = new ArrayList<>();
        boolean english = language != null && "en".equals(Locale.forLanguageTag(language).getLanguage());
        List<Integer> offsets = new ArrayList<>();
        List<TranscriptCue> blockCues = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        for (TranscriptCue cue : cues) {
            String text = cue.sourceText.trim();
            if (text.isEmpty()) continue;
            if (!blockCues.isEmpty()
                    && cue.startMs - blockCues.get(blockCues.size() - 1).endMs >= SENTENCE_GAP_MS) {
                split(rows, block.toString(), offsets, blockCues, english);
                block.setLength(0);
                offsets.clear();
                blockCues.clear();
            }
            if (block.length() > 0) block.append(' ');
            offsets.add(block.length());
            blockCues.add(cue);
            block.append(text);
        }
        split(rows, block.toString(), offsets, blockCues, english);
        return rows;
    }

    private static void split(List<Row> rows, String text, List<Integer> offsets,
                              List<TranscriptCue> cues, boolean english) {
        Matcher punctuation = PUNCTUATION.matcher(text);
        int start = 0;
        while (punctuation.find()) {
            appendRow(rows, text, start, punctuation.end(), offsets, cues, false, false);
            start = punctuation.end();
        }
        splitUnpunctuated(rows, text, start, text.length(), offsets, cues, english);
    }

    private static void splitUnpunctuated(List<Row> rows, String text, int start, int end,
                                         List<Integer> offsets, List<TranscriptCue> cues, boolean english) {
        boolean question = false;
        if (english) {
            Matcher starters = ENGLISH_START.matcher(text).region(start, end);
            while (starters.find()) {
                appendBounded(rows, text, start, starters.start(), offsets, cues, true, question);
                start = starters.start();
                question = starters.start("question") >= 0;
            }
        }
        appendBounded(rows, text, start, end, offsets, cues, english, question);
    }

    private static void appendBounded(List<Row> rows, String text, int start, int end,
                                      List<Integer> offsets, List<TranscriptCue> cues,
                                      boolean english, boolean question) {
        Matcher gaps = WORD_GAP.matcher(text).region(start, end);
        int previousGap = -1;
        int cueGap = -1;
        while (gaps.find()) {
            if (gaps.start() <= start) {
                start = gaps.end();
                continue;
            }
            boolean overLimit = gaps.start() - start > MAX_UNPUNCTUATED_CHARS
                    || timeAt(gaps.start(), offsets, cues) - timeAt(start, offsets, cues) > MAX_UNPUNCTUATED_MS;
            if (overLimit) {
                int cut = cueGap > start ? cueGap : previousGap;
                if (cut <= start) cut = gaps.start();
                appendRow(rows, text, start, cut, offsets, cues, false, false);
                start = cut;
                while (start < end && Character.isWhitespace(text.charAt(start))) start++;
                cueGap = -1;
            }
            previousGap = gaps.start();
            if (gaps.start() - start >= MAX_UNPUNCTUATED_CHARS / 2
                    && java.util.Collections.binarySearch(offsets, gaps.end()) >= 0) {
                cueGap = gaps.start();
            }
        }
        if (start < end && previousGap > start && (end - start > MAX_UNPUNCTUATED_CHARS
                || timeAt(end - 1, offsets, cues) - timeAt(start, offsets, cues) > MAX_UNPUNCTUATED_MS)) {
            int cut = cueGap > start ? cueGap : previousGap;
            appendRow(rows, text, start, cut, offsets, cues, false, false);
            start = cut;
        }
        appendRow(rows, text, start, end, offsets, cues, english, question);
    }

    private static void appendRow(List<Row> rows, String text, int start, int end,
                                  List<Integer> offsets, List<TranscriptCue> cues,
                                  boolean english, boolean question) {
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        if (start == end) return;
        String sentence = text.substring(start, end).trim();
        if (english) {
            sentence = Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
            if (!PUNCTUATION.matcher(sentence).find()) sentence += question ? "?" : ".";
        }

        rows.add(new Row(timeAt(start, offsets, cues), sentence));
    }

    private static long timeAt(int offset, List<Integer> offsets, List<TranscriptCue> cues) {
        int cueIndex = java.util.Collections.binarySearch(offsets, offset);
        if (cueIndex < 0) cueIndex = -cueIndex - 2;
        TranscriptCue cue = cues.get(cueIndex);
        int withinCue = offset - offsets.get(cueIndex);
        if (cue.wordStarts != null) {
            long wordTime = -1;
            for (int i = 0; i < cue.wordStarts.length(); i++) {
                org.json.JSONArray word = cue.wordStarts.optJSONArray(i);
                if (word == null || word.optInt(0, Integer.MAX_VALUE) > withinCue) break;
                wordTime = word.optLong(1, wordTime);
            }
            if (wordTime >= 0) return wordTime;
        }
        long duration = Math.max(0, cue.endMs - cue.startMs);
        if (cueIndex + 1 < cues.size()) {
            duration = Math.min(duration, cues.get(cueIndex + 1).startMs - cue.startMs);
        }
        return cue.startMs + duration * withinCue / cue.sourceText.trim().length();
    }
}
