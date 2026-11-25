package com.aiplayer.android;

import org.json.JSONArray;
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
    @Test public void conservativeDraftReuseKeepsStandaloneChineseButReviewsRelationships() {
        assertTrue(ContextTranslationClient.canKeepDraft("完蛋 了", "Tiêu rồi!", Set.of(), ""));
        assertFalse(ContextTranslationClient.canKeepDraft(
                "妹夫 一直 陪 着 我 妹", "Chị dâu luôn đi cùng tôi", Set.of(), ""));
        assertFalse(ContextTranslationClient.canKeepDraft("再见", "Tạm biệt", Set.of(), "再见: mật hiệu"));
    }

    @Test public void sparseRefinementKeepsOmittedDraftsRequiresMissingCuesAndRejectsBadIds() throws Exception {
        var drafts = java.util.Map.of(4, "Tiêu rồi!", 6, "Chị dâu");
        var requested = List.of(4, 6, 8);
        var result = ContextTranslationClient.mergeDrafts(new JSONObject(
                "{\"translations\":[{\"id\":6,\"text\":\"Em rể\"}]}"), requested, drafts);
        assertEquals(Arrays.asList("Tiêu rồi!", "Em rể", null), result);
        assertEquals(List.of("Tiêu rồi!"), ContextTranslationClient.mergeDrafts(
                new JSONObject("{\"translations\":[]}"), List.of(4), drafts));
        assertThrows(IOException.class, () -> ContextTranslationClient.mergeDrafts(
                new JSONObject("{\"translations\":[{\"id\":9,\"text\":\"Sai\"}]}"), requested, drafts));
        assertThrows(IOException.class, () -> ContextTranslationClient.mergeDrafts(new JSONObject(
                "{\"translations\":[{\"id\":6,\"text\":\"Một\"},{\"id\":6,\"text\":\"Hai\"}]}"), requested, drafts));
    }
    @Test public void vietnameseQualityRejectsCopiedAndMixedScript() {
        assertFalse(VietnameseTranslationQuality.accepts("完蛋 了", "完蛋了", ""));
        assertFalse(VietnameseTranslationQuality.accepts("完蛋了", "Hỏng mất rồi了", ""));
        assertFalse(VietnameseTranslationQuality.accepts("十 秒", "最后十秒", ""));
        assertTrue(VietnameseTranslationQuality.accepts("完蛋了", "Tiêu rồi!", ""));
        assertTrue(VietnameseTranslationQuality.accepts("李雨桐", "Gặp 李雨桐.", "Li Yutong = 李雨桐"));
        assertTrue(VietnameseTranslationQuality.accepts("Qwen 3", "Qwen 3", ""));
        assertFalse(VietnameseTranslationQuality.accepts("I need to go.", "I need to go!", ""));
    }

    @Test public void contextRetryCanSelectRejectedAutomaticRowsOnly() {
        TranscriptCue valid = new TranscriptCue(0, 1000, "你好");
        valid.setTranslation("Xin chào", TranscriptCue.TranslationOrigin.AUTOMATIC);
        TranscriptCue rejected = new TranscriptCue(1000, 2000, "完蛋了");
        rejected.setTranslation("完蛋了", TranscriptCue.TranslationOrigin.AUTOMATIC);
        TranscriptCue manual = new TranscriptCue(2000, 3000, "李雨桐");
        manual.setTranslation("李雨桐", TranscriptCue.TranslationOrigin.MANUAL);
        TranslationBatch batch = new TranslationBatch(List.of(valid, rejected, manual), "vi", "vi",
                null, false, cue -> cue.translationOrigin == TranscriptCue.TranslationOrigin.AUTOMATIC
                        && !VietnameseTranslationQuality.accepts(cue.sourceText, cue.targetText, ""));
        assertEquals(List.of(rejected), batch.input);
    }
    @Test public void contextNotesMergeWithoutRepeatingEstablishedFacts() throws Exception {
        JSONArray notes = new JSONArray();
        ContextTranslationClient.mergeNotes(notes, new JSONArray(
                "[{\"id\":2,\"fact\":\"Use Lin for the family name\"}]"), 0, 4);
        ContextTranslationClient.mergeNotes(notes, new JSONArray(
                "[{\"id\":4,\"fact\":\"use  Lin for the family name\"},"
                + "{\"id\":5,\"fact\":\"Lin addresses her mother formally\"}]"), 4, 8);
        assertEquals(2, notes.length());
        assertEquals("[2] Use Lin for the family name\n[5] Lin addresses her mother formally\n",
                ContextTranslationClient.renderGuide(notes));
        ContextTranslationClient.mergeNotes(notes, new JSONArray(), 8, 12);
        assertEquals(2, notes.length());
    }

    @Test public void rejectsOutOfGroupEvidenceWithoutPartialMerge() throws Exception {
        JSONArray notes = new JSONArray();
        JSONArray updates = new JSONArray("[{\"id\":1,\"fact\":\"Valid\"},{\"id\":3,\"fact\":\"Invalid\"}]");
        assertThrows(org.json.JSONException.class,
                () -> ContextTranslationClient.mergeNotes(notes, updates, 1, 3));
        assertEquals(0, notes.length());
        assertTrue(ContextTranslationClient.guideGrammar(1, 3).endsWith("cue-id ::= \"1\" | \"2\"\n"));
    }

    @Test public void longGuideKeepsEarlyAndRecentFacts() throws Exception {
        JSONArray notes = new JSONArray();
        for (int i = 0; i < 30; i++) {
            notes.put(new JSONObject().put("id", i).put("fact", "a".repeat(160)));
        }
        String guide = ContextTranslationClient.renderGuide(notes);
        assertTrue(guide.length() <= 3000);
        assertTrue(guide.startsWith("[0]"));
        assertTrue(guide.contains("[29]"));
    }
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
