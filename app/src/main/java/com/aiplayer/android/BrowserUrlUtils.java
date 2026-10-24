package com.aiplayer.android;

import java.net.URI;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BrowserUrlUtils {
    private BrowserUrlUtils() {
    }

    static String normalizeBrowserUrl(String value, String homeUrl) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return homeUrl;
        }
        if (text.indexOf(' ') >= 0) {
            return "https://www.google.com/search?q=" + encodeQuery(text);
        }
        URI uri = parseUri(text);
        String scheme = uri == null ? null : uri.getScheme();
        if (scheme == null) {
            return "https://" + text;
        }
        if (isHttpScheme(scheme) || isPlayableExternalScheme(scheme)) {
            return text;
        }
        return "https://www.google.com/search?q=" + encodeQuery(text);
    }

    static boolean isHttpUrl(String rawUrl) {
        URI uri = parseUri(rawUrl);
        return uri != null && isHttpScheme(uri.getScheme());
    }

    static boolean isNativePlaybackUrl(String rawUrl) {
        URI uri = parseUri(rawUrl);
        return uri != null && (isDirectMediaUrl(rawUrl) || isPlayableExternalScheme(uri.getScheme()));
    }

    static boolean shouldExtractMediaFromPage(String rawUrl) {
        URI uri = parseUri(rawUrl);
        if (uri == null || !isHttpScheme(uri.getScheme())) {
            return false;
        }
        List<String> segments = pathSegments(uri.getRawPath());
        if (segments.isEmpty()) {
            return false;
        }
        int offset = segments.get(0).matches("[A-Za-z]{2}") ? 1 : 0;
        if (segments.size() <= offset) {
            return false;
        }
        String first = segments.get(offset).toLowerCase(Locale.US);
        return ("movie".equals(first) && segments.size() >= offset + 3)
                || (("video".equals(first) || "anime".equals(first)) && segments.size() >= offset + 2);
    }

    static boolean isDirectMediaUrl(String rawUrl) {
        URI uri = parseUri(rawUrl);
        if (uri == null) {
            return false;
        }
        String path = uri.getPath();
        if (path == null) {
            return false;
        }
        String lowerPath = path.toLowerCase(Locale.US);
        return lowerPath.endsWith(".mp4")
                || lowerPath.endsWith(".m4v")
                || lowerPath.endsWith(".m3u8")
                || lowerPath.endsWith(".webm")
                || lowerPath.endsWith(".mkv")
                || lowerPath.endsWith(".mov")
                || lowerPath.endsWith(".avi")
                || lowerPath.endsWith(".3gp")
                || lowerPath.endsWith(".mp3")
                || lowerPath.endsWith(".aac")
                || lowerPath.endsWith(".wav");
    }

    static String extractYoutubeId(String rawUrl) {
        URI uri = parseUri(rawUrl);
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        String host = uri.getHost().toLowerCase(Locale.US);
        if ("youtu.be".equals(host)) {
            List<String> segments = pathSegments(uri.getRawPath());
            return segments.isEmpty() ? null : cleanYoutubeId(segments.get(0));
        }
        if (host.endsWith("youtube.com")) {
            String id = queryParameter(uri.getRawQuery(), "v");
            if (id != null && !id.trim().isEmpty()) {
                return cleanYoutubeId(id);
            }
            List<String> segments = pathSegments(uri.getRawPath());
            for (int i = 0; i < segments.size() - 1; i++) {
                String segment = segments.get(i);
                if ("embed".equals(segment) || "shorts".equals(segment) || "live".equals(segment)) {
                    return cleanYoutubeId(segments.get(i + 1));
                }
            }
        }
        return null;
    }

    static String cleanYoutubeId(String value) {
        String cleaned = value == null ? "" : value.trim();
        int query = cleaned.indexOf('?');
        if (query >= 0) {
            cleaned = cleaned.substring(0, query);
        }
        return cleaned.matches("[A-Za-z0-9_-]{6,}") ? cleaned : null;
    }

    private static boolean isHttpScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static boolean isPlayableExternalScheme(String scheme) {
        return "rtsp".equalsIgnoreCase(scheme) || "rtmp".equalsIgnoreCase(scheme);
    }

    private static String encodeQuery(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static URI parseUri(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return null;
        }
        try {
            return URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<String> pathSegments(String rawPath) {
        List<String> segments = new ArrayList<>();
        if (rawPath == null || rawPath.isEmpty()) {
            return segments;
        }
        String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(decode(segment));
            }
        }
        return segments;
    }

    private static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String key = equals >= 0 ? part.substring(0, equals) : part;
            if (name.equals(decode(key))) {
                return equals >= 0 ? decode(part.substring(equals + 1)) : "";
            }
        }
        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
