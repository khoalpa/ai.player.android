package com.aiplayer.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class PlaybackSessionStore {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    static final class Snapshot {
        final Uri uri;
        final String identity;
        final long position;
        final String source;
        final String target;
        final List<TranscriptCue> cues;

        Snapshot(Uri uri, String identity, long position, String source, String target, List<TranscriptCue> cues) {
            this.uri = uri;
            this.identity = identity;
            this.position = position;
            this.source = source;
            this.target = target;
            this.cues = cues;
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

    void load(Uri requested, Consumer<Snapshot> callback) {
        IO.execute(() -> {
            String recent = preferences.getString("video", null);
            Uri uri = requested != null ? requested : (recent == null ? null : Uri.parse(recent));
            Snapshot restored = null;
            if (uri != null) {
                String identity = TranscriptCache.videoIdentity(context, uri);
                SharedPreferences saved = videoPreferences(uri);
                restored = identity != null && identity.equals(saved.getString("identity", null))
                        ? readSnapshot(uri, identity, saved)
                        : new Snapshot(uri, identity, 0, null, null, new ArrayList<>());
            }
            Snapshot result = restored;
            main.post(() -> callback.accept(result));
        });
    }

    private Snapshot readSnapshot(Uri uri, String identity, SharedPreferences preferences) {
        List<TranscriptCue> cues = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString("cues", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                TranscriptCue cue = new TranscriptCue(row.getLong("start"), row.getLong("end"),
                        row.getString("source"));
                cue.targetText = row.optString("target", "");
                cues.add(cue);
            }
        } catch (JSONException ex) {
            Log.w("PlaybackSessionStore", "Could not restore transcript", ex);
            cues.clear();
        }
        return new Snapshot(uri, identity, preferences.getLong("position", 0),
                cues.isEmpty() ? null : preferences.getString("source", null),
                cues.isEmpty() ? null : preferences.getString("target", null), cues);
    }

    void save(Uri uri, String identity, long position, VideoSession session) {
        if (identity == null) {
            IO.execute(() -> preferences.edit().clear().putString("video", uri.toString()).apply());
            return;
        }
        List<TranscriptCue> snapshot = new ArrayList<>();
        for (TranscriptCue cue : session.completedCues()) {
            TranscriptCue copy = new TranscriptCue(cue.startMs, cue.endMs, cue.sourceText);
            copy.targetText = cue.targetText;
            snapshot.add(copy);
        }
        String source = session.completedSource();
        String target = session.completedTarget();
        IO.execute(() -> writeSnapshot(uri, identity, position, source, target, snapshot));
    }

    private void writeSnapshot(Uri uri, String identity, long position, String source, String target,
                               List<TranscriptCue> cues) {
        try {
            JSONArray array = new JSONArray();
            for (TranscriptCue cue : cues) {
                array.put(new JSONObject().put("start", cue.startMs).put("end", cue.endMs)
                        .put("source", cue.sourceText).put("target", cue.targetText));
            }
            videoPreferences(uri).edit().putString("identity", identity).putLong("position", position)
                    .putString("source", source).putString("target", target)
                    .putString("cues", array.toString()).apply();
            preferences.edit().clear().putString("video", uri.toString()).apply();
        } catch (JSONException ex) {
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

    void clearRecent() {
        IO.execute(() -> {
            preferences.edit().clear().apply();
        });
    }
}

