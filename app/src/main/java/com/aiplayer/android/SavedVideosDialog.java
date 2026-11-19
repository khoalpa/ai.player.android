package com.aiplayer.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class SavedVideosDialog {
    private final Activity activity;
    private final SavedVideoStore videos;
    private final PlaybackSessionStore sessions;
    private final AppSettings settings;
    private final ExecutorService metadata = Executors.newSingleThreadExecutor();
    private final LruCache<File, Preview> previews = new LruCache<>(32);
    private AlertDialog dialog;

    SavedVideosDialog(Activity activity, SavedVideoStore videos, PlaybackSessionStore sessions,
                      AppSettings settings) {
        this.activity = activity;
        this.videos = videos;
        this.sessions = sessions;
        this.settings = settings;
    }

    void show(Consumer<File> open, Predicate<File> delete, Uri current, long position, boolean inUse,
              boolean bySize, Runnable dismissed) {
        ListView list = new ListView(activity);
        list.setDividerHeight(dp(1));
        TextView empty = new TextView(activity);
        empty.setText("Chưa có video đã lưu.");
        empty.setPadding(dp(20), dp(16), dp(20), dp(16));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(empty);
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setEmptyView(empty);
        java.util.List<File> files = new java.util.ArrayList<>(java.util.Arrays.asList(videos.list()));
        if (bySize) files.sort(java.util.Comparator.comparingLong(File::length).reversed());
        BaseAdapter adapter = new BaseAdapter() {

            @Override public int getCount() { return files.size(); }
            @Override public File getItem(int index) { return files.get(index); }
            @Override public long getItemId(int index) { return index; }

            @Override public View getView(int index, View recycled, ViewGroup parent) {
                File file = getItem(index);
                VideoRow row = recycled == null ? new VideoRow() : (VideoRow) recycled;
                row.setTag(file);
                String title = videos.displayName(file);
                String sourceLabel = videos.sourceLabel(file);
                String size = android.text.format.Formatter.formatFileSize(activity, file.length());
                String source = sourceLabel.isEmpty() ? size : sourceLabel + " · " + size;
                row.name.setText(title);
                row.setCurrent(Uri.fromFile(file).equals(current));
                row.setOnClickListener(view -> {
                    dialog.dismiss();
                    open.accept(file);
                });
                row.options.setContentDescription("Tùy chọn " + title);
                row.options.setOnClickListener(anchor -> {
                    android.widget.PopupMenu menu = new android.widget.PopupMenu(activity, anchor);
                    menu.getMenu().add("Xóa video").setEnabled(!inUse || !Uri.fromFile(file).equals(current))
                            .setOnMenuItemClickListener(item -> {
                                new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Xóa video?")
                                        .setMessage(title + "\nDung lượng dự kiến thu hồi · " + size).setNegativeButton("Hủy", null)
                                        .setPositiveButton("Xóa", (window, which) -> {
                                            if (delete.test(file)) {
                                                previews.remove(file);
                                                files.remove(file);
                                                notifyDataSetChanged();
                                            } else android.widget.Toast.makeText(activity,
                                                    "Không thể xóa video", android.widget.Toast.LENGTH_SHORT).show();
                                        }).show();
                                return true;
                            });
                    menu.show();
                });
                long watched = Uri.fromFile(file).equals(current) ? position
                        : sessions.savedPosition(Uri.fromFile(file));
                Preview cached = previews.get(file);
                row.bind(cached, watched, source);
                if (cached == null) metadata.execute(() -> {
                    Preview preview = previews.get(file);
                    if (preview == null) {
                        preview = readPreview(file);
                        if (file.isFile()) previews.put(file, preview);
                    }
                    Preview result = preview;
                    activity.runOnUiThread(() -> {
                        if (row.isAttachedToWindow() && file.equals(row.getTag())) row.bind(result, watched, source);
                    });
                });
                return row;
            }
        };
        list.setAdapter(adapter);
        dialog = new PlayerAppearance.DialogBuilder(activity, settings).setTitle("Video đã lưu").setView(content)
                .setNegativeButton("Đóng", null).show();
        dialog.setOnDismissListener(window -> dismissed.run());
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private Preview readPreview(File file) {
        long duration = 0;
        Bitmap image = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value != null) duration = Long.parseLong(value);
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                image = retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90);
            } else {
                image = android.media.ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(),
                        android.provider.MediaStore.Video.Thumbnails.MICRO_KIND);
            }
        } catch (RuntimeException ignored) {
            // Leave unavailable metadata blank.
        } finally {
            try {
                retriever.release();
            } catch (java.io.IOException | RuntimeException ignored) {
                // A failure to release does not invalidate recovered metadata.
            }
        }
        return new Preview(image, duration);
    }

    static String formatPosition(long milliseconds) {
        long seconds = milliseconds / 1000;
        long hours = seconds / 3600;
        return hours == 0 ? String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60);
    }

    void close() {
        if (dialog != null) dialog.dismiss();
        metadata.shutdownNow();
    }

    private int dp(int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private static final class Preview {
        final Bitmap image;
        final long duration;

        Preview(Bitmap image, long duration) {
            this.image = image;
            this.duration = duration;
        }
    }

    private final class VideoRow extends LinearLayout {
        final ImageView image;
        final TextView name;
        final TextView detail;
        final Button options;

        VideoRow() {
            super(activity);
            setGravity(android.view.Gravity.CENTER_VERTICAL);
            setPadding(dp(12), dp(8), dp(8), dp(8));

            image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image, new LayoutParams(dp(72), dp(48)));
            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(VERTICAL);
            labels.setPadding(dp(12), 0, dp(8), 0);
            addView(labels, new LayoutParams(0, -2, 1));
            name = new TextView(activity);
            name.setTextSize(settings.textSize(16));
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(name);
            detail = new TextView(activity);
            detail.setTextSize(settings.textSize(12));
            detail.setTextColor(PlayerAppearance.color(activity, android.R.attr.textColorSecondary));
            labels.addView(detail);
            options = new Button(activity);
            options.setText("⋯");
            PlayerAppearance.styleButton(options, false, settings);
            options.setPadding(0, 0, 0, 0);
            addView(options, new LayoutParams(dp(48), dp(48)));
        }

        void setCurrent(boolean current) {
            int fill = PlayerAppearance.color(activity,
                    current ? R.attr.playerSelection : android.R.attr.colorBackgroundFloating);
            PlayerAppearance.styleSurface(this, fill, settings.raisedControls);
            name.setTypeface(null, current ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }

        void bind(Preview preview, long position, String source) {
            if (preview != null && preview.image != null) image.setImageBitmap(preview.image);
            else image.setImageResource(R.drawable.ic_control_open);
            if (preview == null || preview.image == null) {
                image.setColorFilter(PlayerAppearance.color(activity, android.R.attr.textColorPrimary));
            } else {
                image.clearColorFilter();
            }
            String duration = preview != null && preview.duration > 0 ? formatPosition(preview.duration) : "";
            String watched = position > 0 ? "Đã xem " + formatPosition(position) : "";
            String timing = duration.isEmpty() || watched.isEmpty() ? duration + watched : watched + " / " + duration;
            detail.setText(source.isEmpty() || timing.isEmpty() ? source + timing : source + " · " + timing);
            detail.setVisibility(detail.length() == 0 ? GONE : VISIBLE);
        }
    }
}
