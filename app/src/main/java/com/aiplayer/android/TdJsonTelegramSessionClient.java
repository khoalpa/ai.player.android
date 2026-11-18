package com.aiplayer.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import org.drinkless.tdlib.JsonClient;
import org.json.JSONObject;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TDLib JSONJava transport. Requires matching official libtdjsonjava.so at runtime.
 * Only public channel messages explicitly requested by the user are downloaded.
 */
final class TdJsonTelegramSessionClient implements TelegramSessionClient {
    private static final String TAG = "TdJsonSession";
    private static final long LIMIT_BYTES = VideoUrlPolicy.MAX_BYTES;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TelegramAuthorizationFlow flow = new TelegramAuthorizationFlow();
    private final Map<String, java.util.function.Consumer<JSONObject>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, MessageVideoResult> downloads = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong();
    private final int apiId;
    private final String apiHash;
    private volatile boolean nativeLoaded;
    private volatile boolean running;
    private volatile int clientId;
    private volatile Listener stateListener;
    private Completion logoutCompletion;

    TdJsonTelegramSessionClient(Context context, int apiId, String apiHash) {
        this.context = context.getApplicationContext();
        this.apiId = apiId;
        this.apiHash = apiHash == null ? "" : apiHash.trim();
        try {
            // This fails closed until an audited matching JSONJava binary is bundled.
            System.loadLibrary("tdjsonjava");
            nativeLoaded = true;
        } catch (UnsatisfiedLinkError unavailable) {
            flow.onNativeLibraryUnavailable();
        }
    }

    void closeTransport() {
        stateListener = null;
        running = false;
        pending.clear();
        downloads.clear();
        if (nativeLoaded && clientId != 0) {
            try {
                JsonClient.send(clientId, request("close").toString());
            } catch (LinkageError ignored) {
                // Native bridge may already have been unloaded.
            }
        }
    }

    @Override public boolean isNativeAvailable() { return nativeLoaded; }
    @Override public TelegramAuthorizationFlow.State authorizationState() { return flow.state(); }

    @Override public synchronized void start(Listener listener, Completion callback) {
        if (!nativeLoaded) {
            callback.finished("Thiếu TDLib native JSONJava. Hãy cài bản dựng đã xác minh.");
            return;
        }
        if (apiId <= 0 || apiHash.isEmpty()) {
            callback.finished("Thiếu Telegram API ID và API hash hợp lệ từ my.telegram.org");
            return;
        }
        stateListener = listener;
        if (running) {
            listener.authorizationChanged(flow.state());
            callback.finished(null);
            return;
        }
        try {
            clientId = JsonClient.createClientId();
            running = true;
            Thread worker = new Thread(this::poll, "AIPlayer-TDLib");
            worker.setDaemon(true);
            worker.start();
            // Trigger the initial update. Only updateAuthorizationState advances the flow.
            send(request("getAuthorizationState"), response -> {
                if ("error".equals(response.optString("@type"))) {
                    flow.onError();
                    if (stateListener != null) stateListener.authorizationChanged(flow.state());
                }
            });
            callback.finished(null);
        } catch (LinkageError error) {
            nativeLoaded = false;
            flow.onNativeLibraryUnavailable();
            callback.finished("TDLib native và Java binding không tương thích");
        }
    }

    private void poll() {
        try {
            while (running) {
                String data = JsonClient.receive(0.5);
                if (data == null) continue;
                JSONObject object = new JSONObject(data);
                if (object.has("@client_id") && object.optInt("@client_id") != clientId) continue;
                if ("updateAuthorizationState".equals(object.optString("@type"))) {
                    JSONObject state = object.optJSONObject("authorization_state");
                    String kind = state == null ? null : state.optString("@type", null);
                    handleAuthorizationState(kind);
                    continue;
                }
                if ("updateFile".equals(object.optString("@type"))) {
                    onFile(object.optJSONObject("file"));
                    continue;
                }
                String key = object.optString("@extra");
                java.util.function.Consumer<JSONObject> handler = pending.remove(key);
                if (handler != null) main.post(() -> handler.accept(object));
            }
        } catch (Exception | LinkageError failure) {
            // Never log raw TDLib responses or user credentials.
            running = false;
            flow.onError();
            Listener listener = stateListener;
            if (listener != null) main.post(() -> listener.authorizationChanged(flow.state()));
            main.post(() -> finishLogout("Mất kết nối với TDLib trước khi xác nhận đăng xuất"));
        }
    }

