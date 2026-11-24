package com.aiplayer.android;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ContextTranslationClientTest {
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

    @Test public void reviewMustPreserveSelectedCueIdsAndOrder() throws Exception {
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
