package com.aiplayer.android;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Counts compressed bytes before ZIP decompression and checks cancellation on each read. */
final class ModelDownloadStream extends FilterInputStream {
    private final long total;
    private final BooleanSupplier cancelled;
    private final IntConsumer progress;
    private long downloaded;
    private int lastPercent = -1;

    ModelDownloadStream(InputStream input, long total, BooleanSupplier cancelled, IntConsumer progress) {
        super(input);
        this.total = total;
        this.cancelled = cancelled;
        this.progress = progress;
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Model download cancelled");
        }
    }

    @Override public int read() throws IOException {
        checkCancelled();
        int value = in.read();
        if (value >= 0) report(1);
        return value;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        checkCancelled();
        int count = in.read(buffer, offset, length);
        if (count > 0) report(count);
        return count;
    }

    private void report(int count) throws IOException {
        checkCancelled();
        downloaded += count;
        if (total <= 0) return;
        int percent = (int) Math.min(99, downloaded * 100.0 / total);
        if (percent != lastPercent) {
            lastPercent = percent;
            progress.accept(percent);
        }
    }
}
