package com.aiplayer.android;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OriginalAudioCaptionGenerator {
    private static final long CUE_LEAD_IN_MS = 120L;
    private static final long CUE_TAIL_MS = 320L;
    private static final long MAX_CUE_DURATION_MS = 2_800L;
    private static final long MAX_WORD_GAP_MS = 650L;
    private static final int MAX_CUE_CHARS = 34;

    interface Callback {
        void onStatus(String status);

        void onFinished(List<TranscriptCue> cues, String status);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final VoskModelStore modelStore;

    OriginalAudioCaptionGenerator(Context context) {
        this.context = context.getApplicationContext();
        this.modelStore = new VoskModelStore(context);
    }

    void generate(Uri videoUri, String sourceLanguage, Callback callback) {
        if (videoUri == null) {
            callback.onFinished(new ArrayList<>(), "Chưa có video cục bộ để tạo caption từ âm gốc.");
            return;
        }
        modelStore.prepare(sourceLanguage, new VoskModelStore.Callback() {
            @Override
            public void onReady(Model model) {
                executor.execute(() -> recognize(videoUri, model, callback));
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
    }

    void close() {
        modelStore.close();
        executor.shutdownNow();
    }

    void generate(Uri videoUri, Map<String, String> headers, String sourceLanguage, Callback callback) {
        if (videoUri == null) {
            callback.onFinished(new ArrayList<>(), "Chưa có video để tạo caption từ âm gốc.");
            return;
        }
        modelStore.prepare(sourceLanguage, new VoskModelStore.Callback() {
            @Override
            public void onReady(Model model) {
                executor.execute(() -> recognize(videoUri, headers, model, callback));
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
    }

    private void recognize(Uri videoUri, Model model, Callback callback) {
        recognize(videoUri, null, model, callback);
    }

    private void recognize(Uri videoUri, Map<String, String> headers, Model model, Callback callback) {
        List<TranscriptCue> cues = new ArrayList<>();
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        Recognizer recognizer = null;
        try {
            extractor.setDataSource(context, videoUri, headers);
            MediaFormat audioFormat = null;
            int audioTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioFormat = format;
                    audioTrack = i;
                    break;
                }
            }
            if (audioFormat == null) {
                callback.onFinished(cues, "Video này không có track âm thanh để tạo caption.");
                return;
            }
            String mime = audioFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    : 16000;
            int channelCount = audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? Math.max(1, audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    : 1;
            extractor.selectTrack(audioTrack);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(audioFormat, null, null, 0);
            decoder.start();

            recognizer = new Recognizer(model, sampleRate);
            recognizer.setWords(true);
            decodeAndRecognize(extractor, decoder, recognizer, cues, callback, channelCount);
            addResultCue(cues, recognizer.getFinalResult(), Math.max(0, lastCueEnd(cues)));
            callback.onFinished(cues, cues.isEmpty()
                    ? "Không nhận được lời nói từ âm gốc."
                    : "Đã tạo " + cues.size() + " caption từ âm gốc bằng Vosk offline.");
        } catch (Exception ex) {
            callback.onFinished(cues, "Không tạo được caption từ âm gốc: " + ex.getMessage());
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
            model.close();
        }
    }

    private void decodeAndRecognize(
            MediaExtractor extractor,
            MediaCodec decoder,
            Recognizer recognizer,
            List<TranscriptCue> cues,
            Callback callback,
            int channelCount
    ) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        long lastTimeMs = 0;
        long lastStatusMs = 0;

        while (!outputDone) {
            if (!inputDone) {
                int inputIndex = decoder.dequeueInputBuffer(10000);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                    if (inputBuffer == null) {
                        continue;
                    }
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long sampleTime = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0);
                        extractor.advance();
                    }
                }
            }

            int outputIndex = decoder.dequeueOutputBuffer(info, 10000);
            if (outputIndex >= 0) {
                ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                if (outputBuffer != null && info.size > 0) {
                    byte[] pcm = new byte[info.size];
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    outputBuffer.get(pcm);
                    pcm = downmixToMono16Bit(pcm, channelCount);
                    lastTimeMs = Math.max(lastTimeMs, info.presentationTimeUs / 1000L);
                    if (lastTimeMs - lastStatusMs >= 5000L) {
                        lastStatusMs = lastTimeMs;
                        callback.onStatus("Dang nhan dang am goc " + formatTime(lastTimeMs) + "...");
                    }
                    if (recognizer.acceptWaveForm(pcm, pcm.length)) {
                        addResultCue(cues, recognizer.getResult(), lastTimeMs);
                    }
                }
                decoder.releaseOutputBuffer(outputIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }
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
        copy.spoken = cue.spoken;
        return copy;
    }

    private long lastCueEnd(List<TranscriptCue> cues) {
        return cues.isEmpty() ? 0 : cues.get(cues.size() - 1).endMs;
    }

    private byte[] downmixToMono16Bit(byte[] pcm, int channelCount) {
        if (channelCount <= 1 || pcm.length < channelCount * 2) {
            return pcm;
        }
        int frameSize = channelCount * 2;
        int frameCount = pcm.length / frameSize;
        byte[] mono = new byte[frameCount * 2];
        for (int frame = 0; frame < frameCount; frame++) {
            int sum = 0;
            int base = frame * frameSize;
            for (int channel = 0; channel < channelCount; channel++) {
                int index = base + channel * 2;
                int sample = (short) ((pcm[index] & 0xFF) | (pcm[index + 1] << 8));
                sum += sample;
            }
            short mixed = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum / channelCount));
            int out = frame * 2;
            mono[out] = (byte) (mixed & 0xFF);
            mono[out + 1] = (byte) ((mixed >> 8) & 0xFF);
        }
        return mono;
    }

    private String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

}
