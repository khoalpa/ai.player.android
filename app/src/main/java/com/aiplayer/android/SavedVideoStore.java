package com.aiplayer.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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
    private final SharedPreferences metadata;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static boolean downloadPartialsChecked;

    SavedVideoStore(Context context) {
        this.context = context.getApplicationContext();
        metadata = this.context.getSharedPreferences("saved_video_metadata", Context.MODE_PRIVATE);
        cleanAbandonedDownloadPartials(new File(this.context.getFilesDir(), "saved_videos"));
    }

    private static synchronized void cleanAbandonedDownloadPartials(File directory) {
        if (downloadPartialsChecked) return;
        downloadPartialsChecked = true;
        // The first store in this process is created before any URL download can write a partial.
        File[] partials = directory.listFiles(file -> file.isFile()
                && file.getName().startsWith("video_") && file.getName().endsWith(".part"));
        if (partials != null) {
            for (File partial : partials) partial.delete();
        }
    }

    static String sourceKey(VideoSourceResolver.Source source) {
        return source.kind == VideoSourceResolver.Kind.DIRECT_FILE
                ? VideoUrlPolicy.directSourceKey(source.url) : telegramKey(source);
    }

    static String telegramKey(VideoSourceResolver.Source source) {
        if (source.kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST) {
            String[] parts = source.url.getPath().replaceFirst("/$", "").split("/");
            return "c/" + parts[2] + "/" + parts[parts.length - 1];
        }
        return source.kind == VideoSourceResolver.Kind.TELEGRAM_PUBLIC_POST
                ? source.channel.toLowerCase(java.util.Locale.ROOT) + "/" + source.postId : null;
    }

    File findBySource(String key) {
        if (key == null) return null;
        for (File file : list()) {
            if (key.equals(metadata.getString(file.getName() + ".key", null))) return file;
        }
        return null;
    }

    void remember(File file, String source, String key, String title) {
        String filename = file.getName();
        metadata.edit().putString(filename + ".source", source)
                .putString(filename + ".key", key)
                .putString(filename + ".title", cleanTitle(title))
                .apply();
    }

    void forget(File file) {
        String name = file.getName();
        metadata.edit().remove(name + ".source").remove(name + ".key").remove(name + ".title").apply();
    }

    String displayName(File file) {
        String title = metadata.getString(file.getName() + ".title", null);
        return title == null || title.isEmpty() ? file.getName() : title;
    }

    String sourceLabel(File file) {
        String name = file.getName();
        String previous = name.startsWith("telegram_") ? "Telegram"
                : name.startsWith("video_") ? "URL" : name.startsWith("shared_") ? "Chia sẻ" : "";
        return metadata.getString(name + ".source", previous);
    }

    static String nameFromUrl(String url) {
        return url == null ? null : android.net.Uri.parse(url).getLastPathSegment();
    }

    private static String cleanTitle(String title) {
        if (title == null) return null;
        String clean = title.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        return clean.substring(0, Math.min(clean.length(), 180));
    }

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
                URL originalUrl = url;
                for (int redirects = 0; ; redirects++) {
                    if (task.isCancelled()) throw new IOException("Đã hủy tải video");
                    VideoUrlPolicy.requireDownloadablePath(url);
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
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw new IOException(VideoUrlPolicy.downloadHttpError(status));
                    }
                    break;
                }
                try (java.io.BufferedInputStream input = new java.io.BufferedInputStream(connection.getInputStream())) {
                    input.mark(WebVideoFinder.PREFIX_BYTES);
                    WebVideoFinder.Kind kind = WebVideoFinder.inspect(
                            WebVideoFinder.readPrefix(input, WebVideoFinder.PREFIX_BYTES), connection.getContentType());
                    if (task.isCancelled()) throw new IOException("Đã hủy tải video");
                    if (kind == WebVideoFinder.Kind.PAGE) throw new WebVideoFinder.PageResponse();
                    if (kind == WebVideoFinder.Kind.HLS || kind == WebVideoFinder.Kind.DASH) {
                        throw new IOException(VideoUrlPolicy.streamingMessage());
                    }
                    if (kind == WebVideoFinder.Kind.OTHER) {
                        throw new IOException("Liên kết trả về nội dung không phải video.");
                    }
                    input.reset();
                    String extension = VideoUrlPolicy.downloadExtension(url, originalUrl, connection.getContentType());
                    String name = "video_" + System.currentTimeMillis() + "_" +
                            java.util.UUID.randomUUID().toString().substring(0, 8) + extension;
                    File dir = directory();
                    File destination = new File(dir, name);
                    temp = new File(dir, name + ".part");
                    long length = connection.getContentLengthLong();
                    if (length > VideoUrlPolicy.MAX_BYTES) throw new IOException("Video vượt giới hạn 2 GB");
                    if (length > 0) StorageSpace.require(context, dir, length + StorageSpace.VIDEO_RESERVE);
                    main.post(() -> progress.update(0, length));
                    long bytes = 0;
                    long lastUpdate = 0;
                    try (FileOutputStream output = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[65536];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            if (task.isCancelled()) throw new IOException("Đã hủy tải video");
                            bytes += n;
                            if (bytes > VideoUrlPolicy.MAX_BYTES) throw new IOException("Video vượt giới hạn 2 GB");
                            StorageSpace.require(context, dir, n + StorageSpace.WRITE_RESERVE);
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
                    if (length >= 0 && bytes != length) throw new IOException("Tệp tải về chưa đầy đủ");
                    validateVideo(temp);
                    if (task.isCancelled()) throw new IOException("Đã hủy tải video");
                    if (!temp.renameTo(destination)) throw new IOException("Không thể lưu video");
                    main.post(() -> callback.done(destination, null));
                }
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

    static void validateVideo(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getPath());
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                extractor.selectTrack(index);
                if (extractor.getSampleTime() >= 0) return;
                extractor.unselectTrack(index);
            }
        } catch (IOException | RuntimeException invalid) {
            throw new IOException("Nội dung tải về không phải tệp video hợp lệ", invalid);
        } finally {
            extractor.release();
        }
        throw new IOException("Tệp tải về không có dữ liệu hình ảnh");
    }
}
