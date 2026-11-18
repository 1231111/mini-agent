package com.miniagent.config.security;

import com.miniagent.common.security.RepositoryCredentialDefaults;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionCredentialConfigurationTest {
    private static final List<String> KNOWN_DEFAULT_FINGERPRINTS = List.of(
            "d742528be6c169c41578de3aba7b3deac70f585efed1e17f1c7bb63629b8661c",
            "80092a92eda73dd67002cba208e9681775d2884ca0ab95375652c1bb71a463fc",
            "091962809625f99d33fcfa7179c6d3001852118a936d526e58367e14a110bfe1",
            "848596bc55c278b99baeb192e29226e1130a9379904629ad2edc060602dbb158",
            "233dccc15d4aa64addd68aef2d2df9ab78c044cab3bafcc611571ff2548a4184",
            "1eb18cbe69fd83a54b7f551af28e098de5cd027f833812cbe1dea215c955db8f");

    @Test
    void knownRepositoryCredentialFingerprintsAreRejectedWithoutPlaintextFixtures() {
        KNOWN_DEFAULT_FINGERPRINTS.forEach(fingerprint ->
                assertTrue(RepositoryCredentialDefaults.isKnownFingerprint(fingerprint)));
        assertFalse(RepositoryCredentialDefaults.isKnownFingerprint("e".repeat(64)));
        assertFalse(RepositoryCredentialDefaults.isKnownDefault("a-random-production-credential-that-is-long-enough"));
    }

    @Test
    void fixedDevelopmentCredentialsAreConfinedToDevProfileConfiguration() throws IOException {
        Path root = findRepositoryRoot();
        List<Path> auditedFiles = List.of(
                root.resolve("mini-agent-app/src/main/resources/application.yml"),
                root.resolve("mini-agent-app/src/main/resources/application-desktop.yml"),
                root.resolve("mini-agent-app/src/main/resources/application-dev.yml"),
                root.resolve("mini-agent-account/src/main/resources/application.yml"),
                root.resolve("mini-agent-account/src/main/resources/application-dev.yml"),
                root.resolve("mini-agent-memory-service/src/main/resources/application.yml"),
                root.resolve("mini-agent-memory-service/src/main/resources/application-dev.yml"),
                root.resolve("mini-agent-account/README.md"),
                root.resolve("mini-agent-memory-service/README.md"),
                root.resolve(".env.example"),
                root.resolve("config.example.yml"),
                root.resolve("scripts/run-app.sh"),
                root.resolve("scripts/start-account.sh"),
                root.resolve("scripts/start-memory-service.sh"));

        for (Path file : auditedFiles) {
            String source = Files.readString(file);
            assertFalse(containsKnownDefaultToken(source), () -> "A shipped credential leaked into " + file);
        }

        assertTrue(Files.exists(root.resolve("mini-agent-app/src/main/resources/application-dev.yml")));
        assertTrue(Files.exists(root.resolve("mini-agent-account/src/main/resources/application-dev.yml")));
        assertTrue(Files.exists(root.resolve("mini-agent-memory-service/src/main/resources/application-dev.yml")));
    }

    private static boolean containsKnownDefaultToken(String source) {
        for (String token : source.split("[^A-Za-z0-9+/=_-]+")) {
            if (token.length() >= 24 && RepositoryCredentialDefaults.isKnownDefault(token)) {
                return true;
            }
        }
        return false;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("mini-agent-app/src/main/resources"))
                    && Files.isDirectory(current.resolve("mini-agent-account/src/main/resources"))
                    && Files.isDirectory(current.resolve("mini-agent-memory-service/src/main/resources"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root for credential configuration test");
    }
}
