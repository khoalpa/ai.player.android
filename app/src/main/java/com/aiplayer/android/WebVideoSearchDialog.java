package com.aiplayer.android;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;

/** Observes media requests while the user interacts with an HTTPS page. */
final class WebVideoSearchDialog {
    private static final int MAX_OBSERVED = 24;
    private final LinkedHashSet<String> observed = new LinkedHashSet<>();
    private final AlertDialog dialog;
    private final FrameLayout root;
    private final LinearLayout content;
    private final AppSettings settings;
    private final MainActivity activity;
    private final WebView web;
    private final LinearLayout.LayoutParams inlineWeb;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private View collapseButton;
    private View rotateButton;
    private int inlineOrientation;
    private final List<View> hiddenControls = new ArrayList<>();
    private int inlineWidth;
    private int inlineHeight;
    private int inlineSystemUi;
    private android.graphics.drawable.Drawable inlineBackground;
    private Object fullscreenBackCallback;

    WebVideoSearchDialog(MainActivity activity, AppSettings settings, String page,
                         Consumer<List<String>> check, Runnable openOriginal) {
        this.activity = activity;
        this.settings = settings;
        web = new WebView(activity);
        var config = web.getSettings();
        config.setJavaScriptEnabled(true);
        config.setDomStorageEnabled(true);
        config.setAllowFileAccess(false);
        config.setAllowContentAccess(false);
        config.setSupportMultipleWindows(false);
        config.setJavaScriptCanOpenWindowsAutomatically(false);
        config.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        config.setMediaPlaybackRequiresUserGesture(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"https".equalsIgnoreCase(request.getUrl().getScheme());
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                synchronized (observed) {
                    observed.clear();
                }
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)
                        || "content".equalsIgnoreCase(scheme)) {
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                if ("https".equalsIgnoreCase(scheme) && !request.isForMainFrame()
                        && "GET".equals(request.getMethod()) && looksLikeMedia(request)) {
                    synchronized (observed) {
                        String url = request.getUrl().toString();
                        observed.remove(url);
                        if (observed.size() >= MAX_OBSERVED) observed.remove(observed.iterator().next());
                        observed.add(url);
                    }
                }
                return null;
            }
        });
        root = new FrameLayout(activity);
        content = WebVideoDialogLayout.body(activity);
        root.addView(content, new FrameLayout.LayoutParams(-1, -2));
        WebVideoDialogLayout.header(content, settings, "Mở trang để tìm video", false, this::close);
        android.widget.TextView hint = new android.widget.TextView(activity);
        hint.setText("Nhấn phát trên trang, sau đó chọn Kiểm tra nguồn.");
        WebVideoDialogLayout.add(content, hint, 8);
        var configuration = activity.getResources().getConfiguration();
        int webHeight = Math.max(96, Math.min(540, configuration.screenHeightDp
                - (configuration.fontScale > 1.2f ? 360 : 285)));
        inlineWeb = new LinearLayout.LayoutParams(
                -1, WebVideoDialogLayout.dp(activity, webHeight), 1);
        inlineWeb.topMargin = WebVideoDialogLayout.dp(activity, 8);
        content.addView(web, inlineWeb);
        var browser = WebVideoDialogLayout.button(activity, settings, "Mở trình duyệt", false, () -> {
            close();
            openOriginal.run();
        });
        var inspect = WebVideoDialogLayout.button(activity, settings, "Kiểm tra nguồn", true, () -> {});
        if (configuration.screenWidthDp >= 360 && configuration.fontScale <= 1.15f) {
            var footer = new android.widget.LinearLayout(activity);
            var browserParams = new android.widget.LinearLayout.LayoutParams(0, -2, 1);
            browserParams.setMarginEnd(WebVideoDialogLayout.dp(activity, 8));
            footer.addView(browser, browserParams);
            footer.addView(inspect, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
            WebVideoDialogLayout.add(content, footer, 16);
        } else {
            WebVideoDialogLayout.add(content, inspect, 16);
            WebVideoDialogLayout.add(content, browser, 8);
        }
        WebVideoDialogLayout.add(content, WebVideoDialogLayout.button(activity, settings,
                "Mở rộng trang", false, this::expandPage), PlayerAppearance.CONTROL_GAP_DP);
        dialog = new PlayerAppearance.DialogBuilder(activity, settings).create();
        dialog.setView(root, 0, 0, 0, 0);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (!dialog.isShowing() || fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                if (collapseButton != null) leaveFullscreen();
                enterFullscreen();
                fullscreenView = view;
                fullscreenCallback = callback;
                content.setVisibility(View.INVISIBLE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                showRotateButton();
            }

            @Override public void onHideCustomView() {
                if (fullscreenView != null) leaveFullscreen();
            }
        });
        dialog.setOnKeyListener((ignored, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK || (fullscreenView == null && collapseButton == null)) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) leaveFullscreen();
            return true;
        });
        dialog.setOnDismissListener(ignored -> {
            leaveFullscreen();
            web.stopLoading();
            content.removeView(web);
            web.destroy();
        });
        PlayerAppearance.showDialog(dialog, settings);
        inspect.setOnClickListener(button ->
                web.evaluateJavascript("Array.from(document.querySelectorAll('video'), "
                        + "v => v.currentSrc || v.src).slice(0, 6)", value -> {
                    if (!dialog.isShowing()) return;
                    var urls = new LinkedHashSet<String>();
                    try {
                        JSONArray sources = new JSONArray(value);
                        for (int i = 0; i < sources.length(); i++) {
                            String url = sources.optString(i);
                            if (url.startsWith("https://")) urls.add(url);
                        }
                    } catch (JSONException ignored) {
                        // Requests observed by WebView may still contain the media source.
                    }
                    synchronized (observed) {
                        var recent = new ArrayList<>(observed);
                        java.util.Collections.reverse(recent);
                        urls.addAll(recent);
                    }
                    if (urls.isEmpty()) {
                        hint.setText("Chưa thấy nguồn video. Hãy thử nhấn phát hoặc mở trình duyệt.");
                        return;
                    }
                    dialog.dismiss();
                    check.accept(new ArrayList<>(urls));
                }));
        web.loadUrl(page);
    }

    private void expandPage() {
        enterFullscreen();
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child != web && child.getVisibility() == View.VISIBLE) {
                hiddenControls.add(child);
                child.setVisibility(View.GONE);
            }
        }
        content.setPadding(0, 0, 0, 0);
        content.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        web.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        collapseButton = WebVideoDialogLayout.button(web.getContext(), settings,
                "Thu nhỏ", false, this::leaveFullscreen);
        var buttonParams = new FrameLayout.LayoutParams(-2, -2,
                android.view.Gravity.TOP | android.view.Gravity.START);
        int margin = WebVideoDialogLayout.dp(web.getContext(), PlayerAppearance.CONTROL_GAP_DP);
        buttonParams.topMargin = margin;
        buttonParams.setMarginStart(margin);
        root.addView(collapseButton, buttonParams);
        showRotateButton();
    }

    private void showRotateButton() {
        var button = new android.widget.ImageButton(activity);
        button.setImageResource(R.drawable.ic_control_rotate);
        button.setColorFilter(android.graphics.Color.WHITE);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setContentDescription("Xoay màn hình ngang/dọc");
        PlayerAppearance.styleSurface(button, 0xcc222222, false);
        button.setOnClickListener(view -> activity.toggleVideoOrientation());
        rotateButton = button;
        int size = WebVideoDialogLayout.dp(activity, PlayerAppearance.MIN_TOUCH_DP);
        int gap = WebVideoDialogLayout.dp(activity, PlayerAppearance.CONTROL_GAP_DP);
        var params = new FrameLayout.LayoutParams(size, size,
                android.view.Gravity.TOP | android.view.Gravity.END);
        params.topMargin = gap;
        params.setMarginEnd(gap);
        root.addView(button, params);
    }

    @SuppressWarnings("deprecation")
    private void enterFullscreen() {
        inlineOrientation = activity.getRequestedOrientation();
        var window = dialog.getWindow();
        inlineWidth = window.getAttributes().width;
        inlineHeight = window.getAttributes().height;
        View decor = window.getDecorView();
        inlineSystemUi = decor.getSystemUiVisibility();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // Neutralize the floating dialog's inset background while the content fills the window.
        inlineBackground = decor.getBackground();
        window.setBackgroundDrawableResource(android.R.color.black);
        decor.setSystemUiVisibility(inlineSystemUi | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback back = this::leaveFullscreen;
            dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, back);
            fullscreenBackCallback = back;
        }
    }

    @SuppressWarnings("deprecation")
    private void leaveFullscreen() {
        if (fullscreenView == null && collapseButton == null) return;
        if (rotateButton != null) {
            root.removeView(rotateButton);
            rotateButton = null;
        }
        var callback = fullscreenCallback;
        if (fullscreenView != null) {
            root.removeView(fullscreenView);
            fullscreenView = null;
            fullscreenCallback = null;
            content.setVisibility(View.VISIBLE);
        }
        if (collapseButton != null) {
            root.removeView(collapseButton);
            collapseButton = null;
            for (View child : hiddenControls) child.setVisibility(View.VISIBLE);
            hiddenControls.clear();
            int inset = WebVideoDialogLayout.dp(web.getContext(), PlayerAppearance.HORIZONTAL_INSET_DP);
            content.setPadding(inset, 0, inset,
                    WebVideoDialogLayout.dp(web.getContext(), PlayerAppearance.GROUP_GAP_DP));
            content.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));
            web.setLayoutParams(inlineWeb);
        }
        var window = dialog.getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 33 && fullscreenBackCallback != null) {
            dialog.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) fullscreenBackCallback);
            fullscreenBackCallback = null;
        }
        window.setBackgroundDrawable(inlineBackground);
        window.setLayout(inlineWidth, inlineHeight);
        window.getDecorView().setSystemUiVisibility(inlineSystemUi);
        if (activity.getRequestedOrientation() != inlineOrientation) {
            activity.setRequestedOrientation(inlineOrientation);
        }
        if (callback != null) callback.onCustomViewHidden();
    }

    private static boolean looksLikeMedia(WebResourceRequest request) {
        String path = request.getUrl().getPath();
        if (path != null && path.toLowerCase(Locale.ROOT)
                .matches(".*\\.(mp4|m4v|webm|mkv|mov|m3u8|mpd|avi|flv)$")) return true;
        for (var header : request.getRequestHeaders().entrySet()) {
            if (!header.getKey().equalsIgnoreCase("Accept")) continue;
            String accept = header.getValue().toLowerCase(Locale.ROOT);
            return accept.contains("video/") || accept.contains("mpegurl") || accept.contains("dash+xml");
        }
        return false;
    }

    void close() {
        dialog.dismiss();
    }
}
