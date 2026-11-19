package com.aiplayer.android;

/**
 * Gate for explicitly requested Telegram video downloads.
 * Only a TDLib authorizationStateReady event may allow a download.
 */
final class TelegramVideoDownloadGate {
    private final TelegramSessionClient session;

    TelegramVideoDownloadGate(TelegramSessionClient session) {
        this.session = session;
    }

    void download(String postUrl, TelegramSessionClient.MessageVideoResult callback) {
        if (!session.isNativeAvailable()) {
            callback.finished(null, "TDLib chưa được cài đặt hoặc xác minh");
            return;
        }
        if (session.authorizationState() != TelegramAuthorizationFlow.State.READY) {
            callback.finished(null, "Bạn cần đăng nhập Telegram để tải video");
            return;
        }
        TelegramMessageReference reference;
        try {
            VideoSourceResolver.Source source = VideoSourceResolver.classify(postUrl);
            if (source.kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST) {
                session.downloadPrivateMessageVideo(source.url.toExternalForm(), callback);
                return;
            }
            reference = TelegramMessageReference.fromUrl(postUrl);
        } catch (Exception exception) {
            callback.finished(null, exception.getMessage());
            return;
        }
        session.downloadMessageVideo(reference.publicChannel, reference.messageId, callback);
    }
}
