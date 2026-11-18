package com.aiplayer.android;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;

/** Direct video file downloads only; web pages require a separate site resolver. */
final class VideoDownloads {
    private VideoDownloads() {}

    static boolean isDirectVideoUrl(String raw) {
        if (raw == null) return false;
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null || uri.getUserInfo() != null) return false;
            String path = uri.getPath();
            if (path == null) return false;
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".mp4") || lower.endsWith(".m4v")
                    || lower.endsWith(".webm") || lower.endsWith(".mkv")
                    || lower.endsWith(".mov");
        } catch (Exception ignored) {
            return false;
        }
    }

    static File directory(Context context) {
        File root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (root == null) throw new IllegalStateException("Không có bộ nhớ lưu video.");
        File folder = new File(root, "AIPlayer");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IllegalStateException("Không tạo được thư mục lưu video.");
        }
        return folder;
    }

    static long enqueue(Context context, String url) {
        if (!isDirectVideoUrl(url)) {
            throw new IllegalArgumentException("Chỉ hỗ trợ URL trực tiếp tới tệp MP4/M4V/WebM/MKV/MOV. Liên kết bài đăng cần bộ phân giải riêng.");
        }
        File folder = directory(context);
        String path = URI.create(url.trim()).getPath();
        String extension = path.substring(path.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT);
        String name = "video_" + System.currentTimeMillis() + extension;
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url.trim()));
        request.setTitle("AI Player · " + name);
        request.setDescription("Đang tải video");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationUri(Uri.fromFile(new File(folder, name)));
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) throw new IllegalStateException("Dịch vụ tải xuống không khả dụng.");
        return manager.enqueue(request);
    }

    static File[] saved(Context context) {
        File[] files = directory(context).listFiles(file -> file.isFile() && file.length() > 0
                && isVideoFile(file.getName()));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files;
    }

    private static boolean isVideoFile(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".webm")
                || lower.endsWith(".mkv") || lower.endsWith(".mov");
    }
}
