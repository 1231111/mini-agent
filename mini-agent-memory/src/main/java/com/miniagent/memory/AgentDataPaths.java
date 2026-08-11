package com.miniagent.memory;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 统一数据根：媒体 / 记忆 / skills 都相对此目录，禁止再绑 user.dir。
 *
 * 解析顺序：agent.data-dir → 环境变量 MINI_AGENT_HOME → {user.home}/.miniagent
 */
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

    /** 解析相对路径；兼容旧前缀 conversation-images/、generated-images/、user-uploads/ */
    public Path resolveMedia(String relativeOrUrl) {
        if (relativeOrUrl == null || relativeOrUrl.isBlank()) {
            throw new IllegalArgumentException("path blank");
        }
        String p = relativeOrUrl.trim().replace('\\', '/');
        if (p.startsWith("/generated-images/")) p = "generated-images/" + p.substring("/generated-images/".length());
        if (p.startsWith("generated-images/")) {
            return mediaGenerated().resolve(p.substring("generated-images/".length())).normalize();
        }
        if (p.startsWith("conversation-images/")) {
            return mediaConversations().resolve(p.substring("conversation-images/".length())).normalize();
        }
        if (p.startsWith("media/")) {
            return root.resolve(p).normalize();
        }
        if (p.startsWith("user-uploads/") || p.startsWith("./user-uploads/")) {
            String rest = p.replaceFirst("^\\.?/?user-uploads/", "");
            return mediaUploads().resolve(rest).normalize();
        }
        Path asIs = Path.of(p);
        if (asIs.isAbsolute()) return asIs.normalize();
        return root.resolve(p).normalize();
    }

    static Path resolveRoot(String dataDir) {
        if (dataDir != null && !dataDir.isBlank()) {
            return Path.of(dataDir.trim());
        }
        String env = System.getenv("MINI_AGENT_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim());
        }
        return Path.of(System.getProperty("user.home"), ".miniagent");
    }
}
