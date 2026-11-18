package com.aiplayer.android;

import android.content.Context;
import android.util.JsonReader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class VideoHostRegistry {
    private VideoHostRegistry() {}

    static String find(Context context, String host) {
        if (host.isEmpty()) return null;
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                context.getAssets().open("video_url_hosts.json"), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String site = reader.nextName();
                reader.beginArray();
                while (reader.hasNext()) {
                    if (matches(host, reader.nextString())) return site;
                }
                reader.endArray();
            }
            reader.endObject();
        } catch (IOException ignored) {
            // A missing registry only affects host-specific feedback.
        }
        return null;
    }

    static boolean matches(String host, String pattern) {
        if (pattern.endsWith(".*")) {
            String label = pattern.substring(pattern.startsWith("*.") ? 2 : 0, pattern.length() - 2);
            int tld = host.lastIndexOf('.');
            if (tld < 0) return false;
            String base = host.substring(0, tld);
            return pattern.startsWith("*.") ? base.endsWith("." + label) : base.equals(label);
        }
        return host.equals(pattern) || host.endsWith("." + pattern);
    }
}
