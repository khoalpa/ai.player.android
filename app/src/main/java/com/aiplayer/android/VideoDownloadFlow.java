package com.aiplayer.android;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class VideoDownloadFlow {
    private final MainActivity activity;
    private final AppSettings settings;
    private final SavedVideoStore store;
    private final Supplier<TelegramSessionClient> telegram;
    private final Runnable login;
    private final Consumer<URL> openTelegram;
    private final Consumer<File> openVideo;
    private AlertDialog dialog;
    private String address = "";
    private Transfer active;
    private VideoSourceResolver.Source awaitingLogin;
    private VideoSourceResolver.Source failedSource;
    private boolean failedViaAccount;
    private boolean allowPublicFallback;
    private String error;
    private File completed;

    VideoDownloadFlow(MainActivity activity, AppSettings settings, SavedVideoStore store,
                      Supplier<TelegramSessionClient> telegram, Runnable login,
                      Consumer<URL> openTelegram, Consumer<File> openVideo) {
        this.activity = activity;
        this.settings = settings;
        this.store = store;
        this.telegram = telegram;
        this.login = login;
        this.openTelegram = openTelegram;
        this.openVideo = openVideo;
    }

    void show() {
        if (dialog != null && dialog.isShowing()) return;
        if (active != null) showProgress();
        else if (completed != null && completed.isFile()) showCompleted();
        else showInput();
    }

    private void showInput() {
        completed = null;
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(inset, 0, inset, 0);
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setHint("https://example.com/video.mp4 hoặc t.me/channel/123");
        field.setText(address);
        form.addView(field);
        TextView sourceLabel = new TextView(activity);
        form.addView(sourceLabel);
        TextView problem = new TextView(activity);
        form.addView(problem);
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        form.addView(actions);
        if (error != null) problem.setText(error);
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(form);
        AlertDialog input = new PlayerAppearance.DialogBuilder(activity, settings)
                .setTitle("Tải video từ URL").setView(scroll)
                .setNegativeButton("Đóng", null)
                .setNeutralButton("Dán", null)
                .setPositiveButton(failedSource == null ? "Tải" : "Thử lại", null).create();
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                address = s.toString();
                awaitingLogin = null;
                clearError();
                problem.setText("");
                actions.setVisibility(android.view.View.GONE);
                android.widget.Button load = input.getButton(AlertDialog.BUTTON_POSITIVE);
                if (load != null) load.setText("Tải");
                updateSourceLabel(sourceLabel);
            }
            @Override public void afterTextChanged(android.text.Editable value) {}
        });
        updateSourceLabel(sourceLabel);
        if (failedSource != null && failedSource.kind == VideoSourceResolver.Kind.TELEGRAM_PUBLIC_POST) {
            if (failedViaAccount && allowPublicFallback) {
                addAction(actions, "Thử bản công khai", () -> {
                    VideoSourceResolver.Source source = failedSource;
                    if (source != null) begin(source, false);
                });
            } else if (!connected()) {
                addAction(actions, "Đăng nhập Telegram", () -> {
                    if (failedSource == null) return;
                    awaitingLogin = failedSource;
                    dismiss();
                    login.run();
                });
            }
            addAction(actions, "Mở trong Telegram", () -> {
                if (failedSource != null) openTelegram.accept(failedSource.url);
            });
        }
        display(input);
        input.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            android.content.ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                problem.setText("Bộ nhớ tạm chưa có liên kết.");
                return;
            }
            field.setText(clip.getItemAt(0).coerceToText(activity));
            field.setSelection(field.length());
        });
        input.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                VideoSourceResolver.Source source = VideoSourceResolver.classify(address);
                if (source.kind == VideoSourceResolver.Kind.UNSUPPORTED_PAGE) {
                    problem.setText("Hãy dùng link bài đăng kênh Telegram công khai hoặc chia sẻ tệp từ Telegram.");
                    return;
                }
                begin(source, connected() && (failedSource == null || failedViaAccount));
            } catch (Exception invalid) {
                problem.setText("Hãy nhập link video HTTPS hoặc link bài đăng Telegram hợp lệ.");
            }
        });
    }

    private void addAction(LinearLayout form, String label, Runnable action) {
        android.widget.Button button = new android.widget.Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinimumHeight(Math.round(48 * activity.getResources().getDisplayMetrics().density));
        PlayerAppearance.styleButton(button, false, settings);
        button.setOnClickListener(view -> action.run());
        form.addView(button);
    }

    private void updateSourceLabel(TextView label) {
        try {
            VideoSourceResolver.Kind kind = VideoSourceResolver.classify(address).kind;
            label.setText(kind == VideoSourceResolver.Kind.TELEGRAM_PUBLIC_POST ? "Nguồn Telegram"
                    : kind == VideoSourceResolver.Kind.DIRECT_FILE ? "Nguồn URL" : "");
        } catch (Exception invalid) {
            label.setText("");
        }
    }

    private boolean connected() {
        TelegramSessionClient client = telegram.get();
        return client != null && client.isNativeAvailable()
                && client.authorizationState() == TelegramAuthorizationFlow.State.READY;
    }

    void onTelegramReady() {
        if (awaitingLogin != null && active == null) begin(awaitingLogin, true);
    }

    private void begin(VideoSourceResolver.Source source, boolean account) {
        if (active != null) return;
        awaitingLogin = null;
        clearError();
        dismiss();
        String key = SavedVideoStore.telegramKey(source);
        File existing = store.findBySource(key);
        if (existing != null) {
            display(new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Video đã có trong thư viện")
                    .setMessage(store.displayName(existing)).setNegativeButton("Đóng", null)
                    .setPositiveButton("Mở video", (d, w) -> openVideo.accept(existing)).create());
            return;
        }
        boolean telegramSource = key != null;
        Transfer transfer = new Transfer(source, telegramSource && account);
        active = transfer;
        transfer.status = telegramSource
                ? (transfer.viaAccount ? "Đang tải bằng tài khoản Telegram…" : "Đang tìm video công khai…")
                : "Đang kết nối…";
        showProgress();
        if (!telegramSource) {
            download(transfer, source.url.toExternalForm());
        } else if (transfer.viaAccount) {
            transfer.client = telegram.get();
            transfer.telegramResult = new TelegramSessionClient.MessageVideoResult() {
                @Override public void finished(String path, String failure) {
                    if (active != transfer) return;
                    if (failure != null) {
                        boolean fallback = !failure.startsWith("Bài đăng hạn chế")
                                && !failure.startsWith("Không xác nhận được quyền lưu");
                        fail(transfer, failure, fallback);
                        return;
                    }
                    updateProgress(transfer, "Đang lưu video Telegram…");
                    transfer.title = new File(path).getName();
                    new TdlibSavedVideoImporter(activity).importFile(path, () -> transfer.cancelled,
                            (file, problem) -> finish(transfer, file, problem, null));
                }

                @Override public boolean isCancelled() { return transfer.cancelled; }

                @Override public void progress(long bytes, long total) {
                    updateProgress(transfer, progressText(bytes, total));
                }
            };
            new TelegramVideoDownloadGate(transfer.client).download(source.url.toExternalForm(),
                    transfer.telegramResult);
        } else {
            transfer.cancelRequest = new TelegramPublicResolver().resolve(source, (media, failure) -> {
                if (active != transfer) return;
                if (failure != null) {
                    fail(transfer, errorMessage(failure), false);
                    return;
                }
                updateProgress(transfer, "Đang tải video công khai…");
                download(transfer, media);
            });
        }
    }

    private void download(Transfer transfer, String url) {
        SavedVideoStore.Download download = store.download(url,
                (bytes, total) -> updateProgress(transfer, progressText(bytes, total)),
                (file, failure) -> finish(transfer, file, failure, url));
        transfer.cancelRequest = download::cancel;
    }

    private String progressText(long bytes, long total) {
        String size = android.text.format.Formatter.formatShortFileSize(activity, bytes);
        return "Đã tải " + (total > 0 ? (bytes * 100 / total) + "% · " : "") + size;
    }

    private void showProgress() {
        display(new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Đang tải video")
                .setMessage(active.status)
                .setNegativeButton("Hủy tải", (d, w) -> cancel())
                .setPositiveButton("Đóng", null).create());
    }

    private void updateProgress(Transfer transfer, String status) {
        if (active != transfer) return;
        transfer.status = status;
        if (visible()) dialog.setMessage(status);
    }

    private void finish(Transfer transfer, File file, Exception failure, String mediaUrl) {
        if (active != transfer) {
            if (file != null && transfer.cancelled) file.delete();
            return;
        }
        if (failure != null) {
            fail(transfer, errorMessage(failure), false);
            return;
        }
        active = null;
        String key = SavedVideoStore.telegramKey(transfer.source);
        store.remember(file, key == null ? "URL" : "Telegram", key,
                key == null ? SavedVideoStore.nameFromUrl(mediaUrl) : transfer.title != null ? transfer.title
                        : "@" + transfer.source.channel + " · " + transfer.source.postId);
        completed = file;
        if (visible()) showCompleted();
        else android.widget.Toast.makeText(activity, "Đã lưu video", android.widget.Toast.LENGTH_SHORT).show();
    }

    private static String errorMessage(Exception error) {
        if (error instanceof UnknownHostException) return "Không kết nối được máy chủ. Hãy kiểm tra mạng.";
        if (error instanceof SocketTimeoutException) return "Kết nối quá thời gian chờ. Hãy thử lại.";
        if (error instanceof java.net.ConnectException) return "Không kết nối được máy chủ tải video. Hãy thử lại.";
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? "Không thể tải video. Hãy thử lại." : message;
    }

    private void fail(Transfer transfer, String message, boolean fallback) {
        active = null;
        failedSource = transfer.source;
        failedViaAccount = transfer.viaAccount;
        allowPublicFallback = fallback;
        error = message;
        if (visible()) {
            dismiss();
            showInput();
        }
    }

    private void clearError() {
        failedSource = null;
        failedViaAccount = false;
        allowPublicFallback = false;
        error = null;
    }

    private void showCompleted() {
        File file = completed;
        display(new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Đã lưu video")
                .setMessage(store.displayName(file))
                .setNegativeButton("Tải link khác", (d, w) -> {
                    completed = null;
                    dismiss();
                    showInput();
                })
                .setPositiveButton("Mở video", (d, w) -> {
                    completed = null;
                    openVideo.accept(file);
                }).create());
    }

    private boolean visible() { return dialog != null && dialog.isShowing(); }

    private void display(AlertDialog next) {
        dismiss();
        dialog = next;
        PlayerAppearance.showDialog(dialog, settings);
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void cancel() {
        Transfer transfer = active;
        active = null;
        if (transfer == null) return;
        transfer.cancelled = true;
        if (transfer.cancelRequest != null) transfer.cancelRequest.run();
        if (transfer.client != null) transfer.client.cancelMessageVideo(transfer.telegramResult);
    }

    void close() {
        dismiss();
        cancel();
    }

    private static final class Transfer {
        final VideoSourceResolver.Source source;
        final boolean viaAccount;
        String status;
        String title;
        volatile boolean cancelled;
        Runnable cancelRequest;
        TelegramSessionClient client;
        TelegramSessionClient.MessageVideoResult telegramResult;

        Transfer(VideoSourceResolver.Source source, boolean viaAccount) {
            this.source = source;
            this.viaAccount = viaAccount;
        }
    }
}
