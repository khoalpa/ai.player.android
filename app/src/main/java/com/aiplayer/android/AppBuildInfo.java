package com.aiplayer.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

final class AppBuildInfo {
    final String summary;
    final String versionSummary;
    final String packageName;
    final String variant;
    final String shortCommit;
    final String sourceState;
    private final String clipboardText;

    AppBuildInfo(Activity activity) {
        packageName = activity.getPackageName();
        String version = BuildConfig.VERSION_NAME;
        long build = BuildConfig.VERSION_CODE;
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(packageName, 0);
            version = info.versionName;
            build = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Use the build metadata if package inspection is unavailable.
        }
        variant = BuildConfig.DISTRIBUTION_FLAVOR.isEmpty() ? BuildConfig.BUILD_TYPE
                : BuildConfig.DISTRIBUTION_FLAVOR + " / " + BuildConfig.BUILD_TYPE;
        String commit = BuildConfig.GIT_COMMIT;
        String fullCommit = commit.isEmpty() ? "Không xác định" : commit;
        shortCommit = commit.isEmpty() ? fullCommit : commit.substring(0, Math.min(12, commit.length()));
        switch (BuildConfig.GIT_WORKTREE) {
            case "dirty": sourceState = "Có thay đổi cục bộ"; break;
            case "clean": sourceState = "Khớp với commit"; break;
            default: sourceState = "Không xác định"; break;
        }
        String info = "Phiên bản: " + version + "\nMã bản dựng: " + build
                + "\nGói Android: " + packageName + "\nBiến thể: " + variant;
        String state = "\nMã nguồn: " + sourceState;
        summary = version + (commit.isEmpty() ? "" : " · " + shortCommit);
        versionSummary = "Phiên bản " + version + " · Bản dựng " + build;
        clipboardText = "AI Player\n" + info + "\nCommit: " + fullCommit + state;
    }

    boolean copy(Activity activity) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        if (clipboard == null) return false;
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Player", clipboardText));
        return true;
    }
}
