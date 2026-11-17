package com.aiplayer.android;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ModelArchiveTest {
    @Test public void allowsNestedModelFiles() throws IOException {
        File root = new File(System.getProperty("java.io.tmpdir"), "model.tmp");
        assertEquals(new File(root, "conf/model.conf").getCanonicalFile(),
                ModelArchive.destination(root, "conf/model.conf").getCanonicalFile());
    }

    @Test public void rejectsTraversalAndSiblingWithSamePrefix() throws IOException {
        File root = new File(System.getProperty("java.io.tmpdir"), "model.tmp");
        for (String entry : new String[]{"../outside", "../model.tmp-other/file", "..", "conf/../../file"}) {
            try {
                ModelArchive.destination(root, entry);
                fail("Accepted path outside model directory: " + entry);
            } catch (IOException expected) {
                // No filesystem writes are needed to reject the path.
            }
        }
    }
}
