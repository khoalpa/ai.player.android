package com.aiplayer.android;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TelegramLink {
    private static final Pattern HANDLE = Pattern.compile("@([A-Za-z][A-Za-z0-9_]{3,31})");
    private static final Pattern CHANNEL = Pattern.compile("/(?:s/)?([A-Za-z][A-Za-z0-9_]{3,31})/?");
    private static final Pattern DEEP_LINK = Pattern.compile(
            "(?i)(?<![\\w@./:+-])tg:(?://)?(?:resolve|privatepost|join)\\?[^\\s<>\"']+");
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?:https?://|(?<![\\w@./:+-]))(?:www\\.t\\.me|telegram\\.me|t\\.me)/[^\\s<>\"“”]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARED_LINK = Pattern.compile(
            DEEP_LINK.pattern() + "|" + CANDIDATE.pattern(), Pattern.CASE_INSENSITIVE);
    private static final List<String> CHANNEL_ACTIONS = Arrays.asList("start", "startapp", "startattach",
            "startgroup", "startchannel", "attach", "profile", "text", "direct",
            "voicechat", "videochat", "livestream", "boost", "story");
    private static final List<String> RESERVED_PATHS = Arrays.asList("addemoji", "addlist", "addstickers",
            "addtheme", "boost", "confirmphone", "contact", "giftcode", "invoice",
            "joinchat", "login", "nft", "proxy", "setlanguage", "share", "socks");

    final URI url;
    final VideoSourceResolver.Source source;
    final String channel;

    private TelegramLink(URI url, VideoSourceResolver.Source source, String channel) {
        this.url = url;
        this.source = source;
        this.channel = channel;
    }

    static TelegramLink parse(String input) throws Exception {
        String address = input.trim();
        Matcher handle = HANDLE.matcher(address);
        if (handle.matches()) address = "t.me/" + handle.group(1);
        if (address.regionMatches(true, 0, "tg:", 0, 3)) address = normalizeDeepLink(address);
        if (address.regionMatches(true, 0, "http://", 0, 7)) address = "https://" + address.substring(7);
        VideoSourceResolver.Source source = VideoSourceResolver.classify(address);
        if (source.kind == VideoSourceResolver.Kind.DIRECT_FILE || source.url.getPort() != -1
                || source.url.getPath().length() < 2) {
            throw new IllegalArgumentException("Hãy nhập URL Telegram hoặc @username.");
        }
        URI uri = source.url.toURI();
        URI canonical = URI.create("https://t.me" + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        Matcher channel = CHANNEL.matcher(uri.getPath());
        String username = channel.matches() ? channel.group(1) : null;
        if (username != null && (RESERVED_PATHS.contains(username.toLowerCase(java.util.Locale.ROOT))
                || queryParts(uri.getRawQuery()).stream().map(TelegramLink::parameterName)
                        .anyMatch(CHANNEL_ACTIONS::contains))) username = null;
        return new TelegramLink(canonical, VideoSourceResolver.classify(canonical.toString()), username);
    }

    private static String normalizeDeepLink(String address) {
        URI uri = URI.create(address);
        String target = uri.getRawSchemeSpecificPart();
        if (target.startsWith("//")) target = target.substring(2);
        int queryStart = target.indexOf('?');
        if (queryStart < 0) throw new IllegalArgumentException("Liên kết Telegram thiếu tham số.");
        List<String> parameters = queryParts(target.substring(queryStart + 1));
        String path;
        switch (target.substring(0, queryStart).toLowerCase(java.util.Locale.ROOT)) {
            case "resolve":
                String username = takeParameter(parameters, "domain", "[A-Za-z][A-Za-z0-9_]{3,31}", true);
                String post = takeParameter(parameters, "post", "[1-9][0-9]*", false);
                path = "/" + username + (post == null ? "" : "/" + post);
                break;
            case "privatepost":
                path = "/c/" + takeParameter(parameters, "channel", "[1-9][0-9]*", true)
                        + "/" + takeParameter(parameters, "post", "[1-9][0-9]*", true);
                break;
            case "join":
                path = "/+" + takeParameter(parameters, "invite", "[A-Za-z0-9_-]+", true);
                break;
            default:
                throw new IllegalArgumentException("Dạng liên kết Telegram chưa được hỗ trợ.");
        }
        return "https://t.me" + path + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
    }

    private static List<String> queryParts(String query) {
        return query == null || query.isEmpty()
                ? new ArrayList<>() : new ArrayList<>(Arrays.asList(query.split("&", -1)));
    }

    private static String parameterName(String part) {
        int equals = part.indexOf('=');
        return equals < 0 ? part : part.substring(0, equals);
    }

    private static String takeParameter(List<String> parts, String name, String pattern, boolean required) {
        String value = null;
        for (Iterator<String> iterator = parts.iterator(); iterator.hasNext();) {
            String part = iterator.next();
            if (!parameterName(part).equals(name)) continue;
            if (value != null || !part.startsWith(name + "=")) {
                throw new IllegalArgumentException("Tham số liên kết Telegram không hợp lệ.");
            }
            value = part.substring(name.length() + 1);
            iterator.remove();
        }
        if (value == null ? required : !value.matches(pattern)) {
            throw new IllegalArgumentException("Tham số liên kết Telegram không hợp lệ.");
        }
        return value;
    }

    static List<String> extract(CharSequence text) {
        Set<String> links = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>(links);
        Matcher matcher = SHARED_LINK.matcher(text);
        while (links.size() < 30 && matcher.find()) {
            try {
                links.add(parse(matcher.group().replaceFirst("[.,;!?)\\]}'’]+$", "")).url.toString());
            } catch (Exception ignored) {
                // A share may contain other text or malformed URLs.
            }
        }
        return new ArrayList<>(links);
    }

    boolean isPublicPost() { return source.kind == VideoSourceResolver.Kind.TELEGRAM_PUBLIC_POST; }
    boolean isPrivatePost() { return source.kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST; }
    boolean isBotProfile() {
        return url.getPath().matches("(?i)/[a-z][a-z0-9_]{0,28}bot/?");
    }
    String botDeepLink() {
        String username = url.getPath().replace("/", "");
        String query = url.getRawQuery();
        return "tg://resolve?domain=" + username + (query == null || query.isEmpty() ? "" : "&" + query);
    }
    boolean isInvite() {
        return url.getPath().matches("/(?:\\+|joinchat/)[A-Za-z0-9_-]+/?");
    }
    boolean isComment() {
        return queryParts(url.getRawQuery()).stream().anyMatch(value -> value.startsWith("comment="));
    }
}
