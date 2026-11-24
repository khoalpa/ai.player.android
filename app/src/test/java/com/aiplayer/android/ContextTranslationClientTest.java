package com.aiplayer.android;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ContextTranslationClientTest {
    @Test public void earlyDraftReviewsAddressNamesAndUncasedScripts() {
        assertTrue(ContextTranslationClient.needsContextReview("Come in.", "Mời anh vào.", Set.of()));
        assertTrue(ContextTranslationClient.needsContextReview("Meet John Smith.", "Đến đây.", Set.of()));
        assertTrue(ContextTranslationClient.needsContextReview("さようなら", "Tạm biệt.", Set.of()));
        assertTrue(ContextTranslationClient.needsContextReview("Check the relay.", "Kiểm tra bộ ngắt.", Set.of("relay")));
        assertFalse(ContextTranslationClient.needsContextReview("Rain again.", "Lại mưa rồi.", Set.of("relay")));
    }

    @Test public void seekPrioritizesTheFirstRemainingGroupAtOrAfterPlayback() {
        List<List<Integer>> groups = Arrays.asList(Arrays.asList(0, 1), Arrays.asList(2, 3));
        List<Integer> targets = Arrays.asList(0, 2, 4, 5);
        List<Long> ends = Arrays.asList(1000L, 2000L, 3000L, 4000L, 5000L, 6000L);
        assertEquals(0, ContextTranslationClient.priorityGroup(groups, targets, ends, 2000L));
        assertEquals(1, ContextTranslationClient.priorityGroup(groups, targets, ends, 3000L));
        assertEquals(0, ContextTranslationClient.priorityGroup(groups, targets, ends, 6000L));
    }

    @Test public void sparseResultsKeepTheirCueIndicesAndWaitForAllFragments() {
        assertEquals(Arrays.asList(null, "second", null), ContextTranslationClient.joinCues(
                Arrays.asList(null, "second", "third", null), Arrays.asList(1, 2, 4)));
    }

    @Test public void longCueKeepsAllTextAndDoesNotSplitSurrogatePairs() {
        String cue = String.join("", java.util.Collections.nCopies(599, "a")) + "🙂"
                + String.join("", java.util.Collections.nCopies(3000, "b"));
        List<String> fragments = ContextTranslationClient.splitCue(cue);
        assertEquals(cue, String.join("", fragments));
        assertEquals(599, fragments.get(0).length());
        assertEquals(true, fragments.get(1).startsWith("🙂"));
    }

    @Test public void progressPublishesOnlyCompleteCues() {
        List<Integer> cueEnds = Arrays.asList(1, 3);
        assertEquals(Arrays.asList("Một"), ContextTranslationClient.joinCues(Arrays.asList("Một", "Hai"), cueEnds));
        assertEquals(Arrays.asList("Một", "Hai ba"),
                ContextTranslationClient.joinCues(Arrays.asList("Một", "Hai", "ba"), cueEnds));
    }

    @Test public void groupBoundariesRespectBothLimitsAndCoverTheWholeTranscript() {
        List<String> sources = Arrays.asList("aaa", "bb", "cccc", "d", "e", "f");
        assertEquals(Arrays.asList(0, 2, 4, 6), ContextTranslationClient.groupBoundaries(sources, 2, 5));
        assertEquals(Arrays.asList(0, 2, 4, 6), ContextTranslationClient.groupBoundaries(sources, 4, 5));
    }

    @Test public void translationMustPreserveSelectedCueIdsAndOrder() throws Exception {
        List<Integer> selected = Arrays.asList(4, 72);
        JSONObject response = new JSONObject("{\"translations\":[{\"id\":4,\"text\":\"Mẹ ơi\"},"
                + "{\"id\":72,\"text\":\"Con về rồi\"}]}");
        assertEquals(Arrays.asList("Mẹ ơi", "Con về rồi"),
                ContextTranslationClient.readTranslations(response, selected));
        assertThrows(IOException.class,
                () -> ContextTranslationClient.readTranslations(response, Arrays.asList(72, 4)));
        assertThrows(IOException.class,
                () -> ContextTranslationClient.readTranslations(response, Arrays.asList(4, 4)));
        assertThrows(IOException.class,
                () -> ContextTranslationClient.readTranslations(response, Arrays.asList(4, 72, 73)));
    }
}
