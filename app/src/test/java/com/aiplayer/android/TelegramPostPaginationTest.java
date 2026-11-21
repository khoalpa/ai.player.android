package com.aiplayer.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class TelegramPostPaginationTest {
    @Test public void followsOlderPagesUntilTheyReachTheSavedPost() {
        TelegramPostPagination pages = new TelegramPostPagination(List.of(post(100), post(90)));
        assertTrue(pages.mayContain(75));

        TelegramPublicContent.Post first = post(85);
        TelegramPublicContent.Post second = post(80);
        assertEquals(List.of(first, second), pages.acceptOlder(List.of(post(90), first, second, post(85))));
        assertEquals(80, pages.before());
        assertTrue(pages.mayContain(75));

        TelegramPublicContent.Post anchor = post(75);
        assertEquals(List.of(anchor), pages.acceptOlder(List.of(anchor)));
        assertFalse(pages.mayContain(75));
    }

    @Test public void stopsLookingAfterPassingADeletedPost() {
        TelegramPostPagination pages = new TelegramPostPagination(List.of(post(90)));
        assertTrue(pages.mayContain(85));
        pages.acceptOlder(List.of(post(80)));
        assertFalse(pages.mayContain(85));
        assertTrue(pages.acceptOlder(List.of(post(80), post(90))).isEmpty());
        assertEquals(80, pages.before());
    }

    @Test public void mapsInvitePostsToTheTdlibHistoryCursor() {
        assertEquals(75L << 20, TelegramPostPagination.inviteMessageId("https://t.me/c/123456/75"));
        assertEquals(12345, TelegramPostPagination.inviteMessageId("tdlib:-123456/12345"));
        assertEquals(0, TelegramPostPagination.inviteMessageId("tdlib:-123456/missing"));
    }

    private static TelegramPublicContent.Post post(long id) {
        return new TelegramPublicContent.Post("https://t.me/testchannel/" + id, "", "", "", "", false);
    }
}
