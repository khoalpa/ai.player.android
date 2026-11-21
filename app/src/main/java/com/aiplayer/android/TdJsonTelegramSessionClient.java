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
 * Only messages explicitly requested by the user are downloaded.
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

    void detachListener() { stateListener = null; }

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

    @Override public void downloadPrivateMessageVideo(String postUrl, MessageVideoResult callback) {
        resolveMessageVideo(postUrl, callback, false);
    }

    @Override public void prepareMessageVideoForPlayback(String postUrl, MessageVideoResult callback) {
        try {
            TelegramLink link = TelegramLink.parse(postUrl);
            if (link.isComment() || (!link.isPublicPost() && !link.isPrivatePost())) {
                callback.finished(null, "Liên kết này chưa hỗ trợ phát video qua TDLib.");
                return;
            }
            resolveMessageVideo(link.url.toString(), callback, true);
        } catch (Exception error) {
            callback.finished(null, "Liên kết Telegram không hợp lệ");
        }
    }

    private void resolveMessageVideo(String postUrl, MessageVideoResult callback, boolean playback) {
        if (!nativeLoaded || !running || flow.state() != TelegramAuthorizationFlow.State.READY) {
            callback.finished(null, "Bạn cần đăng nhập Telegram");
            return;
        }
        try {
            JSONObject get = request("getMessageLinkInfo");
            get.put("url", postUrl);
            send(get, info -> {
                if (callback.isCancelled()) return;
                JSONObject message = "messageLinkInfo".equals(info.optString("@type"))
                        ? info.optJSONObject("message") : null;
                if (message == null) {
                    callback.finished(null, "Không truy cập được bài đăng. Hãy kiểm tra quyền truy cập của tài khoản Telegram.");
                    return;
                }
                if (playback && "message".equals(message.optString("@type"))) {
                    downloadVideoFile(message, callback, true);
                } else if (playback) {
                    callback.finished(null, "Không truy cập được video bằng tài khoản Telegram.");
                } else {
                    onMessage(message, callback);
                }
            });
        } catch (Exception error) {
            callback.finished(null, "Liên kết Telegram không hợp lệ");
        }
    }

    void checkSavePermission(TelegramLink link, java.util.function.Consumer<Boolean> callback) {
        if (!running || flow.state() != TelegramAuthorizationFlow.State.READY) {
            callback.accept(false);
            return;
        }
        try {
            send(request("getMessageLinkInfo").put("url", link.url.toString()), info -> {
                JSONObject message = info.optJSONObject("message");
                if (message == null || message.has("can_be_saved")) {
                    callback.accept(message != null && message.optBoolean("can_be_saved"));
                    return;
                }
                try {
                    send(request("getMessageProperties").put("chat_id", message.getLong("chat_id"))
                            .put("message_id", message.getLong("id")), properties ->
                            callback.accept("messageProperties".equals(properties.optString("@type"))
                                    && properties.optBoolean("can_be_saved")));
                } catch (org.json.JSONException error) {
                    callback.accept(false);
                }
            });
        } catch (org.json.JSONException error) {
            callback.accept(false);
        }
    }

    private final java.util.Set<String> pendingInvites = java.util.concurrent.ConcurrentHashMap.newKeySet();

    void checkInvite(String link, java.util.function.Consumer<JSONObject> callback) {
        inviteRequest(request("checkChatInviteLink"), "invite_link", link, callback);
    }

    boolean isInvitePending(String link) {
        return pendingInvites.contains(link);
    }

    void joinInvite(String link, java.util.function.Consumer<JSONObject> callback) {
        inviteRequest(request("joinChatByInviteLink"), "invite_link", link, result -> {
            if ("INVITE_REQUEST_SENT".equals(result.optString("message"))) pendingInvites.add(link);
            callback.accept(result);
        });
    }

    void checkInviteMembership(long chatId, java.util.function.Consumer<Boolean> callback) {
        inviteRequest(request("getChat"), "chat_id", chatId, chat -> {
            JSONObject type = chat.optJSONObject("type");
            if (type == null) {
                callback.accept(null);
                return;
            }
            boolean basic = "chatTypeBasicGroup".equals(type.optString("@type"));
            if (!basic && !"chatTypeSupergroup".equals(type.optString("@type"))) {
                callback.accept(null);
                return;
            }
            String key = basic ? "basic_group_id" : "supergroup_id";
            inviteRequest(request(basic ? "getBasicGroup" : "getSupergroup"), key, type.optLong(key), group -> {
                JSONObject status = group.optJSONObject("status");
                if (status == null) {
                    callback.accept(null);
                    return;
                }
                switch (status.optString("@type")) {
                    case "chatMemberStatusMember":
                    case "chatMemberStatusAdministrator":
                        callback.accept(true);
                        break;
                    case "chatMemberStatusCreator":
                    case "chatMemberStatusRestricted":
                        callback.accept(status.optBoolean("is_member"));
                        break;
                    default:
                        callback.accept(false);
                }
            });
        });
    }

    void previewInvitedChat(long chatId, long before,
                            java.util.function.BiConsumer<TelegramPublicContent, Long> callback) {
        inviteRequest(request("getChat"), "chat_id", chatId, chat -> {
            JSONObject type = chat.optJSONObject("type");
            if (type == null) {
                callback.accept(null, before);
                return;
            }
            long supergroup = type.optLong("supergroup_id");
            JSONObject history = request("getChatHistory");
            try {
                history.put("limit", 20).put("from_message_id", before).put("offset", 0).put("only_local", false);
            } catch (org.json.JSONException error) {
                callback.accept(null, before);
                return;
            }
            inviteRequest(history, "chat_id", chatId, response -> {
                org.json.JSONArray messages = response.optJSONArray("messages");
                if (messages == null) {
                    callback.accept(null, before);
                    return;
                }
                TelegramPublicContent result = new TelegramPublicContent();
                result.title = chat.optString("title");
                long oldest = 0;
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message == null) continue;
                    long id = message.optLong("id");
                    if (id <= 0 || (before != 0 && id >= before)) continue;
                    if (oldest == 0 || id < oldest) oldest = id;
                    long postId = id >> 20;
                    String address = supergroup > 0 && postId > 0 && (postId << 20) == id
                            ? "https://t.me/c/" + supergroup + "/" + postId
                            : "tdlib:" + chatId + "/" + id;
                    result.posts.add(previewPost(address, message));
                }
                callback.accept(result, oldest);
            });
        });
    }

    private void inviteRequest(JSONObject data, String key, Object value,
                               java.util.function.Consumer<JSONObject> callback) {
        try {
            data.put(key, value);
        } catch (org.json.JSONException error) {
            callback.accept(new JSONObject());
            return;
        }
        sendPreviewRequest(data, callback);
    }

    private void sendPreviewRequest(JSONObject data, java.util.function.Consumer<JSONObject> callback) {
        if (!running || flow.state() != TelegramAuthorizationFlow.State.READY) {
            callback.accept(new JSONObject());
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable timeout = () -> {
            if (completed.compareAndSet(false, true)) callback.accept(new JSONObject());
        };
        main.postDelayed(timeout, 20_000);
        send(data, result -> {
            if (!completed.compareAndSet(false, true)) return;
            main.removeCallbacks(timeout);
            callback.accept(result);
        });
    }

    void previewRecentPosts(String username, java.util.function.Consumer<TelegramPublicContent> callback) {
        previewRecentPosts(username, 0, callback);
    }

    void previewRecentPosts(String username, long before, java.util.function.Consumer<TelegramPublicContent> callback) {
        if (!running || flow.state() != TelegramAuthorizationFlow.State.READY) {
            callback.accept(null);
            return;
        }
        try {
            JSONObject search = request("searchPublicChat").put("username", username);
            sendPreviewRequest(search, chat -> {
                if (!"chat".equals(chat.optString("@type"))) {
                    callback.accept(null);
                    return;
                }
                try {
                    JSONObject history = request("getChatHistory")
                            .put("chat_id", chat.getLong("id"))
                            .put("from_message_id", before == 0 ? 0 : TelegramMessageReference.toTdlibMessageId(before))
                            .put("offset", 0)
                            .put("limit", 20)
                            .put("only_local", false);
                    sendPreviewRequest(history, response -> {
                        org.json.JSONArray messages = response.optJSONArray("messages");
                        if (!"messages".equals(response.optString("@type")) || messages == null) {
                            callback.accept(null);
                            return;
                        }
                        TelegramPublicContent result = new TelegramPublicContent();
                        result.title = chat.optString("title");
                        for (int i = 0; i < messages.length(); i++) {
                            JSONObject message = messages.optJSONObject(i);
                            if (message == null) continue;
                            long id = message.optLong("id");
                            long postId = id >> 20;
                            if (postId <= 0 || (postId << 20) != id) continue;
                            if (before != 0 && postId >= before) continue;
                            result.posts.add(previewPost("https://t.me/" + username + "/" + postId, message));
                        }
                        callback.accept(result);
                    });
                } catch (org.json.JSONException error) {
                    callback.accept(null);
                }
            });
        } catch (org.json.JSONException error) {
            callback.accept(null);
        }
    }

    void previewMessage(TelegramLink link, java.util.function.BiConsumer<TelegramPublicContent, Boolean> callback) {
        if (!running || flow.state() != TelegramAuthorizationFlow.State.READY) {
            callback.accept(null, false);
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable timeout = () -> {
            if (completed.compareAndSet(false, true)) callback.accept(null, false);
        };
        main.postDelayed(timeout, 20_000);
        java.util.function.BiConsumer<TelegramPublicContent, Boolean> finish = (result, denied) -> {
            if (!completed.compareAndSet(false, true)) return;
            main.removeCallbacks(timeout);
            callback.accept(result, denied);
        };
        JSONObject get = request("getMessageLinkInfo");
        try {
            get.put("url", link.url.toString());
            send(get, info -> {
                JSONObject message = info.optJSONObject("message");
                if (message == null) {
                    finish.accept(null, info.optInt("code") == 403
                            || (info.has("is_accessible") && !info.optBoolean("is_accessible")));
                    return;
                }
                TelegramPublicContent result = new TelegramPublicContent();
                result.posts.add(previewPost(link.url.toString(), message));
                long album = message.optLong("media_album_id");
                if (album == 0 || link.url.getQuery() != null) {
                    finish.accept(result, false);
                    return;
                }
                try {
                    JSONObject history = request("getChatHistory");
                    history.put("chat_id", message.getLong("chat_id"));
                    history.put("from_message_id", message.getLong("id"));
                    history.put("offset", -9);
                    history.put("limit", 20);
                    history.put("only_local", false);
                    send(history, nearby -> {
                        org.json.JSONArray items = nearby.optJSONArray("messages");
                        String prefix = link.url.toString().replaceFirst("/[0-9]+/?$", "/");
                        for (int i = 0; items != null && i < items.length(); i++) {
                            JSONObject member = items.optJSONObject(i);
                            if (member == null || member.optLong("media_album_id") != album) continue;
                            long id = member.optLong("id");
                            long postId = id >> 20;
                            if (id != message.optLong("id") && postId > 0 && (postId << 20) == id) {
                                result.posts.add(previewPost(prefix + postId, member));
                            }
                        }
                        finish.accept(result, false);
                    });
                } catch (org.json.JSONException error) {
                    finish.accept(result, false);
                }
            });
        } catch (org.json.JSONException error) {
            finish.accept(null, false);
        }
    }

    private static TelegramPublicContent.Post previewPost(String url, JSONObject message) {
        JSONObject content = message.optJSONObject("content");
        if (content == null) content = new JSONObject();
        String type = content.optString("@type");
        JSONObject formatted = content.optJSONObject("messageText".equals(type) ? "text" : "caption");
        String text = formatted == null ? "" : formatted.optString("text");
        JSONObject document = content.optJSONObject("document");
        long date = message.optLong("date");
        String timestamp = date > 0 ? java.time.Instant.ofEpochSecond(date).toString() : "";
        boolean video = "messageVideo".equals(type)
                || (document != null && document.optString("mime_type").startsWith("video/"));
        TelegramPublicContent.Post post = new TelegramPublicContent.Post(url, "", timestamp, text, "", video);
        org.json.JSONArray entities = formatted == null ? null : formatted.optJSONArray("entities");
        for (int i = 0; entities != null && i < entities.length(); i++) {
            JSONObject entity = entities.optJSONObject(i);
            JSONObject linkType = entity == null ? null : entity.optJSONObject("type");
            if (linkType == null || !"textEntityTypeTextUrl".equals(linkType.optString("@type"))) continue;
            int start = entity.optInt("offset", -1);
            post.addLink(start, start + entity.optInt("length"), linkType.optString("url"));
        }
        post.canSave = message.optBoolean("can_be_saved", true);
        post.saveVerified = message.optBoolean("can_be_saved", false);
        JSONObject audio = content.optJSONObject("audio");
        if (audio != null) post.attachment = "Âm thanh · " + audio.optString("file_name", "Tệp âm thanh");
        if (document != null && !video) post.attachment = "Tài liệu · " + document.optString("file_name", "Tệp đính kèm");
        JSONObject photo = content.optJSONObject("photo");
        if (photo != null) {
            post.attachment = "Ảnh";
            org.json.JSONArray sizes = photo.optJSONArray("sizes");
            int best = 0;
            for (int i = 0; sizes != null && i < sizes.length(); i++) {
                JSONObject size = sizes.optJSONObject(i);
                if (size == null) continue;
                int width = Math.max(size.optInt("width"), size.optInt("height"));
                int file = previewFileId(size.optJSONObject("photo"));
                if (file != 0 && (best == 0 || (best < 800 && width > best) || (width >= 800 && width < best))) {
                    best = width;
                    post.previewFile = file;
                }
            }
        }
        JSONObject media = content.optJSONObject("video");
        if (video && media == null) media = document;
        JSONObject thumbnail = media == null ? null : media.optJSONObject("thumbnail");
        if (thumbnail != null) post.previewFile = previewFileId(thumbnail.optJSONObject("file"));
        return post;
    }

    private static int previewFileId(JSONObject file) {
        if (file == null) return 0;
        long size = Math.max(file.optLong("size"), file.optLong("expected_size"));
        return size > 0 && size <= 2 * 1024 * 1024 ? file.optInt("id") : 0;
    }

    void downloadPreview(int fileId, java.util.function.Consumer<java.io.File> callback) {
        if (!running || flow.state() != TelegramAuthorizationFlow.State.READY) return;
        try {
            send(request("downloadFile").put("file_id", fileId).put("priority", 1)
                    .put("offset", 0).put("limit", 0).put("synchronous", true), result -> {
                JSONObject local = result.optJSONObject("local");
                if (local == null || !local.optBoolean("is_downloading_completed")) return;
                java.io.File file = new java.io.File(local.optString("path"));
                if (file.isFile() && file.length() <= 2 * 1024 * 1024) callback.accept(file);
            });
        } catch (org.json.JSONException ignored) {
            // The post remains readable when its preview cannot be downloaded.
        }
    }

    private void onMessage(JSONObject message, MessageVideoResult callback) {
        if (!"message".equals(message.optString("@type"))) {
            callback.finished(null, messageError(message, "Không truy cập được bài đăng"));
            return;
        }
        if (message.has("can_be_saved")) {
            if (message.optBoolean("can_be_saved", false)) downloadVideoFile(message, callback, false);
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
                    downloadVideoFile(message, callback, false);
                } else {
                    callback.finished(null, "Không xác nhận được quyền lưu video của bài đăng");
                }
            });
        } catch (Exception error) {
            callback.finished(null, "Không xác nhận được quyền lưu video của bài đăng");
        }
    }

    private void downloadVideoFile(JSONObject message, MessageVideoResult callback, boolean playback) {
        if (callback.isCancelled()) return;
        if (!running || flow.state() != TelegramAuthorizationFlow.State.READY || logoutCompletion != null) {
            callback.finished(null, "Phiên Telegram không còn sẵn sàng");
            return;
        }
        JSONObject content = message.optJSONObject("content");
        JSONObject video = null;
        if (content != null && "messageVideo".equals(content.optString("@type"))) {
            video = content.optJSONObject("video");
        } else if (playback && content != null && "messageDocument".equals(content.optString("@type"))) {
            JSONObject document = content.optJSONObject("document");
            if (document != null && document.optString("mime_type").startsWith("video/")) {
                video = document;
            }
        }
        if (video == null) {
            callback.finished(null, "Bài đăng không chứa video dạng tệp được hỗ trợ");
            return;
        }
        JSONObject file = video.optJSONObject("video");
        if (file == null && playback) file = video.optJSONObject("document");
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
        if (size > 0) {
            JSONObject local = file.optJSONObject("local");
            long localBytes = local == null ? 0 : Math.max(0, local.optLong("downloaded_size"));
            long remaining = Math.max(0, size - localBytes);
            try {
                StorageSpace.require(context, context.getNoBackupFilesDir(),
                        remaining + (playback ? 0 : size) + StorageSpace.VIDEO_RESERVE);
            } catch (java.io.IOException insufficient) {
                callback.finished(null, insufficient.getMessage());
                return;
            }
        }
        if (downloads.putIfAbsent(id, callback) != null) {
            callback.finished(null, "Video đang được tải trong một thao tác khác. Hãy thử lại sau.");
            return;
        }
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
