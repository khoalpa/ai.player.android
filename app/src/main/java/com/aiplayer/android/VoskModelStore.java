package com.aiplayer.android;

import android.content.Context;
import android.content.res.AssetManager;

import org.vosk.Model;
import org.vosk.android.StorageService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class VoskModelStore {
    interface Callback {
        void onReady(Model model);

        void onError(String message);

        void onStatus(String message);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    VoskModelStore(Context context) {
        this.context = context.getApplicationContext();
    }

    void prepare(String sourceLanguage, Callback callback) {
        ModelSpec spec = specFor(sourceLanguage);
        File modelDir = modelDir(spec.assetName);
        if (isModelReady(modelDir)) {
            executor.execute(() -> openModel(modelDir, callback));
            return;
        }
        if (assetExists(spec.assetName)) {
            callback.onStatus("Đang nạp model Vosk từ assets/" + spec.assetName + "...");
            StorageService.unpack(
                    context,
                    spec.assetName,
                    "vosk-" + spec.assetName,
                    callback::onReady,
                    exception -> callback.onError("Không nạp được model Vosk từ assets: " + exception.getMessage())
            );
            return;
        }
        callback.onStatus("Đang tải model Vosk " + spec.assetName + " lần đầu...");
        executor.execute(() -> downloadAndOpen(spec, modelDir, callback));
    }

    void close() {
        executor.shutdownNow();
    }

    private void downloadAndOpen(ModelSpec spec, File modelDir, Callback callback) {
        File tempDir = new File(modelDir.getParentFile(), modelDir.getName() + ".tmp");
        deleteRecursive(tempDir);
        tempDir.mkdirs();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(spec.url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "AIPlayerAndroid/0.1");
            int total = conn.getContentLength();
            int downloaded = 0;
            try (InputStream raw = conn.getInputStream();
                 ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                byte[] buffer = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    String strippedName = stripTopFolder(entry.getName());
                    if (strippedName.isEmpty()) {
                        continue;
                    }
                    File outFile = new File(tempDir, strippedName);
                    String canonicalRoot = tempDir.getCanonicalPath();
                    String canonicalOut = outFile.getCanonicalPath();
                    if (!canonicalOut.startsWith(canonicalRoot)) {
                        throw new IllegalStateException("Zip entry không hợp lệ");
                    }
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                        continue;
                    }
                    File parent = outFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            downloaded += read;
                        }
                    }
                    if (total > 0) {
                        int percent = Math.min(99, (int) ((downloaded * 100L) / total));
                        callback.onStatus("Đang tải model Vosk " + spec.assetName + ": " + percent + "%");
                    }
                }
            }
            deleteRecursive(modelDir);
            if (!tempDir.renameTo(modelDir)) {
                throw new IllegalStateException("Không lưu được model Vosk");
            }
            if (!isModelReady(modelDir)) {
                throw new IllegalStateException("Model tải về thiếu conf/model.conf");
            }
            openModel(modelDir, callback);
        } catch (Exception ex) {
            deleteRecursive(tempDir);
            callback.onError("Không tải được model Vosk: " + ex.getMessage());
        }
    }

    private void openModel(File modelDir, Callback callback) {
        try {
            callback.onReady(new Model(modelDir.getAbsolutePath()));
        } catch (Exception ex) {
            callback.onError("Không mở được model Vosk: " + ex.getMessage());
        }
    }

    private File modelDir(String assetName) {
        return new File(new File(context.getFilesDir(), "vosk_models"), assetName);
    }

    private boolean isModelReady(File dir) {
        return new File(dir, "conf/model.conf").exists();
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

    private ModelSpec specFor(String sourceLanguage) {
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
