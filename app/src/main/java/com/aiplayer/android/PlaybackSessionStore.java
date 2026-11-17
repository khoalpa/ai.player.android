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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class PlaybackSessionStore {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    static final class Snapshot {
        final Uri uri;
        final long position;
        final String source;
        final String target;
        final List<TranscriptCue> cues;

        Snapshot(Uri uri, long position, String source, String target, List<TranscriptCue> cues) {
            this.uri = uri;
            this.position = position;
            this.source = source;
            this.target = target;
            this.cues = cues;
        }
    }

    private final SharedPreferences preferences;

    PlaybackSessionStore(Context context) {
        preferences = context.getSharedPreferences("playback_session", Context.MODE_PRIVATE);
    }

    void load(Consumer<Snapshot> callback) {
        IO.execute(() -> {
            Snapshot snapshot = readSnapshot();
            main.post(() -> callback.accept(snapshot));
        });
    }

    private Snapshot readSnapshot() {
        String uri = preferences.getString("video", null);
        if (uri == null) return null;
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
        return new Snapshot(Uri.parse(uri), preferences.getLong("position", 0),
                cues.isEmpty() ? null : preferences.getString("source", null),
                cues.isEmpty() ? null : preferences.getString("target", null), cues);
    }

    void save(Uri uri, long position, VideoSession session) {
        List<TranscriptCue> snapshot = new ArrayList<>();
        for (TranscriptCue cue : session.completedCues()) {
            TranscriptCue copy = new TranscriptCue(cue.startMs, cue.endMs, cue.sourceText);
            copy.targetText = cue.targetText;
            snapshot.add(copy);
        }
        String source = session.completedSource();
        String target = session.completedTarget();
        IO.execute(() -> writeSnapshot(uri, position, source, target, snapshot));
    }

    private void writeSnapshot(Uri uri, long position, String source, String target,
                               List<TranscriptCue> cues) {
        try {
            JSONArray array = new JSONArray();
            for (TranscriptCue cue : cues) {
                array.put(new JSONObject().put("start", cue.startMs).put("end", cue.endMs)
                        .put("source", cue.sourceText).put("target", cue.targetText));
            }
            preferences.edit().putString("video", uri.toString()).putLong("position", position)
                    .putString("source", source).putString("target", target)
                    .putString("cues", array.toString()).apply();
        } catch (JSONException ex) {
            Log.w("PlaybackSessionStore", "Could not save transcript", ex);
        }
    }

    void savePosition(Uri uri, long position) {
        IO.execute(() -> {
            if (uri.toString().equals(preferences.getString("video", null))) {
                preferences.edit().putLong("position", position).apply();
            }
        });
    }

    void clear() {
        IO.execute(() -> preferences.edit().clear().apply());
    }
}

