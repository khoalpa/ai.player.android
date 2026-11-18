package com.aiplayer.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class SharedVideoImportPolicyTest {
    @Test public void acceptsKnownVideoMimeTypes() {
        assertEquals(".mp4", SharedVideoImportPolicy.extension("video/mp4"));
        assertEquals(".webm", SharedVideoImportPolicy.extension("video/webm"));
        assertEquals(".mkv", SharedVideoImportPolicy.extension("video/x-matroska"));
        assertEquals(".mp4", SharedVideoImportPolicy.extension("VIDEO/MP4; charset=binary"));
    }

    @Test public void rejectsUnknownAndNonVideoMimeTypes() {
        for (String type : new String[]{"text/plain", "image/jpeg", "video/unknown", ""}) {
            try {
                SharedVideoImportPolicy.extension(type);
                fail("Accepted " + type);
            } catch (IllegalArgumentException expected) { /* expected */ }
        }
        try {
            SharedVideoImportPolicy.extension(null);
            fail("Accepted null");
        } catch (IllegalArgumentException expected) { /* expected */ }
    }
}
