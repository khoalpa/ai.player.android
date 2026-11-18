package com.aiplayer.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class UnavailableTelegramSessionClientTest {
    @Test public void refusesLoginAndMediaDownloadWithoutNativeBridge() {
        TelegramSessionClient client = new UnavailableTelegramSessionClient();
        assertFalse(client.isNativeAvailable());
        assertEquals(TelegramAuthorizationFlow.State.UNAVAILABLE, client.authorizationState());
        final String[] startError = {null};
        client.start(state -> assertEquals(TelegramAuthorizationFlow.State.UNAVAILABLE, state),
                error -> startError[0] = error);
        assertNotNull(startError[0]);
        final String[] error = {null};
        final String[] path = {"unexpected"};
        client.downloadMessageVideo("example", 123L, (file, failure) -> {
            path[0] = file;
            error[0] = failure;
        });
        assertNull(path[0]);
        assertNotNull(error[0]);
    }

    @Test public void clearsSensitiveCodeAndPasswordEvenWhenUnavailable() {
        TelegramSessionClient client = new UnavailableTelegramSessionClient();
        char[] otp = {'1', '2', '3'};
        char[] password = {'s', 'e', 'c', 'r', 'e', 't'};
        client.submitCode(otp, error -> assertNotNull(error));
        client.submitTwoStepPassword(password, error -> assertNotNull(error));
        for (char c : otp) assertEquals((char) 0, c);
        for (char c : password) assertEquals((char) 0, c);
    }
}
