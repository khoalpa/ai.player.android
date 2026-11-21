package com.aiplayer.android;

import java.io.File;
import java.util.function.ObjLongConsumer;

final class TelegramPlaybackHandoff {
    private final long position;
    private final ObjLongConsumer<File> openVideo;

    TelegramPlaybackHandoff(long currentPosition, long rememberedPosition, ObjLongConsumer<File> openVideo) {
        this.position = currentPosition >= 0 ? currentPosition : rememberedPosition;
        this.openVideo = openVideo;
    }

    void open(File file) {
        openVideo.accept(file, position);
    }
}
