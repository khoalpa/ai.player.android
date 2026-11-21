package com.aiplayer.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

final class TelegramPublicContent {
    static final class TextLink {
        final int start;
        final int end;
        final String url;

        TextLink(int start, int end, String url) {
            this.start = start;
            this.end = end;
            this.url = url;
        }
    }

    static final class Post {
        final String url;
        final String author;
        final String date;
        final String text;
        final String image;
        final boolean video;
        final List<TextLink> links = new ArrayList<>();
        int previewFile;
        boolean canSave = true;
        boolean saveVerified;
        String attachment = "";

        Post(String url, String author, String date, String text, String image, boolean video) {
            this.url = url;
            this.author = author;
            this.date = date;
            this.text = text;
            this.image = image;
            this.video = video;
        }

        void addLink(int start, int end, String address) {
            if (start < 0 || end <= start || end > text.length()) return;
            try {
                java.net.URI target = java.net.URI.create(address);
                String scheme = target.getScheme();
                if (target.getHost() != null
                        && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                    links.add(new TextLink(start, end, address));
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid link targets.
            }
        }
    }

    String title = "";
    String description = "";
    String image = "";
    final List<Post> posts = new ArrayList<>();

    static TelegramPublicContent load(TelegramLink link) throws Exception {
        if (link.isBotProfile()) return parse(fetch(link.url.getRawPath()), link);
        String path = link.isPublicPost()
                ? "/" + link.source.channel + "/" + link.source.postId + "?embed=1"
                : link.isInvite() ? link.url.getPath() : "/s/" + link.channel;
        TelegramPublicContent result;
        try {
            result = parse(fetch(path), link);
        } catch (org.jsoup.HttpStatusException failure) {
            if (link.channel == null || failure.getStatusCode() >= 500) throw failure;
            result = new TelegramPublicContent();
        }
        if (link.channel != null && result.posts.isEmpty() && result.title.isEmpty()) {
            result = parse(fetch("/" + link.channel), link);
        }
        if (result.posts.isEmpty() && result.title.isEmpty()) {
            throw new IOException("Telegram chưa cung cấp bản xem trước cho liên kết này.");
        }
        return result;
    }

    private static Document fetch(String path) throws IOException {
        return Jsoup.connect("https://t.me" + path).followRedirects(false)
                .timeout(10_000).maxBodySize(2 * 1024 * 1024).get();
    }

    static TelegramPublicContent loadOlder(TelegramLink link, long before) throws IOException {
        return parse(fetch("/s/" + link.channel + "?before=" + before), link);
    }

    static String playerPage(TelegramLink link) {
        if (!link.isPublicPost() || link.isComment()) return null;
        return "https://t.me/" + link.source.channel + "/" + link.source.postId + "?embed=1&single=1";
    }

    static TelegramPublicContent parse(Document doc, TelegramLink link) {
        TelegramPublicContent result = new TelegramPublicContent();
        if (!link.isPublicPost()) {
            result.title = text(doc.selectFirst(".tgme_channel_info_header_title, .tgme_page_title"));
            result.description = text(doc.selectFirst(".tgme_channel_info_description, .tgme_page_description"));
            Element avatar = doc.selectFirst(".tgme_channel_info img, .tgme_page_photo_image");
            if (avatar != null) result.image = avatar.absUrl("src");
            if (link.isInvite() || link.isBotProfile()) return result;
        }
        Map<String, Post> posts = new LinkedHashMap<>();
        List<Element> messages = doc.select(".tgme_widget_message[data-post]");
        if (!link.isPublicPost()) java.util.Collections.reverse(messages);
        for (Element message : messages) {
            String id = message.attr("data-post");
            String expectedChannel = link.isPublicPost() ? link.source.channel : link.channel;
            if (!id.matches("[A-Za-z][A-Za-z0-9_]{3,31}/[1-9][0-9]*")
                    || !id.regionMatches(true, 0, expectedChannel + "/", 0, expectedChannel.length() + 1)) continue;
            if (link.isPublicPost() && !id.endsWith("/" + link.source.postId)) continue;
            String url = "https://t.me/" + id;
            String author = text(message.selectFirst(".tgme_widget_message_owner_name"));
            Element time = message.selectFirst("time");
            String date = time == null ? "" : time.attr("datetime");
            Element captionElement = message.selectFirst(".tgme_widget_message_text");
            String caption = text(captionElement);
            Element audio = message.selectFirst(".tgme_widget_message_audio_title");
            Element document = message.selectFirst(".tgme_widget_message_document_title");
            String attachment = audio != null ? "Âm thanh · " + text(audio)
                    : document != null ? "Tài liệu · " + text(document) : "";
            List<Post> album = new ArrayList<>();
            Element selected = null;
            org.jsoup.select.Elements tiles = albumTiles(message);
            for (Element tile : tiles) {
                try {
                    TelegramLink member = TelegramLink.parse(tile.absUrl("href"));
                    if (!member.isPublicPost() || !expectedChannel.equalsIgnoreCase(member.source.channel)) continue;
                    if (id.endsWith("/" + member.source.postId)) {
                        selected = tile;
                        continue;
                    }
                    boolean video = isVideo(tile);
                    String cover = image(tile);
                    if (video || !cover.isEmpty()) {
                        album.add(new Post(member.url.toString(), author, date, "", cover, video));
                    }
                } catch (Exception ignored) {
                    // Only same-channel message links can be album members.
                }
            }
            Post main = new Post(url, author, date, caption,
                    selected == null ? image(message) : image(selected),
                    tiles.isEmpty() ? isVideo(message) : isVideo(selected));
            main.attachment = attachment;
            if (captionElement != null) addCaptionLinks(main, captionElement);
            posts.put(url, main);
            for (Post member : album) posts.putIfAbsent(member.url, member);
        }
        result.posts.addAll(posts.values());
        return result;
    }

