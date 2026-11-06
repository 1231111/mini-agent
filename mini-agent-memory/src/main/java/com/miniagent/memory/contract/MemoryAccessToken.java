package com.miniagent.memory.contract;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Shared authenticated-encryption format for account-issued memory credentials. */
public final class MemoryAccessToken {
    public static final String AUDIENCE = "memory-service";
    public static final String PURPOSE = "memory-access";
    public static final Duration MAX_TTL = Duration.ofHours(72);
    private static final String PREFIX = "ma1.";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Claims(long userId, long tenantId, String username, String displayName,
                         String role, String audience, String purpose, long issuedAt,
                         long expiresAt, String tokenId) {
    }

    private MemoryAccessToken() {
    }

    public static String issue(ObjectMapper json, byte[] key, long userId, long tenantId,
                               String username, String displayName, String role, Instant now) {
        validateKey(key);
        if (userId <= 0 || tenantId <= 0 || username == null || username.isBlank()
                || role == null || role.isBlank()) {
            throw new IllegalArgumentException("valid account identity is required");
        }
        Claims claims = new Claims(userId, tenantId, username, displayName, role,
                AUDIENCE, PURPOSE, now.getEpochSecond(), now.plus(MAX_TTL).getEpochSecond(),
                UUID.randomUUID().toString());
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(PREFIX.getBytes(StandardCharsets.US_ASCII));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    cipher.doFinal(json.writeValueAsBytes(claims)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot issue memory access token", e);
        }
    }

    public static Claims decrypt(ObjectMapper json, byte[] key, String token, Instant now) {
        validateKey(key);
        if (token == null || !token.startsWith(PREFIX)) {
            throw new IllegalArgumentException("invalid memory access token");
        }
        try {
            String[] parts = token.substring(PREFIX.length()).split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException("invalid memory access token");
            byte[] iv = Base64.getUrlDecoder().decode(parts[0]);
            if (iv.length != IV_BYTES) throw new IllegalArgumentException("invalid memory access token");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(PREFIX.getBytes(StandardCharsets.US_ASCII));
            Claims claims = json.readValue(cipher.doFinal(Base64.getUrlDecoder().decode(parts[1])), Claims.class);
            long seconds = now.getEpochSecond();
            if (claims.userId() <= 0 || claims.tenantId() <= 0
                    || claims.username() == null || claims.username().isBlank()
                    || claims.role() == null || claims.role().isBlank()
                    || !AUDIENCE.equals(claims.audience()) || !PURPOSE.equals(claims.purpose())
                    || claims.tokenId() == null || claims.tokenId().isBlank()
                    || claims.issuedAt() > seconds + 30 || claims.expiresAt() <= seconds
                    || claims.expiresAt() <= claims.issuedAt()
                    || claims.expiresAt() - claims.issuedAt() > MAX_TTL.toSeconds()) {
                throw new IllegalArgumentException("invalid memory access token claims");
            }
            return claims;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid memory access token", e);
        }
    }

    public static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("memory token encryption key is not configured");
        }
        try {
            byte[] key = Base64.getDecoder().decode(encoded.trim());
            validateKey(key);
            return key;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("memory token encryption key must be base64-encoded 32 bytes", e);
        }
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("AES-256 key must contain exactly 32 bytes");
        }
    }
}
