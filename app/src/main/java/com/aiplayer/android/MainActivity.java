package com.aiplayer.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.annotation.OptIn;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final String TAG = "AIPlayer";
    private static final int REQUEST_VIDEO = 1001;
    private static final int REQUEST_TRANSCRIPT = 1002;
    private static final int REQUEST_RECORD_AUDIO = 2001;
    private static final String BROWSER_HOME_URL = "https://www.google.com";
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final Pattern ABSOLUTE_MEDIA_PATTERN = Pattern.compile(
            "(https?:)?//[^\"'\\\\\\s<>]+\\.(?:mp4|m4v|m3u8|webm|mkv|mov)(?:\\?[^\"'\\\\\\s<>]*)?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern QUOTED_MEDIA_PATTERN = Pattern.compile(
            "[\"']([^\"']+\\.(?:mp4|m4v|m3u8|webm|mkv|mov)(?:\\?[^\"']*)?)[\"']",
            Pattern.CASE_INSENSITIVE
    );
    private static final int CAPTION_HLS_MAX_SEGMENTS = 12;
    private static final long CAPTION_HLS_MAX_WINDOW_MS = 75_000L;
    private static final long CAPTION_HLS_LOOK_BEHIND_MS = 3_000L;
    private static final long CAPTION_PREFETCH_THRESHOLD_MS = 90_000L;
    private static final long CAPTION_DUPLICATE_TOLERANCE_MS = 350L;
    private static final long SUBTITLE_NEARBY_TOLERANCE_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TranslationClient translationClient = new TranslationClient();
    private final YoutubeCaptionClient youtubeCaptionClient = new YoutubeCaptionClient();
    private final List<TranscriptCue> cues = new ArrayList<>();
    private final List<String> browserUrlAttempts = new ArrayList<>();
    private final List<String> runtimeExtractedMediaUrls = new ArrayList<>();

    private AppSettings settings;
    private LiveSpeechRecognizer liveSpeechRecognizer;
    private OriginalAudioCaptionGenerator originalAudioCaptionGenerator;
    private Uri localVideoUri;
    private VideoView videoView;
    private PlayerView playerView;
    private ExoPlayer exoPlayer;
    private WebView webView;
    private MediaPlayer mediaPlayer;
    private LinearLayout rootView;
    private LinearLayout headerView;
    private LinearLayout controlsView;
    private LinearLayout browserActionsView;
    private LinearLayout playerActionsView;
    private LinearLayout timelineView;
    private ScrollView transcriptScrollView;
    private TextView modeLabel;
    private TextView sourceLabel;
    private TextView detectedMediaLabel;
    private TextView subtitleView;
    private TextView transcriptView;
    private TextView timeLabel;
    private TextView statusLabel;
    private Button openExternalButton;
    private SeekBar positionBar;
    private SeekBar sourceVolumeBar;
    private SeekBar dubbingVolumeBar;
    private CheckBox targetSubtitleCheck;
    private CheckBox dubbingCheck;
    private Button microphoneButton;
    private TextToSpeech tts;
    private boolean seeking;
    private boolean fullscreen;
    private boolean webPositionQueryPending;
    private boolean transcriptSheetVisible;
    private boolean nativePlayerFullscreen;
    private int webPositionMs;
    private int webDurationMs;
    private int activeCueIndex = -1;
    private String loadedYoutubeCaptionVideoId;
    private String mediaRefererUrl;
    private String mediaCookieHeader;
    private String currentPageUrl;
    private String currentMediaUrl;
    private String lastAutoCaptionSourceKey;
    private String lastAutoTranslateSourceKey;
    private boolean autoCaptionInProgress;
    private long autoCaptionRequestPositionMs = -1;
    private String autoCaptionRequestSourceKey;
    private long lastCaptionBufferRequestMs = -1;
    private long captionBufferScannedUntilMs = -1;
    private String directExtractionPageUrl;
    private String runtimeExtractionPageUrl;
    private boolean runtimeExtractionActive;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshPlayback();
            handler.postDelayed(this, 250);
        }
    };

    private final Runnable hideChromeRunnable = new Runnable() {
        @Override
        public void run() {
            hideChrome();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        settings = AppSettings.load(this);
        tts = new TextToSpeech(this, this);
        originalAudioCaptionGenerator = new OriginalAudioCaptionGenerator(this);
        liveSpeechRecognizer = new LiveSpeechRecognizer(this, new LiveSpeechRecognizer.Listener() {
            @Override
            public void onPartial(String text) {
                runOnUiThread(() -> showSubtitle(text));
            }

            @Override
            public void onFinal(String text) {
                runOnUiThread(() -> addLiveCue(text));
            }

            @Override
            public void onStatus(String status) {
                runOnUiThread(() -> status(status));
            }
        });
        setContentView(buildUi());
        if (!handleIncomingIntent(getIntent())) {
            openInitialBrowser();
        }
        setFullscreen(true);
        handler.post(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (liveSpeechRecognizer != null) {
            liveSpeechRecognizer.close();
        }
        if (originalAudioCaptionGenerator != null) {
            originalAudioCaptionGenerator.close();
        }
        translationClient.close();
        youtubeCaptionClient.close();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        releaseExoPlayer();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (transcriptSheetVisible) {
            toggleTranscriptSheet();
            return;
        }
        if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("vi", "VN"));
            tts.setSpeechRate(1.0f);
        }
    }

    private View buildUi() {
        int pad = dp(12);
        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(getColor(R.color.shared_bg));

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(getColor(R.color.shared_bg));
        rootView.addView(screen, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        FrameLayout mediaFrame = new FrameLayout(this);
        mediaFrame.setBackgroundColor(Color.BLACK);
        wireChromeReveal(mediaFrame);
        screen.addView(mediaFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        videoView = new VideoView(this);
        videoView.setOnPreparedListener(mp -> {
            mediaPlayer = mp;
            applySourceVolume();
            status("Player ready.");
            maybeAutoCaptionTranslateCurrentVideo();
            if (nativePlayerFullscreen) {
                hideChrome();
            }
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            status("Native player cannot open this URL. Use Browser Mode or a direct media URL.");
            Toast.makeText(this, "Cannot play this video source.", Toast.LENGTH_LONG).show();
            return true;
        });
        wireChromeReveal(videoView);
        mediaFrame.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setVisibility(View.GONE);
        wireChromeReveal(playerView);
        mediaFrame.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(false);
        webSettings.setSupportMultipleWindows(false);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webSettings.setUserAgentString(webSettings.getUserAgentString()
                + " AIPlayer/0.1 Mobile Safari/537.36");
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);
        webView.addJavascriptInterface(new BrowserBridge(), "AIPlayerBridge");
        webSettings.setSafeBrowsingEnabled(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleWebNavigation(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (handleExtractablePageNavigation(url)) {
                    view.stopLoading();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                syncBrowserPage(url);
                if (BrowserNavigationPolicy.canInjectExtractableNavigationBridge(url)) {
                    injectExtractableNavigationBridge();
                }
                inspectRuntimePageHtml();
                updateModeChrome();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    if (!tryAlternateBrowserUrl(request.getUrl())) {
                        status("Cannot open page: " + error.getDescription());
                    }
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    observeRuntimeMediaUrl(request.getUrl().toString());
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
        webView.setBackgroundColor(Color.WHITE);
        webView.setVisibility(View.GONE);
        wireChromeReveal(webView);
        mediaFrame.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        subtitleView = new TextView(this);
        subtitleView.setTextColor(Color.WHITE);
        subtitleView.setTextSize(19);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setBackgroundColor(Color.argb(170, 0, 0, 0));
        subtitleView.setPadding(dp(12), dp(8), dp(12), dp(8));
        subtitleView.setVisibility(View.GONE);
        wireChromeReveal(subtitleView);
        FrameLayout.LayoutParams subParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        subParams.setMargins(dp(18), 0, dp(18), dp(112) + navigationBarInset());
        mediaFrame.addView(subtitleView, subParams);

        headerView = new LinearLayout(this);
        headerView.setOrientation(LinearLayout.VERTICAL);
        headerView.setBackgroundColor(getColor(R.color.shared_bg));
        headerView.setPadding(pad, statusBarHeight() + dp(8), pad, dp(8));
        wireChromeReveal(headerView);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setOrientation(LinearLayout.HORIZONTAL);

        modeLabel = label("Browser");
        modeLabel.setTextColor(getColor(R.color.shared_accent_ink));
        modeLabel.setTextSize(12);
        modeLabel.setGravity(Gravity.CENTER);
        modeLabel.setBackgroundColor(getColor(R.color.shared_accent));
        topBar.addView(modeLabel, new LinearLayout.LayoutParams(dp(78), dp(38)));

        sourceLabel = new TextView(this);
        sourceLabel.setText("AI Player");
        sourceLabel.setTextColor(getColor(R.color.shared_text));
        sourceLabel.setTextSize(13);
        sourceLabel.setSingleLine(true);
        sourceLabel.setGravity(Gravity.CENTER_VERTICAL);
        sourceLabel.setPadding(dp(10), 0, dp(10), 0);
        sourceLabel.setBackgroundColor(getColor(R.color.shared_panel));
        sourceLabel.setOnClickListener(v -> showOpenUrlDialog());
        wireChromeReveal(sourceLabel);
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(0, dp(38), 1);
        sourceParams.setMargins(dp(8), 0, 0, 0);
        topBar.addView(sourceLabel, sourceParams);
        headerView.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
        ));

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setGravity(Gravity.CENTER_VERTICAL);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.addView(button("URL", v -> showOpenUrlDialog()));
        quickActions.addView(button("Local", v -> openVideoFile()));
        quickActions.addView(button("Transcript", v -> openTranscriptFile()));
        quickActions.addView(button("Settings", v -> showSettingsDialog()));
        HorizontalScrollView quickScroll = new HorizontalScrollView(this);
        quickScroll.setHorizontalScrollBarEnabled(false);
        quickScroll.addView(quickActions);
        headerView.addView(quickScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        ));
        screen.addView(headerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        ));

        controlsView = new LinearLayout(this);
        controlsView.setOrientation(LinearLayout.VERTICAL);
        controlsView.setPadding(pad, dp(8), pad, navigationBarInset() + dp(10));
        controlsView.setBackgroundColor(getColor(R.color.shared_bg));
        wireChromeReveal(controlsView);

        browserActionsView = new LinearLayout(this);
        browserActionsView.setGravity(Gravity.CENTER_VERTICAL);
        browserActionsView.setOrientation(LinearLayout.HORIZONTAL);
        browserActionsView.addView(button("Back", v -> {
            if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
                webView.goBack();
            }
            showChromeTemporarily();
        }));
        browserActionsView.addView(button("Reload", v -> reloadCurrentPage()));
        browserActionsView.addView(button("Detect", v -> detectCurrentPageVideo()));
        browserActionsView.addView(button("Sources", v -> showDetectedSourcesDialog()));
        openExternalButton = button("External", v -> openCurrentSourceExternally());
        browserActionsView.addView(openExternalButton);
        detectedMediaLabel = label("No media");
        detectedMediaLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        browserActionsView.addView(detectedMediaLabel, new LinearLayout.LayoutParams(0, dp(42), 1));
        controlsView.addView(browserActionsView);

        playerActionsView = new LinearLayout(this);
        playerActionsView.setGravity(Gravity.CENTER_VERTICAL);
        playerActionsView.setOrientation(LinearLayout.HORIZONTAL);
        playerActionsView.addView(button("Play", v -> togglePlayback()));
        playerActionsView.addView(button("AI", v -> showAiPanel()));
        playerActionsView.addView(button("Transcript", v -> toggleTranscriptSheet()));
        playerActionsView.addView(button("Source", v -> showSourcePanel()));
        timeLabel = new TextView(this);
        timeLabel.setText("00:00 / 00:00");
        timeLabel.setTextColor(getColor(R.color.shared_text));
        timeLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        playerActionsView.addView(timeLabel, new LinearLayout.LayoutParams(0, dp(42), 1));
        controlsView.addView(playerActionsView);

        timelineView = new LinearLayout(this);
        timelineView.setOrientation(LinearLayout.VERTICAL);
        positionBar = new SeekBar(this);
        positionBar.setMax(1000);
        positionBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && webView.getVisibility() == View.VISIBLE && webDurationMs > 0) {
                    int targetMs = (webDurationMs * progress) / 1000;
                    webView.evaluateJavascript(webVideoScript("if(v){v.currentTime=" + (targetMs / 1000.0) + ";}"), null);
                } else if (fromUser && nativeDurationMs() > 0) {
                    seekNativeTo((nativeDurationMs() * progress) / 1000);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seeking = true;
                showChrome();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seeking = false;
                scheduleChromeHide();
            }
        });
        timelineView.addView(positionBar);
        controlsView.addView(timelineView);

        screen.addView(controlsView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        ));

        transcriptView = new TextView(this);
        transcriptView.setText("Transcript will appear here.");
        transcriptView.setTextColor(getColor(R.color.shared_text));
        transcriptView.setTextSize(15);
        transcriptView.setPadding(pad, pad, pad, pad);
        transcriptScrollView = new ScrollView(this);
        transcriptScrollView.setBackgroundColor(getColor(R.color.shared_panel));
        transcriptScrollView.addView(transcriptView);
        transcriptScrollView.setVisibility(View.GONE);
        int sheetHeight = Math.max(dp(280), getResources().getDisplayMetrics().heightPixels / 2);
        FrameLayout.LayoutParams transcriptParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                sheetHeight,
                Gravity.BOTTOM
        );
        transcriptParams.setMargins(dp(10), 0, dp(10), navigationBarInset() + dp(10));
        screen.addView(transcriptScrollView, transcriptParams);

        statusLabel = new TextView(this);
        statusLabel.setText("Ready.");
        statusLabel.setTextColor(getColor(R.color.shared_text));
        statusLabel.setTextSize(13);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setBackgroundColor(getColor(R.color.shared_panel));
        statusLabel.setPadding(dp(12), dp(6), dp(12), dp(6));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        statusParams.setMargins(dp(18), 0, dp(18), dp(160) + navigationBarInset());
        screen.addView(statusLabel, statusParams);

        updateModeChrome();
        showChromeTemporarily();
        return rootView;
    }

    private void wireChromeReveal(View view) {
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                showChromeTemporarily();
            }
            return false;
        });
    }

    private void toggleChrome() {
        if (headerView != null && headerView.getVisibility() == View.VISIBLE) {
            hideChrome();
        } else {
            showChromeTemporarily();
        }
    }

    private void showChrome() {
        updateModeChrome();
        if (headerView != null) {
            headerView.setVisibility(View.VISIBLE);
        }
        if (controlsView != null) {
            controlsView.setVisibility(View.VISIBLE);
        }
        if (statusLabel != null) {
            statusLabel.setVisibility(View.VISIBLE);
        }
        if (openExternalButton != null) {
            openExternalButton.setVisibility(webView != null && webView.getVisibility() == View.VISIBLE
                    ? View.VISIBLE
                    : View.GONE);
        }
        updateSubtitleVisibility();
    }

    private boolean isBrowserMode() {
        return webView != null && webView.getVisibility() == View.VISIBLE;
    }

    private void updateModeChrome() {
        boolean browserMode = isBrowserMode();
        if (modeLabel != null) {
            modeLabel.setText(browserMode ? "Browser" : "Player");
            modeLabel.setBackgroundColor(browserMode
                    ? getColor(R.color.shared_accent)
                    : getColor(R.color.shared_success));
        }
        if (browserActionsView != null) {
            browserActionsView.setVisibility(browserMode ? View.VISIBLE : View.GONE);
        }
        if (playerActionsView != null) {
            playerActionsView.setVisibility(browserMode ? View.GONE : View.VISIBLE);
        }
        if (timelineView != null) {
            timelineView.setVisibility(browserMode ? View.GONE : View.VISIBLE);
        }
        if (openExternalButton != null) {
            openExternalButton.setVisibility(browserMode ? View.VISIBLE : View.GONE);
        }
        updateDetectedMediaCount();
    }

    private void updateDetectedMediaCount() {
        if (detectedMediaLabel == null) {
            return;
        }
        int count;
        synchronized (runtimeExtractedMediaUrls) {
            count = runtimeExtractedMediaUrls.size();
        }
        detectedMediaLabel.setText(count == 0 ? "No media" : count + " media");
    }

    private void showChromeTemporarily() {
        showChrome();
        scheduleChromeHide();
    }

    private void scheduleChromeHide() {
        handler.removeCallbacks(hideChromeRunnable);
        handler.postDelayed(hideChromeRunnable, 4500);
    }

    private void hideChrome() {
        if (transcriptSheetVisible || seeking) {
            return;
        }
        if (headerView != null) {
            headerView.setVisibility(View.GONE);
        }
        if (controlsView != null) {
            controlsView.setVisibility(View.GONE);
        }
        if (statusLabel != null) {
            statusLabel.setVisibility(View.GONE);
        }
        updateSubtitleVisibility();
    }

    private void toggleTranscriptSheet() {
        if (!transcriptSheetVisible && cues.isEmpty()) {
            transcriptView.setText("Chưa có transcript.\n\nHãy nạp file .srt/.vtt/.txt, bật Micro, hoặc dùng Caption âm gốc cho video cục bộ.");
            status("Chưa có transcript để hiển thị.");
        }
        transcriptSheetVisible = !transcriptSheetVisible;
        if (transcriptScrollView != null) {
            transcriptScrollView.setVisibility(transcriptSheetVisible ? View.VISIBLE : View.GONE);
        }
        if (transcriptSheetVisible) {
            showChrome();
        } else {
            showChromeTemporarily();
        }
    }

    private void showSubtitle(String text) {
        if (subtitleView == null) {
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            hideSubtitle();
            return;
        }
        subtitleView.setText(text);
        subtitleView.setVisibility(View.VISIBLE);
        subtitleView.bringToFront();
        if (statusLabel != null) {
            statusLabel.setVisibility(View.GONE);
        }
    }

    private void hideSubtitle() {
        if (subtitleView != null) {
            subtitleView.setText("");
            subtitleView.setVisibility(View.GONE);
        }
    }

    private void updateSubtitleVisibility() {
        if (subtitleView == null) {
            return;
        }
        CharSequence text = subtitleView.getText();
        subtitleView.setVisibility(text == null || text.toString().trim().isEmpty()
                ? View.GONE
                : View.VISIBLE);
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setMinWidth(dp(40));
        button.setTextColor(getColor(R.color.shared_text));
        button.setTextSize(13);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundColor(getColor(R.color.shared_surface));
        button.setOnClickListener(listener);
        return button;
    }

    private Button panelButton(String text, View.OnClickListener listener) {
        Button button = button(text, listener);
        button.setTextColor(getColor(R.color.shared_text));
        button.setBackgroundColor(getColor(R.color.shared_accent_soft));
        return button;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.shared_muted));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(6), 0, dp(6), 0);
        return view;
    }

    private SeekBar seek(int min, int max, int value) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        return bar;
    }

    private SeekBar.OnSeekBarChangeListener simpleSeek(IntConsumer consumer) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    consumer.accept(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    private TextView panelText(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private void showAiPanel() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, pad, pad, 0);

        form.addView(panelText("AI is only shown in Player Mode: subtitles, translation, and voice.", 14, getColor(R.color.shared_muted)));

        form.addView(panelText("Subtitles", 16, getColor(R.color.shared_text)));
        LinearLayout subtitleActions = new LinearLayout(this);
        subtitleActions.setOrientation(LinearLayout.HORIZONTAL);
        subtitleActions.addView(panelButton("Load file", v -> openTranscriptFile()));
        subtitleActions.addView(panelButton("Audio caption", v -> generateOriginalAudioCaptionsFromCurrentVideo()));
        microphoneButton = panelButton(liveSpeechRecognizer.isRunning() ? "Stop mic" : "Mic live", v -> toggleMicrophoneCapture());
        subtitleActions.addView(microphoneButton);
        HorizontalScrollView subtitleScroll = new HorizontalScrollView(this);
        subtitleScroll.setHorizontalScrollBarEnabled(false);
        subtitleScroll.addView(subtitleActions);
        form.addView(subtitleScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        targetSubtitleCheck = new CheckBox(this);
        targetSubtitleCheck.setText("Show translated subtitle");
        targetSubtitleCheck.setChecked(settings.showTargetSubtitle);
        targetSubtitleCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.showTargetSubtitle = isChecked;
            settings.save(this);
            renderTranscript();
            refreshSubtitle(currentPlaybackMs());
        });
        form.addView(targetSubtitleCheck);

        form.addView(panelText("Translation", 16, getColor(R.color.shared_text)));
        form.addView(panelText("Current pair: " + settings.sourceLanguage + " -> " + settings.targetLanguage, 14, getColor(R.color.shared_muted)));
        LinearLayout translationActions = new LinearLayout(this);
        translationActions.setOrientation(LinearLayout.HORIZONTAL);
        translationActions.addView(panelButton("Translate", v -> translateTranscript()));
        translationActions.addView(panelButton("Languages", v -> showSettingsDialog()));
        form.addView(translationActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        form.addView(panelText("Voice", 16, getColor(R.color.shared_text)));
        dubbingCheck = new CheckBox(this);
        dubbingCheck.setText("Read/dub translated subtitle");
        dubbingCheck.setChecked(settings.dubbingEnabled);
        dubbingCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.dubbingEnabled = isChecked;
            settings.save(this);
        });
        form.addView(dubbingCheck);

        LinearLayout sourceVolume = new LinearLayout(this);
        sourceVolume.setGravity(Gravity.CENTER_VERTICAL);
        sourceVolume.addView(panelText("Source", 13, getColor(R.color.shared_muted)));
        sourceVolumeBar = seek(0, 100, settings.sourceVolume);
        sourceVolumeBar.setOnSeekBarChangeListener(simpleSeek(value -> {
            settings.sourceVolume = value;
            settings.save(this);
            applySourceVolume();
        }));
        sourceVolume.addView(sourceVolumeBar, new LinearLayout.LayoutParams(0, dp(42), 1));
        form.addView(sourceVolume);

        LinearLayout voiceVolume = new LinearLayout(this);
        voiceVolume.setGravity(Gravity.CENTER_VERTICAL);
        voiceVolume.addView(panelText("Voice", 13, getColor(R.color.shared_muted)));
        dubbingVolumeBar = seek(0, 100, settings.dubbingVolume);
        dubbingVolumeBar.setOnSeekBarChangeListener(simpleSeek(value -> {
            settings.dubbingVolume = value;
            settings.save(this);
            applyTtsVolume();
        }));
        voiceVolume.addView(dubbingVolumeBar, new LinearLayout.LayoutParams(0, dp(42), 1));
        form.addView(voiceVolume);

        new AlertDialog.Builder(this)
                .setTitle("AI Panel")
                .setView(form)
                .setPositiveButton("Close", null)
                .show();
    }

    private void showSourcePanel() {
        if (isBrowserMode()) {
            showDetectedSourcesDialog();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, pad, pad, 0);
        form.addView(panelText("Current source", 16, getColor(R.color.shared_text)));
        form.addView(panelText(sourceLabel == null ? "" : sourceLabel.getText().toString(), 13, getColor(R.color.shared_muted)));

        LinearLayout primary = new LinearLayout(this);
        primary.setOrientation(LinearLayout.HORIZONTAL);
        primary.addView(panelButton("Browser", v -> {
            if (currentPageUrl != null && !currentPageUrl.trim().isEmpty()) {
                openBrowserPageWithoutExtraction(currentPageUrl);
            } else {
                openInitialBrowser();
            }
        }));
        primary.addView(panelButton("Open URL", v -> showOpenUrlDialog()));
        primary.addView(panelButton("Local", v -> openVideoFile()));
        primary.addView(panelButton("External", v -> openCurrentSourceExternally()));
        HorizontalScrollView primaryScroll = new HorizontalScrollView(this);
        primaryScroll.setHorizontalScrollBarEnabled(false);
        primaryScroll.addView(primary);
        form.addView(primaryScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        ));

        LinearLayout downloads = new LinearLayout(this);
        downloads.setOrientation(LinearLayout.HORIZONTAL);
        downloads.addView(panelButton("Cache", v -> cacheBestVideo()));
        downloads.addView(panelButton("Save", v -> saveBestVideo()));
        form.addView(panelText("Advanced", 16, getColor(R.color.shared_text)));
        form.addView(downloads, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        ));

        new AlertDialog.Builder(this)
                .setTitle("Source")
                .setView(form)
                .setPositiveButton("Close", null)
                .show();
    }

    private void detectCurrentPageVideo() {
        String pageUrl = currentPageUrl;
        if ((pageUrl == null || pageUrl.trim().isEmpty()) && sourceLabel != null) {
            pageUrl = sourceLabel.getText().toString().trim();
        }
        if (pageUrl == null || pageUrl.trim().isEmpty()) {
            showOpenUrlDialog();
            return;
        }
        synchronized (runtimeExtractedMediaUrls) {
            if (!runtimeExtractedMediaUrls.isEmpty()) {
                finalizeRuntimeMediaExtraction(true);
                return;
            }
        }
        startRuntimeMediaExtraction(pageUrl, "Scanning this page for playable media.");
    }

    private void showDetectedSourcesDialog() {
        List<String> urls;
        synchronized (runtimeExtractedMediaUrls) {
            urls = new ArrayList<>(runtimeExtractedMediaUrls);
        }
        if (urls.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Detected Sources")
                    .setMessage("No media source has been detected yet. Play the video in Browser Mode or run Detect.")
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Detect", (dialog, which) -> detectCurrentPageVideo())
                    .show();
            return;
        }
        String[] labels = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++) {
            labels[i] = sourceLabelForMedia(urls.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("Detected Sources")
                .setItems(labels, (dialog, which) -> {
                    String pageUrl = currentPageUrl == null ? sourceLabel.getText().toString() : currentPageUrl;
                    openExtractedMediaNative(urls.get(which), pageUrl);
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private String sourceLabelForMedia(String url) {
        if (url == null) {
            return "Media";
        }
        String lower = url.toLowerCase(Locale.US);
        String type = isHlsUrl(url) ? "HLS" : "Media";
        Matcher quality = Pattern.compile("(2160|1440|1080|720|480|360|240)p").matcher(lower);
        if (quality.find()) {
            type += " " + quality.group(1) + "p";
        }
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        return type + (host == null ? "" : " - " + host);
    }

    private void openVideoFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        startActivityForResult(intent, REQUEST_VIDEO);
    }

    private void openTranscriptFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_TRANSCRIPT);
    }

    private void showOpenUrlDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("google.com hoặc từ khóa tìm kiếm");
        new AlertDialog.Builder(this)
                .setTitle("Mở trang web / video")
                .setView(input)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Mở", (dialog, which) -> openBrowserUrl(input.getText().toString()))
                .show();
    }

    private void showMoreActionsDialog() {
        String subtitleItem = settings.showTargetSubtitle ? "Ẩn phụ đề dịch" : "Hiện phụ đề dịch";
        String dubbingItem = settings.dubbingEnabled ? "Tắt đọc/lồng tiếng" : "Bật đọc/lồng tiếng";
        String microItem = liveSpeechRecognizer.isRunning() ? "Dừng micro" : "Bật micro";
        String[] items = new String[]{
                "Mở URL / tìm kiếm",
                "Mở video",
                "Nạp transcript",
                "Mở trang hiện tại bên ngoài",
                "Tải lại trang",
                "Dừng phát",
                "Cache video tốt nhất",
                "Lưu video tốt nhất",
                subtitleItem,
                dubbingItem,
                microItem,
                "Tạo caption âm gốc",
                "Dịch transcript",
                "Cài đặt AI",
                "Thông tin âm hệ thống"
        };
        new AlertDialog.Builder(this)
                .setTitle("More")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showOpenUrlDialog();
                            break;
                        case 1:
                            openVideoFile();
                            break;
                        case 2:
                            openTranscriptFile();
                            break;
                        case 3:
                            openCurrentSourceExternally();
                            break;
                        case 4:
                            reloadCurrentPage();
                            break;
                        case 5:
                            stopPlayback();
                            break;
                        case 6:
                            cacheBestVideo();
                            break;
                        case 7:
                            saveBestVideo();
                            break;
                        case 8:
                            toggleTargetSubtitle();
                            break;
                        case 9:
                            toggleDubbing();
                            break;
                        case 10:
                            toggleMicrophoneCapture();
                            break;
                        case 11:
                            generateOriginalAudioCaptionsFromCurrentVideo();
                            break;
                        case 12:
                            translateTranscript();
                            break;
                        case 13:
                            showSettingsDialog();
                            break;
                        case 14:
                            showSystemAudioInfo();
                            break;
                        default:
                            break;
                    }
                    showChromeTemporarily();
                })
                .show();
    }

    private void reloadCurrentPage() {
        if (webView.getVisibility() == View.VISIBLE) {
            webView.reload();
        } else if (exoPlayer != null) {
            exoPlayer.prepare();
            exoPlayer.play();
        } else {
            videoView.resume();
        }
        showChromeTemporarily();
    }

    private void toggleTargetSubtitle() {
        settings.showTargetSubtitle = !settings.showTargetSubtitle;
        settings.save(this);
        if (targetSubtitleCheck != null) {
            targetSubtitleCheck.setChecked(settings.showTargetSubtitle);
        }
        renderTranscript();
        status(settings.showTargetSubtitle ? "Đã bật phụ đề dịch." : "Đã tắt phụ đề dịch.");
    }

    private void toggleDubbing() {
        settings.dubbingEnabled = !settings.dubbingEnabled;
        settings.save(this);
        if (dubbingCheck != null) {
            dubbingCheck.setChecked(settings.dubbingEnabled);
        }
        if (!settings.dubbingEnabled && tts != null) {
            tts.stop();
        }
        status(settings.dubbingEnabled ? "Đã bật đọc/lồng tiếng." : "Đã tắt đọc/lồng tiếng.");
    }

    private void saveBestVideo() {
        downloadBestVideo(false);
    }

    private void cacheBestVideo() {
        downloadBestVideo(true);
    }

    private void downloadBestVideo(boolean cacheOnly) {
        String pageUrl = currentPageUrl;
        String mediaUrl = currentMediaUrl;
        String sourceUrl = sourceLabel == null ? "" : sourceLabel.getText().toString().trim();
        if ((pageUrl == null || pageUrl.trim().isEmpty()) && shouldExtractMediaFromPage(sourceUrl)) {
            pageUrl = sourceUrl;
        }
        if ((mediaUrl == null || mediaUrl.trim().isEmpty()) && isDirectMediaUrl(Uri.parse(sourceUrl))) {
            mediaUrl = sourceUrl;
        }
        if ((pageUrl == null || pageUrl.trim().isEmpty()) && (mediaUrl == null || mediaUrl.trim().isEmpty())) {
            status("Chưa có video để lưu.");
            return;
        }

        String finalPageUrl = pageUrl;
        String finalMediaUrl = mediaUrl;
        File targetDirectory = cacheOnly ? cacheVideoDirectory() : savedVideoDirectory();
        status(cacheOnly ? "Đang cache video tốt nhất..." : "Đang chuẩn bị lưu video tốt nhất...");
        new Thread(() -> {
            try {
                String referer = finalPageUrl == null ? mediaRefererUrl : finalPageUrl;
                String bestMedia = finalMediaUrl;
                if (finalPageUrl != null && !finalPageUrl.trim().isEmpty()) {
                    bestMedia = extractBestMediaFromPage(finalPageUrl);
                    referer = finalPageUrl;
                } else if (bestMedia != null && isHlsUrl(bestMedia)) {
                    String variant = chooseBestHlsVariant(bestMedia, referer);
                    if (variant != null) {
                        bestMedia = variant;
                    }
                }
                if (bestMedia == null) {
                    throw new IllegalStateException("Không tìm thấy media trực tiếp");
                }

                File saved = isHlsUrl(bestMedia)
                        ? saveHlsOffline(bestMedia, referer, targetDirectory)
                        : saveDirectMedia(bestMedia, referer, targetDirectory);
                if (cacheOnly) {
                    trimVideoCache();
                }
                runOnUiThread(() -> status((cacheOnly ? "Đã cache video: " : "Đã lưu video: ") + saved.getAbsolutePath()));
            } catch (Exception ex) {
                runOnUiThread(() -> status((cacheOnly ? "Không cache được video: " : "Không lưu được video: ") + ex.getMessage()));
            }
        }).start();
    }

    private File saveDirectMedia(String mediaUrl, String referer) throws Exception {
        return saveDirectMedia(mediaUrl, referer, savedVideoDirectory());
    }

    private File saveDirectMedia(String mediaUrl, String referer, File directory) throws Exception {
        File output = new File(directory, safeVideoName(mediaUrl) + extensionForUrl(mediaUrl, ".mp4"));
        downloadToFile(mediaUrl, referer, output);
        writeText(new File(directory, output.getName() + ".meta.txt"),
                "type=direct\nurl=" + mediaUrl + "\nreferer=" + nullToEmpty(referer) + "\nfile=" + output.getName() + "\n");
        return output;
    }

    private File saveHlsOffline(String playlistUrl, String referer) throws Exception {
        return saveHlsOffline(playlistUrl, referer, savedVideoDirectory());
    }

    private File saveHlsOffline(String playlistUrl, String referer, File baseDirectory) throws Exception {
        String bestPlaylist = chooseBestHlsVariant(playlistUrl, referer);
        if (bestPlaylist != null) {
            playlistUrl = bestPlaylist;
        }
        File directory = new File(baseDirectory, safeVideoName(playlistUrl));
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Không tạo được thư mục lưu");
        }

        String playlist = fetchTextUrl(playlistUrl, referer);
        StringBuilder localPlaylist = new StringBuilder();
        String[] lines = playlist.split("\\r?\\n");
        int segmentIndex = 0;
        int keyIndex = 0;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("#EXT-X-KEY")) {
                String keyUri = hlsAttribute(line, "URI");
                String keyUrl = resolveMediaUrl(keyUri, playlistUrl);
                if (keyUrl != null) {
                    String keyName = String.format(Locale.US, "key_%03d.key", keyIndex++);
                    File keyFile = new File(directory, keyName);
                    downloadToFile(keyUrl, referer, keyFile);
                    localPlaylist.append(line.replace(keyUri, keyName)).append('\n');
                    continue;
                }
            }
            if (line.isEmpty() || line.startsWith("#")) {
                localPlaylist.append(rawLine).append('\n');
                continue;
            }
            String segmentUrl = resolveMediaUrl(line, playlistUrl);
            if (segmentUrl == null) {
                localPlaylist.append(rawLine).append('\n');
                continue;
            }
            String segmentName = String.format(Locale.US, "segment_%05d%s", segmentIndex, extensionForUrl(segmentUrl, ".ts"));
            File segmentFile = new File(directory, segmentName);
            downloadToFile(segmentUrl, referer, segmentFile);
            localPlaylist.append(segmentName).append('\n');
            segmentIndex++;
            if (segmentIndex % 10 == 0) {
                int count = segmentIndex;
                runOnUiThread(() -> status("Đã tải " + count + " segment..."));
            }
        }

        File playlistFile = new File(directory, "index.m3u8");
        writeText(playlistFile, localPlaylist.toString());
        writeText(new File(directory, "metadata.txt"),
                "type=hls\nurl=" + playlistUrl + "\nreferer=" + nullToEmpty(referer) + "\nsegments=" + segmentIndex + "\n");
        return playlistFile;
    }

    private HlsCaptionCache cacheHlsTransportStreamForCaption(String playlistUrl, String referer, long playbackPositionMs) throws Exception {
        if (mediaCookieHeader == null || mediaCookieHeader.trim().isEmpty()) {
            mediaCookieHeader = collectMediaCookies(playlistUrl, buildMediaHeaders(playlistUrl));
        }
        String bestPlaylist = chooseBestHlsVariant(playlistUrl, referer);
        if (bestPlaylist != null) {
            playlistUrl = bestPlaylist;
        }
        File directory = new File(cacheVideoDirectory(), safeVideoName(playlistUrl) + "-caption");
        if (directory.exists()) {
            deleteRecursively(directory);
        }
        if (!directory.mkdirs()) {
            throw new IllegalStateException("Khong tao duoc thu muc caption cache");
        }
        File output = new File(directory, "caption_source.ts");
        String playlist = fetchTextUrl(playlistUrl, referer);
        String[] lines = playlist.split("\\r?\\n");
        List<HlsSegment> segments = new ArrayList<>();
        byte[] aesKey = null;
        byte[] aesIv = null;
        long mediaSequence = 0;
        long pendingDurationMs = 6000;
        int segmentIndex = 0;
        long timelineMs = 0;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                mediaSequence = parseHlsMediaSequence(line);
                continue;
            }
            if (line.startsWith("#EXT-X-KEY")) {
                String method = hlsAttribute(line, "METHOD");
                if ("AES-128".equalsIgnoreCase(method)) {
                    String keyUrl = resolveMediaUrl(hlsAttribute(line, "URI"), playlistUrl);
                    aesKey = keyUrl == null ? null : downloadBytes(keyUrl, referer);
                    String rawIv = hlsAttribute(line, "IV");
                    aesIv = rawIv == null || rawIv.trim().isEmpty() ? null : parseHlsIv(rawIv, mediaSequence + segmentIndex);
                } else {
                    aesKey = null;
                    aesIv = null;
                }
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                pendingDurationMs = parseHlsDurationMs(line, pendingDurationMs);
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String segmentUrl = resolveMediaUrl(line, playlistUrl);
            if (segmentUrl == null) {
                continue;
            }
            long sequence = mediaSequence + segmentIndex;
            byte[] iv = aesIv == null ? null : aesIv.clone();
            segments.add(new HlsSegment(segmentUrl, timelineMs, pendingDurationMs, sequence, aesKey, iv));
            timelineMs += pendingDurationMs;
            segmentIndex++;
        }
        if (segments.isEmpty()) {
            throw new IllegalStateException("Playlist HLS khong co segment doc duoc");
        }

        long targetMs = Math.max(0, playbackPositionMs - CAPTION_HLS_LOOK_BEHIND_MS);
        int startIndex = 0;
        for (int i = 0; i < segments.size(); i++) {
            HlsSegment segment = segments.get(i);
            if (segment.startMs + segment.durationMs > targetMs) {
                startIndex = i;
                break;
            }
        }

        long offsetMs = segments.get(startIndex).startMs;
        long selectedDurationMs = 0;
        int selectedSegments = 0;
        try (FileOutputStream out = new FileOutputStream(output)) {
            for (int i = startIndex; i < segments.size(); i++) {
                if (selectedSegments >= CAPTION_HLS_MAX_SEGMENTS
                        || (selectedSegments > 0 && selectedDurationMs >= CAPTION_HLS_MAX_WINDOW_MS)) {
                    break;
                }
                HlsSegment hlsSegment = segments.get(i);
                byte[] segment = downloadBytes(hlsSegment.url, referer);
                if (hlsSegment.aesKey != null) {
                    byte[] iv = hlsSegment.aesIv == null ? parseHlsIv("", hlsSegment.sequence) : hlsSegment.aesIv;
                    segment = decryptAes128Cbc(segment, hlsSegment.aesKey, iv);
                }
                out.write(segment);
                selectedSegments++;
                selectedDurationMs += hlsSegment.durationMs;
                if (selectedSegments % 5 == 0) {
                    int count = selectedSegments;
                    runOnUiThread(() -> status("Da tai " + count + " segment de tao caption..."));
                }
            }
        }
        if (selectedSegments == 0 || output.length() == 0) {
            throw new IllegalStateException("Playlist HLS khong co segment doc duoc");
        }
        return new HlsCaptionCache(output, offsetMs, offsetMs + selectedDurationMs, selectedSegments);
    }

    private long parseHlsMediaSequence(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return 0;
        }
        try {
            return Long.parseLong(line.substring(colon + 1).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private long parseHlsDurationMs(String line, long fallbackMs) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return fallbackMs;
        }
        String value = line.substring(colon + 1).split(",", 2)[0].trim();
        try {
            return Math.max(250, Math.round(Double.parseDouble(value) * 1000.0));
        } catch (NumberFormatException ignored) {
            return fallbackMs;
        }
    }

    private String hlsAttribute(String text, String name) {
        Matcher matcher = Pattern.compile(name + "=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = Pattern.compile(name + "=([^,]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private byte[] downloadBytes(String url, String referer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        applyRequestHeaders(connection, downloadMediaHeaders(url, referer));
        int code = connection.getResponseCode();
        if (code >= 400) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private byte[] parseHlsIv(String rawIv, long segmentSequence) {
        byte[] iv = new byte[16];
        String value = rawIv == null ? "" : rawIv.trim();
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        if (!value.isEmpty()) {
            while (value.length() < 32) {
                value = "0" + value;
            }
            for (int i = 0; i < 16; i++) {
                iv[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            }
            return iv;
        }
        iv[8] = (byte) ((segmentSequence >> 56) & 0xFF);
        iv[9] = (byte) ((segmentSequence >> 48) & 0xFF);
        iv[10] = (byte) ((segmentSequence >> 40) & 0xFF);
        iv[11] = (byte) ((segmentSequence >> 32) & 0xFF);
        iv[12] = (byte) ((segmentSequence >> 24) & 0xFF);
        iv[13] = (byte) ((segmentSequence >> 16) & 0xFF);
        iv[14] = (byte) ((segmentSequence >> 8) & 0xFF);
        iv[15] = (byte) (segmentSequence & 0xFF);
        return iv;
    }

    private byte[] decryptAes128Cbc(byte[] encrypted, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(encrypted);
    }

    private Map<String, String> downloadMediaHeaders(String mediaUrl, String referer) {
        Map<String, String> headers = buildMediaHeaders(mediaUrl);
        if (referer != null && !referer.trim().isEmpty()) {
            headers.put("Referer", referer);
            Uri refererUri = Uri.parse(referer);
            if (refererUri.getScheme() != null && refererUri.getHost() != null) {
                headers.put("Origin", refererUri.getScheme() + "://" + refererUri.getHost());
            }
        }
        if (mediaCookieHeader != null && !mediaCookieHeader.trim().isEmpty()) {
            headers.put("Cookie", mediaCookieHeader);
        }
        return headers;
    }

    private void downloadToFile(String url, String referer, File output) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        applyRequestHeaders(connection, downloadMediaHeaders(url, referer));
        int code = connection.getResponseCode();
        if (code >= 400) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }
        try (InputStream input = connection.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
    }

    private File savedVideoDirectory() {
        File base = getExternalFilesDir("SavedVideos");
        if (base == null) {
            base = new File(getFilesDir(), "SavedVideos");
        }
        if (!base.exists()) {
            base.mkdirs();
        }
        return base;
    }

    private File cacheVideoDirectory() {
        File base = new File(getCacheDir(), "VideoCache");
        if (!base.exists()) {
            base.mkdirs();
        }
        return base;
    }

    private void trimVideoCache() {
        File base = cacheVideoDirectory();
        long maxBytes = 2L * 1024L * 1024L * 1024L;
        long total = folderSize(base);
        if (total <= maxBytes) {
            return;
        }
        File[] children = base.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, Comparator.comparingLong(File::lastModified));
        for (File child : children) {
            if (total <= maxBytes) {
                break;
            }
            long size = folderSize(child);
            deleteRecursively(child);
            total -= size;
        }
    }

    private long folderSize(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += folderSize(child);
            }
        }
        return total;
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private void writeText(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String safeVideoName(String url) {
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        String name = path == null || path.trim().isEmpty() ? "video" : path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (name.trim().isEmpty()) {
            name = "video";
        }
        return name + "_" + System.currentTimeMillis();
    }

    private String extensionForUrl(String url, String fallback) {
        String path = Uri.parse(url).getPath();
        if (path == null) {
            return fallback;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return fallback;
        }
        String ext = path.substring(dot).toLowerCase(Locale.US);
        return ext.length() > 8 ? fallback : ext;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void showSettingsDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, pad, pad, 0);

        TextView offlineNote = new TextView(this);
        offlineNote.setText("Dịch offline dùng ML Kit trên thiết bị. Model ngôn ngữ cần tải một lần, sau đó dịch không cần endpoint/API key.");
        offlineNote.setTextColor(getColor(R.color.shared_muted));
        offlineNote.setTextSize(14);
        offlineNote.setPadding(0, 0, 0, dp(8));

        EditText sourceLang = field("Ngôn ngữ nguồn: auto, ja, en, ko, zh...", settings.sourceLanguage);
        EditText targetLang = field("Ngôn ngữ đích: vi, en, ja...", settings.targetLanguage);

        form.addView(offlineNote);
        form.addView(sourceLang);
        form.addView(targetLang);

        new AlertDialog.Builder(this)
                .setTitle("Cài đặt AI Player")
                .setView(form)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", (dialog, which) -> {
                    settings.sourceLanguage = sourceLang.getText().toString().trim();
                    settings.targetLanguage = targetLang.getText().toString().trim();
                    settings.save(this);
                    status("Đã lưu cài đặt.");
                })
                .show();
    }

    private EditText field(String hint, String value) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setText(value == null ? "" : value);
        edit.setSingleLine(true);
        return edit;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers grant temporary read access only.
        }
        if (requestCode == REQUEST_VIDEO) {
            localVideoUri = uri;
            openVideoUri(uri);
        } else if (requestCode == REQUEST_TRANSCRIPT) {
            loadTranscript(uri);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMicrophoneCapture();
            } else {
                status("Chưa được cấp quyền micro.");
            }
        }
    }

    private void openInitialBrowser() {
        localVideoUri = null;
        currentPageUrl = null;
        currentMediaUrl = null;
        runtimeExtractedMediaUrls.clear();
        nativePlayerFullscreen = false;
        loadedYoutubeCaptionVideoId = null;
        hideNativePlayer();
        webView.setVisibility(View.VISIBLE);
        openExternalButton.setVisibility(View.VISIBLE);
        sourceLabel.setText(BROWSER_HOME_URL);
        loadBrowserPage(BROWSER_HOME_URL);
        updateModeChrome();
        status("Đang mở trình duyệt.");
    }

    private boolean handleIncomingIntent(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return false;
        }
        Uri data = intent.getData();
        if (!BrowserNavigationPolicy.isIncomingWebUrl(data.toString())) {
            return false;
        }
        Log.d(TAG, "Incoming URL intent " + safeLogUrl(data.toString()));
        openBrowserUrl(data.toString());
        return true;
    }

    private void openBrowserUrl(String value) {
        String url = normalizeBrowserUrl(value);
        Log.d(TAG, "Open browser url normalized=" + safeLogUrl(url));
        if (BrowserUrlUtils.isNativePlaybackUrl(url)) {
            mediaRefererUrl = null;
            currentPageUrl = null;
            currentMediaUrl = url;
            nativePlayerFullscreen = false;
            openVideoUri(Uri.parse(url));
            return;
        }
        if (shouldExtractMediaFromPage(url)) {
            Log.d(TAG, "URL requires media extraction " + safeLogUrl(url));
            extractMediaAndOpenNative(url);
            return;
        }
        if (BrowserNavigationPolicy.shouldOpenHttpPageExternally(url)) {
            openExternalHttpPage(Uri.parse(url));
            return;
        }
        localVideoUri = null;
        currentPageUrl = url;
        currentMediaUrl = null;
        runtimeExtractedMediaUrls.clear();
        nativePlayerFullscreen = false;
        hideNativePlayer();
        webView.setVisibility(View.VISIBLE);
        openExternalButton.setVisibility(View.VISIBLE);
        sourceLabel.setText(url);
        loadBrowserPage(url);
        updateModeChrome();
        status("Đang mở trang web.");
    }

    private String normalizeBrowserUrl(String value) {
        return BrowserUrlUtils.normalizeBrowserUrl(value, BROWSER_HOME_URL);
    }

    private void loadBrowserPage(String url) {
        if (!BrowserNavigationPolicy.canLoadInWebView(url)) {
            openExternalHttpPage(Uri.parse(url));
            return;
        }
        browserUrlAttempts.clear();
        browserUrlAttempts.add(url);
        webView.loadUrl(url);
    }

    private boolean tryAlternateBrowserUrl(Uri failedUri) {
        if (failedUri == null) {
            return false;
        }
        List<String> candidates = BrowserNavigationPolicy.alternateHttpsUrls(failedUri.toString());
        if (candidates.isEmpty()) {
            return false;
        }

        for (String candidate : candidates) {
            if (candidate != null && !browserUrlAttempts.contains(candidate)) {
                browserUrlAttempts.add(candidate);
                sourceLabel.setText(candidate);
                webView.loadUrl(candidate);
                status("Trang lỗi, đang thử: " + candidate);
                return true;
            }
        }
        return false;
    }

    private boolean shouldExtractMediaFromPage(String rawUrl) {
        return BrowserUrlUtils.shouldExtractMediaFromPage(rawUrl);
    }

    private boolean handleExtractablePageNavigation(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty() || !shouldExtractMediaFromPage(rawUrl)) {
            return false;
        }
        if (runtimeExtractionActive) {
            return false;
        }
        String normalized = rawUrl.trim();
        if (normalized.equals(directExtractionPageUrl)) {
            return true;
        }
        extractMediaAndOpenNative(normalized);
        return true;
    }

    private void injectExtractableNavigationBridge() {
        if (webView == null) {
            return;
        }
        String script =
                "(function(){"
                        + "if(window.__aiPlayerVideoBridgeInstalled){return;}"
                        + "window.__aiPlayerVideoBridgeInstalled=true;"
                        + "function abs(u){try{return new URL(u, location.href).href;}catch(e){return '';}}"
                        + "function isVideoPage(u){"
                        + "try{var p=new URL(u, location.href).pathname.split('/').filter(Boolean);"
                        + "var o=(p[0]&&/^[A-Za-z]{2}$/.test(p[0]))?1:0;"
                        + "var s=(p[o]||'').toLowerCase();"
                        + "return (s==='movie'&&p.length>=o+3)||((s==='video'||s==='anime')&&p.length>=o+2);"
                        + "}catch(e){return false;}}"
                        + "function open(u){u=abs(u);if(u&&isVideoPage(u)&&window.AIPlayerBridge){window.AIPlayerBridge.openVideoPage(u);return true;}return false;}"
                        + "document.addEventListener('click',function(e){"
                        + "var n=e.target;"
                        + "while(n&&n!==document&&!n.href){n=n.parentNode;}"
                        + "if(n&&n.href&&open(n.href)){e.preventDefault();e.stopPropagation();}"
                        + "},true);"
                        + "var ps=history.pushState,rs=history.replaceState;"
                        + "history.pushState=function(){var r=ps.apply(this,arguments);setTimeout(function(){open(location.href);},0);return r;};"
                        + "history.replaceState=function(){var r=rs.apply(this,arguments);setTimeout(function(){open(location.href);},0);return r;};"
                        + "window.addEventListener('popstate',function(){setTimeout(function(){open(location.href);},0);});"
                        + "open(location.href);"
                        + "})()";
        webView.evaluateJavascript(script, null);
    }

    private final class BrowserBridge {
        @JavascriptInterface
        public void openVideoPage(String url) {
            runOnUiThread(() -> {
                String pageUrl = currentPageUrl == null || currentPageUrl.trim().isEmpty()
                        ? webView.getUrl()
                        : currentPageUrl;
                if (BrowserNavigationPolicy.canInjectExtractableNavigationBridge(pageUrl)
                        && isSameHostHttpUrl(pageUrl, url)) {
                    handleExtractablePageNavigation(url);
                }
            });
        }
    }

    private boolean isSameHostHttpUrl(String currentUrl, String targetUrl) {
        try {
            Uri current = Uri.parse(currentUrl == null || currentUrl.trim().isEmpty()
                    ? webView.getUrl()
                    : currentUrl);
            Uri target = Uri.parse(targetUrl);
            String currentHost = current.getHost();
            String targetHost = target.getHost();
            return BrowserUrlUtils.isHttpUrl(targetUrl)
                    && currentHost != null
                    && targetHost != null
                    && currentHost.equalsIgnoreCase(targetHost);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void extractMediaAndOpenNative(String pageUrl) {
        directExtractionPageUrl = pageUrl;
        localVideoUri = null;
        hideNativePlayer();
        webView.stopLoading();
        sourceLabel.setText(pageUrl);
        status("Đang trích xuất media từ trang video...");
        new Thread(() -> {
            String mediaUrl = null;
            String message = null;
            try {
                mediaUrl = extractBestMediaFromPage(pageUrl);
                if (mediaUrl == null) {
                    message = "Không tìm thấy media trực tiếp, mở bằng trình duyệt.";
                }
            } catch (Exception ex) {
                message = "Không trích xuất được media: " + ex.getMessage();
            }

            String finalMediaUrl = mediaUrl;
            String finalMessage = message;
            runOnUiThread(() -> {
                if (finalMediaUrl != null) {
                    openExtractedMediaNative(finalMediaUrl, pageUrl);
                } else {
                    startRuntimeMediaExtraction(pageUrl, finalMessage);
                }
            });
        }).start();
    }

    private void openExtractedMediaNative(String mediaUrl, String pageUrl) {
        mediaRefererUrl = pageUrl;
        currentPageUrl = pageUrl;
        currentMediaUrl = mediaUrl;
        runtimeExtractionActive = false;
        runtimeExtractionPageUrl = null;
        directExtractionPageUrl = null;
        runtimeExtractedMediaUrls.clear();
        updateDetectedMediaCount();
        nativePlayerFullscreen = true;
        openVideoUri(Uri.parse(mediaUrl));
        updateModeChrome();
        setFullscreen(true);
        hideChrome();
    }

    private String extractBestMediaFromPage(String pageUrl) throws Exception {
        String buomTvMedia = null;
        try {
            buomTvMedia = extractBuomTvMediaUrl(pageUrl);
        } catch (Exception ex) {
            status("BuomTV API lỗi, thử quét trang...");
        }
        if (buomTvMedia != null && !buomTvMedia.trim().isEmpty()) {
            if (isHlsUrl(buomTvMedia)) {
                String variant = chooseBestHlsVariant(buomTvMedia, pageUrl);
                return variant == null ? buomTvMedia : variant;
            }
            return buomTvMedia;
        }

        String html = fetchPageHtml(pageUrl);
        return chooseBestPlayableMedia(extractMediaUrls(html, pageUrl), pageUrl);
    }

    private String extractBuomTvMediaUrl(String pageUrl) throws Exception {
        Uri pageUri = Uri.parse(pageUrl);
        String host = pageUri.getHost();
        String path = pageUri.getPath();
        if (host == null || path == null || !host.toLowerCase(Locale.US).contains("buomtv")) {
            return null;
        }

        List<String> segments = pageUri.getPathSegments();
        if (segments.size() < 2) {
            return null;
        }
        int offset = segments.get(0).matches("[A-Za-z]{2}") ? 1 : 0;
        if (segments.size() <= offset) {
            return null;
        }
        String first = segments.get(offset).toLowerCase(Locale.US);
        String videoType;
        String videoId;
        if ("movie".equals(first) && segments.size() >= offset + 3) {
            videoType = "long";
            videoId = segments.get(offset + 2);
        } else if (("video".equals(first) || "anime".equals(first)) && segments.size() >= offset + 2) {
            videoType = first;
            videoId = segments.get(offset + 1);
        } else {
            return null;
        }

        String apiBase = buomTvApiBase(pageUri);
        Map<String, String> headers = buomTvHeaders(pageUrl);
        String tokenUrl = apiBase + "/pwa/register/pwatoken?version=old-web&lang=vi";
        String tokenJson = postForm(tokenUrl, "lang=vi", headers);
        JSONObject tokenPayload = new JSONObject(tokenJson);
        String token = tokenPayload.optJSONObject("response") == null
                ? ""
                : tokenPayload.optJSONObject("response").optString("token", "");
        if (token.trim().isEmpty()) {
            throw new IllegalStateException("Không lấy được token");
        }

        String infoUrl = apiBase
                + "/pwa/video/info/" + Uri.encode(videoId)
                + "?token=" + Uri.encode(token)
                + "&video_type=" + Uri.encode(videoType)
                + "&platform=web&lang=vi";
        String infoJson = fetchTextUrl(infoUrl, pageUrl, headers);
        JSONObject infoPayload = new JSONObject(infoJson);
        JSONObject status = infoPayload.optJSONObject("status");
        if (status != null && status.optInt("code", 0) != 200) {
            throw new IllegalStateException(status.optString("message", "Video API lỗi"));
        }
        JSONObject response = infoPayload.optJSONObject("response");
        JSONObject videoUrls = response == null ? null : response.optJSONObject("video_urls");
        String selected = selectBuomTvStreamUrl(apiBase, pageUrl, headers, videoUrls);
        if (selected == null || selected.trim().isEmpty()) {
            throw new IllegalStateException("Không tìm thấy playback URL");
        }
        return absoluteBuomTvUrl(apiBase, selected);
    }

    private String selectBuomTvStreamUrl(String apiBase, String pageUrl, Map<String, String> headers, JSONObject videoUrls) {
        if (videoUrls == null) {
            return "";
        }
        String downloadUrl = resolveBuomTvDownloadUrl(apiBase, pageUrl, headers, videoUrls);
        if (downloadUrl != null && !downloadUrl.trim().isEmpty()) {
            return downloadUrl;
        }
        String bestUrl = "";
        int bestHeight = -1;
        JSONArray names = videoUrls.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                String value = videoUrls.optString(key, "");
                int height = parseInt(key);
                if (height > bestHeight && value != null && !value.trim().isEmpty()) {
                    bestHeight = height;
                    bestUrl = value;
                }
            }
        }
        return bestUrl.trim().isEmpty() ? videoUrls.optString("intro", "") : bestUrl;
    }

    private String resolveBuomTvDownloadUrl(String apiBase, String pageUrl, Map<String, String> headers, JSONObject videoUrls) {
        String downloadPath = videoUrls.optString("dl", "");
        if (downloadPath == null || downloadPath.trim().isEmpty()) {
            return "";
        }
        try {
            String downloadApiUrl = absoluteBuomTvUrl(apiBase, downloadPath);
            String json = fetchTextUrl(downloadApiUrl, pageUrl, headers);
            List<String> urls = extractMediaUrls(json, pageUrl);
            if (urls.isEmpty()) {
                urls = extractMediaUrls(json, apiBase);
            }
            return chooseBestMediaUrl(urls);
        } catch (Exception ex) {
            Log.d(TAG, "BuomTV download URL unavailable: " + ex.getMessage());
            return "";
        }
    }

    private String buomTvApiBase(Uri pageUri) {
        String scheme = pageUri.getScheme() == null ? "https" : pageUri.getScheme();
        String host = pageUri.getHost() == null ? "" : pageUri.getHost().toLowerCase(Locale.US);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return scheme + "://api." + host;
    }

    private Map<String, String> buomTvHeaders(String pageUrl) {
        Map<String, String> headers = new HashMap<>();
        Uri uri = Uri.parse(pageUrl);
        String origin = uri.getScheme() + "://" + uri.getHost();
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "en-US,en;q=0.9,vi;q=0.8");
        headers.put("Connection", "keep-alive");
        headers.put("User-Agent", BROWSER_USER_AGENT);
        headers.put("Referer", pageUrl);
        headers.put("Origin", origin);
        String apiHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
        if (apiHost.startsWith("www.")) {
            apiHost = apiHost.substring(4);
        }
        String cookies = mergeCookieHeaders(
                CookieManager.getInstance().getCookie(pageUrl),
                CookieManager.getInstance().getCookie(uri.getScheme() + "://api." + apiHost)
        );
        if (!cookies.isEmpty()) {
            headers.put("Cookie", cookies);
        }
        return headers;
    }

    private String mergeCookieHeaders(String first, String second) {
        Set<String> cookies = new LinkedHashSet<>();
        addCookieParts(cookies, first);
        addCookieParts(cookies, second);
        StringBuilder builder = new StringBuilder();
        for (String cookie : cookies) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(cookie);
        }
        return builder.toString();
    }

    private void addCookieParts(Set<String> output, String rawCookieHeader) {
        if (rawCookieHeader == null || rawCookieHeader.trim().isEmpty()) {
            return;
        }
        String[] parts = rawCookieHeader.split(";");
        for (String part : parts) {
            String cookie = part.trim();
            if (!cookie.isEmpty()) {
                output.add(cookie);
            }
        }
    }

    private String absoluteBuomTvUrl(String apiBase, String streamPath) {
        String value = streamPath == null ? "" : streamPath.trim();
        if (value.toLowerCase(Locale.US).startsWith("http://")
                || value.toLowerCase(Locale.US).startsWith("https://")) {
            return value;
        }
        return apiBase + "/" + value.replaceFirst("^/+", "");
    }

    private void startRuntimeMediaExtraction(String pageUrl, String reason) {
        mediaRefererUrl = null;
        nativePlayerFullscreen = false;
        runtimeExtractionActive = true;
        runtimeExtractionPageUrl = pageUrl;
        directExtractionPageUrl = null;
        runtimeExtractedMediaUrls.clear();
        updateDetectedMediaCount();
        currentPageUrl = pageUrl;
        currentMediaUrl = null;
        hideNativePlayer();
        webView.setVisibility(View.VISIBLE);
        openExternalButton.setVisibility(View.VISIBLE);
        sourceLabel.setText(pageUrl);
        loadBrowserPage(pageUrl);
        updateModeChrome();
        status(reason == null
                ? "Đang chạy trang để tìm media tốt nhất..."
                : reason + " Đang chạy trang để tìm media tốt nhất...");
        handler.removeCallbacks(finalizeRuntimeExtractionRunnable);
        handler.postDelayed(finalizeRuntimeExtractionRunnable, 9000);
    }

    private final Runnable finalizeRuntimeExtractionRunnable = new Runnable() {
        @Override
        public void run() {
            finalizeRuntimeMediaExtraction(false);
        }
    };

    private void observeRuntimeMediaUrl(String url) {
        if (url == null) {
            return;
        }
        if (!isDirectMediaUrl(Uri.parse(url))) {
            return;
        }
        synchronized (runtimeExtractedMediaUrls) {
            if (!runtimeExtractedMediaUrls.contains(url)) {
                runtimeExtractedMediaUrls.add(url);
            }
        }
        runOnUiThread(this::updateDetectedMediaCount);
        if (!runtimeExtractionActive) {
            return;
        }
        handler.removeCallbacks(finalizeRuntimeExtractionRunnable);
        handler.postDelayed(finalizeRuntimeExtractionRunnable, 2200);
    }

    private void inspectRuntimePageHtml() {
        if (!runtimeExtractionActive) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){return document.documentElement?document.documentElement.outerHTML:'';})()",
                value -> {
                    if (!runtimeExtractionActive || value == null || value.trim().isEmpty()) {
                        return;
                    }
                    try {
                        String html = new JSONArray("[" + value + "]").getString(0);
                        List<String> urls = extractMediaUrls(html, runtimeExtractionPageUrl);
                        synchronized (runtimeExtractedMediaUrls) {
                            for (String url : urls) {
                                if (!runtimeExtractedMediaUrls.contains(url)) {
                                    runtimeExtractedMediaUrls.add(url);
                                }
                            }
                        }
                        if (!urls.isEmpty()) {
                            updateDetectedMediaCount();
                            handler.removeCallbacks(finalizeRuntimeExtractionRunnable);
                            handler.postDelayed(finalizeRuntimeExtractionRunnable, 1200);
                        }
                    } catch (Exception ignored) {
                    }
                }
        );
    }

    private void finalizeRuntimeMediaExtraction(boolean force) {
        if (!runtimeExtractionActive) {
            return;
        }
        List<String> urls;
        synchronized (runtimeExtractedMediaUrls) {
            urls = new ArrayList<>(runtimeExtractedMediaUrls);
        }
        if (urls.isEmpty() && !force) {
            runtimeExtractionActive = false;
            status("Không tìm thấy media trực tiếp, giữ trang trong trình duyệt.");
            return;
        }
        if (urls.isEmpty()) {
            return;
        }
        String pageUrl = runtimeExtractionPageUrl;
        runtimeExtractionActive = false;
        status("Đã tìm thấy media, đang chọn chất lượng tốt nhất...");
        new Thread(() -> {
            try {
                String best = chooseBestPlayableMedia(urls, pageUrl);
                runOnUiThread(() -> openExtractedMediaNative(best, pageUrl));
            } catch (Exception ex) {
                runOnUiThread(() -> status("Không mở được media đã tìm thấy: " + ex.getMessage()));
            }
        }).start();
    }

    private String fetchPageHtml(String pageUrl) throws Exception {
        return fetchTextUrl(pageUrl, pageUrl);
    }

    private String fetchTextUrl(String url, String referer) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BROWSER_USER_AGENT);
        headers.put("Accept", "*/*");
        if (referer != null && !referer.trim().isEmpty()) {
            headers.put("Referer", referer);
        }
        return fetchTextUrl(url, referer, headers);
    }

    private String fetchTextUrl(String url, String referer, Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        applyRequestHeaders(connection, headers);
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            throw new IllegalStateException("HTTP " + code);
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } finally {
            connection.disconnect();
        }
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code);
        }
        return builder.toString();
    }

    private String postForm(String url, String body, Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        applyRequestHeaders(connection, headers);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Length", String.valueOf(data.length));
        try (OutputStream output = connection.getOutputStream()) {
            output.write(data);
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            throw new IllegalStateException("HTTP " + code);
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } finally {
            connection.disconnect();
        }
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code);
        }
        return builder.toString();
    }

    private void applyRequestHeaders(HttpURLConnection connection, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
            connection.setRequestProperty("Accept", "*/*");
            return;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private List<String> extractMediaUrls(String html, String pageUrl) {
        Set<String> urls = new LinkedHashSet<>();
        String normalized = normalizeExtractedText(html);
        collectMediaMatches(urls, ABSOLUTE_MEDIA_PATTERN.matcher(normalized), pageUrl, 0);
        collectMediaMatches(urls, QUOTED_MEDIA_PATTERN.matcher(normalized), pageUrl, 1);
        return new ArrayList<>(urls);
    }

    private String normalizeExtractedText(String text) {
        return text == null ? "" : text
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("&amp;", "&");
    }

    private void collectMediaMatches(Set<String> urls, Matcher matcher, String pageUrl, int group) {
        while (matcher.find()) {
            String value = matcher.group(group);
            String resolved = resolveMediaUrl(value, pageUrl);
            if (resolved != null) {
                urls.add(resolved);
            }
        }
    }

    private String resolveMediaUrl(String value, String pageUrl) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("//")) {
            cleaned = Uri.parse(pageUrl).getScheme() + ":" + cleaned;
        }
        Uri uri = Uri.parse(cleaned);
        if (uri.getScheme() != null) {
            return cleaned;
        }
        try {
            return new URL(new URL(pageUrl), cleaned).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String chooseBestMediaUrl(List<String> urls) {
        String bestUrl = null;
        int bestScore = -1;
        for (String url : urls) {
            int score = mediaUrlScore(url);
            if (score > bestScore) {
                bestScore = score;
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    private int mediaUrlScore(String url) {
        String path = Uri.parse(url).getPath();
        String lower = (path == null ? url : path).toLowerCase(Locale.US);
        int score = 0;
        if (lower.endsWith(".m3u8")) {
            score += 1000000;
        } else if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) {
            score += 800000;
        } else if (lower.endsWith(".webm")) {
            score += 700000;
        } else if (lower.endsWith(".mov") || lower.endsWith(".mkv")) {
            score += 600000;
        }
        Matcher resolution = Pattern.compile("(2160|1440|1080|720|540|480|360|240)p?").matcher(lower);
        while (resolution.find()) {
            score += parseInt(resolution.group(1)) * 100;
        }
        Matcher bandwidth = Pattern.compile("(?:bitrate|bandwidth|br|b)[=_-]?(\\d{4,8})").matcher(lower);
        while (bandwidth.find()) {
            score += parseInt(bandwidth.group(1)) / 100;
        }
        return score;
    }

    private String chooseBestPlayableMedia(List<String> urls, String referer) throws Exception {
        String mediaUrl = chooseBestMediaUrl(urls);
        if (mediaUrl != null && isHlsUrl(mediaUrl)) {
            String variant = chooseBestHlsVariant(mediaUrl, referer);
            return variant == null ? mediaUrl : variant;
        }
        return mediaUrl;
    }

    private boolean isHlsUrl(String url) {
        String path = Uri.parse(url).getPath();
        return path != null && path.toLowerCase(Locale.US).endsWith(".m3u8");
    }

    private String chooseBestHlsVariant(String playlistUrl, String referer) throws Exception {
        String playlist = fetchTextUrl(playlistUrl, referer);
        String[] lines = playlist.split("\\r?\\n");
        String bestUrl = null;
        int bestScore = -1;
        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) {
                continue;
            }
            String candidateLine = nextPlaylistUri(lines, i + 1);
            if (candidateLine == null) {
                continue;
            }
            int score = parseHlsScore(line);
            if (score > bestScore) {
                bestScore = score;
                bestUrl = resolveMediaUrl(candidateLine, playlistUrl);
            }
        }
        return bestUrl;
    }

    private String nextPlaylistUri(String[] lines, int startIndex) {
        for (int i = startIndex; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            return line;
        }
        return null;
    }

    private int parseHlsScore(String streamInfo) {
        int bandwidth = parseIntAttribute(streamInfo, "BANDWIDTH");
        int width = 0;
        int height = 0;
        Matcher matcher = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE).matcher(streamInfo);
        if (matcher.find()) {
            width = parseInt(matcher.group(1));
            height = parseInt(matcher.group(2));
        }
        return (width * height) + Math.max(0, bandwidth / 100);
    }

    private int parseIntAttribute(String text, String name) {
        Matcher matcher = Pattern.compile(name + "=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.find() ? parseInt(matcher.group(1)) : 0;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void openBrowserPageWithoutExtraction(String url) {
        localVideoUri = null;
        currentPageUrl = url;
        currentMediaUrl = null;
        runtimeExtractedMediaUrls.clear();
        nativePlayerFullscreen = false;
        hideNativePlayer();
        webView.setVisibility(View.VISIBLE);
        openExternalButton.setVisibility(View.VISIBLE);
        sourceLabel.setText(url);
        loadBrowserPage(url);
        updateModeChrome();
    }

    private boolean shouldOpenAsBrowserPage(String rawUrl) {
        try {
            Uri uri = Uri.parse(rawUrl);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            return BrowserNavigationPolicy.canLoadInWebView(rawUrl) && !isDirectMediaUrl(uri);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isDirectMediaUrl(Uri uri) {
        return uri != null && BrowserUrlUtils.isDirectMediaUrl(uri.toString());
    }

    private void openVideoUri(Uri uri) {
        if (uri == null || uri.toString().trim().isEmpty()) {
            return;
        }
        String rawUrl = uri.toString().trim();
        lastAutoCaptionSourceKey = null;
        lastAutoTranslateSourceKey = null;
        autoCaptionInProgress = false;
        autoCaptionRequestPositionMs = -1;
        autoCaptionRequestSourceKey = null;
        lastCaptionBufferRequestMs = -1;
        captionBufferScannedUntilMs = -1;
        activeCueIndex = -1;
        cues.clear();
        hideSubtitle();
        sourceLabel.setText(rawUrl);
        String youtubeId = extractYoutubeId(rawUrl);
        if (youtubeId != null) {
            localVideoUri = null;
            openYoutubeVideo(youtubeId);
            return;
        }
        if (shouldExtractMediaFromPage(rawUrl)) {
            extractMediaAndOpenNative(rawUrl);
            return;
        }
        if (shouldOpenAsBrowserPage(rawUrl)) {
            localVideoUri = null;
            openBrowserUrl(rawUrl);
            return;
        }
        loadedYoutubeCaptionVideoId = null;
        webView.stopLoading();
        webView.setVisibility(View.GONE);
        openExternalButton.setVisibility(View.GONE);
        updateModeChrome();
        renderTranscript();
        if (cues.isEmpty()) {
            hideSubtitle();
        }
        if (isRemoteUri(uri)) {
            currentMediaUrl = rawUrl;
        } else {
            currentMediaUrl = null;
            currentPageUrl = null;
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
            openRemoteVideoUri(uri, rawUrl);
        } else {
            openLocalVideoUri(uri, rawUrl);
        }
        status("Đang mở video.");
    }

    private Map<String, String> buildMediaHeaders(String mediaUrl) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BROWSER_USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7");
        if (mediaRefererUrl != null && !mediaRefererUrl.trim().isEmpty()) {
            headers.put("Referer", mediaRefererUrl);
            Uri referer = Uri.parse(mediaRefererUrl);
            if (referer.getScheme() != null && referer.getHost() != null) {
                headers.put("Origin", referer.getScheme() + "://" + referer.getHost());
            }
        } else {
            headers.put("Referer", mediaUrl);
        }
        if (mediaCookieHeader != null && !mediaCookieHeader.trim().isEmpty()) {
            headers.put("Cookie", mediaCookieHeader);
        }
        return headers;
    }

    private boolean isRemoteUri(Uri uri) {
        return uri != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private void openLocalVideoUri(Uri uri, String rawUrl) {
        releaseExoPlayer();
        mediaCookieHeader = null;
        videoView.setVisibility(View.VISIBLE);
        if (playerView != null) {
            playerView.setVisibility(View.GONE);
        }
        videoView.setVideoURI(uri);
        videoView.start();
    }

    private void openRemoteVideoUri(Uri uri, String rawUrl) {
        hideNativePlayer();
        mediaCookieHeader = null;
        if (isHlsUrl(rawUrl)) {
            status("Đang chuẩn bị HLS...");
            new Thread(() -> {
                Map<String, String> baseHeaders = buildMediaHeaders(rawUrl);
                String cookies = collectMediaCookies(rawUrl, baseHeaders);
                Log.d(TAG, "HLS cookie prepared url=" + safeLogUrl(rawUrl) + " cookie=" + !cookies.isEmpty());
                runOnUiThread(() -> {
                    if (!rawUrl.equals(currentMediaUrl)) {
                        return;
                    }
                    mediaCookieHeader = cookies;
                    startExoPlayer(uri, rawUrl);
                });
            }).start();
            return;
        }
        startExoPlayer(uri, rawUrl);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startExoPlayer(Uri uri, String rawUrl) {
        Map<String, String> playbackHeaders = buildMediaHeaders(rawUrl);
        Log.d(TAG, "Starting ExoPlayer url=" + safeLogUrl(rawUrl) + " headers=" + playbackHeaders.keySet());
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(BROWSER_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(playbackHeaders);
        releaseExoPlayer();
        exoPlayer = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpDataSourceFactory))
                .build();
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.d(TAG, "ExoPlayer state=" + playbackState + " url=" + safeLogUrl(rawUrl));
                if (playbackState == Player.STATE_READY) {
                    applySourceVolume();
                    maybeAutoCaptionTranslateCurrentVideo();
                    status("Sẵn sàng phát.");
                    if (nativePlayerFullscreen) {
                        hideChrome();
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                String detail = error == null || error.getMessage() == null ? "" : " " + error.getMessage();
                Log.e(TAG, "ExoPlayer error url=" + safeLogUrl(rawUrl), error);
                status("Không phát được media gốc." + detail);
                Toast.makeText(MainActivity.this, "Không phát được media gốc.", Toast.LENGTH_LONG).show();
            }
        });
        playerView.setPlayer(exoPlayer);
        playerView.setVisibility(View.VISIBLE);
        exoPlayer.setMediaItem(MediaItem.fromUri(uri));
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.prepare();
        applySourceVolume();
    }

    private String collectMediaCookies(String mediaUrl, Map<String, String> headers) {
        if (!isHlsUrl(mediaUrl)) {
            return "";
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(mediaUrl).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            applyRequestHeaders(connection, headers);
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                try {
                    byte[] buffer = new byte[1024];
                    stream.read(buffer);
                } finally {
                    stream.close();
                }
            }
            List<String> cookies = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                String key = entry.getKey();
                if (key != null && "Set-Cookie".equalsIgnoreCase(key) && entry.getValue() != null) {
                    cookies.addAll(entry.getValue());
                }
            }
            if (cookies == null || cookies.isEmpty()) {
                return "";
            }
            Set<String> cookiePairs = new LinkedHashSet<>();
            for (String cookie : cookies) {
                if (cookie == null || cookie.trim().isEmpty()) {
                    continue;
                }
                int semicolon = cookie.indexOf(';');
                cookiePairs.add(semicolon >= 0 ? cookie.substring(0, semicolon) : cookie);
            }
            return String.join("; ", cookiePairs);
        } catch (Exception ignored) {
            Log.w(TAG, "Unable to collect HLS cookies url=" + safeLogUrl(mediaUrl), ignored);
            return "";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String safeLogUrl(String url) {
        if (url == null) {
            return "";
        }
        int query = url.indexOf('?');
        String withoutQuery = query >= 0 ? url.substring(0, query) : url;
        return withoutQuery.length() > 180 ? withoutQuery.substring(0, 180) + "..." : withoutQuery;
    }

    private void releaseExoPlayer() {
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        if (playerView != null) {
            playerView.setPlayer(null);
            playerView.setVisibility(View.GONE);
        }
    }

    private boolean isNativePlayerVisible() {
        return (playerView != null && playerView.getVisibility() == View.VISIBLE)
                || (videoView != null && videoView.getVisibility() == View.VISIBLE);
    }

    private void hideNativePlayer() {
        releaseExoPlayer();
        mediaPlayer = null;
        mediaCookieHeader = null;
        if (videoView != null) {
            videoView.stopPlayback();
            videoView.setVisibility(View.GONE);
        }
    }

    private void generateOriginalAudioCaptions() {
        if (webView.getVisibility() == View.VISIBLE) {
            status("YouTube/WebView không cho app lấy file âm gốc trực tiếp. Hãy dùng caption YouTube, nạp transcript, hoặc bật Micro.");
            return;
        }
        status("Đang tạo caption từ âm gốc...");
        originalAudioCaptionGenerator.generate(localVideoUri, settings.sourceLanguage, new OriginalAudioCaptionGenerator.Callback() {
            @Override
            public void onStatus(String message) {
                runOnUiThread(() -> status(message));
            }

            @Override
            public void onFinished(List<TranscriptCue> generatedCues, String message) {
                runOnUiThread(() -> {
                    if (!generatedCues.isEmpty()) {
                        cues.clear();
                        cues.addAll(generatedCues);
                        activeCueIndex = -1;
                        renderTranscript();
                    }
                    status(message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void maybeAutoCaptionTranslateCurrentVideo() {
        if (webView == null || webView.getVisibility() == View.VISIBLE || autoCaptionInProgress) {
            return;
        }
        String sourceKey = currentNativeSourceKey();
        if (sourceKey.isEmpty()) {
            return;
        }
        enableTargetSubtitleByDefault();
        if (isCurrentRemoteHls()) {
            ensureHlsCaptionBuffer(currentPlaybackMs(), true);
            return;
        }
        if (!cues.isEmpty()) {
            refreshSubtitle(currentPlaybackMs());
            maybeAutoTranslateTranscript(sourceKey);
            return;
        }
        if (sourceKey.equals(lastAutoCaptionSourceKey)) {
            return;
        }
        lastAutoCaptionSourceKey = sourceKey;
        autoCaptionInProgress = true;
        generateOriginalAudioCaptionsFromCurrentVideo(true);
    }

    private boolean isCurrentRemoteHls() {
        return currentMediaUrl != null
                && isHlsUrl(currentMediaUrl)
                && isRemoteUri(Uri.parse(currentMediaUrl));
    }

    private void ensureHlsCaptionBuffer(long positionMs, boolean translateWhenFinished) {
        if (!isCurrentRemoteHls() || autoCaptionInProgress) {
            return;
        }
        String sourceKey = currentNativeSourceKey();
        if (sourceKey.isEmpty()) {
            return;
        }
        long coverageEndMs = captionCoverageEndAfter(Math.max(0, positionMs - CAPTION_HLS_LOOK_BEHIND_MS));
        long preparedEndMs = Math.max(coverageEndMs, captionBufferScannedUntilMs);
        if (preparedEndMs - positionMs > CAPTION_PREFETCH_THRESHOLD_MS) {
            return;
        }
        long requestPositionMs = preparedEndMs > positionMs
                ? Math.max(0, preparedEndMs - CAPTION_HLS_LOOK_BEHIND_MS)
                : Math.max(0, positionMs);
        if (sourceKey.equals(autoCaptionRequestSourceKey)
                && Math.abs(requestPositionMs - lastCaptionBufferRequestMs) < 10_000L) {
            return;
        }
        autoCaptionInProgress = true;
        autoCaptionRequestSourceKey = sourceKey;
        autoCaptionRequestPositionMs = requestPositionMs;
        lastCaptionBufferRequestMs = requestPositionMs;
        generateOriginalAudioCaptionsFromRemoteHls(translateWhenFinished, requestPositionMs);
    }

    private boolean hasCueAt(long positionMs) {
        for (TranscriptCue cue : cues) {
            if (positionMs >= cue.startMs && positionMs <= cue.endMs) {
                return true;
            }
        }
        return false;
    }

    private long captionCoverageEndAfter(long positionMs) {
        long coverageEndMs = -1;
        for (TranscriptCue cue : cues) {
            if (cue.endMs < positionMs) {
                continue;
            }
            if (coverageEndMs < 0 && cue.startMs > positionMs + CAPTION_PREFETCH_THRESHOLD_MS) {
                continue;
            }
            if (coverageEndMs < 0 || cue.startMs <= coverageEndMs + CAPTION_PREFETCH_THRESHOLD_MS) {
                coverageEndMs = Math.max(coverageEndMs, cue.endMs);
            }
        }
        return coverageEndMs;
    }

    private String currentNativeSourceKey() {
        if (currentMediaUrl != null && !currentMediaUrl.trim().isEmpty()) {
            return currentMediaUrl.trim();
        }
        if (localVideoUri != null) {
            return localVideoUri.toString();
        }
        return "";
    }

    private void enableTargetSubtitleByDefault() {
        if (!settings.showTargetSubtitle) {
            settings.showTargetSubtitle = true;
            settings.save(this);
        }
        if (targetSubtitleCheck != null) {
            targetSubtitleCheck.setChecked(true);
        }
    }

    private void maybeAutoTranslateTranscript(String sourceKey) {
        if (cues.isEmpty() || sourceKey.equals(lastAutoTranslateSourceKey)) {
            return;
        }
        lastAutoTranslateSourceKey = sourceKey;
        translateTranscript();
    }

    private void generateOriginalAudioCaptionsFromCurrentVideo() {
        generateOriginalAudioCaptionsFromCurrentVideo(false);
    }

    private void generateOriginalAudioCaptionsFromCurrentVideo(boolean translateWhenFinished) {
        if (webView.getVisibility() == View.VISIBLE) {
            autoCaptionInProgress = false;
            status("WebView không cho lấy trực tiếp âm gốc. Hãy mở video bằng native player hoặc bật Micro.");
            return;
        }

        if (currentMediaUrl != null && isHlsUrl(currentMediaUrl) && isRemoteUri(Uri.parse(currentMediaUrl))) {
            long requestPositionMs = currentPlaybackMs();
            autoCaptionInProgress = true;
            autoCaptionRequestSourceKey = currentNativeSourceKey();
            autoCaptionRequestPositionMs = requestPositionMs;
            lastCaptionBufferRequestMs = requestPositionMs;
            generateOriginalAudioCaptionsFromRemoteHls(translateWhenFinished, requestPositionMs);
            return;
        }

        Uri audioSourceUri = localVideoUri;
        Map<String, String> audioHeaders = null;
        if (audioSourceUri == null && currentMediaUrl != null && !currentMediaUrl.trim().isEmpty()) {
            audioSourceUri = Uri.parse(currentMediaUrl);
            audioHeaders = buildMediaHeaders(currentMediaUrl);
        }
        if (audioSourceUri == null) {
            autoCaptionInProgress = false;
            status("Chưa có video native để lấy caption âm gốc.");
            return;
        }

        status("Đang tạo caption từ âm gốc...");
        originalAudioCaptionGenerator.generate(audioSourceUri, audioHeaders, settings.sourceLanguage, new OriginalAudioCaptionGenerator.Callback() {
            @Override
            public void onStatus(String message) {
                runOnUiThread(() -> status(message));
            }

            @Override
            public void onFinished(List<TranscriptCue> generatedCues, String message) {
                runOnUiThread(() -> {
                    autoCaptionInProgress = false;
                    if (!generatedCues.isEmpty()) {
                        enableTargetSubtitleByDefault();
                        cues.clear();
                        cues.addAll(generatedCues);
                        activeCueIndex = -1;
                        renderTranscript();
                        refreshSubtitle(currentPlaybackMs());
                        if (translateWhenFinished) {
                            maybeAutoTranslateTranscript(currentNativeSourceKey());
                        }
                    }
                    status(message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void generateOriginalAudioCaptionsFromRemoteHls(boolean translateWhenFinished, long playbackPositionMs) {
        String mediaUrl = currentMediaUrl;
        String requestSourceKey = currentNativeSourceKey();
        String referer = mediaRefererUrl == null ? currentPageUrl : mediaRefererUrl;
        status("Dang tai HLS gan vi tri " + TranscriptParser.formatTime(playbackPositionMs) + " de tao caption...");
        new Thread(() -> {
            try {
                HlsCaptionCache cache = cacheHlsTransportStreamForCaption(mediaUrl, referer, playbackPositionMs);
                runOnUiThread(() -> generateOriginalAudioCaptionsFromCachedPlaylist(
                        Uri.fromFile(cache.file),
                        cache.offsetMs,
                        cache.endMs,
                        translateWhenFinished,
                        requestSourceKey
                ));
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    autoCaptionInProgress = false;
                    lastCaptionBufferRequestMs = -1;
                    status("Khong tao duoc caption tu HLS: " + ex.getMessage());
                });
            }
        }).start();
    }

    private void generateOriginalAudioCaptionsFromCachedPlaylist(
            Uri playlistUri,
            long offsetMs,
            long cacheEndMs,
            boolean translateWhenFinished,
            String requestSourceKey
    ) {
        status("Dang tao caption quanh " + TranscriptParser.formatTime(autoCaptionRequestPositionMs) + "...");
        originalAudioCaptionGenerator.generate(playlistUri, null, settings.sourceLanguage, new OriginalAudioCaptionGenerator.Callback() {
            @Override
            public void onStatus(String message) {
                runOnUiThread(() -> status(message));
            }

            @Override
            public void onFinished(List<TranscriptCue> generatedCues, String message) {
                runOnUiThread(() -> {
                    autoCaptionInProgress = false;
                    if (!requestSourceKey.equals(currentNativeSourceKey())) {
                        status("Da bo qua caption cu vi video da thay doi.");
                        return;
                    }
                    if (!generatedCues.isEmpty()) {
                        List<TranscriptCue> timedCues = offsetCues(generatedCues, offsetMs);
                        enableTargetSubtitleByDefault();
                        int added = mergeCaptionCues(timedCues);
                        captionBufferScannedUntilMs = Math.max(captionBufferScannedUntilMs, cacheEndMs);
                        lastCaptionBufferRequestMs = Math.max(lastCaptionBufferRequestMs, cacheEndMs);
                        activeCueIndex = -1;
                        renderTranscript();
                        refreshSubtitle(currentPlaybackMs());
                        if (translateWhenFinished && added > 0) {
                            lastAutoTranslateSourceKey = null;
                            maybeAutoTranslateTranscript(currentNativeSourceKey());
                        }
                        ensureHlsCaptionBuffer(currentPlaybackMs(), translateWhenFinished);
                    } else {
                        captionBufferScannedUntilMs = Math.max(captionBufferScannedUntilMs, cacheEndMs);
                        lastCaptionBufferRequestMs = -1;
                        ensureHlsCaptionBuffer(cacheEndMs, translateWhenFinished);
                    }
                    status(message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private int mergeCaptionCues(List<TranscriptCue> newCues) {
        int added = 0;
        for (TranscriptCue newCue : newCues) {
            if (newCue.sourceText == null || newCue.sourceText.trim().isEmpty()) {
                continue;
            }
            if (hasDuplicateCue(newCue)) {
                continue;
            }
            cues.add(newCue);
            added++;
        }
        cues.sort(Comparator.comparingLong(cue -> cue.startMs));
        trimCaptionOverlaps();
        if (added > 0) {
            TranscriptCue first = newCues.get(0);
            TranscriptCue last = newCues.get(newCues.size() - 1);
            Log.d(TAG, "Merged caption cues added=" + added
                    + " range=" + first.startMs + "-" + last.endMs
                    + " player=" + currentPlaybackMs());
        }
        return added;
    }

    private boolean hasDuplicateCue(TranscriptCue candidate) {
        for (TranscriptCue cue : cues) {
            boolean sameTiming = Math.abs(cue.startMs - candidate.startMs) <= CAPTION_DUPLICATE_TOLERANCE_MS
                    && Math.abs(cue.endMs - candidate.endMs) <= CAPTION_DUPLICATE_TOLERANCE_MS;
            boolean sameText = cue.sourceText != null && cue.sourceText.equals(candidate.sourceText);
            if (sameTiming || (sameText && Math.abs(cue.startMs - candidate.startMs) <= 1500L)) {
                return true;
            }
        }
        return false;
    }

    private void trimCaptionOverlaps() {
        for (int i = 1; i < cues.size(); i++) {
            TranscriptCue previous = cues.get(i - 1);
            TranscriptCue current = cues.get(i);
            if (previous.endMs <= current.startMs) {
                continue;
            }
            long adjustedEndMs = Math.max(previous.startMs + 250, current.startMs - 40);
            cues.set(i - 1, copyCue(previous, previous.startMs, adjustedEndMs));
        }
    }

    private TranscriptCue copyCue(TranscriptCue cue, long startMs, long endMs) {
        TranscriptCue copy = new TranscriptCue(startMs, endMs, cue.sourceText);
        copy.targetText = cue.targetText;
        copy.spoken = cue.spoken;
        return copy;
    }

    private List<TranscriptCue> offsetCues(List<TranscriptCue> sourceCues, long offsetMs) {
        if (offsetMs <= 0) {
            return sourceCues;
        }
        List<TranscriptCue> shifted = new ArrayList<>();
        for (TranscriptCue cue : sourceCues) {
            TranscriptCue shiftedCue = new TranscriptCue(cue.startMs + offsetMs, cue.endMs + offsetMs, cue.sourceText);
            shiftedCue.targetText = cue.targetText;
            shiftedCue.spoken = cue.spoken;
            shifted.add(shiftedCue);
        }
        return shifted;
    }

    private void openYoutubeVideo(String videoId) {
        if (tts != null) {
            tts.stop();
        }
        nativePlayerFullscreen = false;
        hideNativePlayer();
        webView.setVisibility(View.VISIBLE);
        openExternalButton.setVisibility(View.VISIBLE);
        String watchUrl = "https://m.youtube.com/watch?v=" + videoId;
        sourceLabel.setText(watchUrl);
        loadBrowserPage(watchUrl);
        loadedYoutubeCaptionVideoId = videoId;
        updateModeChrome();
        status("Đang mở trang YouTube mobile trong WebView.");
        loadYoutubeCaptions(videoId);
    }

    private void loadYoutubeCaptions(String videoId) {
        status("Đang lấy caption YouTube...");
        youtubeCaptionClient.loadCaptions(videoId, settings.sourceLanguage, (loadedCues, message) -> runOnUiThread(() -> {
            if (!loadedCues.isEmpty()) {
                cues.clear();
                cues.addAll(loadedCues);
                activeCueIndex = -1;
                renderTranscript();
            }
            status(message);
        }));
    }

    private void openCurrentSourceExternally() {
        String url = sourceLabel == null ? "" : sourceLabel.getText().toString().trim();
        if (url.isEmpty() || "Chưa mở nguồn".equals(url)) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    private boolean handleWebNavigation(Uri uri) {
        if (uri == null) {
            return true;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            String rawUrl = uri.toString();
            if (handleExtractablePageNavigation(rawUrl)) {
                return true;
            }
            if (isDirectMediaUrl(uri)) {
                mediaRefererUrl = currentPageUrl;
                openVideoUri(uri);
                return true;
            }
            if (!BrowserNavigationPolicy.canLoadInWebView(rawUrl)) {
                openExternalHttpPage(uri);
                return true;
            }
            return false;
        }
        if (!isExternallyOpenableScheme(scheme)) {
            status("Đã chặn liên kết không hỗ trợ: " + scheme);
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ex) {
            status("Không thể mở liên kết ngoài: " + uri);
        }
        return true;
    }

    private void openExternalHttpPage(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            status("HTTP page opened outside the app. Direct HTTP video links still play in AI Player.");
        } catch (Exception ex) {
            status("Blocked insecure HTTP page: " + uri);
        }
    }

    private boolean isExternallyOpenableScheme(String scheme) {
        return "intent".equalsIgnoreCase(scheme)
                || "market".equalsIgnoreCase(scheme)
                || "mailto".equalsIgnoreCase(scheme)
                || "tel".equalsIgnoreCase(scheme);
    }

    private void syncBrowserPage(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        sourceLabel.setText(url);
        String youtubeId = extractYoutubeId(url);
        if (youtubeId == null) {
            loadedYoutubeCaptionVideoId = null;
            return;
        }
        if (youtubeId.equals(loadedYoutubeCaptionVideoId)) {
            return;
        }
        loadedYoutubeCaptionVideoId = youtubeId;
        loadYoutubeCaptions(youtubeId);
    }

    private String extractYoutubeId(String rawUrl) {
        return BrowserUrlUtils.extractYoutubeId(rawUrl);
    }

    private String cleanYoutubeId(String value) {
        return BrowserUrlUtils.cleanYoutubeId(value);
    }

    private void loadTranscript(Uri uri) {
        try {
            String content = readText(uri);
            cues.clear();
            cues.addAll(TranscriptParser.parse(content));
            activeCueIndex = -1;
            renderTranscript();
            status("Đã nạp " + cues.size() + " đoạn transcript.");
        } catch (Exception ex) {
            Toast.makeText(this, "Không thể đọc transcript: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String readText(Uri uri) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private void translateTranscript() {
        if (cues.isEmpty()) {
            status("Chưa có transcript để dịch.");
            return;
        }
        status("Đang dịch offline trên điện thoại...");
        List<TranscriptCue> snapshot = new ArrayList<>(cues);
        translationClient.translateCues(settings, snapshot, new TranslationClient.Callback() {
            @Override
            public void onTranslated(int index, String translatedText) {
                runOnUiThread(() -> {
                    if (index >= 0 && index < snapshot.size()) {
                        TranscriptCue translatedCue = snapshot.get(index);
                        translatedCue.targetText = translatedText;
                        applyTranslatedCue(translatedCue, translatedText);
                        int playbackMs = currentPlaybackMs();
                        boolean nearPlayback = Math.abs(translatedCue.startMs - playbackMs) <= 60_000L;
                        if (nearPlayback || index % 8 == 0 || index == snapshot.size() - 1) {
                            renderTranscript();
                            refreshSubtitle(playbackMs);
                        }
                    }
                });
            }

            @Override
            public void onProgress(int completed, int total, int cached, int skipped) {
                runOnUiThread(() -> status(
                        "Đang dịch offline " + completed + "/" + total
                                + " đoạn"
                                + (cached > 0 ? ", cache " + cached : "")
                                + (skipped > 0 ? ", bỏ qua " + skipped : "")
                                + "..."
                ));
            }

            @Override
            public void onFinished(int translatedCount, String message) {
                runOnUiThread(() -> {
                    renderTranscript();
                    refreshSubtitle(currentPlaybackMs());
                    status(message);
                });
            }
        });
    }

    private void applyTranslatedCue(TranscriptCue translatedCue, String translatedText) {
        for (TranscriptCue cue : cues) {
            boolean sameText = cue.sourceText != null && cue.sourceText.equals(translatedCue.sourceText);
            boolean nearStart = Math.abs(cue.startMs - translatedCue.startMs) <= 800L;
            if (sameText && nearStart) {
                cue.targetText = translatedText;
                return;
            }
        }
    }

    private void toggleMicrophoneCapture() {
        if (liveSpeechRecognizer.isRunning()) {
            liveSpeechRecognizer.stop();
            if (microphoneButton != null) {
                microphoneButton.setText("Micro");
            }
            status("Đã dừng micro.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        startMicrophoneCapture();
    }

    private void startMicrophoneCapture() {
        liveSpeechRecognizer.start(settings.sourceLanguage);
        if (microphoneButton != null) {
            microphoneButton.setText("Dừng micro");
        }
    }

    private void showSystemAudioInfo() {
        new AlertDialog.Builder(this)
                .setTitle("Âm hệ thống")
                .setMessage("Android không cho app thường lấy trực tiếp âm thanh từ YouTube/WebView để nhận dạng như trên Windows. Với video không có caption, hãy bật Micro và phát âm thanh qua loa để app tạo transcript live. Bản sau có thể bổ sung MediaProjection + ASR native riêng cho máy hỗ trợ.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void addLiveCue(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        long start = Math.max(0, currentPlaybackMs() - 500);
        long end = start + Math.max(3000, Math.min(9000, text.length() * 120L));
        TranscriptCue cue = new TranscriptCue(start, end, text.trim());
        cues.add(cue);
        renderTranscript();
        showSubtitle(cue.sourceText);
        if (settings.dubbingEnabled) {
            speak(cue.sourceText);
        }
        status("Đã thêm transcript từ micro.");
    }

    private int currentPlaybackMs() {
        if (webView.getVisibility() == View.VISIBLE) {
            return webPositionMs;
        }
        return nativePositionMs();
    }

    private int nativePositionMs() {
        if (exoPlayer != null) {
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, exoPlayer.getCurrentPosition()));
        }
        return Math.max(0, videoView.getCurrentPosition());
    }

    private int nativeDurationMs() {
        if (exoPlayer != null) {
            long duration = exoPlayer.getDuration();
            if (duration <= 0 || duration == C.TIME_UNSET) {
                return 0;
            }
            return (int) Math.min(Integer.MAX_VALUE, duration);
        }
        return Math.max(0, videoView.getDuration());
    }

    private void seekNativeTo(int positionMs) {
        int target = Math.max(0, positionMs);
        if (exoPlayer != null) {
            exoPlayer.seekTo(target);
        } else {
            videoView.seekTo(target);
        }
    }

    private void refreshPlayback() {
        if (webView.getVisibility() == View.VISIBLE) {
            refreshWebPlayback();
            return;
        }
        int duration = nativeDurationMs();
        int position = nativePositionMs();
        if (!seeking && duration > 0) {
            positionBar.setProgress((position * 1000) / duration);
        }
        timeLabel.setText(TranscriptParser.formatTime(position) + " / " + TranscriptParser.formatTime(duration));
        refreshSubtitle(position);
        ensureHlsCaptionBuffer(position, true);
    }

    private void refreshWebPlayback() {
        if (!webPositionQueryPending) {
            webPositionQueryPending = true;
            webView.evaluateJavascript(webVideoScript("return v?JSON.stringify({p:v.currentTime||0,d:v.duration||0,paused:v.paused}):'';"), value -> {
                webPositionQueryPending = false;
                String raw = value == null ? "" : value;
                try {
                    if (raw.startsWith("\"") && raw.endsWith("\"")) {
                        raw = raw.substring(1, raw.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                    }
                    if (raw.trim().isEmpty() || "null".equals(raw)) {
                        return;
                    }
                    JSONObject json = new JSONObject(raw);
                    webPositionMs = (int) (json.optDouble("p", 0) * 1000);
                    webDurationMs = (int) (json.optDouble("d", 0) * 1000);
                    if (!seeking && webDurationMs > 0) {
                        positionBar.setProgress((webPositionMs * 1000) / webDurationMs);
                    }
                    timeLabel.setText(TranscriptParser.formatTime(webPositionMs) + " / " + TranscriptParser.formatTime(webDurationMs));
                    refreshSubtitle(webPositionMs);
                } catch (Exception ignored) {
                    timeLabel.setText(TranscriptParser.formatTime(webPositionMs) + " / " + TranscriptParser.formatTime(webDurationMs));
                }
            });
        }
    }

    private String webVideoScript(String body) {
        return "(function(){"
                + "function findVideo(doc){"
                + "var v=doc.querySelector('video');"
                + "if(v){return v;}"
                + "var frames=doc.querySelectorAll('iframe');"
                + "for(var i=0;i<frames.length;i++){"
                + "try{v=findVideo(frames[i].contentDocument);if(v){return v;}}catch(e){}"
                + "}"
                + "return null;"
                + "}"
                + "var v=findVideo(document);"
                + body
                + "})()";
    }

    private void refreshSubtitle(long positionMs) {
        int nextIndex = -1;
        int nearbyIndex = -1;
        long nearbyDistanceMs = Long.MAX_VALUE;
        for (int i = 0; i < cues.size(); i++) {
            TranscriptCue cue = cues.get(i);
            if (positionMs >= cue.startMs && positionMs <= cue.endMs) {
                nextIndex = i;
                break;
            }
            long distanceMs;
            if (positionMs < cue.startMs) {
                distanceMs = cue.startMs - positionMs;
            } else {
                distanceMs = positionMs - cue.endMs;
            }
            if (distanceMs <= SUBTITLE_NEARBY_TOLERANCE_MS && distanceMs < nearbyDistanceMs) {
                nearbyDistanceMs = distanceMs;
                nearbyIndex = i;
            }
        }

        if (nextIndex < 0) {
            nextIndex = nearbyIndex;
            if (nextIndex < 0) {
                hideSubtitle();
                return;
            }
        }

        TranscriptCue cue = cues.get(nextIndex);
        showSubtitle(cue.displayText(settings.showTargetSubtitle));
        if (nextIndex != activeCueIndex) {
            activeCueIndex = nextIndex;
            if (settings.dubbingEnabled && !cue.spoken) {
                cue.spoken = true;
                speak(cue.displayText(true));
            }
        }
    }

    private void speak(String text) {
        if (tts == null || text == null || text.trim().isEmpty()) {
            return;
        }
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Math.max(0f, Math.min(1f, settings.dubbingVolume / 100f)));
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "cue-" + activeCueIndex);
    }

    private void applyTtsVolume() {
        if (tts != null) {
            tts.setSpeechRate(1.0f);
        }
    }

    private void applySourceVolume() {
        float volume = Math.max(0f, Math.min(1f, settings.sourceVolume / 100f));
        if (exoPlayer != null) {
            exoPlayer.setVolume(volume);
        }
        if (mediaPlayer == null) {
            return;
        }
        mediaPlayer.setVolume(volume, volume);
    }

    private void stopPlayback() {
        if (webView.getVisibility() == View.VISIBLE) {
            webView.evaluateJavascript(webVideoScript("if(v){v.pause();v.currentTime=0;}"), null);
        } else if (exoPlayer != null) {
            exoPlayer.pause();
            exoPlayer.seekTo(0);
        } else {
            videoView.pause();
            videoView.seekTo(0);
        }
        activeCueIndex = -1;
        for (TranscriptCue cue : cues) {
            cue.spoken = false;
        }
        if (tts != null) {
            tts.stop();
        }
    }

    private void renderTranscript() {
        if (cues.isEmpty()) {
            transcriptView.setText("Transcript sẽ hiển thị ở đây.");
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (TranscriptCue cue : cues) {
            builder.append(TranscriptParser.formatTime(cue.startMs))
                    .append(" - ")
                    .append(TranscriptParser.formatTime(cue.endMs))
                    .append('\n')
                    .append(cue.sourceText);
            if (cue.targetText != null && !cue.targetText.trim().isEmpty()
                    && !cue.targetText.equals(cue.sourceText)) {
                builder.append('\n').append(cue.targetText);
            }
            builder.append("\n\n");
        }
        transcriptView.setText(builder.toString().trim());
    }

    private void togglePlayback() {
        if (webView.getVisibility() == View.VISIBLE) {
            webView.evaluateJavascript(
                    webVideoScript("if(v){if(v.paused){v.play();}else{v.pause();}}"),
                    null
            );
            showChromeTemporarily();
            return;
        }
        if (exoPlayer != null) {
            if (exoPlayer.isPlaying()) {
                exoPlayer.pause();
            } else {
                exoPlayer.play();
            }
        } else if (videoView.isPlaying()) {
            videoView.pause();
        } else {
            videoView.start();
        }
        showChromeTemporarily();
    }

    private void playCurrentSource() {
        if (webView.getVisibility() == View.VISIBLE) {
            webView.evaluateJavascript(webVideoScript("if(v){v.play();}"), null);
        } else if (exoPlayer != null) {
            exoPlayer.play();
        } else {
            videoView.start();
        }
    }

    private void pauseCurrentSource() {
        if (webView.getVisibility() == View.VISIBLE) {
            webView.evaluateJavascript(webVideoScript("if(v){v.pause();}"), null);
        } else if (exoPlayer != null) {
            exoPlayer.pause();
        } else {
            videoView.pause();
        }
    }

    private void status(String message) {
        if (statusLabel != null) {
            if (isSubtitleVisible() && isNativePlayerVisible()) {
                return;
            }
            statusLabel.setText(message);
            boolean transcriptMessage = message != null && message.toLowerCase(Locale.US).contains("transcript");
            if (nativePlayerFullscreen && !transcriptMessage && isNativePlayerVisible()) {
                statusLabel.setVisibility(View.GONE);
                return;
            }
            statusLabel.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideChromeRunnable);
            handler.postDelayed(hideChromeRunnable, 2500);
        }
    }

    private boolean isSubtitleVisible() {
        if (subtitleView == null || subtitleView.getVisibility() != View.VISIBLE) {
            return false;
        }
        CharSequence text = subtitleView.getText();
        return text != null && !text.toString().trim().isEmpty();
    }

    private void toggleFullscreen() {
        setFullscreen(!fullscreen);
    }

    private void setFullscreen(boolean enabled) {
        fullscreen = enabled;
        rootView.setPadding(0, 0, 0, 0);
        if (enabled) {
            enterFullscreen();
            scheduleChromeHide();
        } else {
            nativePlayerFullscreen = false;
            exitFullscreen();
            showChrome();
        }
    }

    private void enterFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
            return;
        }
        enterFullscreenLegacy();
    }

    private void exitFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
            return;
        }
        exitFullscreenLegacy();
    }

    @SuppressWarnings("deprecation")
    private void enterFullscreenLegacy() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @SuppressWarnings("deprecation")
    private void exitFullscreenLegacy() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : dp(24);
    }

    private int navigationBarInset() {
        int resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return resourceId > 0 ? Math.min(getResources().getDimensionPixelSize(resourceId), dp(16)) : 0;
    }

    private static final class HlsCaptionCache {
        final File file;
        final long offsetMs;
        final long endMs;
        final int segmentCount;

        HlsCaptionCache(File file, long offsetMs, long endMs, int segmentCount) {
            this.file = file;
            this.offsetMs = offsetMs;
            this.endMs = endMs;
            this.segmentCount = segmentCount;
        }
    }

    private static final class HlsSegment {
        final String url;
        final long startMs;
        final long durationMs;
        final long sequence;
        final byte[] aesKey;
        final byte[] aesIv;

        HlsSegment(String url, long startMs, long durationMs, long sequence, byte[] aesKey, byte[] aesIv) {
            this.url = url;
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.sequence = sequence;
            this.aesKey = aesKey;
            this.aesIv = aesIv;
        }
    }

    private interface IntConsumer {
        void accept(int value);
    }
}
