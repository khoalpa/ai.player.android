package com.aiplayer.android;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

final class DevicePerformanceView extends LinearLayout {
    private static final long INTERVAL_MS = 2000;
    private static final int CHART_POINTS = 31;
    private final AppSettings settings;
    private final Supplier<VideoSession.QwenRun> lastRun;
    private final ActivityManager activityManager;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable tick = this::sample;
    private final TextView cpu, gpu, ram, hardware, appRam, qwen;
    private final LinearLayout details;
    private final Trend cpuTrend, ramTrend;
    private boolean attachedVisible, allowed, sampling, closed;
    private int generation;
    private long lastCpuMs, lastSampleMs;
    private VideoSession.QwenRun displayedRun;
    // Accessed only by the sampling worker.
    private String gpuName;
    private boolean vulkanAvailable;

    DevicePerformanceView(Context context, AppSettings settings, Supplier<VideoSession.QwenRun> lastRun) {
        super(context);
        this.settings = settings;
        this.lastRun = lastRun;
        activityManager = context.getSystemService(ActivityManager.class);
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(6), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(PlayerAppearance.color(context, android.R.attr.colorBackgroundFloating));
        surface.setCornerRadius(dp(14));
        setBackground(surface);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LayoutParams(-1, -2));
        TextView title = label("Hiệu năng thiết bị", 16, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LayoutParams(0, -2, 1));
        Button toggle = new Button(context);
        toggle.setAllCaps(false);
        toggle.setText("Chi tiết");
        PlayerAppearance.styleButton(toggle, PlayerAppearance.ButtonRole.UTILITY, settings);
        toggle.setMinHeight(dp(48));
        toggle.setMinimumHeight(dp(48));
        header.addView(toggle);

        cpu = metric("CPU", "AI Player · Đang đo");
        gpu = metric("GPU", "Đang nhận diện");
        ram = metric("RAM", "Đang đo");

