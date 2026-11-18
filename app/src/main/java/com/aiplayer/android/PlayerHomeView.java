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
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

final class PlayerHomeView extends ScrollView {
    private final AppSettings settings;
    private final LinearLayout recent;
    private final Consumer<Uri> playRecent;
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private final List<Future<?>> previewRequests = new ArrayList<>();
    private int previewGeneration;
    private boolean closed;

    PlayerHomeView(Context context, AppSettings settings, Runnable open,
                   Runnable library, Consumer<Uri> playRecent) {
        super(context);
        this.settings = settings;
        this.playRecent = playRecent;
        setVerticalScrollBarEnabled(false);
        LinearLayout content = column();
        content.setPadding(dp(20), 0, dp(20), dp(20));
        int accent = context.getColorStateList(R.color.primary_button_background).getDefaultColor();
        int accentText = context.getColorStateList(R.color.primary_button_foreground).getDefaultColor();
        int surface = PlayerAppearance.color(context, android.R.attr.colorBackgroundFloating);
        int foreground = context.getColorStateList(R.color.control_icon_foreground).getDefaultColor();
        addAction(content, "Mở video", R.drawable.ic_control_open, accent, accentText, 0, open);
        addAction(content, "Video đã lưu", R.drawable.ic_control_library, surface, foreground, 12, library);

        recent = column();
        LinearLayout.LayoutParams section = new LinearLayout.LayoutParams(-1, -2);
        section.topMargin = dp(28);
        content.addView(recent, section);
        TextView heading = label(16, true);
        heading.setText("Video gần đây");
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        recent.addView(heading);

        recent.setVisibility(GONE);
        addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
    }

    void bindRecent(List<PlaybackSessionStore.RecentVideo> videos, ToLongFunction<Uri> position) {
        cancelPreviews();
        recent.removeViews(1, recent.getChildCount() - 1);
        recent.setVisibility(videos.isEmpty() ? GONE : VISIBLE);
        for (PlaybackSessionStore.RecentVideo video : videos) {
            addRecentCard(video, position.applyAsLong(video.uri));
        }
    }

    private void addRecentCard(PlaybackSessionStore.RecentVideo video, long position) {
        Context context = getContext();
        int surface = PlayerAppearance.color(context, android.R.attr.colorBackgroundFloating);
        int foreground = context.getColorStateList(R.color.control_icon_foreground).getDefaultColor();
        LinearLayout card = new LinearLayout(context);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(shape(surface, dp(14)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(12);
        recent.addView(card, cardParams);
        ImageView thumbnail = new ImageView(context);
        thumbnail.setBackground(shape(PlayerAppearance.color(context, R.attr.playerSelection), dp(8)));
        thumbnail.setClipToOutline(true);
        thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(thumbnail, new LinearLayout.LayoutParams(dp(88), dp(50)));

        LinearLayout details = column();
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1);
        detailsParams.setMarginStart(dp(12));
        detailsParams.setMarginEnd(dp(8));
        card.addView(details, detailsParams);
        TextView filename = label(14, true);
        filename.setSingleLine();
        filename.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(filename, new LinearLayout.LayoutParams(-1, -2));
        TextView timestamp = label(12, false);
        timestamp.setAlpha(0.75f);
        details.addView(timestamp);

        Button play = new Button(context);
        play.setMinWidth(0);
        play.setMinimumWidth(0);
        play.setStateListAnimator(null);
        play.setBackgroundTintList(null);
        play.setBackground(ripple(PlayerAppearance.color(context, R.attr.playerSelection), dp(24)));
        play.setForeground(context.getDrawable(R.drawable.ic_home_play).mutate());
        play.getForeground().setTint(foreground);
        play.setForegroundGravity(Gravity.CENTER);
        play.setOnClickListener(view -> playRecent.accept(video.uri));
        card.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));

        String progress = position < 1000 ? "Chưa xem"
                : "Tiếp tục từ " + SavedVideosDialog.formatPosition(position);
        String name = video.name != null ? video.name : PlaybackSessionStore.fallbackName(video.uri);
        setRecentTitle(filename, play, name, progress);
        timestamp.setText(progress);

        thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        thumbnail.setImageResource(R.drawable.ic_control_library);
        int generation = previewGeneration;
        previewRequests.add(previews.submit(() -> {
            String resolvedName = video.name == null
                    ? PlaybackSessionStore.displayName(context, video.uri, null) : null;
            Bitmap frame = readFrame(video.uri);
            if (frame == null && resolvedName == null) return;
            ((android.app.Activity) context).runOnUiThread(() -> {
                if (closed || generation != previewGeneration) return;
                if (resolvedName != null) setRecentTitle(filename, play, resolvedName, progress);
                if (frame != null) {
                    thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    thumbnail.setImageBitmap(frame);
                }
            });
        }));
    }

    private static void setRecentTitle(TextView filename, Button play, String name, String progress) {
        String title = TextUtils.isEmpty(name) ? "Video" : name.replaceAll("\\s+", " ");
        filename.setText(title);
        play.setContentDescription("Phát " + title + ", " + progress);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        View content = getChildAt(0);
        content.getLayoutParams().width = Math.min(MeasureSpec.getSize(widthSpec), dp(540));
        int top = Math.min(dp(100), Math.max(dp(12), MeasureSpec.getSize(heightSpec) / 7));
        if (getPaddingTop() != top) setPadding(0, top, 0, 0);
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
    }

    private void addAction(LinearLayout parent, String text, int icon, int fill, int foreground,
                           int gap, Runnable action) {
        Button button = new Button(getContext());
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(settings.textSize(16));
        button.setTextColor(foreground);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinimumHeight(dp(52));
        button.setMinHeight(dp(52));
        button.setPaddingRelative(dp(16), dp(8), dp(16), dp(8));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, R.drawable.ic_home_chevron, 0);
        button.setCompoundDrawablePadding(dp(14));
        button.setCompoundDrawableTintList(ColorStateList.valueOf(foreground));
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setBackground(ripple(fill, dp(14)));
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
