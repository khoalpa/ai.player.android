package com.aiplayer.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts developer API credentials at rest using Android Keystore AES-GCM.
 * Phone number, verification codes and 2FA password are never persisted here.
 */
final class TelegramApiCredentialStore {
    static final class Credentials {
        final int apiId;
        final String apiHash;
        Credentials(int id, String hash) { apiId = id; apiHash = hash; }
    }

    private static final String ALIAS = "ai.player.android.telegram.api.v1";
    private static final String FILE = "telegram_api_encrypted";
    private static final String ENTRY = "ciphertext";
    private final Context context;

    TelegramApiCredentialStore(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private SecretKey getKey(boolean create) throws Exception {
        KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
        keystore.load(null);
        java.security.Key key = keystore.getKey(ALIAS, null);
        if (key instanceof SecretKey) return (SecretKey) key;
        if (!create) return null;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    void save(int apiId, String apiHash) throws Exception {
        if (apiId <= 0 || apiHash == null || apiHash.trim().isEmpty())
            throw new IllegalArgumentException("Thiếu Telegram API ID/hash");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
        JSONObject data = new JSONObject().put("id", apiId).put("hash", apiHash.trim());
        byte[] payload = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = ByteBuffer.allocate(1 + iv.length + payload.length)
                .put((byte) iv.length).put(iv).put(payload).array();
        if (!preferences().edit().putString(ENTRY, Base64.encodeToString(combined, Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Không thể lưu cấu hình Telegram");
    }

    Credentials load() {
        String stored = preferences().getString(ENTRY, null);
        if (stored == null) return null;
        try {
            SecretKey key = getKey(false);
            if (key == null) throw new IllegalStateException("Không có khóa mã hóa");
            byte[] raw = Base64.decode(stored, Base64.NO_WRAP);
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            int ivLength = buffer.get() & 0xff;
            if (ivLength != 12 || buffer.remaining() <= ivLength)
                throw new IllegalStateException("Dữ liệu mã hóa không hợp lệ");
            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            JSONObject data = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
            int id = data.getInt("id");
            String hash = data.getString("hash");
            if (id <= 0 || hash.isEmpty()) throw new IllegalStateException("Cấu hình Telegram không hợp lệ");
            return new Credentials(id, hash);
        } catch (Exception unreadable) {
            // Fail closed if key is invalidated or ciphertext is damaged.
            preferences().edit().remove(ENTRY).apply();
            return null;
        }
    }

    void clear() {
        preferences().edit().remove(ENTRY).commit();
        try {
            KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
            keystore.load(null);
            if (keystore.containsAlias(ALIAS)) keystore.deleteEntry(ALIAS);
        } catch (Exception ignored) {
            // Ciphertext is already removed; unusable key cannot recover credentials.
        }
    }
}
