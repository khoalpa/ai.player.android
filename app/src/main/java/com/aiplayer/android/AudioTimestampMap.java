package com.aiplayer.android;

import java.util.ArrayList;
import java.util.List;

/** Maps Vosk's concatenated PCM clock to the decoder's media clock. */
final class AudioTimestampMap {
    private final List<Long> starts = new ArrayList<>();
    private final List<Long> offsets = new ArrayList<>();
    private long frames;
    private int rate;

    void append(long presentationUs, int frameCount, int sampleRate) {
        if (sampleRate <= 0 || frameCount < 0) throw new IllegalArgumentException("Invalid PCM");
        if (rate != 0 && rate != sampleRate) throw new IllegalArgumentException("PCM rate changed");
        rate = sampleRate;
        if (frameCount == 0) return;
        long startUs = frames * 1_000_000L / rate;
        long offset = presentationUs - startUs;
        if (offsets.isEmpty() || Math.abs(offset - offsets.get(offsets.size() - 1)) > 2_000L) {
            starts.add(startUs);
            offsets.add(offset);
        }
        frames += frameCount;
    }

    long mediaMs(long pcmMs) {
        return mapUs(pcmMs * 1000L) / 1000L;
    }

    long mediaEndMs(long pcmMs) {
        // An exclusive end at a discontinuity belongs to the preceding buffer.
        long us = pcmMs * 1000L;
        return (mapUs(Math.max(0, us - 1)) + (us > 0 ? 1 : 0)) / 1000L;
    }

    private long mapUs(long pcmUs) {
        int low = 0, high = starts.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (starts.get(mid) <= pcmUs) low = mid + 1;
            else high = mid;
        }
        return Math.max(0, pcmUs + (offsets.isEmpty() ? 0 : offsets.get(Math.max(0, low - 1))));
    }
}
