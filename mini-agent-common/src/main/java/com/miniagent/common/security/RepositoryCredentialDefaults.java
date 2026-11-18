package com.miniagent.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Identifies credentials that were historically shipped as application defaults. */
public final class RepositoryCredentialDefaults {
    private static final Set<String> SHA256_FINGERPRINTS = Set.of(
            "d742528be6c169c41578de3aba7b3deac70f585efed1e17f1c7bb63629b8661c", // database password
            "80092a92eda73dd67002cba208e9681775d2884ca0ab95375652c1bb71a463fc", // Redis password
            "091962809625f99d33fcfa7179c6d3001852118a936d526e58367e14a110bfe1", // JWT secret
            "848596bc55c278b99baeb192e29226e1130a9379904629ad2edc060602dbb158", // older JWT secret
            "233dccc15d4aa64addd68aef2d2df9ab78c044cab3bafcc611571ff2548a4184", // account internal key
            "1eb18cbe69fd83a54b7f551af28e098de5cd027f833812cbe1dea215c955db8f"  // memory token key
    );

    private RepositoryCredentialDefaults() { }

    public static boolean isKnownDefault(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().getBytes(StandardCharsets.UTF_8));
            return isKnownFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Allows configuration audits to compare fingerprints without retaining plaintext defaults. */
    public static boolean isKnownFingerprint(String fingerprint) {
        return fingerprint != null && SHA256_FINGERPRINTS.contains(fingerprint.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
