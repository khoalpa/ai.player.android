package com.aiplayer.android;

import android.content.Context;
import android.text.format.Formatter;

import java.io.File;
import java.io.IOException;

final class StorageSpace {
    private static final String LOW_SPACE = "Không đủ dung lượng lưu trữ";
    static final long VIDEO_RESERVE = 16L * 1024 * 1024;
    static final long WRITE_RESERVE = 1024 * 1024;

    private StorageSpace() {}

    static void require(Context context, File directory, long needed) throws IOException {
        long available = directory.getUsableSpace();
        if (available < needed) {
            throw new IOException(LOW_SPACE + ".\nCần " + Formatter.formatFileSize(context, needed)
                    + " · Còn trống " + Formatter.formatFileSize(context, available));
        }
    }

    static boolean isLowSpace(String message) {
        return message != null && message.contains(LOW_SPACE);
    }
}
