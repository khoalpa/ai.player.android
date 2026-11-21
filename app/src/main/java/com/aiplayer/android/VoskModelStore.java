package com.aiplayer.android;

import android.content.Context;
import android.content.res.AssetManager;

import org.vosk.Model;
import org.vosk.android.StorageService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class VoskModelStore {
    interface Callback {
        boolean isCancelled();

        default void onDownloadProgress(int percent) {}

        void onReady(Model model, String source);

        void onError(String message);

        void onStatus(String message);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile HttpURLConnection activeConnection;
    private volatile Callback activeDownload;
    private volatile long deletionVersion;

    void cancel() {
        HttpURLConnection connection = activeConnection;
        Callback download = activeDownload;
        if (connection != null && download != null && download.isCancelled()) connection.disconnect();
    }

    private static void checkCancelled(Callback callback) throws InterruptedIOException {
        if (callback.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Model preparation cancelled");
        }
    }

    VoskModelStore(Context context) {
        this.context = context.getApplicationContext();
    }

    static String modelVersion(String sourceLanguage) {
        return specFor(sourceLanguage).url;
    }

    String modelKey(String sourceLanguage) {
        return specFor(sourceLanguage).assetName;
    }

    boolean isDownloaded(String language) {
        return isModelReady(modelDir(modelKey(language)));
    }

    boolean isBundled(String language) {
        String model = modelKey(language);
        try (InputStream config = context.getAssets().open(model + "/conf/model.conf")) {
            return config.read() != -1;
        } catch (IOException ex) {
            return false;
        }
    }

    long deletionVersion() { return deletionVersion; }

    void deleteDownloaded(String language, Consumer<Boolean> finished) {
        File directory = modelDir(modelKey(language));
        executor.execute(() -> {
            deletionVersion++;
            deleteRecursive(directory);
            finished.accept(!directory.exists());
        });
    }

    void prepare(String sourceLanguage, Callback callback) {
        if (callback.isCancelled()) return;
        ModelSpec spec = specFor(sourceLanguage);
        File modelDir = modelDir(spec.assetName);
        if (isModelReady(modelDir)) {
            executor.execute(() -> {
                try {
                    checkCancelled(callback);
                    Model model = new Model(modelDir.getAbsolutePath());
                    if (callback.isCancelled()) model.close();
                    else callback.onReady(model, "disk");
                } catch (Exception ex) {
                    if (callback.isCancelled()) return;
                    deleteRecursive(modelDir);
                    callback.onStatus("Model Vosk bị lỗi. Đang tải lại...");
                    downloadAndOpen(spec, modelDir, callback);
                }
            });
            return;
        }
        if (assetExists(spec.assetName)) {
            callback.onStatus("Đang nạp model Vosk từ assets/" + spec.assetName + "...");
            StorageService.unpack(
                    context,
                    spec.assetName,
                    "vosk-" + spec.assetName,
                    model -> {
                        if (callback.isCancelled()) model.close();
                        else callback.onReady(model, "assets");
                    },
                    exception -> {
                        if (!callback.isCancelled()) executor.execute(() -> {
                            callback.onStatus("Không nạp được model đóng gói. Đang tải model thay thế...");
                            downloadAndOpen(spec, modelDir, callback);
                        });
                    }
            );
            return;
        }
        callback.onStatus("Đang tải model Vosk " + spec.assetName + " lần đầu...");
        executor.execute(() -> downloadAndOpen(spec, modelDir, callback));
    }

    void close() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        executor.shutdownNow();
    }

    private void downloadAndOpen(ModelSpec spec, File modelDir, Callback callback) {
        if (callback.isCancelled()) return;
        File tempDir = new File(modelDir.getParentFile(), modelDir.getName() + ".tmp");
        deleteRecursive(tempDir);
        tempDir.mkdirs();
        HttpURLConnection conn = null;
        try {
            checkCancelled(callback);
            URL url = new URL(spec.url);
            conn = (HttpURLConnection) url.openConnection();
            activeDownload = callback;
            activeConnection = conn;
            checkCancelled(callback);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "AIPlayerAndroid/0.1");
            long total = conn.getContentLengthLong();
            try (InputStream raw = new ModelDownloadStream(conn.getInputStream(), total, callback::isCancelled,
                    percent -> {
                        callback.onDownloadProgress(percent);
                        callback.onStatus("Đang tải và giải nén model Vosk " + spec.assetName + ": " + percent + "%");
                    });
                 ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    checkCancelled(callback);
                    String strippedName = stripTopFolder(entry.getName());
                    if (strippedName.isEmpty()) {
                        continue;
                    }
                    File outFile = ModelArchive.destination(tempDir, strippedName);
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                        continue;
                    }
                    File parent = outFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    if (entry.getSize() > 0) StorageSpace.require(context, tempDir, entry.getSize());
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            checkCancelled(callback);
                            StorageSpace.require(context, tempDir, read);
                            out.write(buffer, 0, read);
                        }
                    }
                }
            }
            checkCancelled(callback);
            if (!isModelReady(tempDir)) throw new IOException("Model archive is incomplete");
            callback.onStatus("Đang nạp model Vosk " + spec.assetName + "...");
            deleteRecursive(modelDir);
            if (!tempDir.renameTo(modelDir)) {
                throw new IllegalStateException("Không lưu được model Vosk");
            }
            if (!isModelReady(modelDir)) {
                throw new IllegalStateException("Model tải về thiếu conf/model.conf");
            }
            openModel(modelDir, callback, "download");
        } catch (Exception ex) {
            deleteRecursive(tempDir);
            if (!callback.isCancelled()) callback.onError("Không tải được model Vosk: " + ex.getMessage());
        } finally {
            activeConnection = null;
            activeDownload = null;
            if (conn != null) conn.disconnect();
        }
    }

    private void openModel(File modelDir, Callback callback, String source) {
        try {
            checkCancelled(callback);
            Model model = new Model(modelDir.getAbsolutePath());
            if (callback.isCancelled()) model.close();
            else callback.onReady(model, source);
        } catch (Exception ex) {
            if (callback.isCancelled()) return;
            deleteRecursive(modelDir);
            callback.onError("Không mở được model Vosk. Hãy thử lại để tải lại model: " + ex.getMessage());
        }
    }

    private File modelDir(String assetName) {
        return new File(new File(context.getFilesDir(), "vosk_models"), assetName);
    }

    static String downloadPackage(String language) {
        String url = specFor(language).url;
        return url.substring(url.lastIndexOf('/') + 1);
    }

    long installedBytes(String language) {
        File directory = modelDir(modelKey(language));
        if (!isModelReady(directory)) return -1;
        long bytes = directoryBytes(directory);
        return bytes > 0 && isModelReady(directory) ? bytes : -1;
    }

    long downloadedBytes() {
        File[] models = new File(context.getFilesDir(), "vosk_models").listFiles(
                file -> file.isDirectory() && !file.getName().endsWith(".tmp") && isModelReady(file));
        long bytes = 0;
        if (models != null) for (File model : models) bytes += directoryBytes(model);
        return bytes;
    }

    private static long directoryBytes(File path) {
        if (path.isFile()) return path.length();
        File[] children = path.listFiles();
        long bytes = 0;
        if (children != null) for (File child : children) bytes += directoryBytes(child);
        return bytes;
    }

    private boolean isModelReady(File dir) {
        File configuration = new File(dir, "conf/model.conf");
        return configuration.isFile() && configuration.length() > 0;
    }

    private boolean assetExists(String name) {
        try {
            AssetManager assets = context.getAssets();
            String[] root = assets.list("");
            if (root == null) {
                return false;
            }
            for (String item : root) {
                if (name.equals(item)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String stripTopFolder(String name) {
        String normalized = name.replace('\\', '/');
        int slash = normalized.indexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private static ModelSpec specFor(String sourceLanguage) {
        String language = sourceLanguage == null ? "" : sourceLanguage.trim().toLowerCase(Locale.US);
        if ("ja".equals(language) || "jp".equals(language)) {
            return new ModelSpec("model-ja", "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip");
        }
        if ("vi".equals(language) || "vn".equals(language)) {
            return new ModelSpec("model-vi", "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip");
        }
        if ("ko".equals(language)) {
            return new ModelSpec("model-ko", "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip");
        }
        if ("zh".equals(language) || "cn".equals(language)) {
            return new ModelSpec("model-cn", "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip");
        }
        return new ModelSpec("model-en-us", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip");
    }

    private static final class ModelSpec {
        final String assetName;
        final String url;

        ModelSpec(String assetName, String url) {
            this.assetName = assetName;
            this.url = url;
        }
    }
}
