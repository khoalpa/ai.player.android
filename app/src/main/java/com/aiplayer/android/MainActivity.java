package com.aiplayer.android;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
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
    private enum DocumentRequest {
        OPEN_VIDEO, OPEN_SUBTITLES, EXPORT_TRANSCRIPT, EXPORT_MODEL, IMPORT_M2M_MODEL, IMPORT_QWEN_MODEL, IMPORT_QWEN_LIGHT_MODEL, IMPORT_VOSK_MODEL
    }

    private final ActivityResultLauncher<Intent> videoPicker = documentLauncher(DocumentRequest.OPEN_VIDEO);
    private final ActivityResultLauncher<Intent> subtitlePicker = documentLauncher(DocumentRequest.OPEN_SUBTITLES);
    private final ActivityResultLauncher<Intent> transcriptExporter = documentLauncher(DocumentRequest.EXPORT_TRANSCRIPT);
    private final ActivityResultLauncher<Intent> modelExporter = documentLauncher(DocumentRequest.EXPORT_MODEL);
    private final ActivityResultLauncher<Intent> m2mPicker = documentLauncher(DocumentRequest.IMPORT_M2M_MODEL);
    private final ActivityResultLauncher<Intent> qwenPicker = documentLauncher(DocumentRequest.IMPORT_QWEN_MODEL);
    private final ActivityResultLauncher<Intent> voskPicker = documentLauncher(DocumentRequest.IMPORT_VOSK_MODEL);
    private String pendingTranscriptExport;
    private String pendingVoskImport;
    private final ActivityResultLauncher<Intent> qwenLightPicker = documentLauncher(DocumentRequest.IMPORT_QWEN_LIGHT_MODEL);
    private String pendingModelExport;
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
    private DownloadViewModel downloadState;

    public static final class DownloadViewModel extends ViewModel {
        private VideoDownloadFlow detachedFlow;
        private TdJsonTelegramSessionClient detachedTelegram;

        @Override
        protected void onCleared() {
            if (detachedFlow != null) detachedFlow.close();
            if (detachedTelegram != null) detachedTelegram.closeTransport();
        }
    }

    private TelegramSessionClient telegramSession;
    private TelegramAuthorizationFlow.State lastTelegramState;
    private Uri currentVideo;
    private String currentVideoIdentity;
    private String currentDurationRevision;
    private long savedTranscriptVersion = -1;
    private boolean destroyed;
    private boolean started;
    private AlertDialog transcriptReviewDialog;
    private final java.util.function.Consumer<String> workObserver = message -> {
        if (started && !destroyed) {
            updateControls(message);
            showTranscriptReview();
        }
        refreshModelsAfterWork();
        onStorageWorkChanged();
    };

    private void showTranscriptReview() {
        VideoSession.TranscriptReview review = session.transcriptReview;
        if (!hasVideo || !java.util.Objects.equals(currentVideo, session.videoUri())
                || review == null || transcriptReviewDialog != null) return;
        String[] rows = new String[review.conflicts.size()];
        boolean[] keep = new boolean[rows.length];
        for (int i = 0; i < rows.length; i++) {
            TranscriptCue old = review.conflicts.get(i);
            keep[i] = review.sameLanguage;
            StringBuilder replacement = new StringBuilder();
            for (TranscriptCue fresh : review.generated) {
                if (fresh.startMs >= old.endMs) break;
                if (!VideoSession.overlaps(old, fresh)) continue;
                if (replacement.length() > 0) replacement.append(' ');
                replacement.append(fresh.sourceText);
            }
            rows[i] = "Câu " + (i + 1) + " · " + (old.startMs / 1000) + " giây"
                    + "\nĐã sửa: " + old.sourceText
                    + (old.hasTranslation() ? "\nBản dịch cũ: " + old.targetText : "")
                    + "\nNhận dạng mới: " + (replacement.length() == 0 ? "Không có câu trùng mốc" : replacement);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(review.sameLanguage ? "Chọn câu đã sửa cần giữ" : "Duyệt transcript đổi ngôn ngữ")
                .setNegativeButton("Giữ bản hiện tại", (dialog, which) -> session.resolveTranscriptReview(review, null))
                .setPositiveButton("Áp dụng bản mới", (dialog, which) -> {
                    List<TranscriptCue> retained = new ArrayList<>();
                    for (int i = 0; i < keep.length; i++) {
                        if (keep[i]) retained.add(review.conflicts.get(i));
                    }
                    session.resolveTranscriptReview(review, retained);
                })
                .setOnCancelListener(dialog -> session.resolveTranscriptReview(review, null));
        if (review.sameLanguage) {
            builder.setMultiChoiceItems(rows, keep, (dialog, index, checked) -> keep[index] = checked);
        } else {
            builder.setItems(rows, (dialog, index) -> {});
        }
        AlertDialog dialog = builder.create();
        transcriptReviewDialog = dialog;
        dialog.setOnDismissListener(ignored -> transcriptReviewDialog = null);
        dialog.show();
        if (!review.sameLanguage) {
            dialog.getListView().setOnItemClickListener(null);
        }
    }
    private boolean storageWorkBusy;
    private void onStorageWorkChanged() {
        if (storageWorkBusy && !session.busy && storagePage != null && settingsScreen == storagePage) {
            refreshVisibleStorage();
            work.models.refreshTranslations();
        }
        storageWorkBusy = session.busy;
    }

    private final TranslationOptionsDialog translationOptionsDialog = new TranslationOptionsDialog(this);
    private final Runnable modelObserver = () -> {
        renderModels();
        translationOptionsDialog.refreshModelStatus();
        if (!work.models.pending()) refreshVisibleStorage();
    };
    private int openRequest;
    private CueSpeech speech;
    private ExoPlayer player;
    private AlertDialog sentenceSplitDialog;
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
    private Button statusDetails;
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
    private TextView progressLabel;
    private TextView missingTranslationNotice, transcriptTranslationStatus;
    private View centerPlaybackControls, playbackControlsScrim;
    private int selectedTranscriptRow = -1;
    private long skippedTranslationVersion = -1;
    private Button repairMissingTranslation, skipMissingTranslation;
    private Button primary, cancel, transcript, subtitlesButton, speechButton, followButton;
    private Button transcriptMissing;
    private LinearLayout transcriptToolbar;
    private ListView cueList;
    private CueAdapter cueAdapter;
    private boolean follow = true, fullscreen;
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
    private boolean mlKitExpanded;
    private boolean otherTranslationLanguagesExpanded;

    private enum ModelTab {
        RECOGNITION, TRANSLATION, VOICE
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
        if (state != null) pendingVoskImport = state.getString("pendingVoskImport");
        if (state != null) pendingModelExport = state.getString("pendingModelExport");
        orientation = getResources().getConfiguration().orientation;
        sessionStore = new PlaybackSessionStore(this);
        work = VideoWork.obtain(this);
        session = work.session;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderers).build();
        videoStore = new SavedVideoStore(this);
        downloadState = new ViewModelProvider(this).get(DownloadViewModel.class);
        videoDownloads = downloadState.detachedFlow == null
                ? new VideoDownloadFlow(this, videoStore) : downloadState.detachedFlow;
        downloadState.detachedFlow = null;
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
            public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                fitPortraitVideo();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                showPlaybackControls();
                sync();
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) session.prioritizeContext(newPosition.positionMs);
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
        TdJsonTelegramSessionClient retainedTelegram = downloadState.detachedTelegram;
        downloadState.detachedTelegram = null;
        if (retainedTelegram != null) startTelegramSession(retainedTelegram, true);
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
        Uri media = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
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
        ViewCompat.setOnApplyWindowInsetsListener(root, MainActivity::applySystemInsets);
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
                        .setOnMenuItemClickListener(item -> { pick(subtitlePicker, "*/*"); return true; });
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
        playbackArea.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> fitPortraitVideo());
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
                () -> telegramContent().input(), this::resumeRecentVideo, this::removeRecentHistory,
                session::lastQwenRun);
        home.setPerformanceSamplingAllowed(started);
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
        centerPlaybackControls = view.findViewById(androidx.media3.ui.R.id.exo_center_controls);
        playbackControlsScrim = view.findViewById(androidx.media3.ui.R.id.exo_controls_background);
        view.setOnClickListener(tapped -> {
            if (keepsPlaybackBarVisible()) showPlaybackControls();
        });
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
                if (visibility == View.VISIBLE) setCenterControlsVisible(true);
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
        LinearLayout feedbackContent = column();
        LinearLayout.LayoutParams feedbackParams = new LinearLayout.LayoutParams(0, -2, 1);
        feedbackParams.setMarginEnd(dp(PlayerAppearance.CONTROL_GAP_DP));
        feedback.addView(feedbackContent, feedbackParams);
        progressLabel = new TextView(this);
        progressLabel.setTextSize(settings.textSize(12));
        progressLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedbackContent.addView(progressLabel, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setTextSize(settings.textSize(12));
        feedbackContent.addView(status, new LinearLayout.LayoutParams(-1, -2));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(4));
        progressParams.topMargin = dp(6);
        feedbackContent.addView(progress, progressParams);
        manageStorageStatus = iconButton(feedback, R.drawable.ic_control_info, "Dữ liệu và bộ nhớ",
                false, this::openStorageFromDownload);
        manageStorageStatus.setVisibility(View.GONE);
        statusDetails = compactButton(feedback, "Chi tiết", () -> {
            String message = status.getText().toString();
            AlertDialog.Builder details = new PlayerAppearance.DialogBuilder(this, settings)
                    .setTitle("Trạng thái xử lý").setMessage(message).setNegativeButton("Đóng", null);
            if (StorageSpace.isLowSpace(message)) {
                details.setPositiveButton("Quản lý bộ nhớ", (dialog, which) -> openStorageFromDownload());
            }
            showSheet(details);
        });
        statusDetails.setVisibility(View.GONE);
        dismissStatus = iconButton(feedback, R.drawable.ic_control_cancel, "Đóng thông báo",
                false, () -> showStatus(""));
        cancel = compactButton(feedback, "Hủy", this::cancelVideoTask);
        cancel.setMinWidth(dp(48));
        cancel.setMinimumWidth(dp(48));
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
        repairMissingTranslation = button(actionButtons, "Dịch tiếp", this::retryMissingTranslations);
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

    private void fitPortraitVideo() {
        if (surface == null || transcriptPanel == null) return;
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        androidx.media3.common.VideoSize video = player.getVideoSize();
        int height = wide ? -1 : 0;
        float weight = 2;
        if (!wide && hasVideo && !fullscreen && orientation == Configuration.ORIENTATION_PORTRAIT
                && transcriptPanel.getVisibility() != View.VISIBLE
                && video.width > 0 && video.height > 0) {
            float aspect = video.width * video.pixelWidthHeightRatio / video.height;
            if (aspect > 1f) {
                int desired = Math.round(playbackArea.getWidth() / aspect) + dp(104);
                if (playbackArea.getWidth() > 0 && desired < playbackArea.getHeight()) {
                    height = desired;
                    weight = 0;
                }
            }
        }
        playbackArea.setGravity(weight == 0 ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        int width = wide ? 0 : -1;
        if (surface.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams current = (LinearLayout.LayoutParams) surface.getLayoutParams();
            if (current.width == width && current.height == height && current.weight == weight) return;
        }
        surface.setLayoutParams(new LinearLayout.LayoutParams(width, height, weight));
    }


    private void updatePlaybackLayout() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean compactFooter = landscape && wide;
        playbackArea.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        fitPortraitVideo();
        transcriptPanel.setLayoutParams(new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1));
        playbackFooter.setOrientation(compactFooter ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        playbackFooter.setGravity(Gravity.CENTER_VERTICAL);
        feedback.setLayoutParams(new LinearLayout.LayoutParams(compactFooter ? 0 : -1, -2, compactFooter ? 1 : 0));
        actions.setLayoutParams(new LinearLayout.LayoutParams(compactFooter ? -2 : -1, -2));
        shortcuts.setLayoutParams(new LinearLayout.LayoutParams(compactFooter ? 0 : -1, -2, compactFooter ? 2 : 0));
        status.setSingleLine(landscape);
        status.setEllipsize(landscape ? TextUtils.TruncateAt.END : null);
        playerView.setControllerHideOnTouch((landscape || fullscreen) && !transcriptPanel.isShown());
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

    LinearLayout column() {
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

    private Button settingsActionButton(LinearLayout form, CharSequence label, Runnable action) {
        Button button = compactButton(form, label, action);
        button.setGravity(Gravity.CENTER);
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
            drawable.setTintList(PlayerAppearance.iconRole(icon).tint(this));
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
        PlayerAppearance.setLeadingIcon(button, icon, PlayerAppearance.iconRole(icon).tint(this), 0);
        button.setCompoundDrawablePadding(dp(8));
    }

    private void describeControl(Button button, String description) {
        button.setContentDescription(description);
        button.setTooltipText(description);
    }

    private void updateModeIcon(Button button, int icon, String label, CueTimeline.Mode mode) {
        setControlIcon(button, icon, settings.textSize(20));
        PlayerAppearance.IconRole role = icon == R.drawable.ic_control_captions && mode != CueTimeline.Mode.OFF
                ? PlayerAppearance.IconRole.TRANSCRIPT : PlayerAppearance.IconRole.NEUTRAL;
        button.setCompoundDrawableTintList(role.tint(this));
        button.setSelected(button.isEnabled() && mode != CueTimeline.Mode.OFF);
        button.setText(label + " · " + MODES.get(button.isEnabled() ? mode.ordinal() : 0));
        describeControl(button, label + ": " + (button.isEnabled() ? MODES.get(mode.ordinal())
                : "chưa có transcript"));
    }

    private void setControlIcon(Button button, int icon, int size) {
        android.graphics.drawable.Drawable drawable = button.getContext().getDrawable(icon).mutate();
        drawable.setBounds(0, 0, dp(size), dp(size));
        button.setCompoundDrawableTintList(PlayerAppearance.iconRole(icon).tint(this));
        button.setCompoundDrawablesRelative(null, drawable, null, null);
    }

    private void setTranscriptVisible(boolean visible) {
        transcriptPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        playerView.setControllerHideOnTouch(!visible && (fullscreen || orientation == Configuration.ORIENTATION_LANDSCAPE));
        updateTranslationNoticeVisibility();
        if (visible) showPlaybackControls();
        fitPortraitVideo();
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
        if (session.runningTask != null) {
            VideoSession.TaskProgress progress = session.runningTask == VideoSession.Task.TRANSLATION
                    ? session.translationProgress : null;
            String summary = progress == null ? work.status() : progress.label();
            if (progress == null) {
                progress = new VideoSession.TaskProgress(null, session.progressCompleted,
                        session.progressTotal, null);
            }
            Runnable showDetails = canResumeVideo(session.videoUri()) ? this::showRunningVideoTask : null;
            home.bindTask(session.runningTask.runningLabel, homeTaskName(session.videoUri(), session.videoName),
                    "Xem trạng thái", showDetails, true, summary, progress, this::cancelVideoTask);
            return;
        }
        if (videoDownloads.isDownloading()) {
            home.bindTask(videoDownloads.activeLabel(), videoDownloads.activeTitle(),
                    "Xem tiến độ", videoDownloads::show);
            return;
        }
        for (VideoWork.Result result : work.unreadResults()) {
            if (canResumeVideo(result.video)) {
                String title = result.stopped ? "Đã dừng"
                        : result.task == VideoSession.Task.TRANSLATION ? "Đã dịch xong" : result.task.readyLabel;
                home.bindTask(title, homeTaskName(result.video, result.name),
                        result.stopped ? "Xem trạng thái" : "Xem kết quả", () -> openTaskResult(result),
                        result.stopped, homeTaskResultSummary(result), null, null);
                return;
            }
        }
        if (session.failedTask() != null && canResumeVideo(session.videoUri())) {
            home.bindTask("Thất bại", homeTaskName(session.videoUri(), session.videoName),
                    "Xem trạng thái", this::showTaskVideo, true);
            return;
        }
        home.bindTask(sessionStore.hasOpenedVideo() ? null
                : "Mở video để tạo transcript, dịch và bật phụ đề.", null, null, null);
    }

    private void cancelVideoTask() {
        if (session.busy) work.cancelWork("Đã hủy tác vụ.");
    }

    private String homeTaskName(Uri video, String name) {
        return TextUtils.isEmpty(name) ? PlaybackSessionStore.fallbackName(video) : name;
    }

    private String homeTaskResultSummary(VideoWork.Result result) {
        String count = result.countLabel();
        if (result.finishedAt <= 0 || result.stopped) return count;
        CharSequence time = android.text.format.DateUtils.getRelativeTimeSpanString(
                result.finishedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS);
        return count == null ? time.toString() : count + " · " + time;
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
            if (restored == null || restored.identity == null || (!result.stopped && restored.cues.isEmpty())
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
            if (result.stopped) {
                work.markResultViewed(result);
            } else {
                showResultPanel(result);
            }
        });
    }

    private void showResultPanel(VideoWork.Result result) {
        boolean translation = result.task == VideoSession.Task.TRANSLATION;
        if (session.cues.isEmpty() || (translation && session.targetLanguage == null)) {
            work.markResultViewed(result);
            showStatus("Không thể mở kết quả đã lưu.");
            return;
        }
        showCueTranscript();
        work.markResultViewed(result);
    }

    void showCueTranscript() {
        if (fullscreen) setFullscreen(false);
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
        pick(videoPicker, "video/*");
    }

    private void setFullscreen(boolean enabled) {
        fullscreen = enabled;
        playerView.setFullscreenButtonState(enabled);
        fitPortraitVideo();
        playerView.setControllerHideOnTouch(!keepsPlaybackBarVisible());
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

    LinearLayout row(LinearLayout panel) {
        LinearLayout row = new LinearLayout(this);
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    Button button(LinearLayout row, String label, Runnable action) {
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

    private ActivityResultLauncher<Intent> documentLauncher(DocumentRequest request) {
        return getActivityResultRegistry().register("document:" + request.name(), this,
                new ActivityResultContracts.StartActivityForResult(),
                result -> onDocumentResult(request, result));
    }

    private void pick(ActivityResultLauncher<Intent> launcher, String type) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(type);
        launcher.launch(intent);
    }

    private ModelExport modelExport(String model) throws java.io.IOException {
        if ("qwen-light".equals(model)) return work.models.contextModel(true).exportModel();
        if ("qwen".equals(model)) return work.models.contextModel(false).exportModel();
        if ("m2m100".equals(model)) return work.models.m2m.exportModel();
        return work.models.vosk.exportModel(model);
    }

    private void pickModelExport(String model) {
        if (session.busy || work.models.pending()) return;
        try {
            ModelExport export = modelExport(model);
            pendingModelExport = model;
            modelExporter.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(export.mimeType).putExtra(Intent.EXTRA_TITLE, export.name));
        } catch (java.io.IOException | android.content.ActivityNotFoundException error) {
            pendingModelExport = null;
            storageMessage("Không mở được màn hình xuất model.");
        }
    }

    private void writeModelExport(String model, Uri destination) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        AlertDialog progress = new PlayerAppearance.DialogBuilder(this, settings)
                .setTitle("Xuất model").setMessage("Đang chuẩn bị tệp…")
                .setCancelable(false).setNegativeButton("Dừng", null).create();
        showSheet(progress);
        Button stop = progress.getButton(AlertDialog.BUTTON_NEGATIVE);
        stop.setOnClickListener(view -> {
            cancelled.set(true);
            stop.setEnabled(false);
            progress.setMessage("Đang dừng xuất model…");
        });
        storageIo.execute(() -> {
            String message;
            try {
                if (session.busy || work.models.pending()) throw new java.io.IOException("Model đang được sử dụng.");
                ModelExport export = modelExport(model);
                long total = export.bytes();
                try (java.io.OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new java.io.IOException("Không mở được tệp đích.");
                    export.write(output, cancelled::get, written -> main.post(() -> {
                        if (progress.isShowing() && !cancelled.get()) {
                            progress.setMessage(storageSize(written) + " / " + storageSize(total));
                        }
                    }));
                }
                if (cancelled.get()) throw new java.io.InterruptedIOException();
                message = "Đã xuất " + export.name + ".";
            } catch (java.io.IOException | RuntimeException error) {
                try {
                    android.provider.DocumentsContract.deleteDocument(getContentResolver(), destination);
                } catch (Exception ignored) {
                    // Some document providers do not support deleting an incomplete export.
                }
                message = cancelled.get() ? "Đã dừng xuất model." : "Không xuất được model.";
            }
            String result = message;
            main.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                storageMessage(result);
            });
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pendingTranscriptExport", pendingTranscriptExport);
        state.putString("pendingVoskImport", pendingVoskImport);
        state.putString("pendingModelExport", pendingModelExport);
        if (telegramContent != null) state.putBundle("telegramContent", telegramContent.saveState());
    }

    private void onDocumentResult(DocumentRequest request, ActivityResult activityResult) {
        int result = activityResult.getResultCode();
        Intent data = activityResult.getData();
        if (request == DocumentRequest.EXPORT_MODEL) {
            String model = pendingModelExport;
            pendingModelExport = null;
            if (model != null && result == RESULT_OK && data != null && data.getData() != null) {
                writeModelExport(model, data.getData());
            }
            return;
        }
        if (request == DocumentRequest.IMPORT_QWEN_MODEL || request == DocumentRequest.IMPORT_QWEN_LIGHT_MODEL
                || request == DocumentRequest.IMPORT_VOSK_MODEL) {
            String language = pendingVoskImport;
            pendingVoskImport = null;
            if (result != RESULT_OK || data == null || data.getData() == null || session.busy) return;
            if (request != DocumentRequest.IMPORT_VOSK_MODEL) {
                work.models.importContextModel(work.models.contextModel(request == DocumentRequest.IMPORT_QWEN_LIGHT_MODEL),
                        getContentResolver(), data.getData());
            } else if (language != null) {
                work.models.importVoskModel(language, getContentResolver(), data.getData());
            }
            return;
        }
        if (request == DocumentRequest.IMPORT_M2M_MODEL) {
            if (result != RESULT_OK || data == null || session.busy) return;
            List<Uri> files = new ArrayList<>();
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) files.add(uri);
                }
            } else if (data.getData() != null) {
                files.add(data.getData());
            }
            work.models.importM2mFiles(getContentResolver(), files);
            return;
        }
        if (request == DocumentRequest.EXPORT_TRANSCRIPT) {
            finishTranscriptExport(result == RESULT_OK && data != null ? data.getData() : null);
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == DocumentRequest.OPEN_VIDEO) {
            int requestId = ++openRequest;
            player.pause();
            savePlaybackSession();
            sessionStore.load(uri, restored -> {
                if (destroyed || requestId != openRequest) return;
                savePlaybackSession();
                openVideo(uri, restored, true, data.getFlags());
            });
        } else if (request == DocumentRequest.OPEN_SUBTITLES && hasVideo) {
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
        activeRow = -1;
        selectedTranscriptRow = -1;
        setTranscriptVisible(false);
        currentVideo = uri;
        currentVideoIdentity = restored == null ? null : restored.identity;
        currentDurationRevision = restored == null ? null : restored.durationRevision;
        skippedTranslationVersion = -1;
        player.setPlayWhenReady(false);
        player.setMediaItem(MediaItem.fromUri(uri), restored == null ? 0L : restored.position);
        if (resetSession) {
            work.playbackHistoryCleared = false;
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
        if (currentVideo == null || currentVideo.equals(work.transcriptMutation())
                || session.hasTranscriptPreview()) return;
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

    Spinner selection(LinearLayout form, String label, List<String> options, int selected) {
        return selection(form, label, options, selected, null);
    }

    Spinner selection(LinearLayout form, String label, List<String> options, int selected,
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

    Spinner languages(LinearLayout form, String label, List<String> codes, String current) {
        List<String> names = new ArrayList<>();
        for (String code : codes) names.add(languageName(code));
        return selection(form, label, names, codes.indexOf(current));
    }

    LinearLayout form() {
        LinearLayout form = column();
        form.setPadding(dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.CONTROL_GAP_DP), dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.CONTROL_GAP_DP));
        return form;
    }

    AlertDialog.Builder sheet(String title, LinearLayout form) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        return new PlayerAppearance.DialogBuilder(this, settings).setTitle(title).setView(scroll);
    }

    void showSheet(AlertDialog.Builder builder) {
        showSheet(builder.create());
    }

    void showSheet(AlertDialog dialog) {
        PlayerAppearance.showDialog(dialog, settings);
        dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.getWindow().setLayout(-1, -2);
    }

    List<String> targetLanguages() {
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
        chooseTranslationLanguage();
    }

    private void retryMissingTranslations() {
        if (session.busy || !session.hasTranslationToRetry()) return;
        skippedTranslationVersion = -1;
        updateControls(null);
        showMissingTranslations(true);
    }

    private void showMissingTranslations(boolean openSingle) {
        List<TranscriptCue> missing = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (TranscriptCue cue : session.cues) {
            if (!session.needsTranslation(cue)) continue;
            missing.add(cue);
            labels.add(TranscriptParser.formatTime(cue.startMs) + (cue.hasTranslation() ? " · Cần kiểm tra\n" : " · Chưa dịch\n")
                    + cue.sourceText);
        }
        if (session.failedTask() == VideoSession.Task.TRANSLATION
                && (missing.isEmpty() || !java.util.Objects.equals(session.targetLanguage, session.retryTranslationTarget()))) {
            showTranslationOptions(null, null, true);
            return;
        }
        if (missing.isEmpty()) return;
        boolean canUseContext = "vi".equals(session.retryTranslationTarget());
        if (openSingle && missing.size() == 1 && !canUseContext
                && session.failedTask() != VideoSession.Task.TRANSLATION) {
            editCueTranslation(missing.get(0));
            return;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Còn " + missing.size() + " câu cần xử lý")
                .setItems(labels.toArray(new String[0]), (window, index) -> editCueTranslation(missing.get(index)))
                .setNegativeButton("Để sau", null)
                .setPositiveButton("Dịch tiếp", (window, which) -> showTranslationOptions(null, null, true));
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

        AlertDialog dialog = sheet("Xử lý câu · " + TranscriptParser.formatTime(cue.startMs), form)
                .setNegativeButton("Để sau", null).create();
        String action = session.translationModelUnavailable ? "Tải model và dịch" : "Dịch câu này";
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
            String sourceCode = codes.get(source.getSelectedItemPosition());
            boolean useM2m = getPreferences(MODE_PRIVATE).getBoolean("sentence_engine_m2m", false);
            if (useM2m && (!work.models.m2m.installed() || work.models.m2mDownloading)) {
                if (ContextModelStore.supported() && !work.models.m2mDownloading) work.models.toggleM2mDownload();
                original.setError(ContextModelStore.supported()
                        ? "Hãy đợi tải xong M2M100 rồi bấm dịch lại."
                        : "M2M100 cần Android ARM64.");
                return;
            }
            dialog.dismiss();
            session.repairTranslation(cue, original.getText().toString().trim(), sourceCode, useM2m);
        });
        LinearLayout saveActions = row(form);
        if ("vi".equals(session.targetLanguage)) {
            button(row(form), "Dịch lại theo ngữ cảnh", () -> {
                if (!session.canEditTranslation(cue)) return;
                String draft = original.getText().toString().trim();
                if (draft.isEmpty()) {
                    original.setError("Nhập lời gốc.");
                    return;
                }
                dialog.dismiss();
                showTranslationOptions(cue, draft, false);
            });
        }
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
        showTranslationOptions(null, null, false);
    }

    private void showTranslationOptions(TranscriptCue selected, String editedSource, boolean retry) {
        translationOptionsDialog.show(selected, editedSource, retry, session, work.models, settings, currentVideo);
    }

    boolean isCurrentTranslationVideo(Uri video) {
        return !destroyed && !isFinishing() && java.util.Objects.equals(video, currentVideo);
    }

    long translationPlaybackPosition() {
        return player.getCurrentPosition();
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
        List<String> modelKeys = new ArrayList<>();
        if (!session.cues.isEmpty()) {
            processingLabel(form, transcriptOriginLabel());
            processingLabel(form, "Nhận dạng lại sẽ thay thế transcript và bản dịch hiện tại.");
        }
        String action = session.cues.isEmpty() ? "Tạo transcript" : "Nhận dạng lại";
        AlertDialog dialog = sheet(action, form).setNegativeButton("Hủy", null)
                .setPositiveButton(action, null).create();
        showSheet(dialog);

        Button generate = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        VoskModelStore vosk = work.models.vosk;
        Runnable updateModels = () -> {
            String selected = SOURCE_LANGUAGES.get(language.getSelectedItemPosition());
            String large = VoskModelStore.largeModelKey(selected);
            modelKeys.clear();
            modelKeys.addAll(vosk.availableModelKeys(selected));
            modelOptions.removeAllViews();
            generate.setEnabled(!modelKeys.isEmpty());
            if (modelKeys.isEmpty()) {
                processingLabel(modelOptions, "Chưa cài model nhận dạng cho ngôn ngữ này.");
                compactButton(modelOptions, "Quản lý model", () -> {
                    dialog.dismiss();
                    showModels(ModelTab.RECOGNITION, () -> chooseTrack(videoOnly));
                });
                return;
            }
            List<String> modelLabels = new ArrayList<>();
            for (String key : modelKeys) {
                modelLabels.add(key.equals(large) ? "Vosk Large" : "Vosk Small");
            }
            modelLabels.set(0, modelLabels.get(0) + " (mặc định)");
            Spinner model = selection(modelOptions, "Model cho lượt mới", modelLabels, 0);
            if (large != null && !modelKeys.contains(large)) {
                processingLabel(modelOptions, "Vosk Large chưa cài");
            }
            generate.setOnClickListener(view -> {
                int modelPosition = model.getSelectedItemPosition();
                if (modelPosition < 0) return;
                String recognitionModel = modelKeys.get(modelPosition);
                if (!vosk.isDownloaded(recognitionModel) && !vosk.isBundled(recognitionModel)) {
                    dialog.dismiss();
                    chooseTrack(videoOnly);
                    return;
                }
                if (!videoOnly) {
                    settings.sourceLanguage = selected;
                    settings.save(this);
                }
                dialog.dismiss();
                session.generate(tracks.get(track.getSelectedItemPosition()), selected, recognitionModel);
            });
        };
        language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateModels.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
                modelOptions.removeAllViews();
                generate.setEnabled(false);
            }
        });
        updateModels.run();
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

    private static WindowInsetsCompat applySystemInsets(View view, WindowInsetsCompat insets) {
        Insets visible = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
        view.setPadding(visible.left, visible.top, visible.right, visible.bottom);
        return insets;
    }

    private void applySystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 35) applyLegacySystemBarColors();
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(lightTheme);
        controller.setAppearanceLightNavigationBars(lightTheme);
        if (fullscreen && settingsScreen == null) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    @SuppressWarnings("deprecation") // Before edge-to-edge enforcement, the window owns the bar colors.
    private void applyLegacySystemBarColors() {
        getWindow().setStatusBarColor(PlayerAppearance.color(this, android.R.attr.statusBarColor));
        getWindow().setNavigationBarColor(PlayerAppearance.color(this, android.R.attr.navigationBarColor));
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
        home.setPerformanceSamplingAllowed(started);
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
        ViewCompat.setOnApplyWindowInsetsListener(page, MainActivity::applySystemInsets);
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
        home.setPerformanceSamplingAllowed(false);
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
                        + "2. Chọn Tạo transcript và chọn ngôn ngữ lời thoại. Chọn track âm thanh nếu video có nhiều track. "
                        + "Nếu đã có phụ đề, chọn ⋮ → Nhập phụ đề (SRT/VTT).\n"
                        + "3. Chọn Dịch → Theo câu hoặc Ngữ cảnh, rồi chọn Model dịch ngay trong hộp thoại.\n"
                        + "4. Chạm nút Phụ đề đến khi hiện Dịch. Chạm vào một câu trong transcript để tua đến câu đó.", !modelsExpanded);
        guideSection(form, "Mở và lưu video",
                "Mở video → Chọn tệp trên thiết bị để phát tệp có sẵn. Dùng Tải video từ URL để nhập liên kết. "
                        + "Nếu liên kết dẫn đến trang web, thử Tìm video trên trang.\n\n"
                        + "Trong Telegram, dùng Chia sẻ → AI Player để mở tệp video hoặc liên kết bài đăng. "
                        + "Đăng nhập Telegram nếu ứng dụng yêu cầu.\n\n"
                        + "Mở lại video từ danh sách gần đây trên màn hình chính hoặc tìm video đã tải trong Video đã lưu.", false);
        guideSection(form, "Phụ đề và phát âm",
                "Chạm nút Phụ đề hoặc Phát âm để chuyển giữa Tắt, Gốc và Dịch. "
                        + "Cả hai bám theo mốc thời gian của từng câu. Transcript hiển thị lời gốc và bản dịch khi câu đã được dịch.\n\n"
                        + "Vào Cài đặt → Ngôn ngữ và phụ đề để đổi ngôn ngữ, cỡ chữ, màu chữ và màu nền phụ đề. "
                        + "Vào Cài đặt → Model và giọng đọc → Giọng đọc, chọn ngôn ngữ rồi dùng Đổi giọng hoặc Nghe thử.\n\n"
                        + "Trong menu ⋮ của trình phát, dùng Xuất tệp hoặc Chia sẻ để lưu hay gửi lời gốc, bản dịch hoặc nội dung song ngữ.", false);
        guideSection(form, "Dịch ngoại tuyến",
                "Theo câu: chọn ML Kit hoặc M2M100 418M INT8. ML Kit dùng tiếng Anh làm trung gian khi cả hai ngôn ngữ đều khác tiếng Anh. "
                        + "Mở Model và giọng đọc → Dịch offline → Dịch theo câu "
                        + "để kiểm tra M2M100 và Gói ngôn ngữ ML Kit.\n\n"
                        + "Ngữ cảnh: chọn Qwen3-4B Q4_K_M hoặc Qwen3-1.7B Q4_K_M để dịch sang tiếng Việt dựa trên transcript của cả video. "
                        + "Có thể dịch Theo câu sang tiếng Việt trước, rồi chọn Dịch lại → Ngữ cảnh → Rà soát bản dịch tự động đã có "
                        + "để hiệu đính và giữ các câu đã sửa tay. Nếu cần thống nhất tên riêng, thuật ngữ hoặc xưng hô, "
                        + "chọn + Thêm quy ước dịch, nhập mỗi quy ước một dòng rồi bấm Lưu.\n\n"
                        + "Hai chế độ chạy trên thiết bị sau khi đã cài model cần thiết.", false);
        LinearLayout models = guideSection(form, "Tải, cài lại và xuất model",
                "Trong Cài đặt → Model và giọng đọc, mở Nhận dạng để quản lý Vosk hoặc Dịch offline để quản lý M2M100, Qwen và ML Kit.\n\n"
                        + "Khi tạo transcript, Model cho lượt mới chọn Vosk Large nếu đã cài cho ngôn ngữ lời thoại. "
                        + "Nếu chỉ có Vosk Small, ứng dụng chọn Small. Có thể đổi model cho từng lượt. "
                        + "Dùng Nhận dạng lại để tạo transcript mới bằng model vừa chọn.\n\n"
                        + "Với Vosk, M2M100 và Qwen, dùng Nhập tệp model để cài lần đầu. "
                        + "Với M2M100, mở ⋮ để tìm mục này. Khi đã cài, chọn ⋮ → Cài lại từ tệp hoặc ⋮ → Xuất model.\n\n"
                        + "Vosk và M2M100 xuất tệp ZIP. Qwen xuất tệp GGUF. Có thể dùng tệp đã xuất để cài lại trên cả hai bản ứng dụng. "
                        + "Model ML Kit được tải lại từ tab Dịch offline.", modelsExpanded);
        compactButton(models, "Mở Model và giọng đọc", () -> {
            showModels(ModelTab.TRANSLATION, () -> showGuide(true));
        });
        guideSection(form, "Theo dõi tác vụ",
                "Trên màn hình chính, Tác vụ gần đây hiển thị tiến độ tạo transcript hoặc dịch. "
                        + "Chạm biểu tượng Xem trạng thái khi có để mở chi tiết, hoặc chọn Hủy để dừng. "
                        + "Khi tác vụ hoàn tất, chọn Xem kết quả để mở video cùng kết quả đã lưu.", false);
        guideSection(form, "Hiệu năng thiết bị và Qwen",
                "Ở cuối màn hình chính, Hiệu năng thiết bị hiển thị mức dùng CPU của AI Player, tên GPU "
                        + "và RAM khả dụng. Mở Chi tiết để xem biểu đồ CPU và RAM trong 60 giây, "
                        + "RAM của ứng dụng và thông tin lượt dịch Qwen gần nhất trong phiên.\n\n"
                        + "Vào Cài đặt → Model và giọng đọc → Dịch offline → Dịch theo ngữ cảnh → Ưu tiên xử lý Qwen. "
                        + "Tự động và CPU hiện dùng CPU. Ưu tiên GPU cần bản APK có Vulkan và thiết bị tương thích. "
                        + "Thay đổi có hiệu lực từ lượt Qwen tiếp theo. Nếu GPU không khả dụng hoặc khởi tạo thất bại, Qwen dùng CPU. "
                        + "Xem tiến độ dịch để biết bộ xử lý thực tế.", false);
        guideSection(form, "Xử lý sự cố",
                "Không mở được video? Thử mở lại tệp từ thiết bị, hoặc kiểm tra kết nối và quyền truy cập liên kết.\n\n"
                        + "Không thấy phụ đề? Kiểm tra video đã có transcript hoặc phụ đề nhập, "
                        + "sau đó chuyển Phụ đề sang Gốc hoặc Dịch.\n\n"
                        + "Nếu còn câu chưa dịch hoặc cần rà soát, "
                        + "chọn Dịch tiếp "
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
        Button storage = settingsEntry(form, R.drawable.ic_control_info, "Dữ liệu và bộ nhớ",
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

    String storageSize(long bytes) {
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
        settingsEntry(form, R.drawable.ic_control_delete, "Xóa lịch sử hoạt động",
                "Video gần đây · Liên kết Telegram đã mở", this::showClearActivityHistory);
        LinearLayout content = column();
        form.addView(content, new LinearLayout.LayoutParams(-1, -2));
        TextView measurement = new TextView(this);
        measurement.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        measurement.setVisibility(View.GONE);
        form.addView(measurement);
        String refreshLabel = "Đo lại";
        Button refresh = settingsActionButton(form, refreshLabel, () -> {
            refreshVisibleStorage();
            work.models.refreshTranslations();
        });
        measureStorage = () -> {
            int request = ++storageMeasurement;
            refresh.setEnabled(false);
            refresh.setText("Đang đo…");
            loadStorage((usage, entries) -> {
                if (settingsScreen != page || request != storageMeasurement) return;
                content.removeAllViews();
                renderStorage(content, usage, entries.size(), page);
                String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(usage.measuredAt));
                measurement.setText("Cập nhật lúc " + time);
                measurement.setVisibility(View.VISIBLE);
                refresh.setText(refreshLabel);
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
        PlayerAppearance.IconRole[] roles = {PlayerAppearance.IconRole.VIDEO, PlayerAppearance.IconRole.TRANSCRIPT,
                PlayerAppearance.IconRole.MODEL, PlayerAppearance.IconRole.WARNING};
        int[] colors = new int[roles.length];
        for (int i = 0; i < roles.length; i++) colors[i] = roles[i].color(this);
        storageBar(form, usage.total(),
                new long[]{usage.videoBytes, usage.transcriptBytes, usage.modelBytes, usage.cacheBytes}, colors);
        storageEntry(form, R.drawable.ic_control_library, "Video đã lưu", usage.videos + " video · ",
                usage.videoBytes, usage.total(), roles[0], () -> showSavedVideos(true));
        storageEntry(form, R.drawable.ic_control_captions, "Transcript và bản dịch", transcriptCount + " video · ",
                usage.transcriptBytes, usage.total(), roles[1], this::showTranscriptStorage);
        storageEntry(form, R.drawable.ic_control_download, "Model nhận dạng", "", usage.modelBytes,
                usage.total(), roles[2], () -> showModels(ModelTab.RECOGNITION, this::showStorage));
        settingsEntry(form, R.drawable.ic_control_download, "Model dịch offline",
                noTranslationModels ? storageSize(0) : "Chưa đo được dung lượng",
                () -> showModels(ModelTab.TRANSLATION, this::showStorage));
        Button clear = storageEntry(form, R.drawable.ic_control_info, "Dọn bộ nhớ đệm", "",
                usage.cacheBytes, usage.total(), roles[3], () ->
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
                                long bytes, long total, PlayerAppearance.IconRole role, Runnable open) {
        double share = total == 0 ? 0 : bytes / (double) total;
        java.text.NumberFormat percent = java.text.NumberFormat.getPercentInstance();
        percent.setMaximumFractionDigits(1);
        String ratio = share > 0 && share < 0.001 ? "<" + percent.format(0.001) : percent.format(share);
        Button button = settingsEntry(form, icon, title,
                prefix + storageSize(bytes) + " · " + ratio, open);
        PlayerAppearance.setLeadingIcon(button, icon, role.tint(this), 0);
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
        icon.setImageTintList(PlayerAppearance.IconRole.TRANSCRIPT.tint(this));
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
        missing.setText("Câu thiếu bản dịch");
        missing.setButtonTintList(PlayerAppearance.controlIconTint(this));
        controls.addView(missing);
        ListView list = new ListView(this);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView empty = new TextView(this);
        empty.setPadding(dp(12), dp(12), dp(12), dp(12));
        empty.setText("Không còn câu thiếu bản dịch.");
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
        private final java.util.Set<Integer> expanded = new java.util.HashSet<>();

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
            summary.setText(translated + "/" + snapshot.cues.size() + " câu đã dịch"
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
            int index = indexes.get(position);
            boolean showFullText = expanded.contains(index);
            for (TextView preview : Arrays.asList(original, translation)) {
                preview.setSingleLine(!showFullText);
                preview.setMaxLines(showFullText ? Integer.MAX_VALUE : 1);
                preview.setEllipsize(showFullText ? null : TextUtils.TruncateAt.END);
            }
            body.setOnClickListener(v -> {
                if (showFullText) expanded.remove(index);
                else expanded.add(index);
                notifyDataSetChanged();
            });
            androidx.core.view.ViewCompat.replaceAccessibilityAction(body,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    showFullText ? "Thu gọn câu" : "Đọc đầy đủ câu",
                    (v, arguments) -> v.performClick());
            original.setText(cue.sourceText);
            original.setVisibility(selectedMode == 2 ? View.GONE : View.VISIBLE);
            translation.setText(cue.hasTranslation() ? cue.targetText : "Chưa có bản dịch");
            translation.setVisibility(selectedMode == 1 ? View.GONE : View.VISIBLE);
            Button edit = (Button) line.getChildAt(1);
            edit.setContentDescription("Sửa câu " + time);
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

        AlertDialog dialog = sheet("Sửa câu · " + TranscriptParser.formatTime(cue.startMs), fields)
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
            if (sourceChanged) {
                edited.sourceText = source.trim();
                edited.wordStarts = null;
                edited.sourceEdited = true;
            }
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

    private boolean checkTranscriptDeletion(Uri uri) {
        if (work.transcriptMutation() != null) {
            storageMessage("Đang cập nhật dữ liệu transcript. Hãy thử lại sau.");
            return false;
        }
        if (uri.equals(session.videoUri()) && session.busy) {
            storageMessage("Video này đang có tác vụ xử lý. Hãy đợi tác vụ kết thúc rồi thử lại.");
            return false;
        }
        return true;
    }

    private void showTranscriptActions(PlaybackSessionStore.StoredTranscript entry, View page) {
        List<CharSequence> labels = new ArrayList<>();
        labels.add("Xuất tệp");
        labels.add("Chia sẻ");
        String removeAll = entry.translated ? "Xóa transcript và bản dịch" : "Xóa transcript";
        if (entry.translated) labels.add(PlayerAppearance.dangerLabel(this, "Xóa bản dịch"));
        labels.add(PlayerAppearance.dangerLabel(this, removeAll));
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
                        if (!checkTranscriptDeletion(entry.snapshot.uri)) return;
                        boolean translationOnly = entry.translated && index == 2;
                        confirmTranscriptRemoval(entry, translationOnly,
                                translationOnly ? "Xóa bản dịch" : removeAll, page);
                    }
                })
                .setNegativeButton("Đóng", null).show();
    }

    private void showTranscriptExportOptions(PlaybackSessionStore.Snapshot snapshot, boolean share) {
        showTranscriptExportOptions(snapshot.name, snapshot.cues, share);
    }

    private void showTranscriptExportOptions(String videoName, List<TranscriptCue> cues, boolean share) {
        String action = share ? "Chia sẻ" : "Xuất tệp";
        int missing = 0;
        for (TranscriptCue cue : cues) {
            if (!cue.hasTranslation()) missing++;
        }
        boolean translated = missing < cues.size();
        LinearLayout options = form();
        Spinner content = selection(options, "Nội dung", translated
                ? java.util.Arrays.asList("Transcript gốc", "Bản dịch", "Song ngữ")
                : java.util.Collections.singletonList("Transcript gốc"), translated ? 2 : 0);
        Spinner format = selection(options, "Định dạng",
                java.util.Arrays.asList("TXT", "SRT"), 0);
        if (translated && missing > 0) {
            settingsHeading(options, "Còn " + missing + " câu chưa có bản dịch.");
        }
        showSheet(sheet(action, options).setNegativeButton("Hủy", null)
                .setPositiveButton(action, (dialog, which) -> prepareTranscriptExport(videoName, cues,
                        TranscriptExport.Content.values()[content.getSelectedItemPosition()],
                        TranscriptExport.Format.values()[format.getSelectedItemPosition()], share)));
    }

    private void prepareTranscriptExport(String videoName, List<TranscriptCue> cues,
                                         TranscriptExport.Content content,
                                         TranscriptExport.Format format, boolean share) {
        storageIo.execute(() -> {
            try {
                File file = TranscriptExport.stage(getCacheDir(), videoName, cues, content, format);
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
                            transcriptExporter.launch(create);
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
        ).sum() + entry.sentenceTranslationBytes : entry.bytes;
        new PlayerAppearance.DialogBuilder(this, settings)
                .positiveRole(PlayerAppearance.ButtonRole.CONFIRM_DELETE).setTitle(action + "?")
                .setMessage(entry.snapshot.name + "\nDung lượng dự kiến thu hồi · " + storageSize(estimate)
                        + (translationOnly ? "\nTranscript gốc sẽ được giữ." : ""))
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa", (dialog, which) -> {
                    Uri uri = entry.snapshot.uri;
                    if (!checkTranscriptDeletion(uri)) return;
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

    private String modelVoiceLanguage;
    private CueSpeech modelVoicePreview;

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
                + (voice.isNetworkConnectionRequired() ? " · Cần mạng" : " · Ngoại tuyến");
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
        processingLabel(controls, "Dịch offline theo câu hoặc theo ngữ cảnh");
        if (!session.cues.isEmpty()) {
            compactButton(controls, session.targetLanguage == null ? "Dịch" : "Dịch lại", () -> {
                dialog.dismiss();
                chooseTranslationLanguage();
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

    TextView processingLabel(LinearLayout form, String label) {
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
        class VoiceChoices {
            private final List<String> names = new ArrayList<>();
            private Spinner picker;
            private String pickerLanguage;

            String selectedVoice() {
                String code = selectedCode.get();
                if (!code.equals(pickerLanguage)) refresh();
                int position = picker.getSelectedItemPosition();
                if (position >= 0 && position < names.size()) return names.get(position);
                return videoOnly ? session.voices.get(voiceOverrideKey(code, translated))
                        : AppSettings.voice(MainActivity.this, code);
            }

            void refresh() {
                String code = selectedCode.get();
                CueSpeech catalog = !videoOnly && modelVoicePreview != null ? modelVoicePreview : speech;
                List<android.speech.tts.Voice> voices = catalog.voices(code);
                preview.stop();
                status.setText("");
                names.clear();
                List<String> labels = new ArrayList<>();
                if (videoOnly) {
                    names.add(null);
                    labels.add("Dùng mặc định · " + describeVoice(code, AppSettings.voice(MainActivity.this, code)));
                }
                names.add("");
                labels.add("Mặc định hệ thống");
                for (android.speech.tts.Voice voice : voices) {
                    names.add(voice.getName());
                    labels.add(voiceLabel(voice));
                }
                String saved = videoOnly ? session.voices.get(voiceOverrideKey(code, translated))
                        : AppSettings.voice(MainActivity.this, code);
                int selected = names.indexOf(saved);
                voiceOptions.removeAllViews();
                picker = selection(voiceOptions, "Giọng đọc", labels, selected >= 0 ? selected : names.indexOf(""));
                pickerLanguage = code;
                availability.setText(voices.isEmpty() ? "Android chưa cung cấp giọng cho ngôn ngữ này." : "");
                availability.setVisibility(voices.isEmpty() ? View.VISIBLE : View.GONE);
                systemSettings.setVisibility(videoOnly ? availability.getVisibility() : View.GONE);
            }
        }
        VoiceChoices choices = new VoiceChoices();
        choices.refresh();
        if (languages != null) languages.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                choices.refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        compactButton(controls, "Nghe thử", () -> {
            String code = selectedCode.get();
            String selected = choices.selectedVoice();
            status.setText("");
            preview.preview(voiceSample(code), code, settings.dubbingVolume / 100f,
                    selected == null ? AppSettings.voice(this, code) : selected);
        });
        AlertDialog dialog = sheet(videoOnly ? "Giọng đọc · " + languageName(language) : "Chọn giọng đọc", controls)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", (ignored, which) -> {
                    String code = selectedCode.get();
                    String selected = choices.selectedVoice();
                    String key = voiceOverrideKey(code, translated);
                    if (!videoOnly) {
                        AppSettings.saveVoice(this, code, selected);
                        modelVoiceLanguage = code;
                        renderModels();
                    }
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
        settingsParent = parent;
        modelVoiceLanguage = Locale.forLanguageTag(settings.targetLanguage).getLanguage();
        mlKitExpanded = false;
        otherTranslationLanguagesExpanded = false;

        RadioGroup tabs = new RadioGroup(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        controls.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        int recognitionId = addModelTab(tabs, "Nhận dạng");
        int translationId = addModelTab(tabs, "Dịch offline");
        int voiceId = addModelTab(tabs, "Giọng đọc");
        tabs.check(initialTab == ModelTab.RECOGNITION ? recognitionId
                : initialTab == ModelTab.TRANSLATION ? translationId : voiceId);

        ScrollView scroll = new ScrollView(this);
        modelForm = form();
        scroll.addView(modelForm);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        modelTab = initialTab;

        tabs.setOnCheckedChangeListener((group, checkedId) -> {
            modelTab = checkedId == recognitionId ? ModelTab.RECOGNITION
                    : checkedId == translationId ? ModelTab.TRANSLATION : ModelTab.VOICE;
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
        tab.setButtonDrawable(null);
        tab.setGravity(Gravity.CENTER);
        PlayerAppearance.styleButton(tab, PlayerAppearance.ButtonRole.UTILITY, settings);
        tab.setPadding(dp(4), dp(4), dp(4), dp(4));
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
                List<String> available = SOURCE_LANGUAGES.contains(source)
                        ? models.vosk.availableModelKeys(source) : java.util.Collections.emptyList();
                String defaultModel = available.isEmpty() ? null : available.get(0);
                runOnUiThread(() -> {
                    if (!destroyed && modelForm == targetForm && version == modelRenderVersion) {
                        resetModelRows();
                        renderRecognitionModels(models, source, defaultModel, sizes);
                    }
                });
            });
        } else {
            resetModelRows();
            if (modelTab == ModelTab.TRANSLATION) renderTranslationModels(models, source);
            else renderModelVoiceSettings();
        }
    }

    private void renderModelVoiceSettings() {
        String language = modelVoiceLanguage;
        String savedVoice = AppSettings.voice(this, language);
        LinearLayout current = voiceCard("Giọng đọc hiện tại", 0);
        TextView selection = processingLabel(current, languageName(language) + " · Đang kiểm tra…");
        LinearLayout actions = new LinearLayout(this);
        current.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        Button change = compactButton(actions, "Đổi giọng", () -> {
            modelVoicePreview.stop();
            chooseVoice(language, false, false);
        });
        TextView status = new TextView(this);
        status.setTextSize(settings.textSize(14));
        Button listen = compactButton(actions, "Nghe thử", null);
        listen.setOnClickListener(view -> {
            CueSpeech preview = modelVoicePreview;
            status.setText("");
            status.setVisibility(View.GONE);
            if (preview.hasActiveUtterance()) preview.stop();
            else preview.preview(voiceSample(language), language, settings.dubbingVolume / 100f, savedVoice);
            updateModelVoiceButton(listen, preview);
        });
        PlayerAppearance.styleButton(listen, PlayerAppearance.ButtonRole.ACCENT, settings);
        for (Button action : new Button[]{change, listen}) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) action.getLayoutParams();
            params.width = 0;
            params.weight = 1;
            params.topMargin = dp(PlayerAppearance.CONTROL_GAP_DP);
        }
        current.addView(status);
        status.setVisibility(View.GONE);
        change.setEnabled(false);
        listen.setEnabled(false);

        LinearLayout data = voiceCard("Dữ liệu giọng đọc", PlayerAppearance.GROUP_GAP_DP);
        Set<String> languages = new LinkedHashSet<>();
        String source = hasVideo ? session.sourceLanguage : settings.sourceLanguage;
        String target = hasVideo && session.targetLanguage != null ? session.targetLanguage : settings.targetLanguage;
        if (source != null && !"auto".equals(source)) languages.add(Locale.forLanguageTag(source).getLanguage());
        languages.add(Locale.forLanguageTag(target).getLanguage());
        languages.add(language);
        List<TextView> dataRows = new ArrayList<>();
        for (String code : languages) {
            dataRows.add(processingLabel(data, languageName(code) + " · Đang kiểm tra…"));
        }
        compactButton(data, "Quản lý dữ liệu giọng đọc", this::openVoiceDataSettings);

        CueSpeech preview = new CueSpeech(this, message -> {
            status.setText(message);
            status.setVisibility(View.VISIBLE);
            updateModelVoiceButton(listen, modelVoicePreview);
        }, () -> updateModelVoiceButton(listen, modelVoicePreview), () -> {
            CueSpeech catalog = modelVoicePreview;
            android.speech.tts.Voice voice = catalog.configuredVoice(language, savedVoice);
            boolean explicit = voice != null && savedVoice.equals(voice.getName());
            String name = voice == null ? "" : voice.getName() + " · ";
            selection.setText(languageName(language) + " · " + name
                    + (explicit ? (voice.isNetworkConnectionRequired() ? "Cần mạng" : "Ngoại tuyến")
                    : "Mặc định hệ thống"));
            int index = 0;
            for (String code : languages) {
                dataRows.get(index++).setText(languageName(code) + " · " + catalog.voiceDataStatus(code));
            }
            change.setEnabled(true);
            listen.setEnabled(catalog.supports(language));
        });
        modelVoicePreview = preview;
        current.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}

            @Override public void onViewDetachedFromWindow(View view) {
                preview.close();
                if (modelVoicePreview == preview) modelVoicePreview = null;
            }
        });
    }

    private void updateModelVoiceButton(Button button, CueSpeech preview) {
        button.setText(preview != null && preview.hasActiveUtterance() ? "Dừng" : "Nghe thử");
    }

    private LinearLayout voiceCard(String title, int topMargin) {
        LinearLayout card = column();
        PlayerAppearance.styleSurface(card, PlayerAppearance.color(this, R.attr.playerButtonSurface),
                settings.raisedControls);
        int inset = dp(12);
        card.setPadding(inset, inset, inset, inset);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        modelForm.addView(card, params);
        TextView heading = processingLabel(card, title);
        heading.setTextSize(settings.textSize(16));
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        return card;
    }

    private void openVoiceDataSettings() {
        if (modelVoicePreview != null) modelVoicePreview.stop();
        for (String action : new String[]{"com.android.settings.TTS_SETTINGS",
                android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA,
                android.provider.Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                // Try the next settings entry supported by this device.
            }
        }
        storageMessage("Hãy mở cài đặt bộ máy đọc văn bản của thiết bị để cài giọng.");
    }

    private void resetModelRows() {
        modelForm.removeAllViews();
        if (session.busy && modelTab != ModelTab.VOICE) {
            modelText("Có tác vụ video đang chạy. Hãy đợi để quản lý model.", false);
        }
    }

    private void renderRecognitionModels(OfflineModels models, String source, String defaultModel,
                                         java.util.Map<String, Long> sizes) {
        Set<String> recognition = new LinkedHashSet<>();
        recognition.add(SOURCE_LANGUAGES.contains(source) ? source : "en");
        recognition.addAll(SOURCE_LANGUAGES);
        recognition.addAll(VoskModelStore.largeModelKeys());
        modelText("Tạo transcript (Vosk) · Lớn nhất trước", true);
        modelText("Ngôn ngữ lời thoại mặc định · " + languageName(source), false);
        if (defaultModel == null && SOURCE_LANGUAGES.contains(source)) {
            modelText("Chưa cài model cho ngôn ngữ này", false);
        }
        if (models.voskImportError != null) modelText(models.voskImportError, false);
        boolean recognitionAvailable = !session.busy && !OfflineModels.pending(models.recognition);
        List<String> ordered = new ArrayList<>(recognition);
        ordered.sort(java.util.Comparator.comparingLong((String language) -> sizes.getOrDefault(language, -1L))
                .reversed());
        for (String language : ordered) {
            boolean downloaded = models.vosk.isDownloaded(language);
            OfflineModels.Operation operation = models.recognition.get(language);
            if (downloaded && operation == OfflineModels.Operation.DOWNLOAD_FAILED) operation = null;
            String model = VoskModelStore.modelName(language);
            String role = model + (language.equals(defaultModel) ? " · Mặc định cho lượt mới" : "");
            if (language.equals(models.voskImportLanguage)) {
                String progress = models.recognitionPercent < 0 ? "" : " · " + models.recognitionPercent + "%";
                LinearLayout row = modelRowDetails(language, "Đang nhập" + progress, role, -1,
                        PlayerAppearance.StatusIcon.WORKING);
                compactButton(row, "Dừng nhập", models::cancelVoskImport);
                continue;
            }
            Runnable importFile = () -> {
                if (session.busy || OfflineModels.pending(models.recognition)) return;
                pendingVoskImport = language;
                pick(voskPicker, "*/*");
            };
            modelRow(language, role, downloaded, models.vosk.isBundled(language), operation,
                    models.recognitionPercent, recognitionAvailable, sizes.getOrDefault(language, -1L),
                    () -> models.downloadRecognition(language), () -> models.deleteRecognition(language), importFile, language);
            if (!downloaded) {
                Button importModel = compactButton(modelForm, "Nhập tệp model", importFile);
                importModel.setContentDescription("Nhập tệp model " + model);
                importModel.setEnabled(recognitionAvailable);
            }
        }

    }

    private void renderM2mModel(OfflineModels models) {
        boolean supported = ContextModelStore.supported();
        boolean installed = models.m2m.installed();
        long bytes = installed ? models.m2m.installedBytes() : -1;
        String status = installed ? "Đã tải" : "Chưa tải · Khoảng 1,15 GB";
        String role = installed ? "Dịch trực tiếp" : "";
        PlayerAppearance.StatusIcon icon = installed ? PlayerAppearance.StatusIcon.SUCCESS
                : PlayerAppearance.StatusIcon.WARNING;
        if (!supported) {
            status = "Cần Android ARM64";
            icon = PlayerAppearance.StatusIcon.WARNING;
        } else if (models.m2mDownloading) {
            status = models.m2mStatus();
            icon = PlayerAppearance.StatusIcon.WORKING;
        } else if (models.m2mError != null) {
            status = models.m2mError;
            icon = PlayerAppearance.StatusIcon.ERROR;
        }
        LinearLayout row = modelRowDetails(M2mModelStore.NAME, status, role, bytes, icon,
                "M2M100 · Dịch theo câu", null, false);
        if (!supported) return;
        if (!installed && !models.m2mDownloading) {
            Button download = compactButton(row, "Tải", () -> {
                if (!session.busy && !models.m2mDownloading) models.toggleM2mDownload();
            });
            download.setContentDescription("Tải model " + M2mModelStore.NAME);
            stylePrimaryButton(download);
            download.setEnabled(!session.busy);
            Button importModel = compactButton(modelForm, "Nhập tệp model", () -> pickM2mFiles(models));
            importModel.setContentDescription("Nhập tệp model " + M2mModelStore.NAME);
            importModel.setEnabled(!session.busy);
            return;
        }
        Button menu = iconButton(row, R.drawable.ic_control_more, "Thao tác model " + M2mModelStore.NAME,
                false, null);
        menu.setEnabled(models.m2mDownloading || !session.busy);
        menu.setOnClickListener(anchor -> {
            PopupMenu popup = new PopupMenu(new ContextThemeWrapper(this, R.style.PlayerMenuTheme),
                    anchor, Gravity.END);
            if (models.m2mDownloading) {
                popup.getMenu().add("Dừng").setOnMenuItemClickListener(item -> {
                    if (models.m2mDownloading) models.toggleM2mDownload();
                    return true;
                });
            } else if (models.m2m.installed()) {
                addModelMenuItem(popup, "Xuất model", android.R.drawable.ic_menu_save,
                        PlayerAppearance.IconRole.MODEL).setOnMenuItemClickListener(item -> {
                    pickModelExport("m2m100");
                    return true;
                });
                addModelMenuItem(popup, "Cài lại từ tệp", R.drawable.ic_control_download,
                        PlayerAppearance.IconRole.MODEL).setOnMenuItemClickListener(item -> {
                    if (!session.busy && !models.m2mDownloading) {
                        new PlayerAppearance.DialogBuilder(this, settings)
                                .setTitle("Cài lại " + M2mModelStore.NAME)
                                .setMessage("Chọn tệp ZIP đã xuất hoặc chọn đủ 5 tệp rời trong một lượt.")
                                .setNegativeButton("Hủy", null)
                                .setPositiveButton("Chọn tệp", (dialog, which) -> pickM2mFiles(models))
                                .show();
                    }
                    return true;
                });
                addModelMenuItem(popup, PlayerAppearance.dangerLabel(this, "Xóa model"),
                    R.drawable.ic_control_delete, PlayerAppearance.IconRole.DELETE).setOnMenuItemClickListener(item -> {
                    if (session.busy) return true;
                    new PlayerAppearance.DialogBuilder(this, settings)
                            .positiveRole(PlayerAppearance.ButtonRole.CONFIRM_DELETE)
                            .setTitle("Xóa model " + M2mModelStore.NAME + "?")
                            .setMessage("Dung lượng dự kiến thu hồi · " + storageSize(models.m2m.installedBytes()))
                            .setNegativeButton("Hủy", null)
                            .setPositiveButton("Xóa", (dialog, which) -> {
                                if (!session.busy) models.deleteM2mModel();
                            }).show();
                    return true;
                });
            }
            popup.show();
        });
    }

    private void pickM2mFiles(OfflineModels models) {
        if (session.busy || models.m2mDownloading) return;
        m2mPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true));
    }

    private void renderTranslationModels(OfflineModels models, String source) {
        modelText("Dịch theo câu", true);
        renderMlKitModels(models, source);
        addTranslationModelDivider();
        renderM2mModel(models);
        modelText("Dịch theo ngữ cảnh", true);
        if (!ContextModelStore.supported()) {
            modelText("Model Qwen yêu cầu Android ARM64.", false);
            return;
        }
        renderContextModel(models, false);
        addTranslationModelDivider();
        renderContextModel(models, true);
        addTranslationModelDivider();
        addTranslationSettingsRow("Ưu tiên xử lý", settings.qwenProcessor.label,
                null, ">", this::showQwenProcessor);
    }

    private void renderContextModel(OfflineModels models, boolean light) {
        ContextModelStore model = models.contextModel(light);
        boolean ownsOperation = models.contextOperationModel == model;
        boolean installing = ownsOperation && models.contextPending();
        boolean enabled = !session.busy && !models.contextPending();
        ActivityResultLauncher<Intent> picker = light ? qwenLightPicker : qwenPicker;
        if (model.installed() && !installing) {
            modelRow("vi", "Dịch sang tiếng Việt", true, false,
                    ownsOperation ? models.contextOperation : null, -1, enabled, model.file.length(),
                    () -> models.downloadContextModel(model), () -> models.deleteContextModel(model),
                    () -> pick(picker, "*/*"), light ? "qwen-light" : "qwen");
            if (ownsOperation && models.contextError != null) modelText(models.contextError, false);
            return;
        }

        PlayerAppearance.StatusIcon icon = installing ? PlayerAppearance.StatusIcon.WORKING
                : ownsOperation && models.contextError != null
                        ? PlayerAppearance.StatusIcon.ERROR : PlayerAppearance.StatusIcon.WARNING;
        LinearLayout row = modelRowDetails(model.name, contextDownloadStatus(models, model),
                "Dịch sang tiếng Việt", -1, icon, "Qwen · Dịch theo ngữ cảnh", null, false);
        Button action = compactButton(row, contextDownloadAction(models, model),
                installing ? models::cancelContextDownload : () -> models.downloadContextModel(model));
        PlayerAppearance.styleButton(action, PlayerAppearance.ButtonRole.ACCENT, settings);
        action.setEnabled(installing ? !models.contextCancelling() : enabled);
        if (!installing) {
            Button importModel = compactButton(modelForm, "Nhập tệp model", () -> pick(picker, "*/*"));
            importModel.setContentDescription("Nhập tệp model " + model.name);
            importModel.setEnabled(enabled);
        }
    }

    private void showQwenProcessor() {
        boolean gpuAvailable = ContextTranslationClient.isGpuAvailable();
        LinearLayout content = form();
        AlertDialog dialog = sheet("Ưu tiên xử lý Qwen", content)
                .setNegativeButton("Đóng", null).create();
        RadioGroup choices = new RadioGroup(this);
        for (AppSettings.QwenProcessor processor : AppSettings.QwenProcessor.values()) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(processor.label);
            option.setTextSize(settings.textSize(16));
            option.setMinHeight(dp(48));
            option.setEnabled(processor != AppSettings.QwenProcessor.GPU || gpuAvailable);
            choices.addView(option, new RadioGroup.LayoutParams(-1, -2));
            if (processor == settings.qwenProcessor) choices.check(option.getId());
            option.setOnClickListener(view -> {
                settings.qwenProcessor = processor;
                settings.save(this);
                dialog.dismiss();
                renderModels();
            });
            if (processor == AppSettings.QwenProcessor.GPU && !gpuAvailable) {
                addQwenProcessorNote(choices, "GPU Vulkan không khả dụng trên thiết bị hoặc bản cài này.");
            }
        }
        content.addView(choices);
        addQwenProcessorNote(content, "Áp dụng cho lượt xử lý Qwen tiếp theo.");
        showSheet(dialog);
    }

    private void addQwenProcessorNote(LinearLayout content, String message) {
        TextView note = new TextView(this);
        note.setText(message);
        note.setTextSize(settings.textSize(14));
        note.setPadding(dp(8), dp(8), dp(8), 0);
        content.addView(note);
    }

    private void addTranslationModelDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(PlayerAppearance.color(this, android.R.attr.textColorSecondary));
        divider.setAlpha(0.2f);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(1));
        layout.setMargins(dp(8), 0, dp(8), 0);
        modelForm.addView(divider, layout);
    }

    private void addTranslationSettingsRow(String label, String summary, PlayerAppearance.StatusIcon icon,
                                           String indicator, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        android.util.TypedValue background = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true)) {
            row.setBackgroundResource(background.resourceId);
        }
        LinearLayout details = column();
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(settings.textSize(16));
        details.addView(title);
        if (!summary.isEmpty()) {
            TextView description = new TextView(this);
            description.setText(summary);
            description.setTextSize(settings.textSize(13));
            description.setCompoundDrawablePadding(dp(4));
            if (icon != null) icon.apply(description);
            details.addView(description);
        }
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = new TextView(this);
        arrow.setText(indicator);
        arrow.setTextSize(settings.textSize(20));
        arrow.setPadding(dp(8), 0, 0, 0);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow);
        row.setContentDescription(label + (summary.isEmpty() ? "" : ", " + summary)
                + ("▾".equals(indicator) ? ", đang mở" : "▸".equals(indicator) ? ", đang thu gọn" : ""));
        row.setFocusable(true);
        row.setOnClickListener(view -> action.run());
        modelForm.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderMlKitModels(OfflineModels models, String source) {
        String target = LanguageUtils.normalizeLanguage(settings.targetLanguage, "vi");
        boolean autoSource = "auto".equals(source);
        boolean supportedPair = (autoSource || TranslateLanguage.fromLanguageTag(source) != null)
                && TranslateLanguage.fromLanguageTag(target) != null;
        String pair = (autoSource ? "Tự động" : languageName(source)) + " → " + languageName(target);
        Set<String> required = new LinkedHashSet<>();
        if (supportedPair && !source.equals(target)) {
            if (OfflineModels.needsTranslationModel(source)) required.add(source);
            if (OfflineModels.needsTranslationModel(target)) required.add(target);
        }
        boolean translationKnown = models.translationsLoaded && !models.translationQueryFailed;
        boolean pending = OfflineModels.pending(models.translation);
        boolean translationAvailable = translationKnown && !session.busy && !pending;
        List<String> missing = new ArrayList<>(required);
        missing.removeAll(models.downloadedTranslations);
        boolean retry = missing.stream().anyMatch(language ->
                models.translation.get(language) == OfflineModels.Operation.DOWNLOAD_FAILED);
        String status;
        if (!supportedPair) {
            status = "Cặp ngôn ngữ chưa được hỗ trợ";
        } else if (models.translationQueryFailed) {
            status = "Chưa kiểm tra được";
        } else if (!models.translationsLoaded) {
            status = "Đang kiểm tra";
        } else if (pending) {
            status = "Đang cập nhật gói";
        } else if (!missing.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String language : missing) names.add(languageName(language));
            status = (retry ? "Tải thất bại · Thiếu gói " : "Thiếu gói ") + String.join(", ", names);
        } else if (source.equals(target)) {
            status = "Không cần gói dịch";
        } else {
            status = autoSource ? "Gói nguồn xác định khi dịch" : "Đủ gói";
        }
        boolean knownPair = supportedPair && !autoSource && !source.equals(target);
        boolean viaEnglish = knownPair && !TranslateLanguage.ENGLISH.equals(source)
                && !TranslateLanguage.ENGLISH.equals(target);
        boolean ready = knownPair && translationKnown && !pending && missing.isEmpty();
        String summary = pair + " · " + status + (viaEnglish ? "\nQua tiếng Anh" : "");
        addTranslationSettingsRow("ML Kit", summary,
                ready ? PlayerAppearance.StatusIcon.SUCCESS : PlayerAppearance.StatusIcon.NEUTRAL,
                mlKitExpanded ? "▾" : "▸", () -> {
            mlKitExpanded = !mlKitExpanded;
            renderModels();
        });
        if (models.translationQueryFailed) {
            compactButton(modelForm, "Kiểm tra lại", () -> {
                models.refreshTranslations();
                renderModels();
            });
        }
        if (translationKnown && !missing.isEmpty()) {
            Button download = compactButton(modelForm, retry ? "Thử tải gói thiếu" : "Tải gói thiếu",
                    () -> models.downloadTranslations(missing));
            download.setContentDescription("Tải gói ML Kit còn thiếu");
            stylePrimaryButton(download);
            download.setEnabled(translationAvailable);
        }
        if (!mlKitExpanded) return;

        Set<String> activeLanguages = new LinkedHashSet<>();
        if (!autoSource && TranslateLanguage.fromLanguageTag(source) != null) activeLanguages.add(source);
        activeLanguages.add(target);
        for (String language : activeLanguages) {
            String role = language.equals(source) ? (language.equals(target) ? "Nguồn và đích" : "Nguồn")
                    : "Đích";
            renderMlKitLanguage(models, language, role, translationKnown, translationAvailable);
        }
        addTranslationSettingsRow("Ngôn ngữ khác", "", null,
                otherTranslationLanguagesExpanded ? "▾" : "▸", () -> {
            otherTranslationLanguagesExpanded = !otherTranslationLanguagesExpanded;
            renderModels();
        });
        if (!otherTranslationLanguagesExpanded) return;

        Set<String> otherLanguages = new LinkedHashSet<>(Arrays.asList("zh", "ja", "en", "vi", "ko"));
        otherLanguages.addAll(models.downloadedTranslations);
        otherLanguages.addAll(models.translation.keySet());
        otherLanguages.removeAll(activeLanguages);
        List<String> ordered = new ArrayList<>(otherLanguages);
        ordered.sort(java.util.Comparator
                .comparing((String language) -> !models.downloadedTranslations.contains(language))
                .thenComparing(MainActivity::languageName, String.CASE_INSENSITIVE_ORDER));
        for (String language : ordered) {
            renderMlKitLanguage(models, language, "", translationKnown, translationAvailable);
        }
    }

    private android.view.MenuItem addModelMenuItem(PopupMenu popup, CharSequence label, int icon,
                                                   PlayerAppearance.IconRole role) {
        android.view.MenuItem item = popup.getMenu().add(label);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            popup.setForceShowIcon(true);
            item.setIcon(icon).setIconTintList(role.tint(this));
        }
        return item;
    }

    private void renderMlKitLanguage(OfflineModels models, String language, String role,
                                     boolean translationKnown, boolean translationAvailable) {
        if (TranslateLanguage.ENGLISH.equals(language)) {
            modelRowDetails(language, "Có sẵn", role, -1, PlayerAppearance.StatusIcon.SUCCESS);
        } else if (!translationKnown) {
            modelRowDetails(language, models.translationQueryFailed ? "Chưa rõ trạng thái" : "Đang kiểm tra…",
                    role, -1, models.translationQueryFailed ? PlayerAppearance.StatusIcon.ERROR
                            : PlayerAppearance.StatusIcon.WORKING);
        } else {
            modelRow(language, role, models.downloadedTranslations.contains(language), false,
                    models.translation.get(language), -1, translationAvailable, -1,
                    () -> models.downloadTranslations(Collections.singletonList(language)),
                    () -> models.deleteTranslation(language));
        }
    }

    String contextDownloadAction(OfflineModels models, ContextModelStore model) {
        if ((models.contextOperationModel == model && models.contextPending()) && models.contextImporting) return "Dừng nhập";
        if ((models.contextOperationModel == model && models.contextPending())) return "Hủy tải";
        return model.partialBytes() > 0 ? "Tiếp tục tải" : "Tải model";
    }

    String contextDownloadStatus(OfflineModels models, ContextModelStore model) {
        if ((models.contextOperationModel == model && models.contextPending()) && models.contextImporting) {
            if (models.contextCancelling()) return "Đang dừng nhập Qwen.";
            return "Đang nhập Qwen · " + storageSize(models.contextBytes);
        }
        if (models.contextOperationModel == model && models.contextCancelling()) return "Đang hủy tải Qwen…";
        if ((models.contextOperationModel == model && models.contextPending())) {
            String total = models.contextTotal > 0 ? storageSize(models.contextTotal) : "khoảng " + model.sizeLabel;
            return "Đang tải Qwen · " + storageSize(models.contextBytes) + " / " + total;
        }
        long partial = model.partialBytes();
        String status = partial > 0 ? "Đã lưu " + storageSize(partial) + " / khoảng " + model.sizeLabel
                : "Cần tải khoảng " + model.sizeLabel + " để dịch tiếng Việt offline.";
        return models.contextOperationModel != model || models.contextError == null ? status : models.contextError + "\n" + status;
    }

    private void modelRow(String language, String role, boolean downloaded, boolean bundled, OfflineModels.Operation operation,
                          int percent, boolean enabled, long bytes, Runnable download, Runnable delete) {
        modelRow(language, role, downloaded, bundled, operation, percent, enabled, bytes, download, delete, null, null);
    }

    private void modelRow(String language, String role, boolean downloaded, boolean bundled, OfflineModels.Operation operation,
                          int percent, boolean enabled, long bytes, Runnable download, Runnable delete, Runnable reinstall,
                          String exportModel) {
        boolean contextual = "qwen".equals(exportModel) || "qwen-light".equals(exportModel);
        String name = contextual ? work.models.contextModel("qwen-light".equals(exportModel)).name : languageName(language);
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
        LinearLayout row = contextual
                ? modelRowDetails(name, status, role, downloaded ? bytes : -1, icon,
                        "Qwen · Dịch theo ngữ cảnh", null, false)
                : modelRowDetails(language, status, role, downloaded ? bytes : -1, icon);
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
            if (reinstall != null) {
                addModelMenuItem(popup, "Cài lại từ tệp", R.drawable.ic_control_download,
                        PlayerAppearance.IconRole.MODEL).setOnMenuItemClickListener(item -> {
                    if (!session.busy) reinstall.run();
                    return true;
                });
            }
            if (exportModel != null) {
                addModelMenuItem(popup, "Xuất model", android.R.drawable.ic_menu_save,
                        PlayerAppearance.IconRole.MODEL).setOnMenuItemClickListener(item -> {
                    pickModelExport(exportModel);
                    return true;
                });
            }
            addModelMenuItem(popup, PlayerAppearance.dangerLabel(this, "Xóa model"),
                    R.drawable.ic_control_delete, PlayerAppearance.IconRole.DELETE).setOnMenuItemClickListener(item -> {
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
        boolean recognition = modelTab == ModelTab.RECOGNITION;
        String engine = recognition ? "Vosk · Tạo transcript" : "ML Kit · Dịch offline";
        String downloadPackage = recognition ? VoskModelStore.downloadPackage(language) : null;
        return modelRowDetails(languageName(language), status, role, installedBytes, icon, engine,
                downloadPackage, recognition || !TranslateLanguage.ENGLISH.equals(language));
    }

    private LinearLayout modelRowDetails(String name, String status, String role, long installedBytes,
                                         PlayerAppearance.StatusIcon icon, String engine,
                                         String downloadPackage, boolean showUnknownSize) {
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
        boolean inlineSize = installedBytes >= 0 && !showUnknownSize;
        description.setText(inlineSize ? status + " · " + storageSize(installedBytes)
                + (role.isEmpty() ? "" : " · " + role) : summary);
        description.setTextSize(settings.textSize(13));
        description.setCompoundDrawablePadding(dp(4));
        icon.apply(description);
        details.addView(description);
        if (installedBytes >= 0 && !inlineSize) {
            TextView size = new TextView(this);
            size.setTextSize(settings.textSize(13));
            size.setText(storageSize(installedBytes));
            details.addView(size);
        }

        details.setFocusable(true);
        details.setMinimumHeight(dp(48));
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setOnClickListener(view -> {
            String message = engine + "\n" + summary;
            if (downloadPackage != null) message += "\nGói tải: " + downloadPackage;
            if (installedBytes >= 0) {
                message += "\nDung lượng đã cài: " + storageSize(installedBytes);
            } else if (showUnknownSize) {
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
        Button reset = settingsActionButton(form, "Khôi phục mặc định", () -> {
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
    private static final List<String> MODES = Arrays.asList("Tắt", "Gốc", "Dịch");

    private void showMode(boolean voice) {
        LinearLayout form = form();
        List<String> choices = new ArrayList<>(MODES);
        if (session.targetLanguage == null || (voice && session.cues.stream().noneMatch(TranscriptCue::hasTranslation))) {
            choices.remove(2);
        }
        Spinner mode = selection(form, voice ? "Nội dung đọc" : "Nội dung hiển thị", choices,
                (voice ? read : display).ordinal() < choices.size() ? (voice ? read : display).ordinal() : 0);
        if (choices.size() < MODES.size()) addLabel(form, "Cần có bản dịch để chọn Dịch.");
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
        String remaining = session.translationWorkSummary();
        if (!remaining.isEmpty()) addLabel(form, remaining);
        AlertDialog.Builder modeDialog = sheet(voice ? "Phát âm" : "Phụ đề", form).setNegativeButton("Hủy", null)
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
        if (!remaining.isEmpty() && !session.busy) {
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
        transcriptToolbar = row(transcriptPanel);
        transcriptToolbar.setGravity(Gravity.CENTER_VERTICAL);
        transcriptToolbar.setBaselineAligned(false);
        followButton = iconButton(transcriptToolbar, R.drawable.ic_control_follow, "Về câu đang phát", false,
                this::resumeFollowing);
        transcriptTranslationStatus = new TextView(this);
        transcriptTranslationStatus.setTextSize(settings.textSize(12));
        transcriptTranslationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        transcriptToolbar.addView(transcriptTranslationStatus, new LinearLayout.LayoutParams(0, -2, 1));
        transcriptTranslationStatus.setVisibility(View.GONE);
        transcriptMissing = compactButton(transcriptToolbar, "Dịch tiếp", this::retryMissingTranslations);
        transcriptMissing.setVisibility(View.GONE);
        updateTranscriptToolbarVisibility();
        cueList = new ListView(this);
        cueAdapter = new CueAdapter();
        cueList.setAdapter(cueAdapter);
        cueList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) {
                    follow = false;
                    updateTranscriptToolbarVisibility();
                }
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {}
        });
        transcriptPanel.addView(cueList, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void selectTranscriptRow(int position) {
        selectedTranscriptRow = position;
        player.seekTo(cueAdapter.startTime(position));
        cueAdapter.notifyDataSetChanged();
    }

    private void resumeFollowing() {
        follow = true;
        selectedTranscriptRow = -1;
        cueAdapter.notifyDataSetChanged();
        updateTranscriptToolbarVisibility();
        if (activeRow >= 0) cueList.setSelection(activeRow);
    }

    private void updateTranscriptToolbarVisibility() {
        followButton.setVisibility(follow ? View.GONE : View.VISIBLE);
        transcriptToolbar.setVisibility(!follow || transcriptMissing.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
    }

    private void refreshTranscript() {
        String remaining = session.translationWorkSummary();
        boolean interrupted = session.failedTask() == VideoSession.Task.TRANSLATION;
        boolean hasWork = interrupted || !remaining.isEmpty();
        transcriptMissing.setVisibility(hasWork ? View.VISIBLE : View.GONE);
        transcriptMissing.setEnabled(!session.busy);
        String summary = interrupted
                ? (remaining.isEmpty() ? "Dịch bị gián đoạn" : "Dịch bị gián đoạn · " + remaining)
                : remaining;
        if (!summary.contentEquals(transcriptTranslationStatus.getText())) {
            transcriptTranslationStatus.setText(summary);
        }
        transcriptTranslationStatus.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        missingTranslationNotice.setText(summary);
        updateTranscriptToolbarVisibility();
        cueAdapter.notifyDataSetChanged();
        markVisibleResult();
    }

    private boolean editSentenceRow(int index) {
        if (session.busy) return false;
        TranscriptCue sentence = session.cues.get(index);
        boolean hasNext = index + 1 < session.cues.size();
        String[] actions = hasNext ? new String[]{"Sửa câu gốc", "Tách câu", "Ghép với câu sau"}
                : new String[]{"Sửa câu gốc", "Tách câu"};
        new PlayerAppearance.DialogBuilder(this, settings).setTitle("Chỉnh câu")
                .setItems(actions, (dialog, action) -> {
                    if (!isCurrentSentence(index, sentence)) return;
                    if (action == 2) {
                        List<TranscriptCue> edited = new ArrayList<>(session.cues);
                        TranscriptCue next = edited.remove(index + 1);
                        edited.set(index, TranscriptSentences.cue(sentence.startMs, next.endMs,
                                sentence.sourceText + " " + next.sourceText));
                        session.replaceSentences(edited);
                        return;
                    }
                    EditText text = new EditText(this);
                    text.setText(sentence.sourceText);
                    if (action == 1) text.setShowSoftInputOnFocus(false);
                    new PlayerAppearance.DialogBuilder(this, settings)
                            .setTitle(action == 0 ? "Sửa câu gốc" : "Đặt con trỏ tại điểm tách")
                            .setView(text).setNegativeButton("Hủy", null)
                            .setPositiveButton("Lưu", (window, which) -> {
                                if (!isCurrentSentence(index, sentence)) return;
                                String value = text.getText().toString();
                                List<TranscriptCue> edited = new ArrayList<>(session.cues);
                                if (action == 0) {
                                    if (value.trim().isEmpty() || value.trim().equals(sentence.sourceText)) return;
                                    edited.set(index, TranscriptSentences.cue(sentence.startMs, sentence.endMs,
                                            value.trim()));
                                } else {
                                    int cut = text.getSelectionStart();
                                    if (cut <= 0 || cut >= value.length() || sentence.endMs - sentence.startMs < 2) return;
                                    String first = value.substring(0, cut).trim();
                                    String second = value.substring(cut).trim();
                                    if (first.isEmpty() || second.isEmpty()) return;
                                    long split = sentence.startMs + (sentence.endMs - sentence.startMs) * cut / value.length();
                                    showSentenceSplitTiming(index, sentence, first, second, split);
                                    return;
                                }
                                session.replaceSentences(edited);
                            }).show();
                }).setNegativeButton("Đóng", null).show();
        return true;
    }

    private void showSentenceSplitTiming(int index, TranscriptCue sentence, String first, String second,
                                         long suggestedTime) {
        MediaItem item = player.getCurrentMediaItem();
        if (item == null) return;
        long duration = sentence.endMs - sentence.startMs;
        long[] split = {Math.max(sentence.startMs + 1, Math.min(sentence.endMs - 1, suggestedTime))};
        LinearLayout form = form();
        processingLabel(form, first + "\n\n" + second);
        TextView timing = processingLabel(form, "");
        SeekBar position = new SeekBar(this);
        position.setContentDescription("Mốc tách câu");
        int steps = (int) Math.min(1000, duration - 2);
        position.setMax(steps);
        position.setProgress(steps == 0 ? 0 : (int) Math.round(
                (split[0] - sentence.startMs - 1) * (double) steps / (duration - 2)));
        form.addView(position);
        Runnable refresh = () -> {
            long time = split[0];
            timing.setText(String.format(Locale.US, "Mốc tách %s.%03d",
                    TranscriptParser.formatTime(time), time % 1000));
        };
        refresh.run();

        ExoPlayer[] preview = {null};
        Runnable stopPreview = () -> {
            if (preview[0] != null) {
                preview[0].release();
                preview[0] = null;
            }
        };
        position.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser || steps == 0) return;
                split[0] = sentence.startMs + 1 + Math.round((duration - 2) * (double) value / steps);
                stopPreview.run();
                refresh.run();
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        AlertDialog dialog = sheet("Chỉnh mốc tách câu", form)
                .setNegativeButton("Hủy", null)
                .setNeutralButton("Nghe thử", null)
                .setPositiveButton("Lưu", (window, which) -> {
                    if (!isCurrentSentence(index, sentence)) return;
                    List<TranscriptCue> edited = new ArrayList<>(session.cues);
                    edited.set(index, TranscriptSentences.cue(sentence.startMs, split[0], first));
                    edited.add(index + 1, TranscriptSentences.cue(split[0], sentence.endMs, second));
                    session.replaceSentences(edited);
                }).create();
        sentenceSplitDialog = dialog;
        dialog.setOnDismissListener(window -> {
            stopPreview.run();
            if (sentenceSplitDialog == dialog) sentenceSplitDialog = null;
        });
        showSheet(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            stopPreview.run();
            player.pause();
            speech.stop();
            long start = Math.max(sentence.startMs, split[0] - 1500);
            long end = Math.min(sentence.endMs, split[0] + 1500);
            ExoPlayer audio = new ExoPlayer.Builder(this).build();
            preview[0] = audio;
            audio.setTrackSelectionParameters(audio.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true).build());
            audio.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    if (preview[0] == audio) {
                        timing.setText("Không nghe thử được đoạn này.");
                        stopPreview.run();
                    }
                }
            });
            audio.setMediaItem(item.buildUpon().setClippingConfiguration(
                    new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
                            .setEndPositionMs(end).build()).build());
            audio.prepare();
            audio.play();
        });
    }


    private boolean isCurrentSentence(int index, TranscriptCue sentence) {
        return !session.busy && index < session.cues.size() && session.cues.get(index) == sentence;
    }

    private boolean editTranscriptRow(int position) {
        if (session.busy) return false;
        TranscriptCue cue = session.cues.get(position);
        if (!session.canEditTranslation(cue)) return editSentenceRow(position);
        showSheet(new AlertDialog.Builder(this)
                .setItems(new String[]{"Chỉnh câu gốc", "Sửa bản dịch"}, (dialog, index) -> {
                    if (!isCurrentSentence(position, cue)) return;
                    if (index == 0) editSentenceRow(position);
                    else editCueTranslation(cue);
                }));
        return true;
    }

    private void markVisibleResult() {
        if (!started || session.busy || !transcriptPanel.isShown() || session.cues.isEmpty()) return;
        boolean completeTranslation = session.targetLanguage != null && session.missingTranslations() == 0;
        for (VideoWork.Result result : work.unreadResults()) {
            if (result.video.equals(currentVideo) && (result.task == VideoSession.Task.TRANSCRIPT
                    || (result.task == VideoSession.Task.TRANSLATION && completeTranslation))) {
                work.markResultViewed(result);
            }
        }
    }

    private final class TranscriptRow extends LinearLayout {
        private final LinearLayout content;
        private final TextView timestamp;
        private final TextView source;
        private final TextView translation;
        private final LinearLayout translationLine;
        private final ImageView translationIcon;
        private final Button more;

        TranscriptRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            content = new LinearLayout(MainActivity.this);
            content.setOrientation(VERTICAL);
            content.setPadding(dp(8), dp(8), dp(8), dp(8));
            addView(content, new LinearLayout.LayoutParams(0, -2, 1));

            timestamp = new TextView(MainActivity.this);
            timestamp.setTextColor(PlayerAppearance.color(MainActivity.this, android.R.attr.textColorSecondary));
            content.addView(timestamp);
            source = new TextView(MainActivity.this);
            ImageView sourceIcon = new ImageView(MainActivity.this);
            sourceIcon.setImageResource(R.drawable.ic_control_transcript);
            sourceIcon.setColorFilter(PlayerAppearance.color(MainActivity.this, R.attr.playerIconVideo));
            addLine(source, sourceIcon);
            translation = new TextView(MainActivity.this);
            translationIcon = new ImageView(MainActivity.this);
            translationLine = addLine(translation, translationIcon);

            more = compactButton(this, "⋮", null);
            PlayerAppearance.styleButton(more, PlayerAppearance.ButtonRole.UTILITY, settings);
            more.setLayoutParams(new LinearLayout.LayoutParams(dp(48), -2));
        }

        private LinearLayout addLine(TextView text, ImageView icon) {
            LinearLayout line = new LinearLayout(MainActivity.this);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout icons = new FrameLayout(MainActivity.this);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(26), dp(16));
            iconParams.setMarginEnd(dp(6));
            line.addView(icons, iconParams);
            icons.addView(icon,
                    new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP | Gravity.START));
            text.setIncludeFontPadding(false);
            line.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(line, new LinearLayout.LayoutParams(-1, -2));
            return line;
        }

        void bind(TranscriptCue cue, String time, boolean active, boolean pendingReview) {
            timestamp.setText(time);
            source.setText(cue.sourceText);
            source.setContentDescription(getString(R.string.transcript_source_accessibility, cue.sourceText));
            boolean translated = cue.hasTranslation();
            translationLine.setVisibility(translated ? VISIBLE : GONE);
            translation.setText(translated ? cue.targetText : null);
            translation.setContentDescription(translated ? getString(pendingReview
                    ? R.string.transcript_translation_pending_accessibility
                    : R.string.transcript_translation_accessibility, cue.targetText) : null);
            translationIcon.setImageResource(pendingReview
                    ? R.drawable.ic_transcript_pending : R.drawable.ic_control_translate);
            translationIcon.setColorFilter(PlayerAppearance.color(MainActivity.this,
                    pendingReview ? R.attr.playerIconWarning : R.attr.playerIconTranscript));
            for (TextView text : new TextView[]{timestamp, source, translation}) {
                text.setTextSize(settings.textSize(12));
                text.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
    }

    private final class CueAdapter extends BaseAdapter {
        long startTime(int position) {
            return session.cues.get(position).startMs;
        }

        @Override public int getCount() { return session.cues.size(); }
        @Override public TranscriptCue getItem(int position) { return session.cues.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            TranscriptRow row = recycled == null ? new TranscriptRow() : (TranscriptRow) recycled;
            Button more = row.more;
            boolean active = position == activeRow;
            TranscriptCue cue = getItem(position);
            String timestamp = (active ? "Đang phát · " : "") + TranscriptParser.formatTime(startTime(position));
            row.bind(cue, timestamp, active, cue.hasTranslation() && session.needsTranslation(cue));
            row.setBackgroundColor(active || position == selectedTranscriptRow
                    ? PlayerAppearance.color(MainActivity.this, R.attr.playerSelection) : Color.TRANSPARENT);
            boolean showMenu = !session.busy && (selectedTranscriptRow >= 0
                    ? position == selectedTranscriptRow : active);
            more.setVisibility(showMenu ? View.VISIBLE : View.GONE);
            more.setContentDescription("Chỉnh câu tại " + TranscriptParser.formatTime(cue.startMs));
            more.setOnClickListener(v -> {
                if (isCurrentSentence(position, cue)) editTranscriptRow(position);
            });
            row.setOnClickListener(v -> {
                if (position < session.cues.size() && session.cues.get(position) == cue) {
                    selectTranscriptRow(position);
                }
            });
            row.setOnLongClickListener(v -> isCurrentSentence(position, cue) && editTranscriptRow(position));
            return row;
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

    private final Runnable contextClock = this::updateContextClock;

    private void updateContextClock() {
        if (!started || !session.busy || session.contextStep == null) return;
        long seconds = (android.os.SystemClock.elapsedRealtime() - session.contextStepStartedAt) / 1_000;
        status.setText(session.contextStep + " · " + android.text.format.DateUtils.formatElapsedTime(seconds));
        main.postDelayed(contextClock, 1_000);
    }

    private void showStatus(String message) {
        main.removeCallbacks(hideStatus);
        main.removeCallbacks(contextClock);
        status.setText(message);
        if (message != null && message.equals(session.contextStep)) updateContextClock();
        if (isTemporaryStatus(message)) main.postDelayed(hideStatus, STATUS_TIMEOUT_MS);
        refreshStatusVisibility();
    }

    private boolean isTemporaryStatus(String message) {
        return message != null && (TELEGRAM_AUTHENTICATED.equals(message)
                || message.startsWith("Đã tạo ") || message.startsWith("Đã mở ")
                || message.startsWith("Đã nhập ") || message.startsWith("Đã hủy ")
                || message.startsWith("Đã dịch xong") || message.startsWith("Đã dừng")
                || (message.startsWith("Đã dịch offline ") && !message.contains(", lỗi ")));
    }

    private void refreshStatusVisibility() {
        boolean busy = session.busy;
        if ((!hasVideo && session.runningTask != null)
                || (hasVideo && orientation == Configuration.ORIENTATION_LANDSCAPE && !controlsAreVisible())) {
            feedback.setVisibility(View.GONE);
            return;
        }
        boolean showProgressLabel = busy && progressLabel.length() > 0;
        progressLabel.setVisibility(showProgressLabel ? View.VISIBLE : View.GONE);
        boolean showMessage = !showProgressLabel && status.length() > 0
                && (!hasVideo || busy || controlsAreVisible());
        status.setVisibility(showMessage ? View.VISIBLE : View.GONE);
        boolean landscapeDetails = showMessage && !busy && orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean lowSpace = showMessage && !busy && StorageSpace.isLowSpace(status.getText().toString());
        statusDetails.setVisibility(landscapeDetails ? View.VISIBLE : View.GONE);
        dismissStatus.setVisibility(showMessage && !busy ? View.VISIBLE : View.GONE);
        manageStorageStatus.setVisibility(lowSpace && !landscapeDetails
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
        setCenterControlsVisible(true);
        updateChromeVisibility();
        scheduleControlHide();
    }

    private void hidePlaybackControls() {
        if (!canHideControls()) return;
        if (keepsPlaybackBarVisible() && centerPlaybackControls != null) {
            setCenterControlsVisible(false);
        } else {
            playerView.hideController();
        }
    }

    private void setCenterControlsVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.INVISIBLE;
        if (centerPlaybackControls != null) centerPlaybackControls.setVisibility(visibility);
        if (playbackControlsScrim != null) playbackControlsScrim.setVisibility(visibility);
    }

    private boolean keepsPlaybackBarVisible() {
        return transcriptPanel.isShown() || (!fullscreen && orientation != Configuration.ORIENTATION_LANDSCAPE);
    }

    private void scheduleControlHide() {
        main.removeCallbacks(hideControls);
        if (controlsAreVisible() && canHideControls()) main.postDelayed(hideControls, CONTROLS_TIMEOUT_MS);
    }

    private boolean canHideControls() {
        AccessibilityManager accessibility = getSystemService(AccessibilityManager.class);
        return started && hasVideo && player.isPlaying() && hasWindowFocus()
                && settingsScreen == null && !touchingControls
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
        fitPortraitVideo();
        shortcuts.setVisibility(hasVideo && visible ? View.VISIBLE : View.GONE);
        actions.setVisibility(hasVideo && visible && !session.busy
                ? View.VISIBLE : View.GONE);
        refreshStatusVisibility();
    }

    private void updateTranslationNoticeVisibility() {
        boolean showMissing = !transcriptPanel.isShown() && !session.busy && missingTranslationNotice.length() > 0
                && skippedTranslationVersion != session.transcriptVersion();
        missingTranslationNotice.setVisibility(showMissing ? View.VISIBLE : View.GONE);
        repairMissingTranslation.setVisibility(showMissing ? View.VISIBLE : View.GONE);
        skipMissingTranslation.setVisibility(showMissing ? View.VISIBLE : View.GONE);
    }

    private void updateControls(String message) {
        boolean hasCues = !session.cues.isEmpty();
        refreshTranscript();
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
        primary.setEnabled(hasCues || !session.tracks.isEmpty());
        updateTranslationNoticeVisibility();
        PlayerAppearance.setLeadingIcon(primary,
                hasCues ? R.drawable.ic_control_translate : R.drawable.ic_control_generate,
                PlayerAppearance.primaryIconTint(this, PlayerAppearance.IconRole.TRANSCRIPT), 0);
        primary.setCompoundDrawablePadding(dp(8));
        primary.setText(hasCues ? (translated ? "Dịch lại" : "Dịch") : "Tạo transcript");
        cancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        VideoSession.TaskProgress taskProgress = session.runningTask == VideoSession.Task.TRANSLATION
                ? session.translationProgress : null;
        String progressText = taskProgress != null ? taskProgress.label() : null;
        if (busy && TextUtils.isEmpty(progressText) && session.runningTask != null && status.length() == 0) {
            progressText = session.runningTask.runningLabel;
        }
        if (!TextUtils.equals(progressLabel.getText(), progressText == null ? "" : progressText)) {
            progressLabel.setText(progressText);
        }
        int total = taskProgress == null ? session.progressTotal : taskProgress.total;
        progress.setIndeterminate(total == 0);
        if (total > 0) {
            progress.setMax(total);
            progress.setProgress(taskProgress == null ? session.progressCompleted : taskProgress.completed);
        }
        if (message != null) {
            showStatus(message);
            if (!busy || (session.isContextTranslationRunning()
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
        updateModeIcon(subtitlesButton, R.drawable.ic_control_captions, "Phụ đề", display);
        updateModeIcon(speechButton, read == CueTimeline.Mode.OFF ? R.drawable.ic_control_mute
                : R.drawable.ic_control_voice, "Phát âm", read);
        if (speech != null) sync();
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
        long positionMs = player.getCurrentPosition();
        TranscriptCue cue = session.timeline.at(positionMs);
        if (transcriptPanel.isShown()) {
            int current = cue == null ? -1 : session.cues.indexOf(cue);
            if (current != activeRow) {
                activeRow = current;
                cueAdapter.notifyDataSetChanged();
                if (follow && current >= 0) cueList.setSelection(current);
            }
        }
        String text = display == CueTimeline.Mode.SOURCE && cue != null
                ? cue.sourceSubtitle(positionMs) : display.text(cue);
        if (!text.contentEquals(subtitle.getText())) subtitle.setText(text);
        subtitle.setVisibility(text.isEmpty() ? TextView.GONE : TextView.VISIBLE);

        boolean translatedSpeech = read == CueTimeline.Mode.TRANSLATION;
        if (session.hasTranscriptPreview() || read == CueTimeline.Mode.OFF || !player.isPlaying() || settings.dubbingVolume == 0
                || (translatedSpeech && (cue == null || !cue.hasTranslation()))) {
            speech.stop();
        } else {
            String language = read.speechLanguage(cue, session.sourceLanguage,
                    session.targetLanguage);
            speech.update(cue, read.text(cue), language, settings.dubbingVolume / 100f, effectiveVoice(language, translatedSpeech));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshVisibleStorage();
        if (modelForm != null && modelTab == ModelTab.VOICE) renderModels();
    }

    @Override
    protected void onPause() {
        if (sentenceSplitDialog != null) sentenceSplitDialog.dismiss();
        if (modelVoicePreview != null) modelVoicePreview.stop();
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        home.setPerformanceSamplingAllowed(settingsScreen == null);
        if (hasVideo) updateControls(work.status());
        else updateHomeTask();
        showTranscriptReview();
        PlaybackException error = player.getPlayerError();
        if (hasVideo && error != null && error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) {
            recoverReclaimedDecoder(error);
        }
        main.post(tick);
    }

    @Override
    protected void onStop() {
        if (transcriptReviewDialog != null) transcriptReviewDialog.dismiss();
        if (webVideoPlayer != null) webVideoPlayer.dismiss();
        if (videoDownloads != null) videoDownloads.closeWebSearch();
        if (telegramContent != null) telegramContent.stopInlinePlayback();
        started = false;
        home.setPerformanceSamplingAllowed(false);
        cancelDecoderRetry();
        main.removeCallbacks(tick);
        main.removeCallbacks(contextClock);
        main.removeCallbacks(hideControls);
        player.pause();
        speech.stop();
        if (audioPreview != null) audioPreview.stop();
        savePlaybackSession();
        super.onStop();
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
        if (isChangingConfigurations()) {
            if (telegramSession instanceof TdJsonTelegramSessionClient
                    && videoDownloads.usesTelegramSession(telegramSession)) {
                downloadState.detachedTelegram = (TdJsonTelegramSessionClient) telegramSession;
            }
            videoDownloads.detach();
            downloadState.detachedFlow = videoDownloads;
        } else {
            videoDownloads.close();
        }
        savedVideosDialog.close();
        home.close();
        speech.close();
        if (telegramSession instanceof TdJsonTelegramSessionClient) {
            TdJsonTelegramSessionClient client = (TdJsonTelegramSessionClient) telegramSession;
            if (downloadState.detachedTelegram == client) client.detachListener();
            else client.closeTransport();
        }
        super.onDestroy();
    }

    int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
