package com.aiplayer.android;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stores downloaded videos in the app-private movies directory. */
final class SavedVideoStore {
    interface Callback { void done(File file, Exception error); }
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    SavedVideoStore(Context context) { this.context = context.getApplicationContext(); }

    File[] list() {
        File dir = directory();
        File[] files = dir.listFiles(file -> file.isFile() && !file.getName().endsWith(".part"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files;
    }

    File directory() {
        File dir = new File(context.getFilesDir(), "saved_videos");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create video directory");
        return dir;
    }

    void download(String address, Callback callback) {
        executor.execute(() -> {
            File temp = null;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(address);
                if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IllegalArgumentException("Only HTTPS URLs are supported");
                String path = url.getPath().toLowerCase(java.util.Locale.ROOT);
                if (!(path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv")))
                    throw new IllegalArgumentException("Use a direct MP4, WebM or MKV video URL");
                String extension = path.substring(path.lastIndexOf('.'));
                String name = "video_" + System.currentTimeMillis() + extension;
                File destination = new File(directory(), name);
                temp = new File(directory(), name + ".part");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false);
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) throw new java.io.IOException("HTTP " + status);
                long length = connection.getContentLengthLong();
                if (length > 2L * 1024 * 1024 * 1024) throw new java.io.IOException("Video exceeds 2 GB limit");
                long bytes = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        bytes += n;
                        if (bytes > 2L * 1024 * 1024 * 1024) throw new java.io.IOException("Video exceeds 2 GB limit");
                        output.write(buffer, 0, n);
                    }
                    output.getFD().sync();
                }
                if (bytes == 0 || !temp.renameTo(destination)) throw new java.io.IOException("Could not save video");
                main.post(() -> callback.done(destination, null));
            } catch (Exception error) {
                if (temp != null) temp.delete();
                main.post(() -> callback.done(null, error));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }
}
