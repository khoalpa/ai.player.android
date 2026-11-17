package com.aiplayer.android;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class VideoSession {
    private static final int MAX_SUBTITLE_BYTES = 4 * 1024 * 1024;
    static final class AudioTrack {
        final int index;
        final String label;

        AudioTrack(int index, String label) {
            this.index = index;
            this.label = label;
        }
    }

    private final Context context;
    private final Consumer<String> changed;
    private final OriginalAudioCaptionGenerator generator;
    private final TranscriptCache cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private TranslationClient translator;
    private volatile int revision;
    private Uri video;
    private int videoRevision;
    private long transcriptVersion;
    private List<TranscriptCue> previousCues;
    private String previousSourceLanguage;
    private String previousTargetLanguage;

    final List<AudioTrack> tracks = new ArrayList<>();
    final List<TranscriptCue> cues = new ArrayList<>();
    final CueTimeline timeline = new CueTimeline(cues);
    String sourceLanguage;
    String targetLanguage;
    boolean busy;
    int progressTotal;
    int progressCompleted;

    VideoSession(Context context, Consumer<String> changed) {
        this.context = context.getApplicationContext();
        this.changed = changed;
        generator = new OriginalAudioCaptionGenerator(context);
        cache = new TranscriptCache(context);
    }

    void open(Uri uri, String language, PlaybackSessionStore.Snapshot restored) {
        cancel();
        video = uri;
        transcriptVersion++;
        tracks.clear();
        cues.clear();
        sourceLanguage = null;
        targetLanguage = null;
        int request = revision;
        int opened = ++videoRevision;
        if (restored != null) {
            cues.addAll(restored.cues);
            sourceLanguage = restored.source;
            targetLanguage = restored.target;
        }
        timeline.rebuild();
        boolean loadCache = cues.isEmpty();
        files.execute(() -> scanTracks(uri, opened, request, language, loadCache));
        changed.accept("Đang kiểm tra track âm thanh…");
    }

    private void scanTracks(Uri uri, int opened, int request, String language, boolean loadCache) {
        List<AudioTrack> result = new ArrayList<>();
        MediaExtractor extractor = new MediaExtractor();
        String status;
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    String trackLanguage = format.getString(MediaFormat.KEY_LANGUAGE);
                    String label = "Track " + (result.size() + 1) + " · " + mime;
                    if (trackLanguage != null && !trackLanguage.isEmpty()) {
                        label += " · " + trackLanguage;
                    }
                    result.add(new AudioTrack(i, label));
                }
            }
            status = result.isEmpty() ? "Video không có track âm thanh." : "Đã mở video.";
        } catch (Exception ex) {
            status = "Không đọc được track âm thanh: " + ex.getMessage();
        } finally {
            extractor.release();
        }
        List<TranscriptCue> saved = loadCache ? cache.readLatest(cache.videoKey(uri, language), result)
                : new ArrayList<>();
        String message = status;
        main.post(() -> {
            if (opened != videoRevision || video == null) return;
            tracks.clear();
            tracks.addAll(result);
            if (request == revision && !saved.isEmpty()) {
                finishTranscript(request, saved, language, "Đã mở transcript đã lưu.");
            } else {
                changed.accept(request == revision ? message : null);
            }
        });
    }

    void generate(AudioTrack track, String language) {
        cancel();
        int request = revision;
        busy = true;
        changed.accept("Đang chuẩn bị nhận dạng…");
        Uri input = video;
        files.execute(() -> {
            String key = cache.videoKey(input, language);
            main.post(() -> {
                if (request == revision) recognize(input, track, language, request, key);
            });
        });
    }

    private void recognize(Uri input, AudioTrack track, String language, int request, String key) {
        generator.generate(input, track.index, language, new OriginalAudioCaptionGenerator.Callback() {
            @Override
            public boolean isCancelled() {
                return request != revision;
            }

            @Override
            public void onStatus(String status) {
                main.post(() -> {
                    if (!isCancelled()) changed.accept(status);
                });
            }

            @Override
            public void onCues(List<TranscriptCue> batch) {
                main.post(() -> {
                    if (isCancelled()) return;
                    if (previousCues == null) {
                        previousCues = new ArrayList<>(cues);
                        previousSourceLanguage = sourceLanguage;
                        previousTargetLanguage = targetLanguage;
                        cues.clear();
                        sourceLanguage = language;
                        targetLanguage = null;
                    }
                    cues.addAll(batch);
                    cues.sort(Comparator.comparingLong(cue -> cue.startMs));
                    timeline.rebuild();
                    changed.accept(null);
                });
            }

            @Override
            public void onFinished(List<TranscriptCue> result, String status) {
                main.post(() -> {
                    if (isCancelled()) return;
                    if (result.isEmpty()) restorePreviousTranscript();
                    previousCues = null;
                    finishTranscript(request, result, language, status);
                    if (key != null && !result.isEmpty()) {
                        files.execute(() -> {
                            if (key.equals(cache.videoKey(input, language))) cache.write(key, track.index, result);
                        });
                    }
                });
            }
        });
    }

    void load(Uri uri, String language) {
        cancel();
        int request = revision;
        busy = true;
        changed.accept("Đang đọc phụ đề…");
        files.execute(() -> {
            List<TranscriptCue> result = new ArrayList<>();
            String status;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Không mở được tệp.");
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (request != revision) return;
                    if (count > MAX_SUBTITLE_BYTES - content.size()) {
                        main.post(() -> finishTranscript(request, new ArrayList<>(), language,
                                "Tệp phụ đề vượt giới hạn 4 MiB."));
                        return;
                    }
                    content.write(buffer, 0, count);
                }
                result = TranscriptParser.parse(new String(content.toByteArray(), StandardCharsets.UTF_8));
                status = result.isEmpty() ? "Tệp không có phụ đề SRT/VTT hợp lệ."
                        : "Đã nhập " + result.size() + " đoạn phụ đề.";
            } catch (Exception ex) {
                status = "Không đọc được phụ đề: " + ex.getMessage();
            }
            List<TranscriptCue> parsed = result;
            String message = status;
            main.post(() -> finishTranscript(request, parsed, language, message));
        });
    }

    private void finishTranscript(int request, List<TranscriptCue> result, String language, String status) {
        if (request != revision) return;
        busy = false;
        if (!result.isEmpty()) {
            transcriptVersion++;
            cues.clear();
            cues.addAll(result);
            cues.sort(Comparator.comparingLong(cue -> cue.startMs));
            timeline.rebuild();
            sourceLanguage = language;
            targetLanguage = null;
        }
        changed.accept(status);
    }

    long transcriptVersion() {
        return transcriptVersion;
    }

    List<TranscriptCue> completedCues() {
        return previousCues == null ? cues : previousCues;
    }

    String completedSource() {
        return previousCues == null ? sourceLanguage : previousSourceLanguage;
    }

    String completedTarget() {
        return previousCues == null ? targetLanguage : previousTargetLanguage;
    }

    int missingTranslations() {
        if (targetLanguage == null) return 0;
        int missing = 0;
        for (TranscriptCue cue : cues) {
            if (!cue.hasTranslation()) missing++;
        }
        return missing;
    }

    void translate(String target) {
        cancel();
        int request = revision;
        busy = true;
        AppSettings settings = new AppSettings();
        settings.sourceLanguage = sourceLanguage;
        settings.targetLanguage = target;

        boolean retry = target.equals(targetLanguage);
        List<TranscriptCue> input = new ArrayList<>();
        for (TranscriptCue cue : cues) {
            if (!retry || !cue.hasTranslation()) input.add(cue);
        }
        if (input.isEmpty()) {
            busy = false;
            changed.accept("Bản dịch đã đầy đủ.");
            return;
        }
        String[] translations = new String[input.size()];
        translator = new TranslationClient();
        changed.accept("Đang chuẩn bị model dịch…");
        translator.translateCues(settings, input, new TranslationClient.Callback() {
            @Override
            public void onTranslated(int index, String text) {
                if (request == revision) translations[index] = text;
            }

            @Override
            public void onProgress(int completed, int total, int cached, int skipped) {
                if (request != revision) return;
                progressCompleted = completed;
                progressTotal = total;
                changed.accept("Đang dịch " + completed + "/" + total);
            }

            @Override
            public void onFinished(int count, String status) {
                if (request != revision) return;
                busy = false;
                if (count > 0) {
                    transcriptVersion++;
                    for (int i = 0; i < input.size(); i++) {
                        input.get(i).targetText = translations[i];
                    }
                    targetLanguage = target;
                }
                changed.accept(status);
            }
        });
    }

    private void restorePreviousTranscript() {
        if (previousCues == null) return;
        cues.clear();
        cues.addAll(previousCues);
        timeline.rebuild();
        sourceLanguage = previousSourceLanguage;
        targetLanguage = previousTargetLanguage;
        previousCues = null;
    }

    void cancel() {
        restorePreviousTranscript();
        revision++;
        progressTotal = 0;
        progressCompleted = 0;
        busy = false;
        if (translator != null) {
            translator.close();
            translator = null;
        }
    }

    void close() {
        cancel();
        video = null;
        generator.close();
        files.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
