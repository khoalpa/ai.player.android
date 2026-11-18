package com.aiplayer.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Copies a user-requested TDLib media file into the app's Saved Videos directory. */
final class TdlibSavedVideoImporter {
    interface Callback { void completed(File file, Exception error); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    TdlibSavedVideoImporter(Context context) { this.context = context.getApplicationContext(); }

    void importFile(String path, Callback callback) {
        worker.execute(() -> {
            File partial = null;
            try {
                File root = new File(context.getNoBackupFilesDir(), "tdlib/files").getCanonicalFile();
                File input = new File(path).getCanonicalFile();
                // Never accept an arbitrary path supplied by a forged TDLib response.
                if (!input.getPath().startsWith(root.getPath() + File.separator)
                        || !input.isFile() || !input.canRead())
                    throw new IOException("Tệp TDLib không nằm trong thư mục riêng của ứng dụng");
                long length = input.length();
                if (length <= 0 || length > VideoUrlPolicy.MAX_BYTES)
                    throw new IOException("Dung lượng video không hợp lệ hoặc vượt 2 GB");
                File directory = new SavedVideoStore(context).directory();
                if (directory.getUsableSpace() < length + 16L * 1024 * 1024)
                    throw new IOException("Không đủ dung lượng lưu trữ");
                String name = "telegram_" + System.currentTimeMillis() + "_" +
                        UUID.randomUUID().toString().substring(0, 8) + ".mp4";
                partial = new File(directory, name + ".part");
                File output = new File(directory, name);
                long bytes = 0;
                try (FileInputStream source = new FileInputStream(input);
                     FileOutputStream target = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = source.read(buffer)) != -1) {
                        bytes += count;
                        if (bytes > VideoUrlPolicy.MAX_BYTES || directory.getUsableSpace() < 1024 * 1024)
                            throw new IOException("Không đủ dung lượng hoặc video quá lớn");
                        target.write(buffer, 0, count);
                    }
                    target.getFD().sync();
                }
                if (bytes != length || !partial.renameTo(output))
                    throw new IOException("Không thể lưu tệp video Telegram");
                main.post(() -> callback.completed(output, null));
            } catch (Exception error) {
                if (partial != null) partial.delete();
                main.post(() -> callback.completed(null, error));
            }
        });
    }
}
