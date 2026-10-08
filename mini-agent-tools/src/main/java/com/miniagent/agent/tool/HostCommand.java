package com.miniagent.agent.tool;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 宿主外部命令的探测与执行。给"要向客户机要一个本机不一定有的程序"的工具用。
 *
 * <p><b>为什么需要这个类。</b>桌面客户端出厂后的目标机是裸 Windows，
 * 只有操作系统自带的东西。{@code npx} / {@code mvn} / {@code sh} 都可能在客户机上不存在。
 * 直接 {@code new ProcessBuilder("npx", ...).start()} 在命令缺失时抛
 * {@code IOException: Cannot run program "npx.cmd": CreateProcess error=2}，
 * 而这个异常在工具层很容易被 catch 成两种情况，两种都不好：
 * <ul>
 *   <li>catch 成"渲染失败: CreateProcess error=2" —— 客户看不懂，也不知道该装什么。</li>
 *   <li>catch 成"返回空串" —— 更糟，调用方会把空串当成"真的没有输出"，
 *       于是产出假数据（本项目 {@code AgentEnvironmentTool} 就这么错过一次：
 *       {@code sh} 不存在 → 空串 → {@code is_clean=true}，报告"工作区干净"，
 *       而实际上 git 一次都没跑）。</li>
 * </ul>
 *
 * <p><b>所以调用点的顺序必须是</b>：先 {@link #onPath} 问一句 → 不在就返回
 * <b>可操作</b>的替代方案 → 在才起进程，并且失败时把 exit code 与输出一起回上去。
 *
 * <p><b>探测通过 ≠ 一定能成功。</b>{@code npx -y <pkg>} 除了要有 Node，
 * 还要<b>联网下载</b>那个包。客户机离线时 {@code onPath("npx")} 返回 true 但依然失败。
 * 所以超时与失败处理不能因为"探测过了"就省掉。
 */
public final class HostCommand {

    private HostCommand() {
    }

    /** 探测超时。{@code where} / {@code which} 是本地查 PATH，正常在毫秒级。 */
    private static final long PROBE_TIMEOUT_SECONDS = 5;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    public static boolean isWindows() {
        return WINDOWS;
    }

    /**
     * 命令是否在 PATH 上。Windows 走 {@code where}，其余走 {@code which}。
     *
     * <p>探测本身失败（起不来 / 超时 / 被中断）一律返回 {@code false} ——
     * 宁可让调用方走降级分支，也不要赌它能跑起来。
     *
     * <p>不做缓存：PATH 在本进程生命周期内可能变（用户装了 Node），
     * 一次探测约十几毫秒，而调用它的场景（渲染图、装 Chromium）本身就要几秒到几分钟，
     * 这点开销不值得引入一个缓存过期的负面 bug。
     */
    public static boolean onPath(String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(WINDOWS ? "where" : "which", name);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            // 排空管道再判 exit code：不读的话输出积在缓冲区里，
            // 进程虽然已结束但这侧还留着未消费的数据，反复探测会累积。
            p.getInputStream().readNBytes(4096);
            return p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 一次性执行结果。刻意不用 {@code String} 兼表"失败"，因为<b>空输出是合法结果</b> ——
     * 例如 {@code git remote get-url origin} 在没配 remote 时输出就是空。
     * 用空字符串表示失败，就再也分不出这两种情况了。
     *
     * @param ok     进程正常结束且 exit code 为 0
     * @param output 合并后的 stdout + stderr（已 strip），{@code ok=false} 时是诊断文本
     * @param error  失败原因；{@code ok=true} 时为空串
     */
    public record Exec(boolean ok, String output, String error) {
    }

    /**
     * 按参数列表直接起进程，<b>不经过 shell</b>。
     *
     * <p>为什么刻意不提供"{@code sh -c <整串>}"的重载：那条路在 Windows 上
     * <b>必然失败</b>（没有 {@code sh}，除非客户机装了 Git Bash），
     * 而它想做的事（跑一个 {@code git} 子命令）用参数列表完全能做。
     * 除非真的需要管道 / 重定向 / 通配符展开，否则不要引 shell ——
     * 引了就把"客户机有没有 sh"变成一个隐性硬依赖。
     */
    public static Exec exec(Duration timeout, String... argv) {
        if (argv == null || argv.length == 0) {
            return new Exec(false, "", "空命令");
        }
        Process p = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            // 先判超时再读输出：git 这类命令的输出远小于管道缓冲区（Windows 4KB~64KB），
            // 不会因为"子进程等我们读"而死锁；反过来若先读，超时就形同虚设。
            // 真遇到超大输出（上万行 git status），子进程会阻塞 → 命中这里的超时 →
            // 被 kill 并按超时上报，而不是把调用线程挂死。
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Exec(false, "", "超时（" + timeout.toSeconds() + "s）");
            }
            String text = new String(p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            if (p.exitValue() != 0) {
                return new Exec(false, text, "exit=" + p.exitValue()
                        + (text.isEmpty() ? "" : " " + text));
            }
            return new Exec(true, text, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            return new Exec(false, "", "被中断");
        } catch (Exception e) {
            // 命令不存在时走这里：IOException: Cannot run program "git": CreateProcess error=2
            return new Exec(false, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 把 argv 拼成一行可读文本，只用于日志与错误提示，<b>不要拿去执行</b>。
     */
    public static String describe(String... argv) {
        List<String> parts = new ArrayList<>(argv.length);
        for (String a : argv) {
            parts.add(a == null ? "" : (a.contains(" ") ? "\"" + a + "\"" : a));
        }
        return String.join(" ", parts);
    }
}
