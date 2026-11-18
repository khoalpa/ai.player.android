package com.aiplayer.android;

import android.content.Context;

final class VideoUrlSourceFeedback {
    static String invalidMp4(Context context, String host) {
        return "URL không trả về tệp video MP4 hợp lệ.";
    }
}
