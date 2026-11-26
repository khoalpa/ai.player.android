package com.aiplayer.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Process-owned work; never retains an Activity after detach. Main thread only. */
final class VideoWork {
    private static VideoWork instance;
    final VideoSession session;
    final OfflineModels models;
    private final Context context;
    private final PlaybackSessionStore store;
    private final SharedPreferences homeTasks;
    private final List<Result> unreadResults = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Consumer<String> observer;
    private Runnable modelObserver;
    private boolean serviceRequested;
    private long savedVersion = -1;
    private String lastStatus;
    private boolean checkpointPending;
    private int pendingStoppedCheckpoints;
    private Uri transcriptMutation;
    long position;
    boolean playbackHistoryCleared;
    // Retained with the current video across Activity recreation. -1 means no pending entry.
    int pendingRecentGrantFlags = -1;
    private final Runnable checkpoint = this::saveCheckpoint;

    static final class Result {
        final Uri video;
        final String name;
        final VideoSession.Task task;
        final String identity;
        final long finishedAt;
        final boolean stopped;
        int completed;
        int total;

        Result(Uri video, String name, VideoSession.Task task, String identity,
               long finishedAt, boolean stopped, int completed, int total) {
            this.video = video;
            this.name = name;
            this.task = task;
            this.identity = identity;
            this.finishedAt = finishedAt;
            this.stopped = stopped;
            this.completed = completed;
            this.total = total;
        }

        String countLabel() {
            if (completed <= 0) return null;
            return stopped ? "Đã lưu " + completed + "/" + total + " câu" : completed + " câu";
        }

        String translationStatus() {
            String count = countLabel();
            String title = stopped ? "Đã dừng" : "Đã dịch xong";
            return count == null ? title : title + " · " + count;
        }
    }

    static VideoWork obtain(Context context) {
        if (instance == null) instance = new VideoWork(context.getApplicationContext());
        return instance;
    }

    static VideoWork existing() { return instance; }

