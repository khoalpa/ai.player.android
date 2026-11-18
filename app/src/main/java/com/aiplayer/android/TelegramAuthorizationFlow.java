package com.aiplayer.android;

/**
 * Explicit state machine for an independently authorized TDLib account.
 * No OTP, password, API hash or session token is retained in this class.
 */
final class TelegramAuthorizationFlow {
    enum State {
        UNAVAILABLE, INITIALIZING, PHONE_REQUIRED, EMAIL_REQUIRED,
        EMAIL_CODE_REQUIRED, CODE_REQUIRED, PASSWORD_REQUIRED,
        QR_CONFIRMATION_REQUIRED, REGISTRATION_REQUIRED,
        READY, LOGGING_OUT, CLOSED, ERROR
    }

    private volatile State current = State.UNAVAILABLE;

    State state() { return current; }

    boolean isAuthorized() { return current == State.READY; }

    void onTdlibAuthorizationState(String kind) {
        if (kind == null) {
            current = State.ERROR;
            return;
        }
        switch (kind) {
            case "authorizationStateWaitTdlibParameters": current = State.INITIALIZING; break;
            case "authorizationStateWaitPhoneNumber": current = State.PHONE_REQUIRED; break;
            case "authorizationStateWaitEmailAddress": current = State.EMAIL_REQUIRED; break;
            case "authorizationStateWaitEmailCode": current = State.EMAIL_CODE_REQUIRED; break;
            case "authorizationStateWaitCode": current = State.CODE_REQUIRED; break;
            case "authorizationStateWaitPassword": current = State.PASSWORD_REQUIRED; break;
            case "authorizationStateWaitOtherDeviceConfirmation": current = State.QR_CONFIRMATION_REQUIRED; break;
            case "authorizationStateWaitRegistration": current = State.REGISTRATION_REQUIRED; break;
            case "authorizationStateReady": current = State.READY; break;
            case "authorizationStateLoggingOut":
            case "authorizationStateClosing": current = State.LOGGING_OUT; break;
            case "authorizationStateClosed": current = State.CLOSED; break;
            default: current = State.ERROR; break;
        }
    }

    void onNativeLibraryUnavailable() { current = State.UNAVAILABLE; }
    void onError() { current = State.ERROR; }
}
