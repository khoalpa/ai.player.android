package com.aiplayer.android;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

final class CueSpeech {
    private final TextToSpeech engine;
    private final Consumer<String> status;
    private final Runnable playbackChanged;
    private boolean ready;
    private boolean initializing = true;
    private boolean closed;
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
        this(context, status, playbackChanged, () -> {});
    }

    CueSpeech(Context context, Consumer<String> status, Runnable playbackChanged, Runnable initialized) {
        this.status = status;
        this.playbackChanged = playbackChanged;
        engine = new TextToSpeech(context, result -> main.post(() -> {
            if (closed) return;
            ready = result == TextToSpeech.SUCCESS;
            initializing = false;
            initialized.run();
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

            @Deprecated
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

    boolean hasActiveUtterance() {
        return activeUtterance != null;
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

    void update(TranscriptCue cue, String text, String language, float volume, String voiceName) {
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
        if (!selectVoice(language, voiceName)) return;
        speak(text, volume);
    }

    void preview(String text, String language, float volume, String voiceName) {
        stop();
        if (initializing) {
            status.accept("Đang khởi tạo giọng đọc. Hãy thử lại sau.");
        } else if (!supports(language) || !selectVoice(language, voiceName)) {
            status.accept("Thiết bị chưa có giọng đọc cho ngôn ngữ này.");
        } else {
            speak(text, volume);
        }
    }

    List<Voice> voices(String language) {
        List<Voice> voices = new ArrayList<>();
        if (!ready || language == null) return voices;
        Set<Voice> available = engine.getVoices();
        if (available == null) return voices;
        String code = Locale.forLanguageTag(language).getLanguage();
        for (Voice voice : available) {
            if (code.equals(voice.getLocale().getLanguage())
                    && (voice.getFeatures() == null
                    || !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) {
                voices.add(voice);
            }
        }
        voices.sort(Comparator.comparing(Voice::isNetworkConnectionRequired).thenComparing(Voice::getName));
        return voices;
    }

    Voice configuredVoice(String language, String name) {
        if (!ready) return null;
        if (!name.isEmpty()) {
            for (Voice voice : voices(language)) {
                if (name.equals(voice.getName())) return voice;
            }
        }
        Voice voice = engine.getDefaultVoice();
        return voice != null && voice.getLocale().getLanguage().equals(language) ? voice : null;
    }

    String voiceDataStatus(String language) {
        if (initializing) return "Đang kiểm tra…";
        if (!ready) return "Chưa xác định";
        List<Voice> available = voices(language);
        for (Voice voice : available) {
            if (!voice.isNetworkConnectionRequired()) return "Có giọng ngoại tuyến";
        }
        int availability = engine.isLanguageAvailable(Locale.forLanguageTag(language));
        if (availability == TextToSpeech.LANG_MISSING_DATA) return "Thiếu dữ liệu";
        if (!available.isEmpty()) return "Có giọng cần mạng";
        if (availability == TextToSpeech.LANG_NOT_SUPPORTED) return "Chưa hỗ trợ";
        return "Chưa xác định";
    }

    private boolean selectVoice(String language, String voiceName) {
        Locale locale = Locale.forLanguageTag(language);
        if (!ready || engine.setLanguage(locale) < 0) return false;
        if (voiceName == null || voiceName.isEmpty()) return true;
        for (Voice voice : voices(language)) {
            if (voiceName.equals(voice.getName())) {
                return engine.setVoice(voice) == TextToSpeech.SUCCESS || engine.setLanguage(locale) >= 0;
            }
        }
        return true;
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
        closed = true;
        ready = false;
        stop();
        engine.shutdown();
    }
}
