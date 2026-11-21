package com.aiplayer.android;

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies media URLs. Public Telegram posts are resolved separately from direct files. */
final class VideoSourceResolver {
    enum Kind { DIRECT_FILE, TELEGRAM_PUBLIC_POST, TELEGRAM_PRIVATE_POST, UNSUPPORTED_PAGE }
    static final class Source {
        final Kind kind;
        final URL url;
        final String channel;
        final String postId;
        Source(Kind kind, URL url, String channel, String postId) {
            this.kind = kind;
            this.url = url;
            this.channel = channel;
            this.postId = postId;
        }
    }

    private static final Pattern TELEGRAM_WITHOUT_SCHEME = Pattern.compile(
            "^(?:t\\.me|www\\.t\\.me|telegram\\.me)/", Pattern.CASE_INSENSITIVE);
    private static final Pattern TELEGRAM_POST = Pattern.compile(
            "^/(?:s/)?([A-Za-z][A-Za-z0-9_]{3,31})/(?:[1-9][0-9]*/)?([1-9][0-9]*)/?$");
    private static final Pattern TELEGRAM_PRIVATE_POST = Pattern.compile(
            "^/c/[1-9][0-9]*/(?:[1-9][0-9]*/)?[1-9][0-9]*/?$");
    private VideoSourceResolver() {}

    static Source classify(String input) throws Exception {
        String address = input.trim();
        if (TELEGRAM_WITHOUT_SCHEME.matcher(address).lookingAt()) {
            address = "https://" + address;
        }
        URL url = VideoUrlPolicy.requireHttps(address);
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("t.me") || host.equals("www.t.me") || host.equals("telegram.me")) {
            if (url.getPort() == -1 && TELEGRAM_PRIVATE_POST.matcher(url.getPath()).matches())
                return new Source(Kind.TELEGRAM_PRIVATE_POST, url, null, null);
            Matcher match = TELEGRAM_POST.matcher(url.getPath());
            if (url.getPort() != -1 || !match.matches())
                return new Source(Kind.UNSUPPORTED_PAGE, url, null, null);
            return new Source(Kind.TELEGRAM_PUBLIC_POST, url, match.group(1), match.group(2));
        }
        return new Source(Kind.DIRECT_FILE, url, null, null);
    }

    static URL publicTelegramEmbed(Source source) throws Exception {
        if (source.kind != Kind.TELEGRAM_PUBLIC_POST) throw new IllegalArgumentException("Not a Telegram post");
        return VideoUrlPolicy.requireHttps("https://t.me/" + source.channel + "/" + source.postId + "?embed=1");
    }

    /**
     * A public embed page may advertise a directly accessible media URL.
     * Never follow arbitrary page links or attempt to use cookies/authenticated media.
     */
    static String extractPublicMediaUrl(String html) throws Exception {
        String video = TelegramVideoSource.extractVideo(html);
        if (video != null) return video;
        // Telegram public widgets can place the direct media src on a div.
        Pattern tags = Pattern.compile("<div\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern src = Pattern.compile("\\ssrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Pattern klass = Pattern.compile("\\sclass\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher elements = tags.matcher(html);
        while (elements.find()) {
            String tag = elements.group();
            Matcher classes = klass.matcher(tag);
            if (!classes.find() ||
                    !java.util.Arrays.asList(classes.group(1).split("\\s+"))
                            .contains("tgme_widget_message_video")) continue;
            Matcher attribute = src.matcher(tag);
            if (!attribute.find()) continue;
            String candidate = attribute.group(1).replace("&amp;", "&");
            try {
                URL url = VideoUrlPolicy.requireHttps(candidate);
                return url.toExternalForm();
            } catch (IllegalArgumentException | java.net.MalformedURLException ignored) {
                // Ignore thumbnails and non-video media sources.
            }
        }
        throw new IllegalArgumentException("Bài đăng không cung cấp video công khai trực tiếp");
    }
}