    private static void addCaptionLinks(Post post, Element caption) {
        String raw = caption.wholeText();
        int leading = 0;
        while (leading < raw.length() && raw.charAt(leading) <= ' ') leading++;
        int trimOffset = leading;
        NodeTraversor.traverse(new NodeVisitor() {
            private int offset;
            private int linkStart;

            @Override public void head(Node node, int depth) {
                if (node instanceof TextNode) offset += ((TextNode) node).getWholeText().length();
                if ("br".equals(node.normalName())) offset++;
                if (node instanceof Element && "a".equals(node.normalName())) linkStart = offset;
            }

            @Override public void tail(Node node, int depth) {
                if (!(node instanceof Element) || !"a".equals(node.normalName())) return;
                post.addLink(Math.max(0, linkStart - trimOffset),
                        Math.min(post.text.length(), offset - trimOffset), ((Element) node).absUrl("href"));
            }
        }, caption);
    }

    private static boolean isVideo(Element element) {
        return element != null && element.selectFirst(
                "video, .tgme_widget_message_video, .tgme_widget_message_video_player, .tgme_widget_message_video_duration") != null;
    }

    private static org.jsoup.select.Elements albumTiles(Element message) {
        return message.select(".tgme_widget_message_grouped a[href], a.tgme_widget_message_grouped_layer[href]");
    }

    static String resolveVideo(String html, VideoSourceResolver.Source source) throws Exception {
        Document doc = Jsoup.parse(html, "https://t.me/");
        for (Element message : doc.select(".tgme_widget_message[data-post]")) {
            if (!(source.channel + "/" + source.postId).equalsIgnoreCase(message.attr("data-post"))) continue;
            org.jsoup.select.Elements tiles = albumTiles(message);
            if (tiles.isEmpty()) return VideoSourceResolver.extractPublicMediaUrl(message.outerHtml());
            for (Element tile : tiles) {
                try {
                    TelegramLink item = TelegramLink.parse(tile.absUrl("href"));
                    if (item.isPublicPost() && source.channel.equalsIgnoreCase(item.source.channel)
                            && source.postId.equals(item.source.postId)) {
                        return VideoSourceResolver.extractPublicMediaUrl(tile.outerHtml());
                    }
                } catch (IllegalArgumentException ignored) {
                    // The matching tile may only expose a thumbnail.
                }
            }
            throw new IOException("Không tìm thấy video trực tiếp cho mục này trong album. Hãy thử bằng tài khoản Telegram.");
        }
        if (doc.selectFirst(".tgme_widget_message_grouped, .tgme_widget_message[data-post]") != null) {
            throw new IOException("Chưa xác định được video của bài đăng Telegram này.");
        }
        return VideoSourceResolver.extractPublicMediaUrl(html);
    }

    private static String image(Element element) {
        Element photo = element.selectFirst(".tgme_widget_message_photo_wrap, .tgme_widget_message_video_thumb");
        if (photo == null) return "";
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "url\\(['\"]?(https://[^'\")]+)").matcher(photo.attr("style"));
        return match.find() ? match.group(1) : "";
    }

    private static String text(Element element) { return element == null ? "" : element.wholeText().trim(); }
}
