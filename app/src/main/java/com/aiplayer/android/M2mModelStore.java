package com.aiplayer.android;

import android.content.Context;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.net.ssl.HttpsURLConnection;

final class M2mModelStore {
    static final String NAME = "M2M100 418M INT8";
    private static final String BACKUP_NAME = "M2M100-418M-INT8.zip";
    static final String ENCODER = "onnx/encoder_model_int8.onnx";
    static final String DECODER = "onnx/decoder_model_int8.onnx";
    static final String PIECES = "sentencepiece.bpe.model";
    static final String VOCAB = "vocab.json";
    static final String TOKENIZER_CONFIG = "tokenizer_config.json";
    private static final String BASE = "https://huggingface.co/Xenova/m2m100_418M/resolve/9c374f0/";
    private static final String[] FILES = {ENCODER, DECODER, PIECES, VOCAB, TOKENIZER_CONFIG};
    private static final String[] HASHES = {
            "b47e2d5f3fe1123a8916a30ac0cf648870faef00d33fbd06afa9a333d8a3c457",
            "e4cc03767eaac41e4316bc2ea47d127fc2313a33a4e0ab5a07604a6af2e00880",
            "", "", ""
    };
    private static final long[] LIMITS = {300_000_000L, 900_000_000L, 3_000_000L, 5_000_000L, 10_000L};
    private final File directory;

    M2mModelStore(Context context) {
        this(new File(context.getNoBackupFilesDir(), "m2m100-int8-9c374f0"));
    }

    private M2mModelStore(File directory) {
        this.directory = directory;
    }

    File file(String remotePath) {
        File location = !directory.exists() && backup().isDirectory() ? backup() : directory;
        return new File(location, new File(remotePath).getName());
    }

    private File backup() {
        return new File(directory.getParentFile(), directory.getName() + ".backup");
    }

    private void restoreBackup() throws IOException {
        File backup = backup();
        if (!directory.exists() && backup.isDirectory()) move(backup, directory);
    }

