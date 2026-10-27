package com.miniagent.common.io;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared data-root paths for media, memory, skills, and workspace storage. */
@Component
public class AgentDataPaths {
    private static final Logger log = LoggerFactory.getLogger(AgentDataPaths.class);
    private final Path root;

    public AgentDataPaths(@Value("${agent.data-dir:}") String dataDir) {
        this.root = resolveRoot(dataDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(memory());
            Files.createDirectories(mediaGenerated());
            Files.createDirectories(mediaConversations());
            Files.createDirectories(mediaUploads());
            Files.createDirectories(skills());
            Files.createDirectories(workspace());
            System.setProperty("miniagent.data.dir", root.toString());
            log.info("Agent 数据根: {}", root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录: " + root, e);
        }
    }

    public Path root() { return root; }
    public Path memory() { return root.resolve("memory"); }
    public Path mediaGenerated() { return root.resolve("media").resolve("generated"); }
    public Path mediaConversations() { return root.resolve("media").resolve("conversations"); }
    public Path mediaUploads() { return root.resolve("media").resolve("uploads"); }
    public Path skills() { return root.resolve("skills"); }
    public Path workspace() { return root.resolve("workspace"); }

    public Path resolveMedia(String relativeOrUrl) {
        if (StringUtils.isBlank(relativeOrUrl)) {
            throw new IllegalArgumentException("path blank");
        }
        String path = relativeOrUrl.trim().replace('\\', '/');
        if (path.startsWith("/api/generated-media/")) {
            return mediaGenerated().resolve(path.substring("/api/generated-media/".length())).normalize();
        }
        if (path.startsWith("/generated-images/")) {
            path = "generated-images/" + path.substring("/generated-images/".length());
        }
        if (path.startsWith("generated-images/")) {
            return mediaGenerated().resolve(path.substring("generated-images/".length())).normalize();
        }
        if (path.startsWith("conversation-images/")) {
            return mediaConversations().resolve(path.substring("conversation-images/".length())).normalize();
        }
        if (path.startsWith("media/")) {
            return root.resolve(path).normalize();
        }
        if (path.startsWith("user-uploads/") || path.startsWith("./user-uploads/")) {
            return mediaUploads().resolve(path.replaceFirst("^\\.?/?user-uploads/", "")).normalize();
        }
        Path asIs = Path.of(path);
        return asIs.isAbsolute() ? asIs.normalize() : root.resolve(path).normalize();
    }

    static Path resolveRoot(String dataDir) {
        if (StringUtils.isNotBlank(dataDir)) {
            return Path.of(dataDir.trim());
        }
        String env = System.getenv("MINI_AGENT_HOME");
        if (StringUtils.isNotBlank(env)) {
            return Path.of(env.trim());
        }
        return Path.of(System.getProperty("user.home"), ".miniagent");
    }
}