    private void handleAuthorizationState(String kind) {
        flow.onTdlibAuthorizationState(kind);
        if ("authorizationStateWaitTdlibParameters".equals(kind)) configureTdlib();
        if ("authorizationStateClosed".equals(kind)) {
            running = false;
            main.post(() -> finishLogout(null));
        }
        Listener listener = stateListener;
        if (listener != null) main.post(() -> listener.authorizationChanged(flow.state()));
    }

    private void configureTdlib() {
        try {
            File database = new File(context.getNoBackupFilesDir(), "tdlib/db");
            File media = new File(context.getNoBackupFilesDir(), "tdlib/files");
            if ((!database.isDirectory() && !database.mkdirs())
                    || (!media.isDirectory() && !media.mkdirs()))
                throw new IllegalStateException("Không tạo được thư mục dữ liệu TDLib");
            JSONObject params = request("setTdlibParameters");
            params.put("database_directory", database.getAbsolutePath());
            params.put("files_directory", media.getAbsolutePath());
            params.put("use_file_database", true);
            params.put("use_chat_info_database", true);
            params.put("use_message_database", true);
            params.put("use_secret_chats", false);
            params.put("api_id", apiId);
            params.put("api_hash", apiHash);
            params.put("system_language_code", "vi");
            params.put("device_model", Build.MODEL);
            params.put("system_version", Build.VERSION.RELEASE);
            params.put("application_version", "AI Player 0.1");
            send(params, response -> {
                if ("error".equals(response.optString("@type"))) {
                    android.util.Log.w(TAG, "setTdlibParameters failed, code=" + response.optInt("code", -1));
                    flow.onError();
                    Listener listener = stateListener;
                    if (listener != null) listener.authorizationChanged(flow.state());
                }
            });
        } catch (Exception error) {
            flow.onError();
            Listener listener = stateListener;
            if (listener != null) main.post(() -> listener.authorizationChanged(flow.state()));
        }
    }

    private JSONObject request(String name) {
        try {
            return new JSONObject().put("@type", name);
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("Cannot create TDLib request", error);
        }
    }

    private void send(JSONObject data, java.util.function.Consumer<JSONObject> handler) {
        String key = Long.toString(requestIds.incrementAndGet());
        try {
            data.put("@extra", key);
            pending.put(key, handler);
            JsonClient.send(clientId, data.toString());
        } catch (Exception | LinkageError error) {
            pending.remove(key);
            main.post(() -> handler.accept(new JSONObject()));
        }
    }

    private void authRequest(JSONObject request, Completion callback) {
        send(request, response -> {
            if ("error".equals(response.optString("@type")))
                callback.finished(messageError(response, "Telegram từ chối yêu cầu"));
            else if (!"ok".equals(response.optString("@type")))
                callback.finished("Không nhận được xác nhận từ TDLib");
            else callback.finished(null);
        });
    }

    private boolean canSubmit(TelegramAuthorizationFlow.State state, Completion callback) {
        if (!nativeLoaded || !running || flow.state() != state) {
            callback.finished("Trạng thái đăng nhập Telegram không phù hợp");
            return false;
        }
        return true;
    }

