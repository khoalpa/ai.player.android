package com.aiplayer.android;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Best-effort, anonymous extraction for PUBLIC Telegram post embed pages only. */
final class TelegramPublicResolver {
    interface Callback { void resolved(String mediaUrl, Exception error); }
    private static final int MAX_HTML_BYTES = 512 * 1024;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    Runnable resolve(VideoSourceResolver.Source source, Callback callback) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<HttpURLConnection> active =
                new java.util.concurrent.atomic.AtomicReference<>();
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL embed = VideoSourceResolver.publicTelegramEmbed(source);
                connection = (HttpURLConnection) embed.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "text/html");
                active.set(connection);
                if (cancelled.get()) return;
                int code = connection.getResponseCode();
                if (code != 200) throw new java.io.IOException("Telegram trả về HTTP " + code);
                String type = connection.getContentType();
                if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("text/html"))
                    throw new java.io.IOException("Telegram không trả về trang HTML công khai");
                byte[] buffer = new byte[8192];
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream stream = connection.getInputStream()) {
                    int n;
                    while ((n = stream.read(buffer)) != -1) {
                        if (cancelled.get()) return;
                        if (output.size() + n > MAX_HTML_BYTES)
                            throw new java.io.IOException("Trang Telegram vượt giới hạn kiểm tra");
                        output.write(buffer, 0, n);
                    }
                }
                String media = TelegramPublicContent.resolveVideo(
                        new String(output.toByteArray(), StandardCharsets.UTF_8), source);
                main.post(() -> {
                    if (!cancelled.get()) callback.resolved(media, null);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!cancelled.get()) callback.resolved(null, error);
                });
            } finally {
                active.set(null);
                if (connection != null) connection.disconnect();
            }
        });
        executor.shutdown();
        return () -> {
            cancelled.set(true);
            HttpURLConnection connection = active.get();
            if (connection != null) connection.disconnect();
        };
    }
}
