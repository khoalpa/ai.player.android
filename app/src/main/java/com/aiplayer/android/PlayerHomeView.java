package com.aiplayer.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

final class PlayerHomeView extends ScrollView {
    private static final long RESUME_LABEL_MIN_MS = 1000;

    private final AppSettings settings;
    private final LinearLayout recent;
    private final LinearLayout taskSection;
    private final TextView taskHeading;
    private final TextView taskTitle, taskDetail;
    private final Button taskAction;
    private final Consumer<Uri> playRecent;
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private final ExecutorService durations = Executors.newFixedThreadPool(3);
    private final List<Future<?>> previewRequests = new ArrayList<>();
    private final List<Runnable> recentStatusUpdates = new ArrayList<>();
    private int previewGeneration;
    private boolean closed;

    PlayerHomeView(Context context, AppSettings settings, Runnable open,
                   Runnable telegram, Consumer<Uri> playRecent) {
        super(context);
        this.settings = settings;
        this.playRecent = playRecent;
        setVerticalScrollBarEnabled(false);
        LinearLayout content = column();
        content.setPadding(dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.GROUP_GAP_DP), dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.HORIZONTAL_INSET_DP));
        int surface = PlayerAppearance.color(context, android.R.attr.colorBackgroundFloating);
        addAction(content, "Mở video", R.drawable.ic_control_open, PlayerAppearance.ButtonRole.PRIMARY, 0, open);
        addAction(content, "Telegram", R.drawable.ic_control_telegram, PlayerAppearance.ButtonRole.SECONDARY, PlayerAppearance.CONTROL_GAP_DP, telegram);

        recent = column();
        LinearLayout.LayoutParams section = new LinearLayout.LayoutParams(-1, -2);
        section.topMargin = dp(PlayerAppearance.GROUP_GAP_DP);
        content.addView(recent, section);
        TextView heading = label(16, true);
        heading.setText("Video gần đây");
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        recent.addView(heading);

        recent.setVisibility(GONE);

        taskSection = column();
        LinearLayout.LayoutParams taskParams = new LinearLayout.LayoutParams(-1, -2);
        taskParams.topMargin = dp(PlayerAppearance.GROUP_GAP_DP);
        content.addView(taskSection, taskParams);
        taskHeading = label(16, true);
        taskHeading.setText("Tác vụ gần đây");
        if (Build.VERSION.SDK_INT >= 28) taskHeading.setAccessibilityHeading(true);
        taskSection.addView(taskHeading);
        LinearLayout taskCard = column();
        taskCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        taskCard.setBackground(shape(surface, dp(14)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(8);
        taskSection.addView(taskCard, cardParams);
        taskTitle = label(14, true);
        taskCard.addView(taskTitle);
        taskDetail = label(12, false);
        taskDetail.setMaxLines(2);
        taskDetail.setEllipsize(TextUtils.TruncateAt.END);
        taskCard.addView(taskDetail);
        taskAction = new Button(context);
        taskAction.setAllCaps(false);
        PlayerAppearance.styleButton(taskAction, PlayerAppearance.ButtonRole.ACCENT, settings);
        taskAction.setMinHeight(dp(48));
        taskAction.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.topMargin = dp(PlayerAppearance.CONTROL_GAP_DP);
        taskCard.addView(taskAction, actionParams);
        taskSection.setVisibility(GONE);
        addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
    }

    void bindTask(String title, String detail, String action, Runnable onClick) {
        taskSection.setVisibility(title == null ? GONE : VISIBLE);
        taskHeading.setVisibility(onClick == null ? GONE : VISIBLE);
        taskTitle.setText(title);
        taskDetail.setText(detail);
        taskDetail.setVisibility(TextUtils.isEmpty(detail) ? GONE : VISIBLE);
        taskAction.setText(action);
        taskAction.setOnClickListener(onClick == null ? null : view -> onClick.run());
        taskAction.setVisibility(onClick == null ? GONE : VISIBLE);
    }

    void bindRecent(List<PlaybackSessionStore.RecentVideo> videos, ToLongFunction<Uri> position,
                    ToLongFunction<Uri> duration,
                    Function<Uri, RecentVideoStatus> savedStatus,
                    Function<Uri, RecentVideoStatus> activeStatus) {
        cancelPreviews();
        recentStatusUpdates.clear();
        recent.removeViews(1, recent.getChildCount() - 1);
        recent.setVisibility(videos.isEmpty() ? GONE : VISIBLE);
        for (PlaybackSessionStore.RecentVideo video : videos) {
            addRecentCard(video, position.applyAsLong(video.uri), duration, savedStatus, activeStatus);
        }
    }

    void refreshRecentStatuses() {
        for (Runnable update : recentStatusUpdates) update.run();
    }

    private void addRecentCard(PlaybackSessionStore.RecentVideo video, long position,
                               ToLongFunction<Uri> duration,
                               Function<Uri, RecentVideoStatus> savedStatus,
                               Function<Uri, RecentVideoStatus> activeStatus) {
        Context context = getContext();
        int surface = PlayerAppearance.color(context, android.R.attr.colorBackgroundFloating);
        LinearLayout card = new LinearLayout(context);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(8), dp(12), dp(8));
        card.setMinimumHeight(dp(48));
        card.setBackground(ripple(surface, dp(14)));
        card.setFocusable(true);
        card.setOnClickListener(view -> playRecent.accept(video.uri));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(8);
        recent.addView(card, cardParams);
        ImageView thumbnail = new ImageView(context);
        thumbnail.setBackground(shape(PlayerAppearance.color(context, R.attr.playerSelection), dp(8)));
        thumbnail.setClipToOutline(true);
        thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout preview = column();
        card.addView(preview, new LinearLayout.LayoutParams(dp(88), -2));
        preview.addView(thumbnail, new LinearLayout.LayoutParams(-1, dp(50)));
        ProgressBar watched = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        watched.setProgressTintList(context.getColorStateList(R.color.primary_button_background));
        watched.setProgressBackgroundTintList(ColorStateList.valueOf(
                PlayerAppearance.color(context, R.attr.playerSelection)));
        watched.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        watched.setVisibility(INVISIBLE);
        preview.addView(watched, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout details = column();
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1);
        detailsParams.setMarginStart(dp(12));
        detailsParams.setMarginEnd(dp(8));
        card.addView(details, detailsParams);
        TextView filename = label(14, true);
        filename.setSingleLine(true);
        filename.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        details.addView(filename, new LinearLayout.LayoutParams(-1, -2));
        TextView timestamp = label(12, false);
        timestamp.setSingleLine(true);
        timestamp.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(timestamp, new LinearLayout.LayoutParams(-1, -2));
        TextView processing = label(12, false);
        processing.setSingleLine(true);
        processing.setEllipsize(TextUtils.TruncateAt.END);
        processing.setMinHeight(dp(16));
        processing.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        processing.setCompoundDrawablePadding(dp(4));
        details.addView(processing, new LinearLayout.LayoutParams(-1, -2));

        ImageView play = new ImageView(context);
        play.setImageResource(R.drawable.ic_home_play);
        play.setImageTintList(PlayerAppearance.controlIconTint(context));
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(play, new LinearLayout.LayoutParams(dp(24), dp(24)));
        details.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        boolean canResume = position >= RESUME_LABEL_MIN_MS;
        String progress = !canResume ? "Chưa phát"
                : "Tiếp tục từ " + SavedVideosDialog.formatPosition(position);
        String name = video.name != null ? video.name : PlaybackSessionStore.fallbackName(video.uri);
        filename.setText(recentTitle(name));
        timestamp.setText(progress);
        int muted = processing.getCurrentTextColor();
        int warning = PlayerAppearance.color(context, R.attr.playerIconWarning);
        int error = PlayerAppearance.color(context, R.attr.playerButtonDangerInk);
        class StatusUpdate implements Runnable {
            RecentVideoStatus saved;
            PlayerAppearance.StatusIcon shownIcon;

            @Override public void run() {
                RecentVideoStatus status = activeStatus.apply(video.uri);
                if (status == null) status = saved;
                processing.setVisibility(status == null ? INVISIBLE : VISIBLE);
                String label = status == null ? "" : status.text;
                if (!TextUtils.equals(processing.getText(), label)) processing.setText(label);
                PlayerAppearance.StatusIcon icon = status == null ? null : status.icon;
                if (icon != shownIcon) {
                    if (icon == null) processing.setCompoundDrawablesRelative(null, null, null, null);
                    else icon.apply(processing);
                    shownIcon = icon;
                }
                int color = icon == PlayerAppearance.StatusIcon.ERROR ? error
                        : icon == PlayerAppearance.StatusIcon.WARNING ? warning : muted;
                if (processing.getCurrentTextColor() != color) processing.setTextColor(color);
                card.setContentDescription("Phát " + filename.getText() + ", " + timestamp.getText()
                        + (status == null ? "" : ", " + status.text));
            }
        }
        StatusUpdate statusUpdate = new StatusUpdate();
        recentStatusUpdates.add(statusUpdate);
        statusUpdate.run();

        thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        thumbnail.setImageResource(R.drawable.ic_control_library);
        thumbnail.setImageTintList(PlayerAppearance.controlIconTint(context));
        int generation = previewGeneration;
        previewRequests.add(durations.submit(() -> {
            long durationMs = duration.applyAsLong(video.uri);
            if (durationMs <= 0) return;
            ((android.app.Activity) context).runOnUiThread(() -> {
                if (closed || generation != previewGeneration) return;
                timestamp.setText(progress + (canResume ? " / " : " · ")
                        + SavedVideosDialog.formatPosition(durationMs));
                if (canResume) {
                    watched.setMax(1000);
                    watched.setProgress((int) Math.round(1000.0 * Math.min(position, durationMs) / durationMs));
                    watched.setVisibility(VISIBLE);
                }
                statusUpdate.run();
            });
        }));
        previewRequests.add(previews.submit(() -> {
            RecentVideoStatus saved = savedStatus.apply(video.uri);
            ((android.app.Activity) context).runOnUiThread(() -> {
                if (closed || generation != previewGeneration) return;
                statusUpdate.saved = saved;
                statusUpdate.run();
            });
        }));
        previewRequests.add(previews.submit(() -> {
            String resolvedName = video.name == null
                    ? PlaybackSessionStore.displayName(context, video.uri, null) : null;
            Bitmap frame = readFrame(video.uri);
            ((android.app.Activity) context).runOnUiThread(() -> {
                if (closed || generation != previewGeneration) return;
                if (resolvedName != null) {
                    filename.setText(recentTitle(resolvedName));
                    statusUpdate.run();
                }
                if (frame != null) {
                    thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    thumbnail.setImageTintList(null);
                    thumbnail.setImageBitmap(frame);
                }
            });
        }));
    }

    private static String recentTitle(String name) {
        return TextUtils.isEmpty(name) ? "Video" : name.replaceAll("\\s+", " ");
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        View content = getChildAt(0);
        content.getLayoutParams().width = Math.min(MeasureSpec.getSize(widthSpec), dp(540));
        super.onMeasure(widthSpec, heightSpec);
    }

    private void cancelPreviews() {
        previewGeneration++;
        for (Future<?> request : previewRequests) request.cancel(true);
        previewRequests.clear();
    }

    private Bitmap readFrame(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(getContext(), uri);
            if (Build.VERSION.SDK_INT >= 27) {
                return retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 176, 100);
            }
            Bitmap frame = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            Bitmap preview = android.media.ThumbnailUtils.extractThumbnail(frame, 176, 100,
                    android.media.ThumbnailUtils.OPTIONS_RECYCLE_INPUT);
            return preview;
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // The thumbnail remains usable if releasing metadata fails.
            }
        }
    }

    void close() {
        closed = true;
        cancelPreviews();
        previews.shutdownNow();
        durations.shutdownNow();
    }

    private void addAction(LinearLayout parent, String text, int icon, PlayerAppearance.ButtonRole role,
                           int gap, Runnable action) {
        Button button = new Button(getContext());
        button.setAllCaps(false);
        button.setText(text);
        PlayerAppearance.styleButton(button, role, settings);
        button.setTextSize(settings.textSize(16));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinimumHeight(dp(52));
        button.setMinHeight(dp(52));
        button.setPaddingRelative(dp(16), dp(8), dp(16), dp(8));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, R.drawable.ic_home_chevron, 0);
        button.setCompoundDrawablePadding(dp(14));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        parent.addView(button, params);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(int size, boolean bold) {
        TextView text = new TextView(getContext());
        text.setTextSize(settings.textSize(size));
        text.setTextColor(PlayerAppearance.color(getContext(), bold
                ? android.R.attr.textColorPrimary : android.R.attr.textColorSecondary));
        if (bold) text.setTypeface(null, Typeface.BOLD);
        return text;
    }

    static RippleDrawable ripple(int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x307f7f7f),
                shape(fill, radius), shape(Color.WHITE, radius));
    }

    private static GradientDrawable shape(int fill, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(radius);
        return background;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
