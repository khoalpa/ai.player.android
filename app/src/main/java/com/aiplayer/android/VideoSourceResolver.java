package com.aiplayer.android;

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies media URLs. Public Telegram posts are resolved separately from direct files. */
final class VideoSourceResolver {
    enum Kind { DIRECT_FILE, TELEGRAM_PUBLIC_POST, UNSUPPORTED_PAGE }
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

    private static final Pattern TELEGRAM_POST = Pattern.compile(
            "^/([A-Za-z][A-Za-z0-9_]{3,31})/([1-9][0-9]*)(?:/)?$");
    private VideoSourceResolver() {}

    static Source classify(String input) throws Exception {
        URL url = VideoUrlPolicy.requireHttps(input.trim());
        String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("t.me") || host.equals("www.t.me") || host.equals("telegram.me")) {
            Matcher match = TELEGRAM_POST.matcher(url.getPath());
            if (!match.matches())
                return new Source(Kind.UNSUPPORTED_PAGE, url, null, null);
            return new Source(Kind.TELEGRAM_PUBLIC_POST, url, match.group(1), match.group(2));
        }
        try {
            VideoUrlPolicy.extension(url);
            return new Source(Kind.DIRECT_FILE, url, null, null);
        } catch (IllegalArgumentException ignored) {
            return new Source(Kind.UNSUPPORTED_PAGE, url, null, null);
        }
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
        // Telegram public widgets can place the direct media src on a div.
        Pattern tags = Pattern.compile("<(?:video|source|div)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern src = Pattern.compile("\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Pattern klass = Pattern.compile("\\bclass\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher elements = tags.matcher(html);
        while (elements.find()) {
            String tag = elements.group();
            if (tag.regionMatches(true, 1, "div", 0, 3)) {
                Matcher classes = klass.matcher(tag);
                if (!classes.find() ||
                        !java.util.Arrays.asList(classes.group(1).split("\\s+"))
                                .contains("tgme_widget_message_video")) continue;
            }
            Matcher attribute = src.matcher(tag);
            if (!attribute.find()) continue;
            String candidate = attribute.group(1).replace("&amp;", "&");
            try {
                URL url = VideoUrlPolicy.requireHttps(candidate);
                VideoUrlPolicy.extension(url);
                return url.toExternalForm();
            } catch (IllegalArgumentException | java.net.MalformedURLException ignored) {
                // Ignore thumbnails and non-video media sources.
            }
        }
        throw new IllegalArgumentException("Bài đăng không cung cấp video công khai trực tiếp");
    }
}
