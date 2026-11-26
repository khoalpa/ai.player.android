package com.aiplayer.android;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class M2mTranslation {
    private static final int EOS = 2;
    private static final int UNKNOWN = 3;
    private static final int MAX_OUTPUT_TOKENS = 256;
    private final M2mModelStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Integer> vocabulary = new HashMap<>();
    private final Map<Integer, String> outputPieces = new HashMap<>();
    private final Map<String, Integer> languages = new HashMap<>();
    private OrtEnvironment environment;
    private OrtSession encoder;
    private OrtSession decoder;
    private long tokenizer;
    private OrtSession.RunOptions options;
    private volatile boolean cancelled;
    private volatile boolean sentenceCancelled;

    M2mTranslation(M2mModelStore store) {
        this.store = store;
    }

    Task<Void> prepare() {
        return ExecutorTasks.submit(worker, () -> {
            try {
                checkCancelled();
                System.loadLibrary("aiplayer_m2m");
                readVocabulary();
                tokenizer = openTokenizer(store.file(M2mModelStore.PIECES).getAbsolutePath()
                        .getBytes(StandardCharsets.UTF_8));
                if (tokenizer == 0) throw new IOException("Không mở được bộ tách từ M2M100.");
                environment = OrtEnvironment.getEnvironment();
                try (OrtSession.SessionOptions settings = new OrtSession.SessionOptions()) {
                    settings.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
                    encoder = environment.createSession(store.file(M2mModelStore.ENCODER).getAbsolutePath(), settings);
                    checkCancelled();
                    decoder = environment.createSession(store.file(M2mModelStore.DECODER).getAbsolutePath(), settings);
                }
                synchronized (this) {
                    checkCancelled();
                    options = new OrtSession.RunOptions();
                }
                return null;
            } catch (LinkageError error) {
                throw new IOException("Thiếu thư viện chạy M2M100 trên thiết bị.", error);
            }
        });
    }

    Task<String> translate(String source, String target, String text) {
        return ExecutorTasks.submit(worker, () -> {
            Integer sourceId = languages.get("__" + source + "__");
            Integer targetId = languages.get("__" + target + "__");
            if (sourceId == null || targetId == null) throw new IOException("M2M100 chưa hỗ trợ cặp ngôn ngữ này.");
            List<String> results = new ArrayList<>();
            for (String fragment : ContextTranslationClient.splitCue(text)) {
                results.add(translateFragment(sourceId, targetId, fragment));
            }
            return String.join(" ", results);
        });
    }

    private String translateFragment(int source, int target, String text) throws Exception {
        checkCancelled();
        byte[][] pieces = encode(tokenizer, text.getBytes(StandardCharsets.UTF_8));
        if (pieces == null || pieces.length > 1022) throw new IOException("Câu vượt giới hạn của M2M100.");
        long[] input = new long[pieces.length + 2];
        long[] mask = new long[input.length];
        Arrays.fill(mask, 1);
        input[0] = source;
        for (int i = 0; i < pieces.length; i++) {
            input[i + 1] = vocabulary.getOrDefault(new String(pieces[i], StandardCharsets.UTF_8), UNKNOWN);
        }
        input[input.length - 1] = EOS;
        try (OnnxTensor ids = tensor(input); OnnxTensor attention = tensor(mask)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", ids);
            inputs.put("attention_mask", attention);
            try (OrtSession.Result encoded = encoder.run(inputs, Collections.singleton("last_hidden_state"), options)) {
                OnnxTensor hidden = (OnnxTensor) encoded.get("last_hidden_state").orElseThrow(
                        () -> new IOException("Encoder M2M100 thiếu đầu ra."));
                return decode(target, attention, hidden);
            }
        }
    }

    private String decode(int target, OnnxTensor attention, OnnxTensor hidden) throws Exception {
        long[] generated = new long[MAX_OUTPUT_TOKENS + 2];
        generated[0] = EOS;
        generated[1] = target;
        StringBuilder text = new StringBuilder();
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("encoder_attention_mask", attention);
        inputs.put("encoder_hidden_states", hidden);
        for (int length = 2; length < generated.length; length++) {
            checkCancelled();
            int best = -1;
            float score = Float.NEGATIVE_INFINITY;
            try (OnnxTensor tokens = tensor(Arrays.copyOf(generated, length))) {
                inputs.put("input_ids", tokens);
                try (OrtSession.Result result = decoder.run(inputs, Collections.singleton("logits"), options)) {
                    OnnxTensor logits = (OnnxTensor) result.get("logits").orElseThrow(
                            () -> new IOException("Decoder M2M100 thiếu đầu ra."));
                    long[] shape = logits.getInfo().getShape();
                    int width = Math.toIntExact(shape[2]);
                    java.nio.FloatBuffer values = logits.getFloatBuffer();
                    int offset = values.limit() - width;
                    for (int id = 0; id < width; id++) {
                        if (id != EOS && id != UNKNOWN && !outputPieces.containsKey(id)) continue;
                        float value = values.get(offset + id);
                        if (value > score) {
                            best = id;
                            score = value;
                        }
                    }
                }
            }
            if (best == EOS && text.length() > 0) return text.toString().replace('▁', ' ').trim();
            if (best == -1 || best == EOS) break;
            generated[length] = best;
            text.append(outputPieces.getOrDefault(best, "⁇"));
        }
        throw new IOException("M2M100 chưa hoàn tất bản dịch của câu này.");
    }

    private OnnxTensor tensor(long[] values) throws OrtException {
        return OnnxTensor.createTensor(environment, LongBuffer.wrap(values), new long[]{1, values.length});
    }

    private void readVocabulary() throws Exception {
        JSONObject vocab = readJson(M2mModelStore.VOCAB);
        for (Iterator<String> keys = vocab.keys(); keys.hasNext();) {
            String piece = keys.next();
            int id = vocab.getInt(piece);
            vocabulary.put(piece, id);
            if (id > UNKNOWN) outputPieces.put(id, piece);
        }
        JSONArray codes = readJson(M2mModelStore.TOKENIZER_CONFIG).getJSONArray("additional_special_tokens");
        for (int i = 0; i < codes.length(); i++) languages.put(codes.getString(i), vocab.length() + i);
    }

    private JSONObject readJson(String file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(store.file(file).toPath()), StandardCharsets.UTF_8));
    }

    synchronized Task<Void> recoverAfterTimeout() {
        if (cancelled) {
            return com.google.android.gms.tasks.Tasks.forException(new IOException("M2M100 đã đóng."));
        }
        sentenceCancelled = true;
        try {
            options.setTerminate(true);
        } catch (OrtException error) {
            return com.google.android.gms.tasks.Tasks.forException(error);
        }
        // The single worker resets the terminate flag after the old inference exits.
        return ExecutorTasks.submit(worker, () -> {
            synchronized (this) {
                if (cancelled) throw new IOException("M2M100 đã đóng.");
                options.setTerminate(false);
                sentenceCancelled = false;
            }
            return null;
        });
    }

    private void checkCancelled() throws IOException {
        if (cancelled || sentenceCancelled) throw new IOException("Đã dừng dịch M2M100.");
    }

    synchronized void close() {
        if (cancelled) return;
        cancelled = true;
        if (options != null) {
            try {
                options.setTerminate(true);
            } catch (OrtException error) {
                android.util.Log.w("M2mTranslation", "Cannot terminate ONNX run", error);
            }
        }
        worker.execute(() -> {
            synchronized (this) {
                if (options != null) options.close();
            }
            try {
                if (encoder != null) encoder.close();
            } catch (OrtException error) {
                android.util.Log.w("M2mTranslation", "Cannot release encoder", error);
            }
            try {
                if (decoder != null) decoder.close();
            } catch (OrtException error) {
                android.util.Log.w("M2mTranslation", "Cannot release decoder", error);
            }
            if (tokenizer != 0) closeTokenizer(tokenizer);
        });
        worker.shutdown();
    }

    private static native long openTokenizer(byte[] path);
    private static native byte[][] encode(long handle, byte[] input);
    private static native void closeTokenizer(long handle);
}
