package com.aiplayer.android;

/**
 * Boundary between the Android UI and a separately reviewed official TDLib native client.
 *
 * Every operation is user-initiated. Never persist verification codes, passwords, or
 * app credentials in logs, SharedPreferences, repository files, or analytics.
 */
interface TelegramSessionClient {
    interface Completion { void finished(String error); }
    interface MessageVideoResult { void finished(String localFilePath, String error); }
    interface Listener { void authorizationChanged(TelegramAuthorizationFlow.State state); }

    boolean isNativeAvailable();
    TelegramAuthorizationFlow.State authorizationState();
    void start(Listener listener, Completion callback);
    void submitPhone(String internationalPhone, Completion callback);
    void submitCode(char[] verificationCode, Completion callback);
    void submitTwoStepPassword(char[] password, Completion callback);
    void downloadMessageVideo(String publicChannel, long messageId, MessageVideoResult callback);
    void logoutAndClearSession(Completion callback);
}
