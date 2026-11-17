package com.aiplayer.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.OpenableColumns;
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

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public final class MainActivity extends Activity {
    private static final int OPEN_VIDEO = 1;
    private static final int OPEN_SUBTITLES = 2;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final long STATUS_TIMEOUT_MS = 4_000L;
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
    private PlaybackSessionStore sessionStore;
    private Uri savedVideo;
    private Uri currentVideo;
    private String currentVideoIdentity;
    private long savedTranscriptVersion = -1;
    private boolean destroyed;
    private int openRequest;
    private CueSpeech speech;
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView subtitle;
    private TextView status;
    private LinearLayout feedback;
    private Button dismissStatus;
    private TextView title;
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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = AppSettings.load(this);
        sessionStore = new PlaybackSessionStore(this);
        session = new VideoSession(this, this::updateControls);
        player = new ExoPlayer.Builder(this).build();
        setContentView(buildContent());
        speech = new CueSpeech(this, this::onSpeechError);
        applyVideoVolume();
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
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
                showStatus("Không phát được video: " + error.getErrorCodeName());
            }
        });
        updateControls("Chọn video để bắt đầu.");
        sessionStore.load(null, restored -> {
            if (destroyed || openRequest != 0 || restored == null) return;
            if (hasPersistedRead(restored.uri)) {
                savedVideo = restored.uri;
                openVideo(restored.uri, restored);
            } else {
                sessionStore.clearRecent();
                updateControls("Hãy chọn lại video để cấp quyền đọc.");
            }
        });
    }

    private LinearLayout buildContent() {
        LinearLayout root = column();
        root.setBackgroundColor(getColor(R.color.ai_background));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        chrome = row(root);
        chrome.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(this);
        title.setText("AI Player");
        title.setTextSize(18);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(12), 0, dp(4), 0);
        chrome.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        iconButton(chrome, R.drawable.ic_control_open, "Mở video", false, this::openPicker);
        Button menu = iconButton(chrome, R.drawable.ic_control_more, "Tùy chọn video", false, null);
        menu.setOnClickListener(anchor -> {
            PopupMenu popup = new PopupMenu(this, anchor);
            popup.getMenu().add("Nhập SRT/VTT").setEnabled(hasVideo && !session.busy)
                    .setOnMenuItemClickListener(item -> { pick(OPEN_SUBTITLES, "*/*"); return true; });
            popup.getMenu().add("Tạo lại transcript")
                    .setEnabled(!session.tracks.isEmpty() && !session.cues.isEmpty() && !session.busy)
                    .setOnMenuItemClickListener(item -> { chooseTrack(); return true; });
            popup.getMenu().add("Đổi ngôn ngữ bản dịch")
                    .setEnabled(session.targetLanguage != null && !session.busy)
                    .setOnMenuItemClickListener(item -> { chooseTranslationLanguage(); return true; });
            popup.getMenu().add("Cài đặt").setOnMenuItemClickListener(item -> {
                showSettings();
                return true;
            });
            popup.show();
        });

        FrameLayout surface = new FrameLayout(this);
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 2));
        PlayerView view = new PlayerView(this);
        playerView = view;
        view.setPlayer(player);
        view.setFullscreenButtonClickListener(this::setFullscreen);
        surface.addView(view, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout welcome = column();
        welcome.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams centered = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        surface.addView(welcome, centered);
        empty = welcome;
        Button openVideo = compactButton(welcome, "Mở video", this::openPicker);
        stylePrimaryButton(openVideo);
        setLeadingIcon(openVideo, R.drawable.ic_control_open);
        compactButton(welcome, "Không tìm thấy video?", () -> new AlertDialog.Builder(this)
                .setTitle("Không tìm thấy video?")
                .setMessage("Trình chọn tệp Android có thể ẩn Android/data. Nếu có bản sao video trong Movies hoặc Download, hãy mở từ thư mục đó.")
                .setPositiveButton("Đóng", null).show());

        subtitle = new TextView(this);
        subtitle.setTextColor(0xffffffff);
        subtitle.setBackgroundColor(0xc0000000);
        subtitle.setTextSize(18);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(8), dp(4), dp(8), dp(4));
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        surface.addView(subtitle, overlay);
        subtitle.setVisibility(View.GONE);
        View controller = view.findViewById(androidx.media3.ui.R.id.exo_controller);
        View bottomBar = view.findViewById(androidx.media3.ui.R.id.exo_bottom_bar);
        View progressBar = view.findViewById(androidx.media3.ui.R.id.exo_progress);
        Runnable positionSubtitle = () -> {
            int margin = dp(8);
            if (controller != null && controller.getVisibility() == View.VISIBLE) {
                if (bottomBar != null) margin += bottomBar.getHeight();
                if (progressBar != null) margin += progressBar.getHeight();
            }
            if (overlay.bottomMargin != margin) {
                overlay.bottomMargin = margin;
                subtitle.requestLayout();
            }
        };
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)
                visibility -> positionSubtitle.run());
        surface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> positionSubtitle.run());

        feedback = row(root);
        feedback.setGravity(Gravity.CENTER_VERTICAL);
        feedback.setPadding(dp(12), 0, dp(12), 0);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        feedback.addView(progress, new LinearLayout.LayoutParams(dp(64), dp(24)));
        status = new TextView(this);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        dismissStatus = iconButton(feedback, R.drawable.ic_control_cancel, "Đóng thông báo",
                false, () -> showStatus(""));
        actions = row(root);
        primary = button(actions, "Tạo transcript", this::primaryAction);
        stylePrimaryButton(primary);
        cancel = button(actions, "Hủy", () -> {
            offerTranslation = false;
            session.cancel();
            updateControls("Đã hủy tác vụ.");
        });
        shortcuts = row(root);
        setLeadingIcon(cancel, R.drawable.ic_control_cancel);
        subtitlesButton = iconButton(shortcuts, R.drawable.ic_control_captions, "Phụ đề", true, () -> showMode(false));
        speechButton = iconButton(shortcuts, R.drawable.ic_control_mute, "Đọc thành tiếng", true, () -> showMode(true));
        transcript = iconButton(shortcuts, R.drawable.ic_control_transcript, "Mở transcript", true, () -> {
            setTranscriptVisible(!transcriptPanel.isShown());
            if (transcriptPanel.isShown()) resumeFollowing();
        });
        buildTranscript(root);
        return root;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private Button compactButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(v -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return button;
    }

    private Button iconButton(LinearLayout row, int icon, String description, boolean weighted, Runnable action) {
        Button button = new Button(this);
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.control_icon_background);
        button.setCompoundDrawableTintList(getColorStateList(R.color.control_icon_foreground));
        button.setTextColor(getColorStateList(R.color.control_icon_foreground));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0);
        describeControl(button, description);
        if (action != null) button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(weighted ? 0 : dp(48), -2, weighted ? 1 : 0));
        return button;
    }

    private void stylePrimaryButton(Button button) {
        button.setBackgroundTintList(getColorStateList(R.color.primary_button_background));
        button.setTextColor(getColorStateList(R.color.primary_button_foreground));
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

    private void updateModeIcon(Button button, int icon, String label, CueTimeline.Mode mode) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0);
        button.setSelected(button.isEnabled() && mode != CueTimeline.Mode.OFF);
        button.setText(button.isSelected() ? (mode == CueTimeline.Mode.SOURCE ? "Gốc" : "Dịch") : "");
        describeControl(button, label + ": " + (button.isEnabled() ? MODES.get(mode.ordinal())
                : "chưa có transcript"));
    }

    private void setTranscriptVisible(boolean visible) {
        transcriptPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        transcript.setSelected(visible);
        describeControl(transcript, visible ? "Đóng transcript" : "Mở transcript");
    }

    private void openPicker() {
        pick(OPEN_VIDEO, "video/*");
    }

    private void setFullscreen(boolean enabled) {
        fullscreen = enabled;
        playerView.setFullscreenButtonState(enabled);
        getWindow().getDecorView().setSystemUiVisibility(enabled
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0);
        updateControls(null);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        setFullscreen(hasVideo && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE);
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
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    private String videoName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) {
            // Some document providers do not expose file metadata.
        }
        return "Video";
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            setFullscreen(false);
        } else if (transcriptPanel.isShown()) {
            setTranscriptVisible(false);
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
        Uri previous = savedVideo;
        savedTranscriptVersion = -1;
        if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Providers may grant read access only for the current session.
            }
        }
        savedVideo = hasPersistedRead(uri) ? uri : null;
        if (previous != null && !previous.equals(savedVideo)) {
            try {
                getContentResolver().releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The provider may already have revoked the grant.
            }
        }
    }

    private void openVideo(Uri uri, PlaybackSessionStore.Snapshot restored) {
        speech.stop();
        hasVideo = true;
        currentVideo = uri;
        currentVideoIdentity = restored == null ? null : restored.identity;
        offerTranslation = false;
        title.setText(videoName(uri));
        player.setPlayWhenReady(false);
        player.setMediaItem(MediaItem.fromUri(uri), restored == null ? 0L : restored.position);
        session.open(uri, settings.sourceLanguage, restored);
        player.prepare();
        playerView.showController();
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setFullscreen(true);
        }
        sync();
    }

    private void savePlaybackSession() {
        if (currentVideo == null) return;
        long version = session.transcriptVersion();
        long position = player.getCurrentPosition();
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
        form.addView(caption);
        Spinner picker = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter);
        picker.setSelection(Math.max(0, selected));
        picker.setContentDescription(label);
        form.addView(picker, new LinearLayout.LayoutParams(-1, dp(48)));
        return picker;
    }

    private Spinner languages(LinearLayout form, String label, List<String> codes, String current) {
        List<String> names = new ArrayList<>();
        for (String code : codes) names.add(languageName(code));
        return selection(form, label, names, codes.indexOf(current));
    }

    private LinearLayout form() {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(12), dp(20), dp(12));
        return form;
    }

    private AlertDialog.Builder sheet(String title, LinearLayout form) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        return new AlertDialog.Builder(this).setTitle(title).setView(scroll);
    }

    private void showSheet(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.show();
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
        chooseTranslationLanguage();
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

    private void showSettings() {
        LinearLayout form = form();
        List<String> codes = targetLanguages();
        Spinner source = languages(form, "Ngôn ngữ lời thoại", SOURCE_LANGUAGES, settings.sourceLanguage);
        Spinner target = languages(form, "Ngôn ngữ đích", codes, settings.targetLanguage);
        SeekBar videoVolume = volumeField(form, "Âm lượng video", settings.sourceVolume);
        showSheet(sheet("Cài đặt", form).setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", (dialog, which) -> {
                    settings.sourceLanguage = SOURCE_LANGUAGES.get(source.getSelectedItemPosition());
                    settings.targetLanguage = codes.get(target.getSelectedItemPosition());
                    settings.sourceVolume = videoVolume.getProgress();
                    settings.save(this);
                    updateControls(null);
                }));
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
        transcriptPanel.setBackgroundColor(getColor(R.color.ai_surface));
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
            view.setPadding(dp(12), dp(10), dp(12), dp(10));
            String timestamp = (active ? "Đang phát · " : "") + TranscriptParser.formatTime(cue.startMs);
            SpannableString content = new SpannableString(timestamp + "\n"
                    + (line.isEmpty() ? "Chưa có bản dịch" : line));
            content.setSpan(new ForegroundColorSpan(getColor(R.color.ai_muted)),
                    0, timestamp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(content);
            view.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            view.setBackgroundColor(active ? getColor(R.color.ai_selected) : Color.TRANSPARENT);
            return view;
        }
    }

    private SeekBar volumeField(LinearLayout form, String label, int value) {
        addLabel(form, label);
        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(value);
        seek.setContentDescription(label);
        form.addView(seek);
        return seek;
    }

    private void addLabel(LinearLayout form, String label) {
        TextView text = new TextView(this);
        text.setText(label);
        form.addView(text);
    }

    private void showStatus(String message) {
        main.removeCallbacks(hideStatus);
        status.setText(message);
        if (isTemporaryStatus(message)) main.postDelayed(hideStatus, STATUS_TIMEOUT_MS);
        refreshStatusVisibility();
    }

    private boolean isTemporaryStatus(String message) {
        return message.startsWith("Đã tạo ") || message.startsWith("Đã mở ")
                || message.startsWith("Đã nhập ") || message.startsWith("Đã hủy ")
                || (message.startsWith("Đã dịch offline ") && !message.contains(", lỗi "));
    }

    private void refreshStatusVisibility() {
        boolean showMessage = hasVideo && !fullscreen && status.length() > 0;
        status.setVisibility(showMessage ? View.VISIBLE : View.GONE);
        dismissStatus.setVisibility(showMessage && !session.busy ? View.VISIBLE : View.GONE);
        feedback.setVisibility(showMessage || progress.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
    }

    private void updateControls(String message) {
        boolean hasCues = !session.cues.isEmpty();
        boolean translated = session.targetLanguage != null;
        boolean busy = session.busy;
        boolean workspace = hasVideo && !fullscreen;
        chrome.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        empty.setVisibility(hasVideo ? View.GONE : View.VISIBLE);
        shortcuts.setVisibility(workspace ? View.VISIBLE : View.GONE);
        int missing = busy ? 0 : session.missingTranslations();
        actions.setVisibility(workspace && (busy || !translated || missing > 0) ? View.VISIBLE : View.GONE);
        primary.setVisibility(busy ? View.GONE : View.VISIBLE);
        primary.setEnabled(hasCues || !session.tracks.isEmpty());
        setLeadingIcon(primary, hasCues ? R.drawable.ic_control_translate : R.drawable.ic_control_generate);
        primary.setText(missing > 0 ? "Dịch lại " + missing + " đoạn"
                : hasCues ? "Dịch sang " + languageName(settings.targetLanguage) : "Tạo transcript");
        cancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setVisibility(busy && workspace ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(session.progressTotal == 0);
        if (session.progressTotal > 0) {
            progress.setMax(session.progressTotal);
            progress.setProgress(session.progressCompleted);
        }
        if (message != null) {
            showStatus(message);
            if (!busy) savePlaybackSession();
        }
        refreshStatusVisibility();
        transcript.setEnabled(hasCues);
        subtitlesButton.setEnabled(hasCues);
        speechButton.setEnabled(hasCues);
        if (!workspace || !hasCues) setTranscriptVisible(false);
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
                : R.drawable.ic_control_voice, "Đọc thành tiếng", read);
        refreshTranscript();
        if (speech != null) sync();
        if (offerTranslation && !busy) {
            offerTranslation = false;
            if (translated && workspace && settings.targetLanguage.equals(session.targetLanguage)) {
                new AlertDialog.Builder(this).setMessage("Bật phụ đề bản dịch?")
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
        main.post(tick);
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(tick);
        player.pause();
        speech.stop();
        savePlaybackSession();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacks(hideStatus);
        player.release();
        session.close();
        speech.close();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
