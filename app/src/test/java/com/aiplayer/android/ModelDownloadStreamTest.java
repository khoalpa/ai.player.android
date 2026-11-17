package com.aiplayer.android;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class ModelDownloadStreamTest {
    @Test public void countsCompressedBytesWithoutDoubleCountingReads() throws Exception {
        List<Integer> progress = new ArrayList<>();
        try (ModelDownloadStream input = new ModelDownloadStream(
                new ByteArrayInputStream(new byte[100]), 100, () -> false, progress::add)) {
            assertEquals(0, input.read());
            assertEquals(49, input.read(new byte[49]));
            assertEquals(50, input.read(new byte[50], 0, 50));
            assertEquals(-1, input.read());
        }
        assertEquals(java.util.Arrays.asList(1, 50, 99), progress);
    }

    @Test public void cancelsBeforeReadingMoreData() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (ModelDownloadStream input = new ModelDownloadStream(
                new ByteArrayInputStream(new byte[100]), -1, cancelled::get,
                percent -> fail("Unknown size must not report a percentage"))) {
            assertEquals(0, input.read());
            cancelled.set(true);
            try {
                input.read(new byte[20]);
                fail("Cancelled read must stop");
            } catch (InterruptedIOException expected) {}
            try {
                input.read();
                fail("Cancelled single-byte read must stop");
            } catch (InterruptedIOException expected) {}
        }
    }

    @Test public void progressUsesZipSizeRatherThanExpandedSize() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("model/conf/model.conf"));
            zip.write(new byte[100_000]);
            zip.closeEntry();
        }
        byte[] archive = bytes.toByteArray();
        List<Integer> progress = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ModelDownloadStream(
                new ByteArrayInputStream(archive), archive.length, () -> false, progress::add))) {
            assertNotNull(zip.getNextEntry());
            byte[] buffer = new byte[1024];
            int expanded = 0;
            int read;
            while ((read = zip.read(buffer)) >= 0) expanded += read;
            assertEquals(100_000, expanded);
        }
        assertFalse(progress.isEmpty());
        assertTrue(progress.stream().allMatch(percent -> percent >= 0 && percent <= 99));
    }
}