    private static void move(File from, File to) throws IOException {
        Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    boolean installed() {
        for (String path : FILES) if (!file(path).isFile()) return false;
        return true;
    }

    long installedBytes() {
        long bytes = 0;
        for (String path : FILES) bytes += file(path).length();
        return bytes;
    }

    ModelExport exportModel() throws IOException {
        if (!installed()) throw new IOException("M2M100 chưa được cài đủ tệp.");
        Map<String, File> files = new java.util.LinkedHashMap<>();
        for (String path : FILES) {
            File modelFile = file(path);
            files.put(modelFile.getName(), modelFile);
        }
        return new ModelExport(BACKUP_NAME, "application/zip", files);
    }

    synchronized void install(BooleanSupplier cancelled, ContextModelStore.Progress progress) throws Exception {
        restoreBackup();
        Files.createDirectories(directory.toPath());
        long total = 0;
        for (int i = 0; i < FILES.length; i++) {
            checkCancelled(cancelled);
            File destination = file(FILES[i]);
            if (!destination.isFile()) transfer(i, destination, total, cancelled, progress);
            total += destination.length();
        }
        progress.update(total, total);
    }

    private void transfer(int index, File destination, long completed, BooleanSupplier cancelled,
                          ContextModelStore.Progress progress) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(BASE + FILES[index]).openConnection();
        try {
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() != 200) throw new IOException("Máy chủ chưa trả về tệp model.");
            try (InputStream input = connection.getInputStream()) {
                copy(index, destination, input, connection.getContentLengthLong(), completed, cancelled, progress);
            }
        } finally {
            connection.disconnect();
        }
    }

    synchronized void importFiles(ContentResolver resolver, List<Uri> documents,
                                  BooleanSupplier cancelled, ContextModelStore.Progress progress) throws Exception {
        restoreBackup();
        String name = documents.size() == 1 ? documentName(resolver, documents.get(0)) : null;
        boolean archive = name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
        if (installed() || archive) {
            reinstall(resolver, documents, archive, cancelled, progress);
            return;
        }
        importNewFiles(resolver, documents, cancelled, progress);
    }

    private void importNewFiles(ContentResolver resolver, List<Uri> documents,
                                BooleanSupplier cancelled, ContextModelStore.Progress progress) throws Exception {
        Files.createDirectories(directory.toPath());
        Map<String, Uri> selected = new HashMap<>();
        for (Uri document : documents) {
            selected.put(documentName(resolver, document), document);
        }
        long total = 0;
        for (int i = 0; i < FILES.length; i++) {
            checkCancelled(cancelled);
            File destination = file(FILES[i]);
            Uri document = selected.get(destination.getName());
            if (!destination.isFile() && document != null) {
                try (InputStream input = resolver.openInputStream(document)) {
                    if (input == null) throw new IOException("Không đọc được " + destination.getName());
                    copy(i, destination, input, -1, total, cancelled, progress);
                }
            }
            total += destination.length();
        }
        if (!installed()) {
            java.util.ArrayList<String> missing = new java.util.ArrayList<>();
            for (String path : FILES) if (!file(path).isFile()) missing.add(file(path).getName());
            throw new IOException("Còn thiếu: " + String.join(", ", missing));
        }
        progress.update(total, total);
    }

    private static String documentName(ContentResolver resolver, Uri document) {
        try (Cursor row = resolver.query(document, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return row != null && row.moveToFirst() ? row.getString(0) : null;
        }
    }

    private void importArchive(ContentResolver resolver, Uri document, BooleanSupplier cancelled,
                               ContextModelStore.Progress progress) throws Exception {
        Files.createDirectories(directory.toPath());
        try (InputStream input = resolver.openInputStream(document)) {
            if (input == null) throw new IOException("Không đọc được ZIP của M2M100.");
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(input)) {
                java.util.zip.ZipEntry entry;
                long total = 0;
                while ((entry = zip.getNextEntry()) != null) {
                    checkCancelled(cancelled);
                    int index = 0;
                    while (index < FILES.length && !file(FILES[index]).getName().equals(entry.getName())) index++;
                    if (index == FILES.length || entry.isDirectory() || file(FILES[index]).exists()) {
                        throw new IOException("ZIP M2M100 chứa mục không hợp lệ hoặc bị trùng.");
                    }
                    File destination = file(FILES[index]);
                    copy(index, destination, zip, entry.getSize(), total, cancelled, progress);
                    total += destination.length();
                    zip.closeEntry();
                }
                if (!installed()) throw new IOException("ZIP M2M100 phải chứa đủ 5 tệp model.");
                progress.update(total, total);
            }
        }
    }

    private void reinstall(ContentResolver resolver, List<Uri> documents, boolean archive, BooleanSupplier cancelled,
                           ContextModelStore.Progress progress) throws Exception {
        File backup = backup();
        File staging = new File(directory.getParentFile(), directory.getName() + ".import");
        M2mModelStore replacement = new M2mModelStore(staging);
        replacement.delete();
        try {
            if (archive) replacement.importArchive(resolver, documents.get(0), cancelled, progress);
            else replacement.importNewFiles(resolver, documents, cancelled, progress);
            checkCancelled(cancelled);
            clearFiles(backup);
            if (directory.exists()) move(directory, backup);
            try {
                move(staging, directory);
            } catch (IOException error) {
                try {
                    restoreBackup();
                } catch (IOException restoreError) {
                    error.addSuppressed(restoreError);
                }
                throw error;
            }
            clearFiles(backup);
        } finally {
            replacement.delete();
        }
    }

    private void copy(int index, File destination, InputStream input, long size, long completed,
                      BooleanSupplier cancelled, ContextModelStore.Progress progress) throws Exception {
        File partial = new File(directory, destination.getName() + ".part");
        try {
            long limit = LIMITS[index];
            if (size > limit || size == 0) throw new IOException("Kích thước tệp model không hợp lệ.");
            if (directory.getUsableSpace() + partial.length() < (size < 0 ? limit : size)) {
                throw new IOException("Không đủ bộ nhớ trống để tải M2M100.");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0;
            long reported = 0;
            try (FileOutputStream output = new FileOutputStream(partial)) {
                byte[] bytes = new byte[128 * 1024];
                int count;
                while ((count = input.read(bytes)) != -1) {
                    checkCancelled(cancelled);
                    written += count;
                    if (written > limit) throw new IOException("Tệp model vượt kích thước cho phép.");
                    output.write(bytes, 0, count);
                    digest.update(bytes, 0, count);
                    if (written - reported >= 4_000_000) {
                        progress.update(completed + written, -1);
                        reported = written;
                    }
                }
            }
            if (written == 0 || (size >= 0 && written != size)
                    || (!HASHES[index].isEmpty() && !HASHES[index].equals(ContextModelStore.hex(digest.digest())))) {
                throw new IOException("Tệp model M2M100 không vượt qua kiểm tra toàn vẹn.");
            }
            checkCancelled(cancelled);
            Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial.toPath());
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Đã dừng tải M2M100.");
    }

    synchronized void delete() throws IOException {
        clearFiles(backup());
        clearFiles(directory);
    }

    private static void clearFiles(File folder) throws IOException {
        for (String path : FILES) {
            String name = new File(path).getName();
            Files.deleteIfExists(new File(folder, name).toPath());
            Files.deleteIfExists(new File(folder, name + ".part").toPath());
        }
        Files.deleteIfExists(folder.toPath());
    }
}
