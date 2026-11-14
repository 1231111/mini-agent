package com.miniagent.config.security;

import com.miniagent.config.entity.UserModelConfig;
import com.miniagent.config.repository.UserModelConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Existing startup migration consumes the same 32-byte Base64 key provisioned by the desktop shell. */
class ModelConfigSecretMigratorTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private static SecretCryptoService crypto(String key) {
        SecretCryptoService service = new SecretCryptoService();
        ReflectionTestUtils.setField(service, "encodedKey", key);
        ReflectionTestUtils.invokeMethod(service, "init");
        return service;
    }

    private static UserModelConfig config(long owner, String stored) {
        UserModelConfig row = new UserModelConfig();
        row.setUserId(owner);
        row.setPresetId("default");
        row.setCustomApiKey(stored);
        return row;
    }

    private static void migrate(SecretCryptoService crypto, UserModelConfig... rows) {
        UserModelConfigRepository repository = mock(UserModelConfigRepository.class);
        when(repository.findAll()).thenReturn(List.of(rows));
        ModelConfigSecretMigrator migrator = new ModelConfigSecretMigrator();
        ReflectionTestUtils.setField(migrator, "repository", repository);
        ReflectionTestUtils.setField(migrator, "crypto", crypto);
        migrator.run(new DefaultApplicationArguments());
    }

    @Test void aProvisionedDesktopKeyEnablesLegacyPlaintextMigrationWithoutChangingDestinationBinding() {
        SecretCryptoService crypto = crypto(KEY);
        UserModelConfig bound = config(1, "legacy-test-key");
        bound.setCustomApiKeyBaseUrl("https://model.example/v1");
        UserModelConfig unbound = config(2, "unbound-legacy-test-key");

        migrate(crypto, bound, unbound);

        assertTrue(crypto.isEncrypted(bound.getCustomApiKey()));
        assertEquals("legacy-test-key", crypto.decrypt(bound.getCustomApiKey()));
        assertEquals("https://model.example/v1", bound.getCustomApiKeyBaseUrl());
        assertTrue(crypto.isEncrypted(unbound.getCustomApiKey()));
        assertNull(unbound.getCustomApiKeyBaseUrl(), "migration must not guess a missing credential destination");
        assertEquals("unbound-legacy-test-key", crypto.decrypt(unbound.getCustomApiKey()));
    }

    @Test void aRestartValidatesCiphertextWithoutRotatingItOrEncryptingTwice() {
        SecretCryptoService crypto = crypto(KEY);
        UserModelConfig row = config(1, "legacy-test-key");
        migrate(crypto, row);
        String encrypted = row.getCustomApiKey();

        migrate(crypto(KEY), row);

        assertEquals(encrypted, row.getCustomApiKey());
        assertEquals("legacy-test-key", crypto(KEY).decrypt(row.getCustomApiKey()));
    }

    @Test void unreadableCiphertextIsPreservedWhileOtherRowsCanMigrateAndBeRepairedThroughSettings() {
        String oldCiphertext = crypto(OTHER_KEY).encrypt("old-test-key");
        UserModelConfig broken = config(1, oldCiphertext);
        UserModelConfig plaintext = config(2, "legacy-test-key");
        SecretCryptoService current = crypto(KEY);

        assertDoesNotThrow(() -> migrate(current, broken, plaintext));

        assertEquals(oldCiphertext, broken.getCustomApiKey(), "unknown credentials must never be cleared or encrypted again");
        assertThrows(IllegalStateException.class, () -> current.decrypt(broken.getCustomApiKey()));
        assertTrue(current.isEncrypted(plaintext.getCustomApiKey()));
        assertEquals("legacy-test-key", current.decrypt(plaintext.getCustomApiKey()));
    }

    @Test void blankCredentialsAreNotChangedAndDevelopmentWithoutAKeyKeepsExistingBehavior() {
        UserModelConfig missing = config(1, null);
        UserModelConfig blank = config(2, "");
        UserModelConfig plaintext = config(3, "legacy-test-key");

        migrate(crypto(KEY), missing, blank);
        migrate(crypto(""), plaintext);

        assertNull(missing.getCustomApiKey());
        assertEquals("", blank.getCustomApiKey());
        assertEquals("legacy-test-key", plaintext.getCustomApiKey());
    }
}
