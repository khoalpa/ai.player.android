package com.aiplayer.android;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

final class DeepLClient implements AutoCloseable {
    static final int MAX_TEXTS = 50;
    static final int MAX_REQUEST_BYTES = 128 * 1024;
    private final String key;
    private final String endpoint;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private HttpsURLConnection activeConnection;
    private boolean closed;

    DeepLClient(String key) {
        this.key = key;
        endpoint = key.endsWith(":fx") ? "https://api-free.deepl.com/v2/translate"
                : "https://api.deepl.com/v2/translate";
    }

    Task<List<String>> translate(String source, List<String> texts) {
        return Tasks.call(executor, () -> {
            try {
                return request(source, texts);
            } catch (java.net.SocketTimeoutException error) {
                throw new TranslationFailure("Kết nối DeepL quá thời gian chờ.");
            } catch (TranslationFailure error) {
                throw error;
            } catch (Exception error) {
                throw new TranslationFailure("Không thể kết nối hoặc đọc phản hồi DeepL.");
            }
        });
    }

    static byte[] requestBody(String source, List<String> texts) throws JSONException {
        JSONObject body = new JSONObject().put("text", new JSONArray(texts)).put("target_lang", "VI");
        if (source != null && !"auto".equals(source)) {
            body.put("source_lang", source.toUpperCase(Locale.ROOT));
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<String> request(String source, List<String> texts) throws Exception {
        byte[] body = requestBody(source, texts);
        if (body.length > MAX_REQUEST_BYTES || texts.isEmpty() || texts.size() > MAX_TEXTS) {
            throw new TranslationFailure("Lô dịch vượt giới hạn gửi DeepL.");
        }
        HttpsURLConnection connection = (HttpsURLConnection) new URL(endpoint).openConnection();
        synchronized (this) {
            if (closed) throw new IOException("Đã hủy dịch DeepL.");
            activeConnection = connection;
        }
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Authorization", "DeepL-Auth-Key " + key);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new TranslationFailure(errorMessage(status));
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (response.size() + count > 1024 * 1024) throw new IOException("Phản hồi DeepL quá lớn.");
                    response.write(buffer, 0, count);
                }
                return readTranslations(response.toString("UTF-8"), texts.size());
            }
        } finally {
            synchronized (this) {
                if (activeConnection == connection) activeConnection = null;
            }
            connection.disconnect();
        }
    }

    static List<String> readTranslations(String response, int expectedCount) throws JSONException, IOException {
        JSONArray items = new JSONObject(response).getJSONArray("translations");
        if (items.length() != expectedCount) throw new IOException("DeepL trả về sai số đoạn.");
        List<String> result = new ArrayList<>(expectedCount);
        for (int i = 0; i < expectedCount; i++) {
            Object value = items.getJSONObject(i).get("text");
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                throw new IOException("DeepL trả về bản dịch trống hoặc không hợp lệ.");
            }
            result.add(((String) value).trim());
        }
        return result;
    }

    private static final class TranslationFailure extends IOException {
        TranslationFailure(String message) {
            super(message);
        }
    }

    private static String errorMessage(int status) {
        switch (status) {
            case 401:
            case 403: return "DeepL từ chối khóa API. Hãy kiểm tra khóa.";
            case 429: return "DeepL đang giới hạn số yêu cầu. Hãy thử lại sau.";
            case 456: return "Đã hết hạn mức dịch DeepL.";
            default: return "DeepL không dịch được (HTTP " + status + ").";
        }
    }

    @Override public void close() {
        HttpsURLConnection connection;
        synchronized (this) {
            closed = true;
            connection = activeConnection;
        }
        executor.shutdownNow();
        if (connection != null) connection.disconnect();
    }
}
