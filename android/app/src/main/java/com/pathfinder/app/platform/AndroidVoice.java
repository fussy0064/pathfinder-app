package com.pathfinder.app.platform;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.pathfinder.app.core.Ports;

import java.util.Locale;

/**
 * Speaks with the phone's own text-to-speech engine (works offline).
 * A Swahili voice must be installed on the phone; configure() returns false when none is found.
 * Plays as "navigation guidance" and asks other audio (music) to lower its volume while speaking.
 */
public final class AndroidVoice implements Ports.Voice {
    private final AudioManager audio;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    private final AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build();
    private final Runnable onReady;
    private TextToSpeech tts;
    private boolean engineReady;
    private boolean available;
    private String[] wantedTags;
    private String lastText;
    private long lastAt;

    /** onReady runs (on the main thread) when the text-to-speech engine has started. */
    public AndroidVoice(Context context, Runnable onReady) {
        this.audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.onReady = onReady;
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            engineReady = status == TextToSpeech.SUCCESS;
            if (!engineReady) return;
            tts.setAudioAttributes(attributes);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) { audio.abandonAudioFocusRequest(focus); }
                @Override @SuppressWarnings("deprecation") public void onError(String utteranceId) { audio.abandonAudioFocusRequest(focus); }
            });
            if (onReady != null) onReady.run();
        });
    }

    @Override
    public boolean configure(String[] localeTags) {
        wantedTags = localeTags;
        available = false;
        if (!engineReady) return false;
        for (String tag : localeTags) {
            Locale locale = Locale.forLanguageTag(tag);
            int result = tts.isLanguageAvailable(locale);
            if (result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE
                    || result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                if (tts.setLanguage(locale) >= 0) {
                    available = true;
                    return true;
                }
            }
        }
        return false;
    }

    /** Interrupts anything being said. The same sentence within 3 seconds is ignored. */
    @Override
    public void speak(String text) {
        if (!available || text == null || text.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (text.equals(lastText) && now - lastAt < 3000) return;
        lastText = text;
        lastAt = now;
        audio.requestAudioFocus(focus);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pathfinder");
    }

    @Override
    public void stop() {
        if (engineReady) tts.stop();
        audio.abandonAudioFocusRequest(focus);
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        engineReady = false;
        available = false;
    }
}
