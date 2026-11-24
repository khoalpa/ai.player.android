package com.aiplayer.android;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class VietnameseModelBenchmark extends Instrumentation {
    private static final int SAMPLE_RATE = 16000;
    private static final String SMALL = "vosk-model-small-vn-0.4";
    private static final String LARGE = "vosk-model-vn-0.4";
    private String modelName;
    private boolean timingOnly;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        modelName = arguments.getString("model", "");
        timingOnly = "true".equals(arguments.getString("timingOnly"));
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            result.putString("benchmark", benchmark().toString());
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Exception | LinkageError error) {
            result.putString("error", error.toString());
            finish(android.app.Activity.RESULT_CANCELED, result);
        }
    }

    private JSONObject benchmark() throws Exception {
        if (!SMALL.equals(modelName) && !LARGE.equals(modelName)) {
            throw new IllegalArgumentException("Specify -e model " + SMALL + " or " + LARGE);
        }
        File input = new File(getTargetContext().getFilesDir(), "vosk-benchmark");
        File modelPath = new File(input, modelName);
        File corpus = new File(input, "corpus");
        List<String> rows = corpusRows(corpus);
        long baselinePss = Debug.getPss();
        long loadStart = SystemClock.elapsedRealtimeNanos();
        int edits = 0;
        int referenceWords = 0;
        double audioMs = 0;
        double decodeMs = 0;
        JSONArray clips = new JSONArray();
        reportProgress("Loading " + modelName);
        try (Model model = new Model(modelPath.getAbsolutePath())) {
            double loadMs = elapsedMillis(loadStart);
            long peakPss = Debug.getPss();
            for (String row : rows) {
                if (row.trim().isEmpty()) continue;
                String[] columns = row.split("\t", 2);
                if (columns.length != 2 || !columns[0].matches("[A-Za-z0-9_-]+\\.pcm")) {
                    throw new IOException("Expected a PCM filename and reference separated by a tab");
                }
                File clip = new File(corpus, columns[0]);
                String[] reference = timingOnly ? new String[0] : words(columns[1]);
                if ((!timingOnly && reference.length == 0) || clip.length() == 0 || clip.length() % 2 != 0) {
                    throw new IOException("Invalid reference or PCM data for " + columns[0]);
                }

                StringBuilder transcript = new StringBuilder();
                long start = SystemClock.elapsedRealtimeNanos();
                double clipDecodeMs;
                try (Recognizer recognizer = new Recognizer(model, SAMPLE_RATE);
                     BufferedInputStream audio = new BufferedInputStream(new FileInputStream(clip))) {
                    byte[] buffer = new byte[8000];
                    long processedBytes = 0;
                    long nextUpdate = 30L * SAMPLE_RATE * 2;
                    reportProgress("Decoding " + columns[0]);
                    int size;
                    while ((size = audio.read(buffer)) != -1) {
                        if ((size & 1) != 0) {
                            int lastByte = audio.read();
                            if (lastByte < 0) throw new IOException("Incomplete PCM sample");
                            buffer[size++] = (byte) lastByte;
                        }
                        if (recognizer.acceptWaveForm(buffer, size)) {
                            append(transcript, recognizer.getResult());
                        }
                        processedBytes += size;
                        if (processedBytes >= nextUpdate) {
                            reportProgress("Decoded " + processedBytes / (2 * SAMPLE_RATE) + " s of audio");
                            nextUpdate += 30L * SAMPLE_RATE * 2;
                        }
                    }
                    append(transcript, recognizer.getFinalResult());
                    clipDecodeMs = elapsedMillis(start);
                    peakPss = Math.max(peakPss, Debug.getPss());
                }

                double clipAudioMs = clip.length() * 1000.0 / (2 * SAMPLE_RATE);
                JSONObject clipResult = new JSONObject().put("file", columns[0])
                        .put("text", transcript.toString().trim()).put("decodeMs", clipDecodeMs)
                        .put("audioMs", clipAudioMs);
                if (!timingOnly) {
                    int clipEdits = editDistance(reference, words(transcript.toString()));
                    clipResult.put("edits", clipEdits).put("referenceWords", reference.length);
                    edits += clipEdits;
                    referenceWords += reference.length;
                }
                clips.put(clipResult);
                audioMs += clipAudioMs;
                decodeMs += clipDecodeMs;
            }
            if (clips.length() == 0) throw new IOException("No benchmark clips");
            JSONObject result = new JSONObject().put("model", modelName).put("loadMs", loadMs)
                    .put("baselinePssKiB", baselinePss).put("sampledPeakPssKiB", peakPss)
                    .put("realTimeFactor", decodeMs / audioMs).put("clips", clips);
            if (!timingOnly) result.put("wordErrorRate", (double) edits / referenceWords);
            return result;
        }
    }

    private void reportProgress(String message) {
        Bundle status = new Bundle();
        status.putString("progress", message);
        sendStatus(1, status);
    }

    private List<String> corpusRows(File corpus) throws IOException {
        if (!timingOnly) {
            return Files.readAllLines(new File(corpus, "references.tsv").toPath(), StandardCharsets.UTF_8);
        }
        String[] files = corpus.list((directory, name) -> name.endsWith(".pcm"));
        if (files == null) throw new IOException("Cannot read PCM corpus directory");
        return Arrays.stream(files).sorted().map(name -> name + "\t").collect(Collectors.toList());
    }

    private static double elapsedMillis(long start) {
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0;
    }

    private static void append(StringBuilder transcript, String result) throws Exception {
        transcript.append(new JSONObject(result).optString("text")).append(' ');
    }

    private static String[] words(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFC)
                .replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").trim();
        return normalized.isEmpty() ? new String[0] : normalized.split(" +");
    }

    private static int editDistance(String[] reference, String[] hypothesis) {
        int[] distances = new int[hypothesis.length + 1];
        for (int j = 0; j <= hypothesis.length; j++) distances[j] = j;
        for (int i = 1; i <= reference.length; i++) {
            int diagonal = distances[0];
            distances[0] = i;
            for (int j = 1; j <= hypothesis.length; j++) {
                int above = distances[j];
                int substitution = reference[i - 1].equals(hypothesis[j - 1]) ? 0 : 1;
                distances[j] = Math.min(diagonal + substitution, 1 + Math.min(above, distances[j - 1]));
                diagonal = above;
            }
        }
        return distances[hypothesis.length];
    }
}
