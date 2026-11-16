package com.miniagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loads real Spring config data without starting the application or reading personal config. */
class DesktopCloudIdentityConfigurationTest {
    private static final String SHARED_DATABASE = "agent.auth.cloud.shared-database";
    @TempDir Path temporaryDirectory;

    @Test
    void desktopProfileOverridesTheCloudSharedDatabaseDefault() {
        try (var context = load(true, false, null)) {
            assertEquals(Boolean.FALSE, context.getEnvironment().getProperty(SHARED_DATABASE, Boolean.class));
            assertTrue(hasConfigResource(context, "application.yml"), "The real base config must be loaded");
            assertTrue(hasConfigResource(context, "application-desktop.yml"), "The real desktop profile must be merged");
        }
    }

    @Test
    void nonDesktopBaseConfigurationRetainsTheCloudSharedDatabaseMode() {
        try (var context = load(false, false, null)) {
            assertEquals(Boolean.TRUE, context.getEnvironment().getProperty(SHARED_DATABASE, Boolean.class));
            assertTrue(hasConfigResource(context, "application.yml"));
        }
    }

    @Test
    void desktopLaunchArgumentWinsOverInheritedEnvironmentAndExternalProductionConfiguration() throws Exception {
        Path external = temporaryDirectory.resolve("production-override.yml");
        Files.writeString(external, "agent:\n  auth:\n    cloud:\n      shared-database: true\n", StandardCharsets.UTF_8);

        // Prove both override sources can reproduce the problem if the launch invariant is omitted.
        try (var externalOnly = load(true, false, external);
             var environmentOnly = load(true, true, null)) {
            assertEquals(Boolean.TRUE, externalOnly.getEnvironment().getProperty(SHARED_DATABASE, Boolean.class));
            assertEquals(Boolean.TRUE, environmentOnly.getEnvironment().getProperty(SHARED_DATABASE, Boolean.class));
        }
        try (var launched = load(true, true, external,
                "--spring.profiles.active=desktop", "--agent.auth.cloud.shared-database=false")) {
            assertEquals(Boolean.FALSE, launched.getEnvironment().getProperty(SHARED_DATABASE, Boolean.class));
            assertTrue(hasConfigResource(launched, "production-override.yml"), "The external fixture must really be loaded");
        }
    }

    private static GenericApplicationContext load(boolean desktop, boolean inheritedSharedDatabase,
                                                   Path external, String... arguments) {
        StandardEnvironment environment = new StandardEnvironment();
        // Never resolve the user's MINI_AGENT_HOME/config.yml or process credentials.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("isolated-config-locations", Map.of(
                "spring.config.import", "",
                "spring.config.location", "classpath:/application.yml")));
        if (desktop) environment.setActiveProfiles("desktop");
        if (external != null) {
            environment.getPropertySources().addFirst(new MapPropertySource("external-config-location", Map.of(
                    "spring.config.additional-location", external.toUri().toString())));
        }
        if (inheritedSharedDatabase) {
            environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("inherited-launch-environment",
                    Map.of("AGENT_AUTH_CLOUD_SHARED_DATABASE", "true")));
        }
        if (arguments.length > 0) environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(arguments));
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        try {
            new ConfigDataApplicationContextInitializer().initialize(context);
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    private static boolean hasConfigResource(GenericApplicationContext context, String resourceName) {
        for (var source : context.getEnvironment().getPropertySources()) {
            if (source.getName().contains(resourceName)) return true;
        }
        return false;
    }
}
