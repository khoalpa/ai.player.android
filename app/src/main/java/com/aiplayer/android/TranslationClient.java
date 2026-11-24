package com.aiplayer.android;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class TranslationClient {
    private static final int MAX_TRANSLATORS = 3;
    private static final int MAX_TRANSLATION_CACHE_ENTRIES = 2000;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern URL_LIKE = Pattern.compile("(?i)^(https?://|www\\.|[\\w.-]+\\.[a-z]{2,})(\\S*)$");

    interface Callback {
        void onTranslated(int index, String translatedText);

        default void onModelUnavailable() {}

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
    private final DeepLClient deepL;

    TranslationClient() {
        this(null);
    }

    TranslationClient(String deepLKey) {
        deepL = deepLKey == null ? null : new DeepLClient(deepLKey);
        languageIdentifier = deepL == null ? LanguageIdentification.getClient() : null;
    }

    void translateCues(AppSettings settings, List<TranscriptCue> cues,
            List<TranscriptCue> languageSample, Callback callback) {
        if (cues.isEmpty()) {
            callback.onFinished(0, "Chưa có transcript để dịch.");
            return;
        }

        if (deepL != null) {
            if (!"vi".equals(settings.targetLanguage)) {
                callback.onFinished(0, "DeepL trong AI Player hiện hỗ trợ dịch sang tiếng Việt.");
                return;
            }
            String source = LanguageUtils.normalizeLanguage(settings.sourceLanguage, "auto");
            translateDeepLBatch(source, cues, callback, new TranslationState(cues.size()));
            return;
        }

        resolveSourceLanguage(settings, languageSample)
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
                    Translator translator = translatorFor(sourceLanguage, targetLanguage);
                    DownloadConditions conditions = new DownloadConditions.Builder().build();
                    translator.downloadModelIfNeeded(conditions)
                            .addOnSuccessListener(v -> translateNext(translator::translate, languagePair, cues, callback, new TranslationState(cues.size())))
                            .addOnFailureListener(ex -> {
                                if (closed) return;
                                callback.onModelUnavailable();
                                callback.onFinished(0,
                                        "Chưa có model dịch offline " + sourceLanguage + " -> " + targetLanguage
                                                + ". Hãy kết nối mạng một lần để tải model, sau đó có thể dịch offline.");
                            });
                })
                .addOnFailureListener(ex -> callback.onFinished(0, "Không nhận diện được ngôn ngữ nguồn: " + ex.getMessage()));
    }

    boolean usesDeepL() {
        return deepL != null;
    }

    void close() {
        closed = true;
        if (deepL != null) deepL.close();
        if (languageIdentifier != null) languageIdentifier.close();
        for (Translator translator : translators.values()) {
            translator.close();
        }
        translators.clear();
        translationCache.clear();
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

    private void translateDeepLBatch(String source, List<TranscriptCue> cues,
                                    Callback callback, TranslationState state) {
        if (closed) return;
        Map<String, List<Integer>> batch = new LinkedHashMap<>();
        Map<Integer, String> localResults = new LinkedHashMap<>();
        try {
            int bytes = DeepLClient.requestBody(source, Collections.emptyList()).length;
            while (state.index < cues.size()) {
                int index = state.index;
                String original = cues.get(index).sourceText;
                String text = normalizeCueText(original);
                if (text.isEmpty()) {
                    state.failed++;
                } else if ("vi".equals(source) || !shouldTranslateText(text)) {
                    localResults.put(index, original);
                } else if (translationCache.containsKey(text)) {
                    localResults.put(index, translationCache.get(text));
                } else {
                    List<Integer> indices = batch.get(text);
                    if (indices == null) {
                        int additionalBytes = new JSONArray().put(text).toString()
                                .getBytes(StandardCharsets.UTF_8).length - 1;
                        if (batch.size() == DeepLClient.MAX_TEXTS || bytes + additionalBytes > DeepLClient.MAX_REQUEST_BYTES) {
                            if (batch.isEmpty()) {
                                finishDeepL(callback, state, "Một đoạn văn vượt giới hạn gửi DeepL.");
                                return;
                            }
                            break;
                        }
                        bytes += additionalBytes;
                        indices = new ArrayList<>();
                        batch.put(text, indices);
                    }
                    indices.add(index);
                }
                state.index++;
            }
        } catch (JSONException error) {
            finishDeepL(callback, state, "Không thể tạo yêu cầu DeepL.");
            return;
        }

        if (batch.isEmpty()) {
            publishLocalResults(localResults, callback, state);
            finishDeepL(callback, state, null);
            return;
        }
        deepL.translate(source, new ArrayList<>(batch.keySet()))
                .addOnSuccessListener(results -> {
                    if (closed) return;
                    publishLocalResults(localResults, callback, state);
                    int resultIndex = 0;
                    for (Map.Entry<String, List<Integer>> entry : batch.entrySet()) {
                        String translation = results.get(resultIndex++);
                        translationCache.put(entry.getKey(), translation);
                        for (int index : entry.getValue()) {
                            callback.onTranslated(index, translation);
                            state.translated++;
                        }
                    }
                    callback.onProgress(state.completed(), state.total, state.cached, state.skipped);
                    translateDeepLBatch(source, cues, callback, state);
                })
                .addOnFailureListener(error -> {
                    if (!closed) finishDeepL(callback, state, error.getMessage());
                });
    }

    private void publishLocalResults(Map<Integer, String> results, Callback callback, TranslationState state) {
        results.forEach(callback::onTranslated);
        state.cached += results.size();
    }

    private void finishDeepL(Callback callback, TranslationState state, String failure) {
        int saved = state.completed() - state.failed;
        String progress = "Đã hoàn tất " + saved + "/" + state.total + " đoạn bằng DeepL.";
        callback.onFinished(saved, failure == null ? progress : failure + " " + progress);
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
        while (state.index < cues.size()) {
            int index = state.index;
            String sourceText = cues.get(index).sourceText;
            String normalizedText = normalizeCueText(sourceText);
            state.index++;

            if (normalizedText.isEmpty()) {
                state.failed++;
                maybeReportProgress(callback, state);
                continue;
            }
            if (!shouldTranslateText(normalizedText)) {
                state.skipped++;
                callback.onTranslated(index, sourceText);
                maybeReportProgress(callback, state);
                continue;
            }

            String cacheKey = cacheKey(languagePair, normalizedText);
            String cachedTranslation = translationCache.get(cacheKey);
            if (cachedTranslation != null) {
                state.cached++;
                callback.onTranslated(index, cachedTranslation);
                maybeReportProgress(callback, state);
                continue;
            }

            translator.apply(normalizedText)
                    .addOnSuccessListener(text -> {
                        if (closed) return;
                        if (text == null || text.trim().isEmpty()) {
                            state.failed++;
                        } else {
                            translationCache.put(cacheKey, text);
                            state.translated++;
                            callback.onTranslated(index, text);
                        }
                        maybeReportProgress(callback, state);
                        translateNext(translator, languagePair, cues, callback, state);
                    })
                    .addOnFailureListener(ex -> {
                        if (closed) return;
                        state.failed++;
                        maybeReportProgress(callback, state);
                        translateNext(translator, languagePair, cues, callback, state);
                    });
            return;
        }
        int completed = state.completed();
        callback.onProgress(completed, state.total, state.cached, state.skipped);
        callback.onFinished(
                completed - state.failed,
                "Đã dịch offline " + state.translated + "/" + state.total
                        + " đoạn, cache " + state.cached
                        + ", bỏ qua " + state.skipped
                        + (state.failed > 0 ? ", lỗi " + state.failed : "")
                        + "."
        );
    }

    private void maybeReportProgress(Callback callback, TranslationState state) {
        int completed = state.completed();
        int interval = Math.max(1, state.total / 20);
        if (completed == state.total || completed - state.lastProgress >= interval) {
            state.lastProgress = completed;
            callback.onProgress(completed, state.total, state.cached, state.skipped);
        }
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
        int index;
        int translated;
        int cached;
        int skipped;
        int failed;
        int lastProgress;

        TranslationState(int total) {
            this.total = total;
        }

        int completed() {
            return translated + cached + skipped + failed;
        }
    }
}
