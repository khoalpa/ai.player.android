package com.aiplayer.android;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class YoutubeCaptionParserTest {
    @Test
    public void parsesTimedTextWithEntitiesAndLineBreaks() {
        String xml = "<transcript>"
                + "<text start=\"1.5\" dur=\"2\">Tom &amp; Jerry<br/>Line &#39;two&#39;</text>"
                + "<text start=\"5\">Default duration</text>"
                + "</transcript>";

        List<TranscriptCue> cues = YoutubeCaptionParser.parseTimedText(xml);

        assertEquals(2, cues.size());
        assertEquals(1500, cues.get(0).startMs);
        assertEquals(3500, cues.get(0).endMs);
        assertEquals("Tom & Jerry\nLine 'two'", cues.get(0).sourceText);
        assertEquals(5000, cues.get(1).startMs);
        assertEquals(8000, cues.get(1).endMs);
    }

    @Test
    public void skipsInvalidOrEmptyCaptionNodes() {
        String xml = "<transcript>"
                + "<text dur=\"2\">No start</text>"
                + "<text start=\"3\" dur=\"1\">   </text>"
                + "<text start=\"4\" dur=\"1\">Valid</text>"
                + "</transcript>";

        List<TranscriptCue> cues = YoutubeCaptionParser.parseTimedText(xml);

        assertEquals(1, cues.size());
        assertEquals("Valid", cues.get(0).sourceText);
    }
}
