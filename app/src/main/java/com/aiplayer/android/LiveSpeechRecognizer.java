package com.aiplayer.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LiveSpeechRecognizer {
    interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onStatus(String status);
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final VoskModelStore modelStore;
    private AudioRecord recorder;
    private Model model;
    private boolean running;
    private static final int SAMPLE_RATE = 16000;

    LiveSpeechRecognizer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.modelStore = new VoskModelStore(context);
    }

    void start(String sourceLanguage) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running = false;
            listener.onStatus("Chưa được cấp quyền micro.");
            return;
        }
        running = true;
        modelStore.prepare(sourceLanguage, new VoskModelStore.Callback() {
            @Override
            public void onReady(Model loadedModel) {
                if (running) {
                    model = loadedModel;
                    executor.execute(LiveSpeechRecognizer.this::recognizeLoop);
                }
            }

            @Override
            public void onError(String message) {
                running = false;
                listener.onStatus(message);
            }

            @Override
            public void onStatus(String message) {
                listener.onStatus(message);
            }
        });
    }

    void stop() {
        running = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            recorder.release();
            recorder = null;
        }
        listener.onStatus("Đã dừng nghe micro.");
    }

    boolean isRunning() {
        return running;
    }

    void close() {
        stop();
        modelStore.close();
        executor.shutdownNow();
    }

    private void recognizeLoop() {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufferSize = Math.max(minBuffer, SAMPLE_RATE);
        Recognizer recognizer = null;
        try {
            recognizer = new Recognizer(model, SAMPLE_RATE);
            try {
                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                );
                recorder.startRecording();
            } catch (SecurityException ex) {
                running = false;
                listener.onStatus("Chưa được cấp quyền micro.");
                return;
            }
            byte[] buffer = new byte[bufferSize];
            listener.onStatus("Đang nghe micro bằng Vosk offline.");
            while (running) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                if (recognizer.acceptWaveForm(buffer, read)) {
                    String text = textFromJson(recognizer.getResult(), "text");
                    if (!text.isEmpty()) {
                        listener.onFinal(text);
                    }
                } else {
                    String partial = textFromJson(recognizer.getPartialResult(), "partial");
                    if (!partial.isEmpty()) {
                        listener.onPartial(partial);
                    }
                }
            }
            String finalText = textFromJson(recognizer.getFinalResult(), "text");
            if (!finalText.isEmpty()) {
                listener.onFinal(finalText);
            }
        } catch (Exception ex) {
            listener.onStatus("Không nghe được micro bằng Vosk: " + ex.getMessage());
        } finally {
            if (recognizer != null) {
                recognizer.close();
            }
            if (model != null) {
                model.close();
                model = null;
            }
        }
    }

    private String textFromJson(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (Exception ex) {
            return "";
        }
    }

}
