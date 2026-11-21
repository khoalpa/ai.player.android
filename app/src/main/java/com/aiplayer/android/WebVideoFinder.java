package com.aiplayer.android;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Document;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Discovers media explicitly advertised by an anonymously accessible page. */
final class WebVideoFinder {
    static final int PREFIX_BYTES = 4096;
    private static final int MAX_HTML = 512 * 1024;
    private static final int MAX_CANDIDATES = 6;
    private static final int MAX_FRAMES = 2;
    private static final Pattern DASH = Pattern.compile(
            "^(?:<\\?xml[^>]*>\\s*)?<MPD(?:\\s|>)", Pattern.CASE_INSENSITIVE);

    enum Kind { FILE, HLS, DASH, PAGE, OTHER, UNKNOWN }

    static final class PageResponse extends IOException {
        PageResponse() {
            super("Liên kết mở một trang xem. Chọn Tìm video trên trang để kiểm tra nguồn phát.");
        }
    }

    static final class Candidate {
        final URL url;
        final Kind kind;
        final String format;

        Candidate(URL url, Kind kind, String format) {
            this.url = url;
            this.kind = kind;
            this.format = format;
        }

        String mime() {
            return kind == Kind.HLS ? "application/x-mpegURL" : "application/dash+xml";
        }
    }

    private volatile boolean cancelled;
    private volatile HttpURLConnection current;

    void cancel() {
        cancelled = true;
        HttpURLConnection connection = current;
        if (connection != null) connection.disconnect();
    }

    List<Candidate> find(URL page) throws IOException {
        Document document = readPage(page);
        var urls = new LinkedHashSet<>(candidates(document));
        for (String frame : frames(document)) {
            if (cancelled || urls.size() >= MAX_CANDIDATES) break;
            try {
                urls.addAll(candidates(readPage(VideoUrlPolicy.requireHttps(frame))));
            } catch (IOException | IllegalArgumentException unavailable) {
                // Another frame or the parent may still provide a usable source.
            }
        }
        return verify(urls);
    }

    private Document readPage(URL page) throws IOException {
        HttpURLConnection connection = connect(page, false);
        try (InputStream input = connection.getInputStream()) {
            return Jsoup.parse(new java.io.ByteArrayInputStream(readPrefix(input, MAX_HTML)), null,
                    connection.getURL().toExternalForm());
        } finally {
            connection.disconnect();
        }
    }

    List<Candidate> verify(Iterable<String> urls) {
        var sources = new ArrayList<Candidate>();
        var verified = new LinkedHashSet<String>();
        int checked = 0;
        for (String address : urls) {
            if (cancelled || checked++ >= MAX_CANDIDATES) break;
            try {
                HttpURLConnection connection = connect(VideoUrlPolicy.requireHttps(address), true);
                try (InputStream input = connection.getInputStream()) {
                    byte[] prefix = readPrefix(input, PREFIX_BYTES);
                    Kind kind = inspect(prefix, connection.getContentType());
                    URL finalUrl = connection.getURL();
                    if ((kind == Kind.FILE || kind == Kind.HLS || kind == Kind.DASH)
                            && verified.add(finalUrl.toExternalForm())) {
                        String format = kind == Kind.FILE ? fileFormat(prefix, finalUrl) : kind.name();
                        sources.add(new Candidate(finalUrl, kind, format));
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (IOException | IllegalArgumentException unavailable) {
                // Continue checking the other sources advertised by the page.
            }
        }
        return sources;
    }

    static byte[] readPrefix(InputStream input, int limit) throws IOException {
        byte[] bytes = new byte[limit];
        int size = 0;
        while (size < limit) {
            int count = input.read(bytes, size, limit - size);
            if (count < 0) break;
            size += count;
        }
        return size == limit ? bytes : java.util.Arrays.copyOf(bytes, size);
    }

    static List<String> candidates(Document page) {
        var urls = new LinkedHashSet<String>();
        for (Element element : page.select("video[src], video source[src], "
                + "meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], "
                + "meta[itemprop=contentUrl], link[itemprop=contentUrl]")) {
            String attribute = element.is("meta") ? "content" : element.is("link") ? "href" : "src";
            try {
                URL url = VideoUrlPolicy.requireHttps(element.absUrl(attribute));
                urls.add(url.toExternalForm());
                if (urls.size() == MAX_CANDIDATES) break;
            } catch (IOException | IllegalArgumentException invalid) {
                // Ignore embedded players, relative URLs without a base, and unsupported schemes.
            }
        }
        for (Element script : page.select("script[type=application/ld+json], script[type=application/json]")) {
            if (urls.size() >= MAX_CANDIDATES) break;
            try {
                collectJson(new JSONTokener(script.data()).nextValue(), script.baseUri(), false, 0, urls);
            } catch (org.json.JSONException | StackOverflowError malformed) {
                // Ignore malformed or deeply nested page metadata.
            }
        }
        return new ArrayList<>(urls);
    }

    static List<String> frames(Document page) {
        var urls = new LinkedHashSet<String>();
        for (Element frame : page.select("iframe[src]")) {
            addHttps(urls, frame.baseUri(), frame.absUrl("src"));
            if (urls.size() >= MAX_FRAMES) break;
        }
        return new ArrayList<>(urls);
    }

    private static void collectJson(Object node, String base, boolean media, int depth,
                                    LinkedHashSet<String> urls) {
        if (depth > 12 || urls.size() >= MAX_CANDIDATES) return;
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && urls.size() < MAX_CANDIDATES; i++) {
                collectJson(array.opt(i), base, media, depth + 1, urls);
            }
        } else if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if ("VideoObject".equals(object.optString("@type"))) {
                addHttps(urls, base, object.optString("contentUrl"));
            }
            var keys = object.keys();
            while (keys.hasNext() && urls.size() < MAX_CANDIDATES) {
                String key = keys.next();
                Object value = object.opt(key);
                boolean source = key.equals("sources") || key.equals("playlist") || key.equals("video");
                if (value instanceof String && (key.equals("file") || key.equals("videoUrl")
                        || (media && (key.equals("src") || key.equals("url"))))) {
                    addHttps(urls, base, (String) value);
                } else if (source && value instanceof String) {
                    addHttps(urls, base, (String) value);
                } else if (value instanceof JSONObject || value instanceof JSONArray) {
                    collectJson(value, base, media || source, depth + 1, urls);
                }
            }
        }
    }

