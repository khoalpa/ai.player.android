package com.aiplayer.android;

import java.io.ByteArrayInputStream;
import java.io.InterruptedIOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContentFingerprintTest {
    @Test public void hashesAllContentIncludingTailChanges() throws Exception {
        byte[] original = new byte[200000];
        String first = ContentFingerprint.sha256(new ByteArrayInputStream(original));
        assertEquals(first, ContentFingerprint.sha256(new ByteArrayInputStream(original)));
        original[original.length - 1] = 1;
        assertNotEquals(first, ContentFingerprint.sha256(new ByteArrayInputStream(original)));
    }

    @Test public void matchesKnownDigest() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ContentFingerprint.sha256(new ByteArrayInputStream(new byte[0])));
    }

    @Test public void stopsWhenInterrupted() throws Exception {
        Thread.currentThread().interrupt();
        try {
            ContentFingerprint.sha256(new ByteArrayInputStream(new byte[100]));
            fail("Expected cancellation");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void cancelsBetweenReadsWithoutInterruptingWorker() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[200000]) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                int count = super.read(buffer, offset, length);
                cancelled.set(true);
                return count;
            }
        };
        try {
            ContentFingerprint.sha256(input, cancelled::get);
            fail("Expected cancellation before hashing the remaining data");
        } catch (InterruptedIOException expected) {
            assertTrue(input.available() > 0);
            assertFalse(Thread.currentThread().isInterrupted());
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                ContentFingerprint.sha256(new ByteArrayInputStream(new byte[0])));
    }
}
