package com.aiplayer.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class TelegramVideoSource {
    private static final int MAX_HTML_BYTES = 1024 * 1024;
    private static final Pattern POST = Pattern.compile("/(?:s/)?([A-Za-z0-9_]+/[1-9][0-9]*)/?");
    private static final String ATTRIBUTES = "(?:[^<>\"']|\"[^\"]*\"|'[^']*')*+";
    private static final Pattern VIDEO = Pattern.compile("<video\\b(" + ATTRIBUTES + ")>(.*?)</video\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SOURCE = Pattern.compile("<source\\b(" + ATTRIBUTES + ")>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([^\\s=/'\">]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?");

    private TelegramVideoSource() {}

    static String resolve(String input) throws IOException {
        String value = input.trim();
        if (value.regionMatches(true, 0, "t.me/", 0, 5)) value = "https://" + value;
        URI link = URI.create(value);
        if (!"t.me".equalsIgnoreCase(link.getHost())) return value;
        URI embed = embedUri(link);
        HttpsURLConnection connection = (HttpsURLConnection) embed.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Accept", "text/html");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("Không mở được bản xem trước Telegram.");
            }
            try (InputStream inputStream = connection.getInputStream()) {
                ByteArrayOutputStream html = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = inputStream.read(buffer)) != -1) {
                    if (count > MAX_HTML_BYTES - html.size()) {
                        throw new IOException("Bản xem trước Telegram quá lớn.");
                    }
                    html.write(buffer, 0, count);
                }
                String media = extractVideo(new String(html.toByteArray(), StandardCharsets.UTF_8));
                if (media != null) return media;
            }
            throw new PreviewWithoutVideo(embed.resolve(embed.getPath()));
        } finally {
            connection.disconnect();
        }
    }

    static final class PreviewWithoutVideo extends IOException {
        final URI post;

        PreviewWithoutVideo(URI post) {
            this.post = post;
        }
    }

    static URI embedUri(URI link) {
        Matcher post = POST.matcher(link.getRawPath() == null ? "" : link.getRawPath());
        if (!"https".equalsIgnoreCase(link.getScheme()) || !"t.me".equalsIgnoreCase(link.getHost())
                || link.getRawUserInfo() != null || link.getPort() != -1 || !post.matches()) {
            throw new IllegalArgumentException("Hãy nhập liên kết bài đăng Telegram công khai dạng t.me/kenh/123.");
        }
        return URI.create("https://t.me/" + post.group(1) + "?embed=1&single=1");
    }

    static String extractVideo(String html) {
        Matcher video = VIDEO.matcher(html);
        while (video.find()) {
            String direct = mediaUrl(video.group(1));
            if (direct != null) return direct;
            Matcher source = SOURCE.matcher(video.group(2));
            while (source.find()) {
                String nested = mediaUrl(source.group(1));
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static String mediaUrl(String attributes) {
        Matcher attribute = ATTRIBUTE.matcher(attributes);
        while (attribute.find()) {
            if (!"src".equalsIgnoreCase(attribute.group(1))) continue;
            String value = attribute.group(2);
            if (value == null) value = attribute.group(3);
            if (value == null) value = attribute.group(4);
            if (value == null) return null;
            try {
                URI uri = URI.create(value.replace("&amp;", "&").replace("&#38;", "&"));
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                        && uri.getRawUserInfo() == null && uri.getRawFragment() == null) {
                    return uri.toASCIIString();
                }
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
