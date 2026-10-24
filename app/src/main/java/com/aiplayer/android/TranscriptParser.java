package com.aiplayer.android;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TranscriptParser {
    private TranscriptParser() {
    }

    static List<TranscriptCue> parse(String content) {
        String normalized = content
                .replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        if (normalized.trim().startsWith("WEBVTT")) {
            normalized = normalized.replaceFirst("(?s)^WEBVTT.*?\\n\\n", "");
        }

        List<TranscriptCue> cues = parseTimed(normalized);
        if (!cues.isEmpty()) {
            return cues;
        }
        return parsePlainText(normalized);
    }

    private static List<TranscriptCue> parseTimed(String content) {
        List<TranscriptCue> cues = new ArrayList<>();
        String[] blocks = content.split("\\n\\s*\\n");
        for (String block : blocks) {
            String[] lines = block.trim().split("\\n");
            int timingLine = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    timingLine = i;
                    break;
                }
            }
            if (timingLine < 0) {
                continue;
            }

            String[] parts = lines[timingLine].split("-->");
            if (parts.length < 2) {
                continue;
            }
            long start = parseTime(parts[0]);
            long end = parseTime(parts[1]);
            if (start < 0 || end <= start) {
                continue;
            }

            StringBuilder text = new StringBuilder();
            for (int i = timingLine + 1; i < lines.length; i++) {
                String line = lines[i].replaceAll("<[^>]+>", "").trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(line);
            }
            if (text.length() > 0) {
                cues.add(new TranscriptCue(start, end, text.toString()));
            }
        }
        return cues;
    }

    private static List<TranscriptCue> parsePlainText(String content) {
        List<TranscriptCue> cues = new ArrayList<>();
        String[] chunks = content.trim().split("(?<=[.!?。！？])\\s+|\\n+");
        long start = 0;
        for (String chunk : chunks) {
            String text = chunk.trim();
            if (text.isEmpty()) {
                continue;
            }
            long duration = Math.max(2500, Math.min(9000, text.length() * 80L));
            cues.add(new TranscriptCue(start, start + duration, text));
            start += duration;
        }
        return cues;
    }

    private static long parseTime(String raw) {
        String value = raw.trim().replace(',', '.').split("\\s+")[0];
        String[] parts = value.split(":");
        try {
            double seconds;
            if (parts.length == 3) {
                seconds = Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Double.parseDouble(parts[2]);
            } else if (parts.length == 2) {
                seconds = Integer.parseInt(parts[0]) * 60
                        + Double.parseDouble(parts[1]);
            } else {
                seconds = Double.parseDouble(value);
            }
            return (long) (seconds * 1000.0);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    static String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
