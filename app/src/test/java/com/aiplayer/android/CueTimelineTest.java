package com.aiplayer.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;

import org.junit.Test;

public class CueTimelineTest {
    @Test
    public void resolvesCueAtBoundariesAndAfterSeekingBackward() {
        TranscriptCue first = new TranscriptCue(100, 200, "first");
        TranscriptCue second = new TranscriptCue(200, 300, "second");
        CueTimeline timeline = new CueTimeline(Arrays.asList(first, second));

        assertNull(timeline.at(99));
        assertSame(second, timeline.at(200));
        assertNull(timeline.at(300));
        assertSame(first, timeline.at(100));
    }

    @Test
    public void resolvesOverlappingCuesAndGaps() {
        TranscriptCue outer = new TranscriptCue(0, 500, "outer");
        TranscriptCue inner = new TranscriptCue(100, 200, "inner");
        TranscriptCue next = new TranscriptCue(600, 700, "next");
        CueTimeline timeline = new CueTimeline(Arrays.asList(outer, inner, next));

        assertSame(inner, timeline.at(150));
        assertSame(outer, timeline.at(200));
        assertNull(timeline.at(550));
        assertSame(next, timeline.at(600));
    }

    @Test
    public void translationModeFallsBackToSourceWhenTranslationIsMissing() {
        TranscriptCue cue = new TranscriptCue(0, 100, "source");
        assertEquals("source", CueTimeline.Mode.TRANSLATION.text(cue));
        cue.targetText = null;
        assertEquals("source", CueTimeline.Mode.TRANSLATION.text(cue));
        cue.targetText = "translated";
        assertEquals("translated", CueTimeline.Mode.TRANSLATION.text(cue));
        assertEquals("source", CueTimeline.Mode.SOURCE.text(cue));
        assertEquals("", CueTimeline.Mode.OFF.text(cue));
    }
}
