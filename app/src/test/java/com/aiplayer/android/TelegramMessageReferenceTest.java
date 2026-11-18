package com.aiplayer.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class TelegramMessageReferenceTest {
    @Test public void acceptsPublicPost() throws Exception {
        TelegramMessageReference ref =
                TelegramMessageReference.fromUrl("https://t.me/xdcmdj/3076");
        assertEquals("xdcmdj", ref.publicChannel);
        assertEquals(3076L, ref.messageId);
        assertEquals(3076L << 20, TelegramMessageReference.toTdlibMessageId(ref.messageId));
    }

    @Test public void refusesMessageIdOutsideTdlibInt53() {
        for (long id : new long[]{0, -1, 1L << 33, Long.MAX_VALUE}) {
            try {
                TelegramMessageReference.toTdlibMessageId(id);
                fail("Accepted out-of-range TDLib post ID");
            } catch (IllegalArgumentException expected) { /* expected */ }
        }
    }

    @Test public void rejectsPrivateOrImpersonationLinks() throws Exception {
        for (String url : new String[]{
                "https://t.me/c/123/456",
                "https://t.me.evil.invalid/xdcmdj/3076",
                "http://t.me/xdcmdj/3076",
                "https://example.org/video.mp4",
                "https://t.me/xdcmdj/999999999999999999999999"
        }) {
            try {
                TelegramMessageReference.fromUrl(url);
                fail("Accepted " + url);
            } catch (IllegalArgumentException expected) { /* expected */ }
        }
    }
}
