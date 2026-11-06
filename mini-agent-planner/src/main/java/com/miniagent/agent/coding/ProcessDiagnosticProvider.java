package com.miniagent.agent.coding;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于「起一个子进程跑编译器」的检查器基类。
 *
 * <h2>为什么走子进程而不是 {@code javax.tools.JavaCompiler}</h2>
 *
 * <p>桌面端发的是 jlink 出来的精简 JRE，{@code jdk.compiler} 模块大概率不在里面。
 * 直接 {@code ToolProvider.getSystemJavaCompiler()} 拿到 null 会变成「编译期没问题、运行期静默失效」。
 * 起子进程则由 {@link #available()} 显式探测，探不到就整体跳过，行为可观测。
 *
 * <h2>为什么输出重定向到临时文件</h2>
 *
 * <p>{@code ProcessBuilder} 用 {@code Redirect.PIPE} 时，子进程输出超过管道缓冲区（Linux 64KB）
 * 就会阻塞，而父进程在 {@code waitFor} 上等 —— 互相等到超时。
 * 重定向到临时文件没有这个上限，超时后照样能读到已经写下的部分。</p>
 */
@Slf4j
abstract class ProcessDiagnosticProvider implements DiagnosticProvider {

    /** 单次检查最多回传多少条：200 行报错对模型是噪声，不是信息。 */
    static final int MAX_DIAGNOSTICS = 20;

    /** 读回输出时最多读这么多字节，防止某个检查器刷屏把内存吃掉。 */
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    /**
     * 依赖缺失类报错的识别片段。
     *
     * <p>单文件编译没有 classpath，「找不到 Spring 的类」是<b>必然</b>结果而不是代码错误。
     * 不过滤的话，改一个依赖框架的文件会得到两百行 {@code cannot find symbol}，
     * 比不给反馈更糟 —— 模型会把注意力花在这些噪声上。</p>
     *
     * <p>代价要说清楚：真正的「用了未声明的变量」也会被归到这类里被滤掉。
     * 所以注入给模型的文案必须写明「语法级检查」，不能让模型以为这是完整的类型检查。</p>
     */
    private static final List<String> DEPENDENCY_NOISE = List.of(
            "cannot find symbol",
            "does not exist",
            "cannot access",
            "class file for",
            "package .* does not exist");

    @Override
    public List<Diagnostic> check(Path file, Duration budget) {
        if (!available() || budget.isNegative() || budget.isZero()) {
            return List.of();
        }
        Path out = null;
        Path scratch = null;
        try {
            out = Files.createTempFile("miniagent-diag-", ".out");
            // 编译产物必须落在临时目录：javac 不带 -d 会把 .class 写在源码旁边，
            // 那等于 agent 每检查一次就往用户项目里丢垃圾。
            scratch = Files.createTempDirectory("miniagent-diag-classes-");
            Process process = new ProcessBuilder(command(file, scratch))
                    .redirectErrorStream(true)
                    .redirectOutput(out.toFile())
                    .directory(workDir(file).toFile())
                    .start();
            boolean finished = process.waitFor(budget.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("诊断超时，跳过: {}（provider={}）", file, id());
                return List.of();
            }
            String raw = readCapped(out);
            return parse(file, raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            // 检查器本身出错不能影响主流程：诊断是增益，不是前置条件。
            log.debug("诊断执行失败: {}（provider={}）", file, id(), e);
            return List.of();
        } finally {
            deleteQuietly(out);
            deleteRecursively(scratch);
        }
    }

    /** 完整的命令行。语言相关的过滤逻辑留在 {@link #parse} 里，命令行尽量朴素。 */
    protected abstract List<String> command(Path file, Path scratchDir);

    /** 子进程的工作目录。默认用文件所在目录，让相对路径的报错更容易对上。 */
    protected Path workDir(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        return parent == null ? file.toAbsolutePath() : parent;
    }

    /** 把检查器原始输出解析成诊断列表；实现方负责过滤 {@link #isDependencyNoise}。 */
    protected abstract List<Diagnostic> parse(Path file, String rawOutput);

    /** 这条报错是不是「缺依赖」而不是「代码写错」。 */
    protected static boolean isDependencyNoise(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        for (String marker : DEPENDENCY_NOISE) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 从 {@code Foo.java:12: error: msg} 里拆出行号；拆不出来给 0。 */
    protected static int parseLineNo(String s) {
        if (s == null) {
            return 0;
        }
        int colon = s.indexOf(':');
        if (colon <= 0) {
            return 0;
        }
        int end = colon + 1;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == colon + 1) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(colon + 1, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 截断到 {@link #MAX_DIAGNOSTICS} 条，并在末尾说明还有多少没展示。 */
    protected static List<Diagnostic> cap(List<Diagnostic> all) {
        if (all.size() <= MAX_DIAGNOSTICS) {
            return all;
        }
        List<Diagnostic> kept = new ArrayList<>(all.subList(0, MAX_DIAGNOSTICS));
        kept.add(new Diagnostic(null, 0, "Info",
                "另有 " + (all.size() - MAX_DIAGNOSTICS) + " 条诊断未展示（只保留前 "
                        + MAX_DIAGNOSTICS + " 条）"));
        return kept;
    }

    private static String readCapped(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[MAX_OUTPUT_BYTES];
            int n = in.read(buf);
            if (n <= 0) {
                return "";
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响任何判定，只是留下垃圾。
        }
    }

    /** 递归删掉本次检查产生的编译产物目录。 */
    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(ProcessDiagnosticProvider::deleteQuietly);
        } catch (IOException ignored) {
            // 同上：清理失败不该冒泡成一次工具调用失败。
        }
    }
}
