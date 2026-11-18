package com.aiplayer.android;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.function.Consumer;

/** Process-owned work; never retains an Activity after detach. Main thread only. */
final class VideoWork {
    private static VideoWork instance;
    final VideoSession session;
    final OfflineModels models;
    private final Context context;
    private final PlaybackSessionStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Consumer<String> observer;
    private Runnable modelObserver;
    private boolean serviceRequested;
    private long savedVersion = -1;
    private String lastStatus;
    private boolean checkpointPending;
    long position;
    private final Runnable checkpoint = this::saveCheckpoint;

    static VideoWork obtain(Context context) {
        if (instance == null) instance = new VideoWork(context.getApplicationContext());
        return instance;
    }

    static VideoWork existing() { return instance; }

    private VideoWork(Context context) {
        this.context = context;
        store = new PlaybackSessionStore(context);
        models = new OfflineModels(context, this::modelsChanged);
        session = new VideoSession(context, this::changed, models);
    }

    void attach(Consumer<String> observer) { this.observer = observer; }

    void attachModels(Runnable observer) { modelObserver = observer; }

    void detachModels(Runnable observer) {
        if (modelObserver == observer) modelObserver = null;
    }

    private void modelsChanged() {
        if (modelObserver != null) modelObserver.run();
        releaseIfIdle();
    }

    void detach(Consumer<String> owner) {
        if (observer != owner) return;
        observer = null;
        saveCheckpoint();
        releaseIfIdle();
    }

    String status() { return lastStatus; }

    private void changed(String message) {
        if (message != null) lastStatus = message;
        if (session.busy && !serviceRequested) {
            serviceRequested = true;
            try {
                context.startForegroundService(new Intent(context, VideoWorkService.class));
            } catch (RuntimeException ex) {
                serviceRequested = false;
                session.cancel();
                lastStatus = "Không thể chạy tác vụ nền. Hãy mở lại ứng dụng và thử lại.";
                saveCheckpoint();
                if (observer != null) observer.accept(lastStatus);
                return;
            }
        }
        if (session.busy) {
            if (!checkpointPending) {
                checkpointPending = true;
                main.postDelayed(checkpoint, 2_000);
            }
        } else {
            saveCheckpoint();
        }
        VideoWorkService.refresh();
        if (observer != null) observer.accept(message);
    }

    private void saveCheckpoint() {
        main.removeCallbacks(checkpoint);
        checkpointPending = false;
        if (session.videoUri() != null && savedVersion != session.transcriptVersion()) {
            savedVersion = session.transcriptVersion();
            store.checkpoint(session);
        }
        // Translation progress may be reported only every N cues. Keep checking
        // independently so successful cues are saved even between those reports.
        if (session.busy) {
            checkpointPending = true;
            main.postDelayed(checkpoint, 2_000);
        }
    }

    void serviceStopped() {
        serviceRequested = false;
        if (session.busy) {
            session.cancel();
            changed("Tác vụ đã dừng. Có thể tạo lại transcript hoặc dịch tiếp các đoạn còn thiếu.");
        }
        releaseIfIdle();
    }

    void cancelWork(String message) {
        session.cancel();
        changed(message);
    }

    private void releaseIfIdle() {
        if (instance != this || observer != null || session.busy || serviceRequested || models.pending()) return;
        main.removeCallbacksAndMessages(null);
        session.close();
        store.close();
        if (instance == this) instance = null;
    }
}
