package com.aiplayer.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class YoutubeCaptionClient {
    interface Callback {
        void onLoaded(List<TranscriptCue> loadedCues, String status);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    void loadCaptions(String videoId, String preferredLanguage, Callback callback) {
        executor.execute(() -> {
            try {
                String watchHtml = get("https://www.youtube.com/watch?v=" + videoId + "&hl=en");
                JSONObject player = extractPlayerResponse(watchHtml);
                JSONArray tracks = player
                        .getJSONObject("captions")
                        .getJSONObject("playerCaptionsTracklistRenderer")
                        .getJSONArray("captionTracks");
                JSONObject selected = chooseTrack(tracks, preferredLanguage);
                if (selected == null) {
                    callback.onLoaded(new ArrayList<>(), "Video YouTube này không có caption để đọc/lồng tiếng.");
                    return;
                }
                String captionUrl = selected.getString("baseUrl");
                String lang = selected.optString("languageCode", "");
                List<TranscriptCue> cues = YoutubeCaptionParser.parseTimedText(get(captionUrl));
                callback.onLoaded(cues, "Đã nạp " + cues.size() + " đoạn caption YouTube" + (lang.isEmpty() ? "." : " (" + lang + ")."));
            } catch (Exception ex) {
                callback.onLoaded(new ArrayList<>(), "Không lấy được caption YouTube. Hãy nạp transcript .srt/.vtt/.txt thủ công nếu cần đọc/lồng tiếng.");
            }
        });
    }

    void close() {
        executor.shutdownNow();
    }

    private JSONObject chooseTrack(JSONArray tracks, String preferredLanguage) throws Exception {
        String preferred = preferredLanguage == null ? "" : preferredLanguage.trim().toLowerCase(Locale.US);
        JSONObject first = tracks.length() > 0 ? tracks.getJSONObject(0) : null;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.getJSONObject(i);
            if (track.optString("languageCode", "").toLowerCase(Locale.US).equals(preferred)) {
                return track;
            }
        }
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.getJSONObject(i);
            if (!"asr".equals(track.optString("kind", ""))) {
                return track;
            }
        }
        return first;
    }

    private JSONObject extractPlayerResponse(String html) throws Exception {
        String[] markers = {
                "var ytInitialPlayerResponse =",
                "window[\"ytInitialPlayerResponse\"] =",
                "ytInitialPlayerResponse ="
        };
        int start = -1;
        for (String marker : markers) {
            int markerIndex = html.indexOf(marker);
            if (markerIndex < 0) {
                continue;
            }
            start = html.indexOf('{', markerIndex + marker.length());
            if (start >= 0) {
                break;
            }
        }
        if (start < 0) {
            throw new IllegalStateException("không thấy dữ liệu caption trong trang YouTube");
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < html.length(); i++) {
            char ch = html.charAt(i);
            if (ch == '"') {
                if (!escaped) {
                    inString = !inString;
                }
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString) {
                escaped = !escaped;
                continue;
            }
            escaped = false;
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return new JSONObject(html.substring(start, i + 1));
                }
            }
        }
        throw new IllegalStateException("dữ liệu caption YouTube không hợp lệ");
    }

    private String get(String rawUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/125 Mobile Safari/537.36");
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }
}
