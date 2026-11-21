package com.aiplayer.android;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/** Pure-Java URL rules used by the download pipeline. */
final class VideoUrlPolicy {
    static final int MAX_REDIRECTS = 5;
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private VideoUrlPolicy() {}

    static String directSourceKey(URL url) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(url.toExternalForm().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "url:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    static void requireDownloadablePath(URL url) {
        String path = url.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            throw new IllegalArgumentException(streamingMessage());
        }
    }

    static String streamingMessage() {
        return "Đây là liên kết phát trực tuyến. Hãy dùng URL trỏ đến tệp video.";
    }

    static String downloadHttpError(int status) {
        switch (status) {
            case 401: return "Máy chủ yêu cầu đăng nhập. Hãy dùng liên kết tải có quyền truy cập.";
            case 403: return "Máy chủ từ chối truy cập. Hãy kiểm tra quyền hoặc lấy liên kết mới nếu đã hết hạn.";
            case 404:
            case 410: return "Không tìm thấy video. Hãy kiểm tra hoặc lấy liên kết mới.";
            case 429: return "Máy chủ giới hạn lượt tải. Hãy thử lại sau.";
            default: return status >= 500
                    ? "Máy chủ tải video đang gặp lỗi. Hãy thử lại sau. (HTTP " + status + ")"
                    : "Máy chủ không trả về tệp video. (HTTP " + status + ")";
        }
    }

    static URL requireHttps(String address) throws MalformedURLException {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                || url.getUserInfo() != null || url.getRef() != null)
            throw new IllegalArgumentException("Chỉ chấp nhận URL HTTPS hợp lệ");
        return url;
    }

    private static String extension(URL url) {
        String path = url.getPath().toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".mp4", ".webm", ".mkv"}) {
            if (path.endsWith(ext)) return ext;
        }
        return "";
    }

    static String downloadExtension(URL finalUrl, URL originalUrl, String contentType) {
        String mime = contentType == null ? ""
                : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        switch (mime) {
            case "application/vnd.apple.mpegurl":
            case "application/x-mpegurl":
            case "audio/mpegurl":
            case "audio/x-mpegurl":
            case "application/dash+xml":
                throw new IllegalArgumentException(streamingMessage());
            case "text/html":
            case "application/xhtml+xml":
                throw new IllegalArgumentException("Liên kết mở một trang web. Hãy dùng URL trỏ đến tệp video.");
            case "video/mp4":
            case "application/mp4": return ".mp4";
            case "video/webm": return ".webm";
            case "video/x-matroska": return ".mkv";
            case "":
            case "application/octet-stream":
                break;
            default:
                if (!mime.startsWith("video/")) {
                    throw new IllegalArgumentException("URL không trả về tệp video trực tiếp");
                }
        }
        String extension = extension(finalUrl);
        if (extension.isEmpty()) extension = extension(originalUrl);
        return extension.isEmpty() ? ".video" : extension;
    }

    static URL redirect(URL previous, String location) throws MalformedURLException {
        if (location == null || location.trim().isEmpty()) throw new IllegalArgumentException("Redirect thiếu Location");
        return requireHttps(new URL(previous, location).toExternalForm());
    }
}
