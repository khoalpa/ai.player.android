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
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null)
            throw new IllegalArgumentException("Chỉ chấp nhận URL HTTPS hợp lệ");
        return url;
    }

    static String extension(URL url) {
        String path = url.getPath().toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".mp4", ".webm", ".mkv"}) {
            if (path.endsWith(ext)) return ext;
        }
        throw new IllegalArgumentException("Cần URL trực tiếp đến MP4, WebM hoặc MKV");
    }

    static URL redirect(URL previous, String location) throws MalformedURLException {
        if (location == null || location.trim().isEmpty()) throw new IllegalArgumentException("Redirect thiếu Location");
        return requireHttps(new URL(previous, location).toExternalForm());
    }
}
