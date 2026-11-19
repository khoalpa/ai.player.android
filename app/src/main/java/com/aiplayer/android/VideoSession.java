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
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class VideoSession {
    private static final int MAX_SUBTITLE_BYTES = 4 * 1024 * 1024;
    static final class AudioTrack {
        final int index;
        final String label;
        final String mime;
        final String language;

        AudioTrack(int index, String label, String mime, String language) {
            this.index = index;
            this.label = label;
            this.mime = mime;
            this.language = language;
        }
    }

    private final Context context;
    private final Consumer<String> changed;
    private final OriginalAudioCaptionGenerator generator;
    private final OfflineModels models;
    private final TranscriptCache cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private TranslationClient translator;
    private volatile int revision;
    private Uri video;
    private String videoIdentity;
    String videoName = "Video";
    private Future<?> pendingFile;
    private ReadCancellation fileCancellation;
    private int videoRevision;
    private long transcriptVersion;
    private long recentSummaryVersion = -1;
    private RecentVideoStatus.Summary recentSummary;
    private String repairTarget;
    private String failedTarget;
    boolean translationModelUnavailable;
    enum Task {
        TRANSCRIPT("Đang tạo transcript…", "Transcript đã sẵn sàng"),
        TRANSLATION("Đang dịch…", "Bản dịch đã sẵn sàng");

        final String runningLabel;
        final String readyLabel;

        Task(String runningLabel, String readyLabel) {
            this.runningLabel = runningLabel;
            this.readyLabel = readyLabel;
        }
    }

    Task runningTask;
    Task finishedTask;
    private Task failedTask;
    private String translationTaskTarget;
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

    VideoSession(Context context, Consumer<String> changed, OfflineModels models) {
        this.context = context.getApplicationContext();
        this.changed = changed;
        this.models = models;
        generator = new OriginalAudioCaptionGenerator(context, models.vosk);
        cache = new TranscriptCache(context);
    }

    long transcriptCacheBytes(boolean clear) {
        return cache.cacheBytes(clear);
    }

    void removeCachedTranscript(String identity, String source, Runnable completed) {
        files.execute(() -> {
            cache.removeFor(identity, source);
            main.post(completed);
        });
    }

    void open(Uri uri, String language, PlaybackSessionStore.Snapshot restored) {
        repairTarget = null;
        failedTarget = null;
        translationModelUnavailable = false;
        cancel();
        failedTask = null;
        translationTaskTarget = null;
        video = uri;
        videoIdentity = restored == null ? null : restored.identity;
        videoName = restored == null ? "Video" : restored.name;
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
            repairTarget = restored.repairTarget;
            failedTask = restored.failedTask;
            failedTarget = restored.failedTarget;
        }
        timeline.rebuild();
        boolean loadCache = cues.isEmpty() && (restored == null || !restored.suppressCache);
        String identity = videoIdentity;
        pendingFile = files.submit(() -> scanTracks(uri, opened, request, language, loadCache, identity));
        changed.accept("Đang kiểm tra track âm thanh…");
    }

    private void scanTracks(Uri uri, int opened, int request, String language, boolean loadCache, String identity) {
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
                    result.add(new AudioTrack(i, label, mime, trackLanguage));
                }
            }
            status = result.isEmpty() ? "Video không có track âm thanh." : "Đã mở video.";
        } catch (Exception ex) {
            status = "Không đọc được track âm thanh: " + ex.getMessage();
        } finally {
            extractor.release();
        }
        if (Thread.currentThread().isInterrupted()) return;
        List<TranscriptCue> saved = loadCache ? cache.readLatest(TranscriptCache.keyForIdentity(identity, language), result)
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
        if (OfflineModels.pending(models.recognition)) {
            changed.accept("Đang quản lý model Vosk. Hãy đợi thao tác hoàn tất.");
            return;
        }
        cancel();
        int request = revision;
        busy = true;
        runningTask = Task.TRANSCRIPT;
        changed.accept("Đang chuẩn bị nhận dạng…");
        if (request != revision) return;
        Uri input = video;
        String key = TranscriptCache.keyForIdentity(videoIdentity, language);
        recognize(input, track, language, request, key);
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
                    int first = cues.size();
                    cues.addAll(batch);
                    for (int i = Math.max(1, first); i < cues.size(); i++) {
                        if (cues.get(i - 1).startMs > cues.get(i).startMs) {
                            cues.sort(Comparator.comparingLong(cue -> cue.startMs));
                            first = 0;
                            break;
                        }
                    }
                    timeline.extendFrom(first);
                    changed.accept(null);
                });
            }

            @Override
            public void onFinished(List<TranscriptCue> result, String status) {
                main.post(() -> {
                    if (isCancelled()) return;
                    if (result.isEmpty()) restorePreviousTranscript();
                    previousCues = null;
                    if (!result.isEmpty()) finishedTask = Task.TRANSCRIPT;
                    finishTranscript(request, result, language, status);
                    if (key != null && !result.isEmpty()) {
                        files.execute(() -> cache.write(key, track.index, result));
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
        if (request != revision) return;
        ReadCancellation cancellation = new ReadCancellation();
        fileCancellation = cancellation;
        pendingFile = files.submit(() -> {
            List<TranscriptCue> result = new ArrayList<>();
            String status;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Không mở được tệp.");
                cancellation.register(input);
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
            } finally {
                cancellation.clear();
            }
            List<TranscriptCue> parsed = result;
            String message = status;
            main.post(() -> finishTranscript(request, parsed, language, message));
        });
    }

    private void finishTranscript(int request, List<TranscriptCue> result, String language, String status) {
        if (request != revision) return;
        boolean attempted = runningTask == Task.TRANSCRIPT;
        busy = false;
        runningTask = null;
        if (attempted) {
            failedTask = result.isEmpty() ? Task.TRANSCRIPT : null;
            failedTarget = null;
            transcriptVersion++;
        }
        if (!result.isEmpty()) {
            transcriptVersion++;
            cues.clear();
            cues.addAll(result);
            cues.sort(Comparator.comparingLong(cue -> cue.startMs));
            timeline.rebuild();
            sourceLanguage = language;
            targetLanguage = null;
            repairTarget = null;
            if (!attempted && failedTask == Task.TRANSLATION) {
                failedTask = null;
                failedTarget = null;
            }
        }
        changed.accept(status);
    }

    long transcriptVersion() {
        return transcriptVersion;
    }

    Uri videoUri() { return video; }
    String identity() { return videoIdentity; }
    String translationRepairTarget() { return repairTarget; }
    Task failedTask() { return failedTask; }
    String failedTarget() { return failedTarget; }

    List<TranscriptCue> completedCues() {
        return previousCues == null ? cues : previousCues;
    }

    String completedSource() {
        return previousCues == null ? sourceLanguage : previousSourceLanguage;
    }

    String completedTarget() {
        return previousCues == null ? targetLanguage : previousTargetLanguage;
    }

    RecentVideoStatus recentStatus(String target) {
        if (runningTask == Task.TRANSCRIPT) {
            return new RecentVideoStatus("Đang tạo transcript…", false);
        }
        boolean sameTarget = target.equals(translationTaskTarget);
        if (runningTask == Task.TRANSLATION && sameTarget) {
            return new RecentVideoStatus("Có transcript · Đang dịch…", false);
        }
        if (recentSummaryVersion != transcriptVersion) {
            recentSummary = new RecentVideoStatus.Summary(completedCues(), completedTarget());
            recentSummaryVersion = transcriptVersion;
        }
        return recentSummary.statusFor(target, failedTask, failedTarget);
    }

    int missingTranslations() {
        if (targetLanguage == null) return 0;
        int missing = 0;
        for (TranscriptCue cue : cues) {
            if (!cue.hasTranslation()) missing++;
        }
        return missing;
    }

    boolean needsTranslationRepair() {
        return repairTarget != null && repairTarget.equals(targetLanguage) && missingTranslations() > 0;
    }

    void translate(String target, String configuredSource) {
        translate(target, configuredSource, null);
    }

    void repairTranslation(TranscriptCue cue, String text, String source) {
        if (busy || targetLanguage == null || !cues.contains(cue) || cue.hasTranslation()
                || text.trim().isEmpty()) return;
        if (OfflineModels.pending(models.translation)) {
            changed.accept("Đang quản lý model dịch. Hãy đợi thao tác hoàn tất.");
            return;
        }
        if (!text.equals(cue.sourceText)) {
            cue.sourceText = text;
            transcriptVersion++;
        }
        translate(targetLanguage, source, cue);
    }

    private void translate(String target, String configuredSource, TranscriptCue selected) {
        if (OfflineModels.pending(models.translation)) {
            changed.accept("Đang quản lý model dịch. Hãy đợi thao tác hoàn tất.");
            return;
        }
        cancel();
        int request = revision;
        busy = true;
        AppSettings settings = new AppSettings();
        settings.sourceLanguage = LanguageUtils.normalizeLanguage(configuredSource, "auto");
        if ("auto".equals(settings.sourceLanguage)) settings.sourceLanguage = sourceLanguage;
        settings.targetLanguage = target;

        int missingBefore = missingTranslations();
        int translatedBefore = RecentVideoStatus.translatedCount(cues, targetLanguage, target);
        boolean retry = target.equals(targetLanguage) && missingBefore > 0;
        translationModelUnavailable = false;
        TranslationBatch batch = new TranslationBatch(cues, targetLanguage, target, selected);
        List<TranscriptCue> input = batch.input;
        if (input.isEmpty()) {
            busy = false;
            changed.accept("Bản dịch đã đầy đủ.");
            return;
        }
        translator = new TranslationClient();
        runningTask = Task.TRANSLATION;
        translationTaskTarget = target;
        changed.accept("Đang chuẩn bị model dịch…");
        if (request != revision) return;
        translator.translateCues(settings, input, cues, new TranslationClient.Callback() {
            @Override
            public void onModelUnavailable() {
                if (request == revision) translationModelUnavailable = true;
            }

            @Override
            public void onTranslated(int index, String text) {
                if (request != revision || text == null || text.trim().isEmpty()) return;
                if (!target.equals(targetLanguage)) repairTarget = null;
                batch.accept(index, text);
                targetLanguage = target;
                transcriptVersion++;
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
                translator.close();
                translator = null;
                busy = false;
                runningTask = null;
                boolean complete = target.equals(targetLanguage) && missingTranslations() == 0;
                int translated = RecentVideoStatus.translatedCount(cues, targetLanguage, target);
                if (translated == translatedBefore && !complete) {
                    failedTask = Task.TRANSLATION;
                    failedTarget = target;
                } else if (complete || (failedTask == Task.TRANSLATION && target.equals(failedTarget))) {
                    failedTask = null;
                    failedTarget = null;
                }
                if (retry && missingTranslations() >= missingBefore) {
                    repairTarget = target;
                } else if (complete) {
                    repairTarget = null;
                }
                transcriptVersion++;
                if (count > 0 && complete) {
                    finishedTask = Task.TRANSLATION;
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
        if (fileCancellation != null) fileCancellation.cancel();
        if (pendingFile != null) pendingFile.cancel(true);
        generator.cancel();
        progressTotal = 0;
        progressCompleted = 0;
        busy = false;
        runningTask = null;
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
