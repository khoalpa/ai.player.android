package com.aiplayer.android;

/** Narrow accepted media types for explicit user-shared video imports. */
final class SharedVideoImportPolicy {
    private SharedVideoImportPolicy() {}

    static String extension(String mime) {
        if (mime == null) throw new IllegalArgumentException("Không xác định định dạng video");
        switch (mime.toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].trim()) {
            case "video/mp4": return ".mp4";
            case "video/webm": return ".webm";
            case "video/x-matroska": return ".mkv";
            default: throw new IllegalArgumentException("Chỉ nhận video MP4, WebM hoặc MKV được chia sẻ");
        }
    }
}
