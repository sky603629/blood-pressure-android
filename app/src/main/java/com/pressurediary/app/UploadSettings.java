package com.pressurediary.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private configuration; upload keys are encrypted with a non-exportable Android Keystore key. */
final class UploadSettings {
    private static final String ALIAS = "pressure-diary-upload-key-v1";
    private final SharedPreferences preferences;

    static final class Configuration {
        final boolean enabled;
        final String baseUrl;
        final String token;
        final String installationId;

        Configuration(boolean enabled, String baseUrl, String token, String installationId) {
            this.enabled = enabled;
            this.baseUrl = baseUrl;
            this.token = token;
            this.installationId = installationId;
        }

        String scope() {
            return MeasurementUploadProtocol.sha256((baseUrl + "\n" + token + "\n" + installationId)
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    UploadSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("server_upload", Context.MODE_PRIVATE);
    }

    String baseUrl() { return preferences.getString("base_url", ""); }
    boolean enabled() { return preferences.getBoolean("enabled", false); }

    Configuration read() throws GeneralSecurityException {
        String token = "";
        String saved = preferences.getString("encrypted_token", "");
        if (!saved.isEmpty()) {
            try {
                String[] parts = saved.split(":", -1);
                if (parts.length != 2) throw new GeneralSecurityException("Invalid stored key");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey(false),
                        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                token = new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException error) {
                throw new GeneralSecurityException("Invalid stored key", error);
            }
        }
        String installation = preferences.getString("installation_id", "");
        return new Configuration(enabled(), baseUrl(), token, installation);
    }

    synchronized void save(String baseUrl, String token, boolean enabled) throws GeneralSecurityException {
        String base = baseUrl.trim().isEmpty() ? "" : MeasurementUploadProtocol.normalizeBaseUrl(baseUrl);
        String key = token.trim().isEmpty() ? "" : MeasurementUploadProtocol.normalizeToken(token);
        if (enabled && (base.isEmpty() || key.isEmpty())) {
            throw new IllegalArgumentException("开启上传前，请填写服务器地址和上传密钥");
        }
        String encrypted = "";
        if (!key.isEmpty()) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(true));
            encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        }
        String installation = preferences.getString("installation_id", "");
        if (installation.isEmpty()) installation = UUID.randomUUID().toString();
        if (!preferences.edit().putString("base_url", base).putString("encrypted_token", encrypted)
                .putBoolean("enabled", enabled).putString("installation_id", installation).commit()) {
            throw new GeneralSecurityException("Unable to store settings");
        }
    }

    long acknowledged(Configuration configuration) {
        return configuration.scope().equals(preferences.getString("ack_scope", ""))
                ? preferences.getLong("ack_version", 0) : 0;
    }

    boolean isCurrent(Configuration configuration) throws GeneralSecurityException {
        Configuration current = read();
        return current.enabled && current.scope().equals(configuration.scope());
    }

    void acknowledge(Configuration configuration, long version) throws GeneralSecurityException {
        if (isCurrent(configuration) && !preferences.edit().putString("ack_scope", configuration.scope())
                .putLong("ack_version", version).commit()) {
            throw new GeneralSecurityException("Unable to store sync acknowledgement");
        }
    }

    private static SecretKey secretKey(boolean create) throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (!store.containsAlias(ALIAS)) {
                if (!create) throw new GeneralSecurityException("Stored key is unavailable");
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build());
                return generator.generateKey();
            }
            return (SecretKey) store.getKey(ALIAS, null);
        } catch (java.io.IOException error) {
            throw new GeneralSecurityException("Unable to open Android Keystore", error);
        }
    }
}
