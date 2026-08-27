package com.miniagent.config.security;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM envelope encryption for credentials persisted by the application. */
@Service
public class SecretCryptoService {
    private static final String PREFIX = "enc:v1:";
    private static final byte[] AAD = "miniagent:model-config:v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public SecretCryptoService(
            @Value("${agent.security.model-config-encryption-key:}") String encodedKey) {
        if (StringUtils.isBlank(encodedKey)) {
            this.key = null;
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey.trim());
            if (decoded.length != 32) {
                throw new IllegalArgumentException("key must decode to exactly 32 bytes");
            }
            this.key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "MODEL_CONFIG_ENCRYPTION_KEY must be a Base64-encoded 32-byte key", e);
        }
    }

    public boolean isEnabled() {
        return key != null;
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plaintext) {
        if (StringUtils.isBlank(plaintext) || key == null) {
            return plaintext;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt model credential", e);
        }
    }

    public String decrypt(String stored) {
        if (StringUtils.isBlank(stored) || !isEncrypted(stored)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException(
                    "Encrypted model credential exists but MODEL_CONFIG_ENCRYPTION_KEY is not configured");
        }
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(stored.substring(PREFIX.length()));
            if (envelope.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("invalid envelope");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ciphertext = new byte[envelope.length - NONCE_BYTES];
            System.arraycopy(envelope, 0, nonce, 0, nonce.length);
            System.arraycopy(envelope, nonce.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to decrypt model credential; verify MODEL_CONFIG_ENCRYPTION_KEY", e);
        }
    }
}
