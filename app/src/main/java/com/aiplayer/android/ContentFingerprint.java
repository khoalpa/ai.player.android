package com.aiplayer.android;

import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

final class ContentFingerprint {
    private ContentFingerprint() {}

    static String sha256(InputStream input) throws IOException {
        return sha256(input, () -> false);
    }

    static String sha256(InputStream input, BooleanSupplier cancelled) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Hash cancelled");
                }
                int count = input.read(buffer);
                if (count == -1) break;
                digest.update(buffer, 0, count);
            }
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hash.append(Character.forDigit((value & 255) >>> 4, 16));
                hash.append(Character.forDigit(value & 15, 16));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }
    }
}
