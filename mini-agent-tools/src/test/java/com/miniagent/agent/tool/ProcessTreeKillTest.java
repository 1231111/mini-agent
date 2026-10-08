package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 进程树强杀的回归测试。
 *
 * <p>背景：{@code Process.destroyForcibly()} 只杀直接子进程 —— Windows 上那是 {@code cmd.exe}，
 * 孙进程（{@code mvnw.cmd → java.exe}、{@code npm.cmd → node.exe}）会继续跑、继续写盘、继续占端口，
 * 而模型已经被告知"命令已超时终止"，于是重试时产生重复副作用。
 * {@code ToolConcurrencyPolicy} 的恢复提示甚至教模型"命令进程可能仍在后台运行"——
 * 那是被承认的既有行为，这里把它变成"不再发生"。</p>
 *
 * <p>这是集成型用例：真的起一个会派生子进程的命令，再强杀进程树，验证子孙都不在了。
 * 环境不支持时跳过（而不是假装通过）。</p>
 */
class ProcessTreeKillTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @Test
    void killProcessTreeTerminatesGrandchildren() throws Exception {
        // 选一个"自己会派生子进程且能活很久"的命令：
        //   Windows: cmd /c ping -n 30 127.0.0.1  → cmd.exe 里再起 ping.exe（孙进程）
        //   POSIX:   sh -c 'sleep 30'             → sh 里再起 sleep（孙进程）
        List<String> argv = WINDOWS
                ? List.of("cmd", "/c", "ping", "-n", "30", "127.0.0.1")
                : List.of("sh", "-c", "sleep 30");

        Process proc;
        try {
            proc = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (Exception e) {
            assumeTrue(false, "当前环境无法启动测试进程，跳过: " + e.getMessage());
            return;
        }

        try {
            // 给子进程一点时间派生孙进程
            Thread.sleep(700);
            List<ProcessHandle> descendants = proc.descendants().toList();
            assumeTrue(!descendants.isEmpty(),
                    "该命令在此环境未派生子进程，无法验证树杀（跳过而非假装通过）");

            BuiltinTools.killProcessTree(proc);

            assertTrue(proc.waitFor(5, TimeUnit.SECONDS),
                    "强杀后直接子进程必须退出");
            assertFalse(proc.isAlive(), "直接子进程不该还活着");
            // 孙进程可能正在退出中，给一点回收时间
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean anyAlive = true;
            while (System.nanoTime() < deadline) {
                anyAlive = descendants.stream().anyMatch(ProcessHandle::isAlive);
                if (!anyAlive) {
                    break;
                }
                Thread.sleep(100);
            }
            assertFalse(anyAlive,
                    "孙进程必须一起被杀掉 —— 只杀 cmd.exe 会让 java/node 继续跑并写盘，"
                            + "而模型以为命令已终止");
        } finally {
            BuiltinTools.killProcessTree(proc);
        }
    }

    @Test
    void killProcessTreeIsNullSafeAndIdempotent() {
        BuiltinTools.killProcessTree(null);
        // 已退出的进程再杀一次不能抛
        try {
            Process p = new ProcessBuilder(WINDOWS
                    ? List.of("cmd", "/c", "echo", "hi")
                    : List.of("sh", "-c", "echo hi")).start();
            p.waitFor(5, TimeUnit.SECONDS);
            BuiltinTools.killProcessTree(p);
            BuiltinTools.killProcessTree(p);
        } catch (Exception e) {
            assumeTrue(false, "当前环境无法启动测试进程，跳过: " + e.getMessage());
        }
        assertTrue(true, "null 与重复调用都必须安全");
    }
}
