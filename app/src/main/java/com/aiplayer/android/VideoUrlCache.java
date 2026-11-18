package com.aiplayer.android;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

final class VideoUrlCache {
    private static VideoUrlCache instance;
    private final Context context;
    private final DownloadManager manager;
    private final SharedPreferences state;

    static synchronized VideoUrlCache get(Context context) {
        if (instance == null) instance = new VideoUrlCache(context);
        return instance;
    }

    private VideoUrlCache(Context context) {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(DownloadManager.class);
        state = context.getSharedPreferences("video_url_download", Context.MODE_PRIVATE);
    }

    synchronized void start(String input) {
        URI source = URI.create(input.trim());
        if (!"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null
                || source.getRawUserInfo() != null || source.getRawFragment() != null) {
            throw new IllegalArgumentException("Cần URL HTTPS hợp lệ, không chứa thông tin đăng nhập hoặc fragment.");
        }
        if (active()) throw new IllegalStateException("Đang có một video tải xuống.");
        String id = UUID.randomUUID().toString();
        File partial = new File(directory(), id + ".part");
        long downloadId = manager.enqueue(new DownloadManager.Request(Uri.parse(source.toASCIIString()))
                .setTitle("Tải video từ URL")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(partial)));
        if (!state.edit().putLong("download", downloadId).putString("file", id).commit()) {
            manager.remove(downloadId);
            throw new IllegalStateException("Không lưu được tác vụ tải.");
        }
    }

    synchronized boolean active() {
        return state.contains("download");
    }

    synchronized String refresh() {
        long downloadId = state.getLong("download", -1);
        if (downloadId == -1) return "";
        String id = state.getString("file", "");
        File folder = directory();
        File partial = new File(folder, id + ".part");
        File result = new File(folder, id + ".mp4");
        // The final rename may have succeeded before the process exited.
        if (result.isFile()) {
            finish(downloadId);
            return "Đã tải video.";
        }

        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) {
                finish(downloadId);
                return "Tác vụ tải không còn. Hãy dán lại URL để thử lại.";
            }
            int status = (int) value(cursor, DownloadManager.COLUMN_STATUS);
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                long expected = value(cursor, DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                if (!partial.isFile() || (expected >= 0 && partial.length() != expected)
                        || !isVideoMp4(partial)) {
                    finish(downloadId);
                    return "URL không trả về tệp video MP4 hợp lệ.";
                }
                if (!partial.renameTo(result)) {
                    finish(downloadId);
                    return "Không lưu được video đã tải.";
                }
                finish(downloadId);
                return "Đã tải video.";
            }
            if (status == DownloadManager.STATUS_FAILED) {
                long reason = value(cursor, DownloadManager.COLUMN_REASON);
                finish(downloadId);
                if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) return "Bộ nhớ không đủ để tải video.";
                return "Tải thất bại (" + reason + "). Hãy dán lại URL để thử lại.";
            }
            if (status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_PENDING) {
                return "Đang chờ tải video…";
            }
            long downloaded = value(cursor, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
            long total = value(cursor, DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            return total > 0 ? "Đang tải " + Math.min(100, (int) (100.0 * downloaded / total)) + "%"
                    : "Đã tải " + android.text.format.Formatter.formatFileSize(context, downloaded);
        }
    }

    synchronized void cancel() {
        long id = state.getLong("download", -1);
        if (id != -1) finish(id);
    }

    File[] videos() {
        File[] files = directory().listFiles(file -> file.isFile() && file.getName().endsWith(".mp4"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files;
    }

    boolean contains(Uri uri) {
        if (!"file".equals(uri.getScheme()) || uri.getPath() == null) return false;
        File file = new File(uri.getPath());
        File folder = context.getExternalFilesDir("saved-videos");
        return folder != null && folder.equals(file.getParentFile()) && file.getName().endsWith(".mp4")
                && file.isFile();
    }

    private File directory() {
        File folder = context.getExternalFilesDir("saved-videos");
        if (folder == null || !folder.isDirectory()) {
            throw new IllegalStateException("Không truy cập được thư mục video đã lưu.");
        }
        return folder;
    }

    private void finish(long id) {
        manager.remove(id);
        state.edit().clear().commit();
    }

    private static long value(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }

    private static boolean isVideoMp4(File file) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getPath());
            if (!"video/mp4".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))) {
                return false;
            }
            extractor.setDataSource(file.getPath());
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                extractor.selectTrack(index);
                return extractor.getSampleTime() >= 0;
            }
            return false;
        } catch (IOException | RuntimeException invalid) {
            return false;
        } finally {
            extractor.release();
            try {
                metadata.release();
            } catch (IOException | RuntimeException ignored) {
                // The input may already have been rejected.
            }
        }
    }
}
