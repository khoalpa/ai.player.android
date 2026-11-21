package com.aiplayer.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** On-device translation of Telegram captions, independent of subtitle language settings. */
final class TelegramPostTranslation {
    private static final String TARGET = TranslateLanguage.VIETNAMESE;
    // Keep line breaks, visible URLs, tags, mentions and emoji sequences out of the model.
    private static final Pattern PROTECTED = Pattern.compile(
            "(?i)(?:https?://|tg://|www\\.|t\\.me/)\\S+|[#@][\\p{L}\\p{M}\\p{N}_]+"
            + "|\\R|[0-9#*]\\uFE0F?\\u20E3|[\\p{So}\\uFE0F\\u200D\\x{1F3FB}-\\x{1F3FF}"
            + "\\x{E0020}-\\x{E007F}]+");

    private final SharedPreferences cache;
    private final Set<Translator> active = new HashSet<>();
    private LanguageIdentifier identifier;
    private boolean closed;

    TelegramPostTranslation(Context context) {
        cache = context.getSharedPreferences("telegram_post_translation_vi", Context.MODE_PRIVATE);
    }

    Task<String> identify(String text) {
        String sample = PROTECTED.matcher(text).replaceAll(" ").trim();
        if (identifier == null) identifier = LanguageIdentification.getClient();
        return identifier.identifyLanguage(sample);
    }

    static boolean hasText(String text) {
        return PROTECTED.matcher(text).replaceAll(" ").codePoints().anyMatch(Character::isLetter);
    }

    static boolean supports(String language) {
        String code = supportedCode(language);
        return code != null && !TARGET.equals(code);
    }

    static String supportedCode(String language) {
        return language == null ? null : TranslateLanguage.fromLanguageTag(language);
    }

    Task<Boolean> modelsReady(String source) {
        return RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class)
                .onSuccessTask(models -> Tasks.forResult(models.contains(model(TARGET))
                        && (!OfflineModels.needsTranslationModel(source) || models.contains(model(source)))));
    }

    private static TranslateRemoteModel model(String language) {
        return new TranslateRemoteModel.Builder(language).build();
    }

    String selectedSource(String url, String text) {
        String source = cachedValue("source:" + url, text);
        return supports(source) ? source : null;
    }

    void selectSource(String url, String text, String source) {
        cache.edit().putString("source:" + url, fingerprint(text) + "\n" + source).apply();
    }

    String cached(String url, String text, String source) {
        return cachedValue(source + ":" + url, text);
    }

    private String cachedValue(String key, String text) {
        String stored = cache.getString(key, "");
        String prefix = fingerprint(text) + "\n";
        return stored.startsWith(prefix) ? stored.substring(prefix.length()) : null;
    }

    Task<String> translate(String url, String text, String source, boolean download) {
        Translator translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(source).setTargetLanguage(TARGET).build());
        active.add(translator);
        Task<Void> ready = download
                ? translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                : Tasks.forResult(null);
        return ready.onSuccessTask(unused -> {
            if (closed) return Tasks.forCanceled();
            return translateText(translator, text);
        }).addOnCompleteListener(result -> {
            if (active.remove(translator)) translator.close();
            if (!closed && result.isSuccessful()) {
                cache.edit().putString(source + ":" + url, fingerprint(text) + "\n" + result.getResult())
                        .remove(url).apply();
            }
        });
    }

    private Task<String> translateText(Translator translator, String text) {
        Matcher matcher = PROTECTED.matcher(text);
        Task<StringBuilder> result = Tasks.forResult(new StringBuilder());
        int start = 0;
        while (matcher.find()) {
            result = appendTranslation(result, translator, text.substring(start, matcher.start()), matcher.group());
            start = matcher.end();
        }
        return appendTranslation(result, translator, text.substring(start), "")
                .onSuccessTask(builder -> Tasks.forResult(builder.toString()));
    }

    private Task<StringBuilder> appendTranslation(Task<StringBuilder> previous, Translator translator,
                                                  String text, String suffix) {
        return previous.onSuccessTask(builder -> {
            if (closed) return Tasks.forCanceled();
            String content = text.trim();
            if (content.codePoints().noneMatch(Character::isLetter)) {
                return Tasks.forResult(builder.append(text).append(suffix));
            }
            int start = text.indexOf(content);
            return translator.translate(content).onSuccessTask(translated -> {
                if (translated.trim().isEmpty()) {
                    return Tasks.forException(new IllegalStateException("Empty translation"));
                }
                return Tasks.forResult(builder.append(text, 0, start).append(translated)
                        .append(text, start + content.length(), text.length()).append(suffix));
            });
        });
    }

    private static String fingerprint(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void close() {
        closed = true;
        if (identifier != null) identifier.close();
        for (Translator translator : active) translator.close();
        active.clear();
    }
}
