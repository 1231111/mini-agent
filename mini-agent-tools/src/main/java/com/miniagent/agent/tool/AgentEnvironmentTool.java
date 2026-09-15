package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.AgentEnvironmentParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 环境感知工具：动态获取运行时环境信息
 */
@Slf4j
@Component
public class AgentEnvironmentTool {

    @Autowired
    private ToolRegistry toolRegistry;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostConstruct
    public void register() {
        // 使用新的实体类注册方式
        toolRegistry.register(
                "agent_environment",
                "获取运行时环境信息，包括操作系统、Java版本、项目目录、Git状态等",
                AgentEnvironmentParams.class,
                this::handle
        );
    }

    private String handle(AgentEnvironmentParams params) {
        try {
            Map<String, Object> env = new LinkedHashMap<>();

            // 基础环境信息
            env.put("timestamp", Instant.now().toString());
            env.put("os_name", System.getProperty("os.name"));
            env.put("os_version", System.getProperty("os.version"));
            env.put("os_arch", System.getProperty("os.arch"));
            env.put("java_version", System.getProperty("java.version"));
            env.put("java_vendor", System.getProperty("java.vendor"));
            env.put("user_dir", System.getProperty("user.dir"));
            env.put("user_home", System.getProperty("user.home"));
            env.put("username", System.getProperty("user.name"));

            // JVM信息
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            env.put("jvm_name", runtime.getVmName());
            env.put("jvm_version", runtime.getVmVersion());
            env.put("jvm_uptime_ms", runtime.getUptime());

            // 内存信息
            Runtime runtimeMem = Runtime.getRuntime();
            env.put("available_processors", runtimeMem.availableProcessors());
            env.put("free_memory_mb", runtimeMem.freeMemory() / (1024 * 1024));
            env.put("max_memory_mb", runtimeMem.maxMemory() / (1024 * 1024));

            // 项目目录信息
            Path userDir = Paths.get(System.getProperty("user.dir"));
            env.put("project_root", userDir.toString());

            // Git信息（可选）
            if (params.isIncludeGitOrDefault()) {
                env.put("git", getGitInfo());
            }

            return MAPPER.writeValueAsString(env);
        } catch (Exception e) {
            log.error("获取环境信息失败", e);
            return "{\"error\":\"获取环境信息失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取Git仓库信息
     */
    private Map<String, Object> getGitInfo() {
        Map<String, Object> gitInfo = new LinkedHashMap<>();

        try {
            // 获取当前分支
            String branch = executeCommand("git rev-parse --abbrev-ref HEAD");
            gitInfo.put("branch", branch);

            // 获取最后提交信息
            String lastCommit = executeCommand("git log -1 --format=%H %s");
            String[] parts = lastCommit.split(" ", 2);
            if (parts.length >= 2) {
                gitInfo.put("last_commit_hash", parts[0]);
                gitInfo.put("last_commit_message", parts[1]);
            }

            // 获取工作区状态
            String status = executeCommand("git status --porcelain");
            gitInfo.put("is_clean", status.isEmpty());
            gitInfo.put("modified_files_count", status.lines().count());

            // 获取远程仓库
            String remote = executeCommand("git remote get-url origin");
            if (!remote.isEmpty()) {
                gitInfo.put("remote_url", remote);
            }
        } catch (Exception e) {
            gitInfo.put("error", "获取Git信息失败: " + e.getMessage());
            log.warn("获取Git信息失败", e);
        }

        return gitInfo;
    }

    /**
     * 执行shell命令并返回输出
     */
    private String executeCommand(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return "";
            }

            return output.toString().trim();
        } catch (Exception e) {
            log.debug("执行命令失败: {}", command, e);
            return "";
        }
    }
}