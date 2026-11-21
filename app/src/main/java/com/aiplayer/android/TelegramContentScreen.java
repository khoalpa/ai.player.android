package com.aiplayer.android;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class TelegramContentScreen {
    private static final int HISTORY_LIMIT = 25;
    private final MainActivity activity;
    private final AppSettings settings;
    private final SavedVideoStore store;
    private final Supplier<TelegramSessionClient> session;
    private final Runnable login;
    private final Consumer<VideoSourceResolver.Source> download;
    private final SharedPreferences history;
    private final TelegramPostTranslation postTranslation;
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final List<Future<?>> jobs = new ArrayList<>();
    private Dialog dialog;
    private AlertDialog postLanguageDialog;
    private Runnable pendingPostLoad;
    private Runnable pendingChannelLoad;
    private ScrollView contentScroll;
    private String currentAddress;
    private int pendingScroll = -1;
    private String pendingPost;
    private int pendingPostOffset;
    private final Map<String, View> postCards = new LinkedHashMap<>();
    private final java.util.Set<String> translatedPosts = new java.util.HashSet<>();
    private final java.util.Set<String> expandedPosts = new java.util.HashSet<>();
    private Consumer<TelegramLink> openPostLink;
    private Runnable loadNearEnd;
    private InlinePlayback playback;
    private final java.util.Map<String, Long> playbackPositions = new java.util.HashMap<>();
    private final java.util.Map<String, String> unavailableEmbeds = new java.util.HashMap<>();
    private String draft = "";
    private int generation;

    TelegramContentScreen(MainActivity activity, AppSettings settings, SavedVideoStore store,
                          Supplier<TelegramSessionClient> session, Runnable login,
                          Consumer<VideoSourceResolver.Source> download) {
        this.activity = activity;
        this.settings = settings;
        this.store = store;
        this.session = session;
        this.login = login;
        this.download = download;
        history = activity.getSharedPreferences("telegram_link_history", Context.MODE_PRIVATE);
        postTranslation = new TelegramPostTranslation(activity);
    }

    void share(CharSequence text) {
        List<String> links = TelegramLink.extract(text);
        if (links.size() == 1) {
            open(links.get(0), this::input);
        } else if (links.isEmpty()) {
            Toast.makeText(activity, "Nội dung chia sẻ chưa có liên kết Telegram.", Toast.LENGTH_SHORT).show();
        } else {
            dismiss();
            dialog = new PlayerAppearance.DialogBuilder(activity, settings)
                    .setTitle("Chọn liên kết Telegram")
                    .setItems(links.toArray(new String[0]), (d, which) -> open(links.get(which), this::input))
                    .setNegativeButton("Đóng", null).show();
        }
    }

    void openAddress(String address) {
        open(address, this::input);
    }

    Bundle saveState() {
        if (currentAddress == null || contentScroll == null || dialog == null || !dialog.isShowing()) return null;
        Bundle state = new Bundle();
        state.putString("address", currentAddress);
        state.putInt("scroll", pendingScroll >= 0 ? pendingScroll : contentScroll.getScrollY());
        if (pendingPost != null) {
            state.putString("post", pendingPost);
            state.putInt("postOffset", pendingPostOffset);
        } else {
            int y = contentScroll.getScrollY();
            for (Map.Entry<String, View> entry : postCards.entrySet()) {
                View card = entry.getValue();
                if (!card.isAttachedToWindow() || card.getHeight() == 0) continue;
                Rect bounds = postBounds(contentScroll, card);
                if (bounds.bottom <= y || bounds.top >= y + contentScroll.getHeight()) continue;
                state.putString("post", entry.getKey());
                state.putInt("postOffset", y - bounds.top);
                break;
            }
        }
        state.putStringArrayList("translated", new ArrayList<>(translatedPosts));
        state.putStringArrayList("expanded", new ArrayList<>(expandedPosts));
        return state;
    }

    void restoreState(Bundle state) {
        String address = state.getString("address");
        if (address == null) return;
        openAddress(address);
        applySavedPosition(state);
    }

    private void applySavedPosition(Bundle state) {
        if (contentScroll == null) return;
        pendingScroll = state.getInt("scroll", -1);
        pendingPost = state.getString("post");
        pendingPostOffset = state.getInt("postOffset");
        ArrayList<String> translated = state.getStringArrayList("translated");
        ArrayList<String> expanded = state.getStringArrayList("expanded");
        if (translated != null) translatedPosts.addAll(translated);
        if (expanded != null) expandedPosts.addAll(expanded);
    }

    private static Rect postBounds(ScrollView scroll, View card) {
        Rect bounds = new Rect(0, 0, card.getWidth(), card.getHeight());
        scroll.offsetDescendantRectToMyCoords(card, bounds);
        return bounds;
    }

    void input() {
        dismiss();
        OpenSourceSheet input = new OpenSourceSheet(activity, settings, "Mở Telegram");
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setTextSize(settings.textSize(16));
        field.setHint("Liên kết hoặc @username");
        field.setContentDescription("Liên kết hoặc @username");
        field.setText(draft);
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));

        Button paste = input.button("Dán", false);
        LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(-2, -2);
        pasteParams.setMarginStart(dp(PlayerAppearance.CONTROL_GAP_DP));
        row.addView(paste, pasteParams);
        paste.setOnClickListener(view -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            android.content.ClipData data = clipboard == null ? null : clipboard.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return;
            CharSequence text = data.getItemAt(0).coerceToText(activity);
            if (text == null) return;
            field.setText(text);
            field.setSelection(field.length());
        });
        input.content().addView(row, new LinearLayout.LayoutParams(-1, -2));
        input.addAction("Lịch sử liên kết", R.drawable.ic_control_library, false,
                () -> showHistory(field.getText().toString()));
        Button open = input.addAction("Mở liên kết", 0, true, () -> {
            draft = field.getText().toString();
            openInput(draft, field);
        });
        open.setEnabled(!draft.trim().isEmpty());
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                open.setEnabled(!text.toString().trim().isEmpty());
            }
            @Override public void afterTextChanged(android.text.Editable text) {}
        });
        dialog = input;
        input.show();
    }

    private void openInput(String value, EditText field) {
        try {
            open(TelegramLink.parse(value), this::input);
        } catch (Exception invalid) {
            List<String> links = TelegramLink.extract(value);
            if (links.isEmpty()) field.setError("Hãy nhập URL Telegram hoặc @username.");
            else share(value);
        }
    }

    private void open(String address, Runnable back) {
        try {
            open(TelegramLink.parse(address), back);
        } catch (Exception invalid) {
            Toast.makeText(activity, "Liên kết Telegram không hợp lệ.", Toast.LENGTH_SHORT).show();
        }
    }

    private void open(TelegramLink link, Runnable back) {
        dismiss();
        draft = link.url.toString();
        currentAddress = draft;
        remember(draft);
        int request = generation;
        openPostLink = target -> {
            if (valid(request) && !link.url.equals(target.url)) {
                open(target, () -> open(link, back));
            }
        };
        LinearLayout layout = column();
        PlayerAppearance.addPageHeader(layout, settings, "Nội dung Telegram", back);
        ScrollView scroll = new ScrollView(activity) {
            private float touchStart;

            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    touchStart = event.getY();
                } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE
                        && Math.abs(touchStart - event.getY()) > dp(8)) {
                    pendingScroll = -1;
                    pendingPost = null;
                }
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                        && touchStart - event.getY() >= dp(24) && loadNearEnd != null) loadNearEnd.run();
                return super.dispatchTouchEvent(event);
            }
        };
        contentScroll = scroll;
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (y > oldY && pendingPost == null && pendingScroll < 0 && loadNearEnd != null) {
                loadNearEnd.run();
            }
            if (playback != null) playback.checkVisibility();
        });
        LinearLayout body = column(0);
        scroll.addView(body);
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (contentScroll != scroll || scroll.getHeight() == 0) return;
            if (pendingPost != null) {
                View card = postCards.get(pendingPost);
                if (card != null && card.isAttachedToWindow() && card.getHeight() > 0) {
                    int offset = Math.min(pendingPostOffset, card.getHeight() - 1);
                    scroll.scrollTo(0, postBounds(scroll, card).top + offset);
                    pendingPost = null;
                    pendingScroll = -1;
                }
                return;
            }
            if (pendingScroll < 0) return;
            int target = pendingScroll;
            scroll.scrollTo(0, target);
            if (scroll.getScrollY() == target) pendingScroll = -1;
        });
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        AlertDialog screen = new PlayerAppearance.DialogBuilder(activity, settings).setView(layout).create();
        dialog = screen;
        screen.setOnCancelListener(d -> back.run());
        screen.setOnDismissListener(d -> {
            if (dialog == screen) dismiss();
        });
        screen.show();
        if (screen.getWindow() != null) screen.getWindow().setLayout(-1, -1);
        label(body, link.isPrivatePost() ? "Bài đăng riêng tư" : link.url.toString());
        if (!link.isComment()) showSaved(body, link);
        LinearLayout content = column(0);
        body.addView(content);

        if (link.isComment()) {
            label(content, "Bản xem trước bình luận chưa khả dụng.");
            addTelegramFallback(content, link);
        } else if (link.isPrivatePost()) {
            loadAccount(link, content, request);
        } else if (link.isInvite()) {
            showInvite(link, content, request);
        } else if (link.isPublicPost() || link.channel != null || link.isBotProfile()) {
            label(content, "Đang tải nội dung Telegram…");
            jobs.add(network.submit(() -> {
                try {
                    TelegramPublicContent result = TelegramPublicContent.load(link);
                    activity.runOnUiThread(() -> {
                        if (!valid(request)) return;
                        if (link.isPublicPost() && result.posts.isEmpty()) {
                            showPublicPostUnavailable(link, content, request, back);
                        } else {
                            render(link, content, result, request);
                        }
                    });
                } catch (Exception failure) {
                    activity.runOnUiThread(() -> {
                        if (!valid(request)) return;
                        if (link.isBotProfile()) {
                            render(link, content, new TelegramPublicContent(), request);
                            return;
                        }
                        if (link.isPublicPost()) {
                            showPublicPostUnavailable(link, content, request, back);
                            return;
                        }
                        content.removeAllViews();
                        label(content, "Chưa tải được bản xem trước Telegram.");
                        addTelegramOpenActions(content, "Thử lại", link, () -> {
                            Bundle state = saveState();
                            open(link, back);
                            if (state != null) applySavedPosition(state);
                        });
                        if (link.channel != null) {
                            LinearLayout recent = column(0);
                            content.addView(recent);
                            if (connected()) {
                                action(recent, "Tải bằng tài khoản Telegram",
                                        () -> loadRecentPosts(link, recent, request));
                            } else {
                                loadRecentPosts(link, recent, request);
                            }
                        }
                    });
                }
            }));
        } else {
            label(content, "Liên kết này chưa có nội dung xem trước.");
            addTelegramFallback(content, link);
        }
    }

    private void showPublicPostUnavailable(TelegramLink link, LinearLayout body, int request, Runnable back) {
        body.removeAllViews();
        label(body, "Chưa tải được bản xem trước Telegram.");
        addTelegramOpenActions(body, "Thử lại", link, () -> open(link, back));
        action(body, connected() ? "Tải bằng tài khoản Telegram" : "Kết nối Telegram", () -> {
            if (!valid(request)) return;
            if (connected()) {
                loadAccount(link, body, request);
            } else {
                pendingPostLoad = () -> loadAccount(link, body, request);
                login.run();
            }
        });
    }

    private void loadAccount(TelegramLink link, LinearLayout body, int request) {
        if (!valid(request)) return;
        body.removeAllViews();
        Runnable retry = () -> loadAccount(link, body, request);
        if (!(session.get() instanceof TdJsonTelegramSessionClient) || !connected()) {
            pendingPostLoad = retry;
            label(body, "Kết nối tài khoản Telegram có quyền truy cập để xem bài đăng.");
            addTelegramOpenActions(body, "Kết nối Telegram", link, login);
            return;
        }
        label(body, "Đang tải bài bằng tài khoản Telegram…");
        TdJsonTelegramSessionClient client = (TdJsonTelegramSessionClient) session.get();
        client.previewMessage(link, (result, denied) -> {
            if (!valid(request)) return;
            body.removeAllViews();
            if (session.get() != client || !connected()) {
                loadAccount(link, body, request);
            } else if (result != null) {
                renderPosts(body, result.posts, request, true);
            } else if (denied) {
                label(body, "Tài khoản Telegram hiện tại không có quyền truy cập bài đăng.");
                action(body, "Mở trong Telegram", true, () -> activity.openTelegramPost(link.source.url));
            } else {
                label(body, "Chưa tải được bài đăng bằng tài khoản Telegram.");
                addTelegramOpenActions(body, "Thử lại", link, retry);
            }
        });
    }

    private boolean connected() {
        TelegramSessionClient client = session.get();
        return client != null && client.authorizationState() == TelegramAuthorizationFlow.State.READY;
    }

    private void showInvite(TelegramLink link, LinearLayout body, int request) {
        LinearLayout preview = column(0);
        LinearLayout controls = column(0);
        body.addView(preview);
        body.addView(controls);
        ((LinearLayout.LayoutParams) controls.getLayoutParams()).topMargin = dp(PlayerAppearance.GROUP_GAP_DP);
        jobs.add(network.submit(() -> {
            try {
                TelegramPublicContent data = TelegramPublicContent.load(link);
                activity.runOnUiThread(() -> {
                    if (!valid(request) || preview.getParent() != body) return;
                    preview.removeAllViews();
                    if (!data.title.isEmpty()) label(preview, data.title);
                    image(preview, data.image, request);
                    if (!data.description.isEmpty()) label(preview, data.description);
                });
            } catch (Exception ignored) {
                // TDLib can still check an invite without a web preview.
            }
        }));
        checkInvite(link, body, preview, controls, request, false);
    }

    private void checkInvite(TelegramLink link, LinearLayout body, LinearLayout preview,
                             LinearLayout controls, int request, boolean retrying) {
        if (!valid(request)) return;
        controls.removeAllViews();
        Runnable retry = () -> checkInvite(link, body, preview, controls, request, true);
        TelegramSessionClient active = session.get();
        if (!(active instanceof TdJsonTelegramSessionClient) || !connected()) {
            pendingChannelLoad = retry;
            label(controls, "Lời mời tham gia Telegram");
            action(controls, "Đăng nhập Telegram", true, login);
            addTelegramFallback(controls, link);
            return;
        }
        TdJsonTelegramSessionClient client = (TdJsonTelegramSessionClient) active;
        String address = "https://t.me" + link.url.getRawPath().replaceFirst("/$", "")
                .replaceFirst("^/joinchat/", "/+");
        if (retrying) {
            addTelegramOpenActions(controls, "Đang kiểm tra…", link, retry).setEnabled(false);
        } else {
            label(controls, "Đang kiểm tra lời mời…");
        }
        client.checkInvite(address, info -> {
            if (!valid(request)) return;
            if (session.get() != client || !connected()) {
                retry.run();
                return;
            }
            if (!"chatInviteLinkInfo".equals(info.optString("@type"))) {
                showInviteError(controls, link, info, retry);
                return;
            }
            if (preview.getChildCount() == 0 && !info.optString("title").isEmpty()) {
                label(preview, info.optString("title"));
            }
            long chatId = info.optLong("chat_id");
            Runnable showJoin = () -> {
                if (client.isInvitePending(address)) {
                    showJoinPending(controls, link, retry);
                    return;
                }
                controls.removeAllViews();
                if (info.optJSONObject("subscription_info") != null) {
                    label(controls, "Lời mời này yêu cầu trả phí.");
                    addTelegramFallback(controls, link);
                } else {
                    boolean approval = info.optBoolean("creates_join_request");
                    label(controls, "Lời mời tham gia Telegram");
                    addTelegramOpenActions(controls, approval ? "Gửi yêu cầu tham gia" : "Tham gia", link, () -> {
                        if (session.get() != client || !connected()) {
                            retry.run();
                            return;
                        }
                        controls.removeAllViews();
                        label(controls, approval ? "Đang gửi yêu cầu…" : "Đang tham gia…");
                        client.joinInvite(address, result -> {
                            if (!valid(request)) return;
                            if (session.get() != client || !connected()) {
                                retry.run();
                            } else if ("chat".equals(result.optString("@type")) && result.optLong("id") != 0) {
                                openInvitedChat(client, result.optLong("id"), link, body, request);
                            } else if (client.isInvitePending(address)) {
                                showJoinPending(controls, link, retry);
                            } else {
                                showInviteError(controls, link, result, retry);
                            }
                        });
                    });
                }
            };
            if (chatId == 0) {
                showJoin.run();
                return;
            }
            client.checkInviteMembership(chatId, member -> {
                if (!valid(request)) return;
                if (session.get() != client || !connected()) {
                    retry.run();
                } else if (member == null) {
                    showInviteError(controls, link, new JSONObject(), retry);
                } else if (member) {
                    if (pendingPost != null) {
                        openInvitedChat(client, chatId, link, body, request);
                        return;
                    }
                    controls.removeAllViews();
                    JSONObject type = info.optJSONObject("type");
                    boolean channel = type == null ? info.optBoolean("is_channel")
                            : "inviteLinkChatTypeChannel".equals(type.optString("@type"));
                    addTelegramOpenActions(controls, channel ? "Mở kênh" : "Mở nhóm", link,
                            () -> openInvitedChat(client, chatId, link, body, request));
                } else {
                    showJoin.run();
                }
            });
        });
    }

    private void showJoinPending(LinearLayout controls, TelegramLink link, Runnable retry) {
        controls.removeAllViews();
        label(controls, "Đang chờ phê duyệt");
        action(controls, "Kiểm tra lại", retry);
        addTelegramFallback(controls, link);
    }

    private void showInviteError(LinearLayout controls, TelegramLink link, JSONObject response, Runnable retry) {
        controls.removeAllViews();
        String error = response.optString("message");
        if ("INVITE_HASH_EXPIRED".equals(error) || "INVITE_HASH_INVALID".equals(error)
                || "INVITE_HASH_EMPTY".equals(error)) {
            label(controls, "Lời mời không còn hiệu lực.");
            addTelegramFallback(controls, link);
        } else {
            label(controls, "Chưa xác nhận được trạng thái lời mời.");
            addTelegramOpenActions(controls, "Thử lại", link, retry);
        }
    }

    private void openInvitedChat(TdJsonTelegramSessionClient client, long chatId, TelegramLink link,
                                 LinearLayout body, int request) {
        body.removeAllViews();
        LinearLayout posts = column(0);
        LinearLayout footer = column(0);
        body.addView(posts);
        body.addView(footer);
        ((LinearLayout.LayoutParams) footer.getLayoutParams()).topMargin = dp(PlayerAppearance.GROUP_GAP_DP);
        addTelegramFallback(body, link);
        class InviteHistory {
            void load(long before) {
                if (!valid(request)) return;
                if (session.get() != client || !connected()) {
                    body.removeAllViews();
                    showInvite(link, body, request);
                    return;
                }
                footer.removeAllViews();
                label(footer, before == 0 ? "Đang tải nội dung Telegram…" : "Đang tải bài cũ hơn…");
                client.previewInvitedChat(chatId, before, (result, oldest) -> {
                    if (!valid(request)) return;
                    if (session.get() != client || !connected()) {
                        body.removeAllViews();
                        showInvite(link, body, request);
                        return;
                    }
                    footer.removeAllViews();
                    if (result == null) {
                        label(footer, before == 0 ? "Chưa tải được nội dung Telegram."
                                : "Chưa tải được bài cũ hơn.");
                        action(footer, "Thử lại", true, () -> load(before));
                        return;
                    }
                    if (before == 0 && !result.title.isEmpty()) label(posts, result.title);
                    renderPage(result);
                    if (oldest == 0) {
                        label(footer, before == 0 ? "Chưa có bài đăng để hiển thị."
                                : "Không còn bài cũ hơn mà tài khoản này có thể xem.");
                        finishRestoreIfMissing();
                    } else if (before == 0 && result.posts.size() == 1) {
                        load(oldest);
                    } else {
                        if (pendingPost != null && !postCards.containsKey(pendingPost)) {
                            long anchor = TelegramPostPagination.inviteMessageId(pendingPost);
                            if (anchor > 0 && anchor < oldest) {
                                load(oldest);
                                return;
                            }
                            finishRestoreIfMissing();
                        }
                        action(footer, "Tải bài cũ hơn", true, () -> load(oldest));
                    }
                });
            }

            void renderPage(TelegramPublicContent result) {
                for (TelegramPublicContent.Post post : result.posts) {
                    if (post.url.startsWith("tdlib:")) {
                        LinearLayout card = column(0);
                        posts.addView(card);
                        postCards.put(post.url, card);
                        label(card, formatDate(post.date));
                        image(card, post.image, post.previewFile, request, null);
                        if (!post.text.isEmpty()) postText(card, post, null);
                        if (!post.attachment.isEmpty()) label(card, post.attachment);
                        if (post.video) label(card, "Mở trong Telegram để phát video này.");
                    } else {
                        renderPosts(posts, java.util.Collections.singletonList(post), request);
                    }
                }
            }
        }
        new InviteHistory().load(0);
    }

    void onReady() {
        if (pendingChannelLoad != null) {
            Runnable load = pendingChannelLoad;
            pendingChannelLoad = null;
            load.run();
        } else if (pendingPostLoad != null) {
            Runnable load = pendingPostLoad;
            pendingPostLoad = null;
            load.run();
        }
    }

    private void render(TelegramLink link, LinearLayout body, TelegramPublicContent data, int request) {
        body.removeAllViews();
        if (!data.title.isEmpty()) label(body, data.title);
        image(body, data.image, request);
        if (!data.description.isEmpty()) label(body, data.description);
        if (link.isBotProfile()) {
            label(body, "Mở cuộc trò chuyện trong Telegram để gửi tin nhắn cho bot.");
            action(body, "Mở bot trong Telegram", () -> activity.openTelegramBot(link));
        } else if (link.channel != null && data.posts.isEmpty()) {
            LinearLayout recent = column(0);
            body.addView(recent);
            loadRecentPosts(link, recent, request);
            addTelegramFallback(body, link);
        } else if (link.channel != null) {
            showRecentPosts(link, body, data.posts, request, null);
            addTelegramFallback(body, link);
        } else {
            if (data.posts.size() > 1) label(body, "Các mục trong album");
            renderPosts(body, data.posts, request);
        }
    }

    private void loadRecentPosts(TelegramLink link, LinearLayout body, int request) {
        if (!valid(request)) return;
        body.removeAllViews();
        if (!(session.get() instanceof TdJsonTelegramSessionClient) || !connected()) {
            label(body, "Bản xem trước web chưa có danh sách bài. Kết nối Telegram để tải bài gần đây.");
            action(body, "Kết nối Telegram", () -> {
                pendingChannelLoad = () -> loadRecentPosts(link, body, request);
                login.run();
            });
            return;
        }
        label(body, "Đang tải bài gần đây qua Telegram…");
        TdJsonTelegramSessionClient client = (TdJsonTelegramSessionClient) session.get();
        client.previewRecentPosts(link.channel, result -> {
            if (!valid(request) || session.get() != client || !connected()) return;
            body.removeAllViews();
            if (result == null) {
                label(body, "Chưa truy cập được bài gần đây bằng tài khoản đã kết nối.");
                action(body, "Thử lại", () -> loadRecentPosts(link, body, request));
            } else if (result.posts.isEmpty()) {
                label(body, "Telegram chưa trả về bài đăng có thể hiển thị.");
                action(body, "Tải lại", () -> loadRecentPosts(link, body, request));
            } else {
                showRecentPosts(link, body, result.posts, request, client);
            }
        });
    }

    private void showRecentPosts(TelegramLink link, LinearLayout body,
                                 List<TelegramPublicContent.Post> items, int request,
                                 TdJsonTelegramSessionClient client) {
        LinearLayout posts = column(0);
        body.addView(posts);
        LinearLayout footer = column(0);
        body.addView(footer);
        ((LinearLayout.LayoutParams) footer.getLayoutParams()).topMargin = dp(PlayerAppearance.GROUP_GAP_DP);
        TelegramPostPagination pagination = new TelegramPostPagination(items);
        class Pages {
            boolean loading;
            boolean ended;
            boolean failed;
            boolean useAccount = client != null;

            void next() {
                if (!valid(request) || loading || ended) return;
                long before = pagination.before();
                if (before <= 1) {
                    ended = true;
                    footer.removeAllViews();
                    finishRestoreIfMissing();
                    return;
                }
                loading = true;
                failed = false;
                footer.removeAllViews();
                label(footer, "Đang tải bài cũ hơn…");
                TdJsonTelegramSessionClient pageClient = useAccount && connected()
                        && session.get() instanceof TdJsonTelegramSessionClient
                        ? (TdJsonTelegramSessionClient) session.get() : null;
                Consumer<TelegramPublicContent> complete = result -> {
                    if (!valid(request)) return;
                    loading = false;
                    footer.removeAllViews();
                    if (result == null || (useAccount && (session.get() != pageClient || !connected()))) {
                        failed = true;
                        label(footer, "Không tải được bài cũ hơn.");
                        action(footer, "Thử lại", this::next);
                        return;
                    }
                    List<TelegramPublicContent.Post> older = pagination.acceptOlder(result.posts);
                    if (older.isEmpty()) {
                        if (!useAccount && connected() && session.get() instanceof TdJsonTelegramSessionClient) {
                            useAccount = true;
                            next();
                            return;
                        }
                        ended = true;
                        finishRestoreIfMissing();
                        label(footer, useAccount ? "Đã hết bài có thể hiển thị."
                                : "Bản xem trước web không có thêm bài.");
                        return;
                    }
                    renderPosts(posts, older, request);
                    action(footer, "Tải bài cũ hơn", this::next);
                    continueRestore();
                };
                if (useAccount) {
                    if (pageClient == null) {
                        complete.accept(null);
                    } else {
                        pageClient.previewRecentPosts(link.channel, before, complete);
                    }
                } else {
                    jobs.add(network.submit(() -> {
                        TelegramPublicContent result;
                        try {
                            result = TelegramPublicContent.loadOlder(link, before);
                        } catch (Exception failure) {
                            result = null;
                        }
                        TelegramPublicContent page = result;
                        activity.runOnUiThread(() -> complete.accept(page));
                    }));
                }
            }

            void continueRestore() {
                if (pendingPost == null || postCards.containsKey(pendingPost)) return;
                if (pagination.mayContain(TelegramPostPagination.postId(pendingPost))) {
                    posts.post(() -> {
                        if (valid(request) && pendingPost != null && !postCards.containsKey(pendingPost)
                                && pagination.mayContain(TelegramPostPagination.postId(pendingPost))) next();
                    });
                } else {
                    finishRestoreIfMissing();
                }
            }

        }
        Pages pages = new Pages();
        renderPosts(posts, items, request);
        action(footer, "Tải bài cũ hơn", pages::next);
        pages.continueRestore();
        ScrollView scroll = contentScroll;
        loadNearEnd = () -> {
            int remaining = scroll.getChildAt(0).getBottom() - scroll.getHeight() - scroll.getScrollY();
            if (remaining <= dp(96) && !pages.failed) pages.next();
        };
        scroll.post(() -> {
            if (valid(request) && pendingPost == null && !scroll.canScrollVertically(1)
                    && loadNearEnd != null) loadNearEnd.run();
        });
    }

    private void finishRestoreIfMissing() {
        if (pendingPost != null && !postCards.containsKey(pendingPost)) {
            pendingPost = null;
            pendingScroll = -1;
        }
    }

    private void renderPosts(LinearLayout body, List<TelegramPublicContent.Post> posts,
                             int request) {
        renderPosts(body, posts, request, false);
    }

    private void renderPosts(LinearLayout body, List<TelegramPublicContent.Post> posts,
                             int request, boolean accountPost) {
        if (accountPost && posts.size() > 1) label(body, "Các mục trong album");
        int shown = 0;
        boolean hasVideo = false;
        for (TelegramPublicContent.Post post : posts) {
            try {
                TelegramLink item = TelegramLink.parse(post.url);
                File local = saved(item);
                hasVideo |= post.video || local != null;
                shown++;
                String meta = String.join(" · ", post.author, formatDate(post.date))
                        .replaceFirst("^( · )|( · )$", "").trim();
                LinearLayout card = column(4);
                PlayerAppearance.styleSurface(card, PlayerAppearance.color(activity,
                        android.R.attr.colorBackgroundFloating), false);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.bottomMargin = dp(12);
                body.addView(card, params);
                postCards.put(post.url, card);
                PostTranslationControls translation = TelegramPostTranslation.hasText(post.text)
                        ? new PostTranslationControls(post, request) : null;
                if (post.video || local != null || translation != null) {
                    LinearLayout header = new LinearLayout(activity);
                    header.setOrientation(LinearLayout.HORIZONTAL);
                    header.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    TextView timestamp = new TextView(activity);
                    timestamp.setText(meta);
                    timestamp.setTextSize(15);
                    header.addView(timestamp, new LinearLayout.LayoutParams(0, -2, 1));
                    boolean video = post.video || local != null;
                    Button menu = addPostMenu(header, item, video && post.canSave,
                            video && local == null && post.saveVerified,
                            video && local == null && post.canSave && !post.saveVerified, translation);
                    if (!video) menu.setVisibility(View.GONE);
                    if (translation != null) translation.menu = menu;
                    card.addView(header, new LinearLayout.LayoutParams(-1, -2));
                } else if (!meta.isEmpty()) {
                    label(card, meta);
                }
                String page = TelegramPublicContent.playerPage(item);
                boolean nativeStream = connected() && !item.isComment()
                        && (item.isPublicPost() || item.isPrivatePost());
                String playbackRestriction = local == null && !nativeStream
                        ? unavailableEmbeds.get(item.url.toString()) : null;
                Consumer<android.widget.FrameLayout> mediaAction = local != null ? frame -> showNativePlayer(frame, local, item, null)
                        : !post.video ? null
                        : nativeStream ? frame -> showNativePlayer(frame, null, item, page)
                        : page == null || playbackRestriction != null ? null
                        : frame -> showEmbeddedPlayer(frame, item, page, false);
                image(card, post.image, post.previewFile, request, mediaAction);
                if (accountPost && (post.video || local != null) && (local != null || post.saveVerified)) {
                    action(card, "Phát video", true, () -> openInMainPlayer(item, post.saveVerified));
                }
                if (post.video && mediaAction == null) {
                    label(card, playbackRestriction == null
                            ? "Bài này chưa có nguồn phát trong AI Player." : playbackRestriction);
                    addTelegramFallback(card, item);
                }
                if (post.video && local == null && !post.canSave) {
                    label(card, "Telegram hạn chế lưu video này.");
                }
                if (!post.text.isEmpty()) postText(card, post, translation);
                if (!post.attachment.isEmpty() && !"Ảnh".equals(post.attachment)) {
                    label(card, post.attachment);
                } else if (!post.video && post.text.isEmpty() && post.image.isEmpty()
                        && post.previewFile == 0 && post.attachment.isEmpty()) {
                    label(card, "Tệp đính kèm chưa có bản xem trước.");
                }
            } catch (Exception invalid) {
                // A malformed preview item does not prevent the rest of the page from loading.
            }
        }
        if (shown == 0) label(body, "Chưa có bài đăng để hiển thị.");
        else if (accountPost && !hasVideo) label(body, "Bài đăng này không có video.");
    }

    private Button addPostMenu(LinearLayout header, TelegramLink link, boolean canShareFile,
                               boolean canDownload, boolean canVerify, PostTranslationControls translation) {
        Button menu = new Button(activity);
        menu.setText("⋯");
        menu.setTextSize(22);
        menu.setTextColor(PlayerAppearance.color(activity, android.R.attr.textColorPrimary));
        menu.setBackground(null);
        menu.setStateListAnimator(null);
        menu.setPadding(0, 0, 0, 0);
        menu.setGravity(android.view.Gravity.CENTER);
        menu.setContentDescription("Tùy chọn bài đăng");
        menu.setOnClickListener(view -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(activity, view);
            if (translation != null && TelegramPostTranslation.supports(translation.source)) {
                popup.getMenu().add("Đổi ngôn ngữ nguồn").setEnabled(!translation.busy)
                        .setOnMenuItemClickListener(item -> {
                            translation.chooseSource();
                            return true;
                        });
            }
            boolean canRequestSave = connected() && (canDownload || canVerify);
            if (saved(link) != null || canRequestSave) {
                popup.getMenu().add("Mở trong trình phát chính").setOnMenuItemClickListener(item -> {
                    openInMainPlayer(link, canRequestSave);
                    return true;
                });
            }
            File file = canShareFile ? saved(link) : null;
            if (file != null) popup.getMenu().add("Chia sẻ video").setOnMenuItemClickListener(item -> {
                shareSavedVideo(file);
                return true;
            });
            popup.getMenu().add("Chia sẻ liên kết bài gốc").setOnMenuItemClickListener(item -> {
                android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, link.url.toString());
                launchShareSheet(send, "Chia sẻ liên kết bài gốc");
                return true;
            });
            if (canDownload) popup.getMenu().add("Tải video").setOnMenuItemClickListener(item -> {
                startDownload(link);
                return true;
            });
            if (canVerify && connected() && session.get() instanceof TdJsonTelegramSessionClient) {
                popup.getMenu().add("Kiểm tra quyền lưu").setOnMenuItemClickListener(item -> {
                    int request = generation;
                    TdJsonTelegramSessionClient client = (TdJsonTelegramSessionClient) session.get();
                    client.checkSavePermission(link, allowed -> {
                        if (!valid(request) || session.get() != client) return;
                        AlertDialog.Builder result = new PlayerAppearance.DialogBuilder(activity, settings)
                                .setTitle("Quyền lưu video").setNegativeButton("Đóng", null);
                        if (allowed) result.setPositiveButton("Tải video", (d, w) -> startDownload(link));
                        else result.setMessage("Telegram chưa xác nhận quyền lưu video.");
                        result.show();
                    });
                    return true;
                });
            }
            popup.getMenu().add("Xem bài gốc").setOnMenuItemClickListener(item -> {
                activity.openTelegramPost(link.source.url);
                return true;
            });
            popup.show();
        });
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return menu;
    }

    private void openInMainPlayer(TelegramLink link, boolean canRequestSave) {
        String key = link.url.toString();
        long currentPosition = playback != null && key.equals(playback.postUrl)
                ? playback.position.getAsLong() : -1;
        TelegramPlaybackHandoff handoff = new TelegramPlaybackHandoff(currentPosition,
                playbackPositions.getOrDefault(key, -1L), (file, position) -> {
            dismiss();
            activity.openSavedVideo(file, position);
        });
        File existing = saved(link);
        if (existing != null) {
            handoff.open(existing);
            return;
        }
        if (!canRequestSave) {
            new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Mở trong trình phát chính")
                    .setMessage("Telegram chưa cho phép lưu video này. Bạn có thể tiếp tục xem trong bài viết.")
                    .setPositiveButton("Đóng", null).show();
            return;
        }
        int request = generation;
        activity.saveTelegramForPlayback(link.source, (file, error) -> {
            if (!valid(request)) return;
            if (file != null) {
                handoff.open(file);
            } else {
                new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Chưa mở được video")
                        .setMessage(error).setNegativeButton("Đóng", null)
                        .setPositiveButton("Mở trong Telegram", (dialog, which) ->
                                activity.openTelegramPost(link.source.url)).show();
            }
        });
    }

    private void addTelegramFallback(LinearLayout container, TelegramLink link) {
        action(container, "Mở trong Telegram", () -> activity.openTelegramPost(link.source.url));
    }

    private Button addTelegramOpenActions(LinearLayout container, String title, TelegramLink link, Runnable open) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        Button inPlayer = actionButton(title, true, open);
        Button inTelegram = actionButton("Mở trong Telegram", false,
                () -> activity.openTelegramPost(link.source.url));
        inPlayer.setMaxLines(2);
        inTelegram.setMaxLines(2);
        row.addView(inPlayer, new LinearLayout.LayoutParams(0, -1, 2));
        LinearLayout.LayoutParams externalParams = new LinearLayout.LayoutParams(0, -1, 3);
        externalParams.setMarginStart(dp(PlayerAppearance.CONTROL_GAP_DP));
        row.addView(inTelegram, externalParams);
        container.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return inPlayer;
    }

    private void shareSavedVideo(File file) {
        try {
            if (!file.isFile() || !store.directory().getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) {
                Toast.makeText(activity, "Tệp video đã lưu không còn khả dụng.", Toast.LENGTH_SHORT).show();
                return;
            }
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".transcript-exports", file);
            String filename = file.getName();
            int separator = filename.lastIndexOf('.');
            String extension = separator < 0 ? "" : filename.substring(separator + 1).toLowerCase(Locale.ROOT);
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType(mime != null && mime.startsWith("video/") ? mime : "video/*")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri(filename, uri));
            launchShareSheet(send, "Chia sẻ video");
        } catch (java.io.IOException | IllegalArgumentException error) {
            Toast.makeText(activity, "Không thể chia sẻ tệp video đã lưu.", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchShareSheet(android.content.Intent send, String title) {
        try {
            activity.startActivity(android.content.Intent.createChooser(send, title));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(activity, "Không có ứng dụng nhận nội dung chia sẻ.", Toast.LENGTH_SHORT).show();
        }
    }

    private final class InlinePlayback {
        final android.widget.FrameLayout frame;
        final View cover;
        final LinearLayout layout = column();
        final Button closeButton = new Button(activity);
        Runnable pause = () -> {};
        Runnable release = () -> {};
        String postUrl;
        java.util.function.LongSupplier position = () -> -1;
        android.app.Dialog fullscreen;
        Runnable exitFullscreen;
        Integer previousOrientation;

        InlinePlayback(android.widget.FrameLayout frame) {
            this.frame = frame;
            cover = frame.getChildAt(0);
        }

        boolean visible() {
            return fullscreen != null || (frame.isShown() && frame.getGlobalVisibleRect(new android.graphics.Rect()));
        }

        void checkVisibility() {
            if (!visible()) pause.run();
        }
    }

    private InlinePlayback beginInlinePlayback(android.widget.FrameLayout frame) {
        stopInlinePlayback();
        InlinePlayback inline = new InlinePlayback(frame);
        playback = inline;
        inline.cover.setVisibility(View.GONE);
        frame.addView(inline.layout, new android.widget.FrameLayout.LayoutParams(-1, -2));
        Button close = inline.closeButton;
        close.setText("×");
        close.setTextSize(22);
        close.setTextColor(android.graphics.Color.WHITE);
        close.setContentDescription("Đóng trình phát");
        close.setGravity(android.view.Gravity.CENTER);
        close.setPadding(0, 0, 0, 0);
        close.setStateListAnimator(null);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        background.setColor(0xB0000000);
        close.setBackground(background);
        close.setOnClickListener(view -> stopInlinePlayback());
        frame.addView(close, new android.widget.FrameLayout.LayoutParams(dp(48), dp(48),
                android.view.Gravity.TOP | android.view.Gravity.END));
        return inline;
    }

    void stopInlinePlayback() {
        InlinePlayback previous = playback;
        playback = null;
        if (previous == null) return;
        closeFullscreen(previous);
        previous.release.run();
        previous.frame.removeView(previous.layout);
        previous.frame.removeView(previous.closeButton);
        previous.cover.setVisibility(View.VISIBLE);
        if (previous.previousOrientation != null) {
            activity.setRequestedOrientation(previous.previousOrientation);
        }
    }

    private void rotateVideo(InlinePlayback inline) {
        if (playback != inline) return;
        if (inline.previousOrientation == null) inline.previousOrientation = activity.getRequestedOrientation();
        activity.toggleVideoOrientation();
    }

    private void addRotateControls(androidx.media3.ui.PlayerView view, InlinePlayback inline) {
        for (int id : new int[]{androidx.media3.ui.R.id.exo_fullscreen,
                androidx.media3.ui.R.id.exo_minimal_fullscreen}) {
            View button = view.findViewById(id);
            if (button != null && button.getParent() instanceof LinearLayout) {
                PlayerAppearance.styleVideoControl(activity.addRotateButton(view, id, () -> rotateVideo(inline)),
                        settings.raisedControls);
            }
        }
    }

    private void openFullscreen(InlinePlayback inline, View view, Runnable onExit) {
        android.app.Dialog dialog = new android.app.Dialog(activity,
                android.R.style.Theme_Material_NoActionBar_Fullscreen);
        inline.fullscreen = dialog;
        inline.exitFullscreen = onExit;
        dialog.setContentView(view, new android.view.ViewGroup.LayoutParams(-1, -1));
        if (this.dialog != null && this.dialog.getWindow() != null && dialog.getWindow() != null) {
            int secure = android.view.WindowManager.LayoutParams.FLAG_SECURE;
            if ((this.dialog.getWindow().getAttributes().flags & secure) != 0) {
                dialog.getWindow().addFlags(secure);
            }
        }
        dialog.setOnDismissListener(ignored -> {
            if (inline.fullscreen == dialog) closeFullscreen(inline);
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(-1, -1);
            dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void closeFullscreen(InlinePlayback inline) {
        android.app.Dialog fullscreen = inline.fullscreen;
        if (fullscreen == null) return;
        inline.fullscreen = null;
        Runnable exit = inline.exitFullscreen;
        inline.exitFullscreen = null;
        fullscreen.dismiss();
        exit.run();
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void showNativePlayer(android.widget.FrameLayout frame, File file, TelegramLink link, String page) {
        InlinePlayback inline = beginInlinePlayback(frame);
        LinearLayout layout = inline.layout;
        android.view.Window playbackWindow = file == null && dialog != null ? dialog.getWindow() : null;
        int secureFlag = android.view.WindowManager.LayoutParams.FLAG_SECURE;
        boolean addedSecureFlag = playbackWindow != null && (playbackWindow.getAttributes().flags & secureFlag) == 0;
        if (addedSecureFlag) playbackWindow.addFlags(secureFlag);
        LinearLayout status = column();
        layout.addView(status);
        androidx.media3.ui.PlayerView surface = new androidx.media3.ui.PlayerView(activity);
        layout.addView(surface, new LinearLayout.LayoutParams(-1, dp(230)));
        androidx.media3.exoplayer.ExoPlayer player = new androidx.media3.exoplayer.ExoPlayer.Builder(activity).build();
        surface.setPlayer(player);
        addRotateControls(surface, inline);
        inline.postUrl = link.url.toString();
        inline.position = () -> player.getCurrentMediaItem() == null ? -1 : player.getCurrentPosition();
        surface.setFullscreenButtonClickListener(fullscreen -> {
            if (!fullscreen || playback != inline || inline.fullscreen != null) return;
            androidx.media3.ui.PlayerView expanded = new androidx.media3.ui.PlayerView(activity);
            addRotateControls(expanded, inline);
            expanded.setFullscreenButtonClickListener(ignored -> closeFullscreen(inline));
            expanded.setFullscreenButtonState(true);
            openFullscreen(inline, expanded, () -> {
                surface.setFullscreenButtonState(false);
                if (playback == inline) {
                    androidx.media3.ui.PlayerView.switchTargetView(player, expanded, surface);
                    inline.checkVisibility();
                } else {
                    expanded.setPlayer(null);
                }
            });
            androidx.media3.ui.PlayerView.switchTargetView(player, surface, expanded);
        });
        class Playback implements androidx.media3.common.Player.Listener {
            Runnable cancel;

            void load() {
                status.removeAllViews();
                label(status, "Đang chuẩn bị video…");
                if (file != null) {
                    open(android.net.Uri.fromFile(file));
                    return;
                }
                TelegramSessionClient client = session.get();
                if (client != null && connected() && client.isNativeAvailable()) {
                    prepareTelegramFile(client);
                    return;
                }
                failure("Cần kết nối Telegram để chuẩn bị video.");
            }

            void prepareTelegramFile(TelegramSessionClient client) {
                TelegramSessionClient.MessageVideoResult transfer = new TelegramSessionClient.MessageVideoResult() {
                    @Override public boolean isCancelled() { return playback != inline; }

                    @Override public void progress(long bytes, long total) {
                        if (isCancelled() || total <= 0) return;
                        status.removeAllViews();
                        label(status, "Đang chuẩn bị video · " + Math.min(100, (int) (100.0 * bytes / total)) + "%");
                    }

                    @Override public void finished(String path, String error) {
                        if (isCancelled()) return;
                        cancel = null;
                        if (error != null) {
                            failure(error);
                            return;
                        }
                        File cached = path == null ? null : new File(path);
                        if (session.get() != client || !connected() || cached == null || !cached.isFile()) {
                            failure("Tệp video Telegram chưa sẵn sàng.");
                            return;
                        }
                        open(android.net.Uri.fromFile(cached));
                    }
                };
                cancel = () -> client.cancelMessageVideo(transfer);
                client.prepareMessageVideoForPlayback(link.url.toString(), transfer);
            }

            void open(android.net.Uri uri) {
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri));
                player.seekTo(playbackPositions.getOrDefault(link.url.toString(), 0L));
                player.prepare();
                layout.post(() -> {
                    if (playback == inline && inline.visible()) player.play();
                });
            }

            void failure(String reason) {
                status.removeAllViews();
                label(status, reason);
                addTelegramFallback(status, link);
                if (!reason.startsWith("Bài đăng hạn chế lưu")
                        && !reason.startsWith("Không xác nhận được quyền lưu")) {
                    action(status, "Thử lại", this::load);
                }
                if (page != null && !unavailableEmbeds.containsKey(link.url.toString())) action(status, "Thử trình xem Telegram", () -> {
                    showEmbeddedPlayer(frame, link, page, true);
                });
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (playback == inline && state == androidx.media3.common.Player.STATE_READY) {
                    status.removeAllViews();
                }
            }

            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                if (playback == inline) failure("Trình phát chưa mở được video. Hãy thử lại.");
            }
        }
        Playback media = new Playback();
        player.addListener(media);
        inline.pause = player::pause;
        inline.release = () -> {
            if (player.getCurrentMediaItem() != null) {
                playbackPositions.put(link.url.toString(), player.getCurrentPosition());
            }
            if (media.cancel != null) media.cancel.run();
            surface.setPlayer(null);
            player.release();
            if (addedSecureFlag) playbackWindow.clearFlags(secureFlag);
        };
        media.load();
    }

    private void showEmbeddedPlayer(android.widget.FrameLayout frame, TelegramLink link, String page,
                                    boolean hasNativeOption) {
        InlinePlayback inline = beginInlinePlayback(frame);
        LinearLayout layout = inline.layout;
        android.webkit.WebView web;
        try {
            web = new android.webkit.WebView(activity);
        } catch (RuntimeException error) {
            label(layout, "Không khởi tạo được trình xem Telegram.");
            addTelegramFallback(layout, link);
            action(layout, "Thử lại", () -> showEmbeddedPlayer(frame, link, page, hasNativeOption));
            return;
        }
        android.webkit.WebSettings options = web.getSettings();
        options.setJavaScriptEnabled(true);
        options.setAllowFileAccess(false);
        options.setAllowContentAccess(false);
        options.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        options.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        options.setMediaPlaybackRequiresUserGesture(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setDownloadListener((url, agent, disposition, mime, size) -> { });
        LinearLayout status = column();
        layout.addView(status);
        layout.addView(web, new LinearLayout.LayoutParams(-1, dp(230)));
        Runnable load = () -> {
            status.removeAllViews();
            label(status, "Đang mở trình xem Telegram…");
            web.setVisibility(View.VISIBLE);
            web.loadUrl(page);
        };
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (playback != inline || inline.fullscreen != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                android.widget.FrameLayout container = new android.widget.FrameLayout(activity);
                container.setBackgroundColor(android.graphics.Color.BLACK);
                container.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
                android.widget.ImageButton rotate = new android.widget.ImageButton(activity);
                rotate.setImageResource(R.drawable.ic_control_rotate);
                rotate.setContentDescription("Xoay ngang/dọc");
                PlayerAppearance.styleVideoControl(rotate, settings.raisedControls);
                rotate.setOnClickListener(button -> rotateVideo(inline));
                container.addView(rotate, new android.widget.FrameLayout.LayoutParams(dp(48), dp(48),
                        android.view.Gravity.TOP | android.view.Gravity.END));
                openFullscreen(inline, container, () -> {
                    container.removeView(view);
                    callback.onCustomViewHidden();
                });
            }

            @Override public void onHideCustomView() {
                closeFullscreen(inline);
            }
        });
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public boolean onRenderProcessGone(android.webkit.WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                closeFullscreen(inline);
                if (view.getParent() == layout) {
                    layout.removeView(view);
                    view.destroy();
                }
                if (playback == inline) {
                    inline.pause = () -> stopInlinePlayback();
                    inline.release = () -> {};
                    status.removeAllViews();
                    label(status, "Trình xem Telegram đã dừng.");
                    addTelegramFallback(status, link);
                    action(status, "Thử lại", () -> showEmbeddedPlayer(frame, link, page, hasNativeOption));
                }
                return true;
            }

            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view,
                    android.webkit.WebResourceRequest request) {
                if (page.equals(request.getUrl().toString())) return false;
                if (request.isForMainFrame() && request.isRedirect()) unavailable();
                return true;
            }

            private void unavailable() {
                unavailable("Video chưa thể phát trong AI Player.");
            }

            private void unavailable(String message) {
                if (playback != inline) return;
                unavailableEmbeds.put(link.url.toString(), message);
                frame.post(() -> {
                    if (playback != inline) return;
                    stopInlinePlayback();
                    // A card with native playback keeps its independent play action.
                    if (!hasNativeOption) {
                        android.widget.FrameLayout preview = (android.widget.FrameLayout) inline.cover;
                        preview.getChildAt(0).setOnClickListener(null);
                        preview.getChildAt(0).setClickable(false);
                        preview.getChildAt(0).setContentDescription(null);
                        if (preview.getChildCount() > 1) preview.removeViewAt(1);
                    }
                    if (frame.getParent() instanceof LinearLayout) {
                        LinearLayout card = (LinearLayout) frame.getParent();
                        LinearLayout fallback = column();
                        label(fallback, message);
                        addTelegramFallback(fallback, link);
                        card.addView(fallback, card.indexOfChild(frame) + 1);
                    }
                });
            }

            @Override public void onPageFinished(android.webkit.WebView view, String url) {
                if (playback != inline || view.getParent() != layout || !page.equals(url)
                        || web.getVisibility() != View.VISIBLE) return;
                view.evaluateJavascript(
                        "(function(){"
                        + "var error=document.querySelector('.tgme_widget_message_error,.tgme_widget_message_error_text');"
                        + "var text=error ? error.innerText : document.body && document.body.innerText;"
                        + "return !!text && /^this channel can['’]t be displayed because it was used to spread pornographic content\\.?$/i"
                        + ".test(text.trim().replace(/\\s+/g,' '));"
                        + "})()", blocked -> {
                            if (playback != inline || view.getParent() != layout || web.getVisibility() != View.VISIBLE
                                    || !page.equals(view.getUrl())) return;
                            if ("true".equals(blocked)) {
                                unavailable("Telegram chặn hiển thị kênh này trong trình xem nhúng.");
                            } else {
                                status.removeAllViews();
                            }
                        });
            }

            private void retry() {
                if (playback != inline) return;
                web.setVisibility(View.GONE);
                status.removeAllViews();
                label(status, "Không tải được trình xem Telegram.");
                addTelegramFallback(status, link);
                action(status, "Thử lại", load);
            }

            @Override public void onReceivedError(android.webkit.WebView view,
                    android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) retry();
            }

            @Override public void onReceivedHttpError(android.webkit.WebView view,
                    android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                int code = response.getStatusCode();
                if (code == 401 || code == 403 || code == 404 || code == 410) unavailable();
                else retry();
            }
        });
        inline.pause = () -> stopInlinePlayback();
        inline.release = () -> {
            if (web.getParent() != layout) return;
            web.stopLoading();
            web.onPause();
            layout.removeView(web);
            web.destroy();
        };
        load.run();
    }

    private void postText(LinearLayout card, TelegramPublicContent.Post post, PostTranslationControls translation) {
        TextView content = new TextView(activity);
        content.setTextSize(settings.textSize(15));
        content.setTextIsSelectable(true);
        setPostText(content, post.text, post.links);
        content.setMaxLines(expandedPosts.contains(post.url) ? Integer.MAX_VALUE : 6);
        content.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(content, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button expand = postTextAction();
        expand.setText(content.getMaxLines() == 6 ? "Xem thêm" : "Thu gọn");
        expand.setPadding(0, 0, dp(12), 0);
        expand.setVisibility(View.GONE);
        expand.setOnClickListener(view -> {
            boolean collapsed = content.getMaxLines() == 6;
            if (collapsed) expandedPosts.add(post.url);
            else expandedPosts.remove(post.url);
            content.setMaxLines(collapsed ? Integer.MAX_VALUE : 6);
            expand.setText(collapsed ? "Thu gọn" : "Xem thêm");
        });
        row.addView(expand, new LinearLayout.LayoutParams(-2, -2));
        row.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1));
        if (translation != null) {
            translation.content = content;
            translation.action = postTextAction();
            translation.action.setVisibility(View.GONE);
            row.addView(translation.action, new LinearLayout.LayoutParams(-2, -2));
        }
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (translation != null) {
            translation.originalLinks = new TextView(activity);
            translation.originalLinks.setTextSize(settings.textSize(15));
            translation.originalLinks.setPadding(0, 0, 0, dp(8));
            translation.originalLinks.setVisibility(View.GONE);
            card.addView(translation.originalLinks, new LinearLayout.LayoutParams(-1, -2));
        }
        content.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            android.text.Layout layout = content.getLayout();
            int lines = layout == null ? 0 : layout.getLineCount();
            boolean expandable = lines > 6 || (lines > 0 && layout.getEllipsisCount(lines - 1) > 0);
            expand.setVisibility(expandable ? View.VISIBLE : View.GONE);
        });

        if (translation != null) translation.initialize();
    }

    private final class PostTranslationControls {
        private final TelegramPublicContent.Post post;
        private final int request;
        private TextView content;
        private TextView originalLinks;
        private Button action;
        private Button menu;
        private String source;
        private boolean busy;

        PostTranslationControls(TelegramPublicContent.Post post, int request) {
            this.post = post;
            this.request = request;
        }

        void initialize() {
            String selected = postTranslation.selectedSource(post.url, post.text);
            if (selected != null) {
                setSource(selected);
                return;
            }
            postTranslation.identify(post.text).addOnCompleteListener(result -> {
                if (!valid(request)) return;
                setSource(result.isSuccessful() ? TelegramPostTranslation.supportedCode(result.getResult()) : null);
            });
        }

        private void setSource(String language) {
            source = language;
            if ("vi".equals(source)) return;
            if (source == null) {
                showAction("Chọn ngôn ngữ để dịch", this::chooseSource);
                return;
            }
            menu.setVisibility(View.VISIBLE);
            if (translatedPosts.contains(post.url)) {
                String cached = postTranslation.cached(post.url, post.text, source);
                if (cached != null) {
                    showTranslation(cached, true);
                    return;
                }
                translatedPosts.remove(post.url);
            }
            showAction("Dịch sang tiếng Việt", this::beginTranslation);
        }

        void chooseSource() {
            if (busy || !valid(request)) return;
            Locale vietnamese = Locale.forLanguageTag("vi");
            java.text.Collator collator = java.text.Collator.getInstance(vietnamese);
            List<String> languages = new ArrayList<>(
                    com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages());
            languages.removeIf(language -> !TelegramPostTranslation.supports(language));
            java.util.function.Function<String, String> name =
                    language -> Locale.forLanguageTag(language).getDisplayLanguage(vietnamese);
            languages.sort((a, b) -> collator.compare(name.apply(a), name.apply(b)));
            String[] names = languages.stream().map(name).toArray(String[]::new);
            postLanguageDialog = new PlayerAppearance.DialogBuilder(activity, settings)
                    .setTitle("Chọn ngôn ngữ nguồn")
                    .setSingleChoiceItems(names, languages.indexOf(source), (picker, which) -> {
                        picker.dismiss();
                        if (!valid(request)) return;
                        source = languages.get(which);
                        postTranslation.selectSource(post.url, post.text, source);
                        menu.setVisibility(View.VISIBLE);
                        translatedPosts.remove(post.url);
                        setPostText(content, post.text, post.links);
                        originalLinks.setVisibility(View.GONE);
                        beginTranslation();
                    }).setNegativeButton("Hủy", null).create();
            postLanguageDialog.setOnDismissListener(picker -> {
                if (postLanguageDialog == picker) postLanguageDialog = null;
            });
            postLanguageDialog.show();
        }

        private void beginTranslation() {
            String cached = postTranslation.cached(post.url, post.text, source);
            if (cached != null) {
                showTranslation(cached, true);
                return;
            }
            showAction("Đang dịch…", null);
            postTranslation.modelsReady(source).addOnCompleteListener(result -> {
                if (!valid(request)) return;
                if (!result.isSuccessful()) {
                    showAction("Thử dịch lại", this::beginTranslation);
                } else if (result.getResult()) {
                    translate(false);
                } else {
                    showAction("Tải model dịch", () -> translate(true));
                }
            });
        }

        private void translate(boolean downloadModel) {
            showAction(downloadModel ? "Đang tải model và dịch…" : "Đang dịch…", null);
            postTranslation.translate(post.url, post.text, source, downloadModel).addOnCompleteListener(result -> {
                if (!valid(request)) return;
                if (result.isSuccessful()) {
                    showTranslation(result.getResult(), true);
                } else {
                    showAction("Thử dịch lại", this::beginTranslation);
                }
            });
        }

        private void showTranslation(String translated, boolean show) {
            if (show) translatedPosts.add(post.url);
            else translatedPosts.remove(post.url);
            setPostText(content, show ? translated : post.text,
                    show ? java.util.Collections.emptyList() : post.links);
            if (show) {
                SpannableString original = linkifyPostText(post.text, post.links);
                URLSpan[] spans = original.getSpans(0, original.length(), URLSpan.class);
                java.util.Arrays.sort(spans, java.util.Comparator.comparingInt(original::getSpanStart));
                android.text.SpannableStringBuilder links = new android.text.SpannableStringBuilder();
                java.util.Set<String> urls = new java.util.HashSet<>();
                for (URLSpan span : spans) {
                    if (!urls.add(span.getURL())) continue;
                    links.append(links.length() == 0 ? "Liên kết trong bài gốc\n" : "\n");
                    int start = links.length();
                    links.append(original.toString(), original.getSpanStart(span), original.getSpanEnd(span));
                    links.setSpan(new URLSpan(span.getURL()), start, links.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                displayPostLinks(originalLinks, links);
                originalLinks.setVisibility(links.length() == 0 ? View.GONE : View.VISIBLE);
            } else {
                originalLinks.setVisibility(View.GONE);
            }
            showAction(show ? "Xem bản gốc" : "Dịch sang tiếng Việt", () -> showTranslation(translated, !show));
        }

        private void showAction(String label, Runnable click) {
            busy = click == null;
            postTranslationAction(action, label, click);
        }
    }

    private void setPostText(TextView content, String text, List<TelegramPublicContent.TextLink> links) {
        displayPostLinks(content, linkifyPostText(text, links));
    }

    private SpannableString linkifyPostText(String text, List<TelegramPublicContent.TextLink> links) {
        SpannableString linked = new SpannableString(text);
        android.text.util.Linkify.addLinks(linked, android.text.util.Linkify.WEB_URLS);
        for (TelegramPublicContent.TextLink link : links) {
            for (URLSpan span : linked.getSpans(link.start, link.end, URLSpan.class)) {
                linked.removeSpan(span);
            }
            linked.setSpan(new URLSpan(link.url), link.start, link.end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return linked;
    }

    private void displayPostLinks(TextView content, android.text.Spannable linked) {
        Consumer<TelegramLink> navigate = openPostLink;
        for (URLSpan span : linked.getSpans(0, linked.length(), URLSpan.class)) {
            TelegramLink target;
            try {
                target = TelegramLink.parse(span.getURL());
            } catch (Exception otherLink) {
                continue;
            }
            int start = linked.getSpanStart(span);
            int end = linked.getSpanEnd(span);
            int flags = linked.getSpanFlags(span);
            linked.removeSpan(span);
            linked.setSpan(new URLSpan(span.getURL()) {
                @Override public void onClick(View widget) {
                    navigate.accept(target);
                }
            }, start, end, flags);
        }
        content.setText(linked);
        content.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }

    private Button postTextAction() {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setTextSize(settings.textSize(15));
        button.setTextColor(PlayerAppearance.color(activity, android.R.attr.textColorLink));
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        button.setStateListAnimator(null);
        button.setMinimumHeight(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private static void postTranslationAction(Button button, String label, Runnable click) {
        button.setText(label);
        button.setVisibility(View.VISIBLE);
        button.setEnabled(click != null);
        button.setOnClickListener(click == null ? null : view -> click.run());
    }

    private static String formatDate(String date) {
        try {
            return java.time.OffsetDateTime.parse(date).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        } catch (java.time.DateTimeException invalid) {
            return date;
        }
    }

    private void showSaved(LinearLayout body, TelegramLink link) {
        File file = saved(link);
        if (file != null) action(body, "Phát video đã lưu", () -> openInMainPlayer(link, false));
    }

    private File saved(TelegramLink link) { return store.findBySource(SavedVideoStore.telegramKey(link.source)); }

    private void startDownload(TelegramLink link) {
        remember(link.url.toString());
        dismiss();
        download.accept(link.source);
    }

    private void image(LinearLayout body, String address, int request) {
        image(body, address, 0, request, null);
    }

    private android.widget.FrameLayout image(LinearLayout body, String address, int fileId, int request,
                       Consumer<android.widget.FrameLayout> mediaAction) {
        boolean remote = allowedImage(address);
        if (!remote && fileId == 0 && mediaAction == null) return null;
        android.widget.FrameLayout preview = new android.widget.FrameLayout(activity);
        body.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        ImageView image = new ImageView(activity);
        image.setAdjustViewBounds(true);
        image.setMaxHeight(dp(260));
        image.setVisibility(View.GONE);
        android.widget.FrameLayout cover = new android.widget.FrameLayout(activity);
        preview.addView(cover, new android.widget.FrameLayout.LayoutParams(-1, -2));
        cover.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -2));
        if (mediaAction != null) {
            image.setOnClickListener(view -> mediaAction.accept(preview));
            android.widget.ImageButton button = new android.widget.ImageButton(activity);
            button.setImageResource(android.R.drawable.ic_media_play);
            button.setColorFilter(android.graphics.Color.WHITE);
            button.setContentDescription("Phát video");
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            background.setColor(0xB0000000);
            button.setBackground(background);
            button.setPadding(dp(12), dp(12), dp(12), dp(12));
            button.setOnClickListener(view -> mediaAction.accept(preview));
            cover.addView(button, new android.widget.FrameLayout.LayoutParams(dp(48), dp(48),
                    android.view.Gravity.CENTER));
        }
        if (remote) {
            jobs.add(network.submit(() -> {
                try {
                    byte[] bytes = org.jsoup.Jsoup.connect(address).followRedirects(false).timeout(8_000)
                            .maxBodySize(2 * 1024 * 1024).ignoreContentType(true).execute().bodyAsBytes();
                    displayImage(image, decodePreview(bytes), request, mediaAction == null);
                } catch (Exception ignored) {
                    // Keep the caption and any video action when the thumbnail is unavailable.
                }
            }));
        } else if (fileId != 0 && session.get() instanceof TdJsonTelegramSessionClient) {
            TdJsonTelegramSessionClient client = (TdJsonTelegramSessionClient) session.get();
            client.downloadPreview(fileId, file -> {
                if (!valid(request) || session.get() != client || !connected()) return;
                jobs.add(network.submit(() -> {
                    try {
                        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                        displayImage(image, decodePreview(bytes), request, mediaAction == null);
                    } catch (java.io.IOException ignored) {
                        // The cached preview may have been removed.
                    }
                }));
            });
        }
        return preview;
    }

    private static boolean allowedImage(String address) {
        try {
            URI uri = URI.create(address);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                    && (host.endsWith(".cdn-telegram.org") || host.endsWith(".telegram-cdn.org")
                    || host.endsWith(".telesco.pe") || host.equals("t.me"));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Bitmap decodePreview(byte[] bytes) {
        if (bytes.length > 2 * 1024 * 1024) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1280) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void displayImage(ImageView image, Bitmap bitmap, int request, boolean expandable) {
        if (bitmap == null) return;
        activity.runOnUiThread(() -> {
            if (!valid(request) || !image.isAttachedToWindow()) {
                bitmap.recycle();
                return;
            }
            image.setImageBitmap(bitmap);
            image.setVisibility(View.VISIBLE);
            if (expandable) {
                image.setContentDescription("Xem ảnh toàn màn hình");
                image.setOnClickListener(view -> {
                    ImageView full = new ImageView(activity);
                    full.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    full.setImageBitmap(bitmap);
                    AlertDialog viewer = new PlayerAppearance.DialogBuilder(activity, settings).setView(full)
                            .setNegativeButton("Đóng", null).show();
                    if (viewer.getWindow() != null) viewer.getWindow().setLayout(-1, -1);
                });
            }
        });
    }

    private List<String> history() {
        List<String> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(history.getString("urls", "[]"));
            for (int i = 0; i < array.length(); i++) items.add(array.getString(i));
        } catch (org.json.JSONException ignored) {
            return items;
        }
        return items;
    }

    private void remember(String url) {
        List<String> items = history();
        items.remove(url);
        items.add(0, url);
        if (items.size() > HISTORY_LIMIT) {
            String evicted = items.remove(items.size() - 1);
            history.edit().remove("state:" + evicted).apply();
        }
        writeHistory(items);
    }

    private void writeHistory(List<String> items) {
        history.edit().putString("urls", new JSONArray(items).toString()).apply();
    }

    private void showHistory(String inputDraft) {
        dismiss();
        List<String> items = history();
        LinearLayout list = column();
        for (String url : items) {
            try {
                TelegramLink link = TelegramLink.parse(url);
                String status = !link.isComment() && saved(link) != null
                        ? "Đã lưu" : history.getString("state:" + url, "");
                LinearLayout entry = column(0);
                LinearLayout row = new LinearLayout(activity);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView address = new TextView(activity);
                address.setText(url);
                address.setTextSize(settings.textSize(15));
                address.setSingleLine(true);
                address.setEllipsize(android.text.TextUtils.TruncateAt.END);
                address.setMinHeight(dp(48));
                address.setGravity(android.view.Gravity.CENTER_VERTICAL);
                address.setOnClickListener(view -> open(link, () -> showHistory(inputDraft)));
                row.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
                android.widget.ImageButton remove = new android.widget.ImageButton(activity);
                remove.setImageResource(android.R.drawable.ic_menu_delete);
                remove.setColorFilter(PlayerAppearance.color(activity, android.R.attr.textColorPrimary));
                remove.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                remove.setContentDescription("Xóa khỏi lịch sử");
                remove.setOnClickListener(view -> {
                    items.remove(url);
                    writeHistory(items);
                    history.edit().remove("state:" + url).apply();
                    list.removeView(entry);
                    if (list.getChildCount() == 0) label(list, "Chưa có liên kết Telegram.");
                });
                row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
                entry.addView(row, new LinearLayout.LayoutParams(-1, -2));
                if (!status.isEmpty() && !"Tải lỗi".equals(status)) label(entry, status);
                list.addView(entry, new LinearLayout.LayoutParams(-1, -2));
            } catch (Exception invalid) {
                // Ignore obsolete entries.
            }
        }
        if (list.getChildCount() == 0) label(list, "Chưa có liên kết Telegram.");
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        LinearLayout page = column(0);
        Runnable back = () -> {
            draft = inputDraft;
            input();
        };
        PlayerAppearance.addPageHeader(page, settings, "Lịch sử Telegram", back);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog historyDialog = new PlayerAppearance.DialogBuilder(activity, settings).setView(page).create();
        historyDialog.setOnCancelListener(ignored -> back.run());
        dialog = historyDialog;
        historyDialog.show();
    }

    static void recordTransfer(Context context, VideoSourceResolver.Source source, String status) {
        try {
            String url = TelegramLink.parse(source.url.toExternalForm()).url.toString();
            SharedPreferences history = context.getSharedPreferences("telegram_link_history", Context.MODE_PRIVATE);
            JSONArray urls = new JSONArray(history.getString("urls", "[]"));
            for (int i = 0; i < urls.length(); i++) {
                if (!url.equals(urls.optString(i))) continue;
                history.edit().putString("state:" + url, status).apply();
                break;
            }
        } catch (Exception ignored) {
            // The standalone URL downloader may have no Telegram history entry.
        }
    }

    private LinearLayout column() {
        return column(PlayerAppearance.HORIZONTAL_INSET_DP);
    }

    private LinearLayout column(int horizontalPadding) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(horizontalPadding), dp(4), dp(horizontalPadding), dp(4));
        return layout;
    }

    private void label(LinearLayout parent, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(15);
        label.setTextIsSelectable(true);
        label.setPadding(0, dp(6), 0, dp(6));
        parent.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }

    private void action(LinearLayout parent, String title, Runnable click) {
        action(parent, title, false, click);
    }

    private void action(LinearLayout parent, String title, boolean primary, Runnable click) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        if (parent.getChildCount() != 0) params.topMargin = dp(PlayerAppearance.CONTROL_GAP_DP);
        parent.addView(actionButton(title, primary, click), params);
    }

    private Button actionButton(String title, boolean primary, Runnable click) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(title);
        button.setMinimumHeight(dp(48));
        PlayerAppearance.styleButton(button, primary, settings);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private boolean valid(int request) { return generation == request && dialog != null && dialog.isShowing(); }

    private void dismiss() {
        stopInlinePlayback();
        generation++;
        if (postLanguageDialog != null) postLanguageDialog.dismiss();
        pendingPostLoad = null;
        pendingChannelLoad = null;
        contentScroll = null;
        currentAddress = null;
        pendingScroll = -1;
        pendingPost = null;
        postCards.clear();
        translatedPosts.clear();
        expandedPosts.clear();
        openPostLink = null;
        loadNearEnd = null;
        for (Future<?> job : jobs) job.cancel(true);
        jobs.clear();
        Dialog old = dialog;
        dialog = null;
        if (old != null) old.dismiss();
    }

    void close() {
        dismiss();
        postTranslation.close();
        playbackPositions.clear();
        unavailableEmbeds.clear();
        network.shutdownNow();
    }
}
