package com.aiplayer.android;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.function.Consumer;

final class CueSpeech {
    private final TextToSpeech engine;
    private final Consumer<String> status;
    private final Runnable playbackChanged;
    private boolean ready;
    private boolean initializing = true;
    private TranscriptCue lastCue;
    private String lastText;
    private String lastLanguage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String activeUtterance;
    private boolean speaking;
    private long utteranceSequence;

    CueSpeech(Context context, Consumer<String> status) {
        this(context, status, () -> {});
    }

    CueSpeech(Context context, Consumer<String> status, Runnable playbackChanged) {
        this.status = status;
        this.playbackChanged = playbackChanged;
        engine = new TextToSpeech(context, result -> main.post(() -> {
            ready = result == TextToSpeech.SUCCESS;
            initializing = false;
        }));
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
                main.post(() -> {
                    if (id.equals(activeUtterance)) setSpeaking(true);
                });
            }

            @Override
            public void onDone(String id) {
                finish(id, false);
            }

            @Override
            public void onError(String id) {
                finish(id, true);
            }

            @Override
            public void onError(String id, int errorCode) {
                finish(id, true);
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                finish(id, false);
            }
        });
    }

    private void finish(String id, boolean failed) {
        main.post(() -> {
            if (!id.equals(activeUtterance)) return;
            activeUtterance = null;
            setSpeaking(false);
            if (failed) status.accept("Không đọc được đoạn hiện tại.");
        });
    }

    boolean isSpeaking() {
        return speaking;
    }

    private void setSpeaking(boolean speaking) {
        if (this.speaking == speaking) return;
        this.speaking = speaking;
        playbackChanged.run();
    }

    boolean supports(String language) {
        return ready && language != null && !"auto".equals(language)
                && engine.isLanguageAvailable(Locale.forLanguageTag(language)) >= 0;
    }

    void update(TranscriptCue cue, String text, String language, float volume) {
        if (activeUtterance != null && (cue != lastCue || !lastText.equals(text)
                || !lastLanguage.equals(language))) {
            stop();
        }
        if (cue == null || text == null || text.isEmpty() || language == null || "auto".equals(language)) {
            return;
        }
        if (!ready || activeUtterance != null
                || (cue == lastCue && text.equals(lastText) && language.equals(lastLanguage))) return;
        lastCue = cue;
        lastText = text;
        lastLanguage = language;
        if (engine.setLanguage(Locale.forLanguageTag(language)) < 0) return;
        speak(text, volume);
    }

    void preview(String text, String language, float volume) {
        stop();
        if (initializing) {
            status.accept("Đang khởi tạo giọng đọc. Hãy thử lại sau.");
        } else if (!supports(language) || engine.setLanguage(Locale.forLanguageTag(language)) < 0) {
            status.accept("Thiết bị chưa có giọng đọc tiếng Việt.");
        } else {
            speak(text, volume);
        }
    }

    private void speak(String text, float volume) {
        Bundle parameters = new Bundle();
        parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        activeUtterance = "cue-" + ++utteranceSequence;
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, parameters, activeUtterance)
                == TextToSpeech.ERROR) {
            activeUtterance = null;
            status.accept("Không đọc được đoạn hiện tại.");
        }
    }

    void stop() {
        boolean hadUtterance = activeUtterance != null;
        activeUtterance = null;
        if (hadUtterance) engine.stop();
        setSpeaking(false);
        lastCue = null;
    }

    void close() {
        stop();
        engine.shutdown();
    }
}
