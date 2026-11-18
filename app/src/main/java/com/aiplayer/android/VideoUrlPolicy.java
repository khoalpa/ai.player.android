package com.aiplayer.android;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/** Pure-Java URL rules used by the download pipeline. */
final class VideoUrlPolicy {
    static final int MAX_REDIRECTS = 5;
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private VideoUrlPolicy() {}

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
