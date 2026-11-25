package com.aiplayer.android;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

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
    private TranslationClient draftTranslator;
    private volatile int revision;
    private Uri video;
    private String videoIdentity;
    String videoName = "Video";
    private Future<?> pendingFile;
    private ReadCancellation fileCancellation;
    private int videoRevision;
    private long transcriptVersion;
    private boolean transcriptPreview;
    TranscriptReview transcriptReview;

    static final class TranscriptReview {
        final List<TranscriptCue> generated;
        final List<TranscriptCue> conflicts;
        final String language;
        final String origin;
        final boolean sameLanguage;

        TranscriptReview(List<TranscriptCue> generated, List<TranscriptCue> conflicts,
                         String language, String origin, boolean sameLanguage) {
            this.generated = generated;
            this.conflicts = conflicts;
            this.language = language;
            this.origin = origin;
            this.sameLanguage = sameLanguage;
        }
    }
    private long recentSummaryVersion = -1;
    private RecentVideoStatus.Summary recentSummary;
    private String failedTarget;
    private boolean contextualTranslation;
    private String translationGlossary = "";
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

    final java.util.Map<String, String> voices = new java.util.HashMap<>();
    final List<AudioTrack> tracks = new ArrayList<>();
    final List<TranscriptCue> cues = new ArrayList<>();
    final CueTimeline timeline = new CueTimeline(cues);
    static final String IMPORTED_SUBTITLES = "imported";
    private String transcriptOrigin;
    String sourceLanguage;
    String targetLanguage;
    boolean busy;
    int progressTotal;
    String contextStep;
    long contextStepStartedAt;
    int progressCompleted;
    private QwenRun lastQwenRun;

    static final class QwenRun {
        final String processor;
        final long elapsedMs;
        final int cues;

        QwenRun(String processor, long elapsedMs, int cues) {
            this.processor = processor;
            this.elapsedMs = elapsedMs;
            this.cues = cues;
        }
    }

    QwenRun lastQwenRun() {
        return lastQwenRun;
    }

    private void recordQwenRun(long startedAt, int count) {
        String processor = translator.processorLabel();
        if (!processor.isEmpty() && count > 0) {
            lastQwenRun = new QwenRun(processor, SystemClock.elapsedRealtime() - startedAt, count);
        }
    }

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
        voices.clear();
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
        transcriptOrigin = null;
        sourceLanguage = null;
        targetLanguage = null;
        contextualTranslation = false;
        translationGlossary = "";
        int request = revision;
        int opened = ++videoRevision;
        if (restored != null) {
            cues.addAll(restored.cues);
            transcriptOrigin = restored.transcriptOrigin;
            sourceLanguage = restored.source;
            targetLanguage = restored.target;
            failedTask = restored.failedTask;
            failedTarget = restored.failedTarget;
            contextualTranslation = restored.contextualTranslation;
            translationGlossary = restored.translationGlossary;
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
        TranscriptCache.Entry saved = loadCache ? cache.readLatest(identity, language, result) : null;
        String message = status;
        main.post(() -> {
            if (opened != videoRevision || video == null) return;
            tracks.clear();
            tracks.addAll(result);
            if (request == revision && saved != null) {
                finishTranscript(request, saved.cues, language, "Đã mở transcript đã lưu.", saved.origin);
            } else {
                changed.accept(request == revision ? message : null);
            }
        });
    }

    void generate(AudioTrack track, String language, String model) {
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
        String key = TranscriptCache.keyForIdentity(videoIdentity, model);
        recognize(input, track, language, model, request, key);
    }

    private void recognize(Uri input, AudioTrack track, String language, String model, int request, String key) {
        generator.generate(input, track.index, model, new OriginalAudioCaptionGenerator.Callback() {
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
                    if (isCancelled() || (!transcriptPreview && !cues.isEmpty())) return;
                    transcriptPreview = true;
                    int first = cues.size();
                    cues.addAll(batch);
                    timeline.extendFrom(first);
                    changed.accept(null);
                });
            }

            @Override
            public void onFinished(List<TranscriptCue> result, String status) {
                main.post(() -> {
                    if (isCancelled()) return;
                    if (!result.isEmpty()) finishedTask = Task.TRANSCRIPT;
                    String origin = VoskModelStore.modelName(model);
                    List<TranscriptCue> recognized = new ArrayList<>(result.size());
                    if (key != null) {
                        for (TranscriptCue cue : result) recognized.add(cue.copy());
                    }
                    if (finishTranscript(request, result, language, status, origin) && !recognized.isEmpty()) {
                        files.execute(() -> cache.write(key, track.index, recognized, origin));
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
                                "Tệp phụ đề vượt giới hạn 4 MiB.", null));
                        return;
                    }
                    content.write(buffer, 0, count);
                }
                result = TranscriptParser.parse(new String(content.toByteArray(), StandardCharsets.UTF_8));
                status = result.isEmpty() ? "Tệp không có phụ đề SRT/VTT hợp lệ."
                        : "Đã nhập phụ đề theo câu.";
            } catch (Exception ex) {
                status = "Không đọc được phụ đề: " + ex.getMessage();
            } finally {
                cancellation.clear();
            }
            List<TranscriptCue> parsed = result;
            String message = status;
            main.post(() -> finishTranscript(request, parsed, language, message, IMPORTED_SUBTITLES));
        });
    }

    private boolean finishTranscript(int request, List<TranscriptCue> result, String language, String status, String origin) {
        if (request != revision) return false;
        boolean attempted = runningTask == Task.TRANSCRIPT;
        clearTranscriptPreview();
        busy = false;
        runningTask = null;
        if (attempted) {
            failedTask = result.isEmpty() ? Task.TRANSCRIPT : null;
            failedTarget = null;
            transcriptVersion++;
        }
        if (!result.isEmpty()) {
            boolean sameLanguage = attempted && java.util.Objects.equals(language, sourceLanguage);
            List<TranscriptCue> sorted = new ArrayList<>(result);
            sorted.sort(Comparator.comparingLong(cue -> cue.startMs));
            if (sorted.stream().anyMatch(cue -> !cue.sentence)) {
                sorted = TranscriptSentences.cues(TranscriptSentences.rows(sorted, language), sorted);
            }
            List<TranscriptCue> conflicts = attempted ? transcriptConflicts(cues, sorted, sameLanguage)
                    : java.util.Collections.emptyList();
            if (!conflicts.isEmpty()) {
                finishedTask = null;
                transcriptReview = new TranscriptReview(sorted, conflicts, language, origin, sameLanguage);
                changed.accept("Có " + conflicts.size() + " câu đã sửa cần duyệt trước khi thay transcript.");
                return false;
            }
            applyTranscript(sorted, language, origin, sameLanguage);
            if (!attempted && failedTask == Task.TRANSLATION) {
                failedTask = null;
                failedTarget = null;
            }
        }
        String message = !result.isEmpty() && attempted
                ? "Đã tạo transcript " + cues.size() + " câu." : status;
        changed.accept(message);
        return !result.isEmpty();
    }

    static boolean retainTranscriptEdits(List<TranscriptCue> current, List<TranscriptCue> generated,
                                         boolean sameLanguage) {
        return transcriptConflicts(current, generated, sameLanguage).isEmpty();
    }

    private static List<TranscriptCue> transcriptConflicts(List<TranscriptCue> current, List<TranscriptCue> generated,
                                                            boolean sameLanguage) {
        List<TranscriptCue> conflicts = new ArrayList<>();
        int next = 0;
        for (TranscriptCue old : current) {
            boolean protectedEdit = old.sourceEdited
                    || (old.hasTranslation() && old.translationOrigin == TranscriptCue.TranslationOrigin.MANUAL);
            if (!sameLanguage) {
                if (protectedEdit) conflicts.add(old.copy());
                continue;
            }
            while (next < generated.size() && generated.get(next).endMs <= old.startMs) next++;
            boolean matched = false;
            for (int i = next; i < generated.size(); i++) {
                TranscriptCue fresh = generated.get(i);
                if (fresh.startMs >= old.endMs) break;
                if (Math.abs(fresh.startMs - old.startMs) > 500
                        || Math.abs(fresh.endMs - old.endMs) > 500
                        || !TranscriptSentences.sameText(fresh.sourceText, old.sourceText)) continue;
                if (old.sourceEdited) {
                    fresh.sourceText = old.sourceText;
                    fresh.sourceEdited = true;
                    fresh.wordStarts = null;
                }
                if (old.hasTranslation()) {
                    fresh.setTranslation(old.targetText, old.translationOrigin);
                    fresh.translationNeedsReview = old.translationNeedsReview;
                    fresh.translatedDirectly = old.translatedDirectly;
                    fresh.translationPending = old.translationPending;
                }
                matched = true;
                next = i + 1;
                break;
            }
            if (!matched && protectedEdit) conflicts.add(old.copy());
        }
        return conflicts;
    }

    void resolveTranscriptReview(TranscriptReview review, List<TranscriptCue> kept) {
        if (review != transcriptReview) return;
        transcriptReview = null;
        if (kept == null) {
            changed.accept("Đã giữ transcript hiện tại.");
            return;
        }
        List<TranscriptCue> merged = review.sameLanguage
                ? mergeReviewedTranscript(review.generated, kept) : review.generated;
        applyTranscript(merged, review.language, review.origin, review.sameLanguage);
        finishedTask = Task.TRANSCRIPT;
        changed.accept("Đã cập nhật transcript " + cues.size() + " câu.");
    }

    static List<TranscriptCue> mergeReviewedTranscript(List<TranscriptCue> generated, List<TranscriptCue> kept) {
        List<TranscriptCue> merged = new ArrayList<>(generated.size() + kept.size());
        for (TranscriptCue fresh : generated) {
            if (kept.stream().noneMatch(old -> overlaps(old, fresh))) merged.add(fresh);
        }
        merged.addAll(kept);
        merged.sort(Comparator.comparingLong(cue -> cue.startMs));
        return merged;
    }

    static boolean overlaps(TranscriptCue first, TranscriptCue second) {
        return first.startMs < second.endMs && second.startMs < first.endMs;
    }

    private void applyTranscript(List<TranscriptCue> updated, String language, String origin, boolean sameLanguage) {
        transcriptOrigin = origin;
        transcriptVersion++;
        cues.clear();
        cues.addAll(updated);
        timeline.rebuild();
        sourceLanguage = language;
        if (!sameLanguage) targetLanguage = null;
    }

    boolean hasTranscriptPreview() {
        return transcriptPreview;
    }

    private void clearTranscriptPreview() {
        if (!transcriptPreview) return;
        transcriptPreview = false;
        cues.clear();
        timeline.rebuild();
    }

    String transcriptOrigin() {
        return transcriptOrigin;
    }

    long transcriptVersion() {
        return transcriptVersion;
    }

    Uri videoUri() { return video; }
    String identity() { return videoIdentity; }
    boolean isContextTranslationRunning() {
        return translator != null && translator.usesContext();
    }

    Task failedTask() { return failedTask; }
    String failedTarget() { return failedTarget; }
    boolean contextualTranslation() { return contextualTranslation; }
    String translationGlossary() { return translationGlossary; }

    String retryTranslationTarget() {
        return failedTask == Task.TRANSLATION ? failedTarget : targetLanguage;
    }

    boolean hasTranslationToRetry() {
        return failedTask == Task.TRANSLATION || missingTranslations() > 0;
    }

    List<TranscriptCue> completedCues() {
        return transcriptPreview ? java.util.Collections.emptyList() : cues;
    }

    String completedSource() {
        return sourceLanguage;
    }

    String completedTarget() {
        return targetLanguage;
    }

    RecentVideoStatus recentStatus(String target) {
        if (runningTask == Task.TRANSCRIPT) {
            return new RecentVideoStatus("Đang tạo transcript…", PlayerAppearance.StatusIcon.WORKING);
        }
        boolean sameTarget = target.equals(translationTaskTarget);
        if (runningTask == Task.TRANSLATION && sameTarget) {
            return new RecentVideoStatus("Có transcript · Đang dịch…", PlayerAppearance.StatusIcon.WORKING);
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
            if (needsTranslation(cue)) missing++;
        }
        return missing;
    }

    boolean needsTranslation(TranscriptCue cue) {
        return !cue.hasTranslation() || (contextualTranslation && "vi".equals(targetLanguage)
                && cue.translationOrigin == TranscriptCue.TranslationOrigin.AUTOMATIC
                && (cue.translationPending || !VietnameseTranslationQuality.accepts(
                        cue.sourceText, cue.targetText, translationGlossary)));
    }

    void translate(String target, String configuredSource) {
        translate(target, configuredSource, null, null);
    }

    void retranslate(String target, String configuredSource, boolean replace) {
        translate(target, configuredSource, null, null, replace, null, null, null, null, 0);
    }

    void translateWithM2m(String target, String configuredSource, boolean replace) {
        translate(target, configuredSource, null, null, replace, null, null, null, null, 0, true);
    }

    void translateInContext(TranscriptCue selected, String editedSource, String glossary, boolean replace,
                            long playbackPositionMs, java.util.function.BiConsumer<List<String>, Consumer<Boolean>> preview) {
        if (busy || cues.isEmpty() || (selected != null
                && (!cues.contains(selected) || !"vi".equals(targetLanguage)))) return;
        if (!models.contextual.installed() || !ContextModelStore.supported()) return;
        translate("vi", sourceLanguage, selected, null, replace, models.contextual, glossary, preview, editedSource, playbackPositionMs);
    }

    void prioritizeContext(long positionMs) {
        if (translator != null) translator.prioritizeContext(positionMs);
    }

    boolean canEditTranslation(TranscriptCue cue) {
        return !busy && targetLanguage != null && cues.contains(cue);
    }

    void replaceSentences(List<TranscriptCue> edited) {
        if (busy) return;
        for (TranscriptCue cue : edited) {
            if (!cues.contains(cue)) cue.sourceEdited = true;
        }
        cues.clear();
        cues.addAll(edited);
        transcriptVersion++;
        timeline.rebuild();
        changed.accept("Đã lưu transcript.");
    }

    void applyEditedTranscript(PlaybackSessionStore.Snapshot updated) {
        cues.clear();
        for (TranscriptCue cue : updated.cues) cues.add(cue.copy());
        transcriptOrigin = updated.transcriptOrigin;
        sourceLanguage = updated.source;
        targetLanguage = updated.target;
        failedTask = updated.failedTask;
        failedTarget = updated.failedTarget;
        contextualTranslation = updated.contextualTranslation;
        translationGlossary = updated.translationGlossary;
        transcriptVersion++;
        timeline.rebuild();
        changed.accept("Đã lưu phụ đề.");
    }

    boolean saveTranslation(TranscriptCue cue, String source, String text, TranscriptCue.TranslationOrigin origin) {
        if (!canEditTranslation(cue) || text.trim().isEmpty()) return false;
        if (!source.trim().isEmpty() && !source.trim().equals(cue.sourceText)) {
            cue.sourceText = source.trim();
            cue.wordStarts = null;
            cue.sourceEdited = true;
        }
        cue.setTranslation(text.trim(), origin);
        transcriptVersion++;
        if (missingTranslations() == 0) {
            if (failedTask == Task.TRANSLATION && targetLanguage.equals(failedTarget)
                    && cues.stream().noneMatch(row -> row.translationPending)) {
                failedTask = null;
                failedTarget = null;
            }
            changed.accept("Bản dịch đã hoàn tất.");
        } else {
            changed.accept("Đã lưu bản dịch.");
        }
        return true;
    }

    void repairTranslation(TranscriptCue cue, String text, String source, boolean useM2m) {
        if (!canEditTranslation(cue) || text.trim().isEmpty()) return;
        if (!text.equals(cue.sourceText)) {
            cue.sourceText = text;
            cue.wordStarts = null;
            cue.sourceEdited = true;
            cue.syncTranslationAfterSourceEdit();
            transcriptVersion++;
        }
        translate(targetLanguage, source, cue, null, false, null, null, null, null, 0, useM2m);
    }

    private void translate(String target, String configuredSource, TranscriptCue selected, Runnable onFinished) {
        translate(target, configuredSource, selected, onFinished, false, null, null, null, null, 0);
    }

    private void translate(String target, String configuredSource, TranscriptCue selected,
                           Runnable onFinished, boolean retranslate, ContextModelStore contextModel,
                           String glossary, java.util.function.BiConsumer<List<String>, Consumer<Boolean>> preview,
                           String editedSource, long playbackPositionMs) {
        translate(target, configuredSource, selected, onFinished, retranslate, contextModel, glossary,
                preview, editedSource, playbackPositionMs, false);
    }

    private void translate(String target, String configuredSource, TranscriptCue selected,
                           Runnable onFinished, boolean retranslate, ContextModelStore contextModel,
                           String glossary, java.util.function.BiConsumer<List<String>, Consumer<Boolean>> preview,
                           String editedSource, long playbackPositionMs, boolean useM2m) {
        if (!useM2m && contextModel == null && OfflineModels.pending(models.translation)) {
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

        translationModelUnavailable = false;
        boolean inContext = contextModel != null;
        TranslationBatch batch = new TranslationBatch(cues, targetLanguage, target, selected, retranslate,
                cue -> target.equals(targetLanguage)
                        && cue.translationOrigin == TranscriptCue.TranslationOrigin.AUTOMATIC
                        && ((!cue.translatedDirectly
                                && (useM2m || inContext || TranslationClient.requiresDirect(settings.sourceLanguage, target)
                                        || "auto".equals(settings.sourceLanguage)))
                                || (inContext && !VietnameseTranslationQuality.accepts(cue.sourceText, cue.targetText, glossary))));
        List<TranscriptCue> input = batch.input;
        if (inContext && selected == null) {
            if (targetLanguage != null && !target.equals(targetLanguage)
                    && cues.stream().anyMatch(cue -> cue.translationOrigin == TranscriptCue.TranslationOrigin.MANUAL)) {
                busy = false;
                changed.accept("Bản dịch hiện có câu sửa tay thuộc ngôn ngữ khác. Hãy chọn video khác hoặc dùng ML Kit để đổi ngôn ngữ.");
                return;
            }
            input.removeIf(cue -> cue.translationOrigin == TranscriptCue.TranslationOrigin.MANUAL
                    || (target.equals(targetLanguage) && cue.translationOrigin == TranscriptCue.TranslationOrigin.ORIGINAL));
        }
        if (!inContext && selected == null && target.equals(targetLanguage)) {
            input.removeIf(cue -> cue.translationOrigin == TranscriptCue.TranslationOrigin.MANUAL
                    || cue.translationOrigin == TranscriptCue.TranslationOrigin.ORIGINAL);
        }
        if (input.isEmpty()) {
            busy = false;
            changed.accept("Bản dịch đã đầy đủ.");
            return;
        }
        if (selected == null) {
            if (!target.equals(retryTranslationTarget())) {
                for (TranscriptCue cue : cues) cue.translationPending = false;
            }
            for (TranscriptCue cue : input) cue.translationPending = true;
            contextualTranslation = inContext;
            if (inContext) translationGlossary = glossary;
            failedTask = Task.TRANSLATION;
            failedTarget = target;
            transcriptVersion++;
        }
        ContextTranslationClient contextual = contextModel == null ? null
                : new ContextTranslationClient(contextModel, glossary, settings.qwenProcessor);
        translator = new TranslationClient(contextual, models.contextual);
        if (useM2m) translator.useM2m(models.m2m);
        long startedAt = SystemClock.elapsedRealtime();
        runningTask = Task.TRANSLATION;
        translationTaskTarget = target;
        changed.accept("Đang chuẩn bị model dịch…");
        if (request != revision) return;
        TranslationClient.Callback callback = new TranslationClient.Callback() {
            @Override
            public void onModelUnavailable() {
                if (request == revision) translationModelUnavailable = true;
            }

            @Override
            public void onTranslated(int index, String text) {
                if (request != revision || text == null || text.trim().isEmpty()) return;
                batch.accept(index, text);
                input.get(index).translatedDirectly = true;
                targetLanguage = target;
                transcriptVersion++;
            }

            @Override
            public void onProgress(int completed, int total, int cached, int skipped) {
                if (request != revision) return;
                progressCompleted = completed;
                progressTotal = total;
                String label = inContext ? "Hiệu đính · Đã xử lý " + completed + "/" + total + " câu"
                        : "Đang dịch " + completed + "/" + total;
                changed.accept(label + translator.processorStatus());
            }

            @Override
            public void onFinished(int count, String status) {
                if (request != revision) return;
                contextStep = null;
                if (preview == null) recordQwenRun(startedAt, count);
                closeDraftTranslator();
                translator.close();
                translator = null;
                busy = false;
                runningTask = null;
                boolean complete = target.equals(targetLanguage) && missingTranslations() == 0;
                boolean pending = cues.stream().anyMatch(cue -> cue.translationPending);
                if (selected == null && count < input.size()) {
                    if (inContext) {
                        if (count > 0) status += " Đã xử lý " + count + "/" + input.size() + " câu.";
                    } else {
                        status = "Dịch bị gián đoạn · Đã dịch " + count + "/" + input.size()
                                + " câu trong lần này. " + status;
                    }
                } else if (complete && !pending && target.equals(failedTarget)) {
                    failedTask = null;
                    failedTarget = null;
                }
                transcriptVersion++;
                if (count == input.size() && complete && !pending) {
                    finishedTask = Task.TRANSLATION;
                }
                changed.accept(status);
                if (onFinished != null) onFinished.run();
            }
        };
        if (contextual == null) {
            translator.translateCues(settings, input, cues, callback);
            return;
        }
        java.util.Map<Integer, String> drafts = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.CountDownLatch draftReady = new java.util.concurrent.CountDownLatch(1);
        if (selected == null) {
            if (target.equals(targetLanguage)) {
                for (int i = 0; i < input.size(); i++) {
                    TranscriptCue cue = input.get(i);
                    if (cue.translatedDirectly && !cue.translationNeedsReview && VietnameseTranslationQuality.accepts(
                            cue.sourceText, cue.targetText, glossary)) {
                        drafts.put(i, cue.targetText);
                    }
                }
            }
            if ("en".equals(settings.sourceLanguage) && !OfflineModels.pending(models.translation)) {
                startDraftTranslation(settings, batch, request, playbackPositionMs, drafts, draftReady);
            } else {
                draftReady.countDown();
            }
        } else {
            draftReady.countDown();
        }
        List<TranscriptCue> contextCues = cues;
        List<TranscriptCue> contextInput = input;
        if (selected != null && editedSource != null && !editedSource.equals(selected.sourceText)) {
            TranscriptCue draft = selected.copy();
            draft.sourceText = editedSource;
            contextCues = new ArrayList<>(cues);
            contextCues.set(cues.indexOf(selected), draft);
            contextInput = java.util.Collections.singletonList(draft);
        }
        long refinementStartedAt = SystemClock.elapsedRealtime();
        int[] refined = {0};
        boolean[] refining = {false};
        List<String> appliedRefinements = new ArrayList<>(java.util.Collections.nCopies(input.size(), null));
        contextual.prioritize(playbackPositionMs);
        contextual.translate(contextCues, contextInput, retranslate || selected != null, () -> main.post(() -> {
            if (request != revision) return;
            showContextStep("Đang nạp mô hình");
        }), (completed, total) -> main.post(() -> {
            if (request != revision) return;
            showContextStep("Ngữ cảnh · Đã phân tích " + completed + "/" + total + " nhóm" + contextual.processorStatus());
        }), () -> main.post(() -> {
            if (request != revision) return;
            refining[0] = true;
            contextStep = null;
            callback.onProgress(refined[0], input.size(), 0, 0);
        }), drafts, draftReady, results -> main.post(() -> {
            if (request != revision) return;
            List<String> applied = appliedRefinements;
            int count = 0;
            for (int i = 0; i < results.size(); i++) {
                String text = results.get(i);
                if (text == null) continue;
                count++;
                if (preview == null && !text.equals(applied.get(i))) callback.onTranslated(i, text);
                applied.set(i, text);
            }
            if (count == refined[0]) return;
            if (refined[0] == 0) {
                android.util.Log.i("ContextTranslation", "first_refined elapsed_ms="
                        + (SystemClock.elapsedRealtime() - refinementStartedAt));
            }
            refined[0] = count;
            if (refining[0]) callback.onProgress(count, input.size(), 0, 0);
        }))
                .addOnSuccessListener(results -> {
                    if (request != revision) return;
                    android.util.Log.i("ContextTranslation", "refinement_total elapsed_ms="
                            + (SystemClock.elapsedRealtime() - refinementStartedAt));
                    if (results.size() == input.size() && results.contains(null)) {
                        callback.onFinished(refined[0], "Còn " + (input.size() - refined[0])
                                + " câu cần kiểm tra. Có thể chọn Dịch tiếp.");
                        return;
                    }
                    if (results.size() != input.size()) {
                        callback.onFinished(0, "Bản dịch theo ngữ cảnh chưa đủ số câu.");
                        return;
                    }
                    if (preview == null) {
                        for (int i = 0; i < results.size(); i++) {
                            if (!results.get(i).equals(appliedRefinements.get(i))) callback.onTranslated(i, results.get(i));
                        }
                        callback.onFinished(results.size(), "Đã lưu bản dịch theo ngữ cảnh.");
                        contextual.clearCompleted();
                        return;
                    }
                    recordQwenRun(startedAt, results.size());
                    preview.accept(results, apply -> {
                        if (request != revision || !busy) return;
                        contextual.clearCompleted();
                        if (!apply) {
                            cancel();
                            changed.accept("Đã bỏ bản dịch theo ngữ cảnh.");
                            return;
                        }
                        if (editedSource != null && !editedSource.equals(selected.sourceText)) {
                            selected.sourceText = editedSource;
                            selected.wordStarts = null;
                            selected.sourceEdited = true;
                        }
                        for (int i = 0; i < results.size(); i++) callback.onTranslated(i, results.get(i));
                        callback.onFinished(results.size(), "Đã lưu bản dịch theo ngữ cảnh.");
                    });
                })
                .addOnFailureListener(error -> callback.onFinished(refined[0], error instanceof java.io.IOException
                        ? error.getMessage() : "Không thể xử lý bản dịch theo ngữ cảnh."));
    }

    private void startDraftTranslation(AppSettings settings, TranslationBatch batch, int request,
                                       long playbackPositionMs, java.util.Map<Integer, String> drafts,
                                       java.util.concurrent.CountDownLatch draftReady) {
        List<Integer> order = new ArrayList<>();
        int first = 0;
        while (first < batch.input.size() && batch.input.get(first).endMs <= playbackPositionMs) first++;
        for (int offset = 0; offset < batch.input.size(); offset++) {
            int index = (first + offset) % batch.input.size();
            if (!drafts.containsKey(index)) {
                order.add(index);
            }
        }
        if (order.isEmpty()) {
            draftReady.countDown();
            return;
        }
        List<TranscriptCue> draftInput = new ArrayList<>(order.size());
        for (int index : order) draftInput.add(batch.input.get(index));
        long started = SystemClock.elapsedRealtime();
        draftTranslator = new TranslationClient(null, models.contextual);
        draftTranslator.translateCues(settings, draftInput, cues, new TranslationClient.Callback() {
            private boolean published;

            @Override public void onTranslated(int index, String text) {
                if (request != revision || !busy) return;
                int targetIndex = order.get(index);
                if (!VietnameseTranslationQuality.accepts(
                        batch.input.get(targetIndex).sourceText, text, translationGlossary)) return;
                drafts.put(targetIndex, text);
                if (batch.acceptDraft(targetIndex, text, settings.targetLanguage.equals(targetLanguage))) {
                    batch.input.get(targetIndex).translatedDirectly = true;
                    targetLanguage = settings.targetLanguage;
                    transcriptVersion++;
                }
                if (!published) {
                    published = true;
                    android.util.Log.i("ContextTranslation", "first_draft elapsed_ms="
                            + (SystemClock.elapsedRealtime() - started));
                    publishDraftProgress(drafts.size(), batch.input.size());
                }
            }

            @Override public void onProgress(int completed, int total, int cached, int skipped) {
                if (request == revision && busy && published) {
                    publishDraftProgress(drafts.size(), batch.input.size());
                }
            }

            @Override public void onFinished(int count, String status) {
                draftReady.countDown();
                if (request == revision) closeDraftTranslator();
            }
        });
    }

    private void closeDraftTranslator() {
        if (draftTranslator == null) return;
        draftTranslator.close();
        draftTranslator = null;
    }

    private void publishDraftProgress(int drafted, int total) {
        String label = "Bản nháp " + drafted + "/" + total + " câu";
        changed.accept(contextStep != null ? contextStep + " · " + label
                : "Hiệu đính · Đã xử lý " + progressCompleted + "/" + total + " câu · " + label);
    }

    private void showContextStep(String label) {
        contextStep = label;
        contextStepStartedAt = SystemClock.elapsedRealtime();
        changed.accept(label);
    }

    void cancel() {
        revision++;
        transcriptReview = null;
        clearTranscriptPreview();
        closeDraftTranslator();
        contextStep = null;
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
