package com.aiplayer.android;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class TranscriptParserTest {
    @Test
    public void parseSrtCues() {
        String content = "1\n"
                + "00:00:01,000 --> 00:00:03,500\n"
                + "Hello world\n\n"
                + "2\n"
                + "00:00:04.000 --> 00:00:05.250\n"
                + "<i>Second line</i>\n";

        List<TranscriptCue> cues = TranscriptParser.parse(content);

        assertEquals(2, cues.size());
        assertEquals(1000, cues.get(0).startMs);
        assertEquals(3500, cues.get(0).endMs);
        assertEquals("Hello world", cues.get(0).sourceText);
        assertEquals(4000, cues.get(1).startMs);
        assertEquals(5250, cues.get(1).endMs);
        assertEquals("Second line", cues.get(1).sourceText);
    }

    @Test
    public void parseWebVttWithBomHeaderAndCueSettings() {
        String content = "\uFEFFWEBVTT\n"
                + "Kind: captions\n\n"
                + "00:00:01.000 --> 00:00:03.000 align:start position:0%\n"
                + "<c>Hello</c> from VTT\n\n";

        List<TranscriptCue> cues = TranscriptParser.parse(content);

        assertEquals(1, cues.size());
        assertEquals(1000, cues.get(0).startMs);
        assertEquals(3000, cues.get(0).endMs);
        assertEquals("Hello from VTT", cues.get(0).sourceText);
    }

    @Test
    public void parsePlainTextCreatesSequentialCues() {
        List<TranscriptCue> cues = TranscriptParser.parse("First sentence. Second sentence!");

        assertEquals(2, cues.size());
        assertEquals(0, cues.get(0).startMs);
        assertEquals(cues.get(0).endMs, cues.get(1).startMs);
        assertEquals("First sentence.", cues.get(0).sourceText);
        assertEquals("Second sentence!", cues.get(1).sourceText);
    }

    @Test
    public void parsePlainTextSplitsOnNewLines() {
        List<TranscriptCue> cues = TranscriptParser.parse("First line\nSecond line\n\nThird line");

        assertEquals(3, cues.size());
        assertEquals("First line", cues.get(0).sourceText);
        assertEquals(cues.get(0).endMs, cues.get(1).startMs);
        assertEquals("Third line", cues.get(2).sourceText);
    }

    @Test
    public void formatTimeIncludesHoursWhenNeeded() {
        assertEquals("01:01", TranscriptParser.formatTime(61_000));
        assertEquals("1:01:01", TranscriptParser.formatTime(3_661_000));
    }
}
