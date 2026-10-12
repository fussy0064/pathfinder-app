package com.pathfinder.app.platform;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps one secret (the DeepSeek API key) encrypted with an AES-GCM key that lives in the Android Keystore.
 * The key never leaves the Keystore; the encrypted text is stored in private app preferences.
 */
final class SecretStore {
    private static final String ALIAS = "pathfinder_ai_key";
    private static final String PREF = "ai_key_enc";
    private static final int IV_BYTES = 12;

    private final SharedPreferences prefs;

    SecretStore(Context context) {
        prefs = context.getSharedPreferences("pathfinder_secret", Context.MODE_PRIVATE);
    }

    boolean has() {
        return prefs.contains(PREF);
    }

    void save(String secret) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keyOrCreate());
        byte[] iv = cipher.getIV();
        byte[] enc = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(enc, 0, all, iv.length, enc.length);
        prefs.edit().putString(PREF, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
    }

    /** Returns null when nothing is saved or the stored value cannot be decrypted. */
    String read() {
        try {
            String stored = prefs.getString(PREF, null);
            if (stored == null) return null;
            byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            if (all.length <= IV_BYTES) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyOrCreate(), new GCMParameterSpec(128, Arrays.copyOfRange(all, 0, IV_BYTES)));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    void clear() {
        prefs.edit().remove(PREF).apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignored) {
            // nothing to delete
        }
    }

    private SecretKey keyOrCreate() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
