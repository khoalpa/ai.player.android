package com.aiplayer.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class TelegramAuthorizationFlowTest {
    @Test public void onlyReadyStateAllowsAuthenticatedAccess() {
        TelegramAuthorizationFlow flow = new TelegramAuthorizationFlow();
        assertFalse(flow.isAuthorized());
        for (String state : new String[]{
                "authorizationStateWaitTdlibParameters",
                "authorizationStateWaitPhoneNumber",
                "authorizationStateWaitCode",
                "authorizationStateWaitPassword",
                "authorizationStateWaitEmailCode",
                "authorizationStateWaitOtherDeviceConfirmation"
        }) {
            flow.onTdlibAuthorizationState(state);
            assertFalse(flow.isAuthorized());
        }
        flow.onTdlibAuthorizationState("authorizationStateReady");
        assertTrue(flow.isAuthorized());
        flow.onTdlibAuthorizationState("authorizationStateLoggingOut");
        assertFalse(flow.isAuthorized());
        flow.onTdlibAuthorizationState("authorizationStateClosed");
        assertFalse(flow.isAuthorized());
    }

    @Test public void unknownStateFailsClosed() {
        TelegramAuthorizationFlow flow = new TelegramAuthorizationFlow();
        flow.onTdlibAuthorizationState("authorizationStateReady");
        flow.onTdlibAuthorizationState("unknownNewAuthorizationState");
        assertEquals(TelegramAuthorizationFlow.State.ERROR, flow.state());
        assertFalse(flow.isAuthorized());
    }

    @Test public void nativeMissingFailsClosed() {
        TelegramAuthorizationFlow flow = new TelegramAuthorizationFlow();
        flow.onTdlibAuthorizationState("authorizationStateReady");
        flow.onNativeLibraryUnavailable();
        assertEquals(TelegramAuthorizationFlow.State.UNAVAILABLE, flow.state());
        assertFalse(flow.isAuthorized());
    }
}
