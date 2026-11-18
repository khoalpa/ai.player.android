package com.aiplayer.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class VideoUrlCacheDialogs {
    private final Activity activity;
    private final AppSettings settings;
    private final Consumer<Uri> open;
    private final VideoUrlCache cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog progressDialog;
    private boolean destroyed;

    VideoUrlCacheDialogs(Activity activity, Consumer<Uri> open) {
        this.activity = activity;
        settings = AppSettings.load(activity);
        this.open = open;
        cache = VideoUrlCache.get(activity);
    }

    boolean contains(Uri uri) {
        return cache.contains(uri);
    }

    void show(Runnable openPicker, Uri currentVideo) {
        new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Mở video").setItems(new String[]{
                "Chọn tệp trên thiết bị", "Tải video từ URL", "Video đã lưu"
        }, (dialog, index) -> {
            if (index == 0) openPicker.run();
            if (index == 1) showInput();
            if (index == 2) showSaved(currentVideo);
        }).show();
    }

    private void showInput() {
        if (cache.active()) {
            showProgress();
            return;
        }
        EditText url = new EditText(activity);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("https://…");
        AlertDialog dialog = new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Tải video từ URL")
                .setMessage("Dán URL HTTPS trực tiếp tới tệp MP4 hoặc liên kết bài đăng Telegram công khai.")
                .setView(url).setNegativeButton("Đóng", null).setPositiveButton("Tải", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String source = url.getText().toString();
                    view.setEnabled(false);
                    worker.execute(() -> {
                        String error = null;
                        try {
                            cache.start(TelegramVideoSource.resolve(source));
                        } catch (IllegalArgumentException ex) {
                            error = ex.getMessage() != null && ex.getMessage().startsWith("Hãy nhập liên kết bài đăng")
                                    ? ex.getMessage() : "Hãy nhập URL HTTPS hoặc liên kết Telegram hợp lệ.";
                        } catch (TelegramVideoSource.PreviewWithoutVideo ex) {
                            main.post(() -> {
                                if (destroyed || !dialog.isShowing()) return;
                                view.setEnabled(true);
                                showTelegramFallback(ex.post);
                            });
                            return;
                        } catch (IOException ex) {
                            error = ex instanceof java.net.SocketTimeoutException
                                    || ex instanceof java.net.UnknownHostException
                                    ? "Không kết nối được Telegram. Hãy kiểm tra mạng rồi thử lại."
                                    : ex.getMessage();
                        } catch (RuntimeException ex) {
                            error = "Không bắt đầu được tác vụ tải.";
                        }
                        String result = error;
                        main.post(() -> {
                            if (destroyed || !dialog.isShowing()) return;
                            if (result != null) {
                                url.setError(result);
                                view.setEnabled(true);
                                return;
                            }
                            dialog.dismiss();
                            showProgress();
                        });
                    });
                }));
        PlayerAppearance.showDialog(dialog, settings);
    }

    private void showTelegramFallback(URI post) {
        new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Video Telegram")
                .setMessage("Bản xem trước công khai không có video để tải. Nếu Telegram cho phép lưu, "
                        + "hãy lưu video rồi mở lại bằng Mở video → Chọn tệp trên thiết bị.")
                .setNegativeButton("Đóng", null)
                .setPositiveButton("Mở trong Telegram", (dialog, which) -> openTelegramPost(post))
                .show();
    }

    private void openTelegramPost(URI post) {
        Uri web = Uri.parse(post.toASCIIString());
        Uri telegram = new Uri.Builder().scheme("tg").authority("resolve")
                .appendQueryParameter("domain", web.getPathSegments().get(0))
                .appendQueryParameter("post", web.getPathSegments().get(1))
                .build();
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, telegram));
        } catch (ActivityNotFoundException missingApp) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, web));
            } catch (ActivityNotFoundException missingHandler) {
                notifyUser("Không có ứng dụng để mở liên kết Telegram.");
            }
        }
    }

    private void showProgress() {
        AlertDialog dialog = new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Tải video từ URL")
                .setMessage("Đang kiểm tra tác vụ tải…")
                .setNegativeButton("Đóng", null)
                .setNeutralButton("Hủy tải", (ignored, which) -> worker.execute(() -> {
                    try {
                        cache.cancel();
                    } catch (RuntimeException ex) {
                        main.post(() -> notifyUser("Không hủy được tác vụ tải."));
                    }
                })).create();
        progressDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (progressDialog == dialog) progressDialog = null;
        });
        PlayerAppearance.showDialog(dialog, settings);
        refreshProgress(dialog);
    }

    private void refreshProgress(AlertDialog dialog) {
        if (destroyed || progressDialog != dialog) return;
        worker.execute(() -> {
            String status = refresh();
            boolean active = cache.active();
            main.post(() -> {
                if (destroyed || progressDialog != dialog) return;
                dialog.setMessage(status.isEmpty() ? "Không có tác vụ tải." : status);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(active);
                if (active) main.postDelayed(() -> refreshProgress(dialog), 1000);
            });
        });
    }

    private String refresh() {
        try {
            return cache.refresh();
        } catch (RuntimeException ex) {
            return "Không đọc được trạng thái tải.";
        }
    }

    private void showSaved(Uri currentVideo) {
        worker.execute(() -> {
            String status = refresh();
            File[] videos;
            try {
                videos = cache.videos();
            } catch (IllegalStateException ex) {
                main.post(() -> notifyUser("Không truy cập được thư mục video đã lưu."));
                return;
            }
            main.post(() -> {
                if (destroyed) return;
                if (!status.isEmpty()) notifyUser(status);
                String[] labels = new String[videos.length];
                for (int index = 0; index < videos.length; index++) {
                    Date created = new Date(videos[index].lastModified());
                    labels[index] = "Video · " + DateFormat.getMediumDateFormat(activity).format(created)
                            + " " + DateFormat.getTimeFormat(activity).format(created)
                            + " · " + Formatter.formatFileSize(activity, videos[index].length());
                }
                AlertDialog.Builder dialog = new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Video đã lưu")
                        .setNegativeButton("Đóng", null);
                if (videos.length == 0) {
                    dialog.setMessage("Chưa có video đã tải xong.");
                } else {
                    dialog.setItems(labels, (ignored, index) -> showVideo(videos[index], labels[index], currentVideo));
                }
                if (cache.active()) dialog.setNeutralButton("Tiến độ tải", (ignored, which) -> showProgress());
                dialog.show();
            });
        });
    }

    private void showVideo(File video, String label, Uri currentVideo) {
        Uri uri = Uri.fromFile(video);
        AlertDialog.Builder dialog = new PlayerAppearance.DialogBuilder(activity, settings).setTitle(label)
                .setPositiveButton("Mở video", (ignored, which) -> {
                    if (cache.contains(uri)) open.accept(uri);
                    else notifyUser("Video đã bị xóa hoặc bộ nhớ không khả dụng.");
                }).setNegativeButton("Đóng", null);
        if (!uri.equals(currentVideo)) {
            dialog.setNeutralButton("Xóa", (ignored, which) -> new PlayerAppearance.DialogBuilder(activity, settings)
                    .setTitle("Xóa video đã lưu?")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Xóa", (confirmation, button) -> {
                        notifyUser(video.delete() ? "Đã xóa video." : "Không xóa được video.");
                        showSaved(currentVideo);
                    }).show());
        }
        dialog.show();
    }

    private void notifyUser(String message) {
        if (!destroyed) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    void destroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        worker.shutdown();
        if (progressDialog != null) progressDialog.dismiss();
    }
}
