package com.aiplayer.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ModelExport {
    final String name;
    final String mimeType;
    private final Map<String, File> files;

    ModelExport(String name, String mimeType, Map<String, File> files) {
        this.name = name;
        this.mimeType = mimeType;
        this.files = files;
    }

    static ModelExport vosk(String packageName, File directory) throws IOException {
        if (!new File(directory, "conf/model.conf").isFile()) throw new IOException("Model Vosk chưa được cài.");
        Map<String, File> files = new LinkedHashMap<>();
        collect(directory, packageName.substring(0, packageName.length() - 4), files);
        return new ModelExport(packageName, "application/zip", files);
    }

    private static void collect(File directory, String prefix, Map<String, File> files) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Không đọc được thư mục model.");
        for (File child : children) {
            String entry = prefix + "/" + child.getName();
            if (child.isDirectory()) collect(child, entry, files);
            else files.put(entry, child);
        }
    }

    long bytes() {
        long total = 0;
        for (File file : files.values()) total += file.length();
        return total;
    }

    void write(OutputStream output, BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        if (!"application/zip".equals(mimeType)) {
            copy(files.values().iterator().next(), output, buffer, cancelled, progress, 0);
            return;
        }
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            long written = 0;
            for (Map.Entry<String, File> entry : files.entrySet()) {
                checkCancelled(cancelled);
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                written = copy(entry.getValue(), zip, buffer, cancelled, progress, written);
                zip.closeEntry();
            }
        }
    }

    private static long copy(File file, OutputStream output, byte[] buffer, BooleanSupplier cancelled,
                             LongConsumer progress, long written) throws IOException {
        long reported = written;
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(cancelled);
                output.write(buffer, 0, count);
                written += count;
                if (written - reported >= 4_000_000) {
                    reported = written;
                    progress.accept(written);
                }
            }
        }
        checkCancelled(cancelled);
        return written;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }
}
