package com.aiplayer.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.util.AtomicFile;
import android.database.Cursor;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class PlaybackSessionStore {
    private static final int RECENT_LIMIT = 3;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Object FILE_LOCK = new Object();
    private static final LruCache<Uri, RecentSummary> RECENT_SUMMARIES = new LruCache<>(RECENT_LIMIT);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private Future<?> pendingLoad;
    private ReadCancellation cancellation;
    static final class Snapshot {
        final Uri uri;
        String durationRevision;
        final String identity;
        final long position;
        final String source;
        final String target;
        final List<TranscriptCue> cues;
        String name = "Video";
        String transcriptOrigin;
        boolean suppressCache;
        VideoSession.Task failedTask;
        String failedTarget;
        boolean contextualTranslation;
        String translationGlossary = "";

        Snapshot(Uri uri, String identity, long position, String source, String target, List<TranscriptCue> cues) {
            this.uri = uri;
            this.identity = identity;
            this.position = position;
            this.source = source;
            this.target = target;
            this.cues = cues;
        }
    }

    static final class RecentVideo {
        final Uri uri;
        final String name;

        RecentVideo(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private final Context context;
    private final SharedPreferences preferences;

    PlaybackSessionStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("playback_session", Context.MODE_PRIVATE);
    }

    private SharedPreferences videoPreferences(Uri uri) {
        String key = UUID.nameUUIDFromBytes(uri.toString().getBytes(StandardCharsets.UTF_8)).toString();
        return context.getSharedPreferences("playback_video_" + key, Context.MODE_PRIVATE);
    }

    private AtomicFile transcriptFile(Uri uri) {
        String key = UUID.nameUUIDFromBytes(uri.toString().getBytes(StandardCharsets.UTF_8)).toString();
        return new AtomicFile(new File(new File(context.getFilesDir(), "playback_transcripts"), key + ".json"));
    }

    void cancelLoad() {
        if (cancellation != null) cancellation.cancel();
        if (pendingLoad != null) pendingLoad.cancel(true);
    }

    void close() {
        cancelLoad();
        reads.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    void load(Uri uri, Consumer<Snapshot> callback) {
        cancelLoad();
        ReadCancellation request = new ReadCancellation();
        cancellation = request;
        Future<?> writes = IO.submit(() -> {});
        pendingLoad = reads.submit(() -> {
            try {
                writes.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException ignored) {
                // Use the last available snapshot.
            }
            if (request.isCancelled()) return;
            Snapshot restored = null;
            if (uri != null) {
                String metadata = TranscriptCache.metadataIdentity(context, uri, request.signal);
                String revision = "file".equals(uri.getScheme()) ? fileDurationRevision(uri) : metadata;
                if (request.isCancelled()) return;
                String identity = metadata != null ? metadata
                        : TranscriptCache.contentIdentity(context, uri, request);
                if (request.isCancelled()) return;
                SharedPreferences saved = videoPreferences(uri);
                restored = identity != null
                        ? readSnapshot(uri, identity, saved)
                        : new Snapshot(uri, identity, 0, null, null, new ArrayList<>());
                restored.name = displayName(context, uri, request.signal);
                restored.durationRevision = revision;
            }
            Snapshot result = restored;
            main.post(() -> { if (!request.isCancelled()) callback.accept(result); });
        });
    }

    private Snapshot readSnapshot(Uri uri, String identity, SharedPreferences preferences) {
        synchronized (FILE_LOCK) { return readSnapshotLocked(uri, identity, preferences); }
    }

    private static final class RecentSummary {
        final RecentVideoStatus.Summary counts;
        final VideoSession.Task failedTask;
        final String failedTarget;

        RecentSummary(Snapshot snapshot) {
            counts = new RecentVideoStatus.Summary(snapshot.cues, snapshot.target);
            failedTask = snapshot.failedTask;
            failedTarget = snapshot.failedTarget;
        }
    }

    // Called by the home screen's background worker. Older saves are summarized on first access.
    RecentVideoStatus savedStatus(Uri uri, String target) {
        synchronized (FILE_LOCK) {
            RecentSummary summary = RECENT_SUMMARIES.get(uri);
            if (summary == null) {
                SharedPreferences saved = videoPreferences(uri);
                String identity = saved.getString("identity", null);
                if (identity == null) return RecentVideoStatus.from(null, 0, 0);
                summary = new RecentSummary(readSnapshotLocked(uri, identity, saved));
                RECENT_SUMMARIES.put(uri, summary);
            }
            return summary.counts.statusFor(target, summary.failedTask, summary.failedTarget);
        }
    }

    private Snapshot readSnapshotLocked(Uri uri, String identity, SharedPreferences preferences) {
        List<TranscriptCue> cues = new ArrayList<>();
        boolean sameIdentity = identity.equals(preferences.getString("identity", null));
        String source = sameIdentity ? preferences.getString("source", null) : null;
        String target = sameIdentity ? preferences.getString("target", null) : null;
        String transcriptOrigin = null;
        boolean suppressCache = false;
        String failedTarget = null;
        VideoSession.Task failedTask = null;
        boolean contextualTranslation = false;
        String translationGlossary = "";
        try {
            String json = sameIdentity ? preferences.getString("cues", "[]") : "[]";
            AtomicFile file = transcriptFile(uri);
            if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists()) {
                JSONObject saved = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
                if (identity.equals(saved.optString("identity"))) {
                    json = saved.getJSONArray("cues").toString();
                    transcriptOrigin = saved.optString("transcript_origin", null);
                    suppressCache = saved.optBoolean("transcript_removed");
                    source = saved.isNull("source") ? null : saved.optString("source", null);
                    target = saved.isNull("target") ? null : saved.optString("target", null);
                    failedTarget = saved.optString("failed_target", null);
                    contextualTranslation = saved.optBoolean("contextual_translation");
                    translationGlossary = saved.optString("translation_glossary", "");
                    String task = saved.optString("failed_task");
                    if (VideoSession.Task.TRANSCRIPT.name().equals(task)) failedTask = VideoSession.Task.TRANSCRIPT;
                    if (VideoSession.Task.TRANSLATION.name().equals(task)) failedTask = VideoSession.Task.TRANSLATION;
                } else json = "[]";
            }
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                TranscriptCue cue = new TranscriptCue(row.getLong("start"), row.getLong("end"),
                        row.getString("source"));
                cue.wordStarts = row.optJSONArray("word_starts");
                cue.sentence = row.optBoolean("sentence");
                cue.sourceEdited = row.optBoolean("source_edited");
                String text = row.optString("target", "");
                String origin = row.optString("translation_origin");
                cue.setTranslation(text, "MANUAL".equals(origin) ? TranscriptCue.TranslationOrigin.MANUAL
                        : "ORIGINAL".equals(origin) ? TranscriptCue.TranslationOrigin.ORIGINAL
                        : TranscriptCue.TranslationOrigin.AUTOMATIC);
                cue.translationNeedsReview = row.optBoolean("translation_needs_review") && cue.hasTranslation();
                        cue.translatedDirectly = row.optBoolean("translated_directly") && cue.hasTranslation();
                cue.translationPending = row.optBoolean("translation_pending");
                cues.add(cue);
            }
        } catch (Exception ex) {
            Log.w("PlaybackSessionStore", "Could not restore transcript", ex);
            cues.clear();
        }
        if (cues.stream().anyMatch(cue -> !cue.sentence)) {
            AppSettings settings = AppSettings.load(context);
            String configuredTarget = settings.targetLanguage;
            SentenceTranscriptEdits edits = new SentenceTranscriptEdits(
                    context.getSharedPreferences(SentenceTranscriptEdits.PREFERENCES, Context.MODE_PRIVATE));
            cues = edits.migrate(uri.toString(), cues, source == null ? settings.sourceLanguage : source,
                    target, configuredTarget, true);
            if (target == null && cues.stream().anyMatch(TranscriptCue::hasTranslation)) {
                target = configuredTarget;
            }
        }
        Snapshot result = new Snapshot(uri, identity, sameIdentity ? preferences.getLong("position", 0) : 0,
                cues.isEmpty() ? null : source, cues.isEmpty() ? null : target, cues);
        result.transcriptOrigin = cues.isEmpty() ? null : transcriptOrigin;
        result.suppressCache = suppressCache;
        result.failedTask = failedTask;
        result.failedTarget = failedTarget;
        result.contextualTranslation = contextualTranslation;
        result.translationGlossary = translationGlossary;
        return result;
    }

    static final class StoredTranscript {
        final Snapshot snapshot;
        final long bytes;
        final long sentenceTranslationBytes;
        final boolean translated;

        StoredTranscript(Snapshot snapshot, long bytes, long sentenceTranslationBytes) {
            this.snapshot = snapshot;
            this.bytes = bytes + sentenceTranslationBytes;
            this.sentenceTranslationBytes = sentenceTranslationBytes;
            translated = sentenceTranslationBytes > 0 || snapshot.cues.stream().anyMatch(TranscriptCue::hasTranslation);
        }
    }

    void loadStoredTranscript(Snapshot requested, Consumer<Snapshot> callback) {
        IO.execute(() -> {
            Snapshot snapshot;
            synchronized (FILE_LOCK) {
                snapshot = readSnapshotLocked(requested.uri, requested.identity, videoPreferences(requested.uri));
                snapshot.name = requested.name;
            }
            main.post(() -> callback.accept(snapshot));
        });
    }

    void inspect(Consumer<List<StoredTranscript>> callback) {
        IO.execute(() -> {
            List<StoredTranscript> result = new ArrayList<>();
            File[] files = videoPreferenceFiles();
            List<RecentVideo> recent = recentVideos();
            if (files != null) synchronized (FILE_LOCK) {
                java.util.Map<String, Long> sentenceTranslations = new java.util.HashMap<>();
                SharedPreferences sentences = context.getSharedPreferences(
                        SentenceTranscriptEdits.PREFERENCES, Context.MODE_PRIVATE);
                for (java.util.Map.Entry<String, ?> entry : sentences.getAll().entrySet()) {
                    String key = entry.getKey();
                    int separator = key.indexOf('\n');
                    if (separator >= 0 && key.indexOf('\n', separator + 1) >= 0
                            && entry.getValue() instanceof String && !((String) entry.getValue()).trim().isEmpty()) {
                        long bytes = ((String) entry.getValue()).getBytes(StandardCharsets.UTF_8).length;
                        sentenceTranslations.merge(key.substring(0, separator), bytes, Long::sum);
                    }
                }
                for (File file : files) {
                    String filename = file.getName();
                    SharedPreferences saved = context.getSharedPreferences(
                            filename.substring(0, filename.length() - 4), Context.MODE_PRIVATE);
                    String identity = saved.getString("identity", "");
                    int separator = identity.indexOf('\n');
                    if (separator <= 0) continue;
                    Uri uri = Uri.parse(identity.substring(0, separator));
                    Snapshot snapshot = readSnapshotLocked(uri, identity, saved);
                    if (snapshot.cues.isEmpty()) continue;
                    snapshot.name = saved.getString("name", fallbackName(uri));
                    for (RecentVideo video : recent) {
                        if (uri.equals(video.uri) && video.name != null) snapshot.name = video.name;
                    }
                    long bytes = transcriptFile(uri).getBaseFile().length();
                    if (bytes == 0) bytes = saved.getString("cues", "").getBytes(StandardCharsets.UTF_8).length;
                    result.add(new StoredTranscript(snapshot, bytes,
                            sentenceTranslations.getOrDefault(uri.toString(), 0L)));
                }
            }
            result.sort(java.util.Comparator.comparingLong((StoredTranscript entry) -> entry.bytes).reversed());
            main.post(() -> callback.accept(result));
        });
    }

    void removeTranscript(StoredTranscript entry, boolean translationOnly, Consumer<Snapshot> callback) {
        IO.execute(() -> {
            Snapshot result = null;
            synchronized (FILE_LOCK) {
                Snapshot requested = entry.snapshot;
                SharedPreferences saved = videoPreferences(requested.uri);
                if (requested.identity.equals(saved.getString("identity", null))) {
                    Snapshot current = readSnapshotLocked(requested.uri, requested.identity, saved);
                    if (translationOnly) {
                        for (TranscriptCue cue : current.cues) {
                            cue.setTranslation("", TranscriptCue.TranslationOrigin.AUTOMATIC);
                        }
                    } else {
                        current.cues.clear();
                    }
                    Snapshot removed = new Snapshot(current.uri, current.identity, current.position,
                            translationOnly ? current.source : null, null, current.cues);
                    removed.transcriptOrigin = translationOnly ? current.transcriptOrigin : null;
                    removed.suppressCache = !translationOnly;
                    if (translationOnly && current.failedTask == VideoSession.Task.TRANSCRIPT) {
                        removed.failedTask = current.failedTask;
                    }
                    if (writeSnapshotLocked(removed, false)) {
                        new SentenceTranscriptEdits(context.getSharedPreferences(
                                SentenceTranscriptEdits.PREFERENCES, Context.MODE_PRIVATE))
                                .remove(current.uri.toString(), translationOnly);
                        result = removed;
                        result.name = requested.name;
                    }
                }
            }
            Snapshot updated = result;
            main.post(() -> callback.accept(updated));
        });
    }

    void editTranscriptCue(Snapshot expected, int index, TranscriptCue edited, String defaultTarget,
                           Consumer<Snapshot> callback) {
        IO.execute(() -> {
            Snapshot result = null;
            synchronized (FILE_LOCK) {
                SharedPreferences saved = videoPreferences(expected.uri);
                if (expected.identity.equals(saved.getString("identity", null))) {
                    Snapshot current = readSnapshotLocked(expected.uri, expected.identity, saved);
                    if (index < current.cues.size() && java.util.Objects.equals(expected.target, current.target)
                            && java.util.Objects.equals(expected.source, current.source)
                            && sameCue(expected.cues.get(index), current.cues.get(index))) {
                        current.cues.set(index, edited);
                        String target = current.target == null && edited.hasTranslation()
                                ? defaultTarget : current.target;
                        result = new Snapshot(current.uri, current.identity, current.position,
                                current.source, target, current.cues);
                        result.transcriptOrigin = current.transcriptOrigin;
                        result.name = expected.name;
                        result.failedTask = current.failedTask;
                        result.failedTarget = current.failedTarget;
                        result.contextualTranslation = current.contextualTranslation;
                        result.translationGlossary = current.translationGlossary;
                        if (result.failedTask == VideoSession.Task.TRANSLATION
                                && java.util.Objects.equals(target, result.failedTarget)
                                && result.cues.stream().allMatch(cue -> cue.hasTranslation() && !cue.translationPending)) {
                            result.failedTask = null;
                            result.failedTarget = null;
                        }
                        if (!writeSnapshotLocked(result, false)) result = null;
                    }
                }
            }
            Snapshot updated = result;
            main.post(() -> callback.accept(updated));
        });
    }

    private static boolean sameCue(TranscriptCue expected, TranscriptCue current) {
        return expected.startMs == current.startMs && expected.endMs == current.endMs
                && expected.sourceText.equals(current.sourceText)
                && java.util.Objects.equals(expected.targetText, current.targetText)
                && expected.translationOrigin == current.translationOrigin
                && expected.translationNeedsReview == current.translationNeedsReview
                && expected.translatedDirectly == current.translatedDirectly;
    }

    void checkpoint(VideoSession session) {
        if (session.videoUri() != null) save(session.videoUri(), session.identity(), 0, session, false);
    }

    void save(Uri uri, String identity, long position, VideoSession session, boolean updatePosition) {
        if (identity == null) return;
        List<TranscriptCue> snapshot = new ArrayList<>();
        for (TranscriptCue cue : session.completedCues()) {
            snapshot.add(cue.copy());
        }
        Snapshot state = new Snapshot(uri, identity, position, session.completedSource(), session.completedTarget(),
                snapshot);
        state.transcriptOrigin = session.transcriptOrigin();
        state.failedTask = session.failedTask();
        state.failedTarget = session.failedTarget();
        state.contextualTranslation = session.contextualTranslation();
        state.translationGlossary = session.translationGlossary();
        IO.execute(() -> {
            synchronized (FILE_LOCK) {
                state.suppressCache = snapshot.isEmpty()
                        && readSnapshotLocked(uri, identity, videoPreferences(uri)).suppressCache;
                writeSnapshotLocked(state, updatePosition);
            }
        });
    }

    private boolean writeSnapshotLocked(Snapshot state, boolean updatePosition) {
        AtomicFile file = transcriptFile(state.uri);
        FileOutputStream output = null;
        try {
            JSONArray array = new JSONArray();
            for (TranscriptCue cue : state.cues) {
                array.put(new JSONObject().put("start", cue.startMs).put("end", cue.endMs)
                        .put("source", cue.sourceText).put("target", cue.targetText)
                        .put("word_starts", cue.wordStarts)
                        .put("sentence", cue.sentence ? Boolean.TRUE : null)
                        .put("source_edited", cue.sourceEdited ? Boolean.TRUE : null)
                        .put("translation_origin", cue.translationOrigin == TranscriptCue.TranslationOrigin.AUTOMATIC
                                ? null : cue.translationOrigin.name())
                        .put("translation_pending", cue.translationPending ? Boolean.TRUE : null)
                        .put("translated_directly", cue.translatedDirectly ? Boolean.TRUE : null)
                        .put("translation_needs_review", cue.translationNeedsReview ? Boolean.TRUE : null));
            }
            JSONObject data = new JSONObject().put("identity", state.identity).put("cues", array)
                    .put("source", state.source).put("target", state.target)
                    .put("transcript_origin", state.transcriptOrigin)
                    .put("transcript_removed", state.suppressCache)
                    .put("contextual_translation", state.contextualTranslation)
                    .put("translation_glossary", state.translationGlossary)
                    .put("failed_target", state.failedTarget)
                    .put("failed_task", state.failedTask == null ? null : state.failedTask.name());
            output = file.startWrite();
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
            SharedPreferences video = videoPreferences(state.uri);
            SharedPreferences.Editor edit = video.edit().putString("identity", state.identity)
                    .putString("source", state.source).putString("target", state.target)
                    .remove("cues");
            if (updatePosition) {
                edit.putLong("position", state.position);
            } else if (!state.identity.equals(video.getString("identity", null))) {
                edit.remove("position");
            }
            boolean saved = edit.commit();
            RECENT_SUMMARIES.remove(state.uri);
            if (saved) RECENT_SUMMARIES.put(state.uri, new RecentSummary(state));
            return saved;
        } catch (Exception ex) {
            if (output != null) file.failWrite(output);
            Log.w("PlaybackSessionStore", "Could not save transcript", ex);
            return false;
        }
    }

    void savePosition(Uri uri, String identity, long position) {
        IO.execute(() -> {
            SharedPreferences video = videoPreferences(uri);
            if (identity != null && identity.equals(video.getString("identity", null))) {
                video.edit().putLong("position", position).apply();
            }
        });
    }

    long savedPosition(Uri uri) {
        return videoPreferences(uri).getLong("position", 0);
    }

    private String fileDurationRevision(Uri uri) {
        String path = uri.getPath();
        if (path == null) return null;
        File file = new File(path);
        long modified = file.lastModified();
        long size = file.length();
        return modified > 0 && size > 0 ? uri + "\n" + size + "\n" + modified : null;
    }

    void saveDuration(Uri uri, String revision, long duration) {
        if (revision == null || duration <= 0) return;
        videoPreferences(uri).edit().putString("duration_revision", revision)
                .putLong("duration_ms", duration).apply();
    }

    long recentDuration(Uri uri) {
        String revision = "file".equals(uri.getScheme()) ? fileDurationRevision(uri)
                : TranscriptCache.metadataIdentity(context, uri, null);
        if (revision != null) {
            Map<String, ?> saved = videoPreferences(uri).getAll();
            Object duration = saved.get("duration_ms");
            if (revision.equals(saved.get("duration_revision")) && duration instanceof Long
                    && (Long) duration > 0) return (Long) duration;
        }
        if (Thread.currentThread().isInterrupted()) return 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            long duration = Long.parseLong(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (!Thread.currentThread().isInterrupted()) saveDuration(uri, revision, duration);
            return Math.max(0, duration);
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    List<RecentVideo> recentVideos() {
        List<RecentVideo> recent = new ArrayList<>();
        String stored = preferences.getString("recent_videos", null);
        if (stored == null) {
            String legacy = preferences.getString("video", null);
            if (legacy != null) {
                Uri uri = Uri.parse(legacy);
                recent.add(new RecentVideo(uri, null));
            }
            return recent;
        }
        try {
            JSONArray array = new JSONArray(stored);
            for (int index = 0; index < array.length() && recent.size() < RECENT_LIMIT; index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null || item.optString("uri").isEmpty()) continue;
                Uri uri = Uri.parse(item.optString("uri"));
                recent.add(new RecentVideo(uri, item.optString("name", null)));
            }
        } catch (org.json.JSONException ex) {
            Log.w("PlaybackSessionStore", "Could not read recent videos", ex);
        }
        return recent;
    }

    static String displayName(Context context, Uri uri, android.os.CancellationSignal cancellation) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null, cancellation)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) {
            // Some providers do not expose a display name.
        }
        return fallbackName(uri);
    }

    static String fallbackName(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) return "Video";
        return name.substring(name.lastIndexOf('/') + 1);
    }

    void recordOpened(Uri uri, String name) {
        if (!preferences.getBoolean("has_opened_video", false)) {
            preferences.edit().putBoolean("has_opened_video", true).apply();
        }
        if (name != null) videoPreferences(uri).edit().putString("name", name).apply();
        List<RecentVideo> recent = recentVideos();
        recent.removeIf(video -> uri.equals(video.uri));
        recent.add(0, new RecentVideo(uri, name));
        RecentVideo evicted = recent.size() > RECENT_LIMIT ? recent.remove(RECENT_LIMIT) : null;
        writeRecent(recent);
        if (evicted != null && "content".equals(evicted.uri.getScheme())) {
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        evicted.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The provider may already have revoked the grant.
            }
        }
    }

    boolean hasOpenedVideo() {
        return preferences.getBoolean("has_opened_video", false) || !recentVideos().isEmpty();
    }

    void removeRecent(Uri uri) {
        List<RecentVideo> recent = recentVideos();
        if (recent.removeIf(video -> uri.equals(video.uri))) writeRecent(recent);
    }

    void clearHistory(Uri uri, Consumer<Boolean> completion) {
        if (uri == null) {
            preferences.edit().remove("video").remove("recent_videos").apply();
        } else {
            removeRecent(uri);
        }
        IO.execute(() -> {
            boolean saved = true;
            if (uri != null) {
                saved = clearPosition(videoPreferences(uri));
            } else {
                File[] files = videoPreferenceFiles();
                if (files != null) {
                    for (File file : files) {
                        String name = file.getName();
                        SharedPreferences video = context.getSharedPreferences(
                                name.substring(0, name.length() - 4), Context.MODE_PRIVATE);
                        saved &= clearPosition(video);
                    }
                }
            }
            boolean success = saved;
            main.post(() -> completion.accept(success));
        });
    }

    private File[] videoPreferenceFiles() {
        return new File(context.getApplicationInfo().dataDir, "shared_prefs")
                .listFiles(file -> file.getName().matches("playback_video_[a-f0-9-]+[.]xml"));
    }

    private static boolean clearPosition(SharedPreferences video) {
        return video.edit().remove("position").commit();
    }

    private void writeRecent(List<RecentVideo> recent) {
        try {
            JSONArray array = new JSONArray();
            for (RecentVideo video : recent) {
                array.put(new JSONObject().put("uri", video.uri.toString()).put("name", video.name));
            }
            preferences.edit().remove("video").putString("recent_videos", array.toString()).apply();
        } catch (org.json.JSONException ex) {
            Log.w("PlaybackSessionStore", "Could not save recent videos", ex);
        }
    }
}
