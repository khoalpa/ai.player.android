package com.aiplayer.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

final class TranscriptExport {
    enum Content {
        ORIGINAL("transcript"), TRANSLATION("translation"), BILINGUAL("bilingual");

        final String suffix;

        Content(String suffix) {
            this.suffix = suffix;
        }
    }

    enum Format {
        TXT("txt", "text/plain"), SRT("srt", "application/x-subrip");

        final String extension;
        final String mimeType;

        Format(String extension, String mimeType) {
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }

    private TranscriptExport() {}

    static File directory(File cache) {
        return new File(cache, "transcript-exports");
    }

    static File stage(File cache, String videoName, List<TranscriptCue> cues,
                      Content content, Format format) throws IOException {
        File directory = directory(cache);
        Files.createDirectories(directory.toPath());
        removeExpiredExports(directory);
        File batch = Files.createTempDirectory(directory.toPath(), "export-").toFile();
        String stem = videoName == null ? "" : videoName.replaceFirst("\\.[^.]+$", "")
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (stem.isEmpty()) stem = "video";
        if (stem.codePointCount(0, stem.length()) > 50) {
            stem = stem.substring(0, stem.offsetByCodePoints(0, 50));
        }
        File file = new File(batch, stem + "_" + content.suffix + "." + format.extension);
        try {
            Files.write(file.toPath(), render(cues, content, format).getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException | RuntimeException error) {
            file.delete();
            batch.delete();
            throw error;
        }
    }

    private static void removeExpiredExports(File directory) {
        long cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(7);
        File[] batches = directory.listFiles(file -> file.isDirectory() && file.lastModified() < cutoff);
        if (batches == null) return;
        for (File batch : batches) {
            File[] files = batch.listFiles(File::isFile);
            if (files != null) {
                for (File file : files) file.delete();
            }
            batch.delete();
        }
    }

    static String render(List<TranscriptCue> cues, Content content, Format format) {
        StringBuilder result = new StringBuilder();
        int index = 0;
        for (TranscriptCue cue : cues) {
            String original = content == Content.TRANSLATION ? "" : clean(cue.sourceText);
            String translation = content == Content.ORIGINAL ? "" : clean(cue.targetText);
            if (original.isEmpty() && translation.isEmpty()) continue;
            if (format == Format.SRT) {
                long start = Math.max(0L, cue.startMs);
                long end = Math.max(start, cue.endMs);
                result.append(++index).append('\n')
                        .append(timestamp(start)).append(" --> ").append(timestamp(end)).append('\n');
            }
            if (!original.isEmpty()) result.append(original).append('\n');
            if (!translation.isEmpty()) result.append(translation).append('\n');
            result.append('\n');
        }
        return result.toString();
    }

    private static String clean(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .trim().replaceAll("\n(?:[\\t ]*\n)+", "\n");
    }

    private static String timestamp(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000);
    }
}
