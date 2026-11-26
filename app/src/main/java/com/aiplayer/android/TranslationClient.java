package com.aiplayer.android;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.Supplier;

final class TranslationClient {
    private static final String TAG = "SentenceTranslation";
    private static final long SLOW_SENTENCE_MS = 10_000;
    private static final long M2M_RECOVERY_MS = 10_000;
    private static final long MODEL_SETUP_MS = 180_000;

    private enum Engine {
        ML_KIT("ML Kit", 30_000),
        M2M("M2M100", 60_000),
        QWEN("Qwen", 120_000);

        final String label;
        final long sentenceTimeoutMs;

        Engine(String label, long sentenceTimeoutMs) {
            this.label = label;
            this.sentenceTimeoutMs = sentenceTimeoutMs;
        }
    }
    private static final int MAX_TRANSLATORS = 3;
    private static final int MAX_TRANSLATION_CACHE_ENTRIES = 2000;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern URL_LIKE = Pattern.compile("(?i)^(https?://|www\\.|[\\w.-]+\\.[a-z]{2,})(\\S*)$");

    interface Callback {
        void onTranslated(int index, String translatedText);

        default void onModelUnavailable() {}

        default void onSentence(String engine, int index, int total, long elapsedMs) {}

        default void onProgress(int completed, int total, int cached, int skipped) {
        }

        void onFinished(int translatedCount, String status);
    }

