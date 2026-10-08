package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子进程环境沙箱的回归测试。
 *
 * <p>背景：{@code exec_command} 是原始 shell，MCP 服务是模型的工具后端，
 * 两者此前都继承 agent 的**完整**环境（含模型 key、搜索 key、数据库口令）。
 * 这些用例锁住"默认不给、显式才给"这条不变式，避免以后有人为了修某个 CLI
 * 又把 {@code putAll(System.getenv())} 加回来。</p>
 */
class ProcessEnvTest {

    @Test
    void sanitizeDropsUnknownVarsButKeepsPath() {
        ProcessBuilder pb = new ProcessBuilder("noop");
        pb.environment().put("MINIAGENT_TEST_SECRET", "super-secret-value");
        pb.environment().put("PATH", pb.environment().getOrDefault("PATH", "/usr/bin"));

        int dropped = ProcessEnv.sanitize(pb);

        assertFalse(pb.environment().containsKey("MINIAGENT_TEST_SECRET"),
                "未在白名单里的变量必须被丢弃，否则密钥会随子进程外流");
        assertTrue(pb.environment().containsKey("PATH"),
                "PATH 必须保留，否则子进程找不到可执行文件");
        assertTrue(dropped >= 1, "至少要报出被丢弃的变量数，便于审计");
    }

    @Test
    void sanitizeIsCaseInsensitiveOnKeyNames() {
        ProcessBuilder pb = new ProcessBuilder("noop");
        // Windows 上环境变量名不区分大小写，白名单比较也必须归一化
        pb.environment().put("Path", "C:\\Windows");
        ProcessEnv.sanitize(pb);
        assertTrue(pb.environment().containsKey("Path"),
                "大小写不同的 PATH 变体同样要保留");
    }

    @Test
    void allowKeysAdmitsExplicitPassthroughOnly() {
        ProcessEnv.allowKeys("MINIAGENT_TEST_TOKEN, another_key");
        ProcessBuilder pb = new ProcessBuilder("noop");
        pb.environment().put("MINIAGENT_TEST_TOKEN", "t");
        pb.environment().put("ANOTHER_KEY", "a");
        pb.environment().put("MINIAGENT_TEST_NOT_LISTED", "n");

        ProcessEnv.sanitize(pb);

        assertTrue(pb.environment().containsKey("MINIAGENT_TEST_TOKEN"),
                "显式放行的变量要保留");
        assertTrue(pb.environment().containsKey("ANOTHER_KEY"),
                "放行清单大小写不敏感");
        assertFalse(pb.environment().containsKey("MINIAGENT_TEST_NOT_LISTED"),
                "没在放行清单里的变量仍然丢弃");
    }

    @Test
    void putDeclaredMergesAfterSanitize() {
        ProcessBuilder pb = new ProcessBuilder("noop");
        pb.environment().put("MINIAGENT_TEST_SECRET", "should-be-dropped");
        ProcessEnv.sanitize(pb);

        ProcessEnv.putDeclared(pb, Map.of("DECLARED_TOKEN", "value"));

        assertFalse(pb.environment().containsKey("MINIAGENT_TEST_SECRET"));
        assertEquals("value", pb.environment().get("DECLARED_TOKEN"),
                "调用方显式声明的变量（如 MCP server 配置的 env）要在收敛之后合并");
    }
}
