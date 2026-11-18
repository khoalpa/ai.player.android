package com.aiplayer.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import java.io.File;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;

import com.google.mlkit.nl.translate.TranslateLanguage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerControlView;
import androidx.media3.ui.PlayerView;

public final class MainActivity extends Activity {
    private static final int OPEN_VIDEO = 1;
    private static final int OPEN_SUBTITLES = 2;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final long STATUS_TIMEOUT_MS = 4_000L;
    private static final long CONTROLS_TIMEOUT_MS = 3_000L;
    private static final long DECODER_RETRY_DELAY_MS = 500L;
    private static final String DECODER_RECLAIMED_MESSAGE =
            "Android đã thu hồi bộ giải mã video. Nhấn Phát để thử lại.";
    private final Runnable hideStatus = () -> showStatus("");
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            sync();
            main.postDelayed(this, 80);
        }
    };

    private AppSettings settings;
    private VideoSession session;
    private VideoWork work;
    private PlaybackSessionStore sessionStore;
    private SavedVideoStore videoStore;
    private VideoDownloadFlow videoDownloads;
    private TelegramSessionClient telegramSession;
    private TelegramAuthorizationFlow.State lastTelegramState;
    private Uri currentVideo;
    private String currentVideoIdentity;
    private long savedTranscriptVersion = -1;
    private boolean destroyed;
    private boolean started;
    private final java.util.function.Consumer<String> workObserver = message -> {
        if (started && !destroyed) updateControls(message);
    };
    private int openRequest;
    private CueSpeech speech;
    private ExoPlayer player;
    private boolean decoderRetryUsed;
    private Runnable decoderRetry;
    private PlayerView playerView;
    private LinearLayout playbackArea;
    private FrameLayout surface;
    private boolean touchingControls;
    private final Runnable hideControls = this::hidePlaybackControls;
    private TextView subtitle;
    private TextView status;
    private LinearLayout feedback;
    private Button dismissStatus;
    private TextView title;
    private Button openSource;
    private PlayerHomeView home;
    private Button optionsMenu;
    private android.graphics.drawable.Drawable homeLogo;
    private android.graphics.drawable.Drawable playbackMenuBackground;
    private android.graphics.drawable.Drawable homeMenuBackground;
    private boolean restoringTelegram;
    private static final String TELEGRAM_AUTHENTICATED = "Đã xác thực Telegram qua TDLib";
    private SavedVideosDialog savedVideosDialog;
    private LinearLayout chrome, actions, shortcuts, transcriptPanel;
    private View empty;
    private ProgressBar progress;
    private Button primary, cancel, transcript, subtitlesButton, speechButton, followButton, transcriptLanguage;
    private ListView cueList;
    private CueAdapter cueAdapter;
    private boolean showTranslation, follow = true, fullscreen;
    private int activeRow = -1;
    private CueTimeline.Mode display = CueTimeline.Mode.SOURCE;
    private CueTimeline.Mode read = CueTimeline.Mode.OFF;
    private boolean hasVideo;
    private FrameLayout screenHost;
    private View settingsScreen;
    private boolean settingsDetail;
    private TextView telegramSettingsStatus;
    private TextView telegramSettingsDescription;
    private ProgressBar telegramSettingsProgress;
    private Button telegramSettingsAction;
    private boolean telegramRequestPending;
    private String telegramConnectionError;
    private boolean lightTheme;
    private int orientation;

    @Override
    @OptIn(markerClass = UnstableApi.class)
    protected void onCreate(Bundle state) {
        settings = AppSettings.load(this);
        selectTheme();
        super.onCreate(state);
        orientation = getResources().getConfiguration().orientation;
        sessionStore = new PlaybackSessionStore(this);
        work = VideoWork.obtain(this);
        session = work.session;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers).build();
        videoStore = new SavedVideoStore(this);
        videoDownloads = new VideoDownloadFlow(this, settings, videoStore, () -> telegramSession,
                this::showTelegramAccount, this::openTelegramPost, this::openSavedVideo);
        savedVideosDialog = new SavedVideosDialog(this, videoStore, sessionStore, settings);
        installPlayerScreen();
        playerView.setPlayer(player);
        applySystemBars();
        speech = new CueSpeech(this, this::onSpeechError);
        work.attach(workObserver);
        applyVideoVolume();
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                showPlaybackControls();
                sync();
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                Player.PositionInfo newPosition, int reason) {
                speech.stop();
                sync();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) {
                    recoverReclaimedDecoder(error);
                    return;
                }
                showStatus("Không phát được video: " + error.getErrorCodeName());
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY
                        && DECODER_RECLAIMED_MESSAGE.contentEquals(status.getText())) {
                    showStatus("");
                }
            }
        });
        updateControls(null);
        if (session.videoUri() != null && (!"file".equals(session.videoUri().getScheme())
                || isSavedFileAvailable(session.videoUri()))) {
            PlaybackSessionStore.Snapshot retained = new PlaybackSessionStore.Snapshot(session.videoUri(),
                    session.identity(), work.position, session.sourceLanguage, session.targetLanguage, session.cues);
            retained.name = session.videoName;
            openVideo(retained.uri, retained, false);
            updateControls(work.status());
        } else updateResumeVideo();
        handleSharedVideoIntent(getIntent());
        if (!Intent.ACTION_SEND.equals(getIntent().getAction())) restoreTelegramSession();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedVideoIntent(intent);
    }

    private void handleSharedVideoIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())
                || intent.getType() == null || !intent.getType().startsWith("video/")) return;
        Uri media = android.os.Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                : intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (media == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
            media = intent.getClipData().getItemAt(0).getUri();
        if (media == null) return;
        final Uri shared = media;
        ++openRequest; // Do not restore a previous playback session over the incoming video.
        new PlayerAppearance.DialogBuilder(this, settings).setTitle("Nhập video được chia sẻ")
                .setMessage("Lưu một bản sao video vào Video đã lưu của AI Player? Không cần nhập tài khoản hoặc mật khẩu Telegram.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Nhập video", (dialog, which) -> {
                    showStatus("Đang nhập video được chia sẻ...");
                    new SharedVideoImporter(this).importVideo(shared, (file, error) -> {
                        if (destroyed) return;
                        if (error != null) {
                            showVideoDownloadError(error.getMessage());
                            return;
                        }
                        new PlayerAppearance.DialogBuilder(this, settings).setTitle("Đã lưu video")
                                .setMessage(videoStore.displayName(file)).setNegativeButton("Đóng", null)
                                .setPositiveButton("Mở video", (d, w) -> openSavedVideo(file)).show();
                    });
                }).show();
    }

    private LinearLayout buildContent() {
        LinearLayout root = column();
        root.setBackgroundColor(PlayerAppearance.color(this, android.R.attr.colorBackground));
        if (android.os.Build.VERSION.SDK_INT >= 29) root.setForceDarkAllowed(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        chrome = row(root);
        chrome.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(this);
        title.setText("AI Player");
        title.setTextSize(settings.textSize(16));
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(4), 0);
        chrome.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        openSource = iconButton(chrome, R.drawable.ic_control_open, "Mở video", false,
                this::showVideoSources);
        homeLogo = getDrawable(R.mipmap.ic_launcher).mutate();
        homeLogo.setBounds(0, 0, dp(28), dp(28));
        title.setCompoundDrawablePadding(dp(10));
        optionsMenu = iconButton(chrome, R.drawable.ic_control_more, "Tùy chọn", false, null);
        playbackMenuBackground = optionsMenu.getBackground();
        homeMenuBackground = PlayerHomeView.ripple(Color.TRANSPARENT, dp(14));
        optionsMenu.setOnClickListener(anchor -> {
            PopupMenu popup = new PopupMenu(this, anchor);
            if (!hasVideo) {
                popup.getMenu().add("Telegram").setOnMenuItemClickListener(item -> {
                    showTelegramSettings();
                    return true;
                });
            } else {
                popup.getMenu().add("Nhập SRT/VTT").setEnabled(!session.busy)
                        .setOnMenuItemClickListener(item -> { pick(OPEN_SUBTITLES, "*/*"); return true; });
                popup.getMenu().add("Tạo lại transcript")
                        .setEnabled(!session.tracks.isEmpty() && !session.cues.isEmpty() && !session.busy)
                        .setOnMenuItemClickListener(item -> { chooseTrack(); return true; });
                popup.getMenu().add("Đổi ngôn ngữ bản dịch")
                        .setEnabled(session.targetLanguage != null && !session.busy)
                        .setOnMenuItemClickListener(item -> { chooseTranslationLanguage(); return true; });
            }
            popup.getMenu().add("Cài đặt").setOnMenuItemClickListener(item -> {
                showSettings();
                return true;
            });
            popup.show();
        });

        playbackArea = column();
        root.addView(playbackArea, new LinearLayout.LayoutParams(-1, 0, 1));
        surface = new FrameLayout(this);
        playbackArea.addView(surface);
        PlayerView view = new PlayerView(this);
        playerView = view;
        view.setBackgroundColor(Color.BLACK);
        view.setControllerShowTimeoutMs(0);
        view.setControllerHideOnTouch(false);
        view.setFullscreenButtonClickListener(this::setFullscreen);
        surface.addView(view, new FrameLayout.LayoutParams(-1, -1));

        if (home != null) home.close();
        home = new PlayerHomeView(this, settings, this::showVideoSources,
                this::showSavedVideos, this::resumeRecentVideo);
        empty = home;
        surface.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        subtitle = new TextView(this);
        subtitle.setTextColor(0xffffffff);
        subtitle.setBackgroundColor(0xc0000000);
        subtitle.setTextSize(settings.subtitleSize);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(8), dp(4), dp(8), dp(4));
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        surface.addView(subtitle, overlay);
        subtitle.setVisibility(View.GONE);
        View controller = view.findViewById(androidx.media3.ui.R.id.exo_controller);
        if (controller != null) styleMediaControls(controller);
        if (controller instanceof PlayerControlView) {
            ((PlayerControlView) controller).setAnimationEnabled(false);
        }
        View bottomBar = view.findViewById(androidx.media3.ui.R.id.exo_bottom_bar);
        View progressBar = view.findViewById(androidx.media3.ui.R.id.exo_progress);
        TextView captionView = subtitle;
        Runnable positionSubtitle = () -> {
            int margin = dp(8);
            if (controller != null && controller.getVisibility() == View.VISIBLE) {
                if (bottomBar != null) margin += bottomBar.getHeight();
                if (progressBar != null) margin += progressBar.getHeight();
            }
            if (overlay.bottomMargin != margin) {
                overlay.bottomMargin = margin;
                captionView.requestLayout();
            }
        };
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            positionSubtitle.run();
            if (view == playerView && shortcuts != null) {
                updateChromeVisibility();
                scheduleControlHide();
            }
        });
        surface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> positionSubtitle.run());

        feedback = row(root);
        feedback.setGravity(Gravity.CENTER_VERTICAL);
        feedback.setPadding(dp(8), 0, dp(8), 0);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        feedback.addView(progress, new LinearLayout.LayoutParams(dp(64), dp(24)));
        status = new TextView(this);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setTextSize(settings.textSize(12));
        feedback.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        dismissStatus = iconButton(feedback, R.drawable.ic_control_cancel, "Đóng thông báo",
                false, () -> showStatus(""));
        cancel = compactButton(feedback, "Hủy", () -> {
            offerTranslation = false;
            work.cancelWork("Đã hủy tác vụ.");
        });
        actions = row(root);
        primary = button(actions, "Tạo transcript", this::primaryAction);
        stylePrimaryButton(primary);
        shortcuts = row(root);
        subtitlesButton = iconButton(shortcuts, R.drawable.ic_control_captions, "Phụ đề", true, () -> showMode(false));
        speechButton = iconButton(shortcuts, R.drawable.ic_control_mute, "Đọc thành tiếng", true, () -> showMode(true));
        transcript = iconButton(shortcuts, R.drawable.ic_control_transcript, "Mở transcript", true, () -> {
            toggleTranscript();
        });
        transcript.setText("Transcript");
        buildTranscript(playbackArea);
        updatePlaybackLayout();
        return root;
    }

    private void styleMediaControls(View view) {
        int id = view.getId();
        if (view instanceof android.widget.ImageButton
                || id == androidx.media3.ui.R.id.exo_rew || id == androidx.media3.ui.R.id.exo_ffwd
                || id == androidx.media3.ui.R.id.exo_rew_with_amount
                || id == androidx.media3.ui.R.id.exo_ffwd_with_amount) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.width = dp(48);
            params.height = dp(48);
            View spacingTarget = (id == androidx.media3.ui.R.id.exo_rew_with_amount
                    || id == androidx.media3.ui.R.id.exo_ffwd_with_amount)
                    && view.getParent() instanceof FrameLayout ? (View) view.getParent() : view;
            if (spacingTarget.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams spacing = (ViewGroup.MarginLayoutParams) spacingTarget.getLayoutParams();
                int halfGap = dp(PlayerAppearance.CONTROL_GAP_DP / 2);
                spacing.setMarginStart(halfGap);
                spacing.setMarginEnd(halfGap);
                spacingTarget.setLayoutParams(spacing);
            }
            view.setLayoutParams(params);
            int bottomPadding = view.getPaddingBottom();
            view.setBackgroundTintList(null);
            PlayerAppearance.styleSurface(view, 0xe6192338, settings.raisedControls);
            // Keep the 3D gradient without casting a second outline over the video.
            view.setStateListAnimator(null);
            view.setElevation(0);
            view.setTranslationZ(0);
            if (view instanceof android.widget.ImageButton) {
                view.setPadding(dp(10), dp(10), dp(10), dp(10));
            } else {
                // Media3 draws the seek arrow in the foreground and the amount at the bottom.
                view.setPadding(0, 0, 0, bottomPadding);
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) styleMediaControls(group.getChildAt(i));
        }
    }

    private void updatePlaybackLayout() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        playbackArea.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        surface.setLayoutParams(new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 2));
        transcriptPanel.setLayoutParams(new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1));
    }

    private void toggleTranscript() {
        boolean visible = !transcriptPanel.isShown();
        setTranscriptVisible(visible);
        if (visible) resumeFollowing();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private Button compactButton(LinearLayout parent, CharSequence label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48)); // Maintain an accessible touch target.
        button.setMinimumHeight(dp(48));
        PlayerAppearance.styleButton(button, false, settings);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        if (action != null) button.setOnClickListener(v -> action.run());
        boolean stacked = parent.getOrientation() == LinearLayout.VERTICAL;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(stacked ? -1 : -2, -2);
        int halfGap = dp(PlayerAppearance.CONTROL_GAP_DP / 2);
        if (stacked) params.setMargins(0, halfGap, 0, halfGap);
        else params.setMargins(halfGap, 0, halfGap, 0);
        button.setGravity(stacked && parent.getGravity() != Gravity.CENTER
                ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        parent.addView(button, params);
        return button;
    }

    private Button iconButton(LinearLayout row, int icon, String description, boolean weighted, Runnable action) {
        Button button = new Button(this);
        // Keep a 48dp icon touch target for accessibility in compact mode.
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        PlayerAppearance.styleButton(button, false, settings);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);

        if (weighted) {
            button.setCompoundDrawableTintList(button.getTextColors());
            setControlIcon(button, icon, 20);
        } else {
            // Center the icon without reserving space for an empty line of text.
            android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
            drawable.setTintList(button.getTextColors());
            button.setForeground(drawable);
            button.setForegroundGravity(Gravity.CENTER);
        }
        describeControl(button, description);
        if (action != null) button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(weighted ? 0 : dp(48),
                weighted ? -2 : dp(48), weighted ? 1 : 0);
        int halfGap = dp(PlayerAppearance.CONTROL_GAP_DP / 2);
        params.setMargins(halfGap, halfGap, halfGap, halfGap);
        row.addView(button, params);
        return button;
    }

    private void stylePrimaryButton(Button button) {
        PlayerAppearance.styleButton(button, true, settings);

    }

    private void setLeadingIcon(Button button, int icon) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(8));
        button.setCompoundDrawableTintList(button.getTextColors());
    }

    private void describeControl(Button button, String description) {
        button.setContentDescription(description);
        button.setTooltipText(description);
    }

    private void updateModeIcon(Button button, int icon, String label, String description, CueTimeline.Mode mode) {
        setControlIcon(button, icon, 20);
        button.setSelected(button.isEnabled() && mode != CueTimeline.Mode.OFF);
        button.setText(label + " · " + (mode == CueTimeline.Mode.OFF || !button.isEnabled()
                ? "Tắt" : mode == CueTimeline.Mode.SOURCE ? "Gốc" : "Bản dịch"));
        describeControl(button, description + ": " + (button.isEnabled() ? MODES.get(mode.ordinal())
                : "chưa có transcript"));
    }

    private void setControlIcon(Button button, int icon, int size) {
        android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
        drawable.setBounds(0, 0, dp(size), dp(size));
        button.setCompoundDrawablesRelative(null, drawable, null, null);
    }

    private void setTranscriptVisible(boolean visible) {
        transcriptPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        transcript.setSelected(visible);
        describeControl(transcript, visible ? "Đóng transcript" : "Mở transcript");
        scheduleControlHide();
    }

    private void showVideoSources() {
        LinearLayout form = form();
        AlertDialog dialog = sheet("Mở video", form).setNegativeButton("Đóng", null).create();
        addVideoSource(form, dialog, "Chọn tệp trên thiết bị", R.drawable.ic_control_open, this::openPicker);
        addVideoSource(form, dialog, "Tải video từ URL", R.drawable.ic_control_download, this::showDownloadDialog);
        addVideoSource(form, dialog, "Video đã lưu", R.drawable.ic_control_library, this::showSavedVideos);
        showSheet(dialog);
    }

    private void addVideoSource(LinearLayout form, AlertDialog dialog, String label, int icon, Runnable action) {
        Button source = compactButton(form, label, () -> {
            dialog.dismiss();
            action.run();
        });
        source.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        source.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        source.setTextSize(settings.textSize(14));
        setLeadingIcon(source, icon);
    }

    private void showDownloadDialog() {
        videoDownloads.show();
    }

    private void restoreTelegramSession() {
        TelegramApiCredentialStore.Credentials config =
                new TelegramApiCredentialStore(this).load();
        if (config == null) return;
        TdJsonTelegramSessionClient restored =
                new TdJsonTelegramSessionClient(this, config.apiId, config.apiHash);
        if (!restored.isNativeAvailable()) return;
        startTelegramSession(restored, true);
    }

    private void startTelegramSession(TdJsonTelegramSessionClient client, boolean restore) {
        telegramSession = client;
        restoringTelegram = restore;
        telegramConnectionError = null;
        lastTelegramState = null;
        telegramRequestPending = true;
        refreshTelegramSettings();
        client.start(state -> {
            if (!destroyed && telegramSession == client) onTelegramAuthState(state);
        }, error -> {
            if (destroyed || telegramSession != client || error == null) return;
            telegramRequestPending = false;
            telegramConnectionError = error;
            refreshTelegramSettings();
            reportTelegramConnectionError(error);
        });
    }

    private void showTelegramAccount() {
        if (telegramRequestPending) return;
        if (isTelegramConnected()) {
            confirmTelegramLogout();
            return;
        }
        if (telegramSession != null && telegramSession.isNativeAvailable()) {
            if (telegramConnectionError == null && showTelegramCredential(telegramSession.authorizationState())) return;
            new PlayerAppearance.DialogBuilder(this, settings).setTitle("Tài khoản Telegram")
                    .setMessage("Nếu cần đổi API ID hoặc API hash, hãy xóa cấu hình đã lưu rồi kết nối lại.")
                    .setNegativeButton("Đóng", null)
                    .setPositiveButton("Xóa cấu hình API", (d, w) -> {
                        new TelegramApiCredentialStore(this).clear();
                        if (telegramSession instanceof TdJsonTelegramSessionClient)
                            ((TdJsonTelegramSessionClient) telegramSession).closeTransport();
                        telegramSession = null;
                        lastTelegramState = null;
                        telegramConnectionError = null;
                        refreshTelegramSettings();
                    }).show();
            return;
        }
        TdJsonTelegramSessionClient probe = new TdJsonTelegramSessionClient(this, 0, "");
        if (!probe.isNativeAvailable()) {
            new PlayerAppearance.DialogBuilder(this, settings).setTitle("TDLib chưa được cài đặt")
                    .setMessage("Cần build và đóng gói thư viện TDLib JSONJava chính thức (libtdjsonjava.so) trước khi có thể đăng nhập. AI Player không thể đăng nhập chỉ bằng giao diện.")
                    .setPositiveButton("Đóng", null).show();
            return;
        }

        LinearLayout form = column();
        EditText id = new EditText(this);
        id.setHint("Telegram API ID (my.telegram.org)");
        id.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        form.addView(id);
        EditText hash = new EditText(this);
        hash.setHint("Telegram API hash");
        hash.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(hash);
        new PlayerAppearance.DialogBuilder(this, settings).setTitle("Kết nối Telegram TDLib")
                .setMessage("Sử dụng API ID/hash ứng dụng của bạn. Không chia sẻ API hash hay mã xác minh trong tin nhắn hoặc log.")
                .setView(form).setNegativeButton("Hủy", null)
                .setPositiveButton("Tiếp tục", (dialog, which) -> {
                    int apiId;
                    try {
                        apiId = Integer.parseInt(id.getText().toString().trim());
                        if (apiId <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException invalid) {
                        showVideoDownloadError("API ID phải là số nguyên dương");
                        return;
                    }
                    String apiHash = hash.getText().toString().trim();
                    hash.setText("");
                    if (apiHash.length() != 32 || !apiHash.matches("[0-9a-fA-F]{32}")) {
                        showVideoDownloadError("Telegram API hash phải là 32 ký tự hexadecimal");
                        return;
                    }
                    try {
                        new TelegramApiCredentialStore(this).save(apiId, apiHash);
                    } catch (Exception failure) {
                        showVideoDownloadError("Không lưu an toàn được cấu hình Telegram bằng Android Keystore");
                        return;
                    }
                    startTelegramSession(new TdJsonTelegramSessionClient(this, apiId, apiHash), false);
                }).show();
    }

    private void confirmTelegramLogout() {
        TelegramSessionClient client = telegramSession;
        new PlayerAppearance.DialogBuilder(this, settings).setTitle("Đăng xuất Telegram?")
                .setMessage("AI Player sẽ đăng xuất khỏi Telegram và xóa cấu hình API đã lưu.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Đăng xuất", (dialog, which) -> {
                    if (telegramSession != client || telegramRequestPending || !isTelegramConnected()) return;
                    telegramRequestPending = true;
                    refreshTelegramSettings();
                    client.logoutAndClearSession(error -> {
                        if (telegramSession != client) return;
                        telegramRequestPending = false;
                        if (error == null) {
                            new TelegramApiCredentialStore(this).clear();
                            telegramSession = null;
                            lastTelegramState = null;
                            telegramConnectionError = null;
                        }
                        if (destroyed) return;
                        refreshTelegramSettings();
                        if (error != null) showVideoDownloadError(error);
                    });
                }).show();
    }

    private void reportTelegramConnectionError(String message) {
        if (telegramSettingsStatus == null) showVideoDownloadError(message);
    }

    private boolean showTelegramCredential(TelegramAuthorizationFlow.State state) {
        switch (state) {
            case PHONE_REQUIRED:
                promptTelegramCredential("Số điện thoại Telegram", "Số điện thoại quốc tế, ví dụ +84...",
                        false, (value, callback) -> telegramSession.submitPhone(value, callback));
                return true;
            case CODE_REQUIRED:
                promptTelegramCredential("Mã xác minh Telegram", "Mã OTP Telegram",
                        true, (value, callback) -> {
                            char[] code = value.toCharArray();
                            telegramSession.submitCode(code, callback);
                        });
                return true;
            case PASSWORD_REQUIRED:
                promptTelegramCredential("Xác thực hai bước", "Mật khẩu Telegram 2FA",
                        true, (value, callback) -> {
                            char[] password = value.toCharArray();
                            telegramSession.submitTwoStepPassword(password, callback);
                        });
                return true;
            default:
                return false;
        }
    }

    private void onTelegramAuthState(TelegramAuthorizationFlow.State state) {
        if (state == lastTelegramState) return;
        lastTelegramState = state;
        telegramRequestPending = state == TelegramAuthorizationFlow.State.INITIALIZING
                || state == TelegramAuthorizationFlow.State.LOGGING_OUT;
        telegramConnectionError = state == TelegramAuthorizationFlow.State.ERROR
                ? "TDLib gặp lỗi khởi tạo hoặc xác thực." : null;
        refreshTelegramSettings();
        if (showTelegramCredential(state)) {
            restoringTelegram = false;
            return;
        }
        switch (state) {
            case READY:
                if (!restoringTelegram) showStatus(TELEGRAM_AUTHENTICATED);
                videoDownloads.onTelegramReady();
                restoringTelegram = false;
                break;
            case ERROR:
                reportTelegramConnectionError(telegramConnectionError);
                break;
            case EMAIL_REQUIRED:
            case EMAIL_CODE_REQUIRED:
            case REGISTRATION_REQUIRED:
            case QR_CONFIRMATION_REQUIRED:
                new PlayerAppearance.DialogBuilder(this, settings).setTitle("Bước xác thực bổ sung")
                        .setMessage("Tài khoản yêu cầu phương thức xác thực Telegram bổ sung chưa có giao diện trong bản thử nghiệm này. Không thể coi là đã đăng nhập.")
                        .setPositiveButton("Đóng", null).show();
                break;
            default:
                // INITIALIZING, LOGGING_OUT, CLOSED: no credential prompt.
                break;
        }
    }

    private interface TelegramCredentialSubmit {
        void submit(String value, TelegramSessionClient.Completion callback);
    }

    private void promptTelegramCredential(String title, String hint, boolean sensitive,
                                          TelegramCredentialSubmit action) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        if (sensitive) field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new PlayerAppearance.DialogBuilder(this, settings).setTitle(title).setView(field)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xác nhận", (dialog, which) -> {
                    String value = field.getText().toString().trim();
                    field.setText("");
                    telegramRequestPending = true;
                    refreshTelegramSettings();
                    action.submit(value, error -> {
                        if (destroyed) return;
                        telegramRequestPending = false;
                        refreshTelegramSettings();
                        if (error != null) showVideoDownloadError(error);
                    });
                }).show();
    }

    private void openTelegramPost(java.net.URL post) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(post.toExternalForm()));
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(view);
        } catch (android.content.ActivityNotFoundException exception) {
            showVideoDownloadError("Không có ứng dụng mở được liên kết Telegram");
        }
    }
    private void showVideoDownloadError(String message) {
        new PlayerAppearance.DialogBuilder(this, settings).setTitle("Không thể tải video")
                .setMessage(message).setPositiveButton("Đóng", null).show();
    }

    private void showSavedVideos() {
        savedVideosDialog.show(this::openSavedVideo, file -> {
            Uri uri = Uri.fromFile(file);
            boolean current = uri.equals(currentVideo);
            if (current && (hasVideo || session.busy)) return false;
            if (!file.delete()) return false;
            videoStore.forget(file);
            if (current) {
                player.clearMediaItems();
                currentVideo = null;
            }
            sessionStore.removeRecent(uri);
            updateResumeVideo();
            return true;
        }, currentVideo, player.getCurrentPosition(), hasVideo || session.busy);
    }

    private boolean isSavedFileAvailable(Uri uri) {
        return "file".equals(uri.getScheme()) && uri.getPath() != null && new File(uri.getPath()).isFile();
    }

    private boolean canResumeVideo(Uri uri) {
        return "file".equals(uri.getScheme()) ? isSavedFileAvailable(uri)
                : uri.equals(currentVideo) || hasPersistedRead(uri);
    }

    private void updateResumeVideo() {
        List<PlaybackSessionStore.RecentVideo> available = new ArrayList<>();
        for (PlaybackSessionStore.RecentVideo video : sessionStore.recentVideos()) {
            if (canResumeVideo(video.uri)) available.add(video);
        }
        home.bindRecent(available, uri -> uri.equals(currentVideo)
                ? player.getCurrentPosition() : sessionStore.savedPosition(uri));
    }

    @OptIn(markerClass = UnstableApi.class)
    private void resumeRecentVideo(Uri uri) {
        int requestId = ++openRequest;
        if (!canResumeVideo(uri)) {
            sessionStore.removeRecent(uri);
            updateResumeVideo();
            showStatus("Video không còn khả dụng.");
            return;
        }
        if (uri.equals(currentVideo)) {
            sessionStore.recordOpened(uri, session.videoName);
            hasVideo = true;
            title.setText(session.videoName);
            updateControls(work.status());
            playerView.showController();
            player.play();
            return;
        }
        player.pause();
        savePlaybackSession();
        sessionStore.load(uri, restored -> {
            if (destroyed || requestId != openRequest) return;
            openVideo(uri, restored);
            player.play();
        });
    }

    private void openSavedVideo(File file) {
        Uri uri = Uri.fromFile(file);
        int requestId = ++openRequest;
        player.pause();
        savePlaybackSession();
        sessionStore.load(uri, restored -> {
            if (destroyed || requestId != openRequest) return;
            openVideo(uri, restored);
        });
    }

    private void openPicker() {
        pick(OPEN_VIDEO, "video/*");
    }

    @OptIn(markerClass = UnstableApi.class)
    private void setFullscreen(boolean enabled) {
        fullscreen = enabled;
        playerView.setFullscreenButtonState(enabled);
        applySystemBars();
        updateControls(null);
        showPlaybackControls();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        boolean orientationChanged = orientation != configuration.orientation;
        orientation = configuration.orientation;
        if (isLightTheme(settings.colorMode) != lightTheme) refreshAppearance();
        else updatePlaybackLayout();
        if (orientationChanged) {
            setFullscreen(hasVideo && orientation == Configuration.ORIENTATION_LANDSCAPE);
        }
    }

    private LinearLayout row(LinearLayout panel) {
        LinearLayout row = new LinearLayout(this);
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private Button button(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48)); // Maintain an accessible touch target.
        button.setMinimumHeight(dp(48));
        PlayerAppearance.styleButton(button, false, settings);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setOnClickListener(view -> action.run());
        int halfGap = dp(PlayerAppearance.CONTROL_GAP_DP / 2);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(halfGap, halfGap, halfGap, halfGap);
        row.addView(button, params);
        return button;
    }

    @Override
    public void onBackPressed() {
        if (settingsScreen != null) {
            if (settingsDetail) showSettings();
            else closeSettings();
        } else if (fullscreen) {
            setFullscreen(false);
        } else if (transcriptPanel.isShown()) {
            setTranscriptVisible(false);
        } else if (hasVideo) {
            player.pause();
            savePlaybackSession();
            hasVideo = false;
            title.setText(R.string.app_name);
            updateResumeVideo();
            updateControls("");
        } else {
            super.onBackPressed();
        }
    }

    private void pick(int request, String type) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(type);
        startActivityForResult(intent, request);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == OPEN_VIDEO) {
            int requestId = ++openRequest;
            player.pause();
            savePlaybackSession();
            sessionStore.load(uri, restored -> {
                if (destroyed || requestId != openRequest) return;
                savePlaybackSession();
                rememberVideo(uri, data.getFlags());
                openVideo(uri, restored);
            });
        } else if (request == OPEN_SUBTITLES && hasVideo) {
            LinearLayout form = form();
            List<String> codes = targetLanguages();
            Spinner source = languages(form, "Ngôn ngữ phụ đề", codes, settings.sourceLanguage);
            showSheet(sheet("Nhập SRT/VTT", form).setNegativeButton("Hủy", null)
                    .setPositiveButton("Nhập", (dialog, which) ->
                            session.load(uri, codes.get(source.getSelectedItemPosition()))));
        }
    }

    private boolean hasPersistedRead(Uri uri) {
        return getContentResolver().getPersistedUriPermissions().stream()
                .anyMatch(permission -> uri.equals(permission.getUri()) && permission.isReadPermission());
    }

    private void rememberVideo(Uri uri, int flags) {
        savedTranscriptVersion = -1;
        if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Providers may grant read access only for the current session.
            }
        }
    }

    private void openVideo(Uri uri, PlaybackSessionStore.Snapshot restored) {
        openVideo(uri, restored, true);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void openVideo(Uri uri, PlaybackSessionStore.Snapshot restored, boolean resetSession) {
        cancelDecoderRetry();
        decoderRetryUsed = false;
        if (DECODER_RECLAIMED_MESSAGE.contentEquals(status.getText())) showStatus("");
        speech.stop();
        hasVideo = true;
        savedTranscriptVersion = -1;
        setTranscriptVisible(false);
        currentVideo = uri;
        currentVideoIdentity = restored == null ? null : restored.identity;
        offerTranslation = false;
        player.setPlayWhenReady(false);
        player.setMediaItem(MediaItem.fromUri(uri), restored == null ? 0L : restored.position);
        if (resetSession) {
            session.open(uri, settings.sourceLanguage, restored);
            sessionStore.recordOpened(uri, session.videoName);
        }
        title.setText(TextUtils.isEmpty(session.videoName) ? "Video" : session.videoName);
        player.prepare();
        updateControls(null);
        playerView.showController();
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setFullscreen(true);
        }
        sync();
    }

    private void recoverReclaimedDecoder(PlaybackException error) {
        showStatus(DECODER_RECLAIMED_MESSAGE);
        if (!started || !hasVideo || currentVideo == null || decoderRetryUsed || decoderRetry != null) return;

        int request = openRequest;
        decoderRetry = () -> {
            decoderRetry = null;
            if (!started || !hasVideo || request != openRequest || player.getPlayerError() != error) return;

            decoderRetryUsed = true;
            // prepare() retries the current item at its existing position and playWhenReady state.
            player.prepare();
        };
        main.postDelayed(decoderRetry, DECODER_RETRY_DELAY_MS);
    }

    private void cancelDecoderRetry() {
        if (decoderRetry == null) return;
        main.removeCallbacks(decoderRetry);
        decoderRetry = null;
    }

    private void savePlaybackSession() {
        if (currentVideo == null) return;
        long version = session.transcriptVersion();
        long position = player.getCurrentPosition();
        work.position = position;
        if (savedTranscriptVersion == version) {
            sessionStore.savePosition(currentVideo, currentVideoIdentity, position);
        } else {
            sessionStore.save(currentVideo, currentVideoIdentity, position, session);
            savedTranscriptVersion = version;
        }
    }

    private static final List<String> SOURCE_LANGUAGES = Arrays.asList("ja", "en", "vi", "ko", "zh");

    private static String languageName(String code) {
        return Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag("vi"));
    }

    private Spinner selection(LinearLayout form, String label, List<String> options, int selected) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(settings.textSize(12));
        form.addView(caption);
        Spinner picker = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, recycled, parent);
                item.setTextSize(settings.textSize(16));
                return item;
            }

            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                TextView item = (TextView) super.getDropDownView(position, recycled, parent);
                item.setTextSize(settings.textSize(16));
                return item;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter);
        picker.setSelection(Math.max(0, selected));
        picker.setContentDescription(label);
        picker.setMinimumHeight(dp(48));
        form.addView(picker, new LinearLayout.LayoutParams(-1, -2));
        return picker;
    }

    private Spinner languages(LinearLayout form, String label, List<String> codes, String current) {
        List<String> names = new ArrayList<>();
        for (String code : codes) names.add(languageName(code));
        return selection(form, label, names, codes.indexOf(current));
    }

    private LinearLayout form() {
        LinearLayout form = column();
        form.setPadding(dp(12), dp(8), dp(12), dp(8));
        return form;
    }

    private AlertDialog.Builder sheet(String title, LinearLayout form) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        return new PlayerAppearance.DialogBuilder(this, settings).setTitle(title).setView(scroll);
    }

    private void showSheet(AlertDialog.Builder builder) {
        showSheet(builder.create());
    }

    private void showSheet(AlertDialog dialog) {
        PlayerAppearance.showDialog(dialog, settings);
        dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.getWindow().setLayout(-1, -2);
    }

    private List<String> targetLanguages() {
        List<String> codes = new ArrayList<>(TranslateLanguage.getAllLanguages());
        codes.sort((a, b) -> languageName(a).compareToIgnoreCase(languageName(b)));
        return codes;
    }

    private void primaryAction() {
        if (session.busy) return;
        if (session.cues.isEmpty()) {
            chooseTrack();
            return;
        }
        if (session.missingTranslations() > 0) {
            session.translate(session.targetLanguage);
            return;
        }
        if (session.targetLanguage == null) chooseTranslationLanguage();
        else toggleTranscript();
    }

    private void chooseTranslationLanguage() {
        List<String> codes = targetLanguages();
        LinearLayout form = form();
        Spinner target = languages(form, "Ngôn ngữ đích", codes, settings.targetLanguage);
        showSheet(sheet("Dịch transcript", form).setNegativeButton("Hủy", null)
                .setPositiveButton("Dịch", (dialog, which) -> {
                    settings.targetLanguage = codes.get(target.getSelectedItemPosition());
                    settings.save(this);
                    offerTranslation = true;
                    session.translate(settings.targetLanguage);
                }));
    }

    private void chooseTrack() {
        List<VideoSession.AudioTrack> tracks = new ArrayList<>(session.tracks);
        if (tracks.isEmpty()) return;
        List<String> labels = new ArrayList<>();
        int playing = playingTrack(tracks);
        for (int i = 0; i < tracks.size(); i++) {
            labels.add(tracks.get(i).label + (i == playing ? " (đang phát)" : ""));
        }
        LinearLayout form = form();
        Spinner track = selection(form, "Track âm thanh", labels, Math.max(0, playing));
        Spinner language = languages(form, "Ngôn ngữ lời thoại", SOURCE_LANGUAGES, settings.sourceLanguage);
        showSheet(sheet("Tạo transcript", form).setNegativeButton("Hủy", null)
                .setPositiveButton("Tạo transcript", (dialog, which) -> {
                    settings.sourceLanguage = SOURCE_LANGUAGES.get(language.getSelectedItemPosition());
                    settings.save(this);
                    session.generate(tracks.get(track.getSelectedItemPosition()), settings.sourceLanguage);
                }));
    }

    private int playingTrack(List<VideoSession.AudioTrack> tracks) {
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSelected(i)) continue;
                Format format = group.getTrackFormat(i);
                int match = -1;
                for (int index = 0; index < tracks.size(); index++) {
                    VideoSession.AudioTrack track = tracks.get(index);
                    if (!track.mime.equals(format.sampleMimeType)
                            || !audioLanguage(track.language).equals(audioLanguage(format.language))) continue;
                    if (match != -1) return -1;
                    match = index;
                }
                return match;
            }
        }
        return -1;
    }

    private static String audioLanguage(String language) {
        if (language == null || "und".equalsIgnoreCase(language)) return "";
        Locale locale = Locale.forLanguageTag(language);
        try {
            return locale.getISO3Language();
        } catch (java.util.MissingResourceException ex) {
            return locale.getLanguage();
        }
    }

    private boolean isLightTheme(int colorMode) {
        return colorMode == 1 || (colorMode == 2
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES);
    }

    private void selectTheme() {
        lightTheme = isLightTheme(settings.colorMode);
        setTheme(lightTheme ? R.style.AppThemeLight : R.style.AppTheme);
    }

    private void applySystemBars() {
        int flags = lightTheme ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (lightTheme && android.os.Build.VERSION.SDK_INT >= 27) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        if (fullscreen && settingsScreen == null) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        getWindow().setStatusBarColor(PlayerAppearance.color(this, android.R.attr.statusBarColor));
        getWindow().setNavigationBarColor(PlayerAppearance.color(this, android.R.attr.navigationBarColor));
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void refreshAppearance() {
        String currentTitle = title.getText().toString();
        CharSequence currentStatus = status.getText();
        boolean transcriptVisible = transcriptPanel.getVisibility() == View.VISIBLE;
        int transcriptPosition = cueList.getFirstVisiblePosition();
        boolean controllerVisible = controlsAreVisible();
        PlayerView previousView = playerView;
        previousView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);
        selectTheme();
        boolean settingsOpen = settingsScreen != null;
        installPlayerScreen();
        PlayerView.switchTargetView(player, previousView, playerView);
        playerView.setFullscreenButtonState(fullscreen);
        applySystemBars();
        title.setText(currentTitle);
        status.setText(currentStatus);
        setTranscriptVisible(transcriptVisible);
        if (transcriptVisible) {
            cueList.setSelection(transcriptPosition);
            followButton.setVisibility(follow ? View.GONE : View.VISIBLE);
        }
        updateResumeVideo();
        if (hasVideo && controllerVisible) playerView.showController();
        else playerView.hideController();
        updateControls(null);
        sync();
        scheduleControlHide();
        if (settingsOpen) showSettings();
    }

    private void showAppearance() {
        LinearLayout form = form();
        Spinner mode = selection(form, "Chế độ màu",
                Arrays.asList("Tối đen", "Sáng", "Theo hệ thống"), settings.colorMode);
        Spinner depth = selection(form, "Hiệu ứng chiều sâu",
                Arrays.asList("Phẳng", "Nổi nhẹ 3D"), settings.raisedControls ? 1 : 0);
        Spinner font = selection(form, "Cỡ chữ giao diện",
                Arrays.asList("Nhỏ", "Tiêu chuẩn", "Lớn"), settings.fontSize);
        Button save = settingsEditor("Giao diện", form, () -> {
            settings.colorMode = mode.getSelectedItemPosition();
            settings.raisedControls = depth.getSelectedItemPosition() == 1;
            settings.fontSize = font.getSelectedItemPosition();
            settings.save(this);
            refreshAppearance();
        });
        watchSettingsSelection(() -> {
            save.setEnabled(mode.getSelectedItemPosition() != settings.colorMode
                    || (depth.getSelectedItemPosition() == 1) != settings.raisedControls
                    || font.getSelectedItemPosition() != settings.fontSize);
        }, mode, depth, font);
    }

    private void installPlayerScreen() {
        screenHost = new FrameLayout(this);
        screenHost.addView(buildContent(), new FrameLayout.LayoutParams(-1, -1));
        settingsScreen = null;
        telegramSettingsStatus = null;
        telegramSettingsDescription = null;
        telegramSettingsProgress = null;
        telegramSettingsAction = null;
        setContentView(screenHost);
    }

    private void closeSettings() {
        screenHost.removeView(settingsScreen);
        settingsScreen = null;
        telegramSettingsStatus = null;
        telegramSettingsDescription = null;
        telegramSettingsProgress = null;
        telegramSettingsAction = null;
        screenHost.getChildAt(0).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        ((ViewGroup) screenHost.getChildAt(0)).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        applySystemBars();
        scheduleControlHide();
    }

    private LinearLayout settingsPage(String heading, LinearLayout form, boolean detail) {
        if (settingsScreen != null) screenHost.removeView(settingsScreen);
        telegramSettingsStatus = null;
        telegramSettingsDescription = null;
        telegramSettingsProgress = null;
        telegramSettingsAction = null;
        settingsDetail = detail;
        LinearLayout page = column();
        page.setBackgroundColor(PlayerAppearance.color(this, android.R.attr.colorBackground));
        page.setClickable(true);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = row(page);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = compactButton(header, "‹", detail ? this::showSettings : this::closeSettings);
        back.setBackgroundResource(0);
        back.setElevation(0);
        back.setStateListAnimator(null);
        back.setTextSize(settings.textSize(24));
        back.setIncludeFontPadding(false);
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        back.setContentDescription("Quay lại");
        TextView titleView = new TextView(this);
        titleView.setText(heading);
        titleView.setTextSize(settings.textSize(22));
        titleView.setPadding(dp(12), 0, dp(12), 0);
        if (android.os.Build.VERSION.SDK_INT >= 28) titleView.setAccessibilityHeading(true);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(form);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        settingsScreen = page;
        screenHost.getChildAt(0).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ((ViewGroup) screenHost.getChildAt(0)).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        screenHost.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.requestApplyInsets();
        applySystemBars();
        back.requestFocus();
        return page;
    }

    private Button settingsEditor(String title, LinearLayout form, Runnable commit) {
        LinearLayout footer = row(settingsPage(title, form, true));
        footer.setPadding(dp(12), dp(8), dp(12), dp(8));
        button(footer, "Hủy", this::showSettings);
        Button save = button(footer, "Lưu", commit);
        stylePrimaryButton(save);
        save.setEnabled(false);
        return save;
    }

    private void watchSettingsSelection(Runnable refresh, Spinner... fields) {
        android.widget.AdapterView.OnItemSelectedListener listener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                refresh.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        };
        for (Spinner field : fields) field.setOnItemSelectedListener(listener);
    }

    private Button settingsEntry(LinearLayout form, int icon, String title, String summary, Runnable action) {
        Button entry = compactButton(form, settingsEntryText(title, summary), action);
        setLeadingIcon(entry, icon);
        entry.setPadding(dp(12), dp(10), dp(12), dp(10));
        return entry;
    }

    private CharSequence settingsEntryText(String title, String summary) {
        SpannableString label = new SpannableString(title + "  ›\n" + summary);
        int start = label.length() - summary.length();
        label.setSpan(new ForegroundColorSpan(PlayerAppearance.color(this, android.R.attr.textColorSecondary)),
                start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new android.text.style.RelativeSizeSpan(0.9f),
                start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    private void showSettings() {
        LinearLayout form = form();
        settingsPage("Cài đặt", form, false);
        String color = Arrays.asList("Tối đen", "Sáng", "Theo hệ thống").get(settings.colorMode);
        String font = Arrays.asList("Nhỏ", "Tiêu chuẩn", "Lớn").get(settings.fontSize);
        settingsEntry(form, R.drawable.ic_control_appearance, "Giao diện",
                color + " · " + (settings.raisedControls ? "Nổi nhẹ 3D" : "Phẳng") + " · " + font,
                this::showAppearance);
        settingsEntry(form, R.drawable.ic_control_captions, "Ngôn ngữ và phụ đề",
                languageName(settings.sourceLanguage) + " → " + languageName(settings.targetLanguage)
                        + " · Phụ đề " + settings.subtitleSize + " sp", this::showLanguageSettings);
        settingsEntry(form, R.drawable.ic_control_voice, "Âm thanh",
                "Video " + settings.sourceVolume + "% · Giọng đọc " + settings.dubbingVolume + "%",
                this::showAudioSettings);
        AppBuildInfo build = new AppBuildInfo(this);
        settingsEntry(form, R.drawable.ic_control_info, "Thông tin ứng dụng", build.summary,
                () -> showBuildSettings(build));
    }

    private void showTelegramSettings() {
        LinearLayout form = form();
        settingsPage("Tài khoản Telegram", form, false);
        form.setPadding(dp(20), dp(12), dp(20), dp(20));
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        PlayerAppearance.styleSurface(card,
                PlayerAppearance.color(this, android.R.attr.colorBackgroundFloating), false);
        form.addView(card, new LinearLayout.LayoutParams(-1, -2));
        TextView heading = new TextView(this);
        heading.setText("Telegram");
        heading.setTypeface(null, Typeface.BOLD);
        heading.setTextSize(settings.textSize(17));
        card.addView(heading);
        telegramSettingsStatus = new TextView(this);
        telegramSettingsStatus.setTextSize(settings.textSize(14));
        telegramSettingsStatus.setPadding(0, dp(8), 0, 0);
        telegramSettingsStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(telegramSettingsStatus);
        telegramSettingsDescription = new TextView(this);
        telegramSettingsDescription.setTextSize(settings.textSize(14));
        telegramSettingsDescription.setPadding(0, dp(8), 0, dp(12));
        card.addView(telegramSettingsDescription);
        telegramSettingsProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        card.addView(telegramSettingsProgress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        telegramSettingsAction = compactButton(card, "", this::handleTelegramSettingsAction);
        telegramSettingsAction.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        refreshTelegramSettings();
    }

    private void handleTelegramSettingsAction() {
        if (telegramRequestPending) return;
        if (telegramSession != null
                && telegramSession.authorizationState() == TelegramAuthorizationFlow.State.CLOSED) {
            telegramSession = null;
            restoreTelegramSession();
            if (telegramSession != null) return;
        }
        showTelegramAccount();
    }

    private void showBuildSettings(AppBuildInfo build) {
        LinearLayout form = form();
        TextView details = new TextView(this);
        details.setText(build.details);
        details.setTextSize(settings.textSize(16));
        details.setPadding(dp(4), dp(8), dp(4), dp(14));
        details.setTextIsSelectable(true);
        form.addView(details);
        LinearLayout page = settingsPage("Thông tin ứng dụng", form, true);
        LinearLayout footer = column();
        footer.setPadding(dp(12), dp(8), dp(12), dp(8));
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        String copyLabel = "Sao chép thông tin bản dựng";
        Button copy = new Button(this);
        copy.setText(copyLabel);
        copy.setAllCaps(false);
        copy.setGravity(Gravity.CENTER);
        copy.setMinHeight(dp(48));
        copy.setMinimumHeight(dp(48));
        copy.setPadding(dp(12), dp(8), dp(12), dp(8));
        copy.setStateListAnimator(null);
        copy.setBackgroundTintList(null);
        copy.setBackground(PlayerHomeView.ripple(
                PlayerAppearance.color(this, android.R.attr.colorAccent), dp(14)));
        copy.setTextColor(getColorStateList(R.color.primary_button_foreground));
        copy.setTextSize(settings.textSize(14));
        copy.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Runnable resetCopyLabel = () -> copy.setText(copyLabel);
        copy.setOnClickListener(view -> {
            if (!build.copy(this)) return;
            copy.removeCallbacks(resetCopyLabel);
            copy.setText("Đã sao chép");
            copy.postDelayed(resetCopyLabel, 2_000L);
        });
        footer.addView(copy, new LinearLayout.LayoutParams(-1, -2));
    }

    private void refreshTelegramSettings() {
        if (telegramSettingsAction != null) {
            TelegramAuthorizationFlow.State state = telegramSession == null
                    ? TelegramAuthorizationFlow.State.UNAVAILABLE : telegramSession.authorizationState();
            boolean connected = isTelegramConnected();
            String description = "Kết nối tài khoản để tải video từ Telegram.";
            String action = "Kết nối Telegram";
            if (telegramRequestPending) {
                description = "Đang chờ phản hồi từ Telegram.";
            } else if (connected) {
                description = "Có thể tải video từ các bài đăng mà tài khoản của bạn có quyền truy cập.";
                action = "Đăng xuất";
            } else if (state == TelegramAuthorizationFlow.State.CLOSED) {
                description = "Phiên Telegram đã đóng. Bạn có thể thử kết nối lại.";
                action = "Thử lại";
            } else if (telegramConnectionError != null || state == TelegramAuthorizationFlow.State.EMAIL_REQUIRED
                    || state == TelegramAuthorizationFlow.State.EMAIL_CODE_REQUIRED
                    || state == TelegramAuthorizationFlow.State.REGISTRATION_REQUIRED
                    || state == TelegramAuthorizationFlow.State.QR_CONFIRMATION_REQUIRED) {
                description = telegramConnectionError != null ? telegramConnectionError
                        : "Tài khoản cần bước xác thực bổ sung chưa được hỗ trợ.";
                action = "Kiểm tra cấu hình";
            } else if (state == TelegramAuthorizationFlow.State.PHONE_REQUIRED
                    || state == TelegramAuthorizationFlow.State.CODE_REQUIRED
                    || state == TelegramAuthorizationFlow.State.PASSWORD_REQUIRED) {
                description = "Hoàn tất bước xác minh để kết nối tài khoản.";
                action = "Tiếp tục xác thực";
            }
            String summary = telegramSummary();
            int dotColor = connected && !telegramRequestPending ? getColor(R.color.ai_ready)
                    : PlayerAppearance.color(this, android.R.attr.textColorSecondary);
            SpannableString statusLabel = new SpannableString("●  " + summary);
            statusLabel.setSpan(new ForegroundColorSpan(dotColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            telegramSettingsStatus.setText(statusLabel);
            telegramSettingsStatus.setContentDescription(summary);
            telegramSettingsDescription.setText(description);
            telegramSettingsProgress.setVisibility(telegramRequestPending ? View.VISIBLE : View.GONE);
            telegramSettingsAction.setVisibility(telegramRequestPending ? View.GONE : View.VISIBLE);
            telegramSettingsAction.setText(action);
            boolean primary = "Kết nối Telegram".equals(action);
            int fill = PlayerAppearance.color(this, primary ? android.R.attr.colorAccent
                    : android.R.attr.colorBackground);
            telegramSettingsAction.setBackground(PlayerHomeView.ripple(fill, dp(14)));
            telegramSettingsAction.setStateListAnimator(null);
            telegramSettingsAction.setElevation(0);
            telegramSettingsAction.setTextColor(primary ? getColorStateList(R.color.primary_button_foreground)
                    : getColorStateList(R.color.control_icon_foreground));
        }
    }

    private String telegramSummary() {
        if (telegramRequestPending) {
            return isTelegramConnected() || lastTelegramState == TelegramAuthorizationFlow.State.LOGGING_OUT
                    ? "Đang đăng xuất…" : "Đang xác thực…";
        }
        if (telegramConnectionError != null) return "Lỗi kết nối";
        return isTelegramConnected() ? "Đã kết nối" : "Chưa kết nối";
    }

    private boolean isTelegramConnected() {
        return telegramSession != null && telegramSession.authorizationState() == TelegramAuthorizationFlow.State.READY;
    }

    private void showLanguageSettings() {
        LinearLayout form = form();
        List<String> codes = targetLanguages();
        Spinner source = languages(form, "Ngôn ngữ lời thoại", SOURCE_LANGUAGES, settings.sourceLanguage);
        Spinner target = languages(form, "Ngôn ngữ đích", codes, settings.targetLanguage);
        List<String> sizes = Arrays.asList("12 sp", "14 sp", "16 sp", "18 sp", "20 sp", "22 sp", "24 sp");
        Spinner size = selection(form, "Cỡ chữ phụ đề", sizes, (settings.subtitleSize - 12) / 2);
        Button save = settingsEditor("Ngôn ngữ và phụ đề", form, () -> {
            settings.sourceLanguage = SOURCE_LANGUAGES.get(source.getSelectedItemPosition());
            settings.targetLanguage = codes.get(target.getSelectedItemPosition());
            settings.subtitleSize = 12 + 2 * size.getSelectedItemPosition();
            subtitle.setTextSize(settings.subtitleSize);
            settings.save(this);
            updateControls(null);
            showSettings();
        });
        int initialSource = source.getSelectedItemPosition();
        int initialTarget = target.getSelectedItemPosition();
        int initialSize = size.getSelectedItemPosition();
        watchSettingsSelection(() -> save.setEnabled(source.getSelectedItemPosition() != initialSource
                || target.getSelectedItemPosition() != initialTarget || size.getSelectedItemPosition() != initialSize),
                source, target, size);
    }

    private void showAudioSettings() {
        LinearLayout form = form();
        SeekBar video = volumeField(form, "Âm lượng video", settings.sourceVolume);
        SeekBar voice = volumeField(form, "Âm lượng giọng đọc", settings.dubbingVolume);
        Button save = settingsEditor("Âm thanh", form, () -> {
            settings.sourceVolume = video.getProgress();
            settings.dubbingVolume = voice.getProgress();
            settings.save(this);
            updateControls(null);
            showSettings();
        });
        Runnable refresh = () -> save.setEnabled(video.getProgress() != settings.sourceVolume
                || voice.getProgress() != settings.dubbingVolume);
        // These fields already update their labels when progress changes.
        video.setTag(refresh);
        voice.setTag(refresh);
    }
    private static final List<String> MODES = Arrays.asList("Tắt", "Bản gốc", "Bản dịch");
    private boolean offerTranslation;

    private void showMode(boolean voice) {
        LinearLayout form = form();
        List<String> choices = new ArrayList<>(MODES);
        if (session.targetLanguage == null) choices.remove(2);
        Spinner mode = selection(form, voice ? "Nội dung đọc" : "Nội dung hiển thị", choices,
                (voice ? read : display).ordinal() < choices.size() ? (voice ? read : display).ordinal() : 0);
        if (choices.size() < MODES.size()) addLabel(form, "Cần tạo bản dịch trước để chọn Bản dịch.");
        SeekBar volume = voice ? volumeField(form, "Âm lượng giọng đọc", settings.dubbingVolume) : null;
        TextView availability = new TextView(this);
        availability.setTextSize(settings.textSize(12));
        if (voice) {
            form.addView(availability);
            mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    availability.setText(speechUnavailable(CueTimeline.Mode.values()[position]));
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        int missing = voice ? session.missingTranslations() : 0;
        if (missing > 0) addLabel(form, "Còn " + missing + " đoạn chưa có bản dịch.");
        AlertDialog.Builder modeDialog = sheet(voice ? "Đọc thành tiếng" : "Phụ đề", form).setNegativeButton("Hủy", null)
                .setPositiveButton("Áp dụng", (dialog, which) -> {
                    CueTimeline.Mode selected = CueTimeline.Mode.values()[mode.getSelectedItemPosition()];
                    if (volume != null) {
                        String unavailable = speechUnavailable(selected);
                        if (!unavailable.isEmpty()) {
                            showStatus(unavailable);
                            return;
                        }
                        read = selected;
                        settings.dubbingVolume = volume.getProgress();
                        settings.save(this);
                        speech.stop();
                    } else {
                        display = selected;
                    }
                    updateControls(null);
                });
        if (missing > 0 && !session.busy) {
            modeDialog.setNeutralButton("Dịch lại", (window, which) -> {
                if (!session.busy && session.missingTranslations() > 0) {
                    session.translate(session.targetLanguage);
                }
            });
        }
        showSheet(modeDialog);
    }

    private void buildTranscript(LinearLayout root) {
        transcriptPanel = column();
        transcriptPanel.setBackgroundColor(PlayerAppearance.color(this, android.R.attr.colorBackgroundFloating));
        root.addView(transcriptPanel, new LinearLayout.LayoutParams(-1, 0, 1));
        transcriptPanel.setVisibility(View.GONE);
        LinearLayout toolbar = row(transcriptPanel);
        transcriptLanguage = button(toolbar, "Bản gốc", () -> {
            showTranslation = !showTranslation;
            refreshTranscript();
        });
        followButton = iconButton(toolbar, R.drawable.ic_control_follow, "Về đoạn đang phát", false,
                this::resumeFollowing);
        followButton.setVisibility(View.GONE);
        cueList = new ListView(this);
        cueAdapter = new CueAdapter();
        cueList.setAdapter(cueAdapter);
        cueList.setOnItemClickListener((parent, view, position, id) ->
                player.seekTo(session.cues.get(position).startMs));
        cueList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) {
                    follow = false;
                    followButton.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {}
        });
        transcriptPanel.addView(cueList, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void resumeFollowing() {
        follow = true;
        followButton.setVisibility(View.GONE);
        if (activeRow >= 0) cueList.setSelection(activeRow);
    }

    private void refreshTranscript() {
        if (session.targetLanguage == null) showTranslation = false;
        transcriptLanguage.setEnabled(session.targetLanguage != null);
        transcriptLanguage.setText(showTranslation ? "Bản dịch" : "Bản gốc");
        if (showTranslation && session.missingTranslations() > 0) {
            transcriptLanguage.setText("Bản dịch · thiếu " + session.missingTranslations() + " đoạn");
        }
        cueAdapter.notifyDataSetChanged();
    }

    private final class CueAdapter extends BaseAdapter {
        @Override public int getCount() { return session.cues.size(); }
        @Override public TranscriptCue getItem(int position) { return session.cues.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            TextView view = recycled == null ? new TextView(MainActivity.this) : (TextView) recycled;
            TranscriptCue cue = getItem(position);
            boolean active = position == activeRow;
            String line = showTranslation ? CueTimeline.Mode.TRANSLATION.text(cue) : cue.sourceText;
            if (showTranslation && !cue.hasTranslation()) {
                line = "Chưa có bản dịch\nBản gốc: " + cue.sourceText;
            }
            view.setTextSize(settings.textSize(12));
            view.setPadding(dp(8), dp(8), dp(8), dp(8));
            String timestamp = (active ? "Đang phát · " : "") + TranscriptParser.formatTime(cue.startMs);
            SpannableString content = new SpannableString(timestamp + "\n"
                    + (line.isEmpty() ? "Chưa có bản dịch" : line));
            content.setSpan(new ForegroundColorSpan(PlayerAppearance.color(MainActivity.this, android.R.attr.textColorSecondary)),
                    0, timestamp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(content);
            view.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            view.setBackgroundColor(active ? PlayerAppearance.color(MainActivity.this, R.attr.playerSelection) : Color.TRANSPARENT);
            return view;
        }
    }

    private SeekBar volumeField(LinearLayout form, String label, int value) {
        TextView volumeLabel = new TextView(this);
        volumeLabel.setTextSize(settings.textSize(14));
        volumeLabel.setText(label + ": " + value + "%");
        form.addView(volumeLabel);
        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(value);
        seek.setContentDescription(label);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                volumeLabel.setText(label + ": " + progress + "%");
                if (bar.getTag() instanceof Runnable) ((Runnable) bar.getTag()).run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        form.addView(seek);
        return seek;
    }

    private void addLabel(LinearLayout form, String label) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(settings.textSize(12));
        form.addView(text);
    }

    private void showStatus(String message) {
        main.removeCallbacks(hideStatus);
        status.setText(message);
        if (isTemporaryStatus(message)) main.postDelayed(hideStatus, STATUS_TIMEOUT_MS);
        refreshStatusVisibility();
    }

    private boolean isTemporaryStatus(String message) {
        return TELEGRAM_AUTHENTICATED.equals(message)
                || message.startsWith("Đã tạo ") || message.startsWith("Đã mở ")
                || message.startsWith("Đã nhập ") || message.startsWith("Đã hủy ")
                || (message.startsWith("Đã dịch offline ") && !message.contains(", lỗi "));
    }

    private void refreshStatusVisibility() {
        boolean busy = session.busy;
        boolean showMessage = status.length() > 0 && (!hasVideo || busy || controlsAreVisible());
        status.setVisibility(showMessage ? View.VISIBLE : View.GONE);
        dismissStatus.setVisibility(showMessage && !busy ? View.VISIBLE : View.GONE);
        feedback.setVisibility(showMessage || progress.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
    }

    @OptIn(markerClass = UnstableApi.class)
    private boolean controlsAreVisible() {
        return playerView.isControllerFullyVisible();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void showPlaybackControls() {
        if (!hasVideo || destroyed) return;
        playerView.showController();
        updateChromeVisibility();
        scheduleControlHide();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void hidePlaybackControls() {
        if (canHideControls()) playerView.hideController();
    }

    private void scheduleControlHide() {
        main.removeCallbacks(hideControls);
        if (controlsAreVisible() && canHideControls()) main.postDelayed(hideControls, CONTROLS_TIMEOUT_MS);
    }

    private boolean canHideControls() {
        AccessibilityManager accessibility = getSystemService(AccessibilityManager.class);
        return started && hasVideo && player.isPlaying() && hasWindowFocus()
                && settingsScreen == null && !touchingControls && !transcriptPanel.isShown()
                && (accessibility == null || !accessibility.isTouchExplorationEnabled());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchingControls = true;
                main.removeCallbacks(hideControls);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchingControls = false;
                scheduleControlHide();
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        touchingControls = false;
        if (playerView != null) scheduleControlHide();
        if (focused) refreshTelegramSettings();
    }

    private void updateVideoTitleFrame() {
        if (hasVideo == (title.getBackground() != null)) return;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) title.getLayoutParams();
        if (hasVideo) {
            PlayerAppearance.styleSurface(title,
                    PlayerAppearance.color(this, android.R.attr.colorBackgroundFloating), settings.raisedControls);
            title.setElevation(0);
            title.setPaddingRelative(dp(12), 0, dp(12), 0);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            params.height = dp(48);
            params.setMarginStart(dp(PlayerAppearance.CONTROL_GAP_DP / 2));
            params.setMarginEnd(dp(PlayerAppearance.CONTROL_GAP_DP / 2));
        } else {
            title.setBackground(null);
            title.setPaddingRelative(dp(8), 0, dp(4), 0);
            title.setEllipsize(TextUtils.TruncateAt.END);
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.setMarginStart(0);
            params.setMarginEnd(0);
        }
        title.setLayoutParams(params);
    }

    private void updateChromeVisibility() {
        updateVideoTitleFrame();
        boolean visible = controlsAreVisible();
        chrome.setVisibility(!hasVideo || visible ? View.VISIBLE : View.GONE);
        openSource.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
        android.graphics.drawable.Drawable menuBackground = hasVideo
                ? playbackMenuBackground : homeMenuBackground;
        if (optionsMenu.getBackground() != menuBackground) {
            title.setCompoundDrawablesRelative(hasVideo ? null : homeLogo, null, null, null);
            title.setTypeface(null, hasVideo ? Typeface.NORMAL : Typeface.BOLD);
            if (!hasVideo) title.setPaddingRelative(dp(20), 0, dp(4), 0);
            optionsMenu.setBackground(menuBackground);
            optionsMenu.setElevation(hasVideo && settings.raisedControls ? dp(3) : 0);
        }
        playerView.setVisibility(hasVideo ? View.VISIBLE : View.INVISIBLE);
        shortcuts.setVisibility(hasVideo && visible ? View.VISIBLE : View.GONE);
        actions.setVisibility(hasVideo && visible && !session.busy ? View.VISIBLE : View.GONE);
        refreshStatusVisibility();
    }

    private void updateControls(String message) {
        boolean hasCues = !session.cues.isEmpty();
        boolean translated = session.targetLanguage != null;
        boolean busy = session.busy;
        if (busy && android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("askedWorkNotifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedWorkNotifications", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
        empty.setVisibility(hasVideo ? View.GONE : View.VISIBLE);
        int missing = busy ? 0 : session.missingTranslations();
        primary.setEnabled(hasCues || !session.tracks.isEmpty());
        setLeadingIcon(primary, missing == 0 && translated ? R.drawable.ic_control_transcript
                : hasCues ? R.drawable.ic_control_translate : R.drawable.ic_control_generate);
        primary.setText(missing > 0 ? "Dịch lại " + missing + " đoạn"
                : translated ? "Xem transcript"
                : hasCues ? "Dịch sang " + languageName(settings.targetLanguage) : "Tạo transcript");
        cancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(session.progressTotal == 0);
        if (session.progressTotal > 0) {
            progress.setMax(session.progressTotal);
            progress.setProgress(session.progressCompleted);
        }
        if (message != null) {
            showStatus(message);
            if (!busy) savePlaybackSession();
        }
        updateChromeVisibility();
        transcript.setEnabled(hasCues);
        subtitlesButton.setEnabled(hasCues);
        speechButton.setEnabled(hasCues);
        if ((!hasVideo || !hasCues) && transcriptPanel.isShown()) setTranscriptVisible(false);
        if (!translated) {
            if (display == CueTimeline.Mode.TRANSLATION) display = CueTimeline.Mode.SOURCE;
        }
        if (!hasCues || (read == CueTimeline.Mode.TRANSLATION && !translated)) {
            read = CueTimeline.Mode.OFF;
        } else if (!busy) {
            String unavailable = speechUnavailable(read);
            if (!unavailable.isEmpty()) {
                read = CueTimeline.Mode.OFF;
                showStatus(unavailable);
            }
        }
        applyVideoVolume();
        updateModeIcon(subtitlesButton, R.drawable.ic_control_captions, "Phụ đề", "Phụ đề", display);
        updateModeIcon(speechButton, read == CueTimeline.Mode.OFF ? R.drawable.ic_control_mute
                : R.drawable.ic_control_voice, "Đọc", "Đọc thành tiếng", read);
        refreshTranscript();
        if (speech != null) sync();
        if (offerTranslation && !busy) {
            offerTranslation = false;
            if (translated && hasVideo && settings.targetLanguage.equals(session.targetLanguage)) {
                new PlayerAppearance.DialogBuilder(this, settings).setMessage("Bật phụ đề bản dịch?")
                        .setNegativeButton("Để sau", null)
                        .setPositiveButton("Bật", (dialog, which) -> {
                            display = CueTimeline.Mode.TRANSLATION;
                            updateControls(null);
                        }).show();
            }
        }
    }

    private void onSpeechError(String message) {
        read = CueTimeline.Mode.OFF;
        speech.stop();
        updateControls(null);
        showStatus(message);
    }

    private void applyVideoVolume() {
        player.setVolume(read == CueTimeline.Mode.OFF ? settings.sourceVolume / 100f : 0f);
    }

    private String speechUnavailable(CueTimeline.Mode mode) {
        if (mode == CueTimeline.Mode.OFF) return "";
        String language = mode == CueTimeline.Mode.SOURCE ? session.sourceLanguage : session.targetLanguage;
        if (speech == null || !speech.supports(language)) {
            return language == null ? "Chưa có ngôn ngữ để đọc thành tiếng."
                    : "Thiết bị chưa có giọng đọc cho " + languageName(language) + ".";
        }
        return "";
    }

    private void sync() {
        if (!hasVideo) return;
        TranscriptCue cue = session.timeline.at(player.getCurrentPosition());
        if (transcriptPanel.isShown()) {
            int current = cue == null ? -1 : session.cues.indexOf(cue);
            if (current != activeRow) {
                activeRow = current;
                cueAdapter.notifyDataSetChanged();
                if (follow && current >= 0) cueList.setSelection(current);
            }
        }
        String text = display.text(cue);
        if (!text.contentEquals(subtitle.getText())) subtitle.setText(text);
        subtitle.setVisibility(text.isEmpty() ? TextView.GONE : TextView.VISIBLE);

        boolean translatedSpeech = read == CueTimeline.Mode.TRANSLATION;
        if (read == CueTimeline.Mode.OFF || !player.isPlaying()
                || (translatedSpeech && (cue == null || !cue.hasTranslation()))) {
            speech.stop();
        } else {
            String language = translatedSpeech ? session.targetLanguage : session.sourceLanguage;
            speech.update(cue, read.text(cue), language, settings.dubbingVolume / 100f);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (hasVideo) updateControls(work.status());
        PlaybackException error = player.getPlayerError();
        if (hasVideo && error != null && error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) {
            recoverReclaimedDecoder(error);
        }
        main.post(tick);
    }

    @Override
    protected void onStop() {
        started = false;
        cancelDecoderRetry();
        main.removeCallbacks(tick);
        main.removeCallbacks(hideControls);
        player.pause();
        speech.stop();
        savePlaybackSession();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        cancelDecoderRetry();
        main.removeCallbacks(hideStatus);
        main.removeCallbacks(hideControls);
        player.release();
        work.detach(workObserver);
        sessionStore.close();
        videoDownloads.close();
        savedVideosDialog.close();
        home.close();
        speech.close();
        if (telegramSession instanceof TdJsonTelegramSessionClient)
            ((TdJsonTelegramSessionClient) telegramSession).closeTransport();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
