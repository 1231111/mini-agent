package com.miniagent.agent.tool;

import java.util.Locale;
import java.util.Map;

/**
 * 退出码语义：<b>非 0 退出不等于失败</b>。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>很多命令用退出码传递信息而不是表达错误：{@code grep} / {@code findstr} 用 1 表示
 * 「没有匹配到」，{@code find} 用 1 表示「部分目录读不了但仍有结果」，{@code diff} 用 1 表示
 * 「文件有差异」，{@code test} 用 1 表示「条件不成立」。把这些一律读成失败，会有两个连锁后果：</p>
 * <ol>
 *   <li>模型收到「工具执行失败」，于是重试同一条命令 —— 结果永远不变，白烧轮次；</li>
 *   <li>{@code AgentLoop} 的 {@code allFailedRepeated} 闸门按失败次数计数，重试到阈值就中止整轮，
 *       报「连续多次用相同参数调用同样的工具但没拿到新结果」。</li>
 * </ol>
 *
 * <p>判定依据是<b>最后一段</b>的基础命令：{@code findstr x f.txt | more} 的退出码来自 {@code more}，
 * 与 {@code findstr} 的语义无关。</p>
 */
public final class CommandSemantics {

    /** 一次退出码解读的结果。{@code message} 仅在 {@code error} 为 false 但有话要说时非空。 */
    public record Interpretation(boolean error, String message) {
        static Interpretation ok() {
            return new Interpretation(false, null);
        }

        static Interpretation tolerated(String message) {
            return new Interpretation(false, message);
        }

        static Interpretation failed(String message) {
            return new Interpretation(true, message);
        }
    }

    /**
     * 特殊语义表：这些命令的 1 是信息，2 起才是错误。
     *
     * <p>只登记「广泛验证过、且在 Windows / POSIX 两边都有对应命令」的。拿不准的一律走默认语义，
     * 因为把真失败读成成功比把成功读成失败更危险。</p>
     */
    private static final Map<String, String> NON_ERROR_EXIT_ONE = Map.ofEntries(
            Map.entry("grep", "未匹配到内容"),
            Map.entry("egrep", "未匹配到内容"),
            Map.entry("fgrep", "未匹配到内容"),
            Map.entry("rg", "未匹配到内容"),
            Map.entry("findstr", "未匹配到内容"),
            Map.entry("select-string", "未匹配到内容"),
            Map.entry("find", "部分目录不可访问，但结果已返回"),
            Map.entry("fd", "部分目录不可访问，但结果已返回"),
            Map.entry("diff", "文件存在差异"),
            Map.entry("cmp", "文件存在差异"),
            Map.entry("fc", "文件存在差异"),
            Map.entry("test", "条件不成立"),
            Map.entry("[", "条件不成立")
    );

    /** 这些命令只有 0 和 1 两种退出，1 表示「没找到」，不存在「错误」档。 */
    private static final Map<String, String> ZERO_OR_ONE_ONLY = Map.of(
            "where", "未找到匹配项",
            "which", "未找到匹配项",
            "whereis", "未找到匹配项"
    );

    /**
     * 非错误退出码的提示行前缀。
     *
     * <p><b>写方与读方共用这一个常量</b>：{@code BuiltinTools.execCommand} 写它，
     * {@code ToolResult.fromLegacy} 读它。两边各写一份字面量是这类判定的经典裂缝
     * —— 改一处忘一处，判定就永久失效且没人发现。</p>
     *
     * <p>为什么提示要出现在给模型看的正文里：模型看到 {@code exit_code=1} 会本能地重试，
     * 必须同时告诉它「这个 1 不是错误」，否则修好判定也治不住重试。</p>
     */
    public static final String TOLERATED_EXIT_PREFIX = "[exit-note] ";

    private CommandSemantics() {
    }

    /** 非错误退出码要写进结果正文的提示行；0 与真错误都返回空串。 */
    public static String toleratedNote(String command, int exitCode) {
        if (exitCode == 0) {
            // 0 是成功，没有任何需要解释的东西。少了这个短路，
            // interpret 会走到「error=false 但 message 为空」的分支，给成功结果写出一行
            // 「[exit-note] 该退出码不是错误（退出码 0 不是错误…）」——
            // 调用方现在恰好只在 exitCode != 0 时调用它，但那是调用方的约定，不该由它保证。
            return "";
        }
        Interpretation it = interpret(command, exitCode);
        if (it.error()) {
            return "";
        }
        String reason = it.message() == null ? "该退出码不是错误" : it.message();
        return TOLERATED_EXIT_PREFIX + reason
                + "（退出码 " + exitCode + " 不是错误，不要重试同一条命令）";
    }

    /** 结果正文里是否带「非错误退出码」提示。 */
    public static boolean isToleratedExitText(String rawText) {
        return rawText != null && rawText.contains(TOLERATED_EXIT_PREFIX);
    }

    /**
     * 解读一次命令执行的退出码。
     *
     * @param command  原始命令（可含管道与链式）
     * @param exitCode 进程退出码
     */
    public static Interpretation interpret(String command, int exitCode) {
        if (exitCode == 0) {
            return Interpretation.ok();
        }
        String base = baseCommand(command);
        if (base == null || base.isEmpty()) {
            return Interpretation.failed(defaultMessage(exitCode));
        }
        String zeroOrOne = ZERO_OR_ONE_ONLY.get(base);
        if (zeroOrOne != null) {
            return exitCode == 1
                    ? Interpretation.tolerated(zeroOrOne)
                    : Interpretation.failed(defaultMessage(exitCode));
        }
        String tolerated = NON_ERROR_EXIT_ONE.get(base);
        if (tolerated != null) {
            // 1 = 语义性非错误；2 起（含 127 找不到命令）才是错误
            return exitCode == 1
                    ? Interpretation.tolerated(tolerated)
                    : Interpretation.failed(defaultMessage(exitCode));
        }
        return Interpretation.failed(defaultMessage(exitCode));
    }

    /** 该命令的这个退出码是否应判为失败。 */
    public static boolean isFailure(String command, int exitCode) {
        return interpret(command, exitCode).error();
    }

    private static String baseCommand(String command) {
        ShellCommandLine.Segment last = ShellCommandLine.lastSegment(command);
        return last == null ? null : last.baseCommand();
    }

    private static String defaultMessage(int exitCode) {
        return "Command failed with exit code " + exitCode;
    }

    /** 供诊断与测试：该命令是否登记了特殊退出码语义。 */
    public static boolean hasSpecialSemantics(String command) {
        String base = baseCommand(command);
        if (base == null) {
            return false;
        }
        String lower = base.toLowerCase(Locale.ROOT);
        return NON_ERROR_EXIT_ONE.containsKey(lower) || ZERO_OR_ONE_ONLY.containsKey(lower);
    }
}