    private static void addHttps(LinkedHashSet<String> urls, String base, String value) {
        if (value.trim().isEmpty()) return;
        try {
            URL url = VideoUrlPolicy.requireHttps(new URL(new URL(base), value).toExternalForm());
            urls.add(url.toExternalForm());
        } catch (IOException | IllegalArgumentException invalid) {
            // Unsupported or malformed source URL.
        }
    }

    private static String fileFormat(byte[] prefix, URL url) {
        if (matches(prefix, 4, "ftyp")) return "MP4";
        if (matches(prefix, 0, "FLV")) return "FLV";
        if (matches(prefix, 0, "RIFF")) return "AVI";
        return url.getPath().toLowerCase(Locale.ROOT).endsWith(".webm") ? "WebM" : "Matroska / WebM";
    }

    private HttpURLConnection connect(URL url, boolean probe) throws IOException {
        for (int redirects = 0; ; redirects++) {
            if (cancelled) throw new IOException("Đã hủy tìm video.");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            current = connection;
            try {
                if (cancelled) throw new IOException("Đã hủy tìm video.");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setInstanceFollowRedirects(false);
                if (probe) connection.setRequestProperty("Range", "bytes=0-4095");
                int status = connection.getResponseCode();
                if (status == 200 || (probe && status == 206)) return connection;
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    throw new IOException(VideoUrlPolicy.downloadHttpError(status));
                }
                if (redirects >= VideoUrlPolicy.MAX_REDIRECTS) throw new IOException("Quá nhiều lần chuyển hướng.");
                url = VideoUrlPolicy.redirect(url, connection.getHeaderField("Location"));
            } catch (IOException | RuntimeException error) {
                connection.disconnect();
                throw error;
            }
            connection.disconnect();
        }
    }

    static Kind inspect(byte[] prefix, String contentType) {
        String text = new String(prefix, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "").trim();
        String lower = text.toLowerCase(Locale.ROOT);
        String mime = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("#EXTM3U") && text.contains("#EXT-X-")) return Kind.HLS;
        if (DASH.matcher(text).find()) return Kind.DASH;
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html")
                || lower.startsWith("<head") || lower.startsWith("<body") || lower.startsWith("<video")
                || mime.equals("text/html") || mime.equals("application/xhtml+xml")) return Kind.PAGE;
        if (text.startsWith("{") || text.startsWith("[") || text.startsWith("%PDF-")
                || text.startsWith("#EXTM3U") || mime.startsWith("image/") || isImage(prefix)) return Kind.OTHER;
        if (matches(prefix, 4, "ftyp") || matches(prefix, 0, "\u001a\u0045\u00df\u00a3")
                || (matches(prefix, 0, "RIFF") && matches(prefix, 8, "AVI "))
                || matches(prefix, 0, "FLV")) return Kind.FILE;
        return Kind.UNKNOWN;
    }

    private static boolean isImage(byte[] prefix) {
        return matches(prefix, 0, "\u00ff\u00d8\u00ff") || matches(prefix, 0, "\u0089PNG")
                || matches(prefix, 0, "GIF8") || (matches(prefix, 0, "RIFF") && matches(prefix, 8, "WEBP"));
    }

    private static boolean matches(byte[] prefix, int offset, String marker) {
        if (prefix.length < offset + marker.length()) return false;
        for (int index = 0; index < marker.length(); index++) {
            if ((prefix[offset + index] & 0xff) != marker.charAt(index)) return false;
        }
        return true;
    }
}
