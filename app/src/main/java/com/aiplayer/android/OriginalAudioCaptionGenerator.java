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
    private static final long CUE_LEAD_IN_MS = 120L;
    private static final long CUE_TAIL_MS = 320L;
    private static final long MAX_CUE_DURATION_MS = 2_800L;
    private static final long MAX_WORD_GAP_MS = 650L;
    private static final int MAX_CUE_CHARS = 34;

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

    private static final class Timing {
        final long start = SystemClock.elapsedRealtimeNanos();
        long codec;
        long pcm;
        long vosk;
        long firstCue = -1;
        long audioMs;
    }

    OriginalAudioCaptionGenerator(Context context) {
        this.context = context.getApplicationContext();
        this.modelStore = new VoskModelStore(context);
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
            if (key.equals(cachedKey)) {
                recognize(videoUri, audioTrack, cachedModel, callback, timing, "memory");
                return;
            }
            releaseModel();
            modelStore.prepare(sourceLanguage, new VoskModelStore.Callback() {
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
        executor.execute(() -> {
            modelStore.close();
            releaseModel();
        });
        executor.shutdown();
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
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        Recognizer recognizer = null;
        try {
            extractor.setDataSource(context, videoUri, null);
            MediaFormat audioFormat = extractor.getTrackFormat(audioTrack);
            String mime = audioFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("audio/")) {
                callback.onFinished(cues, "Video này không có track âm thanh để tạo caption.");
                return;
            }
            int sampleRate = audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    : 16000;
            int channelCount = audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? Math.max(1, audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    : 1;
            extractor.selectTrack(audioTrack);
            long trackOffsetMs = Math.max(0L, extractor.getSampleTime() / 1000L);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(audioFormat, null, null, 0);
            decoder.start();

            timing.codec += SystemClock.elapsedRealtimeNanos() - started;
            started = SystemClock.elapsedRealtimeNanos();
            recognizer = new Recognizer(model, sampleRate);
            recognizer.setWords(true);
            timing.vosk += SystemClock.elapsedRealtimeNanos() - started;
            long endMs = decodeAndRecognize(extractor, decoder, recognizer, cues, callback,
                    channelCount, trackOffsetMs, timing);
            if (callback.isCancelled() || Thread.currentThread().isInterrupted()) {
                outcome = "cancelled";
                return;
            }
            started = SystemClock.elapsedRealtimeNanos();
            addResultCue(cues, recognizer.getFinalResult(), Math.max(endMs, lastCueEnd(cues)));
            timing.vosk += SystemClock.elapsedRealtimeNanos() - started;
            recordFirstCue(cues, timing);
            if (trackOffsetMs > 0) {
                cues.replaceAll(cue -> new TranscriptCue(
                        cue.startMs + trackOffsetMs, cue.endMs + trackOffsetMs, cue.sourceText));
            }
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
            if (recognizer != null) {
                recognizer.close();
            }
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
            Recognizer recognizer,
            List<TranscriptCue> cues,
            Callback callback,
            int channelCount, long trackOffsetMs, Timing timing
    ) {
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
                channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        && outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                    throw new IllegalStateException("Định dạng PCM của track chưa được hỗ trợ.");
                }
            }
            if (outputIndex >= 0) {
                ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                if (outputBuffer != null && info.size > 0) {
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
                    lastTimeMs = Math.max(lastTimeMs, info.presentationTimeUs / 1000L - trackOffsetMs);
                    if (lastTimeMs - lastStatusMs >= 5000L) {
                        lastStatusMs = lastTimeMs;
                        callback.onStatus("Dang nhan dang am goc " + formatTime(lastTimeMs) + "...");
                    }
                    timing.audioMs = lastTimeMs;
                    started = SystemClock.elapsedRealtimeNanos();
                    if (recognizer.acceptWaveForm(pcm, pcmSize)) {
                        addResultCue(cues, recognizer.getResult(), lastTimeMs);
                        recordFirstCue(cues, timing);
                    }
                    timing.vosk += SystemClock.elapsedRealtimeNanos() - started;
                    // The next recognition result can still shorten the last cue.
                    int stable = cues.size() - 1;
                    long now = SystemClock.elapsedRealtime();
                    if (stable > published && (published == 0 || now - lastPublishMs >= 350)) {
                        List<TranscriptCue> batch = new ArrayList<>(stable - published);
                        for (int i = published; i < stable; i++) {
                            TranscriptCue cue = cues.get(i);
                            batch.add(new TranscriptCue(cue.startMs + trackOffsetMs,
                                    cue.endMs + trackOffsetMs, cue.sourceText));
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

    private void addResultCue(List<TranscriptCue> cues, String resultJson, long endMs) {
        try {
            JSONObject result = new JSONObject(resultJson);
            String text = result.optString("text", "").trim();
            if (text.isEmpty()) {
                return;
            }
            JSONArray words = result.optJSONArray("result");
            if (words != null && words.length() > 0) {
                int before = cues.size();
                addWordTimedCues(cues, words);
                if (cues.size() > before) {
                    trimOverlaps(cues, before);
                    return;
                }
            }
            long startMs = cues.isEmpty() ? Math.max(0, endMs - 4000) : Math.max(cues.get(cues.size() - 1).endMs, endMs - 5000);
            long fixedEndMs = Math.max(startMs + 1000, endMs);
            cues.add(new TranscriptCue(startMs, fixedEndMs, text));
        } catch (Exception ignored) {
        }
    }

    private void addWordTimedCues(List<TranscriptCue> cues, JSONArray words) {
        long groupStartMs = -1;
        long groupEndMs = -1;
        StringBuilder groupText = new StringBuilder();
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.optJSONObject(i);
            if (word == null) {
                continue;
            }
            String token = word.optString("word", "").trim();
            if (token.isEmpty()) {
                continue;
            }
            long startMs = Math.max(0, Math.round(word.optDouble("start", 0) * 1000.0));
            long endMs = Math.max(startMs + 80, Math.round(word.optDouble("end", startMs / 1000.0) * 1000.0));
            boolean shouldSplit = groupText.length() > 0
                    && (startMs - groupEndMs > MAX_WORD_GAP_MS
                    || endMs - groupStartMs > MAX_CUE_DURATION_MS
                    || groupText.length() + token.length() + 1 > MAX_CUE_CHARS);
            if (shouldSplit) {
                addWordGroupCue(cues, groupStartMs, groupEndMs, groupText.toString());
                groupStartMs = -1;
                groupEndMs = -1;
                groupText.setLength(0);
            }
            if (groupStartMs < 0) {
                groupStartMs = startMs;
            }
            if (groupText.length() > 0) {
                groupText.append(' ');
            }
            groupText.append(token);
            groupEndMs = Math.max(groupEndMs, endMs);
        }
        if (groupText.length() > 0) {
            addWordGroupCue(cues, groupStartMs, groupEndMs, groupText.toString());
        }
    }

    private void addWordGroupCue(List<TranscriptCue> cues, long startMs, long endMs, String text) {
        String cleanText = text == null ? "" : text.trim();
        if (cleanText.isEmpty() || startMs < 0 || endMs <= startMs) {
            return;
        }
        long cueStartMs = Math.max(0, startMs - CUE_LEAD_IN_MS);
        long cueEndMs = Math.max(cueStartMs + 250, endMs + CUE_TAIL_MS);
        cues.add(new TranscriptCue(cueStartMs, cueEndMs, cleanText));
    }

    private void trimOverlaps(List<TranscriptCue> cues, int startIndex) {
        int first = Math.max(1, startIndex);
        for (int i = first; i < cues.size(); i++) {
            TranscriptCue previous = cues.get(i - 1);
            TranscriptCue current = cues.get(i);
            if (previous.endMs <= current.startMs) {
                continue;
            }
            long adjustedEndMs = Math.max(previous.startMs + 250, current.startMs - 40);
            cues.set(i - 1, copyCue(previous, previous.startMs, adjustedEndMs));
        }
    }

    private TranscriptCue copyCue(TranscriptCue cue, long startMs, long endMs) {
        TranscriptCue copy = new TranscriptCue(startMs, endMs, cue.sourceText);
        copy.targetText = cue.targetText;
        return copy;
    }

    private long lastCueEnd(List<TranscriptCue> cues) {
        return cues.isEmpty() ? 0 : cues.get(cues.size() - 1).endMs;
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
