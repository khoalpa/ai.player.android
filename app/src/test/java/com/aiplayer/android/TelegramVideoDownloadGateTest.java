package com.aiplayer.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class TelegramVideoDownloadGateTest {
    static final class FakeSession implements TelegramSessionClient {
        boolean nativeLoaded;
        TelegramAuthorizationFlow.State state = TelegramAuthorizationFlow.State.UNAVAILABLE;
        String requestedChannel;
        String requestedPrivateLink;
        long requestedId;
        @Override public boolean isNativeAvailable() { return nativeLoaded; }
        @Override public TelegramAuthorizationFlow.State authorizationState() { return state; }
        @Override public void start(Listener listener, Completion callback) { callback.finished("unsupported"); }
        @Override public void submitPhone(String phone, Completion callback) { callback.finished("unsupported"); }
        @Override public void submitCode(char[] code, Completion callback) { callback.finished("unsupported"); }
        @Override public void submitTwoStepPassword(char[] password, Completion callback) { callback.finished("unsupported"); }
        @Override public void downloadMessageVideo(String channel, long id, MessageVideoResult callback) {
            requestedChannel = channel;
            requestedId = id;
            callback.finished("/private/video.mp4", null);
        }
        @Override public void logoutAndClearSession(Completion callback) { callback.finished(null); }
        @Override public void downloadPrivateMessageVideo(String postUrl, MessageVideoResult callback) {
            requestedPrivateLink = postUrl;
            callback.finished("/private/video.mp4", null);
        }
    }

    @Test public void refusesMissingNativeOrUnauthorizedSession() {
        FakeSession session = new FakeSession();
        TelegramVideoDownloadGate gate = new TelegramVideoDownloadGate(session);
        String[] error = {null};
        gate.download("https://t.me/xdcmdj/3076", (file, failure) -> {
            assertNull(file);
            error[0] = failure;
        });
        assertNotNull(error[0]);
        assertNull(session.requestedChannel);
        session.nativeLoaded = true;
        session.state = TelegramAuthorizationFlow.State.CODE_REQUIRED;
        gate.download("https://t.me/xdcmdj/3076", (file, failure) -> {
            assertNull(file);
            error[0] = failure;
        });
        assertNotNull(error[0]);
        assertNull(session.requestedChannel);
    }

    @Test public void onlyRoutesAuthorizedExplicitPublicMessage() {
        FakeSession session = new FakeSession();
        session.nativeLoaded = true;
        session.state = TelegramAuthorizationFlow.State.READY;
        TelegramVideoDownloadGate gate = new TelegramVideoDownloadGate(session);
        gate.download("https://t.me/xdcmdj/3076", (file, error) -> {
            assertNull(error);
            assertEquals("/private/video.mp4", file);
        });
        assertEquals("xdcmdj", session.requestedChannel);
        assertEquals(3076L, session.requestedId);
    }

    @Test public void routesPrivateLinksOnlyAfterAuthorization() {
        FakeSession session = new FakeSession();
        session.nativeLoaded = true;
        session.state = TelegramAuthorizationFlow.State.CODE_REQUIRED;
        TelegramVideoDownloadGate gate = new TelegramVideoDownloadGate(session);
        gate.download("t.me/c/123/456", (file, error) -> {
            assertNull(file);
            assertNotNull(error);
        });
        assertNull(session.requestedPrivateLink);
        session.state = TelegramAuthorizationFlow.State.READY;
        gate.download("t.me/c/123/456", (file, error) -> {
            assertNull(error);
            assertEquals("/private/video.mp4", file);
        });
        assertEquals("https://t.me/c/123/456", session.requestedPrivateLink);
        assertNull(session.requestedChannel);
    }
}
