package com.aiplayer.android;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OriginalAudioCaptionGenerator {

    interface Callback {
        boolean isCancelled();

        void onStatus(String status);

        void onCues(List<TranscriptCue> cues);

        void onFinished(List<TranscriptCue> cues, String status);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final VoskModelStore modelStore;

    // Owned by the recognition executor.
    private Model cachedModel;
    private String cachedKey;
    private long cachedDeletionVersion;

    private static final class Timing {
        final long start = SystemClock.elapsedRealtimeNanos();
        long codec;
        long pcm;
        long vosk;
        long firstCue = -1;
        long audioMs;
    }

    OriginalAudioCaptionGenerator(Context context, VoskModelStore modelStore) {
        this.context = context.getApplicationContext();
        this.modelStore = modelStore;
    }

    void generate(Uri videoUri, int audioTrack, String sourceLanguage, Callback callback) {
        if (videoUri == null) {
            callback.onFinished(new ArrayList<>(), "Chưa có video cục bộ để tạo caption từ âm gốc.");
            return;
        }
        Timing timing = new Timing();
        String key = modelStore.modelKey(sourceLanguage);
        executor.execute(() -> {
            if (callback.isCancelled()) return;
            long deletionVersion = modelStore.deletionVersion();
            if (key.equals(cachedKey) && cachedDeletionVersion == deletionVersion) {
                recognize(videoUri, audioTrack, cachedModel, callback, timing, "memory");
                return;
            }
            releaseModel();
            modelStore.prepare(sourceLanguage, new VoskModelStore.Callback() {
                @Override
                public boolean isCancelled() {
                    return callback.isCancelled();
                }

                @Override
                public void onReady(Model model, String source) {
                    try {
                        executor.execute(() -> {
                            if (callback.isCancelled()) {
                                model.close();
                                return;
                            }
                            releaseModel();
                            cachedModel = model;
                            cachedKey = key;
                            cachedDeletionVersion = deletionVersion;
                            recognize(videoUri, audioTrack, model, callback, timing, source);
                        });
                    } catch (RejectedExecutionException ex) {
                        model.close();
                    }
                }

                @Override
                public void onError(String message) {
                    callback.onFinished(new ArrayList<>(), message);
                }

                @Override
                public void onStatus(String message) {
                    callback.onStatus(message);
                }
            });
        });
    }

    void close() {
        modelStore.cancel();
        executor.execute(() -> {
            modelStore.close();
            releaseModel();
        });
        executor.shutdown();
    }

    void cancel() {
        modelStore.cancel();
    }

    private void releaseModel() {
        if (cachedModel != null) {
            cachedModel.close();
            cachedModel = null;
            cachedKey = null;
        }
    }

    private void recognize(Uri videoUri, int audioTrack, Model model, Callback callback,
                           Timing timing, String modelSource) {
        long started = SystemClock.elapsedRealtimeNanos();
        long modelTime = started - timing.start;
        String outcome = "error";
        List<TranscriptCue> cues = new ArrayList<>();
        SpokenSentences sentences = new SpokenSentences(cues);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        PcmRecognition recognition = new PcmRecognition(model);
        try {
            extractor.setDataSource(context, videoUri, null);
            MediaFormat audioFormat = extractor.getTrackFormat(audioTrack);
            String mime = audioFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("audio/")) {
                callback.onFinished(cues, "Video này không có track âm thanh để tạo caption.");
                return;
            }
            int channelCount = audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? Math.max(1, audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    : 1;
            extractor.selectTrack(audioTrack);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(audioFormat, null, null, 0);
            decoder.start();

            timing.codec += SystemClock.elapsedRealtimeNanos() - started;
            long endMs = decodeAndRecognize(extractor, decoder, recognition, sentences, cues, callback,
                    channelCount, timing);
            if (callback.isCancelled() || Thread.currentThread().isInterrupted()) {
                outcome = "cancelled";
                return;
            }
            started = SystemClock.elapsedRealtimeNanos();
            if (recognition.recognizer != null) {
                addResult(sentences, recognition.recognizer.getFinalResult(), endMs, recognition.timestamps);
            }
            timing.vosk += SystemClock.elapsedRealtimeNanos() - started;
            sentences.finish();
            recordFirstCue(cues, timing);
            outcome = cues.isEmpty() ? "empty" : "complete";
            callback.onFinished(cues, cues.isEmpty()
                    ? "Không nhận được lời nói từ âm gốc."
                    : "Đã tạo " + cues.size() + " caption từ âm gốc bằng Vosk offline.");
        } catch (Exception ex) {
            callback.onFinished(new ArrayList<>(), "Không tạo được caption từ âm gốc: " + ex.getMessage());
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
                decoder.release();
            }
            recognition.close();
            extractor.release();
            Log.i("TranscriptTiming", "outcome=" + outcome + " model=" + modelSource
                    + " modelMs=" + modelTime / 1_000_000L
                    + " codecMs=" + timing.codec / 1_000_000L
                    + " pcmMs=" + timing.pcm / 1_000_000L
                    + " voskMs=" + timing.vosk / 1_000_000L
                    + " firstCueMs=" + timing.firstCue
                    + " totalMs=" + (SystemClock.elapsedRealtimeNanos() - timing.start) / 1_000_000L
                    + " audioPositionMs=" + timing.audioMs);
        }
    }

    private long decodeAndRecognize(
            MediaExtractor extractor,
            MediaCodec decoder,
            PcmRecognition recognition,
            SpokenSentences sentences,
            List<TranscriptCue> cues,
            Callback callback,
            int channelCount, Timing timing
    ) throws java.io.IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        long lastTimeMs = 0;
        long lastStatusMs = 0;
        long lastPublishMs = 0;
        int published = 0;

        byte[] pcm = new byte[0];
        boolean waitForOutput = false;
        while (!outputDone && !Thread.currentThread().isInterrupted() && !callback.isCancelled()) {
            long started = SystemClock.elapsedRealtimeNanos();
            int inputIndex;
            while (!inputDone && !callback.isCancelled() && !Thread.currentThread().isInterrupted()
                    && (inputIndex = decoder.dequeueInputBuffer(0)) >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                if (inputBuffer == null) throw new IllegalStateException("Missing audio input buffer");
                int sampleSize = extractor.readSampleData(inputBuffer, 0);
                if (sampleSize < 0) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inputDone = true;
                } else {
                    decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                    extractor.advance();
                }
                waitForOutput = false;
            }

            int outputIndex = decoder.dequeueOutputBuffer(info, waitForOutput ? 10_000L : 0);
            waitForOutput = outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER;
            timing.codec += SystemClock.elapsedRealtimeNanos() - started;
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outputFormat = decoder.getOutputFormat();
                recognition.configure(outputFormat);
                channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
            if (outputIndex >= 0) {
                ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                if (outputBuffer != null && info.size > 0) {
                    if (recognition.recognizer == null) {
                        MediaFormat outputFormat = decoder.getOutputFormat();
                        recognition.configure(outputFormat);
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    started = SystemClock.elapsedRealtimeNanos();
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    boolean mix = channelCount > 1 && info.size >= channelCount * 2;
                    int pcmSize = mix ? (info.size / (channelCount * 2)) * 2 : info.size;
                    if (pcm.length < pcmSize) pcm = new byte[pcmSize];
                    if (mix) {
                        downmixToMono16Bit(outputBuffer, pcm, pcmSize, channelCount);
                    } else {
                        outputBuffer.get(pcm, 0, pcmSize);
                    }
                    timing.pcm += SystemClock.elapsedRealtimeNanos() - started;
                    recognition.timestamps.append(info.presentationTimeUs, pcmSize / 2, recognition.sampleRate);
                    lastTimeMs = Math.max(lastTimeMs, info.presentationTimeUs / 1000L
                            + (pcmSize / 2) * 1000L / recognition.sampleRate);
                    if (lastTimeMs - lastStatusMs >= 5000L) {
                        lastStatusMs = lastTimeMs;
                        callback.onStatus("Dang nhan dang am goc " + formatTime(lastTimeMs) + "...");
                    }
                    timing.audioMs = lastTimeMs;
                    started = SystemClock.elapsedRealtimeNanos();
                    if (recognition.recognizer.acceptWaveForm(pcm, pcmSize)) {
                        addResult(sentences, recognition.recognizer.getResult(), lastTimeMs, recognition.timestamps);
                        recordFirstCue(cues, timing);
                    }
                    timing.vosk += SystemClock.elapsedRealtimeNanos() - started;
                    // Only emit sentences whose boundaries have been finalized.
                    int stable = cues.size();
                    long now = SystemClock.elapsedRealtime();
                    if (stable > published && (published == 0 || now - lastPublishMs >= 750)) {
                        List<TranscriptCue> batch = new ArrayList<>(stable - published);
                        for (int i = published; i < stable; i++) {
                            TranscriptCue cue = cues.get(i);
                            batch.add(cue.copy());
                        }
                        published = stable;
                        lastPublishMs = now;
                        callback.onCues(batch);
                    }
                }
                decoder.releaseOutputBuffer(outputIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }
        return lastTimeMs;
    }

    private static final class PcmRecognition {
        private final Model model;
        private Recognizer recognizer;
        private int sampleRate;
        private final AudioTimestampMap timestamps = new AudioTimestampMap();

        PcmRecognition(Model model) {
            this.model = model;
        }

        void configure(MediaFormat format) throws java.io.IOException {
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (rate <= 0 || channels <= 0) throw new java.io.IOException("Invalid PCM format");
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    && format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                throw new java.io.IOException("Unsupported PCM encoding");
            }
            if (recognizer != null && rate != sampleRate) {
                throw new java.io.IOException("PCM sample rate changed during recognition");
            }
            if (recognizer == null) {
                recognizer = new Recognizer(model, rate);
                sampleRate = rate;
                recognizer.setWords(true);
            }
        }

        void close() {
            if (recognizer != null) recognizer.close();
        }
    }

    private void addResult(SpokenSentences sentences, String resultJson, long endMs,
                           AudioTimestampMap timestamps) throws java.io.IOException {
        try {
            JSONObject result = new JSONObject(resultJson);
            JSONArray words = result.optJSONArray("result");
            if (words != null && words.length() > 0) {
                for (int i = 0; i < words.length(); i++) {
                    JSONObject word = words.optJSONObject(i);
                    if (word == null || word.optString("word").trim().isEmpty()
                            || !Double.isFinite(word.optDouble("start"))
                            || !Double.isFinite(word.optDouble("end"))) {
                        sentences.addUntimed(result.optString("text").trim(), endMs);
                        return;
                    }
                }
                for (int i = 0; i < words.length(); i++) {
                    JSONObject word = words.getJSONObject(i);
                    long start = Math.max(0, Math.round(word.getDouble("start") * 1000));
                    long end = Math.max(start + 1, Math.round(word.getDouble("end") * 1000));
                    sentences.add(word.getString("word").trim(),
                            timestamps.mediaMs(start), timestamps.mediaEndMs(end));
                }
            } else {
                sentences.addUntimed(result.optString("text").trim(), endMs);
            }
        } catch (org.json.JSONException ex) {
            throw new java.io.IOException("Invalid recognition result", ex);
        }
    }

    private void downmixToMono16Bit(ByteBuffer input, byte[] mono, int size, int channelCount) {
        for (int out = 0; out < size; out += 2) {
            int sum = 0;
            for (int channel = 0; channel < channelCount; channel++) {
                int low = input.get() & 0xFF;
                int high = input.get() << 8;
                sum += (short) (low | high);
            }
            int mixed = sum / channelCount;
            mono[out] = (byte) mixed;
            mono[out + 1] = (byte) (mixed >> 8);
        }
    }

    private void recordFirstCue(List<TranscriptCue> cues, Timing timing) {
        if (timing.firstCue < 0 && !cues.isEmpty()) {
            timing.firstCue = (SystemClock.elapsedRealtimeNanos() - timing.start) / 1_000_000L;
        }
    }

    private String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

}
