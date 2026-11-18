package com.aiplayer.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class PlaybackSessionStore {
    private static final int RECENT_LIMIT = 3;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Object FILE_LOCK = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private Future<?> pendingLoad;
    private ReadCancellation cancellation;
    static final class Snapshot {
        final Uri uri;
        final String identity;
        final long position;
        final String source;
        final String target;
        final List<TranscriptCue> cues;
        String name = "Video";

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
                String identity = TranscriptCache.videoIdentity(context, uri, request);
                if (request.isCancelled()) return;
                SharedPreferences saved = videoPreferences(uri);
                restored = identity != null
                        ? readSnapshot(uri, identity, saved)
                        : new Snapshot(uri, identity, 0, null, null, new ArrayList<>());
                restored.name = displayName(context, uri, request.signal);
            }
            Snapshot result = restored;
            main.post(() -> { if (!request.isCancelled()) callback.accept(result); });
        });
    }

    private Snapshot readSnapshot(Uri uri, String identity, SharedPreferences preferences) {
        synchronized (FILE_LOCK) { return readSnapshotLocked(uri, identity, preferences); }
    }

    private Snapshot readSnapshotLocked(Uri uri, String identity, SharedPreferences preferences) {
        List<TranscriptCue> cues = new ArrayList<>();
        boolean sameIdentity = identity.equals(preferences.getString("identity", null));
        String source = sameIdentity ? preferences.getString("source", null) : null;
        String target = sameIdentity ? preferences.getString("target", null) : null;
        try {
            String json = sameIdentity ? preferences.getString("cues", "[]") : "[]";
            AtomicFile file = transcriptFile(uri);
            if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists()) {
                JSONObject saved = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
                if (identity.equals(saved.optString("identity"))) {
                    json = saved.getJSONArray("cues").toString();
                    source = saved.isNull("source") ? null : saved.optString("source", null);
                    target = saved.isNull("target") ? null : saved.optString("target", null);
                } else json = "[]";
            }
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                TranscriptCue cue = new TranscriptCue(row.getLong("start"), row.getLong("end"),
                        row.getString("source"));
                cue.targetText = row.optString("target", "");
                cues.add(cue);
            }
        } catch (Exception ex) {
            Log.w("PlaybackSessionStore", "Could not restore transcript", ex);
            cues.clear();
        }
        Snapshot result = new Snapshot(uri, identity, sameIdentity ? preferences.getLong("position", 0) : 0,
                cues.isEmpty() ? null : source, cues.isEmpty() ? null : target, cues);
        return result;
    }

    void save(Uri uri, String identity, long position, VideoSession session) {
        save(uri, identity, position, session, true);
    }

    void checkpoint(VideoSession session) {
        if (session.videoUri() != null) save(session.videoUri(), session.identity(), 0, session, false);
    }

    private void save(Uri uri, String identity, long position, VideoSession session, boolean updatePosition) {
        if (identity == null) return;
        List<TranscriptCue> snapshot = new ArrayList<>();
        for (TranscriptCue cue : session.completedCues()) {
            TranscriptCue copy = new TranscriptCue(cue.startMs, cue.endMs, cue.sourceText);
            copy.targetText = cue.targetText;
            snapshot.add(copy);
        }
        String source = session.completedSource();
        String target = session.completedTarget();
        IO.execute(() -> writeSnapshot(uri, identity, position, source, target, snapshot, updatePosition));
    }

    private void writeSnapshot(Uri uri, String identity, long position, String source, String target,
                               List<TranscriptCue> cues, boolean updatePosition) {
        synchronized (FILE_LOCK) {
            writeSnapshotLocked(uri, identity, position, source, target, cues, updatePosition);
        }
    }

    private void writeSnapshotLocked(Uri uri, String identity, long position, String source, String target,
                                     List<TranscriptCue> cues, boolean updatePosition) {
        AtomicFile file = transcriptFile(uri);
        FileOutputStream output = null;
        try {
            JSONArray array = new JSONArray();
            for (TranscriptCue cue : cues) {
                array.put(new JSONObject().put("start", cue.startMs).put("end", cue.endMs)
                        .put("source", cue.sourceText).put("target", cue.targetText));
            }
            JSONObject data = new JSONObject().put("identity", identity).put("cues", array)
                    .put("source", source).put("target", target);
            output = file.startWrite();
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
            SharedPreferences video = videoPreferences(uri);
            SharedPreferences.Editor edit = video.edit().putString("identity", identity)
                    .putString("source", source).putString("target", target)
                    .remove("cues");
            if (updatePosition || !identity.equals(video.getString("identity", null))) {
                edit.putLong("position", position);
            }
            edit.commit();
        } catch (Exception ex) {
            if (output != null) file.failWrite(output);
            Log.w("PlaybackSessionStore", "Could not save transcript", ex);
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

    void removeRecent(Uri uri) {
        List<RecentVideo> recent = recentVideos();
        if (recent.removeIf(video -> uri.equals(video.uri))) writeRecent(recent);
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
