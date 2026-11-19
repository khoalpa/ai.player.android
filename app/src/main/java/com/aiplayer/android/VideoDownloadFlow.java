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
    private final Context context;
    private MainActivity activity;
    private AppSettings settings;
    private final SavedVideoStore store;
    private Supplier<TelegramSessionClient> telegram;
    private Runnable login;
    private Consumer<URL> openTelegram;
    private Consumer<File> openVideo;
    private Runnable onTransferChanged;
    private AlertDialog dialog;
    private String address = "";
    private Transfer active;
    private VideoSourceResolver.Source awaitingLogin;
    private VideoSourceResolver.Source failedSource;
    private boolean failedViaAccount;
    private boolean allowPublicFallback;
    private String error;
    private File completed;

    VideoDownloadFlow(Context context, SavedVideoStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
    }

    void attach(MainActivity activity, AppSettings settings, Supplier<TelegramSessionClient> telegram,
                Runnable login, Consumer<URL> openTelegram, Consumer<File> openVideo, Runnable onTransferChanged) {
        this.activity = activity;
        this.settings = settings;
        this.telegram = telegram;
        this.login = login;
        this.openTelegram = openTelegram;
        this.openVideo = openVideo;
        this.onTransferChanged = onTransferChanged;
    }

    boolean isDownloading() { return active != null; }

    boolean usesTelegramSession(TelegramSessionClient client) {
        return client != null && active != null && active.client == client;
    }

    String activeTitle() {
        if (active.title != null) return active.title;
        if (active.source.kind == VideoSourceResolver.Kind.TELEGRAM_PUBLIC_POST) {
            return "@" + active.source.channel + " · " + active.source.postId;
        }
        if (active.source.kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST) return "Video Telegram";
        String name = SavedVideoStore.nameFromUrl(active.source.url.toExternalForm());
        return TextUtils.isEmpty(name) ? active.source.url.getHost() : name;
    }

    String activeLabel() {
        return active.percent < 0 ? "Đang tải video…" : "Đang tải video · " + active.percent + "%";
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
        field.setHint("https://example.com/video.mp4");
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
        if (failedSource != null && isTelegram(failedSource)) {
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
                    problem.setText("Hãy dùng liên kết bài đăng Telegram hoặc chia sẻ tệp từ Telegram.");
                    return;
                }
                if (source.kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST && !connected()) {
                    failedSource = source;
                    error = "Cần đăng nhập Telegram bằng tài khoản có quyền truy cập bài đăng này.";
                    dismiss();
                    showInput();
                    return;
                }
                begin(source, connected() && (source.kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST
                        || failedSource == null || failedViaAccount));
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
            if (kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST) {
                label.setText("Bài đăng Telegram riêng tư · Cần đăng nhập bằng tài khoản có quyền truy cập.");
                return;
            }
        } catch (Exception invalid) {
            // Keep the supported-source hint while the address is incomplete.
        }
        label.setText("Hỗ trợ URL video trực tiếp và bài đăng Telegram.");
    }

    private static boolean isTelegram(VideoSourceResolver.Source source) {
        return source.kind == VideoSourceResolver.Kind.TELEGRAM_PUBLIC_POST
                || source.kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST;
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
        boolean telegramSource = isTelegram(source);
        Transfer transfer = new Transfer(source, telegramSource && account);
        active = transfer;
        transfer.status = telegramSource
                ? (transfer.viaAccount ? "Đang tải bằng tài khoản Telegram…" : "Đang tìm video công khai…")
                : "Đang kết nối…";
        showProgress();
        onTransferChanged.run();
        if (!telegramSource) {
            download(transfer, source.url.toExternalForm());
        } else if (transfer.viaAccount) {
            transfer.client = telegram.get();
            transfer.telegramResult = new TelegramSessionClient.MessageVideoResult() {
                @Override public void finished(String path, String failure) {
                    if (active != transfer) return;
                    if (failure != null) {
                        boolean fallback = source.kind == VideoSourceResolver.Kind.TELEGRAM_PUBLIC_POST
                                && !failure.startsWith("Bài đăng hạn chế")
                                && !failure.startsWith("Không xác nhận được quyền lưu");
                        fail(transfer, failure, fallback);
                        return;
                    }
                    transfer.title = new File(path).getName();
                    updateProgress(transfer, "Đang lưu video Telegram…");
                    new TdlibSavedVideoImporter(context).importFile(path, () -> transfer.cancelled,
                            (file, problem) -> finish(transfer, file, problem, null));
                }

                @Override public boolean isCancelled() { return transfer.cancelled; }

                @Override public void progress(long bytes, long total) {
                    updateBytes(transfer, bytes, total);
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
                (bytes, total) -> updateBytes(transfer, bytes, total),
                (file, failure) -> finish(transfer, file, failure, url));
        transfer.cancelRequest = download::cancel;
    }

    private void updateBytes(Transfer transfer, long bytes, long total) {
        transfer.percent = total > 0 ? (int) Math.min(100, 100.0 * bytes / total) : -1;
        String size = android.text.format.Formatter.formatShortFileSize(context, bytes);
        updateProgress(transfer, "Đã tải "
                + (transfer.percent >= 0 ? transfer.percent + "% · " : "") + size);
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
        notifyTransferChanged();
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
        boolean telegramSource = isTelegram(transfer.source);
        String title = telegramSource ? transfer.title : SavedVideoStore.nameFromUrl(mediaUrl);
        if (telegramSource && title == null) title = "@" + transfer.source.channel + " · " + transfer.source.postId;
        store.remember(file, telegramSource ? "Telegram" : "URL", key, title);
        completed = file;
        notifyTransferChanged();
        if (visible()) showCompleted();
        else android.widget.Toast.makeText(context, "Đã lưu video", android.widget.Toast.LENGTH_SHORT).show();
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
        notifyTransferChanged();
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
        notifyTransferChanged();
    }

    private void notifyTransferChanged() {
        if (onTransferChanged != null) onTransferChanged.run();
    }

    void detach() {
        dismiss();
        activity = null;
        settings = null;
        telegram = null;
        login = null;
        openTelegram = null;
        openVideo = null;
        onTransferChanged = null;
    }

    void close() {
        detach();
        cancel();
    }

    private static final class Transfer {
        final VideoSourceResolver.Source source;
        final boolean viaAccount;
        String status;
        String title;
        int percent = -1;
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
