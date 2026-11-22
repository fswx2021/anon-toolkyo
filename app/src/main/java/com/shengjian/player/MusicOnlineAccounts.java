package com.shengjian.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Saves the personal music server credentials on this device. */
final class MusicOnlineAccounts {
    private static final String ALIAS = "anon_tool_navidrome_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private MusicOnlineAccounts() {}

    static synchronized MusicOnlineEngine.Config get(Context context) throws IOException {
        SharedPreferences prefs = preferences(context);
        String address = prefs.getString("address", "");
        if (address.isEmpty()) return new MusicOnlineEngine.Config("", "", "");
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(prefs.getString("password", ""), Base64.NO_WRAP);
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
            String password = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            return new MusicOnlineEngine.Config(address, prefs.getString("username", ""), password);
        } catch (GeneralSecurityException | IOException | RuntimeException unavailable) {
            throw new IOException("无法读取 Navidrome 登录信息，请重新填写并保存");
        }
    }

    static synchronized void save(Context context, MusicOnlineEngine.Config config) throws IOException {
        if (config.baseUrl().isBlank() && config.username().isBlank() && config.password().isEmpty()) {
            if (!preferences(context).edit().clear().commit()) throw new IOException("无法清除 Navidrome 登录信息");
            return;
        }
        MusicOnlineEngine.validateConfig(config);
        String address = MusicOnlineEngine.canonicalBaseUrl(config);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] encrypted = cipher.doFinal(config.password().getBytes(StandardCharsets.UTF_8));
            boolean saved = preferences(context).edit().putString("address", address)
                    .putString("username", config.username().trim())
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("password", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit();
            if (!saved) throw new IOException("无法写入 Navidrome 登录信息");
        } catch (GeneralSecurityException | RuntimeException unavailable) {
            throw new IOException("无法在设备上保存 Navidrome 登录信息");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("music_online_accounts", Context.MODE_PRIVATE);
    }

    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(ALIAS, null) instanceof SecretKey saved) return saved;
        if (!create) throw new GeneralSecurityException("Navidrome key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
