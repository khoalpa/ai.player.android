package com.aiplayer.android;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class ContextModelStore {
    static final String NAME = "Qwen3-4B Q4_K_M";
    static final String SHA256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5";
    private static final String FILENAME = "Qwen3-4B-Q4_K_M.gguf";
    private static final String URL = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/bc64014/" + FILENAME;
    private static final long MAX_BYTES = 2_600_000_000L;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Object DOWNLOAD_LOCK = new Object();

    interface Progress {
        void update(long written, long total);
    }

    final String name;
    final String sizeLabel;
    final String sha256;
    private final String filename;
    private final String url;
    private final long maxBytes;

    final File file;
    final File checkpoints;

    ContextModelStore(Context context, boolean light) {
        name = light ? "Qwen3-1.7B Q4_K_M" : NAME;
        sizeLabel = light ? "1,28 GB" : "2,50 GB";
        sha256 = light ? "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5" : SHA256;
        filename = light ? "Qwen3-1.7B-Q4_K_M.gguf" : FILENAME;
        url = light ? "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/" + filename : URL;
        maxBytes = light ? 1_350_000_000L : MAX_BYTES;
        file = new File(new File(context.getNoBackupFilesDir(), "context-model"), filename);
        checkpoints = new File(context.getCacheDir(), "context-translation");
    }

    boolean installed() {
        return file.isFile();
    }

    ModelExport exportModel() throws IOException {
        if (!installed()) throw new IOException("Qwen chưa được cài.");
        return new ModelExport(filename, "application/octet-stream",
                java.util.Collections.singletonMap(filename, file));
    }

    static boolean supported() {
        return android.os.Process.is64Bit() && Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
    }

    private File partial() {
        return new File(file.getParentFile(), filename + ".part");
    }

    void importModel(android.content.ContentResolver resolver, android.net.Uri uri,
                     BooleanSupplier cancelled, Progress progress) throws Exception {
        synchronized (DOWNLOAD_LOCK) {
            File directory = file.getParentFile();
            Files.createDirectories(directory.toPath());
            File staging = new File(directory, filename + ".import");
            try (android.content.res.AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r")) {
                if (descriptor == null) throw new IOException("Không đọc được tệp Qwen.");
                long size = descriptor.getLength();
                if (size > maxBytes) throw new IOException("Tệp Qwen vượt kích thước cho phép.");
                if (size >= 0 && directory.getUsableSpace() + staging.length() < size) {
                    throw new IOException("Không đủ dung lượng trống để nhập Qwen.");
                }
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                long written = 0;
                long reported = 0;
                try (InputStream in = descriptor.createInputStream(); FileOutputStream out = new FileOutputStream(staging)) {
                    byte[] buffer = new byte[128 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        written += count;
                        if (written > maxBytes) throw new IOException("Tệp Qwen vượt kích thước cho phép.");
                        out.write(buffer, 0, count);
                        hash.update(buffer, 0, count);
                        if (written - reported >= 4_000_000) {
                            reported = written;
                            progress.update(written, size);
                        }
                    }
                    out.getFD().sync();
                }
                if (!sha256.equals(hex(hash.digest()))) throw new IOException("SHA-256 của tệp Qwen không khớp.");
                checkCancelled(cancelled);
                Files.move(staging.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                progress.update(written, written);
            } finally {
                Files.deleteIfExists(staging.toPath());
            }
        }
    }

    long partialBytes() {
        return partial().length();
    }

    void download(BooleanSupplier cancelled, Progress progress) throws Exception {
        synchronized (DOWNLOAD_LOCK) {
            checkCancelled(cancelled);
            if (!installed()) downloadModel(cancelled, progress);
        }
    }

    private void downloadModel(BooleanSupplier cancelled, Progress progress) throws Exception {
        File directory = file.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Không tạo được thư mục model.");
        File partial = partial();
        long offset = partial.length();
        if (offset >= maxBytes) {
            Files.delete(partial.toPath());
            offset = 0;
        }
        if (offset > 0 && transfer(partial, offset, cancelled, progress)) return;
        transfer(partial, 0, cancelled, progress);
    }

    private boolean transfer(File partial, long offset, BooleanSupplier cancelled, Progress progress) throws Exception {
        checkCancelled(cancelled);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int status = connection.getResponseCode();
            checkCancelled(cancelled);
            long size;
            if (offset > 0 && status == 206) {
                size = resumedSize(connection.getHeaderField("Content-Range"), offset, maxBytes);
                if (size < 0) return false;
            } else if (status == 200) {
                offset = 0;
                size = connection.getContentLengthLong();
            } else if (offset > 0 && status == 416) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                hashPartial(partial, digest, new byte[128 * 1024], cancelled);
                if (sha256.equals(hex(digest.digest()))) {
                    install(partial, cancelled);
                    return true;
                }
                return false;
            } else {
                throw new IOException("Máy chủ không trả về tệp model.");
            }
            if (size > maxBytes) throw new IOException("Tệp model vượt kích thước dự kiến.");
            long required = (size > 0 ? size : maxBytes) - offset;
            long available = partial.getParentFile().getUsableSpace() + (offset == 0 ? partial.length() : 0);
            if (available < required) throw new IOException("Không đủ dung lượng trống để tải model.");

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            if (offset > 0) hashPartial(partial, digest, buffer, cancelled);
            long written = offset;
            progress.update(written, size);
            long lastUpdate = android.os.SystemClock.elapsedRealtime();
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partial, offset > 0)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    if (written + count > (size > 0 ? size : maxBytes)) {
                        throw new IOException("Tệp model vượt kích thước dự kiến.");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    written += count;
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastUpdate >= 500) {
                        progress.update(written, size);
                        lastUpdate = now;
                    }
                }
                output.getFD().sync();
            }
            checkCancelled(cancelled);
            if (size > 0 && written != size) throw new IOException("Tải model bị gián đoạn. Hãy tiếp tục tải.");
            if (!sha256.equals(hex(digest.digest()))) {
                Files.deleteIfExists(partial.toPath());
                throw new IOException("Mã kiểm tra model không khớp. Hãy tải lại.");
            }
            install(partial, cancelled);
            return true;
        } finally {
            connection.disconnect();
        }
    }

    private void install(File partial, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        if (!partial.renameTo(file)) throw new IOException("Không lưu được model đã tải.");
    }

    private static void hashPartial(File partial, MessageDigest digest, byte[] buffer,
                                    BooleanSupplier cancelled) throws IOException {
        try (InputStream existing = new FileInputStream(partial)) {
            int count;
            while ((count = existing.read(buffer)) != -1) {
                checkCancelled(cancelled);
                digest.update(buffer, 0, count);
            }
        }
    }

    static long resumedSize(String header, long offset) {
        return resumedSize(header, offset, MAX_BYTES);
    }

    private static long resumedSize(String header, long offset, long maxBytes) {
        if (header == null) return -1;
        Matcher range = CONTENT_RANGE.matcher(header);
        if (!range.matches()) return -1;
        try {
            long start = Long.parseLong(range.group(1));
            long end = Long.parseLong(range.group(2));
            long total = Long.parseLong(range.group(3));
            return start == offset && end >= start && end == total - 1 && total <= maxBytes ? total : -1;
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException();
    }

    static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }
}