        details = new LinearLayout(context);
        details.setOrientation(VERTICAL);
        details.setVisibility(GONE);
        addView(details, new LayoutParams(-1, -2));
        hardware = label("", 12, false);
        details.addView(hardware);
        cpuTrend = addTrend("CPU của AI Player · 60 giây", R.attr.playerAccentText);
        ramTrend = addTrend("RAM khả dụng · 60 giây", R.attr.playerIconSuccess);
        appRam = label("", 12, false);
        details.addView(appRam);
        qwen = label("Chưa có lượt dịch Qwen trong phiên này", 12, false);
        qwen.setPadding(0, dp(8), 0, 0);
        details.addView(qwen);
        toggle.setOnClickListener(view -> {
            boolean expand = details.getVisibility() == GONE;
            details.setVisibility(expand ? VISIBLE : GONE);
            toggle.setText(expand ? "Thu gọn" : "Chi tiết");
            if (expand) showLastRun();
        });
    }

    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        attachedVisible = isVisible;
        updateSampling();
    }

    void setSamplingAllowed(boolean allowed) {
        this.allowed = allowed;
        updateSampling();
    }

    private void updateSampling() {
        boolean active = attachedVisible && allowed;
        if (closed || sampling == active) return;
        sampling = active;
        generation++;
        removeCallbacks(tick);
        lastSampleMs = 0;
        cpuTrend.clear();
        ramTrend.clear();
        if (sampling) {
            cpu.setText("AI Player · Đang đo");
            showLastRun();
            post(tick);
        }
    }

    void close() {
        closed = true;
        removeCallbacks(tick);
        worker.shutdownNow();
    }

    private void sample() {
        if (closed || !sampling) return;
        int request = generation;
        worker.execute(() -> {
            if (gpuName == null) {
                try {
                    gpuName = readGpuName();
                } catch (RuntimeException error) {
                    gpuName = "Không đọc được tên GPU";
                    android.util.Log.w("DevicePerformance", "GPU renderer query failed", error);
                }
                vulkanAvailable = ContextModelStore.supported() && ContextTranslationClient.isGpuAvailable();
            }
            long now = SystemClock.elapsedRealtime();
            long cpuMs = Process.getElapsedCpuTime();
            int cores = Runtime.getRuntime().availableProcessors();
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            if (activityManager != null) activityManager.getMemoryInfo(memory);
            long appBytes = Debug.getPss() * 1024L;
            post(() -> {
                if (closed || !sampling || generation != request) return;
                renderSample(now, cpuMs, cores, memory, appBytes);
                showLastRun();
                postDelayed(tick, INTERVAL_MS);
            });
        });
    }

    private void renderSample(long now, long cpuMs, int cores, ActivityManager.MemoryInfo memory, long appBytes) {
        if (lastSampleMs != 0 && now > lastSampleMs) {
            float load = clamp((cpuMs - lastCpuMs) / ((now - lastSampleMs) * (float) cores));
            cpu.setText(String.format(Locale.getDefault(), "AI Player · %.0f%%", load * 100));
            cpuTrend.add(load);
        }
        lastSampleMs = now;
        lastCpuMs = cpuMs;
        gpu.setText(gpuName + (vulkanAvailable ? " · Vulkan khả dụng" : " · Vulkan cho Qwen không khả dụng"));
        if (memory.totalMem > 0) {
            ram.setText(formatBytes(memory.availMem) + " khả dụng / " + formatBytes(memory.totalMem));
            ramTrend.add(clamp(memory.availMem / (float) memory.totalMem));
        } else {
            ram.setText("Không đọc được RAM thiết bị");
        }
        String chip = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "";
        String cpuInfo = cores + " lõi CPU khả dụng";
        hardware.setText(chip.isEmpty() || Build.UNKNOWN.equalsIgnoreCase(chip) ? cpuInfo : chip + " · " + cpuInfo);
        appRam.setText(appBytes > 0 ? "RAM AI Player · " + formatBytes(appBytes) + " (PSS)"
                : "RAM AI Player · Không có số liệu PSS");
    }

    private void showLastRun() {
        VideoSession.QwenRun run = lastRun.get();
        if (run == displayedRun) return;
        displayedRun = run;
        if (run == null) {
            qwen.setText("Chưa có lượt dịch Qwen trong phiên này");
            return;
        }
        qwen.setText("Lượt dịch Qwen gần nhất\n" + run.processor + " · "
                + SavedVideosDialog.formatPosition(run.elapsedMs) + " · " + run.cues + " câu");
    }

    private TextView metric(String name, String initial) {
        LinearLayout row = new LinearLayout(getContext());
        row.setPadding(0, dp(4), 0, dp(4));
        row.addView(label(name, 13, true), new LayoutParams(dp(48), -2));
        TextView value = label(initial, 13, false);
        row.addView(value, new LayoutParams(0, -2, 1));
        addView(row, new LayoutParams(-1, -2));
        return value;
    }

    private Trend addTrend(String name, int colorAttr) {
        TextView heading = label(name, 12, true);
        heading.setPadding(0, dp(8), 0, 0);
        details.addView(heading);
        Trend trend = new Trend(PlayerAppearance.color(getContext(), colorAttr));
        details.addView(trend, new LayoutParams(-1, dp(42)));
        return trend;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextSize(settings.textSize(size));
        if (bold) label.setTypeface(null, Typeface.BOLD);
        return label;
    }

    private String formatBytes(long bytes) {
        return Formatter.formatShortFileSize(getContext(), bytes);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String readGpuName() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, null, 0, null, 0)) {
            return "Không đọc được tên GPU";
        }
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            int[] configAttrs = {EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE};
            if (EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                        new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
                surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                        new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
                if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE
                        && EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
                    if (renderer != null && !renderer.trim().isEmpty()) return renderer;
                }
            }
            return "Không đọc được tên GPU";
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            EGL14.eglReleaseThread();
        }
    }

    private final class Trend extends View {
        private final float[] values = new float[CHART_POINTS];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int color, grid;
        private int next, count;

        Trend(int color) {
            super(DevicePerformanceView.this.getContext());
            this.color = color;
            grid = PlayerAppearance.color(getContext(), android.R.attr.textColorSecondary);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void add(float value) {
            values[next] = value;
            next = (next + 1) % values.length;
            count = Math.min(count + 1, values.length);
            if (isShown()) invalidate();
        }

        void clear() {
            count = 0;
            next = 0;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float top = dp(4), bottom = getHeight() - dp(4);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(grid);
            paint.setAlpha(45);
            canvas.drawLine(0, top, getWidth(), top, paint);
            canvas.drawLine(0, bottom, getWidth(), bottom, paint);
            if (count == 0) return;
            paint.setColor(color);
            paint.setStrokeWidth(dp(2));
            path.reset();
            float step = (getWidth() - dp(2)) / (float) (values.length - 1);
            for (int i = 0; i < count; i++) {
                float x = dp(1) + step * (values.length - count + i);
                int index = (next - count + i + values.length) % values.length;
                float y = bottom - (bottom - top) * values[index];
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
                if (i == count - 1) canvas.drawCircle(x, y, dp(1), paint);
            }
            canvas.drawPath(path, paint);
        }
    }
}
