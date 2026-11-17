package com.aiplayer.android;

import android.os.CancellationSignal;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cancels provider requests and closes a blocked read without blocking the UI. */
final class ReadCancellation {
    private static final ExecutorService CLOSER = Executors.newCachedThreadPool();
    final CancellationSignal signal = new CancellationSignal();
    private volatile boolean cancelled;
    private Closeable input;

    boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }

    synchronized void register(Closeable stream) throws IOException {
        if (cancelled) {
            stream.close();
            throw new InterruptedIOException("Read cancelled");
        }
        input = stream;
    }

    synchronized void clear() { input = null; }

    void cancel() {
        Closeable stream;
        synchronized (this) {
            cancelled = true;
            stream = input;
            input = null;
        }
        CLOSER.execute(() -> {
            signal.cancel();
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
            }
        });
    }
}