    private VideoWork(Context context) {
        this.context = context;
        store = new PlaybackSessionStore(context);
        homeTasks = context.getSharedPreferences("home_tasks", Context.MODE_PRIVATE);
        try {
            JSONArray saved = new JSONArray(homeTasks.getString("unread", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.getJSONObject(i);
                unreadResults.add(new Result(Uri.parse(item.getString("video")), item.optString("name", null),
                        VideoSession.Task.valueOf(item.getString("task")), item.optString("identity", null),
                        item.optLong("finishedAt"), item.optBoolean("stopped"),
                        item.optInt("completed"), item.optInt("total")));
            }
        } catch (JSONException | IllegalArgumentException ignored) {
            // Keep any results that could be read before the invalid entry.
        }
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

    List<Result> unreadResults() { return new ArrayList<>(unreadResults); }

    boolean isResultUnread(Result result) { return unreadResults.contains(result); }

    void markResultViewed(Result result) {
        if (unreadResults.remove(result)) saveResults();
    }

    void discardResults(Uri video, boolean translationOnly) {
        if (unreadResults.removeIf(result -> result.video.equals(video)
                && (!translationOnly || result.task == VideoSession.Task.TRANSLATION))) {
            saveResults();
        }
    }

    Uri transcriptMutation() { return transcriptMutation; }

    void editTranscriptCue(PlaybackSessionStore.Snapshot snapshot, int index, TranscriptCue edited,
                           String target, Consumer<PlaybackSessionStore.Snapshot> completed) {
        transcriptMutation = snapshot.uri;
        store.editTranscriptCue(snapshot, index, edited, target, updated -> {
            if (updated != null && updated.uri.equals(session.videoUri())
                    && updated.identity.equals(session.identity())) {
                session.applyEditedTranscript(updated);
            }
            transcriptMutation = null;
            completed.accept(updated);
            releaseIfIdle();
        });
    }

    void removeTranscript(PlaybackSessionStore.StoredTranscript entry, boolean translationOnly,
                          String sourceLanguage, Consumer<PlaybackSessionStore.Snapshot> completed) {
        Uri uri = entry.snapshot.uri;
        transcriptMutation = uri;
        store.removeTranscript(entry, translationOnly, updated -> {
            Runnable finish = () -> {
                if (updated != null) {
                    discardResults(uri, translationOnly);
                    if (uri.equals(session.videoUri())) session.open(uri, sourceLanguage, updated);
                }
                transcriptMutation = null;
                completed.accept(updated);
                releaseIfIdle();
            };
            if (updated != null && !translationOnly) {
                session.removeCachedTranscript(updated.identity, entry.snapshot.source, finish);
            } else {
                finish.run();
            }
        });
    }

    private void saveResults() {
        JSONArray saved = new JSONArray();
        try {
            for (Result result : unreadResults) {
                saved.put(new JSONObject().put("video", result.video.toString())
                        .put("name", result.name).put("task", result.task.name()).put("identity", result.identity)
                        .put("finishedAt", result.finishedAt).put("stopped", result.stopped)
                        .put("completed", result.completed).put("total", result.total));
            }
        } catch (JSONException ignored) {
            return;
        }
        homeTasks.edit().putString("unread", saved.toString()).apply();
    }

    private void addResult(Result result) {
        unreadResults.removeIf(previous -> previous.video.equals(result.video)
                && (previous.task == result.task || result.task == VideoSession.Task.TRANSCRIPT));
        unreadResults.add(0, result);
        saveResults();
    }

    private void changed(String message) {
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
        if (session.finishedTask != null) {
            int count = session.completedCues().size();
            Result result = new Result(session.videoUri(), session.videoName, session.finishedTask,
                    session.identity(), System.currentTimeMillis(), false, count, count);
            session.finishedTask = null;
            addResult(result);
            if (result.task == VideoSession.Task.TRANSLATION) message = result.translationStatus();
        }
        if (message != null) lastStatus = message;
        VideoWorkService.refresh();
        if (observer != null) observer.accept(message);
    }

    private void saveCheckpoint() {
        main.removeCallbacks(checkpoint);
        checkpointPending = false;
        if (session.videoUri() != null && !session.videoUri().equals(transcriptMutation)
                && savedVersion != session.transcriptVersion()) {
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
            cancelWork("Tác vụ đã dừng. Có thể tạo lại transcript hoặc dịch tiếp các đoạn còn thiếu.");
        }
        releaseIfIdle();
    }

    void cancelWork(String message) {
        VideoSession.Task task = session.runningTask;
        Uri video = session.videoUri();
        String target = session.runningTranslationTarget();
        session.cancel();
        if (task != null && video != null) {
            Result result = new Result(video, session.videoName, task, session.identity(),
                    System.currentTimeMillis(), true, 0, 0);
            addResult(result);
            if (task == VideoSession.Task.TRANSLATION) message = result.translationStatus();
            if (task == VideoSession.Task.TRANSLATION && target != null && result.identity != null) {
                savedVersion = session.transcriptVersion();
                pendingStoppedCheckpoints++;
                store.save(video, result.identity, 0, session, false, snapshot -> {
                    pendingStoppedCheckpoints--;
                    if (isResultUnread(result) && snapshot != null && target.equals(snapshot.target)) {
                        result.total = snapshot.cues.size();
                        result.completed = (int) snapshot.cues.stream().filter(TranscriptCue::hasTranslation).count();
                        saveResults();
                        if (!session.busy && "Đã dừng".equals(lastStatus)
                                && unreadResults.get(0) == result
                                && video.equals(session.videoUri()) && result.completed > 0) {
                            lastStatus = result.translationStatus();
                            if (observer != null) observer.accept(lastStatus);
                        } else if (observer != null) {
                            observer.accept(null);
                        }
                    }
                    releaseIfIdle();
                });
            }
        }
        changed(message);
    }

    private void releaseIfIdle() {
        if (instance != this || observer != null || session.busy || serviceRequested
                || models.pending() || transcriptMutation != null || pendingStoppedCheckpoints > 0) return;
        main.removeCallbacksAndMessages(null);
        session.close();
        store.close();
        if (instance == this) instance = null;
    }
}
