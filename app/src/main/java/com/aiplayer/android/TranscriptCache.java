package com.aiplayer.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract.Document;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

final class TranscriptCache {
    private static final String TAG = "TranscriptCache";
    private final Context context;

    TranscriptCache(Context context) {
        this.context = context.getApplicationContext();
    }

    static String keyForIdentity(String identity, String language) {
        if (identity == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("2\n" + identity + "\n" + language + "\n" + VoskModelStore.modelVersion(language))
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                key.append(Character.forDigit((value & 0xff) >>> 4, 16));
                key.append(Character.forDigit(value & 0xf, 16));
            }
            return key.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    static String videoIdentity(Context context, Uri uri, ReadCancellation cancellation) {
        String[] columns = {Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
        try (Cursor row = context.getContentResolver().query(uri, columns, null, null, null, cancellation.signal)) {
            if (row != null && row.moveToFirst()) {
                int size = row.getColumnIndex(Document.COLUMN_SIZE);
                int modified = row.getColumnIndex(Document.COLUMN_LAST_MODIFIED);
                if (size >= 0 && modified >= 0 && !row.isNull(size) && !row.isNull(modified)
                        && row.getLong(size) > 0 && row.getLong(modified) > 0) {
                    return uri + "\n" + row.getLong(size) + "\n" + row.getLong(modified);
                }
            }
        } catch (Exception ex) {
            // Providers may reject the requested metadata columns.
        }
        if (cancellation.isCancelled()) return null;
        return contentIdentity(context, uri, cancellation);
    }

    // Runs on the session/cache IO executor. Hash the full file when metadata is
    // unavailable so replacing content at the same URI cannot restore stale cues.
    private static String contentIdentity(Context context, Uri uri, ReadCancellation cancellation) {
        try (android.content.res.AssetFileDescriptor descriptor = context.getContentResolver()
                .openAssetFileDescriptor(uri, "r", cancellation.signal)) {
            if (descriptor == null) return null;
            try (InputStream input = descriptor.createInputStream()) {
                cancellation.register(input);
                return uri + "\nsha256:" + ContentFingerprint.sha256(input, cancellation::isCancelled);
            }
        } catch (Exception ex) {
            return null;
        } finally {
            cancellation.clear();
        }
    }

    List<TranscriptCue> readLatest(String key, List<VideoSession.AudioTrack> tracks) {
        List<TranscriptCue> latest = new ArrayList<>();
        long latestTime = Long.MIN_VALUE;
        if (key == null) return latest;
        for (VideoSession.AudioTrack track : tracks) {
            File file = fileFor(key, track.index);
            long updated = file.lastModified();
            if (!file.isFile() || updated < latestTime) continue;
            try {
                JSONArray array = new JSONArray(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
                List<TranscriptCue> result = new ArrayList<>(array.length());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject cue = array.getJSONObject(i);
                    result.add(new TranscriptCue(cue.getLong("start"), cue.getLong("end"), cue.getString("text")));
                }
                if (!result.isEmpty()) {
                    latest = result;
                    latestTime = updated;
                }
            } catch (Exception ex) {
                Log.w(TAG, "Could not read cached transcript");
            }
        }
        return latest;
    }

    void write(String key, int track, List<TranscriptCue> cues) {
        if (key == null || cues.isEmpty()) return;
        AtomicFile file = new AtomicFile(fileFor(key, track));
        FileOutputStream output = null;
        try {
            JSONArray array = new JSONArray();
            for (TranscriptCue cue : cues) {
                array.put(new JSONObject().put("start", cue.startMs).put("end", cue.endMs)
                        .put("text", cue.sourceText));
            }
            output = file.startWrite();
            output.write(array.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception ex) {
            if (output != null) file.failWrite(output);
            Log.w(TAG, "Could not save transcript");
        }
    }

    private File fileFor(String key, int track) {
        return new File(new File(context.getCacheDir(), "transcripts"), key + "-" + track + ".json");
    }
}
