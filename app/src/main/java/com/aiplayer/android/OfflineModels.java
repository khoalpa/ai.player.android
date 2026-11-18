package com.aiplayer.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread state for model management, shared with the playback session. */
final class OfflineModels {
    enum Operation { DOWNLOADING, DELETING, DOWNLOAD_FAILED, DELETE_FAILED, WIFI_REQUIRED }

    final VoskModelStore vosk;
    final Map<String, Operation> recognition = new HashMap<>();
    final Map<String, Operation> translation = new HashMap<>();
    final Set<String> downloadedTranslations = new HashSet<>();
    boolean translationsLoaded;
    boolean translationQueryFailed;
    int recognitionPercent = -1;

    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private int translationQuery;

    OfflineModels(Context context, Runnable changed) {
        vosk = new VoskModelStore(context);
        connectivity = context.getSystemService(ConnectivityManager.class);
        this.changed = changed;
    }

    static boolean pending(Map<String, Operation> operations) {
        return operations.containsValue(Operation.DOWNLOADING) || operations.containsValue(Operation.DELETING);
    }

    boolean pending() { return pending(recognition) || pending(translation); }

    void refreshTranslations() {
        if (pending(translation)) return;
        int query = ++translationQuery;
        translationQueryFailed = false;
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(models -> {
                    if (query != translationQuery) return;
                    downloadedTranslations.clear();
                    for (TranslateRemoteModel model : models) {
                        String language = model.getLanguage();
                        downloadedTranslations.add(language);
                        Operation previous = translation.get(language);
                        if (previous == Operation.DOWNLOAD_FAILED || previous == Operation.WIFI_REQUIRED) {
                            translation.remove(language);
                        }
                    }
                    translationsLoaded = true;
                    changed.run();
                })
                .addOnFailureListener(error -> {
                    if (query != translationQuery) return;
                    translationQueryFailed = true;
                    changed.run();
                });
    }

    void downloadRecognition(String language) {
        if (pending(recognition)) return;
        Network wifi = wifiNetwork();
        if (wifi == null) {
            finish(recognition, language, Operation.WIFI_REQUIRED);
            return;
        }
        recognitionPercent = -1;
        recognition.put(language, Operation.DOWNLOADING);
        changed.run();
        vosk.prepare(language, new VoskModelStore.Callback() {
            @Override public boolean isCancelled() { return false; }
            @Override public Network downloadNetwork() { return wifi; }
            @Override public void onDownloadProgress(int percent) {
                if (percent % 10 == 0) main.post(() -> {
                    recognitionPercent = percent;
                    changed.run();
                });
            }
            @Override public void onReady(org.vosk.Model model, String source) {
                model.close();
                main.post(() -> finish(recognition, language, null));
            }
            @Override public void onError(String message) {
                main.post(() -> finish(recognition, language, Operation.DOWNLOAD_FAILED));
            }
            @Override public void onStatus(String message) {}
        });
    }

    void deleteRecognition(String language) {
        if (pending(recognition)) return;
        recognition.put(language, Operation.DELETING);
        changed.run();
        vosk.deleteDownloaded(language, success -> main.post(() ->
                finish(recognition, language, success ? null : Operation.DELETE_FAILED)));
    }

    void downloadTranslations(List<String> languages) {
        if (languages.isEmpty() || pending(translation)) return;
        if (wifiNetwork() == null) {
            for (String language : languages) translation.put(language, Operation.WIFI_REQUIRED);
            changed.run();
            return;
        }
        translationQuery++;
        for (String language : languages) translation.put(language, Operation.DOWNLOADING);
        changed.run();
        DownloadConditions conditions = new DownloadConditions.Builder().requireWifi().build();
        RemoteModelManager manager = RemoteModelManager.getInstance();
        for (String language : languages) {
            manager.download(new TranslateRemoteModel.Builder(language).build(), conditions)
                    .addOnSuccessListener(result -> {
                        downloadedTranslations.add(language);
                        finish(translation, language, null);
                    })
                    .addOnFailureListener(error -> finish(translation, language, Operation.DOWNLOAD_FAILED));
        }
    }

    void deleteTranslation(String language) {
        if (pending(translation)) return;
        translationQuery++;
        translation.put(language, Operation.DELETING);
        changed.run();
        RemoteModelManager.getInstance().deleteDownloadedModel(new TranslateRemoteModel.Builder(language).build())
                .addOnSuccessListener(result -> {
                    downloadedTranslations.remove(language);
                    finish(translation, language, null);
                })
                .addOnFailureListener(error -> finish(translation, language, Operation.DELETE_FAILED));
    }

    static boolean needsTranslationModel(String language) {
        // ML Kit uses English as its pivot and ships no standalone English download.
        return TranslateLanguage.fromLanguageTag(language) != null && !TranslateLanguage.ENGLISH.equals(language);
    }

    private Network wifiNetwork() {
        Network network = connectivity.getActiveNetwork();
        if (network == null) return null;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? network : null;
    }

    private void finish(Map<String, Operation> operations, String language, Operation failure) {
        if (failure == null) operations.remove(language);
        else operations.put(language, failure);
        changed.run();
    }
}
