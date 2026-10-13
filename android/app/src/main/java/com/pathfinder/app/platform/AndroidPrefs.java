package com.pathfinder.app.platform;

import android.content.Context;
import android.content.SharedPreferences;

import com.pathfinder.app.core.Lang;
import com.pathfinder.app.core.Ports;

import java.security.GeneralSecurityException;
import java.util.Locale;

/** Settings in private SharedPreferences; the AI key in the Keystore-backed SecretStore. */
public final class AndroidPrefs implements Ports.Prefs {
    private final SharedPreferences prefs;
    private final SecretStore secret;

    public AndroidPrefs(Context context) {
        prefs = context.getSharedPreferences("pathfinder_settings", Context.MODE_PRIVATE);
        secret = new SecretStore(context);
    }

    @Override public Lang lang() {
        String saved = prefs.getString("lang", null);
        if (saved != null) return Lang.fromTag(saved);
        return Lang.fromTag(Locale.getDefault().getLanguage()); // first start: follow the phone language
    }

    @Override public boolean voiceOn() { return prefs.getBoolean("voice_on", true); }
    @Override public boolean headingUp() { return prefs.getBoolean("heading_up", true); }
    @Override public boolean aiOn() { return prefs.getBoolean("ai_on", false); } // off by default
    @Override public boolean hasApiKey() { return secret.has(); }
    @Override public String readApiKey() { return secret.read(); }

    @Override public void setLang(Lang lang) { prefs.edit().putString("lang", lang.tag).apply(); }
    @Override public void setVoiceOn(boolean on) { prefs.edit().putBoolean("voice_on", on).apply(); }
    @Override public void setHeadingUp(boolean on) { prefs.edit().putBoolean("heading_up", on).apply(); }
    @Override public void setAiOn(boolean on) { prefs.edit().putBoolean("ai_on", on).apply(); }

    @Override public void saveApiKey(String key) {
        try {
            secret.save(key);
        } catch (GeneralSecurityException | RuntimeException e) {
            secret.clear(); // do not keep a half-saved key
        }
    }

    @Override public void removeApiKey() { secret.clear(); }
}
