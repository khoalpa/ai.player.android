package com.aiplayer.android;

/**
 * Converts a public Telegram post source to a user-requested TDLib message.
 * This does not grant access to the post; Telegram must authorize the request.
 */
final class TelegramMessageReference {
    final String publicChannel;
    final long messageId;

    private TelegramMessageReference(String publicChannel, long messageId) {
        this.publicChannel = publicChannel;
        this.messageId = messageId;
    }

    static long toTdlibMessageId(long publicPostId) {
        // TDLib channel message IDs use a 20-bit fractional component.
        if (publicPostId <= 0 || publicPostId > ((1L << 33) - 1))
            throw new IllegalArgumentException("ID bài đăng vượt phạm vi TDLib int53");
        return publicPostId << 20;
    }

    static TelegramMessageReference fromUrl(String url) throws Exception {
        VideoSourceResolver.Source source = VideoSourceResolver.classify(url);
        if (source.kind != VideoSourceResolver.Kind.TELEGRAM_PUBLIC_POST)
            throw new IllegalArgumentException("Chỉ hỗ trợ đường dẫn bài đăng kênh Telegram công khai");
        long id;
        try {
            id = Long.parseLong(source.postId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Mã bài đăng Telegram không hợp lệ", exception);
        }
        if (id <= 0) throw new IllegalArgumentException("Mã bài đăng phải lớn hơn 0");
        return new TelegramMessageReference(source.channel, id);
    }
}