    @Override public void submitPhone(String phone, Completion callback) {
        if (!canSubmit(TelegramAuthorizationFlow.State.PHONE_REQUIRED, callback)) return;
        try {
            JSONObject request = request("setAuthenticationPhoneNumber");
            request.put("phone_number", phone);
            authRequest(request, callback);
        } catch (Exception error) { callback.finished("Số điện thoại không hợp lệ"); }
    }

    @Override public void submitCode(char[] verificationCode, Completion callback) {
        if (!canSubmit(TelegramAuthorizationFlow.State.CODE_REQUIRED, callback)) {
            if (verificationCode != null) java.util.Arrays.fill(verificationCode, '\0');
            return;
        }
        try {
            JSONObject request = request("checkAuthenticationCode");
            request.put("code", new String(verificationCode));
            authRequest(request, callback);
        } catch (Exception error) { callback.finished("Mã xác minh không hợp lệ"); }
        finally { if (verificationCode != null) java.util.Arrays.fill(verificationCode, '\0'); }
    }

    @Override public void submitTwoStepPassword(char[] password, Completion callback) {
        if (!canSubmit(TelegramAuthorizationFlow.State.PASSWORD_REQUIRED, callback)) {
            if (password != null) java.util.Arrays.fill(password, '\0');
            return;
        }
        try {
            JSONObject request = request("checkAuthenticationPassword");
            request.put("password", new String(password));
            authRequest(request, callback);
        } catch (Exception error) { callback.finished("Mật khẩu xác thực không hợp lệ"); }
        finally { if (password != null) java.util.Arrays.fill(password, '\0'); }
    }

    @Override public void downloadMessageVideo(String publicChannel, long messageId,
                                                MessageVideoResult callback) {
        if (!nativeLoaded || !running || flow.state() != TelegramAuthorizationFlow.State.READY) {
            callback.finished(null, "Bạn cần đăng nhập Telegram");
            return;
        }
        try {
            JSONObject search = request("searchPublicChat");
            search.put("username", publicChannel);
            send(search, chat -> {
                if (!"chat".equals(chat.optString("@type"))) {
                    callback.finished(null, messageError(chat, "Không tìm thấy kênh"));
                    return;
                }
                try {
                    long tdlibMessageId = TelegramMessageReference.toTdlibMessageId(messageId);
                    JSONObject get = request("getMessage");
                    get.put("chat_id", chat.getLong("id"));
                    get.put("message_id", tdlibMessageId);
                    send(get, message -> onMessage(message, callback));
                } catch (Exception error) {
                    callback.finished(null, "Không đọc được ID bài đăng");
                }
            });
        } catch (Exception error) { callback.finished(null, "Liên kết Telegram không hợp lệ"); }
    }

    private void onMessage(JSONObject message, MessageVideoResult callback) {
        if (!"message".equals(message.optString("@type"))) {
            callback.finished(null, messageError(message, "Không truy cập được bài đăng"));
            return;
        }
        if (message.has("can_be_saved")) {
            if (message.optBoolean("can_be_saved", false)) downloadSaveableMessage(message, callback);
            else callback.finished(null, "Bài đăng hạn chế lưu hoặc xuất video");
            return;
        }
        try {
            JSONObject request = request("getMessageProperties");
            request.put("chat_id", message.getLong("chat_id"));
            request.put("message_id", message.getLong("id"));
            send(request, properties -> {
                if ("messageProperties".equals(properties.optString("@type"))
                        && properties.optBoolean("can_be_saved", false)) {
                    downloadSaveableMessage(message, callback);
                } else {
                    callback.finished(null, "Không xác nhận được quyền lưu video của bài đăng");
                }
            });
        } catch (Exception error) {
            callback.finished(null, "Không xác nhận được quyền lưu video của bài đăng");
        }
    }

