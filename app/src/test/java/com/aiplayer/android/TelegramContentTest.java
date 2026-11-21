package com.aiplayer.android;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.jsoup.Jsoup;
import org.junit.Test;

public class TelegramContentTest {
    @Test public void publicPostKeepsLinksOnTheirLabels() throws Exception {
        String html = post("sample_channel/3", "<div class='tgme_widget_message_text'>  "
                + "&#128276;<br><a href='/sample_channel/4'><b>VIP ALL</b></a>"
                + " VIP ALL <a href='javascript:void(0)'>invalid</a>  </div>");
        TelegramPublicContent.Post entry = TelegramPublicContent.parse(Jsoup.parse(html, "https://t.me/"),
                TelegramLink.parse("t.me/sample_channel/3")).posts.get(0);
        assertEquals(1, entry.links.size());
        TelegramPublicContent.TextLink link = entry.links.get(0);
        assertEquals(entry.text.indexOf("VIP ALL"), link.start);
        assertEquals("VIP ALL", entry.text.substring(link.start, link.end));
        assertEquals("https://t.me/sample_channel/4", link.url);
    }

    @Test public void embeddedPlaybackTargetsTheExactPublicPost() throws Exception {
        TelegramLink link = TelegramLink.parse("t.me/sample_channel/3");
        assertEquals("https://t.me/sample_channel/3?embed=1&single=1", TelegramPublicContent.playerPage(link));
        assertNull(TelegramPublicContent.playerPage(TelegramLink.parse("t.me/c/12345/3")));
    }
    @Test public void normalizesSharedLinksAndKeepsNavigationParameters() throws Exception {
        assertEquals("https://t.me/sample_channel", TelegramLink.parse("@sample_channel").url.toString());
        assertEquals(Arrays.asList("https://t.me/sample_channel/12?comment=42", "https://t.me/+invite"),
                TelegramLink.extract("Bài đăng (https://telegram.me/sample_channel/12?comment=42), "
                        + "nhóm t.me/+invite, giả mạo https://evil.t.me/not_a_channel."));
        TelegramLink topic = TelegramLink.parse("https://t.me/c/12345/9/12");
        assertTrue(topic.isPrivatePost());
        assertEquals("/c/12345/9/12", topic.url.getPath());
        assertEquals("12", TelegramLink.parse("t.me/sample_channel/9/12").source.postId);
        assertTrue(TelegramLink.parse("t.me/sample_channel/12?comment=42").isComment());
    }

    @Test public void channelPreviewAcceptsMetadataButLeavesTelegramActionsToTelegram() throws Exception {
        TelegramLink channel = TelegramLink.parse("https://telegram.me/sample_channel?utm_source=a%26b");
        assertEquals("sample_channel", channel.channel);
        assertEquals("https://t.me/sample_channel?utm_source=a%26b", channel.source.url.toExternalForm());
        assertFalse(TelegramLink.parse("t.me/sample_channel/12?utm_source=a%26comment=42").isComment());
        assertNull(TelegramLink.parse("t.me/sample_channel?profile").channel);
        assertNull(TelegramLink.parse("t.me/sample_channel?livestream").channel);
        assertNull(TelegramLink.parse("t.me/addstickers").channel);
    }

    @Test public void normalizesSupportedDeepLinksWithoutLosingMessageOptions() throws Exception {
        assertEquals("sample_channel", TelegramLink.parse("tg://resolve?domain=sample_channel").channel);
        TelegramLink post = TelegramLink.parse(
                "tg://resolve?domain=sample_channel&post=12&single&comment=42&t=1m20s");
        assertTrue(post.isPublicPost());
        assertTrue(post.isComment());
        assertEquals("https://t.me/sample_channel/12?single&comment=42&t=1m20s",
                post.source.url.toExternalForm());
        TelegramLink privatePost = TelegramLink.parse("tg:privatepost?channel=12345&post=12&thread=9");
        assertTrue(privatePost.isPrivatePost());
        assertEquals("https://t.me/c/12345/12?thread=9", privatePost.url.toString());
        assertTrue(TelegramLink.parse("tg://join?invite=abc-def").isInvite());
        assertEquals(Arrays.asList("https://t.me/+abc-def", "https://t.me/sample_channel/12"),
                TelegramLink.extract("Mời (tg://join?invite=abc-def), bài t.me/sample_channel/12 "
                        + "tg://resolve?domain=sample_channel&post=12"));
        assertTrue(TelegramLink.extract("tg://resolve?domain=sample_channel&post=0 "
                + "tg://join?invite=one&invite=two tg://privatepost?channel=12345").isEmpty());
    }

