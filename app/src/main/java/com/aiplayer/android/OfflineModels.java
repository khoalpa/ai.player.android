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
    enum Operation { DOWNLOADING, DELETING, DOWNLOAD_FAILED, DELETE_FAILED }

    final VoskModelStore vosk;
    final ContextModelStore contextual;
    Operation contextOperation;
    long contextBytes;
    long contextTotal = -1;
    private volatile boolean contextCancelRequested;
    String contextError;
    final Map<String, Operation> recognition = new HashMap<>();
    final Map<String, Operation> translation = new HashMap<>();
    final Set<String> downloadedTranslations = new HashSet<>();
    boolean translationsLoaded;
    boolean translationQueryFailed;
    int recognitionPercent = -1;
    final Map<String, String> recognitionSpaceErrors = new HashMap<>();

    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private int translationQuery;

    OfflineModels(Context context, Runnable changed) {
        vosk = new VoskModelStore(context);
        contextual = new ContextModelStore(context);
        connectivity = context.getSystemService(ConnectivityManager.class);
        this.changed = changed;
    }

    static boolean pending(Map<String, Operation> operations) {
        return operations.containsValue(Operation.DOWNLOADING) || operations.containsValue(Operation.DELETING);
    }

    boolean pending() { return pending(recognition) || pending(translation) || contextPending(); }

    boolean contextPending() { return contextOperation == Operation.DOWNLOADING; }

    boolean contextCancelling() { return contextCancelRequested; }

    void cancelContextDownload() {
        if (!contextPending() || contextCancelling()) return;
        contextCancelRequested = true;
        changed.run();
    }

    void downloadContextModel() {
        if (contextPending() || contextual.installed() || !ContextModelStore.supported()) return;
        contextOperation = Operation.DOWNLOADING;
        contextBytes = contextual.partialBytes();
        contextTotal = -1;
        contextCancelRequested = false;
        contextError = null;
        changed.run();
        new Thread(() -> {
            String failure = null;
            try {
                contextual.download(() -> contextCancelRequested, (bytes, total) -> main.post(() -> {
                    if (contextCancelling()) return;
                    contextBytes = bytes;
                    contextTotal = total;
                    changed.run();
                }));
            } catch (Exception error) {
                failure = error.getMessage();
                if (failure == null) failure = "Không tải được model dịch theo ngữ cảnh.";
            }
            String result = failure;
            main.post(() -> {
                contextError = contextCancelling() ? null : result;
                contextOperation = contextError == null ? null : Operation.DOWNLOAD_FAILED;
                contextCancelRequested = false;
                changed.run();
            });
        }, "context-model-download").start();
    }

    void deleteContextModel() {
        if (contextPending() || !contextual.installed()) return;
        boolean deleted = contextual.file.delete();
        contextOperation = deleted ? null : Operation.DELETE_FAILED;
        contextError = deleted ? null : "Không xóa được model dịch theo ngữ cảnh.";
        changed.run();
    }

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
                        if (previous == Operation.DOWNLOAD_FAILED) {
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
        recognitionSpaceErrors.remove(language);
        if (!hasInternetConnection()) {
            finish(recognition, language, Operation.DOWNLOAD_FAILED);
            return;
        }
        recognitionPercent = -1;
        recognition.put(language, Operation.DOWNLOADING);
        changed.run();
        vosk.prepare(language, new VoskModelStore.Callback() {
            @Override public boolean isCancelled() { return false; }
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
                main.post(() -> {
                    if (StorageSpace.isLowSpace(message)) recognitionSpaceErrors.put(language, message);
                    finish(recognition, language, Operation.DOWNLOAD_FAILED);
                });
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
        if (!hasInternetConnection()) {
            for (String language : languages) translation.put(language, Operation.DOWNLOAD_FAILED);
            changed.run();
            return;
        }
        translationQuery++;
        for (String language : languages) translation.put(language, Operation.DOWNLOADING);
        changed.run();
        DownloadConditions conditions = new DownloadConditions.Builder().build();
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

    private boolean hasInternetConnection() {
        Network network = connectivity.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void finish(Map<String, Operation> operations, String language, Operation failure) {
        if (failure == null) operations.remove(language);
        else operations.put(language, failure);
        changed.run();
    }
}