    private void downloadSaveableMessage(JSONObject message, MessageVideoResult callback) {
        if (callback.isCancelled()) return;
        if (!running || flow.state() != TelegramAuthorizationFlow.State.READY || logoutCompletion != null) {
            callback.finished(null, "Phiên Telegram không còn sẵn sàng");
            return;
        }
        JSONObject content = message.optJSONObject("content");
        if (content == null || !"messageVideo".equals(content.optString("@type"))) {
            callback.finished(null, "Bài đăng không chứa video dạng tệp được hỗ trợ");
            return;
        }
        JSONObject video = content.optJSONObject("video");
        JSONObject file = video == null ? null : video.optJSONObject("video");
        if (file == null) {
            callback.finished(null, "Không nhận được metadata video từ TDLib");
            return;
        }
        long size = Math.max(file.optLong("size", 0), file.optLong("expected_size", 0));
        if (size > LIMIT_BYTES) {
            callback.finished(null, "Video vượt giới hạn 2 GB");
            return;
        }
        int id = file.optInt("id", -1);
        if (id <= 0) {
            callback.finished(null, "Video không có file ID");
            return;
        }
        downloads.put(id, callback);
        try {
            JSONObject request = request("downloadFile");
            request.put("file_id", id);
            request.put("priority", 16);
            request.put("offset", 0);
            request.put("limit", 0);
            request.put("synchronous", false);
            send(request, result -> {
                if ("error".equals(result.optString("@type"))) {
                    if (downloads.remove(id, callback)) callback.finished(null, messageError(result, "Không tải được tệp"));
                } else onFile(result);
            });
        } catch (Exception error) {
            downloads.remove(id, callback);
            callback.finished(null, "Không thể gửi yêu cầu tải video");
        }
    }

    private void onFile(JSONObject file) {
        if (file == null || !"file".equals(file.optString("@type"))) return;
        int id = file.optInt("id", -1);
        MessageVideoResult callback = downloads.get(id);
        if (callback == null) return;
        if (Math.max(file.optLong("size", 0), file.optLong("expected_size", 0)) > LIMIT_BYTES) {
            if (!downloads.remove(id, callback)) return;
            main.post(() -> callback.finished(null, "Video vượt giới hạn 2 GB"));
            return;
        }
        JSONObject local = file.optJSONObject("local");
        if (local != null && local.optBoolean("is_downloading_completed", false)) {
            if (!downloads.remove(id, callback)) return;
            String path = local.optString("path", "");
            main.post(() -> callback.finished(path.isEmpty() ? null : path,
                    path.isEmpty() ? "TDLib không trả về đường dẫn video" : null));
        } else if (local != null) {
            long bytes = local.optLong("downloaded_size");
            long total = Math.max(file.optLong("size"), file.optLong("expected_size"));
            main.post(() -> {
                if (!callback.isCancelled()) callback.progress(bytes, total);
            });
        }
    }

    @Override public void cancelMessageVideo(MessageVideoResult callback) {
        downloads.forEach((id, waiting) -> {
            if (waiting != callback || !downloads.remove(id, waiting)) return;
            JSONObject cancel = request("cancelDownloadFile");
            try {
                cancel.put("file_id", id);
                cancel.put("only_if_pending", false);
                send(cancel, ignored -> {});
            } catch (org.json.JSONException ignored) {
                // The UI has already dropped this transfer's callback.
            }
        });
    }

    private String messageError(JSONObject response, String fallback) {
        // Only use the sanitized TDLib code, not raw text or private message content.
        return fallback + " (TDLib " + response.optInt("code", -1) + ")";
    }

    @Override public void logoutAndClearSession(Completion callback) {
        if (!running || !nativeLoaded || logoutCompletion != null) {
            callback.finished("Không thể xác nhận đăng xuất ở trạng thái hiện tại");
            return;
        }
        logoutCompletion = callback;
        send(request("logOut"), response -> {
            if (!"ok".equals(response.optString("@type"))) {
                finishLogout(messageError(response, "Đăng xuất thất bại"));
            }
        });
    }

    private void finishLogout(String error) {
        Completion completion = logoutCompletion;
        logoutCompletion = null;
        if (completion != null) completion.finished(error);
    }
}
