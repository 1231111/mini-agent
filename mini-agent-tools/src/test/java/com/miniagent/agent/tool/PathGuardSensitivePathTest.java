package com.miniagent.agent.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;

class PathGuardSensitivePathTest {
    @TempDir
    Path workspace;

    @BeforeEach
    void setWorkspaceRoot() {
        PathGuard.clearExtraRoots();
        WorkspaceContext.setRootOverride(workspace);
    }

    @AfterEach
    void clearWorkspaceRoot() {
        WorkspaceContext.clearAll();
        PathGuard.clearExtraRoots();
    }

    @Test
    void permitsOrdinarySourceInsideWorkspace() throws Exception {
        Path source = workspace.resolve("src/main.js");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "export const value = 1;\n");

        assertThat(PathGuard.isAllowed(source)).isTrue();
        PathGuard.assertAllowed(source, "read");
    }

    @Test
    void deniesExplicitCredentialStoresPrivateKeysAndCredentialFilesCaseInsensitively() {
        List<String> blocked = List.of(
                ".env", ".ENV.production", ".env.local.backup",
                ".ssh/id_rsa", ".AWS/credentials", ".Azure/profile.json",
                ".KUBE/config", ".gnupg/private-keys-v1.d/key", ".docker/config.json",
                ".config/gcloud/application_default_credentials.json",
                "keys/id_ed25519", "certs/signing.PEM", "certs/signing.key",
                "certs/client.p12", "certs/keystore.jks", "app.credentials.json",
                "session.token", "deployment.secret", "service-account-prod.json",
                "access_token", "refresh_token.json");

        for (String relative : blocked) {
            Path candidate = workspace.resolve(relative);
            assertThat(PathGuard.isAllowed(candidate))
                    .as("sensitive path should be denied: %s", relative)
                    .isFalse();
            assertThatThrownBy(() -> PathGuard.assertAllowed(candidate, "read"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("敏感凭证或密钥路径");
        }
    }

    @Test
    void permitsCredentialNamedSourcePackagesTemplatesAndPublicCertificates() throws Exception {
        List<String> ordinaryFiles = List.of(
                "src/main/java/SecretsService.java", "src/main/java/CredentialProvider.java",
                "src/main/java/credentials/AuthService.java", "src/secrets/token/TokenService.java",
                "src/service-account-client.ts", "src/private_key_codec.py",
                ".env.example", ".ENV.production.TEMPLATE", ".env.local.sample", ".env.dist",
                ".git-credentials.example", ".npmrc.template", ".envoy.yaml", ".gitconfig",
                "certs/server.CRT", "certs/root.cer", "certs/chain.p7b", "certs/request.p10");

        for (String relative : ordinaryFiles) {
            Path candidate = workspace.resolve(relative);
            Files.createDirectories(candidate.getParent());
            Files.writeString(candidate, "non-sensitive test fixture\n");
            assertThat(PathGuard.isAllowed(candidate))
                    .as("ordinary source/template/public certificate should be usable: %s", relative)
                    .isTrue();
            PathGuard.assertAllowed(candidate, "read");
        }

        for (String directory : List.of("credentials", "secrets", "token", "config.key")) {
            Path ordinaryDirectory = Files.createDirectories(workspace.resolve(directory));
            assertThat(PathGuard.isAllowed(ordinaryDirectory))
                    .as("business directory must not be classified as a credential file: %s", directory)
                    .isTrue();
        }
    }

    @Test
    void environmentAndCredentialRulesMatchBoundariesRatherThanSubstrings() throws Exception {
        for (String relative : List.of("docs/secrets-guide.md", "schema/credentials-schema.json",
                "business-credentials/README.md", ".config/gcloud-docs/setup.md", "src/.env-loader.js")) {
            Path ordinary = workspace.resolve(relative);
            Files.createDirectories(ordinary.getParent());
            Files.writeString(ordinary, "non-sensitive test fixture\n");
            assertThat(PathGuard.isAllowed(ordinary)).as(relative).isTrue();
        }
        assertThat(PathGuard.isAllowed(workspace.resolve(".env.production.backup"))).isFalse();
        assertThat(PathGuard.isAllowed(workspace.resolve("config/app.credentials.json"))).isFalse();
        assertThat(PathGuard.isAllowed(workspace.resolve(".config/gcloud/application_default_credentials.json"))).isFalse();
    }

    @Test
    void explicitAdditionalReadRootDoesNotOverrideCredentialDenyRules() throws Exception {
        Path extraRoot = workspace.resolveSibling("operator-approved-root");
        Files.createDirectories(extraRoot);
        Path ordinary = extraRoot.resolve("Main.java");
        Path secret = extraRoot.resolve("service-account-prod.json");
        Files.writeString(ordinary, "class Main {}\n");
        Files.writeString(secret, "credential-placeholder\n");
        PathGuard.allowRoots(extraRoot.toString());

        assertThat(PathGuard.isAllowed(ordinary)).isTrue();
        assertThat(PathGuard.isAllowed(secret)).isFalse();
    }

    @Test
    void checksBothLexicalAliasAndResolvedSymlinkTarget() throws Exception {
        Path secret = workspace.resolve(".env.production");
        Files.writeString(secret, "credential-placeholder\n");
        Path ordinaryAlias = workspace.resolve("source-copy.java");
        Path ordinaryDirectory = workspace.resolve("ordinary-config");
        Files.createDirectories(ordinaryDirectory);

        try {
            Files.createSymbolicLink(ordinaryAlias, secret);
            Files.createSymbolicLink(workspace.resolve(".aws"), ordinaryDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            abort("Symbolic links are unavailable in this test environment: " + unavailable.getClass().getSimpleName());
        }

        // The alias name is ordinary, but the actual target is a secret file.
        assertThat(PathGuard.isAllowed(ordinaryAlias)).isFalse();
        // The target is ordinary, but the lexical path enters a credential store.
        assertThat(PathGuard.isAllowed(workspace.resolve(".aws/config.json"))).isFalse();
    }
}
