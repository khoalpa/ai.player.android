package com.aiplayer.android;

import android.content.Context;

final class VideoUrlSourceFeedback {
    static String invalidMp4(Context context, String host) {
        String profile = VideoHostRegistry.find(context, host);
        return profile == null ? "URL không trả về tệp video MP4 hợp lệ."
                : "Host " + profile + " có trong danh mục ai.player. Android hiện cần URL MP4 trực tiếp.";
    }
}
