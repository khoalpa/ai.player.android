package com.aiplayer.android;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.widget.EditText;
import android.widget.ImageButton;
import java.io.File;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends ComponentActivity {
    private static final int OPEN_VIDEO = 1;
    private static final int OPEN_SUBTITLES = 2;
    private static final int EXPORT_TRANSCRIPT = 3;
    private String pendingTranscriptExport;
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
    private TelegramContentScreen telegramContent;

    private TelegramContentScreen telegramContent() {
        if (telegramContent == null) {
            telegramContent = new TelegramContentScreen(this, settings, videoStore,
                    () -> telegramSession, this::showTelegramAccount, videoDownloads::showTelegram);
        }
        return telegramContent;
    }
    private RetainedDownload retainedDownload;

    private static final class RetainedDownload {
        final VideoDownloadFlow flow;
        final TdJsonTelegramSessionClient telegram;

        RetainedDownload(VideoDownloadFlow flow, TdJsonTelegramSessionClient telegram) {
            this.flow = flow;
            this.telegram = telegram;
        }
    }
    private TelegramSessionClient telegramSession;
    private TelegramAuthorizationFlow.State lastTelegramState;
    private Uri currentVideo;
    private String currentVideoIdentity;
    private String currentDurationRevision;
    private long savedTranscriptVersion = -1;
    private long skippedTranslationVersion = -1;
    private boolean destroyed;
    private boolean started;
    private final java.util.function.Consumer<String> workObserver = message -> {
        if (started && !destroyed) updateControls(message);
        refreshModelsAfterWork();
        onStorageWorkChanged();
    };
    private boolean storageWorkBusy;
    private void onStorageWorkChanged() {
        if (storageWorkBusy && !session.busy && storagePage != null && settingsScreen == storagePage) {
            refreshVisibleStorage();
            work.models.refreshTranslations();
        }
        storageWorkBusy = session.busy;
    }

    private final Runnable modelObserver = () -> {
        renderModels();
        if (!work.models.pending()) refreshVisibleStorage();
    };
    private int openRequest;
    private CueSpeech speech;
    private ExoPlayer player;
    private AlertDialog webVideoPlayer;
    private boolean decoderRetryUsed;
    private Runnable decoderRetry;
    private PlayerView playerView;
    private LinearLayout playbackArea;
    private FrameLayout surface;
    private boolean touchingControls;
    private final Runnable hideControls = this::hidePlaybackControls;
    private TextView subtitle;
    private TextView status;
    private LinearLayout feedback, playbackFooter;
    private ImageButton rotateButton, minimalRotateButton;
    private Button dismissStatus;
    private Button manageStorageStatus;
    private TextView title;
    private Button openSource;
    private PlayerHomeView home;
    private Button optionsMenu;
    private android.graphics.drawable.Drawable homeLogo;
    private boolean restoringTelegram;
    private static final String TELEGRAM_AUTHENTICATED = "Đã xác thực Telegram qua TDLib";
    private SavedVideosDialog savedVideosDialog;
    private LinearLayout chrome, actions, shortcuts, transcriptPanel;
    private View empty;
    private ProgressBar progress;
    private TextView missingTranslationNotice;
    private Button skipMissingTranslation;
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
    private Runnable settingsParent;
    private final java.util.concurrent.ExecutorService storageIo =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private LinearLayout modelForm;
    private ModelTab modelTab;

    private enum ModelTab {
        RECOGNITION, TRANSLATION
    }
    private boolean modelSessionBusy;
    private boolean settingsDetail;
    private java.util.function.BooleanSupplier languageSettingsChanged;
    private java.util.function.BooleanSupplier audioSettingsChanged;
    private CueSpeech audioPreview;
    private AppSettings appearanceDraft;
    private LinearLayout appearancePreview;
    private TextView telegramSettingsStatus;
    private TextView telegramSettingsDescription;
    private ProgressBar telegramSettingsProgress;
    private Button telegramSettingsAction;
    private Button telegramSettingsLogout;
    private boolean telegramRequestPending;
    private boolean telegramLogoutPending;
    private String telegramConnectionError;
    private boolean lightTheme;
    private int orientation;

    @Override
    protected void onCreate(Bundle state) {
        settings = AppSettings.load(this);
        selectTheme();
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (handlePlayerBack()) return;
                setEnabled(false);
                try {
                    getOnBackPressedDispatcher().onBackPressed();
                } finally {
                    setEnabled(true);
                }
            }
        });
        if (state != null) pendingTranscriptExport = state.getString("pendingTranscriptExport");
        orientation = getResources().getConfiguration().orientation;
        sessionStore = new PlaybackSessionStore(this);
        work = VideoWork.obtain(this);
        session = work.session;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers).build();
        videoStore = new SavedVideoStore(this);
        RetainedDownload previous = (RetainedDownload) getLastCustomNonConfigurationInstance();
        videoDownloads = previous == null ? new VideoDownloadFlow(this, videoStore) : previous.flow;
        videoDownloads.attach(this, settings, () -> telegramSession,
                this::showTelegramAccount, this::openTelegramPost, this::openSavedVideo, this::onVideoDownloadChanged);
        savedVideosDialog = new SavedVideosDialog(this, videoStore, sessionStore, settings);
        installPlayerScreen();
        playerView.setPlayer(player);
        applySystemBars();
        speech = new CueSpeech(this, this::showStatus, this::applyVideoVolume);
        work.attach(workObserver);
        work.attachModels(modelObserver);
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
                if (state != Player.STATE_READY) return;
                if (currentVideo != null) {
                    sessionStore.saveDuration(currentVideo, currentDurationRevision, player.getDuration());
                }
                if (work.pendingRecentGrantFlags >= 0 && currentVideo != null) {
                    persistReadPermission(currentVideo, work.pendingRecentGrantFlags);
                    work.pendingRecentGrantFlags = -1;
                    if (!work.playbackHistoryCleared) {
                        sessionStore.recordOpened(currentVideo, session.videoName);
                    }
                    if (!hasVideo) updateResumeVideo();
                }
                if (DECODER_RECLAIMED_MESSAGE.contentEquals(status.getText())) showStatus("");
            }
        });
        updateControls(null);
        if (session.videoUri() != null && (!"file".equals(session.videoUri().getScheme())
                || isSavedFileAvailable(session.videoUri()))) {
            PlaybackSessionStore.Snapshot retained = new PlaybackSessionStore.Snapshot(session.videoUri(),
                    session.identity(), work.position, session.completedSource(), session.completedTarget(),
                    new ArrayList<>(session.completedCues()));
            retained.transcriptOrigin = session.transcriptOrigin();
            retained.name = session.videoName;
            openVideo(retained.uri, retained, false, 0);
            updateControls(work.status());
        } else updateResumeVideo();
        if (previous != null && previous.telegram != null) startTelegramSession(previous.telegram, true);
        else restoreTelegramSession();
        Bundle telegramState = state == null ? null : state.getBundle("telegramContent");
        if (telegramState != null) {
            telegramContent().restoreState(telegramState);
        } else if (state == null) {
            if (!handleTelegramViewIntent(getIntent())) handleSharedVideoIntent(getIntent());
        } else if (getIntent().getType() != null && getIntent().getType().startsWith("video/")) {
            handleSharedVideoIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleTelegramViewIntent(intent)) handleSharedVideoIntent(intent);
    }

    private boolean handleTelegramViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        Uri link = intent.getData();
        intent.setAction(null);
        if (link != null && ("https".equals(link.getScheme()) || "http".equals(link.getScheme()))) {
            telegramContent().openAddress(link.toString());
        }
        return true;
    }

    private void handleSharedVideoIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
                shared = intent.getClipData().getItemAt(0).getText();
            }
            intent.setAction(null);
            telegramContent().share(shared);
            return;
        }
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
                        refreshVisibleStorage();
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
        int controlInset = dp(PlayerAppearance.HORIZONTAL_INSET_DP - PlayerAppearance.CONTROL_GAP_DP / 2);
        chrome.setPaddingRelative(controlInset, 0, controlInset, 0);
        chrome.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(this);
        title.setText("AI Player");
        title.setTextColor(PlayerAppearance.color(this, android.R.attr.textColorPrimary));
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
        optionsMenu.setOnClickListener(anchor -> {
            PopupMenu popup = new PopupMenu(new ContextThemeWrapper(this, R.style.PlayerMenuTheme),
                    anchor, Gravity.END);
            int applicationGroup = hasVideo ? 1 : 0;
            int exitGroup = applicationGroup + 1;
            if (android.os.Build.VERSION.SDK_INT >= 28) popup.getMenu().setGroupDividerEnabled(true);
            if (!hasVideo) {
                popup.getMenu().add("Telegram").setOnMenuItemClickListener(item -> {
                    showTelegramSettings();
                    return true;
                });
            } else {
                popup.getMenu().add("Tùy chọn xử lý").setOnMenuItemClickListener(item -> {
                    showVideoProcessing();
                    return true;
                });
                popup.getMenu().add("Hướng màn hình").setOnMenuItemClickListener(item -> {
                    showPlaybackOrientation();
                    return true;
                });
                popup.getMenu().add("Nhập phụ đề (SRT/VTT)").setEnabled(!session.busy)
                        .setOnMenuItemClickListener(item -> { pick(OPEN_SUBTITLES, "*/*"); return true; });
                popup.getMenu().add("Đổi ngôn ngữ dịch")
                        .setEnabled(session.targetLanguage != null && !session.busy)
                        .setOnMenuItemClickListener(item -> { chooseTranslationLanguage(); return true; });
                popup.getMenu().add("Tạo lại transcript")
                        .setEnabled(!session.tracks.isEmpty() && !session.cues.isEmpty() && !session.busy)
                        .setOnMenuItemClickListener(item -> { chooseTrack(); return true; });
            }
            popup.getMenu().add(applicationGroup, 0, 0, "Cài đặt").setOnMenuItemClickListener(item -> {
                showSettings();
                return true;
            });
            popup.getMenu().add(applicationGroup, 0, 0, "Hướng dẫn").setOnMenuItemClickListener(item -> {
                showGuide(false);
                return true;
            });
            popup.getMenu().add(exitGroup, 0, 0, "Thoát").setOnMenuItemClickListener(item -> {
                requestExit();
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
                () -> telegramContent().input(), this::resumeRecentVideo, this::removeRecentHistory);
        empty = home;
        surface.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        subtitle = createSubtitleView();
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        surface.addView(subtitle, overlay);
        subtitle.setVisibility(View.GONE);
        rotateButton = addRotateButton(view, androidx.media3.ui.R.id.exo_fullscreen);
        minimalRotateButton = addRotateButton(view, androidx.media3.ui.R.id.exo_minimal_fullscreen);
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

        playbackFooter = column();
        playbackFooter.setPaddingRelative(controlInset, 0, controlInset, 0);
        root.addView(playbackFooter, new LinearLayout.LayoutParams(-1, -2));
        feedback = row(playbackFooter);
        feedback.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        feedback.addView(progress, new LinearLayout.LayoutParams(dp(64), dp(24)));
        status = new TextView(this);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setTextSize(settings.textSize(12));
        feedback.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        manageStorageStatus = iconButton(feedback, R.drawable.ic_control_info, "Dữ liệu và bộ nhớ",
                false, this::openStorageFromDownload);
        manageStorageStatus.setVisibility(View.GONE);
        dismissStatus = iconButton(feedback, R.drawable.ic_control_cancel, "Đóng thông báo",
                false, () -> showStatus(""));
        cancel = compactButton(feedback, "Hủy", () -> {
            offerTranslation = false;
            work.cancelWork("Đã hủy tác vụ.");
        });
        actions = column();
        playbackFooter.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        missingTranslationNotice = new TextView(this);
        missingTranslationNotice.setTextSize(settings.textSize(12));
        missingTranslationNotice.setCompoundDrawablePadding(dp(4));
        PlayerAppearance.StatusIcon.WARNING.apply(missingTranslationNotice);
        missingTranslationNotice.setPaddingRelative(dp(8), 0, dp(8), 0);
        missingTranslationNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        actions.addView(missingTranslationNotice);
        LinearLayout actionButtons = row(actions);
        primary = button(actionButtons, "Tạo transcript", this::primaryAction);
        stylePrimaryButton(primary);
        skipMissingTranslation = button(actionButtons, "Bỏ qua", () -> {
            skippedTranslationVersion = session.transcriptVersion();
            showStatus("");
            updateControls(null);
        });
        shortcuts = row(playbackFooter);
        ((LinearLayout.LayoutParams) shortcuts.getLayoutParams()).topMargin = dp(PlayerAppearance.GROUP_GAP_DP);
        subtitlesButton = iconButton(shortcuts, R.drawable.ic_control_captions, "Phụ đề", true, () -> showMode(false));
        speechButton = iconButton(shortcuts, R.drawable.ic_control_mute, "Phát âm", true, () -> showMode(true));
        transcript = iconButton(shortcuts, R.drawable.ic_control_transcript, "Mở transcript", true,
                this::toggleTranscript);
        transcript.setText("Transcript");
        buildTranscript(playbackArea);
        updatePlaybackLayout();
        return root;
    }

    private void styleMediaControls(View view) {
        int id = view.getId();
        if (view instanceof ImageButton
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
            PlayerAppearance.styleVideoControl(view, settings.raisedControls);
            if (view instanceof ImageButton) {
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
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean compactFooter = landscape && wide;
        playbackArea.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        surface.setLayoutParams(new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 2));
        transcriptPanel.setLayoutParams(new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1));
        playbackFooter.setOrientation(compactFooter ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        playbackFooter.setGravity(Gravity.CENTER_VERTICAL);
        feedback.setLayoutParams(new LinearLayout.LayoutParams(compactFooter ? 0 : -1, -2, compactFooter ? 1 : 0));
        actions.setLayoutParams(new LinearLayout.LayoutParams(compactFooter ? -2 : -1, -2));
        shortcuts.setLayoutParams(new LinearLayout.LayoutParams(compactFooter ? 0 : -1, -2, compactFooter ? 2 : 0));
        status.setSingleLine(landscape);
        status.setEllipsize(landscape ? TextUtils.TruncateAt.END : null);
        playerView.setControllerHideOnTouch(landscape || fullscreen);
        String description = landscape ? "Xoay dọc" : "Xoay ngang";
        rotateButton.setContentDescription(description);
        rotateButton.setTooltipText(description);
        minimalRotateButton.setContentDescription(description);
        minimalRotateButton.setTooltipText(description);
    }

    private ImageButton addRotateButton(PlayerView view, int fullscreenButtonId) {
        return addRotateButton(view, fullscreenButtonId, this::toggleVideoOrientation);
    }

    ImageButton addRotateButton(PlayerView view, int fullscreenButtonId, Runnable onRotate) {
        View fullscreenButton = view.findViewById(fullscreenButtonId);
        LinearLayout controls = (LinearLayout) fullscreenButton.getParent();
        ImageButton rotate = new ImageButton(this);
        rotate.setImageResource(R.drawable.ic_control_rotate);
        rotate.setContentDescription("Xoay ngang/dọc");
        rotate.setOnClickListener(button -> onRotate.run());
        controls.addView(rotate, controls.indexOfChild(fullscreenButton),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        return rotate;
    }

    void toggleVideoOrientation() {
        setRequestedOrientation(orientation == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void showPlaybackOrientation() {
        int[] modes = {ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE};
        int selected = 0;
        for (int i = 1; i < modes.length; i++) {
            if (modes[i] == getRequestedOrientation()) selected = i;
        }
        new PlayerAppearance.DialogBuilder(this, settings)
                .setTitle("Hướng màn hình")
                .setSingleChoiceItems(new String[]{"Theo hệ thống", "Dọc", "Ngang"}, selected,
                        (dialog, which) -> {
                            dialog.dismiss();
                            setRequestedOrientation(modes[which]);
                        })
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void toggleTranscript() {
        boolean visible = !transcriptPanel.isShown();
        setTranscriptVisible(visible);
        if (visible) {
            resumeFollowing();
            markVisibleResult();
        }
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
        int horizontalPadding = dp(settings.controlSpacing(8));
        int verticalPadding = dp(settings.controlSpacing(4));
        button.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
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
        PlayerAppearance.styleButton(button, PlayerAppearance.ButtonRole.UTILITY, settings);
        int horizontalPadding = dp(settings.controlSpacing(8));
        int verticalPadding = dp(settings.controlSpacing(4));
        button.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);

        if (weighted) {
            setControlIcon(button, icon, settings.textSize(20));
        } else {
            // Center the icon without reserving space for an empty line of text.
            android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
            drawable.setTintList(PlayerAppearance.controlIconTint(this));
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
    }

    private void describeControl(Button button, String description) {
        button.setContentDescription(description);
        button.setTooltipText(description);
    }

    private void updateModeIcon(Button button, int icon, String label, CueTimeline.Mode mode, String activeLabel) {
        setControlIcon(button, icon, settings.textSize(20));
        button.setSelected(button.isEnabled() && mode != CueTimeline.Mode.OFF);
        button.setText(label + " · " + (mode == CueTimeline.Mode.OFF || !button.isEnabled()
                ? "Tắt" : activeLabel));
        describeControl(button, label + ": " + (button.isEnabled() ? MODES.get(mode.ordinal())
                : "chưa có transcript"));
    }

    private void setControlIcon(Button button, int icon, int size) {
        android.graphics.drawable.Drawable drawable = button.getContext().getDrawable(icon).mutate();
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
        OpenSourceSheet sheet = new OpenSourceSheet(this, settings, "Mở video");
        addVideoSource(sheet, "Chọn tệp trên thiết bị", R.drawable.ic_control_open, this::openPicker);
        addVideoSource(sheet, "Tải video từ URL", R.drawable.ic_control_download, this::showDownloadDialog);
        addVideoSource(sheet, "Video đã lưu", R.drawable.ic_control_library, this::showSavedVideos);
        sheet.show();
    }

    private void addVideoSource(OpenSourceSheet sheet, String label, int icon, Runnable action) {
        sheet.addAction(label, icon, false, () -> {
            sheet.dismiss();
            action.run();
        });
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
        if (telegramRequestPending || telegramLogoutPending) return;
        if (isTelegramConnected()) {
            confirmTelegramLogout();
            return;
        }
        if (telegramSession != null && telegramSession.isNativeAvailable()) {
            if (telegramConnectionError == null && showTelegramCredential(telegramSession.authorizationState())) return;
            new PlayerAppearance.DialogBuilder(this, settings)
                    .positiveRole(PlayerAppearance.ButtonRole.CONFIRM_DELETE).setTitle("Tài khoản Telegram")
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
            boolean supportsTdlibAbi = android.os.Process.is64Bit()
                    && java.util.Arrays.asList(android.os.Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a");
            new PlayerAppearance.DialogBuilder(this, settings)
                    .setTitle(supportsTdlibAbi ? "Không tải được TDLib" : "Thiết bị chưa hỗ trợ đăng nhập Telegram")
                    .setMessage(supportsTdlibAbi
                            ? "Hãy cài bản AI Player có thư viện TDLib dành cho ARM64 để đăng nhập Telegram."
                            : "Bản AI Player này cần chạy ở chế độ ARM64 để đăng nhập Telegram. Bạn vẫn có thể xem bài công khai hoặc chọn Mở trong Telegram.")
                    .setNegativeButton("Đóng", null).show();
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
        new PlayerAppearance.DialogBuilder(this, settings)
                .positiveRole(PlayerAppearance.ButtonRole.SECONDARY).setTitle("Đăng xuất khỏi Telegram?")
                .setMessage("AI Player sẽ đăng xuất khỏi Telegram và xóa cấu hình API đã lưu.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Đăng xuất", (dialog, which) -> {
                    if (telegramSession != client || telegramRequestPending || telegramLogoutPending
                            || !isTelegramConnected()) return;
                    telegramLogoutPending = true;
                    refreshTelegramSettings();
                    client.logoutAndClearSession(error -> {
                        if (telegramSession != client) return;
                        telegramLogoutPending = false;
                        telegramRequestPending = false;
                        if (error == null) {
                            new TelegramApiCredentialStore(this).clear();
                            telegramSession = null;
                            lastTelegramState = null;
                            telegramConnectionError = null;
                        }
                        if (destroyed) return;
                        refreshTelegramSettings();
                        if (error != null) {
                            new PlayerAppearance.DialogBuilder(this, settings)
                                    .setMessage("Không thể đăng xuất. Vui lòng thử lại.")
                                    .setNegativeButton("Đóng", null).show();
                        }
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
                if (telegramContent != null) telegramContent.onReady();
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
                        .setNegativeButton("Đóng", null).show();
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

    void openTelegramBot(TelegramLink link) {
        if (!tryOpenTelegramApp(Uri.parse(link.botDeepLink()))) openTelegramPost(link.source.url);
    }

    private boolean tryOpenTelegramApp(Uri link) {
        Intent view = new Intent(Intent.ACTION_VIEW, link);
        view.addCategory(Intent.CATEGORY_BROWSABLE);
        for (String packageName : new String[]{"org.telegram.messenger", "org.telegram.messenger.web",
                "org.thunderdog.challegram"}) {
            try {
                startActivity(view.setPackage(packageName));
                return true;
            } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                // Try the next Telegram app before offering the HTTPS link.
            }
        }
        return false;
    }

    void openTelegramPost(java.net.URL post) {
        Uri link = Uri.parse(post.toExternalForm());
        if (tryOpenTelegramApp(link)) return;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, link);
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            Intent chooser = Intent.createChooser(view, "Mở liên kết Telegram");
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                    new android.content.ComponentName[]{getComponentName()});
            startActivity(chooser);
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, "Không có ứng dụng mở được liên kết Telegram.", Toast.LENGTH_LONG).show();
        }
    }
    private void showVideoDownloadError(String message) {
        AlertDialog.Builder builder = new PlayerAppearance.DialogBuilder(this, settings)
                .setTitle("Không thể tải video").setMessage(message).setNegativeButton("Đóng", null);
        if (StorageSpace.isLowSpace(message)) {
            builder.setPositiveButton("Dữ liệu và bộ nhớ", (dialog, which) -> openStorageFromDownload());
        }
        builder.show();
    }

    private void showSavedVideos() {
        showSavedVideos(false);
    }

    private void showSavedVideos(boolean bySize) {
        savedVideosDialog.show(file -> {
            if (bySize && settingsScreen != null) closeSettings();
            openSavedVideo(file);
        }, file -> {
            Uri uri = Uri.fromFile(file);
            boolean current = uri.equals(currentVideo);
            if (current && (hasVideo || session.busy)) return false;
            if (!file.delete()) return false;
            videoStore.forget(file);
            if (current) {
                player.clearMediaItems();
                currentVideo = null;
                work.pendingRecentGrantFlags = -1;
            }
            sessionStore.removeRecent(uri);
            work.discardResults(uri, false);
            updateResumeVideo();
            return true;
        }, currentVideo, work.playbackHistoryCleared ? 0 : player.getCurrentPosition(),
                hasVideo || session.busy, bySize,
                () -> { if (bySize) refreshVisibleStorage(); });
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
        String target = settings.targetLanguage;
        home.bindRecent(available, uri -> uri.equals(currentVideo)
                        ? player.getCurrentPosition() : sessionStore.savedPosition(uri),
                sessionStore::recentDuration,
                uri -> sessionStore.savedStatus(uri, target),
                uri -> uri.equals(session.videoUri()) ? session.recentStatus(target) : null);
        updateHomeTask();
    }

    private void onVideoDownloadChanged() {
        if (!videoDownloads.isDownloading()) refreshVisibleStorage();
        updateHomeTask();
    }

    private void updateHomeTask() {
        if (destroyed || hasVideo || home == null) return;
        home.refreshRecentStatuses();
        if (videoDownloads.isDownloading()) {
            home.bindTask(videoDownloads.activeLabel(), videoDownloads.activeTitle(),
                    "Xem tiến độ", videoDownloads::show);
            return;
        }
        if (session.runningTask != null && canResumeVideo(session.videoUri())) {
            home.bindTask(session.runningTask.runningLabel, session.videoName, "Xem trạng thái",
                    this::showRunningVideoTask);
            return;
        }
        for (VideoWork.Result result : work.unreadResults()) {
            if (canResumeVideo(result.video)) {
                home.bindTask(result.task.readyLabel, result.name, "Xem kết quả", () -> openTaskResult(result));
                return;
            }
        }
        home.bindTask(sessionStore.hasOpenedVideo() ? null
                : "Mở video để tạo transcript, dịch và bật phụ đề.", null, null, null);
    }

    private void showRunningVideoTask() {
        if (session.runningTask == null || !canResumeVideo(session.videoUri())) {
            updateHomeTask();
            return;
        }
        ++openRequest;
        showTaskVideo();
    }

    private void showTaskVideo() {
        hasVideo = true;
        title.setText(TextUtils.isEmpty(session.videoName) ? "Video" : session.videoName);
        updateControls(work.status());
        playerView.showController();
        sync();
    }

    private void openTaskResult(VideoWork.Result result) {
        if (!work.isResultUnread(result) || videoDownloads.isDownloading() || session.runningTask != null) return;
        if (!canResumeVideo(result.video)) {
            work.markResultViewed(result);
            updateHomeTask();
            showStatus("Video không còn khả dụng.");
            return;
        }
        int request = ++openRequest;
        savePlaybackSession();
        sessionStore.load(result.video, restored -> {
            if (destroyed || request != openRequest || !work.isResultUnread(result)
                    || videoDownloads.isDownloading() || session.runningTask != null
                    || result.video.equals(work.transcriptMutation())) return;
            if (restored == null || restored.identity == null || restored.cues.isEmpty()
                    || (result.identity != null && !result.identity.equals(restored.identity))
                    || !canResumeVideo(result.video)) {
                work.markResultViewed(result);
                updateHomeTask();
                showStatus("Không thể mở kết quả đã lưu.");
                return;
            }
            if (result.video.equals(currentVideo) && restored.identity.equals(currentVideoIdentity)) {
                showTaskVideo();
            } else {
                openVideo(result.video, restored);
            }
            showResultPanel(result);
        });
    }

    private void showResultPanel(VideoWork.Result result) {
        boolean translation = result.task == VideoSession.Task.TRANSLATION;
        if (session.cues.isEmpty() || (translation
                && (session.targetLanguage == null || session.missingTranslations() > 0))) {
            work.markResultViewed(result);
            showStatus("Không thể mở kết quả đã lưu.");
            return;
        }
        if (fullscreen) setFullscreen(false);
        showTranslation = translation;
        setTranscriptVisible(true);
        refreshTranscript();
        resumeFollowing();
    }

    private void resumeRecentVideo(Uri uri) {
        int requestId = ++openRequest;
        if (!canResumeVideo(uri)) {
            sessionStore.removeRecent(uri);
            updateResumeVideo();
            showStatus("Video không còn khả dụng.");
            return;
        }
        if (uri.equals(currentVideo)) {
            work.playbackHistoryCleared = false;
            if (work.pendingRecentGrantFlags < 0 && player.getPlayerError() == null) {
                sessionStore.recordOpened(uri, session.videoName);
            }
            hasVideo = true;
            title.setText(session.videoName);
            updateControls(work.status());
            playerView.showController();
            sync();
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
        openSavedVideo(file, -1);
    }

    void playWebVideo(WebVideoFinder.Candidate source, Runnable findAgain, Runnable openPage) {
        if (webVideoPlayer != null) webVideoPlayer.dismiss();
        player.pause();
        ExoPlayer stream = new ExoPlayer.Builder(this).build();
        PlayerView view = new PlayerView(this);
        int playerHeight = Math.max(120, Math.min(320, getResources().getConfiguration().screenHeightDp - 240));
        view.setPlayer(stream);
        styleMediaControls(view);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        TextView error = new TextView(this);
        LinearLayout content = WebVideoDialogLayout.body(this);
        WebVideoDialogLayout.header(content, settings, "Phát video trực tuyến", false, () -> {
            if (webVideoPlayer != null) webVideoPlayer.dismiss();
        });
        AlertDialog dialog = new PlayerAppearance.DialogBuilder(this, settings)
                .setView(WebVideoDialogLayout.scroll(content)).create();
        LinearLayout recovery = WebVideoDialogLayout.column(this);
        recovery.setVisibility(View.GONE);
        var openOnPage = WebVideoDialogLayout.button(this, settings, "Phát trên trang", true, () -> {
            dialog.dismiss();
            openPage.run();
        });
        var retrySource = WebVideoDialogLayout.button(this, settings, "Tìm lại nguồn", false, () -> {
            dialog.dismiss();
            findAgain.run();
        });
        Button quality = WebVideoDialogLayout.button(this, settings, "Chất lượng video", false, () -> {});
        quality.setVisibility(View.GONE);
        android.app.Dialog[] qualityDialog = new android.app.Dialog[1];
        quality.setOnClickListener(ignored -> {
            qualityDialog[0] = new androidx.media3.ui.TrackSelectionDialogBuilder(
                    this, "Chất lượng video", stream, C.TRACK_TYPE_VIDEO).build();
            qualityDialog[0].show();
        });
        var frame = new LinearLayout.LayoutParams(-1, WebVideoDialogLayout.dp(this, playerHeight));
        frame.topMargin = WebVideoDialogLayout.dp(this, 8);
        content.addView(view, frame);
        WebVideoDialogLayout.add(recovery, error, 0);
        WebVideoDialogLayout.add(recovery, openOnPage, 16);
        WebVideoDialogLayout.add(recovery, retrySource, 8);
        WebVideoDialogLayout.add(content, recovery, 16);
        WebVideoDialogLayout.add(content, quality, 8);
        stream.addListener(new Player.Listener() {
            @Override public void onTracksChanged(Tracks tracks) {
                int available = 0;
                for (Tracks.Group group : tracks.getGroups()) {
                    if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
                    for (int index = 0; index < group.length; index++) {
                        if (group.isTrackSupported(index)) available++;
                    }
                }
                quality.setVisibility(available > 1 ? View.VISIBLE : View.GONE);
            }

            @Override public void onPlayerError(PlaybackException failure) {
                error.setText(webPlaybackError(failure));
                recovery.setVisibility(View.VISIBLE);
            }
        });
        dialog.setOnDismissListener(ignored -> {
            if (qualityDialog[0] != null) qualityDialog[0].dismiss();
            view.setPlayer(null);
            stream.release();
            if (webVideoPlayer == dialog) webVideoPlayer = null;
        });
        webVideoPlayer = dialog;
        PlayerAppearance.showDialog(dialog, settings);
        stream.setMediaItem(new MediaItem.Builder().setUri(source.url.toExternalForm())
                .setMimeType(source.mime()).build());
        stream.prepare();
        stream.play();
    }

    private static String webPlaybackError(PlaybackException failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                int status = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return "Máy chủ trả lỗi khi phát luồng video. (HTTP " + status + ")\n"
                        + VideoUrlPolicy.downloadHttpError(status);
            }
        }
        return "Không phát được luồng video. (" + failure.getErrorCodeName() + ")";
    }

    void saveTelegramForPlayback(VideoSourceResolver.Source source,
                                 java.util.function.BiConsumer<File, String> completion) {
        videoDownloads.saveTelegramForPlayback(source, completion);
    }

    void openSavedVideo(File file, long position) {
        Uri uri = Uri.fromFile(file);
        int requestId = ++openRequest;
        player.pause();
        savePlaybackSession();
        sessionStore.load(uri, restored -> {
            if (destroyed || requestId != openRequest) return;
            openVideo(uri, restored);
            if (position >= 0 && uri.equals(currentVideo) && !uri.equals(work.transcriptMutation())) {
                player.seekTo(position);
            }
        });
    }

    private void openPicker() {
        pick(OPEN_VIDEO, "video/*");
    }

    private void setFullscreen(boolean enabled) {
        fullscreen = enabled;
        playerView.setFullscreenButtonState(enabled);
        playerView.setControllerHideOnTouch(enabled || orientation == Configuration.ORIENTATION_LANDSCAPE);
        applySystemBars();
        updateControls(null);
        showPlaybackControls();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        orientation = configuration.orientation;
        if (isLightTheme(settings.colorMode) != lightTheme) {
            refreshAppearance();
        } else {
            updatePlaybackLayout();
            if (appearanceDraft != null && appearanceDraft.colorMode == 2) {
                updateAppearancePreview(appearancePreview, appearanceDraft);
            }
        }
        updateChromeVisibility();
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

    private boolean handlePlayerBack() {
        if (work.transcriptMutation() != null) return true;
        if (settingsScreen != null) {
            if (settingsDetail) leaveSettingsDetail();
            else closeSettings();
        } else if (fullscreen) {
            setFullscreen(false);
        } else if (transcriptPanel.isShown()) {
            setTranscriptVisible(false);
        } else if (hasVideo) {
            ++openRequest;
            sessionStore.cancelLoad();
            player.pause();
            savePlaybackSession();
            hasVideo = false;
            subtitle.setText("");
            subtitle.setVisibility(View.GONE);
            if (getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
            title.setText(R.string.app_name);
            updateResumeVideo();
            updateControls("");
        } else {
            return false;
        }
        return true;
    }

    private void requestExit() {
        boolean downloading = videoDownloads.isDownloading();
        if (!session.busy && !downloading) {
            finishAndRemoveTask();
            return;
        }
        String message = downloading
                ? "Đang tải “" + videoDownloads.activeTitle() + "”. Thoát sẽ hủy tải video này."
                : "Tác vụ đang chạy. Bạn có muốn dừng và thoát?";
        if (downloading && session.busy) message += "\nTác vụ xử lý video cũng sẽ dừng.";
        new PlayerAppearance.DialogBuilder(this, settings)
                .positiveRole(PlayerAppearance.ButtonRole.SECONDARY).setTitle("Thoát ứng dụng")
                .setMessage(message)
                .setNegativeButton("Ở lại", null)
                .setPositiveButton("Dừng và thoát", (dialog, which) -> {
                    if (session.busy) work.cancelWork("Đã dừng tác vụ.");
                    finishAndRemoveTask();
                })
                .show();
    }

    private void pick(int request, String type) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(type);
        startActivityForResult(intent, request);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pendingTranscriptExport", pendingTranscriptExport);
        if (telegramContent != null) state.putBundle("telegramContent", telegramContent.saveState());
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_TRANSCRIPT) {
            finishTranscriptExport(result == RESULT_OK && data != null ? data.getData() : null);
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == OPEN_VIDEO) {
            int requestId = ++openRequest;
            player.pause();
            savePlaybackSession();
            sessionStore.load(uri, restored -> {
                if (destroyed || requestId != openRequest) return;
                savePlaybackSession();
                openVideo(uri, restored, true, data.getFlags());
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

    private void persistReadPermission(Uri uri, int flags) {
        if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Providers may grant read access only for the current session.
            }
        }
    }

    private void openVideo(Uri uri, PlaybackSessionStore.Snapshot restored) {
        openVideo(uri, restored, true, 0);
    }

    private void openVideo(Uri uri, PlaybackSessionStore.Snapshot restored, boolean resetSession,
                           int grantFlags) {
        if (uri.equals(work.transcriptMutation())) return;
        cancelDecoderRetry();
        decoderRetryUsed = false;
        if (DECODER_RECLAIMED_MESSAGE.contentEquals(status.getText())) showStatus("");
        speech.stop();
        hasVideo = true;
        savedTranscriptVersion = -1;
        setTranscriptVisible(false);
        currentVideo = uri;
        currentVideoIdentity = restored == null ? null : restored.identity;
        currentDurationRevision = restored == null ? null : restored.durationRevision;
        offerTranslation = false;
        player.setPlayWhenReady(false);
        player.setMediaItem(MediaItem.fromUri(uri), restored == null ? 0L : restored.position);
        if (resetSession) {
            work.playbackHistoryCleared = false;
            skippedTranslationVersion = -1;
            session.open(uri, settings.sourceLanguage, restored);
            work.pendingRecentGrantFlags = grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        }
        title.setText(TextUtils.isEmpty(session.videoName) ? "Video" : session.videoName);
        player.prepare();
        updateControls(null);
        playerView.showController();
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
        if (currentVideo == null || currentVideo.equals(work.transcriptMutation())) return;
        long version = session.transcriptVersion();
        long position = player.getCurrentPosition();
        work.position = position;
        if (savedTranscriptVersion == version) {
            if (!work.playbackHistoryCleared) {
                sessionStore.savePosition(currentVideo, currentVideoIdentity, position);
            }
        } else {
            sessionStore.save(currentVideo, currentVideoIdentity, position, session,
                    !work.playbackHistoryCleared);
            savedTranscriptVersion = version;
        }
    }

    private static final List<String> SOURCE_LANGUAGES = Arrays.asList("ja", "en", "vi", "ko", "zh");

    private static String languageName(String code) {
        if (VoskModelStore.largeModelKeys().contains(code)) {
            return languageName(code.substring(0, code.indexOf('-'))) + " · Vosk Large";
        }
        return Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag("vi"));
    }

    private Spinner selection(LinearLayout form, String label, List<String> options, int selected) {
        return selection(form, label, options, selected, null);
    }

    private Spinner selection(LinearLayout form, String label, List<String> options, int selected,
                              int[] swatches) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(settings.textSize(12));
        form.addView(caption);
        Spinner picker = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, recycled, parent);
                styleItem(item, position);
                return item;
            }

            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                TextView item = (TextView) super.getDropDownView(position, recycled, parent);
                styleItem(item, position);
                return item;
            }
            private void styleItem(TextView item, int position) {
                item.setTextSize(settings.textSize(16));
                if (swatches == null) return;
                android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
                dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dot.setColor(swatches[position]);
                dot.setStroke(dp(1), 0xff666666);
                dot.setBounds(0, 0, dp(14), dp(14));
                item.setCompoundDrawablesRelative(dot, null, null, null);
                item.setCompoundDrawablePadding(dp(8));
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
        form.setPadding(dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.CONTROL_GAP_DP), dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.CONTROL_GAP_DP));
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
            retryMissingTranslations();
            return;
        }
        if (session.targetLanguage == null) chooseTranslationLanguage();
    }

    private void retryMissingTranslations() {
        if (session.busy || session.missingTranslations() == 0) return;
        skippedTranslationVersion = -1;
        updateControls(null);
        showMissingTranslations(true);
    }

    private void showMissingTranslations(boolean openSingle) {
        List<TranscriptCue> missing = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (TranscriptCue cue : session.cues) {
            if (cue.hasTranslation()) continue;
            missing.add(cue);
            labels.add(TranscriptParser.formatTime(cue.startMs) + " · Chưa dịch\n" + cue.sourceText);
        }
        if (missing.isEmpty()) return;
        boolean canUseDeepL = "vi".equals(session.targetLanguage);
        if (openSingle && missing.size() == 1 && !canUseDeepL) {
            editCueTranslation(missing.get(0));
            return;
        }
        Uri video = currentVideo;
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Còn " + missing.size() + " đoạn chưa được dịch")
                .setItems(labels.toArray(new String[0]), (window, index) -> editCueTranslation(missing.get(index)))
                .setNegativeButton("Để sau", null)
                .setPositiveButton("Dịch lại", (window, which) ->
                        session.retryMissingTranslations(settings.sourceLanguage, () -> {
                            if (started && !destroyed && hasVideo && settingsScreen == null
                                    && java.util.Objects.equals(video, currentVideo)) {
                                showMissingTranslations(false);
                            }
                        }));
        if (canUseDeepL) {
            dialog.setNeutralButton("DeepL", (window, which) -> chooseTranslationLanguage(true, true));
        }
        showSheet(dialog);
    }

    private void editCueTranslation(TranscriptCue cue) {
        if (!session.canEditTranslation(cue)) return;
        LinearLayout form = form();
        List<String> codes = new ArrayList<>(SOURCE_LANGUAGES);
        codes.add(0, "auto");
        Spinner source = languages(form, "Ngôn ngữ nguồn", codes, settings.sourceLanguage);
        EditText original = new EditText(this);
        original.setHint("Lời gốc");
        original.setText(cue.sourceText);
        addLabel(form, "Lời gốc");
        form.addView(original);
        EditText translation = new EditText(this);
        translation.setHint("Nhập bản dịch");
        translation.setText(cue.targetText);
        addLabel(form, "Bản dịch");
        form.addView(translation);

        AlertDialog dialog = sheet("Xử lý đoạn · " + TranscriptParser.formatTime(cue.startMs), form)
                .setNegativeButton("Để sau", null).create();
        String action = session.translationModelUnavailable ? "Tải model và dịch" : "Dịch đoạn này";
        LinearLayout sourceActions = row(form);
        button(sourceActions, "Nghe lại", () -> {
            if (!session.canEditTranslation(cue)) return;
            read = CueTimeline.Mode.OFF;
            speech.stop();
            updateControls(null);
            player.seekTo(cue.startMs);
            player.play();
        });
        Button translate = button(sourceActions, action, () -> {
            if (!session.canEditTranslation(cue)) return;
            dialog.dismiss();
            session.repairTranslation(cue, original.getText().toString().trim(),
                    codes.get(source.getSelectedItemPosition()));
        });
        LinearLayout saveActions = row(form);
        Button useOriginal = button(saveActions, "Dùng lời gốc", () -> {
            String text = original.getText().toString();
            if (session.saveTranslation(cue, text, text, TranscriptCue.TranslationOrigin.ORIGINAL)) {
                dialog.dismiss();
            }
        });
        Button save = button(saveActions, "Lưu bản dịch", () -> {
            if (session.saveTranslation(cue, original.getText().toString(), translation.getText().toString(),
                    TranscriptCue.TranslationOrigin.MANUAL)) {
                dialog.dismiss();
            }
        });
        stylePrimaryButton(save);
        updateActionsForText(original, translate, useOriginal);
        updateActionsForText(translation, save);
        showSheet(dialog);
    }

    private void updateActionsForText(EditText field, Button... buttons) {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean enabled = !s.toString().trim().isEmpty();
                for (Button button : buttons) button.setEnabled(enabled);
            }
            @Override public void afterTextChanged(Editable text) {}
        };
        field.addTextChangedListener(watcher);
        watcher.onTextChanged(field.getText(), 0, 0, field.length());
    }

    private void chooseTranslationLanguage() {
        chooseTranslationLanguage(false);
    }

    private String deepLKey;

    private void chooseTranslationLanguage(boolean videoOnly) {
        chooseTranslationLanguage(videoOnly, false);
    }

    private void chooseTranslationLanguage(boolean videoOnly, boolean preferDeepL) {
        List<String> codes = targetLanguages();
        LinearLayout form = form();
        processingLabel(form, "Ngôn ngữ đích");
        Spinner target = languages(form, "Ngôn ngữ đích", codes,
                videoOnly && session.targetLanguage != null ? session.targetLanguage : settings.targetLanguage);
        processingLabel(form, "Bộ dịch");
        Spinner provider = selection(form, "Bộ dịch",
                java.util.Arrays.asList("ML Kit Offline", "DeepL · Cần mạng"), 0);
        LinearLayout deepLOptions = column();
        form.addView(deepLOptions);
        processingLabel(deepLOptions, "DeepL gửi văn bản cần dịch đến máy chủ DeepL để dịch sang tiếng Việt.");
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setSaveEnabled(false);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint("Khóa DeepL API Free hoặc Pro");
        key.setText(deepLKey);
        deepLOptions.addView(key);
        boolean changesTarget = session.targetLanguage != null && !"vi".equals(session.targetLanguage);
        android.widget.CheckBox replaceTarget = new android.widget.CheckBox(this);
        if (changesTarget) {
            replaceTarget.setText("Thay bản dịch hiện có bằng tiếng Việt, kể cả đoạn đã sửa tay.");
            deepLOptions.addView(replaceTarget);
        }
        deepLOptions.setVisibility(View.GONE);
        int[] offlineTarget = {target.getSelectedItemPosition()};
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean online = position == 1;
                deepLOptions.setVisibility(online ? View.VISIBLE : View.GONE);
                if (online && target.isEnabled()) {
                    offlineTarget[0] = target.getSelectedItemPosition();
                    target.setSelection(codes.indexOf("vi"));
                } else if (!online && !target.isEnabled()) {
                    target.setSelection(offlineTarget[0]);
                }
                target.setEnabled(!online && !preferDeepL);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
                deepLOptions.setVisibility(View.GONE);
            }
        });
        if (preferDeepL) provider.setSelection(1);
        if (session.targetLanguage != null) {
            processingLabel(form, "Dịch lại bằng ML Kit có thể thay thế bản dịch đã chỉnh sửa.");
        }
        AlertDialog dialog = sheet("Dịch transcript", form).setNegativeButton("Hủy", null)
                .setPositiveButton("Dịch", null).create();
        showSheet(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (session.busy || session.cues.isEmpty()) return;
            boolean useDeepL = provider.getSelectedItemPosition() == 1;
            String selected = codes.get(target.getSelectedItemPosition());
            if (useDeepL && !"vi".equals(selected)) {
                android.widget.Toast.makeText(this, "DeepL hiện hỗ trợ đích Tiếng Việt trong AI Player.",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            String keyValue = useDeepL ? key.getText().toString().trim() : null;
            if (useDeepL && (keyValue.isEmpty() || keyValue.matches(".*\\s+.*"))) {
                key.setError("Nhập khóa DeepL API hợp lệ.");
                return;
            }
            if (useDeepL && changesTarget && !replaceTarget.isChecked()) {
                replaceTarget.setError("Xác nhận đổi ngôn ngữ bản dịch.");
                return;
            }
            if (useDeepL) deepLKey = keyValue;
            if (!videoOnly) {
                settings.targetLanguage = selected;
                settings.save(this);
            }
            offerTranslation = true;
            dialog.dismiss();
            session.retranslate(selected, session.sourceLanguage, videoOnly && !preferDeepL && !useDeepL, keyValue);
        });
    }

    private void chooseTrack() {
        chooseTrack(false);
    }

    private void chooseTrack(boolean videoOnly) {
        List<VideoSession.AudioTrack> tracks = new ArrayList<>(session.tracks);
        if (tracks.isEmpty()) return;
        List<String> labels = new ArrayList<>();
        int playing = playingTrack(tracks);
        for (int i = 0; i < tracks.size(); i++) {
            labels.add(tracks.get(i).label + (i == playing ? " (đang phát)" : ""));
        }
        LinearLayout form = form();
        Spinner track = selection(form, "Track âm thanh", labels, Math.max(0, playing));
        Spinner language = languages(form, "Ngôn ngữ lời thoại", SOURCE_LANGUAGES,
                videoOnly && SOURCE_LANGUAGES.contains(session.sourceLanguage)
                        ? session.sourceLanguage : settings.sourceLanguage);
        LinearLayout modelOptions = column();
        form.addView(modelOptions);
        processingLabel(modelOptions, "Model cho lượt mới");
        Spinner model = selection(modelOptions, "Model cho lượt mới",
                java.util.Arrays.asList("Vosk Small (mặc định)", "Vosk Large"), 0);
        Runnable updateModels = () -> modelOptions.setVisibility(
                VoskModelStore.largeModelKey(SOURCE_LANGUAGES.get(language.getSelectedItemPosition())) != null
                        ? View.VISIBLE : View.GONE);
        language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateModels.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
                modelOptions.setVisibility(View.GONE);
            }
        });
        updateModels.run();
        if (!session.cues.isEmpty()) {
            processingLabel(form, transcriptOriginLabel());
            processingLabel(form, "Nhận dạng lại sẽ thay thế transcript và bản dịch hiện tại.");
        }
        String action = session.cues.isEmpty() ? "Tạo transcript" : "Nhận dạng lại";
        showSheet(sheet(action, form).setNegativeButton("Hủy", null)
                .setPositiveButton(action, (dialog, which) -> {
                    String selected = SOURCE_LANGUAGES.get(language.getSelectedItemPosition());
                    if (!videoOnly) {
                        settings.sourceLanguage = selected;
                        settings.save(this);
                    }
                    String large = VoskModelStore.largeModelKey(selected);
                    String recognitionModel = large != null && model.getSelectedItemPosition() == 1 ? large : selected;
                    session.generate(tracks.get(track.getSelectedItemPosition()), selected, recognitionModel);
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
        AppSettings draft = appearanceDraft;
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
        if (draft != null) showAppearance(draft);
        else if (settingsOpen) showSettings();
    }

    private void showAppearance() {
        AppSettings draft = new AppSettings();
        draft.colorMode = settings.colorMode;
        draft.raisedControls = settings.raisedControls;
        draft.fontSize = settings.fontSize;
        showAppearance(draft);
    }

    private void showAppearance(AppSettings draft) {
        LinearLayout form = form();
        settingsHeading(form, "Xem trước");
        LinearLayout preview = column();
        form.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout page = settingsPage("Giao diện", form, true);
        appearanceDraft = draft;
        appearancePreview = preview;

        LinearLayout footer = column();
        footer.setPadding(dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.GROUP_GAP_DP), dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.CONTROL_GAP_DP));
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        Button save = compactButton(footer, "Lưu", () -> {
            settings.colorMode = draft.colorMode;
            settings.raisedControls = draft.raisedControls;
            settings.fontSize = draft.fontSize;
            settings.save(this);
            refreshAppearance();
        });
        save.setGravity(Gravity.CENTER);
        stylePrimaryButton(save);
        Runnable update = () -> {
            updateAppearancePreview(preview, draft);
            save.setEnabled(hasAppearanceChanges());
        };
        appearanceChoices(form, "Chủ đề", new String[]{"Tối đen", "Sáng", "Theo hệ thống"},
                new int[]{R.drawable.ic_theme_dark, R.drawable.ic_theme_light, R.drawable.ic_theme_system},
                draft.colorMode, index -> { draft.colorMode = index; update.run(); });
        appearanceChoices(form, "Cỡ giao diện", new String[]{"Nhỏ", "Tiêu chuẩn", "Lớn"},
                null, draft.fontSize, index -> { draft.fontSize = index; update.run(); });
        appearanceChoices(form, "Kiểu nút", new String[]{"Phẳng", "Nổi nhẹ"},
                null, draft.raisedControls ? 1 : 0, index -> { draft.raisedControls = index == 1; update.run(); });
        update.run();
    }

    private void settingsHeading(LinearLayout form, String label) {
        TextView heading = new TextView(this);
        heading.setText(label);
        heading.setTextSize(settings.textSize(16));
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(0, dp(12), 0, dp(4));
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        form.addView(heading);
    }

    private void appearanceChoices(LinearLayout form, String label, String[] choices, int[] icons,
                                   int selection, java.util.function.IntConsumer onSelection) {
        settingsHeading(form, label);
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        group.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < choices.length; i++) {
            android.widget.RadioButton choice = new android.widget.RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(choices[i]);
            PlayerAppearance.styleButton(choice, PlayerAppearance.ButtonRole.UTILITY, settings);
            choice.setMinHeight(dp(48));
            choice.setPaddingRelative(dp(8), dp(4), dp(8), dp(4));
            if (icons != null) {
                choice.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, icons[i], 0);
                choice.setCompoundDrawablePadding(dp(8));
            }
            group.addView(choice, new android.widget.RadioGroup.LayoutParams(-1, -2));
        }
        group.check(group.getChildAt(selection).getId());
        group.setOnCheckedChangeListener((view, id) ->
                onSelection.accept(view.indexOfChild(view.findViewById(id))));
        form.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    private void updateAppearancePreview(LinearLayout preview, AppSettings draft) {
        android.view.ContextThemeWrapper context = new android.view.ContextThemeWrapper(this,
                isLightTheme(draft.colorMode) ? R.style.AppThemeLight : R.style.AppTheme);
        preview.removeAllViews();
        android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable();
        card.setColor(PlayerAppearance.color(context, android.R.attr.colorBackground));
        card.setCornerRadius(dp(12));
        card.setStroke(dp(1), PlayerAppearance.color(context, R.attr.playerSelection));
        preview.setBackground(card);
        int horizontalPadding = dp(draft.controlSpacing(12));
        int verticalPadding = dp(draft.controlSpacing(10));
        preview.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        TextView sampleTitle = new TextView(context);
        sampleTitle.setText("Video mẫu.mp4");
        sampleTitle.setTextSize(draft.textSize(16));
        preview.addView(sampleTitle);

        LinearLayout controls = row(preview);
        int[] icons = {R.drawable.ic_control_captions, R.drawable.ic_control_mute};
        String[] labels = {"Phụ đề", "Phát âm"};
        for (int i = 0; i < icons.length; i++) {
            Button sample = new Button(context);
            PlayerAppearance.styleButton(sample, false, draft);
            sample.setText(labels[i]);
            sample.setSelected(i == 0);
            sample.setAllCaps(false);
            sample.setClickable(false);
            sample.setFocusable(false);
            sample.setMinWidth(0);
            sample.setMinimumWidth(0);
            sample.setMinHeight(dp(48));
            sample.setMinimumHeight(dp(48));
            int sidePadding = dp(draft.controlSpacing(4));
            int topBottomPadding = dp(draft.controlSpacing(6));
            sample.setPadding(sidePadding, topBottomPadding, sidePadding, topBottomPadding);
            setControlIcon(sample, icons[i], draft.textSize(20));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            int gap = dp(draft.controlSpacing(4));
            params.setMargins(gap, dp(draft.controlSpacing(8)), gap, gap);
            controls.addView(sample, params);
        }
    }

    private boolean hasAppearanceChanges() {
        return appearanceDraft != null && (appearanceDraft.colorMode != settings.colorMode
                || appearanceDraft.raisedControls != settings.raisedControls
                || appearanceDraft.fontSize != settings.fontSize);
    }

    private void leaveSettingsDetail() {
        if (work.transcriptMutation() != null) return;
        if (settingsParent != null) {
            settingsParent.run();
            return;
        }
        boolean languageChanged = languageSettingsChanged != null && languageSettingsChanged.getAsBoolean();
        boolean audioChanged = audioSettingsChanged != null && audioSettingsChanged.getAsBoolean();
        if (!languageChanged && !audioChanged && !hasAppearanceChanges()) {
            showSettings();
            return;
        }
        new PlayerAppearance.DialogBuilder(this, settings)
                .setTitle(audioChanged ? "Bỏ thay đổi?" : languageChanged
                        ? "Bỏ thay đổi ngôn ngữ và phụ đề?" : "Bỏ thay đổi giao diện?")
                .setNegativeButton(languageChanged || audioChanged ? "Tiếp tục chỉnh sửa" : "Tiếp tục chỉnh", null)
                .setPositiveButton("Bỏ thay đổi", (dialog, which) -> showSettings())
                .show();
    }

    private void installPlayerScreen() {
        clearAudioSettings();
        screenHost = new FrameLayout(this);
        screenHost.addView(buildContent(), new FrameLayout.LayoutParams(-1, -1));
        settingsScreen = null;
        languageSettingsChanged = null;
        modelForm = null;
        telegramSettingsStatus = null;
        telegramSettingsDescription = null;
        telegramSettingsProgress = null;
        telegramSettingsAction = null;
        telegramSettingsLogout = null;
        setContentView(screenHost);
    }

    private void closeSettings() {
        clearAudioSettings();
        storagePage = null;
        measureStorage = null;
        screenHost.removeView(settingsScreen);
        settingsScreen = null;
        languageSettingsChanged = null;
        modelForm = null;
        telegramSettingsStatus = null;
        telegramSettingsDescription = null;
        telegramSettingsProgress = null;
        telegramSettingsAction = null;
        telegramSettingsLogout = null;
        screenHost.getChildAt(0).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        ((ViewGroup) screenHost.getChildAt(0)).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        applySystemBars();
        scheduleControlHide();
    }

    private LinearLayout settingsPage(String heading, LinearLayout form, boolean detail) {
        return settingsPage(heading, form, detail, true);
    }

    private LinearLayout settingsPage(String heading, LinearLayout form, boolean detail, boolean scrollContent) {
        clearAudioSettings();
        storagePage = null;
        measureStorage = null;
        if (settingsScreen != null) screenHost.removeView(settingsScreen);
        languageSettingsChanged = null;
        appearanceDraft = null;
        appearancePreview = null;
        modelForm = null;
        telegramSettingsStatus = null;
        telegramSettingsDescription = null;
        telegramSettingsProgress = null;
        telegramSettingsAction = null;
        telegramSettingsLogout = null;
        settingsDetail = detail;
        settingsParent = null;
        LinearLayout page = column();
        page.setBackgroundColor(PlayerAppearance.color(this, android.R.attr.colorBackground));
        page.setClickable(true);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        View back = PlayerAppearance.addPageHeader(page, settings, heading,
                detail ? this::leaveSettingsDetail : this::closeSettings);
        if (scrollContent) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(form);
            page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            page.addView(form, new LinearLayout.LayoutParams(-1, -2));
        }
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
        footer.setPadding(dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.GROUP_GAP_DP), dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.CONTROL_GAP_DP));
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

    private void showGuide(boolean modelsExpanded) {
        LinearLayout form = form();
        settingsPage("Hướng dẫn", form, false);
        compactButton(form, "Chính sách quyền riêng tư", () -> {
            Uri policy = Uri.parse("https://github.com/khoalpa/ai.player.android/blob/main/docs/privacy-policy.md");
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, policy));
            } catch (android.content.ActivityNotFoundException unavailable) {
                Toast.makeText(this, "Không tìm thấy trình duyệt để mở chính sách.", Toast.LENGTH_SHORT).show();
            }
        });
        guideSection(form, "Bắt đầu nhanh",
                "1. Chọn Mở video và chọn nguồn video.\n"
                        + "2. Chọn Tạo transcript, rồi chọn track âm thanh và ngôn ngữ lời thoại.\n"
                        + "3. Chọn Dịch sang ngôn ngữ đích và chờ xử lý.\n"
                        + "4. Mở Phụ đề, chọn Bản dịch rồi bấm Áp dụng.", !modelsExpanded);
        guideSection(form, "Mở và lưu video",
                "Mở video → Chọn tệp trên thiết bị để phát tệp có sẵn. Dùng Tải video từ URL để nhập liên kết.\n\n"
                        + "Trong Telegram, có thể chia sẻ tệp video sang AI Player. Với liên kết bài đăng, "
                        + "dán vào Tải video từ URL và đăng nhập Telegram khi ứng dụng yêu cầu.\n\n"
                        + "Tìm lại video đã nhập trong Video đã lưu.", false);
        guideSection(form, "Phụ đề và phát âm",
                "Vào Cài đặt → Ngôn ngữ và phụ đề để đổi ngôn ngữ mặc định, cỡ chữ và màu chữ phụ đề.\n\n"
                        + "Trên màn hình phát, mở Phụ đề để chọn văn bản hiển thị. "
                        + "Mở Phát âm, chọn nội dung và bấm Áp dụng để nghe giọng đọc.", false);
        LinearLayout models = guideSection(form, "Dịch ngoại tuyến",
                "Mở Model và giọng đọc → Dịch offline (ML Kit). Tải model cho ngôn ngữ nguồn và đích theo "
                        + "trạng thái hiển thị. Dùng Wi-Fi hoặc dữ liệu di động khi tải lần đầu. "
                        + "Sau khi tải đủ model, bạn có thể dịch ngoại tuyến.", modelsExpanded);
        compactButton(models, "Mở Model và giọng đọc", () -> {
            showModels(ModelTab.TRANSLATION, () -> showGuide(true));
        });
        guideSection(form, "Xử lý sự cố",
                "Không mở được video? Thử mở lại tệp từ thiết bị, hoặc kiểm tra kết nối và quyền truy cập liên kết.\n\n"
                        + "Không thấy phụ đề? Kiểm tra video đã có transcript hoặc phụ đề nhập, "
                        + "sau đó chọn nội dung hiển thị trong Phụ đề.\n\n"
                        + "Dịch 361/363 đoạn nghĩa là còn 2 đoạn chưa dịch. Mở lại video và chọn Dịch lại "
                        + "2 đoạn thiếu. Nếu vẫn còn thiếu và thấy nút Xử lý 2 đoạn thiếu, hãy mở nút đó "
                        + "để xem các lựa chọn xử lý.", false);
    }

    private LinearLayout guideSection(LinearLayout form, String heading, String instructions, boolean expanded) {
        LinearLayout content = column();
        Button header = compactButton(form, heading + (expanded ? "  ▾" : "  ▸"), null);
        header.setContentDescription(heading + (expanded ? ", đang mở" : ", đang thu gọn"));
        header.setOnClickListener(view -> {
            boolean opening = content.getVisibility() == View.GONE;
            content.setVisibility(opening ? View.VISIBLE : View.GONE);
            header.setText(heading + (opening ? "  ▾" : "  ▸"));
            header.setContentDescription(heading + (opening ? ", đang mở" : ", đang thu gọn"));
        });
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        if (android.os.Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
        content.setPadding(dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.CONTROL_GAP_DP), dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.GROUP_GAP_DP));
        TextView body = new TextView(this);
        body.setText(instructions);
        body.setTextSize(settings.textSize(14));
        content.addView(body);
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        form.addView(content, new LinearLayout.LayoutParams(-1, -2));
        return content;
    }

    private void showSettings() {
        LinearLayout form = form();
        settingsPage("Cài đặt", form, false);
        String color = Arrays.asList("Tối đen", "Sáng", "Theo hệ thống").get(settings.colorMode);
        String font = Arrays.asList("Nhỏ", "Tiêu chuẩn", "Lớn").get(settings.fontSize);
        settingsEntry(form, R.drawable.ic_control_appearance, "Giao diện",
                color + " · " + (settings.raisedControls ? "Nổi nhẹ" : "Phẳng") + " · " + font,
                this::showAppearance);
        settingsEntry(form, R.drawable.ic_control_captions, "Ngôn ngữ và phụ đề",
                languageName(settings.sourceLanguage) + " → " + languageName(settings.targetLanguage)
                        + " · Phụ đề " + settings.subtitleSize + " sp", this::showLanguageSettings);
        settingsEntry(form, R.drawable.ic_control_download, "Model và giọng đọc",
                "Nhận dạng · Dịch offline · Phát âm", this::showModels);
        Button storage = settingsEntry(form, R.drawable.ic_control_library, "Dữ liệu và bộ nhớ",
                "Đang tính dung lượng…", this::showStorage);
        loadStorage((usage, entries) -> {
            if (storage.isAttachedToWindow()) storage.setText(settingsEntryText("Dữ liệu và bộ nhớ",
                    "Đã đo " + storageSize(usage.total())));
        });
        settingsEntry(form, R.drawable.ic_control_voice, "Âm thanh",
                "Video " + settings.sourceVolume + "% · Giọng đọc " + settings.dubbingVolume + "%",
                this::showAudioSettings);
        AppBuildInfo build = new AppBuildInfo(this);
        settingsEntry(form, R.drawable.ic_control_info, "Thông tin ứng dụng", build.summary,
                () -> showBuildSettings(build));
    }

    private View storagePage;
    private Runnable measureStorage;
    private int storageMeasurement;

    private static final class StorageUsage {
        int videos;
        long videoBytes;
        long transcriptBytes;
        long modelBytes;
        long cacheBytes;
        long deviceTotal;
        long deviceFree;
        long measuredAt;

        long total() {
            return videoBytes + transcriptBytes + modelBytes + cacheBytes;
        }
    }

    private void loadStorage(java.util.function.BiConsumer<StorageUsage,
            List<PlaybackSessionStore.StoredTranscript>> ready) {
        sessionStore.inspect(entries -> {
            if (destroyed) return;
            storageIo.execute(() -> {
                StorageUsage usage = new StorageUsage();
                File[] videos = videoStore.list();
                usage.videos = videos.length;
                for (File file : videos) usage.videoBytes += file.length();
                for (PlaybackSessionStore.StoredTranscript entry : entries) usage.transcriptBytes += entry.bytes;
                usage.modelBytes = work.models.vosk.downloadedBytes();
                usage.cacheBytes = session.transcriptCacheBytes(false);
                try {
                    android.os.StatFs volume = new android.os.StatFs(getFilesDir().getAbsolutePath());
                    usage.deviceTotal = volume.getTotalBytes();
                    usage.deviceFree = Math.min(usage.deviceTotal, volume.getAvailableBytes());
                } catch (IllegalArgumentException | SecurityException ignored) {
                    // The device volume can be unavailable independently of the app's measurements.
                }
                usage.measuredAt = System.currentTimeMillis();
                runOnUiThread(() -> { if (!destroyed) ready.accept(usage, entries); });
            });
        });
    }

    private String storageSize(long bytes) {
        return android.text.format.Formatter.formatFileSize(this, bytes);
    }

    void openStorageFromDownload() {
        boolean fromPlayer = settingsScreen == null;
        showStorage();
        if (fromPlayer) settingsParent = this::closeSettings;
    }

    private void refreshVisibleStorage() {
        if (!destroyed && storagePage != null && settingsScreen == storagePage) measureStorage.run();
    }

    private void showStorage() {
        LinearLayout form = form();
        View page = settingsPage("Dữ liệu và bộ nhớ", form, true);
        storagePage = page;
        settingsEntry(form, R.drawable.ic_control_library, "Xóa lịch sử hoạt động",
                "Video gần đây · Liên kết Telegram đã mở", this::showClearActivityHistory);
        LinearLayout content = column();
        form.addView(content, new LinearLayout.LayoutParams(-1, -2));
        TextView measurement = new TextView(this);
        measurement.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(measurement);
        Button refresh = compactButton(form, "Đo lại", () -> {
            refreshVisibleStorage();
            work.models.refreshTranslations();
        });
        measureStorage = () -> {
            int request = ++storageMeasurement;
            refresh.setEnabled(false);
            measurement.setText("Đang đo…");
            loadStorage((usage, entries) -> {
                if (settingsScreen != page || request != storageMeasurement) return;
                content.removeAllViews();
                renderStorage(content, usage, entries.size(), page);
                String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(usage.measuredAt));
                measurement.setText("Cập nhật lúc " + time);
                refresh.setEnabled(true);
            });
        };
        refreshVisibleStorage();
        work.models.refreshTranslations();
    }

    private void showClearActivityHistory() {
        String[] categories = {"Video gần đây và vị trí phát đã lưu", "Liên kết Telegram đã mở"};
        boolean[] selected = new boolean[categories.length];
        AlertDialog dialog = new PlayerAppearance.DialogBuilder(this, settings)
                .setTitle("Xóa lịch sử hoạt động")
                .setMultiChoiceItems(categories, selected, (ignored, which, checked) -> {
                    selected[which] = checked;
                    AlertDialog choices = (AlertDialog) ignored;
                    choices.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(selected[0] || selected[1]);
                })
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Tiếp tục", (ignored, which) -> {
                    String scope = selected[0] && selected[1]
                            ? categories[0] + "\n" + categories[1]
                            : categories[selected[0] ? 0 : 1];
                    new PlayerAppearance.DialogBuilder(this, settings)
                            .setTitle("Xóa lịch sử đã chọn?")
                            .setMessage(scope + "\n\nVideo đã lưu, transcript, bản dịch và phiên Telegram vẫn được giữ.")
                            .setNegativeButton("Hủy", null)
                            .setPositiveButton("Xóa lịch sử", (confirm, action) -> clearActivityHistory(selected))
                            .show();
                })
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    private void clearActivityHistory(boolean[] selected) {
        if (selected[1]) telegramContent().clearHistory();
        if (selected[0]) {
            removeRecentHistory(null);
        } else {
            storageMessage("Đã xóa lịch sử đã chọn.");
        }
    }

    private void removeRecentHistory(Uri uri) {
        if (currentVideo != null && (uri == null || uri.equals(currentVideo))) {
            work.playbackHistoryCleared = true;
        }
        sessionStore.clearHistory(uri, success -> {
            if (destroyed) return;
            storageMessage(success
                    ? (uri == null ? "Đã xóa lịch sử đã chọn." : "Đã xóa video khỏi lịch sử.")
                    : "Chưa xóa được một số vị trí phát đã lưu. Hãy thử lại.");
        });
        updateResumeVideo();
    }

    private void renderStorage(LinearLayout form, StorageUsage usage, int transcriptCount, View page) {
        settingsHeading(form, "Bộ nhớ thiết bị");
        if (usage.deviceTotal > 0) {
            long used = usage.deviceTotal - usage.deviceFree;
            settingsHeading(form, "Còn trống " + storageSize(usage.deviceFree));
            TextView deviceDetails = new TextView(this);
            deviceDetails.setText("Đã dùng " + storageSize(used) + " / " + storageSize(usage.deviceTotal));
            form.addView(deviceDetails);
            storageBar(form, usage.deviceTotal, new long[]{used, usage.deviceFree},
                    new int[]{0xff527ff2, 0xff6c727f});
        } else {
            settingsHeading(form, "Chưa đo được dung lượng");
        }
        settingsEntry(form, R.drawable.ic_control_info, "Mở cài đặt bộ nhớ", "Android", () -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
            } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                storageMessage("Không mở được cài đặt bộ nhớ Android.");
            }
        });

        settingsHeading(form, "Dung lượng đã đo · " + storageSize(usage.total()));
        OfflineModels models = work.models;
        boolean noTranslationModels = models.translationsLoaded && !models.translationQueryFailed
                && !OfflineModels.pending(models.translation) && models.downloadedTranslations.isEmpty();
        if (!noTranslationModels) {
            TextView scope = new TextView(this);
            scope.setText("Chưa gồm model dịch offline");
            form.addView(scope);
        }
        int[] colors = {0xff527ff2, 0xff37bf97, 0xffa586ef, 0xffe5ad57};
        storageBar(form, usage.total(),
                new long[]{usage.videoBytes, usage.transcriptBytes, usage.modelBytes, usage.cacheBytes}, colors);
        storageEntry(form, R.drawable.ic_control_library, "Video đã lưu", usage.videos + " video · ",
                usage.videoBytes, usage.total(), colors[0], () -> showSavedVideos(true));
        storageEntry(form, R.drawable.ic_control_captions, "Transcript và bản dịch", transcriptCount + " video · ",
                usage.transcriptBytes, usage.total(), colors[1], this::showTranscriptStorage);
        storageEntry(form, R.drawable.ic_control_download, "Model nhận dạng", "", usage.modelBytes,
                usage.total(), colors[2], () -> showModels(ModelTab.RECOGNITION, this::showStorage));
        settingsEntry(form, R.drawable.ic_control_download, "Model dịch offline",
                noTranslationModels ? storageSize(0) : "Chưa đo được dung lượng",
                () -> showModels(ModelTab.TRANSLATION, this::showStorage));
        Button clear = storageEntry(form, R.drawable.ic_control_info, "Dọn bộ nhớ đệm", "",
                usage.cacheBytes, usage.total(), colors[3], () ->
                        new PlayerAppearance.DialogBuilder(this, settings)
                                .positiveRole(PlayerAppearance.ButtonRole.CONFIRM_DELETE)
                                .setTitle("Dọn bộ nhớ đệm?")
                                .setMessage("Dung lượng dự kiến thu hồi · " + storageSize(usage.cacheBytes))
                                .setNegativeButton("Hủy", null)
                                .setPositiveButton("Dọn", (dialog, which) -> clearStorageCache(page))
                                .show());
        clear.setEnabled(usage.cacheBytes > 0);
    }

    private void storageBar(LinearLayout form, long total, long[] sizes, int[] colors) {
        if (total <= 0) return;
        LinearLayout bar = row(form);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(8)));
        for (int index = 0; index < sizes.length; index++) {
            if (sizes[index] == 0) continue;
            View segment = new View(this);
            segment.setBackgroundColor(colors[index]);
            bar.addView(segment, new LinearLayout.LayoutParams(0, -1, sizes[index] / (float) total));
        }
    }

    private Button storageEntry(LinearLayout form, int icon, String title, String prefix,
                                long bytes, long total, int color, Runnable open) {
        double share = total == 0 ? 0 : bytes / (double) total;
        java.text.NumberFormat percent = java.text.NumberFormat.getPercentInstance();
        percent.setMaximumFractionDigits(1);
        String ratio = share > 0 && share < 0.001 ? "<" + percent.format(0.001) : percent.format(share);
        Button button = settingsEntry(form, icon, title,
                prefix + storageSize(bytes) + " · " + ratio, open);
        button.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
        return button;
    }

    private void clearStorageCache(View page) {
        if (session.busy) {
            storageMessage("Hãy đợi tác vụ đang chạy hoàn tất.");
            return;
        }
        storageIo.execute(() -> {
            long freed = session.transcriptCacheBytes(true);
            runOnUiThread(() -> {
                if (destroyed) return;
                storageMessage(freed > 0 ? "Đã giải phóng " + storageSize(freed)
                        : "Không giải phóng thêm dung lượng.");
                if (settingsScreen == page) refreshVisibleStorage();
            });
        });
    }

    private void showTranscriptStorage() {
        LinearLayout form = form();
        View page = settingsPage("Transcript và bản dịch", form, true);
        settingsParent = this::showStorage;
        settingsHeading(form, "Đang đọc dữ liệu…");
        sessionStore.inspect(entries -> {
            if (destroyed || settingsScreen != page) return;
            form.removeAllViews();
            settingsHeading(form, entries.isEmpty() ? "Chưa có transcript đã lưu." : "Lớn nhất trước");
            for (PlaybackSessionStore.StoredTranscript entry : entries) {
                addStoredTranscriptRow(form, entry, page);
            }
            if (entries.stream().anyMatch(entry -> !canDeleteTranscript(entry.snapshot.uri))) {
                settingsHeading(form, "Hãy về màn khởi động và đợi tác vụ đang chạy hoàn tất để xóa dữ liệu video hiện tại.");
            }
        });
    }

    private void addStoredTranscriptRow(LinearLayout form, PlaybackSessionStore.StoredTranscript entry, View page) {
        LinearLayout line = row(form);
        line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLayout = (LinearLayout.LayoutParams) line.getLayoutParams();
        rowLayout.topMargin = form.getChildCount() > 1 ? dp(8) : 0;
        line.setLayoutParams(rowLayout);

        LinearLayout item = row(line);
        item.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setMinimumHeight(dp(64));
        PlayerAppearance.styleControlSurface(item, settings.raisedControls);
        item.setPaddingRelative(dp(12), dp(8), dp(12), dp(8));
        item.setOnClickListener(v -> showStoredTranscript(entry.snapshot));
        item.setFocusable(true);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.ic_control_captions);
        icon.setImageTintList(PlayerAppearance.controlIconTint(this));
        item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout labels = column();
        LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(0, -2, 1);
        labelLayout.setMarginStart(dp(8));
        item.addView(labels, labelLayout);
        TextView name = new TextView(this);
        name.setText(entry.snapshot.name);
        name.setTextSize(settings.textSize(14));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        labels.addView(name, new LinearLayout.LayoutParams(-1, -2));

        String summaryText = storageSize(entry.bytes) + (entry.translated ? " · Có bản dịch" : " · Có transcript");
        TextView summary = new TextView(this);
        summary.setText(summaryText);
        summary.setTextSize(settings.textSize(13));
        summary.setTextColor(PlayerAppearance.color(this, android.R.attr.textColorSecondary));
        summary.setSingleLine(true);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        item.setContentDescription(entry.snapshot.name + "\n" + summaryText);
        item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });

        Button more = compactButton(line, "⋮", () -> showTranscriptActions(entry, page));
        PlayerAppearance.styleButton(more, PlayerAppearance.ButtonRole.UTILITY, settings);
        LinearLayout.LayoutParams moreLayout = new LinearLayout.LayoutParams(dp(48), -2);
        moreLayout.setMarginStart(dp(8));
        more.setLayoutParams(moreLayout);
        more.setContentDescription("Thao tác dữ liệu của " + entry.snapshot.name);

    }

    private void showStoredTranscript(PlaybackSessionStore.Snapshot snapshot) {
        LinearLayout controls = form();
        LinearLayout page = settingsPage("Transcript và bản dịch", controls, true, false);
        settingsParent = this::showTranscriptStorage;
        settingsHeading(controls, snapshot.name);
        TextView summary = new TextView(this);
        controls.addView(summary);
        Spinner mode = new Spinner(this);
        String[] modes = {"Song ngữ", "Transcript", "Bản dịch"};
        ArrayAdapter<String> modesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        modesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(modesAdapter);
        mode.setContentDescription("Chế độ xem phụ đề");
        controls.addView(mode);
        android.widget.CheckBox missing = new android.widget.CheckBox(this);
        missing.setText("Đoạn thiếu bản dịch");
        missing.setButtonTintList(PlayerAppearance.controlIconTint(this));
        controls.addView(missing);
        ListView list = new ListView(this);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView empty = new TextView(this);
        empty.setPadding(dp(12), dp(12), dp(12), dp(12));
        empty.setText("Không còn đoạn thiếu bản dịch.");
        page.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setEmptyView(empty);
        StoredCueAdapter adapter = new StoredCueAdapter(snapshot, summary, mode, missing);
        list.setAdapter(adapter);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                adapter.notifyDataSetChanged();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        missing.setOnCheckedChangeListener((button, checked) -> adapter.refresh());
        adapter.refresh();
    }

    private final class StoredCueAdapter extends BaseAdapter {
        private PlaybackSessionStore.Snapshot snapshot;
        private final TextView summary;
        private final Spinner mode;
        private final android.widget.CheckBox missing;
        private final List<Integer> indexes = new ArrayList<>();

        StoredCueAdapter(PlaybackSessionStore.Snapshot snapshot, TextView summary, Spinner mode,
                         android.widget.CheckBox missing) {
            this.snapshot = snapshot;
            this.summary = summary;
            this.mode = mode;
            this.missing = missing;
        }

        void refresh() {
            indexes.clear();
            int translated = 0;
            for (int i = 0; i < snapshot.cues.size(); i++) {
                boolean hasTranslation = snapshot.cues.get(i).hasTranslation();
                if (hasTranslation) translated++;
                if (!missing.isChecked() || !hasTranslation) indexes.add(i);
            }
            summary.setText(translated + "/" + snapshot.cues.size() + " đoạn đã dịch"
                    + (snapshot.target == null ? "" : " · " + languageName(snapshot.target)));
            notifyDataSetChanged();
        }

        @Override public int getCount() { return indexes.size(); }
        @Override public TranscriptCue getItem(int position) { return snapshot.cues.get(indexes.get(position)); }
        @Override public long getItemId(int position) { return indexes.get(position); }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout line = (LinearLayout) recycled;
            if (line == null) {
                line = new LinearLayout(MainActivity.this);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(dp(12), dp(8), dp(12), dp(8));
                LinearLayout body = column();
                for (int i = 0; i < 3; i++) {
                    TextView preview = new TextView(MainActivity.this);
                    preview.setTextSize(settings.textSize(14));
                    preview.setSingleLine(true);
                    preview.setEllipsize(TextUtils.TruncateAt.END);
                    body.addView(preview, new LinearLayout.LayoutParams(-1, -2));
                }
                line.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
                compactButton(line, "Sửa", null);
            }
            TranscriptCue cue = getItem(position);
            String time = TranscriptParser.formatTime(cue.startMs);
            LinearLayout body = (LinearLayout) line.getChildAt(0);
            TextView timestamp = (TextView) body.getChildAt(0);
            TextView original = (TextView) body.getChildAt(1);
            TextView translation = (TextView) body.getChildAt(2);
            int selectedMode = mode.getSelectedItemPosition();
            timestamp.setText(cue.translationNeedsReview ? time + " · Cần xem lại" : time);
            original.setText(cue.sourceText);
            original.setVisibility(selectedMode == 2 ? View.GONE : View.VISIBLE);
            translation.setText(cue.hasTranslation() ? cue.targetText : "Chưa có bản dịch");
            translation.setVisibility(selectedMode == 1 ? View.GONE : View.VISIBLE);
            Button edit = (Button) line.getChildAt(1);
            int index = indexes.get(position);
            edit.setContentDescription("Sửa đoạn " + time);
            edit.setOnClickListener(v -> editStoredCue(this, index));
            return line;
        }
    }

    private void editStoredCue(StoredCueAdapter adapter, int index) {
        PlaybackSessionStore.Snapshot snapshot = adapter.snapshot;
        TranscriptCue cue = snapshot.cues.get(index);
        LinearLayout fields = form();
        EditText original = new EditText(this);
        addLabel(fields, "Lời gốc");
        original.setHint("Lời gốc");
        original.setText(cue.sourceText);
        fields.addView(original);
        EditText translation = new EditText(this);
        String target = snapshot.target == null ? settings.targetLanguage : snapshot.target;
        addLabel(fields, "Bản dịch · " + languageName(target));
        translation.setHint("Nhập bản dịch");
        String previousTranslation = cue.targetText == null ? "" : cue.targetText;
        translation.setText(previousTranslation);
        fields.addView(translation);

        AlertDialog dialog = sheet("Sửa đoạn · " + TranscriptParser.formatTime(cue.startMs), fields)
                .setNegativeButton("Hủy", null).setPositiveButton("Lưu", null).create();
        boolean[] usingOriginal = {false};
        if (!cue.hasTranslation()) {
            compactButton(fields, "Dùng lời gốc", () -> {
                translation.setText(original.getText().toString().trim());
                usingOriginal[0] = true;
            });
        }
        dialog.setCancelable(false);
        showSheet(dialog);
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Runnable commit = () -> {
            if (!save.isEnabled()) return;
            String source = original.getText().toString();
            String translated = translation.getText().toString();
            if (source.trim().isEmpty()) {
                original.setError("Nhập lời gốc.");
                return;
            }
            boolean sourceChanged = !source.equals(cue.sourceText);
            boolean translationChanged = !translated.equals(previousTranslation);
            if (!sourceChanged && !translationChanged) {
                dialog.dismiss();
                return;
            }
            if (work.transcriptMutation() != null || (snapshot.uri.equals(session.videoUri()) && session.busy)) {
                storageMessage("Dữ liệu đang được sử dụng. Hãy thử lại sau.");
                return;
            }
            TranscriptCue edited = cue.copy();
            if (sourceChanged) edited.sourceText = source.trim();
            if (translationChanged) {
                String text = translated.trim();
                edited.setTranslation(text, text.isEmpty() ? TranscriptCue.TranslationOrigin.AUTOMATIC
                        : usingOriginal[0] && text.equals(edited.sourceText)
                                ? TranscriptCue.TranslationOrigin.ORIGINAL : TranscriptCue.TranslationOrigin.MANUAL);
            } else if (sourceChanged) {
                edited.syncTranslationAfterSourceEdit();
            }
            save.setEnabled(false);
            work.editTranscriptCue(snapshot, index, edited, target, updated -> {
                if (destroyed) return;
                save.setEnabled(true);
                if (updated == null) {
                    storageMessage("Không thể lưu phụ đề. Dữ liệu có thể đã thay đổi.");
                    return;
                }
                adapter.snapshot = updated;
                adapter.refresh();
                updateResumeVideo();
                dialog.dismiss();
                storageMessage("Đã lưu phụ đề.");
            });
        };
        Runnable close = () -> {
            if (!save.isEnabled()) return;
            if (cue.sourceText.contentEquals(original.getText())
                    && previousTranslation.contentEquals(translation.getText())) {
                dialog.dismiss();
                return;
            }
            new PlayerAppearance.DialogBuilder(this, settings)
                    .setTitle("Lưu thay đổi?")
                    .setPositiveButton("Lưu thay đổi", (confirm, which) -> commit.run())
                    .setNegativeButton("Bỏ thay đổi", (confirm, which) -> dialog.dismiss())
                    .setNeutralButton("Tiếp tục sửa", null)
                    .show();
        };
        save.setOnClickListener(v -> commit.run());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> close.run());
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, close::run);
        } else {
            registerLegacyEditorBack(dialog, close);
        }
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    private static void registerLegacyEditorBack(AlertDialog dialog, Runnable close) {
        dialog.setOnKeyListener((window, key, event) -> {
            if (key != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) close.run();
            return true;
        });
    }

    private boolean canDeleteTranscript(Uri uri) {
        return work.transcriptMutation() == null
                && (!uri.equals(session.videoUri()) || (!hasVideo && !session.busy));
    }

    private void showTranscriptActions(PlaybackSessionStore.StoredTranscript entry, View page) {
        List<CharSequence> labels = new ArrayList<>();
        labels.add("Xuất tệp");
        labels.add("Chia sẻ");
        boolean deletionAvailable = canDeleteTranscript(entry.snapshot.uri);
        String removeAll = entry.translated ? "Xóa transcript và bản dịch" : "Xóa transcript";
        if (deletionAvailable) {
            if (entry.translated) labels.add(PlayerAppearance.dangerLabel(this, "Xóa bản dịch"));
            labels.add(PlayerAppearance.dangerLabel(this, removeAll));
        }
        new PlayerAppearance.DialogBuilder(this, settings).setTitle(entry.snapshot.name)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, index) -> {
                    if (index < 2) {
                        sessionStore.loadStoredTranscript(entry.snapshot, snapshot -> {
                            if (destroyed || settingsScreen != page) return;
                            if (snapshot == null || snapshot.cues.isEmpty()) {
                                storageMessage("Không tìm thấy transcript đã lưu.");
                                return;
                            }
                            showTranscriptExportOptions(snapshot, index == 1);
                        });
                    } else {
                        boolean translationOnly = entry.translated && index == 2;
                        confirmTranscriptRemoval(entry, translationOnly,
                                translationOnly ? "Xóa bản dịch" : removeAll, page);
                    }
                })
                .setNegativeButton("Đóng", null).show();
    }

    private void showTranscriptExportOptions(PlaybackSessionStore.Snapshot snapshot, boolean share) {
        String action = share ? "Chia sẻ" : "Xuất tệp";
        int missing = 0;
        for (TranscriptCue cue : snapshot.cues) {
            if (!cue.hasTranslation()) missing++;
        }
        boolean translated = missing < snapshot.cues.size();
        LinearLayout options = form();
        Spinner content = selection(options, "Nội dung", translated
                ? java.util.Arrays.asList("Transcript gốc", "Bản dịch", "Song ngữ")
                : java.util.Collections.singletonList("Transcript gốc"), translated ? 2 : 0);
        Spinner format = selection(options, "Định dạng",
                java.util.Arrays.asList("TXT", "SRT"), 0);
        if (translated && missing > 0) {
            settingsHeading(options, "Còn " + missing + " đoạn chưa có bản dịch.");
        }
        showSheet(sheet(action, options).setNegativeButton("Hủy", null)
                .setPositiveButton(action, (dialog, which) -> prepareTranscriptExport(snapshot,
                        TranscriptExport.Content.values()[content.getSelectedItemPosition()],
                        TranscriptExport.Format.values()[format.getSelectedItemPosition()], share)));
    }

    private void prepareTranscriptExport(PlaybackSessionStore.Snapshot snapshot,
                                         TranscriptExport.Content content,
                                         TranscriptExport.Format format, boolean share) {
        storageIo.execute(() -> {
            try {
                File file = TranscriptExport.stage(getCacheDir(), snapshot.name, snapshot.cues, content, format);
                main.post(() -> {
                    if (destroyed) return;
                    try {
                        if (share) {
                            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                                    getPackageName() + ".transcript-exports", file);
                            Intent send = new Intent(Intent.ACTION_SEND).setType(format.mimeType)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            send.setClipData(android.content.ClipData.newRawUri(file.getName(), uri));
                            startActivity(Intent.createChooser(send, "Chia sẻ transcript và bản dịch"));
                        } else {
                            pendingTranscriptExport = file.getParentFile().getName() + "/" + file.getName();
                            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType(format.mimeType)
                                    .putExtra(Intent.EXTRA_TITLE, file.getName());
                            startActivityForResult(create, EXPORT_TRANSCRIPT);
                        }
                    } catch (RuntimeException error) {
                        if (!share) pendingTranscriptExport = null;
                        storageMessage(share ? "Không thể mở bảng chia sẻ." : "Không thể mở trình lưu tệp.");
                    }
                });
            } catch (java.io.IOException | RuntimeException error) {
                main.post(() -> {
                    if (!destroyed) storageMessage("Không thể tạo tệp transcript.");
                });
            }
        });
    }

    private void finishTranscriptExport(Uri destination) {
        String relativePath = pendingTranscriptExport;
        pendingTranscriptExport = null;
        if (relativePath == null) {
            if (destination != null) storageMessage("Không tìm thấy tệp để xuất.");
            return;
        }
        storageIo.execute(() -> {
            File file = new File(TranscriptExport.directory(getCacheDir()), relativePath);
            try {
                if (destination == null) return;
                try (java.io.OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new java.io.IOException("Document stream unavailable");
                    java.nio.file.Files.copy(file.toPath(), output);
                }
                main.post(() -> {
                    if (!destroyed) storageMessage("Đã xuất tệp");
                });
            } catch (java.io.IOException | RuntimeException error) {
                main.post(() -> {
                    if (!destroyed) storageMessage("Không thể lưu tệp transcript.");
                });
            } finally {
                file.delete();
                file.getParentFile().delete();
            }
        });
    }

    private void confirmTranscriptRemoval(PlaybackSessionStore.StoredTranscript entry, boolean translationOnly,
                                          String action, View page) {
        long estimate = translationOnly ? entry.snapshot.cues.stream().mapToLong(cue ->
                org.json.JSONObject.quote(cue.targetText).getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 2
        ).sum() : entry.bytes;
        new PlayerAppearance.DialogBuilder(this, settings)
                .positiveRole(PlayerAppearance.ButtonRole.CONFIRM_DELETE).setTitle(action + "?")
                .setMessage(entry.snapshot.name + "\nDung lượng dự kiến thu hồi · " + storageSize(estimate)
                        + (translationOnly ? "\nTranscript gốc sẽ được giữ." : ""))
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa", (dialog, which) -> {
                    Uri uri = entry.snapshot.uri;
                    if (!canDeleteTranscript(uri)) {
                        storageMessage("Dữ liệu đang được sử dụng. Hãy thử lại sau.");
                        return;
                    }
                    work.removeTranscript(entry, translationOnly, settings.sourceLanguage, updated -> {
                        if (destroyed) return;
                        updateResumeVideo();
                        storageMessage(updated == null ? "Không thể xóa dữ liệu." : "Đã xóa dữ liệu.");
                        if (settingsScreen == page) showTranscriptStorage();
                    });
                }).show();
    }

    private void storageMessage(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private String effectiveVoice(String language, boolean translated) {
        if (language == null || "auto".equals(language)) return "";
        String code = Locale.forLanguageTag(language).getLanguage();
        String override = session.voices.get(voiceOverrideKey(code, translated));
        return override == null ? AppSettings.voice(this, code) : override;
    }

    private static String voiceOverrideKey(String code, boolean translated) {
        return (translated ? "translation." : "original.") + code;
    }

    private void showDefaultVoices() {
        chooseVoice(settings.targetLanguage, false, false);
    }

    private String voiceDescription(String language, boolean translated) {
        return describeVoice(language, effectiveVoice(language, translated));
    }

    private String describeVoice(String language, String selected) {
        for (android.speech.tts.Voice voice : speech.voices(language)) {
            if (voice.getName().equals(selected)) return voiceLabel(voice);
        }
        return "Tự động";
    }

    private static String voiceLabel(android.speech.tts.Voice voice) {
        return voice.getLocale().getDisplayName() + " · " + voice.getName()
                + (voice.isNetworkConnectionRequired() ? " · Cần mạng" : " · Offline");
    }

    private String transcriptOriginLabel() {
        String origin = session.transcriptOrigin();
        if (origin == null) return "Transcript hiện tại · Chưa ghi nhận model";
        if (VideoSession.IMPORTED_SUBTITLES.equals(origin)) return "Transcript hiện tại · Phụ đề đã nhập";
        String model = origin.startsWith("vosk-model-small-") ? "Vosk Small" : "Vosk Large";
        return "Transcript hiện tại · " + model + " (" + origin + ")";
    }

    private void showVideoProcessing() {
        LinearLayout controls = form();
        String source = session.sourceLanguage;
        String target = session.targetLanguage == null ? settings.targetLanguage : session.targetLanguage;
        AlertDialog dialog = sheet("Tùy chọn xử lý", controls).setNegativeButton("Đóng", null).create();
        processingLabel(controls, "Nhận dạng · Vosk offline");
        if (!session.completedCues().isEmpty()) processingLabel(controls, transcriptOriginLabel());
        if (!session.tracks.isEmpty()) {
            compactButton(controls, session.cues.isEmpty() ? "Tạo transcript" : "Nhận dạng lại", () -> {
                dialog.dismiss();
                chooseTrack(true);
            }).setEnabled(!session.busy);
        }
        processingLabel(controls, "Dịch · ML Kit Offline hoặc DeepL");
        if (!session.cues.isEmpty()) {
            compactButton(controls, session.targetLanguage == null ? "Dịch" : "Dịch lại", () -> {
                dialog.dismiss();
                chooseTranslationLanguage(true);
            }).setEnabled(!session.busy);
        }
        if (source != null && !"auto".equals(source)) {
            processingLabel(controls, voiceDescription(source, false));
            compactButton(controls, "Giọng gốc · " + languageName(source), () -> {
                dialog.dismiss();
                chooseVoice(source, true, false);
            });
        }
        processingLabel(controls, voiceDescription(target, true));
        compactButton(controls, "Giọng dịch · " + languageName(target), () -> {
            dialog.dismiss();
            chooseVoice(target, true, true);
        });
        showSheet(dialog);
    }

    private TextView processingLabel(LinearLayout form, String label) {
        TextView view = new TextView(this);
        view.setTextSize(settings.textSize(14));
        view.setText(label);
        form.addView(view);
        return view;
    }

    private void chooseVoice(String language, boolean videoOnly, boolean translated) {
        LinearLayout controls = form();
        List<String> codes = videoOnly ? java.util.Collections.singletonList(language) : targetLanguages();
        Spinner languagePicker = null;
        if (!videoOnly) {
            processingLabel(controls, "Ngôn ngữ");
            languagePicker = languages(controls, "Ngôn ngữ giọng đọc", codes, language);
        }
        Spinner languages = languagePicker;
        java.util.function.Supplier<String> selectedCode = () -> Locale.forLanguageTag(
                codes.get(languages == null ? 0 : Math.max(0, languages.getSelectedItemPosition()))).getLanguage();
        processingLabel(controls, "Giọng đọc");
        LinearLayout voiceOptions = column();
        controls.addView(voiceOptions);
        TextView availability = processingLabel(controls, "");
        TextView status = processingLabel(controls, "");
        CueSpeech preview = new CueSpeech(this, status::setText);
        Button systemSettings = compactButton(controls, "Mở cài đặt giọng đọc", () -> {
            preview.stop();
            try {
                startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
            } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
        });
        List<String> names = new ArrayList<>();
        java.util.function.Supplier<String> selectedVoice = () -> {
            Spinner picker = (Spinner) voiceOptions.getChildAt(0);
            return names.get(Math.max(0, picker.getSelectedItemPosition()));
        };
        Runnable refreshVoices = () -> {
            String code = selectedCode.get();
            List<android.speech.tts.Voice> voices = speech.voices(code);
            preview.stop();
            status.setText("");
            names.clear();
            List<String> labels = new ArrayList<>();
            if (videoOnly) {
                names.add(null);
                labels.add("Dùng mặc định · " + describeVoice(code, AppSettings.voice(this, code)));
            }
            names.add("");
            labels.add("Tự động");
            for (android.speech.tts.Voice voice : voices) {
                names.add(voice.getName());
                labels.add(voiceLabel(voice));
            }
            String saved = videoOnly ? session.voices.get(voiceOverrideKey(code, translated))
                    : AppSettings.voice(this, code);
            int selected = names.indexOf(saved);
            voiceOptions.removeAllViews();
            selection(voiceOptions, "Giọng đọc", labels, selected >= 0 ? selected : names.indexOf(""));
            availability.setText(voices.isEmpty() ? "Android chưa cung cấp giọng cho ngôn ngữ này." : "");
            availability.setVisibility(voices.isEmpty() ? View.VISIBLE : View.GONE);
            systemSettings.setVisibility(availability.getVisibility());
        };
        refreshVoices.run();
        if (languages != null) languages.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshVoices.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        compactButton(controls, "Nghe thử", () -> {
            String code = selectedCode.get();
            String selected = selectedVoice.get();
            status.setText("");
            preview.preview(voiceSample(code), code, settings.dubbingVolume / 100f,
                    selected == null ? AppSettings.voice(this, code) : selected);
        });
        AlertDialog dialog = sheet(videoOnly ? "Giọng đọc · " + languageName(language) : "Chọn giọng đọc", controls)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", (ignored, which) -> {
                    String code = selectedCode.get();
                    String selected = selectedVoice.get();
                    String key = voiceOverrideKey(code, translated);
                    if (!videoOnly) AppSettings.saveVoice(this, code, selected);
                    else if (selected == null) session.voices.remove(key);
                    else session.voices.put(key, selected);
                }).create();
        dialog.setOnDismissListener(ignored -> preview.close());
        showSheet(dialog);
    }

    private static String voiceSample(String language) {
        switch (language) {
            case "vi": return "Đây là giọng đọc mẫu của AI Player.";
            case "ja": return "これは音声のサンプルです。";
            case "ko": return "이것은 음성 샘플입니다.";
            case "zh": return "这是语音示例。";
            case "en": return "This is a sample of the selected voice.";
            default: return Locale.forLanguageTag(language).getDisplayLanguage(Locale.forLanguageTag(language));
        }
    }

    private void showModels() {
        showModels(ModelTab.RECOGNITION, null);
    }

    private void showModels(ModelTab initialTab, Runnable parent) {
        LinearLayout controls = form();
        LinearLayout page = settingsPage("Model và giọng đọc", controls, true, false);
        compactButton(controls, "Chọn giọng đọc", this::showDefaultVoices);
        settingsParent = parent;

        RadioGroup tabs = new RadioGroup(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        controls.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        int recognitionId = addModelTab(tabs, "Nhận dạng");
        int translationId = addModelTab(tabs, "Dịch offline");
        tabs.check(initialTab == ModelTab.RECOGNITION ? recognitionId : translationId);

        ScrollView scroll = new ScrollView(this);
        modelForm = form();
        scroll.addView(modelForm);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        modelTab = initialTab;

        tabs.setOnCheckedChangeListener((group, checkedId) -> {
            modelTab = checkedId == recognitionId ? ModelTab.RECOGNITION : ModelTab.TRANSLATION;
            renderModels();
            scroll.scrollTo(0, 0);
        });
        work.models.refreshTranslations();
        renderModels();
    }

    private int addModelTab(RadioGroup tabs, String label) {
        RadioButton tab = new RadioButton(this);
        tab.setId(View.generateViewId());
        tab.setText(label);
        PlayerAppearance.styleButton(tab, PlayerAppearance.ButtonRole.UTILITY, settings);
        tab.setMinHeight(dp(48));
        tabs.addView(tab, new RadioGroup.LayoutParams(0, -2, 1));
        return tab.getId();
    }

    private void refreshModelsAfterWork() {
        if (modelForm != null && modelSessionBusy != session.busy) {
            renderModels();
            if (!session.busy) work.models.refreshTranslations();
        }
    }

    private int modelRenderVersion;

    private void renderModels() {
        if (modelForm == null || destroyed) return;
        OfflineModels models = work.models;
        modelSessionBusy = session.busy;
        int version = ++modelRenderVersion;
        String source = LanguageUtils.normalizeLanguage(settings.sourceLanguage, "auto");
        if (modelTab == ModelTab.RECOGNITION) {
            LinearLayout targetForm = modelForm;
            storageIo.execute(() -> {
                java.util.Map<String, Long> sizes = new java.util.HashMap<>();
                for (String language : SOURCE_LANGUAGES) sizes.put(language, models.vosk.installedBytes(language));
                for (String model : VoskModelStore.largeModelKeys()) {
                    sizes.put(model, models.vosk.installedBytes(model));
                }
                runOnUiThread(() -> {
                    if (!destroyed && modelForm == targetForm && version == modelRenderVersion) {
                        resetModelRows();
                        renderRecognitionModels(models, source, sizes);
                    }
                });
            });
        } else {
            resetModelRows();
            renderTranslationModels(models, source);
        }
    }

    private void resetModelRows() {
        modelForm.removeAllViews();
        if (session.busy) modelText("Có tác vụ video đang chạy. Hãy đợi để quản lý model.", false);
    }

    private void renderRecognitionModels(OfflineModels models, String source, java.util.Map<String, Long> sizes) {
        Set<String> recognition = new LinkedHashSet<>();
        recognition.add(SOURCE_LANGUAGES.contains(source) ? source : "en");
        recognition.addAll(SOURCE_LANGUAGES);
        recognition.addAll(VoskModelStore.largeModelKeys());
        modelText("Tạo transcript (Vosk) · Lớn nhất trước", true);
        boolean recognitionAvailable = !session.busy && !OfflineModels.pending(models.recognition);
        List<String> ordered = new ArrayList<>(recognition);
        ordered.sort(java.util.Comparator.comparingLong((String language) -> sizes.getOrDefault(language, -1L))
                .reversed());
        for (String language : ordered) {
            boolean downloaded = models.vosk.isDownloaded(language);
            OfflineModels.Operation operation = models.recognition.get(language);
            if (downloaded && operation == OfflineModels.Operation.DOWNLOAD_FAILED) operation = null;
            String model = VoskModelStore.modelName(language);
            modelRow(language, model + (language.equals(source) ? " · Nguồn đang chọn" : ""), downloaded,
                    models.vosk.isBundled(language), operation, models.recognitionPercent, recognitionAvailable,
                    sizes.getOrDefault(language, -1L),
                    () -> models.downloadRecognition(language), () -> models.deleteRecognition(language));
        }

    }

    private void renderTranslationModels(OfflineModels models, String source) {
        String target = LanguageUtils.normalizeLanguage(settings.targetLanguage, "vi");
        modelText("Dịch offline (ML Kit)", true);
        modelText("Cặp dịch đang chọn: " + ("auto".equals(source) ? "Tự động" : languageName(source))
                + " → " + languageName(target), false);
        Set<String> required = new LinkedHashSet<>();
        if (!source.equals(target)) {
            if (OfflineModels.needsTranslationModel(source)) required.add(source);
            if (OfflineModels.needsTranslationModel(target)) required.add(target);
        }
        if (source.equals(target)) modelText("Nguồn và đích giống nhau. Không cần tải model để dịch.", false);
        if ("auto".equals(source)) modelText("Model nguồn được xác định khi dịch transcript.", false);
        if (models.translationQueryFailed) {
            modelText("Không kiểm tra được model dịch trên thiết bị.", false);
            compactButton(modelForm, "Kiểm tra lại", () -> {
                models.refreshTranslations();
                renderModels();
            });
        } else if (!models.translationsLoaded) {
            modelText("Đang kiểm tra model dịch…", false);
        }

        boolean translationKnown = models.translationsLoaded && !models.translationQueryFailed;
        boolean translationAvailable = translationKnown && !session.busy && !OfflineModels.pending(models.translation);
        List<String> missing = new ArrayList<>(required);
        missing.removeAll(models.downloadedTranslations);
        if (translationKnown && !missing.isEmpty()) {
            String downloadLabel = "auto".equals(source) ? "Tải model ngôn ngữ đích" : "Tải model cần thiết";
            Button download = compactButton(modelForm, downloadLabel, () -> models.downloadTranslations(missing));
            stylePrimaryButton(download);
            download.setEnabled(translationAvailable);
        }
        Set<String> translation = new LinkedHashSet<>(Arrays.asList("zh", "ja", "en", "vi", "ko"));
        translation.addAll(required);
        List<String> installed = new ArrayList<>(models.downloadedTranslations);
        installed.sort((a, b) -> languageName(a).compareToIgnoreCase(languageName(b)));
        translation.addAll(installed);
        for (String language : translation) {
            String role = language.equals(source) ? (language.equals(target) ? "Nguồn và đích" : "Nguồn")
                    : (language.equals(target) ? "Đích" : "");
            if (TranslateLanguage.ENGLISH.equals(language)) {
                modelRowDetails(language, "Có sẵn · Không cần tải riêng", role, -1,
                        PlayerAppearance.StatusIcon.SUCCESS);
                continue;
            }
            if (!translationKnown) {
                modelRowDetails(language, models.translationQueryFailed
                        ? "Chưa rõ trạng thái" : "Đang kiểm tra…", role, -1,
                        models.translationQueryFailed ? PlayerAppearance.StatusIcon.ERROR
                                : PlayerAppearance.StatusIcon.WORKING);
                continue;
            }
            modelRow(language, role, models.downloadedTranslations.contains(language), false,
                    models.translation.get(language), -1, translationAvailable, -1,
                    () -> models.downloadTranslations(Collections.singletonList(language)),
                    () -> models.deleteTranslation(language));
        }
    }

    private void modelRow(String language, String role, boolean downloaded, boolean bundled, OfflineModels.Operation operation,
                          int percent, boolean enabled, long bytes, Runnable download, Runnable delete) {
        String name = languageName(language);
        String spaceError = modelTab == ModelTab.RECOGNITION
                ? work.models.recognitionSpaceErrors.get(language) : null;
        String status = downloaded ? "Đã tải" : bundled ? "Có sẵn trong ứng dụng" : "Chưa tải";
        PlayerAppearance.StatusIcon icon = downloaded || bundled ? PlayerAppearance.StatusIcon.SUCCESS
                : role.isEmpty() ? PlayerAppearance.StatusIcon.NEUTRAL : PlayerAppearance.StatusIcon.WARNING;
        if (operation != null) {
            switch (operation) {
                case DOWNLOADING:
                    status = percent < 0 ? "Đang tải…" : "Đang tải và giải nén · " + percent + "%";
                    icon = PlayerAppearance.StatusIcon.WORKING;
                    break;
                case DELETING:
                    status = "Đang xóa…";
                    icon = PlayerAppearance.StatusIcon.WORKING;
                    break;
                case DOWNLOAD_FAILED:
                    status = spaceError == null
                            ? "Không thể tải model. Kiểm tra kết nối mạng và thử lại." : spaceError;
                    icon = PlayerAppearance.StatusIcon.ERROR;
                    break;
                case DELETE_FAILED:
                    status = "Xóa thất bại";
                    icon = PlayerAppearance.StatusIcon.ERROR;
                    break;
            }
        }
        LinearLayout row = modelRowDetails(language, status, role, downloaded ? bytes : -1, icon);
        if ((bundled && !downloaded) || operation == OfflineModels.Operation.DOWNLOADING
                || operation == OfflineModels.Operation.DELETING) return;
        boolean retry = operation == OfflineModels.Operation.DOWNLOAD_FAILED;
        if (!downloaded) {
            String label = retry ? "Thử lại" : "Tải";
            Button action = compactButton(row, label, download);
            PlayerAppearance.styleButton(action, PlayerAppearance.ButtonRole.ACCENT, settings);
            action.setContentDescription(label + " model " + name);
            action.setEnabled(enabled);
            if (spaceError != null) {
                compactButton(modelForm, "Dữ liệu và bộ nhớ", this::openStorageFromDownload);
            }
            return;
        }

        Button menu = iconButton(row, R.drawable.ic_control_more, "Thao tác model " + name, false, null);
        menu.setEnabled(enabled);
        menu.setOnClickListener(anchor -> {
            PopupMenu popup = new PopupMenu(new ContextThemeWrapper(this, R.style.PlayerMenuTheme),
                    anchor, Gravity.END);
            popup.getMenu().add(PlayerAppearance.dangerLabel(this, "Xóa model")).setOnMenuItemClickListener(item -> {
                if (session.busy) return true;
                new PlayerAppearance.DialogBuilder(this, settings)
                        .positiveRole(PlayerAppearance.ButtonRole.CONFIRM_DELETE)
                        .setTitle("Xóa model " + name + "?")
                        .setMessage((downloaded && bytes >= 0
                                ? "Dung lượng dự kiến thu hồi · " + storageSize(bytes)
                                : "Chưa đo được dung lượng dự kiến thu hồi.")
                                + (bundled ? "\nỨng dụng sẽ dùng model có sẵn nếu cần."
                                : "\nỨng dụng sẽ cần kết nối mạng để tải lại model khi sử dụng."))
                        .setNegativeButton("Hủy", null)
                        .setPositiveButton("Xóa", (dialog, which) -> {
                            if (!session.busy) delete.run();
                        }).show();
                return true;
            });
            popup.show();
        });
    }

    private LinearLayout modelRowDetails(String language, String status, String role, long installedBytes,
                                         PlayerAppearance.StatusIcon icon) {
        String name = languageName(language);
        boolean recognition = modelTab == ModelTab.RECOGNITION;
        String summary = role.isEmpty() ? status : status + " · " + role;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(8), dp(4), 0, dp(4));
        LinearLayout details = column();
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(settings.textSize(16));
        details.addView(title);
        TextView description = new TextView(this);
        description.setText(summary);
        description.setTextSize(settings.textSize(13));
        description.setCompoundDrawablePadding(dp(4));
        icon.apply(description);
        details.addView(description);
        if (installedBytes >= 0) {
            TextView size = new TextView(this);
            size.setTextSize(settings.textSize(13));
            size.setText(storageSize(installedBytes));
            details.addView(size);
        }

        details.setFocusable(true);
        details.setMinimumHeight(dp(48));
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setOnClickListener(view -> {
            String message = (recognition ? "Vosk · Tạo transcript" : "ML Kit · Dịch offline")
                    + "\n" + summary;
            if (recognition) message += "\nGói tải: " + VoskModelStore.downloadPackage(language);
            if (installedBytes >= 0) {
                message += "\nDung lượng đã cài: " + storageSize(installedBytes);
            } else if (!TranslateLanguage.ENGLISH.equals(language) || recognition) {
                message += "\nChưa đo được dung lượng";
            }
            new PlayerAppearance.DialogBuilder(this, settings)
                    .setTitle("Thông tin model · " + name)
                    .setMessage(message)
                    .setNegativeButton("Đóng", null).show();
        });
        modelForm.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void modelText(String label, boolean heading) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(settings.textSize(heading ? 18 : 14));
        text.setPadding(dp(8), dp(heading ? 14 : 6), dp(8), dp(4));
        if (heading) {
            text.setTypeface(null, Typeface.BOLD);
            if (android.os.Build.VERSION.SDK_INT >= 28) text.setAccessibilityHeading(true);
        }
        modelForm.addView(text);
    }

    private void showTelegramSettings() {
        LinearLayout form = form();
        LinearLayout page = settingsPage("Tài khoản Telegram", form, false);
        form.setPadding(dp(20), dp(12), dp(20), dp(20));
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        PlayerAppearance.styleSurface(card,
                PlayerAppearance.color(this, android.R.attr.colorBackgroundFloating), false);
        form.addView(card, new LinearLayout.LayoutParams(-1, -2));
        telegramSettingsStatus = new TextView(this);
        telegramSettingsStatus.setTextSize(settings.textSize(16));
        telegramSettingsStatus.setTypeface(null, Typeface.BOLD);
        telegramSettingsStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(telegramSettingsStatus);
        telegramSettingsDescription = new TextView(this);
        telegramSettingsDescription.setTextSize(settings.textSize(14));
        telegramSettingsDescription.setPadding(0, dp(8), 0, 0);
        card.addView(telegramSettingsDescription);
        telegramSettingsProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        card.addView(telegramSettingsProgress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        telegramSettingsAction = compactButton(card, "", this::handleTelegramSettingsAction);
        telegramSettingsAction.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        LinearLayout footer = column();
        footer.setPadding(dp(20), 0, dp(20), dp(12));
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        telegramSettingsLogout = compactButton(footer, "Đăng xuất", this::confirmTelegramLogout);
        telegramSettingsLogout.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        telegramSettingsLogout.setGravity(Gravity.CENTER);
        PlayerAppearance.styleButton(telegramSettingsLogout, PlayerAppearance.ButtonRole.SECONDARY, settings);
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
        form.setPadding(dp(20), dp(12), dp(20), dp(20));
        LinearLayout identity = row(form);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        logoParams.setMarginEnd(dp(12));
        identity.addView(logo, logoParams);

        LinearLayout identityText = column();
        identity.addView(identityText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = new TextView(this);
        name.setText("AI Player");
        name.setTextSize(settings.textSize(20));
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(PlayerAppearance.color(this, android.R.attr.textColorPrimary));
        identityText.addView(name);
        TextView version = new TextView(this);
        version.setText(build.versionSummary);
        version.setTextSize(settings.textSize(14));
        version.setTextColor(PlayerAppearance.color(this, android.R.attr.textColorSecondary));
        identityText.addView(version);

        LinearLayout card = column();
        card.setPadding(dp(16), dp(4), dp(16), dp(16));
        PlayerAppearance.styleSurface(card,
                PlayerAppearance.color(this, android.R.attr.colorBackgroundFloating), false);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(PlayerAppearance.GROUP_GAP_DP);
        form.addView(card, cardParams);
        settingsHeading(card, "Chi tiết bản dựng");
        addBuildDetail(card, "Gói Android", build.packageName, true);
        addBuildDetail(card, "Biến thể", build.variant, false);
        addBuildDetail(card, "Commit", build.shortCommit, true);
        addBuildDetail(card, "Trạng thái mã nguồn", build.sourceState, false);

        LinearLayout page = settingsPage("Thông tin ứng dụng", form, true);
        LinearLayout footer = column();
        footer.setPadding(dp(20), dp(8), dp(20), dp(12));
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        String copyLabel = "Sao chép bản dựng";
        Button copy = new Button(this);
        copy.setText(copyLabel);
        copy.setAllCaps(false);
        copy.setGravity(Gravity.CENTER);
        copy.setMinHeight(dp(48));
        copy.setMinimumHeight(dp(48));
        copy.setPadding(dp(12), dp(8), dp(12), dp(8));
        PlayerAppearance.styleButton(copy, PlayerAppearance.ButtonRole.PRIMARY, settings);
        copy.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_control_copy, 0, 0, 0);
        copy.setCompoundDrawablePadding(dp(8));
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

    private void addBuildDetail(LinearLayout card, String label, String value, boolean monospace) {
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(settings.textSize(13));
        labelView.setTextColor(PlayerAppearance.color(this, android.R.attr.textColorSecondary));
        labelView.setPadding(0, dp(10), 0, dp(2));
        card.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(settings.textSize(15));
        valueView.setTextColor(PlayerAppearance.color(this, android.R.attr.textColorPrimary));
        if (monospace) valueView.setTypeface(Typeface.MONOSPACE);
        valueView.setTextIsSelectable(true);
        card.addView(valueView, new LinearLayout.LayoutParams(-1, -2));
    }

    private void refreshTelegramSettings() {
        if (telegramSettingsAction != null) {
            TelegramAuthorizationFlow.State state = telegramSession == null
                    ? TelegramAuthorizationFlow.State.UNAVAILABLE : telegramSession.authorizationState();
            boolean connected = isTelegramConnected();
            String description = "Kết nối tài khoản để tải video từ Telegram.";
            String action = "Kết nối Telegram";
            boolean pending = telegramRequestPending || telegramLogoutPending;
            if (pending) {
                description = "Đang chờ phản hồi từ Telegram.";
            } else if (connected) {
                description = "Bạn có thể tải video từ các bài đăng mà tài khoản này có quyền truy cập.";
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
            int dotColor = connected && !pending ? getColor(R.color.ai_ready)
                    : PlayerAppearance.color(this, android.R.attr.textColorSecondary);
            SpannableString statusLabel = new SpannableString("●  " + summary);
            statusLabel.setSpan(new ForegroundColorSpan(dotColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            telegramSettingsStatus.setText(statusLabel);
            telegramSettingsStatus.setContentDescription(summary);
            telegramSettingsDescription.setText(description);
            telegramSettingsProgress.setVisibility(pending ? View.VISIBLE : View.GONE);
            telegramSettingsAction.setVisibility(pending || connected ? View.GONE : View.VISIBLE);
            telegramSettingsLogout.setEnabled(!pending);
            telegramSettingsLogout.setText(telegramLogoutPending ? "Đang đăng xuất…" : "Đăng xuất");
            ((View) telegramSettingsLogout.getParent()).setVisibility(
                    connected || telegramLogoutPending ? View.VISIBLE : View.GONE);
            telegramSettingsAction.setText(action);
            boolean primary = "Kết nối Telegram".equals(action);
            PlayerAppearance.styleButton(telegramSettingsAction, primary, settings);
        }
    }

    private String telegramSummary() {
        if (telegramLogoutPending) return "Đang đăng xuất…";
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

    private TextView createSubtitleView() {
        TextView caption = new TextView(this);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(dp(8), dp(4), dp(8), dp(4));
        styleSubtitle(caption, settings.subtitleSize, settings.subtitleColor,
                settings.subtitleBackground, settings.subtitleOpacity);
        return caption;
    }

    private void styleSubtitle(TextView caption, int size, AppSettings.SubtitleColor textColor,
                               AppSettings.SubtitleBackground background, int opacity) {
        caption.setTextSize(size);
        caption.setTextColor(textColor.argb);
        caption.setBackgroundColor(background.withOpacity(opacity));
    }

    private void styleLanguagePicker(Spinner picker) {
        ViewGroup form = (ViewGroup) picker.getParent();
        int index = form.indexOfChild(picker);
        form.removeViewAt(index);
        FrameLayout surface = new FrameLayout(this);
        PlayerAppearance.styleSurface(surface,
                PlayerAppearance.color(this, android.R.attr.colorBackgroundFloating), false);
        surface.addView(picker, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(4), 0, dp(8));
        form.addView(surface, index, params);
    }

    private void showLanguageSettings() {
        LinearLayout form = form();
        List<String> codes = targetLanguages();
        settingsHeading(form, "Ngôn ngữ");
        Spinner source = languages(form, "Ngôn ngữ lời thoại", SOURCE_LANGUAGES, settings.sourceLanguage);
        styleLanguagePicker(source);
        addLabel(form, "Dùng để tạo transcript");
        Spinner target = languages(form, "Dịch sang", codes, settings.targetLanguage);
        styleLanguagePicker(target);

        settingsHeading(form, "Phụ đề");
        List<String> sizes = Arrays.asList("12 sp", "14 sp", "16 sp", "18 sp", "20 sp", "22 sp", "24 sp");
        Spinner size = selection(form, "Cỡ chữ phụ đề", sizes, (settings.subtitleSize - 12) / 2);
        styleLanguagePicker(size);
        AppSettings.SubtitleColor[] colors = AppSettings.SubtitleColor.values();
        List<String> colorNames = new ArrayList<>();
        for (AppSettings.SubtitleColor color : colors) colorNames.add(color.label);
        Spinner color = selection(form, "Màu chữ phụ đề", colorNames, settings.subtitleColor.ordinal(),
                Arrays.stream(colors).mapToInt(value -> value.argb).toArray());
        styleLanguagePicker(color);
        AppSettings.SubtitleBackground[] backgrounds = AppSettings.SubtitleBackground.values();
        List<String> backgroundNames = new ArrayList<>();
        for (AppSettings.SubtitleBackground value : backgrounds) backgroundNames.add(value.label);
        Spinner background = selection(form, "Màu nền phụ đề", backgroundNames,
                settings.subtitleBackground.ordinal(), Arrays.stream(backgrounds).mapToInt(value -> value.argb).toArray());
        styleLanguagePicker(background);
        int opacityStep = AppSettings.SUBTITLE_OPACITY_STEP;
        SeekBar opacity = percentageField(form, "Độ đậm nền phụ đề", settings.subtitleOpacity,
                true, opacityStep);
        settingsHeading(form, "Xem trước");
        FrameLayout preview = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable previewBackdrop = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xff252d3a, 0xffd9e0e8});
        previewBackdrop.setCornerRadius(dp(12));
        preview.setBackground(previewBackdrop);
        preview.setPadding(dp(12), dp(16), dp(12), dp(16));
        TextView sample = createSubtitleView();
        sample.setText("Đây là phụ đề mẫu");
        preview.addView(sample, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        form.addView(preview, new LinearLayout.LayoutParams(-1, -2));

        Button save = settingsEditor("Ngôn ngữ và phụ đề", form, () -> {
            String previousTarget = settings.targetLanguage;
            settings.sourceLanguage = SOURCE_LANGUAGES.get(source.getSelectedItemPosition());
            settings.targetLanguage = codes.get(target.getSelectedItemPosition());
            settings.subtitleSize = 12 + 2 * size.getSelectedItemPosition();
            settings.subtitleColor = colors[color.getSelectedItemPosition()];
            settings.subtitleBackground = backgrounds[background.getSelectedItemPosition()];
            settings.subtitleOpacity = opacity.getProgress() * opacityStep;
            styleSubtitle(subtitle, settings.subtitleSize, settings.subtitleColor,
                    settings.subtitleBackground, settings.subtitleOpacity);
            settings.save(this);
            if (!hasVideo && !previousTarget.equals(settings.targetLanguage)) updateResumeVideo();
            updateControls(null);
            showSettings();
            android.widget.Toast.makeText(this, "Đã lưu cài đặt", android.widget.Toast.LENGTH_SHORT).show();
        });
        stylePrimaryButton(save);
        int initialSource = source.getSelectedItemPosition();
        int initialTarget = target.getSelectedItemPosition();
        int initialSize = size.getSelectedItemPosition();
        java.util.function.BooleanSupplier changed = () -> source.getSelectedItemPosition() != initialSource
                || target.getSelectedItemPosition() != initialTarget || size.getSelectedItemPosition() != initialSize
                || color.getSelectedItemPosition() != settings.subtitleColor.ordinal()
                || background.getSelectedItemPosition() != settings.subtitleBackground.ordinal()
                || opacity.getProgress() * opacityStep != settings.subtitleOpacity;
        languageSettingsChanged = changed;
        Runnable refresh = () -> {
            styleSubtitle(sample, 12 + 2 * size.getSelectedItemPosition(),
                    colors[color.getSelectedItemPosition()], backgrounds[background.getSelectedItemPosition()],
                    opacity.getProgress() * opacityStep);
            save.setEnabled(changed.getAsBoolean());
        };
        watchSettingsSelection(refresh, source, target, size, color, background);
        opacity.setTag(refresh);
        refresh.run();
    }

    private void showAudioSettings() {
        LinearLayout form = form();
        form.setPadding(dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.GROUP_GAP_DP), dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.GROUP_GAP_DP));
        SeekBar video = volumeField(form, "Âm lượng video", settings.sourceVolume, true);
        LinearLayout voiceGroup = column();
        LinearLayout.LayoutParams voiceParams = new LinearLayout.LayoutParams(-1, -2);
        voiceParams.topMargin = dp(PlayerAppearance.GROUP_GAP_DP);
        form.addView(voiceGroup, voiceParams);
        SeekBar voice = volumeField(voiceGroup, "Âm lượng giọng đọc", settings.dubbingVolume, true);
        TextView previewStatus = new TextView(this);
        previewStatus.setTextSize(settings.textSize(12));
        previewStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        previewStatus.setVisibility(View.GONE);
        Button preview = audioTextButton(voiceGroup, "Nghe thử", () -> {
            previewStatus.setVisibility(View.GONE);
            audioPreview.preview("Đây là giọng đọc mẫu của AI Player.", "vi",
                    voice.getProgress() / 100f, AppSettings.voice(this, "vi"));
        });
        voiceGroup.addView(previewStatus);
        Button reset = audioTextButton(form, "Khôi phục mặc định", () -> {
            video.setProgress(AppSettings.DEFAULT_SOURCE_VOLUME);
            voice.setProgress(AppSettings.DEFAULT_DUBBING_VOLUME);
        });

        LinearLayout footer = row(settingsPage("Âm thanh", form, true));
        footer.setPadding(dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.GROUP_GAP_DP), dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.CONTROL_GAP_DP));
        button(footer, "Hủy", this::showSettings);
        Button save = button(footer, "Lưu", () -> {
            settings.sourceVolume = video.getProgress();
            settings.dubbingVolume = voice.getProgress();
            settings.save(this);
            speech.stop();
            updateControls(null);
            showSettings();
        });
        stylePrimaryButton(save);
        audioSettingsChanged = () -> video.getProgress() != settings.sourceVolume
                || voice.getProgress() != settings.dubbingVolume;
        audioPreview = new CueSpeech(this, message -> {
            previewStatus.setText(message);
            previewStatus.setVisibility(View.VISIBLE);
        });
        Runnable refresh = () -> {
            save.setEnabled(audioSettingsChanged.getAsBoolean());
            reset.setEnabled(video.getProgress() != AppSettings.DEFAULT_SOURCE_VOLUME
                    || voice.getProgress() != AppSettings.DEFAULT_DUBBING_VOLUME);
        };
        video.setTag(refresh);
        voice.setTag((Runnable) () -> {
            audioPreview.stop();
            previewStatus.setVisibility(View.GONE);
            refresh.run();
        });
        refresh.run();
    }

    private Button audioTextButton(LinearLayout form, String label, Runnable action) {
        Button control = compactButton(form, label, action);
        control.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        PlayerAppearance.styleButton(control, PlayerAppearance.ButtonRole.UTILITY, settings);
        return control;
    }

    private void clearAudioSettings() {
        audioSettingsChanged = null;
        if (audioPreview != null) {
            audioPreview.close();
            audioPreview = null;
        }
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
        int missing = session.missingTranslations();
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
            modeDialog.setNeutralButton("Xử lý",
                    (window, which) -> retryMissingTranslations());
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
        cueList.setOnItemLongClickListener((parent, view, position, id) -> {
            TranscriptCue cue = session.cues.get(position);
            if (!showTranslation || !session.canEditTranslation(cue)) {
                return false;
            }
            editCueTranslation(cue);
            return true;
        });
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
        markVisibleResult();
    }

    private void markVisibleResult() {
        if (!started || session.busy || !transcriptPanel.isShown() || session.cues.isEmpty()
                || (showTranslation && session.missingTranslations() > 0)) return;
        VideoSession.Task visibleTask = showTranslation ? VideoSession.Task.TRANSLATION
                : VideoSession.Task.TRANSCRIPT;
        for (VideoWork.Result result : work.unreadResults()) {
            if (result.task == visibleTask && result.video.equals(currentVideo)) {
                work.markResultViewed(result);
                return;
            }
        }
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
            if (showTranslation && cue.hasTranslation()) {
                if (cue.translationOrigin == TranscriptCue.TranslationOrigin.ORIGINAL) {
                    timestamp += " · Dùng lời gốc";
                } else if (cue.translationOrigin == TranscriptCue.TranslationOrigin.MANUAL) {
                    timestamp += " · Dịch thủ công";
                }
            }
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
        return volumeField(form, label, value, false);
    }

    private SeekBar volumeField(LinearLayout form, String label, int value, boolean separateValue) {
        return percentageField(form, label, value, separateValue, 1);
    }

    private SeekBar percentageField(LinearLayout form, String label, int value, boolean separateValue, int step) {
        TextView volumeLabel = new TextView(this);
        volumeLabel.setTextSize(settings.textSize(14));
        if (separateValue) {
            LinearLayout heading = row(form);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(label);
            name.setTextSize(settings.textSize(16));
            name.setTextColor(PlayerAppearance.color(this, android.R.attr.textColorPrimary));
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            volumeLabel.setPaddingRelative(dp(8), 0, 0, 0);
            volumeLabel.setGravity(Gravity.END);
            volumeLabel.setTextColor(PlayerAppearance.color(this, android.R.attr.textColorPrimary));
            heading.addView(volumeLabel, new LinearLayout.LayoutParams(-2, -2));
        } else {
            form.addView(volumeLabel);
        }
        String prefix = separateValue ? "" : label + ": ";
        java.util.function.IntConsumer showValue = progress -> volumeLabel.setText(
                separateValue && step == 1 && progress == 0 ? "Tắt tiếng" : prefix + progress * step + "%");
        SeekBar seek = new SeekBar(this);
        seek.setMax(100 / step);
        seek.setProgress(value / step);
        if (separateValue) seek.setMinimumHeight(dp(48));
        showValue.accept(seek.getProgress());
        seek.setContentDescription(label);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                showValue.accept(progress);
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
        if (hasVideo && orientation == Configuration.ORIENTATION_LANDSCAPE && !controlsAreVisible()) {
            feedback.setVisibility(View.GONE);
            return;
        }
        boolean showMessage = status.length() > 0 && (!hasVideo || busy || controlsAreVisible());
        status.setVisibility(showMessage ? View.VISIBLE : View.GONE);
        dismissStatus.setVisibility(showMessage && !busy ? View.VISIBLE : View.GONE);
        manageStorageStatus.setVisibility(showMessage && !busy && StorageSpace.isLowSpace(status.getText().toString())
                ? View.VISIBLE : View.GONE);
        feedback.setVisibility(showMessage || progress.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
    }

    private boolean controlsAreVisible() {
        return playerView.isControllerFullyVisible();
    }

    private void showPlaybackControls() {
        if (!hasVideo || destroyed) return;
        playerView.showController();
        updateChromeVisibility();
        scheduleControlHide();
    }

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
        boolean showingHomeLogo = title.getCompoundDrawablesRelative()[0] != null;
        if (showingHomeLogo == hasVideo) {
            title.setCompoundDrawablesRelative(hasVideo ? null : homeLogo, null, null, null);
            title.setTypeface(null, hasVideo ? Typeface.NORMAL : Typeface.BOLD);
            if (!hasVideo) title.setPaddingRelative(dp(20), 0, dp(4), 0);
        }
        playerView.setVisibility(hasVideo ? View.VISIBLE : View.INVISIBLE);
        shortcuts.setVisibility(hasVideo && visible ? View.VISIBLE : View.GONE);
        actions.setVisibility(hasVideo && visible && !session.busy && primary.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
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
        updateHomeTask();
        int missing = busy ? 0 : session.missingTranslations();
        primary.setEnabled(hasCues || !session.tracks.isEmpty());
        boolean showMissing = missing > 0 && skippedTranslationVersion != session.transcriptVersion();
        missingTranslationNotice.setVisibility(showMissing ? View.VISIBLE : View.GONE);
        if (showMissing) missingTranslationNotice.setText("Còn " + missing + " đoạn chưa được dịch.");
        skipMissingTranslation.setVisibility(showMissing ? View.VISIBLE : View.GONE);
        primary.setVisibility(!translated || showMissing ? View.VISIBLE : View.GONE);
        setLeadingIcon(primary, hasCues ? R.drawable.ic_control_translate : R.drawable.ic_control_generate);
        primary.setText(showMissing ? "Xử lý"
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
            if (!busy || (session.isDeepLTranslationRunning()
                    && savedTranscriptVersion != session.transcriptVersion())) savePlaybackSession();
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
        updateModeIcon(subtitlesButton, R.drawable.ic_control_captions, "Phụ đề", display,
                display == CueTimeline.Mode.SOURCE ? "Gốc" : "Dịch");
        updateModeIcon(speechButton, read == CueTimeline.Mode.OFF ? R.drawable.ic_control_mute
                : R.drawable.ic_control_voice, "Phát âm", read, "Bật");
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

    private void applyVideoVolume() {
        if (destroyed) return;
        boolean speaking = read != CueTimeline.Mode.OFF && speech != null && speech.isSpeaking();
        player.setVolume(speaking ? 0f : settings.sourceVolume / 100f);
    }

    private String speechUnavailable(CueTimeline.Mode mode) {
        if (mode == CueTimeline.Mode.OFF) return "";
        if (mode == CueTimeline.Mode.TRANSLATION) {
            boolean needsSourceVoice = false;
            boolean needsTargetVoice = false;
            for (TranscriptCue cue : session.cues) {
                if (!cue.hasTranslation()) continue;
                if (cue.translationOrigin == TranscriptCue.TranslationOrigin.ORIGINAL) {
                    needsSourceVoice = true;
                } else {
                    needsTargetVoice = true;
                }
                if (needsSourceVoice && needsTargetVoice) break;
            }
            if (!needsSourceVoice && !needsTargetVoice) return "Chưa có bản dịch để phát âm.";
            if (speech != null && ((needsSourceVoice && speech.supports(session.sourceLanguage))
                    || (needsTargetVoice && speech.supports(session.targetLanguage)))) return "";
            if (needsSourceVoice && needsTargetVoice) return "Thiết bị chưa có giọng đọc phù hợp.";
            return missingSpeechVoice(needsSourceVoice ? session.sourceLanguage : session.targetLanguage);
        }
        String language = session.sourceLanguage;
        if (speech == null || !speech.supports(language)) {
            return missingSpeechVoice(language);
        }
        return "";
    }

    private String missingSpeechVoice(String language) {
        return language == null ? "Chưa có ngôn ngữ để đọc thành tiếng."
                : "Thiết bị chưa có giọng đọc cho " + languageName(language) + ".";
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
        if (read == CueTimeline.Mode.OFF || !player.isPlaying() || settings.dubbingVolume == 0
                || (translatedSpeech && (cue == null || !cue.hasTranslation()))) {
            speech.stop();
        } else {
            String language = read.speechLanguage(cue, session.sourceLanguage, session.targetLanguage);
            speech.update(cue, read.text(cue), language, settings.dubbingVolume / 100f, effectiveVoice(language, translatedSpeech));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshVisibleStorage();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (hasVideo) updateControls(work.status());
        else updateHomeTask();
        PlaybackException error = player.getPlayerError();
        if (hasVideo && error != null && error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) {
            recoverReclaimedDecoder(error);
        }
        main.post(tick);
    }

    @Override
    protected void onStop() {
        if (webVideoPlayer != null) webVideoPlayer.dismiss();
        if (videoDownloads != null) videoDownloads.closeWebSearch();
        if (telegramContent != null) telegramContent.stopInlinePlayback();
        started = false;
        cancelDecoderRetry();
        main.removeCallbacks(tick);
        main.removeCallbacks(hideControls);
        player.pause();
        speech.stop();
        if (audioPreview != null) audioPreview.stop();
        savePlaybackSession();
        super.onStop();
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        TdJsonTelegramSessionClient client = telegramSession instanceof TdJsonTelegramSessionClient
                && videoDownloads.usesTelegramSession(telegramSession)
                ? (TdJsonTelegramSessionClient) telegramSession : null;
        retainedDownload = new RetainedDownload(videoDownloads, client);
        return retainedDownload;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (telegramContent != null) telegramContent.close();
        clearAudioSettings();
        cancelDecoderRetry();
        main.removeCallbacks(hideStatus);
        main.removeCallbacks(hideControls);
        player.release();
        work.detachModels(modelObserver);
        work.detach(workObserver);
        sessionStore.close();
        storageIo.shutdownNow();
        if (retainedDownload != null) videoDownloads.detach();
        else videoDownloads.close();
        savedVideosDialog.close();
        home.close();
        speech.close();
        if (telegramSession instanceof TdJsonTelegramSessionClient) {
            TdJsonTelegramSessionClient client = (TdJsonTelegramSessionClient) telegramSession;
            if (retainedDownload != null && retainedDownload.telegram == client) client.detachListener();
            else client.closeTransport();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
