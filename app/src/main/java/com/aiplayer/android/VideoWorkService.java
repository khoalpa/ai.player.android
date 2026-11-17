package com.aiplayer.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/** Keeps user-requested transcript/translation work alive after the UI closes. */
public final class VideoWorkService extends Service {
    private static final String CHANNEL = "video_processing";
    private static final String CANCEL = "com.aiplayer.android.CANCEL_WORK";
    private static final int ID = 10;
    private static VideoWorkService running;
    private boolean stopping;
    private PowerManager.WakeLock wakeLock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable deadline = () -> onTimeout(0, 0);

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Xử lý transcript", NotificationManager.IMPORTANCE_LOW));
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "AIPlayer:VideoProcessing");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        main.postDelayed(deadline, 6 * 60 * 60 * 1000L);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stopping = false;
        // Always promote even if the work finished before this start was delivered.
        Notification notification = notification();
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ID, notification);
            }
        } catch (RuntimeException ex) {
            VideoWork work = VideoWork.existing();
            if (work != null) work.cancelWork("Không thể chạy tác vụ nền. Hãy mở lại ứng dụng và thử lại.");
            stopSelf();
            return START_NOT_STICKY;
        }
        running = this;
        VideoWork work = VideoWork.existing();
        if (intent != null && CANCEL.equals(intent.getAction()) && work != null) {
            work.cancelWork("Đã hủy tác vụ.");
        }
        refresh();
        return START_NOT_STICKY;
    }

    static void refresh() {
        if (running == null) return;
        VideoWork work = VideoWork.existing();
        if (work == null || !work.session.busy) {
            VideoWorkService service = running;
            running = null;
            service.stopping = true;
            service.stopForeground(STOP_FOREGROUND_REMOVE);
            service.stopSelf();
            if (work != null) work.serviceStopped();
        } else {
            running.getSystemService(NotificationManager.class).notify(ID, running.notification());
        }
    }

    private Notification notification() {
        VideoWork work = VideoWork.existing();
        String message = work == null || work.status() == null ? "Đang xử lý video" : work.status();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, VideoWorkService.class).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("AI Player · Xử lý transcript").setContentText(message)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Hủy", cancel).build()).build();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        VideoWork work = VideoWork.existing();
        if (work != null) work.cancelWork("Đã hết thời gian xử lý nền. Có thể tạo lại transcript hoặc dịch tiếp.");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (running == this) running = null;
        VideoWork work = VideoWork.existing();
        if (work != null && !stopping) work.serviceStopped();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
