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
    private final ContextModelStore standardModel;
    private final ContextModelStore lightModel;
    private final android.content.SharedPreferences contextPreferences;
    private ContextModelStore contextual;
    final M2mModelStore m2m;
    boolean m2mDownloading;
    long m2mBytes;
    String m2mError;
    private volatile boolean m2mCancelled;
    private boolean m2mImporting;
    Operation contextOperation;
    ContextModelStore contextOperationModel;
    long contextBytes;
    long contextTotal = -1;
    private volatile boolean contextCancelRequested;
    boolean contextImporting;
    String voskImportLanguage;
    String voskImportError;
    private volatile boolean voskImportCancelled;
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
        standardModel = new ContextModelStore(context, false);
        lightModel = new ContextModelStore(context, true);
        contextPreferences = context.getSharedPreferences("context_translation_models", Context.MODE_PRIVATE);
        contextual = contextPreferences.getBoolean("light", false) ? lightModel : standardModel;
        m2m = new M2mModelStore(context);
        connectivity = context.getSystemService(ConnectivityManager.class);
        this.changed = changed;
    }

    static boolean pending(Map<String, Operation> operations) {
        return operations.containsValue(Operation.DOWNLOADING) || operations.containsValue(Operation.DELETING);
    }

    boolean pending() { return pending(recognition) || pending(translation) || contextPending() || m2mDownloading; }

    void toggleM2mDownload() {
        if (m2mDownloading) {
            m2mCancelled = true;
            changed.run();
            return;
        }
        if (m2m.installed() || !ContextModelStore.supported()) return;
        startM2mInstall(null, null);
    }

    void importM2mFiles(android.content.ContentResolver resolver, List<android.net.Uri> files) {
        if (m2mDownloading || !ContextModelStore.supported() || files.isEmpty()) return;
        startM2mInstall(resolver, files);
    }

    private void startM2mInstall(android.content.ContentResolver resolver, List<android.net.Uri> files) {
        m2mImporting = files != null;
        m2mDownloading = true;
        m2mCancelled = false;
        m2mError = null;
        m2mBytes = 0;
        changed.run();
        new Thread(() -> {
            String failure = null;
            try {
                ContextModelStore.Progress progress = (bytes, total) -> main.post(() -> {
                    m2mBytes = bytes;
                    changed.run();
                });
                if (files == null) m2m.install(() -> m2mCancelled, progress);
                else m2m.importFiles(resolver, files, () -> m2mCancelled, progress);
            } catch (java.net.UnknownHostException error) {
                failure = "Mạng hiện tại không phân giải được máy chủ tải model. "
                        + "Hãy thử Wi-Fi khác hoặc chọn Nhập tệp model.";
            } catch (Exception error) {
                failure = error.getMessage() == null ? "Không tải được M2M100." : error.getMessage();
            }
            String result = failure;
            main.post(() -> {
                m2mError = m2mCancelled ? null : result;
                m2mDownloading = false;
                changed.run();
            });
        }, "m2m-model-download").start();
    }

    String m2mStatus() {
        if (m2mDownloading) return m2mCancelled ? "Đang dừng"
                : (m2mImporting ? "Đã nhập " : "Đã tải ") + m2mBytes / 1_000_000 + " MB";
        if (m2m.installed()) return "Đã cài đặt";
        return m2mError == null ? "Cần tải khoảng 1,15 GB" : m2mError;
    }

    void deleteM2mModel() {
        if (m2mDownloading) return;
        try {
            m2m.delete();
            m2mError = null;
        } catch (java.io.IOException error) {
            m2mError = "Không xóa được model M2M100.";
        }
        changed.run();
    }

    ContextModelStore contextual() { return contextual; }

    ContextModelStore contextModel(boolean light) { return light ? lightModel : standardModel; }

    void selectContextModel(boolean light) {
        ContextModelStore selected = contextModel(light);
        if (contextPending() || selected == contextual) return;
        contextual = selected;
        contextPreferences.edit().putBoolean("light", light).apply();
    }

    boolean contextPending() { return contextOperation == Operation.DOWNLOADING; }

    boolean contextCancelling() { return contextCancelRequested; }

    void cancelContextDownload() {
        if (!contextPending() || contextCancelling()) return;
        contextCancelRequested = true;
        changed.run();
    }

    void downloadContextModel(ContextModelStore model) {
        if (contextPending() || model.installed() || !ContextModelStore.supported()) return;
        startContextInstall(model, null, null);
    }

    void importContextModel(ContextModelStore model, android.content.ContentResolver resolver, android.net.Uri uri) {
        if (contextPending() || !ContextModelStore.supported()) return;
        startContextInstall(model, resolver, uri);
    }

    private void startContextInstall(ContextModelStore installing, android.content.ContentResolver resolver, android.net.Uri uri) {
        contextOperationModel = installing;
        contextImporting = uri != null;
        contextOperation = Operation.DOWNLOADING;
        contextBytes = contextImporting ? 0 : installing.partialBytes();
        contextTotal = -1;
        contextCancelRequested = false;
        contextError = null;
        changed.run();
        new Thread(() -> {
            String failure = null;
            try {
                ContextModelStore.Progress progress = (bytes, total) -> main.post(() -> {
                    if (contextCancelling()) return;
                    contextBytes = bytes;
                    contextTotal = total;
                    changed.run();
                });
                if (uri == null) installing.download(() -> contextCancelRequested, progress);
                else installing.importModel(resolver, uri, () -> contextCancelRequested, progress);
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

    void cancelVoskImport() {
        voskImportCancelled = true;
        changed.run();
    }

    void importVoskModel(String language, android.content.ContentResolver resolver, android.net.Uri uri) {
        if (pending(recognition)) return;
        voskImportLanguage = language;
        voskImportError = null;
        voskImportCancelled = false;
        recognitionPercent = -1;
        recognition.put(language, Operation.DOWNLOADING);
        changed.run();
        vosk.importModel(language, resolver, uri, new VoskModelStore.Callback() {
            @Override public boolean isCancelled() { return voskImportCancelled; }
            @Override public void onReady(org.vosk.Model model, String source) { model.close(); }
            @Override public void onStatus(String message) {}
            @Override public void onError(String message) {}
            @Override public void onDownloadProgress(int percent) {
                if (percent % 5 == 0) main.post(() -> {
                    recognitionPercent = percent;
                    changed.run();
                });
            }
        }, error -> main.post(() -> {
            voskImportError = voskImportCancelled ? null : error;
            voskImportLanguage = null;
            recognition.remove(language);
            changed.run();
        }));
    }

    void deleteContextModel(ContextModelStore model) {
        if (contextPending() || !model.installed()) return;
        contextOperationModel = model;
        boolean deleted = model.file.delete();
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
