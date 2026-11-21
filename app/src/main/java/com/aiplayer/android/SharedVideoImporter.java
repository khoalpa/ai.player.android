package com.aiplayer.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Imports content explicitly shared by another app (including an authenticated Telegram app).
 * No Telegram credentials, tokens or background access are requested or stored.
 */
final class SharedVideoImporter {
    interface Callback { void complete(File file, Exception error); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    SharedVideoImporter(Context context) { this.context = context.getApplicationContext(); }

    void importVideo(Uri uri, Callback callback) {
        io.execute(() -> {
            File partial = null;
            try {
                if (uri == null || !"content".equals(uri.getScheme()))
                    throw new IllegalArgumentException("Chỉ hỗ trợ video được chia sẻ qua content URI");
                ContentResolver resolver = context.getContentResolver();
                String ext = SharedVideoImportPolicy.extension(resolver.getType(uri));
                SavedVideoStore store = new SavedVideoStore(context);
                File dir = store.directory();
                long claimed = -1;
                String title = null;
                try (Cursor cursor = resolver.query(uri,
                        new String[]{OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                        int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) claimed = cursor.getLong(sizeColumn);
                        if (nameColumn >= 0) title = cursor.getString(nameColumn);
                    }
                } catch (RuntimeException ignored) {
                    // Some providers do not expose their metadata.
                }
                if (claimed > VideoUrlPolicy.MAX_BYTES)
                    throw new IOException("Video vượt giới hạn 2 GB");
                if (claimed > 0) StorageSpace.require(context, dir, claimed + StorageSpace.VIDEO_RESERVE);
                String stem = "shared_" + System.currentTimeMillis() + "_" +
                        UUID.randomUUID().toString().substring(0, 8);
                partial = new File(dir, stem + ext + ".part");
                File output = new File(dir, stem + ext);
                long copied = 0;
                try (InputStream in = resolver.openInputStream(uri)) {
                    if (in == null) throw new IOException("Không mở được video chia sẻ");
                    try (FileOutputStream out = new FileOutputStream(partial)) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            copied += count;
                            if (copied > VideoUrlPolicy.MAX_BYTES)
                                throw new IOException("Video vượt giới hạn 2 GB");
                            StorageSpace.require(context, dir, count + StorageSpace.WRITE_RESERVE);
                            out.write(buffer, 0, count);
                        }
                        out.getFD().sync();
                    }
                }
                SavedVideoStore.validateVideo(partial);
                if (copied == 0 || !partial.renameTo(output))
                    throw new IOException("Không thể lưu video chia sẻ");
                store.remember(output, "Chia sẻ", null, title);
                main.post(() -> callback.complete(output, null));
            } catch (Exception error) {
                if (partial != null) partial.delete();
                main.post(() -> callback.complete(null, error));
            }
        });
        io.shutdown();
    }
}