    private final LanguageIdentifier languageIdentifier;
    private final Map<String, Translator> translators = new LinkedHashMap<String, Translator>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Translator> eldest) {
            if (size() <= MAX_TRANSLATORS) {
                return false;
            }
            eldest.getValue().close();
            return true;
        }
    };
    private final Map<String, String> translationCache = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_TRANSLATION_CACHE_ENTRIES;
        }
    };
    private boolean closed;
    private boolean usesEnglishPivot;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable timeout;
    private Runnable slowNotice;
    private final ContextTranslationClient contextual;
    private M2mTranslation m2mEngine;
    private M2mModelStore m2mModel;
    private boolean requireInstalledMlKit;

    void useInstalledMlKit() {
        requireInstalledMlKit = true;
    }

    TranslationClient(ContextTranslationClient contextual) {
        this.contextual = contextual;
        languageIdentifier = contextual == null ? LanguageIdentification.getClient() : null;
    }

    void prioritizeContext(long positionMs) {
        if (contextual != null) contextual.prioritize(positionMs);
    }

    boolean usesContext() {
        return contextual != null;
    }

    String processorStatus() {
        String label = processorLabel();
        return label.isEmpty() ? "" : " · " + label;
    }

    String processorLabel() {
        return contextual == null ? "" : contextual.processorLabel();
    }

    void useM2m(M2mModelStore model) {
        m2mModel = model;
    }

    void translateCues(AppSettings settings, List<TranscriptCue> cues,
            List<TranscriptCue> languageSample, Callback callback) {
        if (cues.isEmpty()) {
            callback.onFinished(0, "Chưa có transcript để dịch.");
            return;
        }

        bounded(() -> resolveSourceLanguage(settings, languageSample), 30_000)
                .addOnSuccessListener(sourceLanguage -> {
                    if (closed) {
                        return;
                    }
                    String targetLanguage = LanguageUtils.normalizeLanguage(settings.targetLanguage, "vi");
                    if (sourceLanguage == null || TranslateLanguage.fromLanguageTag(sourceLanguage) == null) {
                        callback.onFinished(0, "Không nhận diện được ngôn ngữ nguồn. Hãy nhập mã nguồn như ja, en, ko, zh.");
                        return;
                    }
                    if (sourceLanguage.equals(targetLanguage)) {
                        int accepted = 0;
                        for (int i = 0; i < cues.size(); i++) {
                            String source = cues.get(i).sourceText;
                            if (!normalizeCueText(source).isEmpty()) {
                                callback.onTranslated(i, source);
                                accepted++;
                            }
                        }
                        callback.onProgress(cues.size(), cues.size(), 0, accepted);
                        callback.onFinished(accepted, "Nguồn và đích cùng ngôn ngữ, đã giữ nguyên transcript.");
                        return;
                    }
                    String languagePair = sourceLanguage + ">" + targetLanguage;
                    if (m2mModel != null) {
                        if (!ContextModelStore.supported() || !m2mModel.installed()) {
                            callback.onModelUnavailable();
                            callback.onFinished(0, "Hãy tải M2M100 INT8 trong Quản lý model trên Android ARM64.");
                            return;
                        }
                        m2mEngine = new M2mTranslation(m2mModel);
                        bounded(m2mEngine::prepare, MODEL_SETUP_MS).addOnSuccessListener(ignored -> {
                            if (closed) return;
                            translateNext(text -> m2mEngine.translate(sourceLanguage, targetLanguage, text),
                                    languagePair, cues, callback, new TranslationState(cues.size(), Engine.M2M));
                        }).addOnFailureListener(error -> {
                            if (!closed) callback.onFinished(0, "Không mở được M2M100 INT8. " + error.getMessage());
                        });
                        return;
                    }
                    usesEnglishPivot = usesEnglishPivot(sourceLanguage, targetLanguage);
                    Translator translator = translatorFor(sourceLanguage, targetLanguage);
                    DownloadConditions conditions = new DownloadConditions.Builder().build();
                    bounded(() -> requireInstalledMlKit
                            ? requireMlKitLanguages(sourceLanguage, targetLanguage)
                            : translator.downloadModelIfNeeded(conditions), MODEL_SETUP_MS)
                            .addOnSuccessListener(v -> {
                                if (closed) return;
                                translateNext(translator::translate, languagePair, cues, callback,
                                        new TranslationState(cues.size(), Engine.ML_KIT));
                            })
                            .addOnFailureListener(ex -> {
                                if (closed) return;
                                if (ex instanceof java.util.concurrent.TimeoutException) {
                                    callback.onFinished(0, "Quá thời gian chuẩn bị model ML Kit.");
                                    return;
                                }
                                callback.onModelUnavailable();
                                callback.onFinished(0,
                                        "Chưa có model dịch offline " + sourceLanguage + " -> " + targetLanguage
                                                + ". Hãy kết nối mạng một lần để tải model, sau đó có thể dịch offline.");
                            });
                })
                .addOnFailureListener(ex -> {
                    if (!closed) callback.onFinished(0, "Không nhận diện được ngôn ngữ nguồn: " + ex.getMessage());
                });
    }

    private Task<Void> requireMlKitLanguages(String source, String target) {
        return com.google.mlkit.common.model.RemoteModelManager.getInstance()
                .getDownloadedModels(com.google.mlkit.nl.translate.TranslateRemoteModel.class)
                .continueWith(task -> {
                    if (!task.isSuccessful()) throw task.getException();
                    java.util.Set<String> installed = new java.util.HashSet<>();
                    for (com.google.mlkit.nl.translate.TranslateRemoteModel model : task.getResult()) {
                        installed.add(model.getLanguage());
                    }
                    if ((!TranslateLanguage.ENGLISH.equals(source) && !installed.contains(source))
                            || (!TranslateLanguage.ENGLISH.equals(target) && !installed.contains(target))) {
                        throw new java.io.IOException("Thiếu gói ngôn ngữ ML Kit.");
                    }
                    return null;
                });
    }

    // All listeners and timers run on the main looper, with one pending operation per client.
    private <T> Task<T> bounded(Supplier<Task<T>> operation, long timeoutMs) {
        com.google.android.gms.tasks.TaskCompletionSource<T> result =
                new com.google.android.gms.tasks.TaskCompletionSource<>();
        Runnable deadline = () -> {
            clearTimers();
            result.trySetException(new java.util.concurrent.TimeoutException("Đã quá thời gian chờ."));
        };
        timeout = deadline;
        main.postDelayed(deadline, timeoutMs);
        try {
            operation.get().addOnCompleteListener(task -> {
                if (closed || timeout != deadline) return;
                clearTimers();
                if (task.isSuccessful()) {
                    result.trySetResult(task.getResult());
                } else {
                    Exception error = task.getException();
                    result.trySetException(error == null
                            ? new java.util.concurrent.CancellationException("Bộ dịch đã hủy xử lý.") : error);
                }
            });
        } catch (RuntimeException error) {
            clearTimers();
            result.trySetException(error);
        }
        return result.getTask();
    }

    private void clearTimers() {
        if (timeout != null) main.removeCallbacks(timeout);
        if (slowNotice != null) main.removeCallbacks(slowNotice);
        timeout = null;
        slowNotice = null;
    }

    void close() {
        closed = true;
        clearTimers();
        if (contextual != null) contextual.close();
        if (m2mEngine != null) m2mEngine.close();
        if (languageIdentifier != null) languageIdentifier.close();
        for (Translator translator : translators.values()) {
            translator.close();
        }
        translators.clear();
        translationCache.clear();
    }

    boolean usesEnglishPivot() {
        return usesEnglishPivot;
    }

    static boolean usesEnglishPivot(String source, String target) {
        return source != null && !"auto".equals(source) && !source.equals(target)
                && !TranslateLanguage.ENGLISH.equals(source) && !TranslateLanguage.ENGLISH.equals(target);
    }

    private Task<String> resolveSourceLanguage(AppSettings settings, List<TranscriptCue> cues) {
        String configured = LanguageUtils.normalizeLanguage(settings.sourceLanguage, "auto");
        if (!"auto".equals(configured) && TranslateLanguage.fromLanguageTag(configured) != null) {
            return com.google.android.gms.tasks.Tasks.forResult(configured);
        }
        StringBuilder sample = new StringBuilder();
        for (TranscriptCue cue : cues) {
            if (sample.length() > 1200) {
                break;
            }
            sample.append(cue.sourceText).append('\n');
        }
        return languageIdentifier.identifyLanguage(sample.toString());
    }

    private Translator translatorFor(String sourceLanguage, String targetLanguage) {
        String key = sourceLanguage + ">" + targetLanguage;
        Translator existing = translators.get(key);
        if (existing != null) {
            return existing;
        }
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build();
        Translator translator = Translation.getClient(options);
        translators.put(key, translator);
        return translator;
    }

    private void translateNext(
            java.util.function.Function<String, Task<String>> translator,
            String languagePair,
            List<TranscriptCue> cues,
            Callback callback,
            TranslationState state
    ) {
        if (closed) {
            return;
        }
        while (!closed && state.index < cues.size()) {
            int index = state.index;
            String sourceText = cues.get(index).sourceText;
            String normalizedText = normalizeCueText(sourceText);
            state.index++;

            if (normalizedText.isEmpty()) {
                state.failed++;
                reportProgress(callback, state);
                continue;
            }
            if (!shouldTranslateText(normalizedText)) {
                state.skipped++;
                callback.onTranslated(index, sourceText);
                reportProgress(callback, state);
                continue;
            }

            String cacheKey = cacheKey(languagePair, normalizedText);
            String cachedTranslation = translationCache.get(cacheKey);
            if (cachedTranslation != null) {
                state.cached++;
                callback.onTranslated(index, cachedTranslation);
                reportProgress(callback, state);
                continue;
            }

            long started = SystemClock.elapsedRealtime();
            callback.onSentence(state.engine.label, index + 1, state.total, 0);
            if (closed) return;
            slowNotice = new Runnable() {
                @Override public void run() {
                    callback.onSentence(state.engine.label, index + 1, state.total,
                            SystemClock.elapsedRealtime() - started);
                    if (slowNotice == this) main.postDelayed(this, SLOW_SENTENCE_MS);
                }
            };
            main.postDelayed(slowNotice, SLOW_SENTENCE_MS);
            bounded(() -> translator.apply(normalizedText), state.engine.sentenceTimeoutMs)
                    .addOnSuccessListener(text -> {
                        if (closed) return;
                        boolean empty = text == null || text.trim().isEmpty();
                        logSentence(state, index, started, empty ? "empty" : "success");
                        if (empty) {
                            state.failed++;
                        } else {
                            translationCache.put(cacheKey, text);
                            state.translated++;
                            callback.onTranslated(index, text);
                        }
                        reportProgress(callback, state);
                        translateNext(translator, languagePair, cues, callback, state);
                    })
                    .addOnFailureListener(ex -> {
                        if (closed) return;
                        if (ex instanceof java.util.concurrent.TimeoutException) {
                            if (state.engine == Engine.M2M) {
                                state.failed++;
                                state.timedOut++;
                                Log.w(TAG, "engine=M2M100 sentence=" + (index + 1) + "/" + state.total
                                        + " input_chars=" + normalizedText.length()
                                        + " elapsed_ms=" + (SystemClock.elapsedRealtime() - started)
                                        + " result=timeout");
                                reportProgress(callback, state);
                                if (closed) return;
                                bounded(m2mEngine::recoverAfterTimeout, M2M_RECOVERY_MS)
                                        .addOnSuccessListener(ignored -> {
                                            if (closed) return;
                                            Log.d(TAG, "engine=M2M100 recovery=success");
                                            translateNext(translator, languagePair, cues, callback, state);
                                        })
                                        .addOnFailureListener(error -> {
                                            if (closed) return;
                                            Log.w(TAG, "engine=M2M100 recovery=failed", error);
                                            callback.onFinished(state.completed() - state.failed,
                                                    state.timedOut + " câu quá thời gian. M2M100 không thể phục hồi.");
                                        });
                                return;
                            }
                            logSentence(state, index, started, "timeout");
                            callback.onFinished(state.completed() - state.failed,
                                    state.engine.label + " quá thời gian xử lý câu " + (index + 1)
                                            + ". Hãy dịch tiếp các câu còn thiếu.");
                            return;
                        }
                        logSentence(state, index, started, ex.getClass().getSimpleName());
                        state.failed++;
                        reportProgress(callback, state);
                        translateNext(translator, languagePair, cues, callback, state);
                    });
            return;
        }
        if (closed) return;
        int completed = state.completed();
        if (state.timedOut > 0) {
            int otherFailures = state.failed - state.timedOut;
            callback.onFinished(completed - state.failed, state.timedOut + " câu quá thời gian"
                    + (otherFailures > 0 ? " · " + otherFailures + " câu lỗi khác" : "") + ".");
            return;
        }
        callback.onFinished(
                completed - state.failed,
                "Đã dịch offline " + state.translated + "/" + state.total
                        + " câu, cache " + state.cached
                        + ", bỏ qua " + state.skipped
                        + (state.failed > 0 ? ", lỗi " + state.failed : "")
                        + "."
        );
    }

    private void reportProgress(Callback callback, TranslationState state) {
        callback.onProgress(state.completed(), state.total, state.cached, state.skipped);
    }

    private void logSentence(TranslationState state, int index, long started, String result) {
        Log.d(TAG, "engine=" + state.engine.label + " sentence=" + (index + 1) + "/" + state.total
                + " elapsed_ms=" + (SystemClock.elapsedRealtime() - started) + " result=" + result);
    }

    private String normalizeCueText(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE.matcher(text.trim()).replaceAll(" ");
    }

    private boolean shouldTranslateText(String text) {
        if (URL_LIKE.matcher(text).matches()) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                hasLetter = true;
                break;
            }
        }
        return hasLetter;
    }

    private String cacheKey(String languagePair, String normalizedText) {
        return languagePair + '\u001f' + normalizedText;
    }

    private static final class TranslationState {
        final int total;
        final Engine engine;
        int index;
        int translated;
        int cached;
        int skipped;
        int failed;
        int timedOut;

        TranslationState(int total, Engine engine) {
            this.total = total;
            this.engine = engine;
        }

        int completed() {
            return translated + cached + skipped + failed;
        }
    }
}