    @Test public void botLinkKeepsStartParameterAndShowsProfileDetails() throws Exception {
        TelegramLink link = TelegramLink.parse("https://t.me/KBBFQbot?start=sample");
        assertTrue(link.isBotProfile());
        assertEquals("https://t.me/KBBFQbot?start=sample", link.source.url.toExternalForm());
        assertEquals("tg://resolve?domain=KBBFQbot&start=sample", link.botDeepLink());
        assertEquals("tg://resolve?domain=KBBFQbot", TelegramLink.parse("@KBBFQbot").botDeepLink());
        assertTrue(TelegramLink.parse("@KBBFQbot").isBotProfile());
        assertFalse(TelegramLink.parse("t.me/KBBFQbot/12").isBotProfile());
        assertFalse(TelegramLink.parse("t.me/sample_channel").isBotProfile());
        TelegramPublicContent profile = TelegramPublicContent.parse(Jsoup.parse(
                "<div class='tgme_page_title'>Bot mẫu</div>"
                        + "<div class='tgme_page_description'>Gửi mã cho bot</div>"
                        + "<img class='tgme_page_photo_image' src='https://t.me/avatar.jpg'>"), link);
        assertEquals("Bot mẫu", profile.title);
        assertEquals("Gửi mã cho bot", profile.description);
        assertEquals("https://t.me/avatar.jpg", profile.image);
        assertTrue(profile.posts.isEmpty());
    }

    @Test public void channelPreviewKeepsTextAndVideoPostsFromTheRequestedChannel() throws Exception {
        String html = "<div class='tgme_channel_info_header_title'>Kênh mẫu</div>"
                + post("sample_channel/2", "<div class='tgme_widget_message_text'>Thông báo</div>")
                + post("sample_channel/3", "<video></video>")
                + post("another_channel/4", "<video></video>");
        TelegramPublicContent channel = TelegramPublicContent.parse(Jsoup.parse(html),
                TelegramLink.parse("t.me/sample_channel"));
        assertEquals("Kênh mẫu", channel.title);
        assertEquals(2, channel.posts.size());
        assertTrue(channel.posts.get(0).video);
        assertFalse(channel.posts.get(1).video);
        assertEquals("Thông báo", channel.posts.get(1).text);
    }

    @Test public void groupedPreviewMatchesEachVideoToItsOwnMessage() throws Exception {
        String html = post("sample_channel/3", "<div class='tgme_widget_message_grouped'>"
                + "<a class='tgme_widget_message_photo_wrap' href='https://t.me/sample_channel/3'"
                + " style=\"background-image:url('https://t.me/photo.jpg')\"></a>"
                + "<a href='https://t.me/sample_channel/4'><video></video></a></div>");
        TelegramPublicContent album = TelegramPublicContent.parse(Jsoup.parse(html),
                TelegramLink.parse("t.me/sample_channel/3"));
        assertEquals(2, album.posts.size());
        assertFalse(album.posts.get(0).video);
        assertTrue(album.posts.get(1).video);
        assertEquals("https://t.me/sample_channel/4", album.posts.get(1).url);
    }

    @Test public void publicDownloadDoesNotUseAnotherVideoInTheAlbum() throws Exception {
        String html = post("sample_channel/3", "<div class='tgme_widget_message_grouped'>"
                + "<a href='https://t.me/sample_channel/3'><video src='https://t.me/selected.mp4'></video></a>"
                + "<a href='https://t.me/sample_channel/4'><video src='https://t.me/other.mp4'></video></a></div>");
        assertEquals("https://t.me/selected.mp4", TelegramPublicContent.resolveVideo(html,
                TelegramLink.parse("t.me/sample_channel/3").source));
        try {
            TelegramPublicContent.resolveVideo(html, TelegramLink.parse("t.me/sample_channel/4").source);
            fail("A preview for a different post cannot supply the download");
        } catch (java.io.IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test public void channelPreviewIncludesAlbumMediaAndKeepsAttachmentSeparateFromCaption() throws Exception {
        String html = post("sample_channel/3", "<div class='tgme_widget_message_text'>Album</div>"
                + "<div class='tgme_widget_message_grouped'>"
                + "<a class='tgme_widget_message_photo_wrap' href='https://t.me/sample_channel/3'"
                + " style=\"background-image:url('https://t.me/photo.jpg')\"></a>"
                + "<a href='https://t.me/sample_channel/4'><video></video></a></div>")
                + post("sample_channel/5", "<div class='tgme_widget_message_document_title'>doc.pdf</div>");
        TelegramPublicContent content = TelegramPublicContent.parse(Jsoup.parse(html),
                TelegramLink.parse("t.me/sample_channel"));
        assertEquals(3, content.posts.size());
        assertEquals("Tài liệu · doc.pdf", content.posts.get(0).attachment);
        assertEquals("Album", content.posts.get(1).text);
        assertEquals("https://t.me/photo.jpg", content.posts.get(1).image);
        assertTrue(content.posts.get(2).video);
    }

    private static String post(String id, String body) {
        return "<div class='tgme_widget_message' data-post='" + id + "'>" + body + "</div>";
    }
}
