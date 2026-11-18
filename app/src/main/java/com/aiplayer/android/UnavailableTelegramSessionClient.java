package com.aiplayer.android;

import java.util.Arrays;

/**
 * Fail-closed default. Replaced only by a separately verified TDLib JNI implementation.
 * Never claims authentication succeeded without a TDLib authorizationStateReady event.
 */
final class UnavailableTelegramSessionClient implements TelegramSessionClient {
    private static final String ERROR = "TDLib native chưa được cài đặt hoặc xác minh";
    private final TelegramAuthorizationFlow flow = new TelegramAuthorizationFlow();

    @Override public boolean isNativeAvailable() { return false; }
    @Override public TelegramAuthorizationFlow.State authorizationState() { return flow.state(); }

    @Override public void start(Listener listener, Completion callback) {
        listener.authorizationChanged(flow.state());
        callback.finished(ERROR);
    }

    @Override public void submitPhone(String phone, Completion callback) { callback.finished(ERROR); }

    @Override public void submitCode(char[] code, Completion callback) {
        if (code != null) Arrays.fill(code, '\\0');
        callback.finished(ERROR);
    }

    @Override public void submitTwoStepPassword(char[] password, Completion callback) {
        if (password != null) Arrays.fill(password, '\\0');
        callback.finished(ERROR);
    }

    @Override public void downloadMessageVideo(String channel, long messageId, MessageVideoResult callback) {
        callback.finished(null, ERROR);
    }

    @Override public void logoutAndClearSession(Completion callback) {
        callback.finished(null); // No native session exists in this implementation.
    }
}
