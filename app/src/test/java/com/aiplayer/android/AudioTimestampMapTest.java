package com.aiplayer.android;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AudioTimestampMapTest {
    @Test public void preservesInitialOffsetAndForwardGap() {
        AudioTimestampMap map = new AudioTimestampMap();
        map.append(2_000_000, 16000, 16000);
        map.append(6_000_000, 16000, 16000);
        assertEquals(2500, map.mediaMs(500));
        assertEquals(3000, map.mediaEndMs(1000));
        assertEquals(6000, map.mediaMs(1000));
        assertEquals(6500, map.mediaMs(1500));
        assertEquals(7000, map.mediaEndMs(2000));
    }

    @Test public void followsBackwardDiscontinuity() {
        AudioTimestampMap map = new AudioTimestampMap();
        map.append(5_000_000, 48000, 48000);
        map.append(4_000_000, 48000, 48000);
        assertEquals(6000, map.mediaEndMs(1000));
        assertEquals(4000, map.mediaMs(1000));
    }

    @Test public void fractionalBuffersDoNotAccumulateRoundingDrift() {
        AudioTimestampMap map = new AudioTimestampMap();
        for (int i = 0; i < 10000; i++) {
            map.append(700_000L + i * 1024L * 1_000_000L / 44100, 1024, 44100);
        }
        assertEquals(200700, map.mediaMs(200000));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSampleRateChange() {
        AudioTimestampMap map = new AudioTimestampMap();
        map.append(0, 16000, 16000);
        map.append(1_000_000, 48000, 48000);
    }
}
