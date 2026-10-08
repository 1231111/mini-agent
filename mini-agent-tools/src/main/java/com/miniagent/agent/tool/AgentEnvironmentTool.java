package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.AgentEnvironmentParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 环境感知工具：动态获取运行时环境信息
 */
@Slf4j
@Component
public class AgentEnvironmentTool {

    @Autowired
    private ToolRegistry toolRegistry;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 单条 git 命令的超时。git 是本地命令，正常在毫秒级；
     * 给 5 秒是留给大仓库的 {@code status}，不是留给网络。
     */
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(5);

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
     * 获取 Git 仓库信息。
     *
     * <p>原实现的问题（2026-09-28 修）：
     * <ol>
     *   <li>命令走 {@code sh -c <整串>}，而 Windows 没有 {@code sh} —— 客户机上必然失败。
     *       改为 {@link HostCommand#exec} 的参数列表形式，不引 shell。</li>
     *   <li>执行失败被吞成空串，调用方拿空串当"真的没输出"：
     *       {@code status.isEmpty()} → {@code is_clean=true}，于是向用户报告
     *       "工作区干净"，而实际上 git 一次都没跑过。<b>假数据比报错危害大</b>。
     *       现在改为显式回报 {@code available=false} + 失败原因。</li>
     *   <li>{@code git status --porcelain} 是多行输出，原实现逐行 {@code append} 而不加
     *       {@code \n}，多行被拼成一行 → {@code lines().count()} 恒为 1，
     *       {@code modified_files_count} 一直是错的。{@link HostCommand.Exec#output()}
     *       保留了换行，这里按行统计。</li>
     *   <li>可用性探测不能用 {@code rev-parse --abbrev-ref HEAD} —— 它在"刚 init、
     *       零提交"的仓库上 exit 128，会把正常空仓库误报成"git 不可用"。
     *       改用 {@code rev-parse --is-inside-work-tree}。</li>
     * </ol>
     */
    private Map<String, Object> getGitInfo() {
        Map<String, Object> gitInfo = new LinkedHashMap<>();

        // 可用性探测刻意用 --is-inside-work-tree 而不是 --abbrev-ref HEAD：
        // 后者在"刚 git init、还一次都没提交"的仓库上会 exit 128
        //   fatal: ambiguous argument 'HEAD': unknown revision
        // 于是把一个正常的空仓库误报成"git 不可用"。--is-inside-work-tree
        // 在任何仓库里都输出 true 并 exit 0，不在仓库里才 exit 128 —— 语义正好。
        HostCommand.Exec probe = HostCommand.exec(GIT_TIMEOUT, "git", "rev-parse", "--is-inside-work-tree");
        if (!probe.ok() || !"true".equalsIgnoreCase(probe.output())) {
            gitInfo.put("available", false);
            gitInfo.put("error", probe.ok() ? "git rev-parse --is-inside-work-tree 返回 " + probe.output()
                    : probe.error());
            log.debug("git 不可用: {}", gitInfo.get("error"));
            return gitInfo;
        }
        gitInfo.put("available", true);

        // 分支单独取，且给一条回退链：
        //   symbolic-ref --short HEAD  在空仓库上可用（HEAD 指向不存在的分支也能读），
        //                              但 detached HEAD 时会失败；
        //   rev-parse --abbrev-ref HEAD 正常仓库可用，但空仓库失败、detached 时给 "HEAD"。
        // 两条都失败就不写 branch —— 不写比写个错的好。
        HostCommand.Exec sym = HostCommand.exec(GIT_TIMEOUT, "git", "symbolic-ref", "--short", "HEAD");
        if (sym.ok() && !sym.output().isEmpty()) {
            gitInfo.put("branch", sym.output());
        } else {
            HostCommand.Exec abbrev = HostCommand.exec(GIT_TIMEOUT,
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
            if (abbrev.ok() && !abbrev.output().isEmpty()) {
                gitInfo.put("branch", abbrev.output());
            }
        }

        HostCommand.Exec lastCommit = HostCommand.exec(GIT_TIMEOUT,
                "git", "log", "-1", "--format=%H %s");
        if (lastCommit.ok()) {
            String[] parts = lastCommit.output().split(" ", 2);
            if (parts.length >= 2) {
                gitInfo.put("last_commit_hash", parts[0]);
                gitInfo.put("last_commit_message", parts[1]);
            }
        }

        HostCommand.Exec status = HostCommand.exec(GIT_TIMEOUT, "git", "status", "--porcelain");
        if (status.ok()) {
            String out = status.output();
            long changed = out.isEmpty() ? 0L
                    : out.lines().filter(line -> !line.isBlank()).count();
            gitInfo.put("is_clean", changed == 0L);
            gitInfo.put("modified_files_count", changed);
        }

        // 没配 origin 时 git 会以非 0 退出并往 stderr 吐 "No such remote"。
        // 原实现把 stderr 并进 stdout，于是那句错误文本被当成 remote_url 填了进去。
        // 现在靠 exit code 区分：只有真正成功且非空才记。
        HostCommand.Exec remote = HostCommand.exec(GIT_TIMEOUT, "git", "remote", "get-url", "origin");
        if (remote.ok() && !remote.output().isEmpty()) {
            gitInfo.put("remote_url", remote.output());
        }

        return gitInfo;
    }
}
