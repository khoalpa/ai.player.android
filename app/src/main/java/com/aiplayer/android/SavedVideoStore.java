package com.aiplayer.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** App-private video downloads. Callbacks are dispatched on the main thread. */
final class SavedVideoStore {
    interface Callback { void done(File file, Exception error); }
    interface Progress { void update(long bytes, long total); }

    static final class Download {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile HttpURLConnection activeConnection;
        void cancel() {
            cancelled.set(true);
            HttpURLConnection connection = activeConnection;
            if (connection != null) connection.disconnect();
        }
        boolean isCancelled() { return cancelled.get(); }
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    SavedVideoStore(Context context) { this.context = context.getApplicationContext(); }

    File[] list() {
        File[] files = directory().listFiles(file -> file.isFile() && !file.getName().endsWith(".part"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files;
    }

    File directory() {
        File dir = new File(context.getFilesDir(), "saved_videos");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create video directory");
        return dir;
    }

    Download download(String address, Progress progress, Callback callback) {
        Download task = new Download();
        executor.execute(() -> {
            File temp = null;
            HttpURLConnection connection = null;
            try {
                URL url = VideoUrlPolicy.requireHttps(address);
                String extension = VideoUrlPolicy.extension(url);
                // Timestamp plus a random suffix prevents name collisions.
                String name = "video_" + System.currentTimeMillis() + "_" +
                        java.util.UUID.randomUUID().toString().substring(0, 8) + extension;
                File dir = directory();
                File destination = new File(dir, name);
                temp = new File(dir, name + ".part");
                for (int redirects = 0; ; redirects++) {
                    if (task.isCancelled()) throw new IOException("Đã hủy tải video");
                    connection = (HttpURLConnection) url.openConnection();
                    task.activeConnection = connection;
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(false);
                    int status = connection.getResponseCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        if (redirects >= VideoUrlPolicy.MAX_REDIRECTS) throw new IOException("Quá nhiều lần chuyển hướng");
                        URL next = VideoUrlPolicy.redirect(url, connection.getHeaderField("Location"));
                        connection.disconnect();
                        task.activeConnection = null;
                        connection = null;
                        url = next;
                        continue;
                    }
                    if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
                    break;
                }
                long length = connection.getContentLengthLong();
                if (length > VideoUrlPolicy.MAX_BYTES) throw new IOException("Video vượt giới hạn 2 GB");
                if (length > 0 && dir.getUsableSpace() < length + 16L * 1024 * 1024)
                    throw new IOException("Không đủ dung lượng lưu trữ");
                long bytes = 0;
                long lastUpdate = 0;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (task.isCancelled()) throw new IOException("Đã hủy tải video");
                        bytes += n;
                        if (bytes > VideoUrlPolicy.MAX_BYTES) throw new IOException("Video vượt giới hạn 2 GB");
                        if (dir.getUsableSpace() < 1024 * 1024) throw new IOException("Không đủ dung lượng lưu trữ");
                        output.write(buffer, 0, n);
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastUpdate > 300) {
                            lastUpdate = now;
                            long current = bytes;
                            main.post(() -> progress.update(current, length));
                        }
                    }
                    output.getFD().sync();
                }
                if (task.isCancelled()) throw new IOException("Đã hủy tải video");
                if (bytes == 0 || !temp.renameTo(destination)) throw new IOException("Không thể lưu video");
                main.post(() -> callback.done(destination, null));
            } catch (Exception error) {
                if (temp != null) temp.delete();
                main.post(() -> callback.done(null, error));
            } finally {
                task.activeConnection = null;
                if (connection != null) connection.disconnect();
            }
        });
        return task;
    }
}
