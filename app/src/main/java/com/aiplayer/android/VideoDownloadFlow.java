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
    private VideoSourceResolver.Source pageSource;
    private java.util.List<WebVideoFinder.Candidate> pageResults;
    private WebVideoSearchDialog webSearch;

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
        if (active.searching) return "Đang tìm video trên trang";
        if (active.checking) return "Đang kiểm tra liên kết";
        return active.percent < 0 ? "Đang tải video…" : "Đang tải video · " + active.percent + "%";
    }

    void show() {
        if (dialog != null && dialog.isShowing()) return;
        if (active != null) showProgress();
        else if (completed != null && completed.isFile()) showCompleted();
        else if (pageResults != null) showPageResults();
        else showInput();
    }

    void showTelegram(VideoSourceResolver.Source source) {
        if (active != null) {
            show();
            return;
        }
        address = source.url.toExternalForm();
        awaitingLogin = null;
        clearError();
        begin(source, connected());
    }

    private void showInput() {
        completed = null;
        LinearLayout form = WebVideoDialogLayout.body(activity);
        WebVideoDialogLayout.header(form, settings, "Tải video từ URL", false, this::dismiss);
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setHint("https://example.com/video.mp4");
        field.setText(address);
        LinearLayout entry = new LinearLayout(activity);
        entry.setGravity(android.view.Gravity.CENTER_VERTICAL);
        entry.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        WebVideoDialogLayout.add(form, entry, 8);
        TextView sourceLabel = new TextView(activity);
        WebVideoDialogLayout.add(form, sourceLabel, 8);
        TextView problem = new TextView(activity);
        form.addView(problem);
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        if (error != null) problem.setText(error);
        boolean knownPage = isKnownPage();
        var load = WebVideoDialogLayout.button(activity, settings,
                knownPage ? "Tìm video trên trang" : "Tải video", true, () -> {});
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                address = s.toString();
                awaitingLogin = null;
                clearError();
                problem.setText("");
                actions.setVisibility(android.view.View.GONE);
                load.setText(isKnownPage() ? "Tìm video trên trang" : "Tải video");
                updateSourceLabel(sourceLabel);
            }
            @Override public void afterTextChanged(android.text.Editable value) {}
        });
        updateSourceLabel(sourceLabel);
        if (StorageSpace.isLowSpace(error)) {
            addAction(actions, "Dữ liệu và bộ nhớ", () -> {
                dismiss();
                activity.openStorageFromDownload();
            });
        }
        if (knownPage) {
            sourceLabel.setText("Liên kết mở một trang xem video.");
            problem.setText("Chọn Tìm video trên trang để kiểm tra nguồn phát.");
            addAction(actions, "Mở trang để tìm video", this::openWebSearch);
            addAction(actions, "Mở trình duyệt", this::openOriginalPage);
        }
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
        var paste = WebVideoDialogLayout.button(activity, settings, "Dán", false, () -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            android.content.ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                problem.setText("Bộ nhớ tạm chưa có liên kết.");
                return;
            }
            field.setText(clip.getItemAt(0).coerceToText(activity));
            field.setSelection(field.length());
        });
        var pasteParams = new LinearLayout.LayoutParams(-2, -2);
        pasteParams.setMarginStart(WebVideoDialogLayout.dp(activity, 8));
        entry.addView(paste, pasteParams);
        WebVideoDialogLayout.add(form, load, 16);
        WebVideoDialogLayout.add(form, actions, 8);
        load.setOnClickListener(view -> {
            if (isKnownPage()) {
                findPageVideo();
                return;
            }
            try {
                VideoSourceResolver.Source source = VideoSourceResolver.classify(address);
                if (source.kind == VideoSourceResolver.Kind.UNSUPPORTED_PAGE) {
                    problem.setText("Hãy dùng liên kết bài đăng Telegram hoặc chia sẻ tệp từ Telegram.");
                    return;
                }
                begin(source, connected() && (source.kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST
                        || failedSource == null || failedViaAccount));
            } catch (Exception invalid) {
                problem.setText("Hãy nhập link video HTTPS hoặc link bài đăng Telegram hợp lệ.");
            }
        });
        display(new PlayerAppearance.DialogBuilder(activity, settings)
                .setView(WebVideoDialogLayout.scroll(form)).create());
    }

    private boolean isKnownPage() {
        return pageSource != null && address.trim().equals(pageSource.url.toExternalForm());
    }

    private android.widget.Button addAction(LinearLayout form, String label, Runnable action) {
        var button = WebVideoDialogLayout.button(activity, settings, label, false, action);
        WebVideoDialogLayout.add(form, button, 8);
        return button;
    }

    private void updateSourceLabel(TextView label) {
        if (isKnownPage()) {
            label.setText("Liên kết mở một trang xem video.");
            return;
        }
        try {
            VideoSourceResolver.Kind kind = VideoSourceResolver.classify(address).kind;
            if (kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST) {
                label.setText("Bài đăng Telegram riêng tư · Cần đăng nhập bằng tài khoản có quyền truy cập.");
                return;
            }
        } catch (Exception invalid) {
            // Keep the supported-source hint while the address is incomplete.
        }
        label.setText("Dùng URL HTTPS trỏ đến tệp video hoặc liên kết bài đăng Telegram có video.");
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
        begin(source, account, null);
    }

    void saveTelegramForPlayback(VideoSourceResolver.Source source,
                                 java.util.function.BiConsumer<File, String> completion) {
        if (active != null || !connected()) {
            completion.accept(null, active != null ? "Đang có một lượt tải video khác."
                    : "Cần kết nối Telegram để xác minh quyền lưu video.");
            return;
        }
        begin(source, true, completion);
    }

    private void begin(VideoSourceResolver.Source source, boolean account,
                       java.util.function.BiConsumer<File, String> completion) {
        begin(source, account, completion, false);
    }

    private void begin(VideoSourceResolver.Source source, boolean account,
                       java.util.function.BiConsumer<File, String> completion, boolean reload) {
        if (active != null) return;
        completed = null;
        awaitingLogin = null;
        clearError();
        dismiss();
        File existing = reload ? null : store.findBySource(SavedVideoStore.sourceKey(source));
        if (existing != null) {
            if (completion != null) {
                completion.accept(existing, null);
                return;
            }
            AlertDialog.Builder saved = new PlayerAppearance.DialogBuilder(activity, settings)
                    .setTitle("Video đã có trong thư viện")
                    .setMessage(store.displayName(existing)).setNegativeButton("Đóng", null)
                    .setPositiveButton("Mở video đã lưu", (d, w) -> openVideo.accept(existing));
            if (source.kind == VideoSourceResolver.Kind.DIRECT_FILE) {
                saved.setNeutralButton("Tải lại", (d, w) -> begin(source, false, null, true));
            }
            display(saved.create());
            return;
        }
        if (source.kind == VideoSourceResolver.Kind.TELEGRAM_PRIVATE_POST && !account) {
            failedSource = source;
            error = "Cần đăng nhập Telegram bằng tài khoản có quyền truy cập bài đăng này.";
            showInput();
            return;
        }
        boolean telegramSource = isTelegram(source);
        if (telegramSource) TelegramContentScreen.recordTransfer(context, source, "Chờ tải");
        Transfer transfer = new Transfer(source, telegramSource && account);
        transfer.completion = completion;
        active = transfer;
        transfer.checking = !telegramSource;
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
                                && !StorageSpace.isLowSpace(failure)
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

    private void findPageVideo() {
        findPageVideo(null);
    }

    private void findPageVideo(java.util.List<String> observedUrls) {
        if (active != null || pageSource == null) return;
        WebVideoFinder finder = new WebVideoFinder();
        Transfer transfer = new Transfer(pageSource, false);
        transfer.searching = true;
        transfer.status = "Đang kiểm tra nguồn video trên trang.";
        transfer.cancelRequest = finder::cancel;
        pageResults = null;
        active = transfer;
        showProgress();
        notifyTransferChanged();
        new Thread(() -> {
            java.util.List<WebVideoFinder.Candidate> results = null;
            String problem = null;
            try {
                results = observedUrls == null ? finder.find(transfer.source.url) : finder.verify(observedUrls);
            } catch (Exception failure) {
                problem = errorMessage(failure);
            }
            var found = results;
            var error = problem;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (active != transfer) return;
                if (error != null) {
                    fail(transfer, error, false);
                    return;
                }
                active = null;
                pageResults = found;
                notifyTransferChanged();
                if (visible()) showPageResults();
            });
        }, "web-video-finder").start();
    }

    private void openWebSearch() {
        if (pageSource == null || activity == null) return;
        dismiss();
        if (webSearch != null) webSearch.close();
        webSearch = new WebVideoSearchDialog(activity, settings, pageSource.url.toExternalForm(),
                this::findPageVideo, this::openOriginalPage);
    }

    void closeWebSearch() {
        if (webSearch != null) {
            webSearch.close();
            webSearch = null;
        }
    }

    private void showPageResults() {
        var sources = pageResults;
        LinearLayout actions = WebVideoDialogLayout.body(activity);
        WebVideoDialogLayout.header(actions, settings, "Video trên trang", true, () -> {
            pageResults = null;
            showInput();
        });
        if (sources.isEmpty()) {
            TextView message = new TextView(activity);
            message.setText("Chưa tìm thấy nguồn video. Mở trang, nhấn phát rồi chọn Kiểm tra nguồn.");
            WebVideoDialogLayout.add(actions, message, 8);
        }
        for (var source : sources) {
            String name = SavedVideoStore.nameFromUrl(source.url.toExternalForm());
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout labels = WebVideoDialogLayout.column(activity);
            TextView host = new TextView(activity);
            host.setText(source.format + " · " + source.url.getHost());
            host.setSingleLine();
            host.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(host);
            if (!TextUtils.isEmpty(name)) {
                TextView filename = new TextView(activity);
                filename.setText(name);
                filename.setSingleLine();
                filename.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                labels.addView(filename);
            }
            var labelParams = new LinearLayout.LayoutParams(0, -2, 1);
            labelParams.setMarginEnd(WebVideoDialogLayout.dp(activity, 8));
            row.addView(labels, labelParams);
            var select = WebVideoDialogLayout.button(activity, settings,
                    source.kind == WebVideoFinder.Kind.FILE ? "Tải" : "Phát",
                    source == sources.get(0), () -> selectPageSource(source));
            row.addView(select, new LinearLayout.LayoutParams(-2, -2));
            WebVideoDialogLayout.add(actions, row, 8);
        }
        var openPage = WebVideoDialogLayout.button(activity, settings, "Mở trang để tìm video",
                sources.isEmpty(), this::openWebSearch);
        WebVideoDialogLayout.add(actions, openPage, 16);
        if (!sources.isEmpty()) addAction(actions, "Tìm lại", this::findPageVideo);
        display(new PlayerAppearance.DialogBuilder(activity, settings)
                .setView(WebVideoDialogLayout.scroll(actions)).create());
    }

    private void selectPageSource(WebVideoFinder.Candidate candidate) {
        pageResults = null;
        dismiss();
        if (candidate.kind == WebVideoFinder.Kind.FILE) {
            begin(new VideoSourceResolver.Source(VideoSourceResolver.Kind.DIRECT_FILE,
                    candidate.url, null, null), false);
        } else {
            activity.playWebVideo(candidate, this::findPageVideo, this::openWebSearch);
        }
    }

    private void openOriginalPage() {
        try {
            activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(pageSource.url.toExternalForm())));
        } catch (android.content.ActivityNotFoundException unavailable) {
            android.widget.Toast.makeText(context, "Không có trình duyệt để mở trang.",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void updateBytes(Transfer transfer, long bytes, long total) {
        if (active != transfer) return;
        if (transfer.checking) {
            transfer.checking = false;
            if (visible()) dialog.setTitle("Đang tải video");
        }
        transfer.percent = total > 0 ? (int) Math.min(100, 100.0 * bytes / total) : -1;
        String size = android.text.format.Formatter.formatShortFileSize(context, bytes);
        updateProgress(transfer, "Đã tải "
                + (transfer.percent >= 0 ? transfer.percent + "% · " : "") + size);
    }

    private void showProgress() {
        display(new PlayerAppearance.DialogBuilder(activity, settings)
                .setTitle(active.searching ? "Đang tìm video trên trang"
                        : active.checking ? "Đang kiểm tra liên kết" : "Đang tải video")
                .setMessage(progressMessage(active.status))
                .setPositiveButton(active.searching ? "Hủy tìm" : "Hủy tải", (d, w) -> cancel())
                .setNegativeButton("Đóng tiến độ", null).create());
    }

    private static String progressMessage(String status) {
        return status + "\n\nTác vụ tiếp tục khi bạn đóng hộp tiến độ.";
    }

    private void updateProgress(Transfer transfer, String status) {
        if (active != transfer) return;
        transfer.status = status;
        if (visible()) dialog.setMessage(progressMessage(status));
        notifyTransferChanged();
    }

    private void finish(Transfer transfer, File file, Exception failure, String mediaUrl) {
        if (active != transfer) {
            if (file != null && transfer.cancelled) file.delete();
            return;
        }
        if (failure != null) {
            if (transfer.source.kind == VideoSourceResolver.Kind.DIRECT_FILE
                    && failure instanceof WebVideoFinder.PageResponse
                    && (pageSource == null || !address.trim().equals(pageSource.url.toExternalForm()))) {
                pageSource = transfer.source;
                address = pageSource.url.toExternalForm();
            }
            fail(transfer, errorMessage(failure), false);
            return;
        }
        active = null;
        String key = SavedVideoStore.sourceKey(transfer.source);
        boolean telegramSource = isTelegram(transfer.source);
        String title = telegramSource ? transfer.title : SavedVideoStore.nameFromUrl(mediaUrl);
        if (telegramSource && title == null) title = "@" + transfer.source.channel + " · " + transfer.source.postId;
        store.remember(file, telegramSource ? "Telegram" : "URL", key, title);
        if (telegramSource) TelegramContentScreen.recordTransfer(context, transfer.source, null);
        completed = file;
        notifyTransferChanged();
        if (transfer.completion != null) {
            dismiss();
            transfer.completion.accept(file, null);
            return;
        }
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
        if (isTelegram(transfer.source)) TelegramContentScreen.recordTransfer(context, transfer.source, "Tải lỗi");
        if (transfer.completion != null) {
            dismiss();
            notifyTransferChanged();
            transfer.completion.accept(null, message);
            return;
        }
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
        if (isTelegram(transfer.source)) TelegramContentScreen.recordTransfer(context, transfer.source, "Chờ tải");
        transfer.cancelled = true;
        if (transfer.cancelRequest != null) transfer.cancelRequest.run();
        if (transfer.client != null) transfer.client.cancelMessageVideo(transfer.telegramResult);
        notifyTransferChanged();
    }

    private void notifyTransferChanged() {
        if (onTransferChanged != null) onTransferChanged.run();
    }

    void detach() {
        closeWebSearch();
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
        boolean searching;
        boolean checking;
        TelegramSessionClient client;
        TelegramSessionClient.MessageVideoResult telegramResult;
        java.util.function.BiConsumer<File, String> completion;

        Transfer(VideoSourceResolver.Source source, boolean viaAccount) {
            this.source = source;
            this.viaAccount = viaAccount;
        }
    }
}
