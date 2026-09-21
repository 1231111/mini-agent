package com.miniagent.agent.tool;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 命令级只读判定：这条命令跑完「不可能改动任何东西」才算只读。
 *
 * <h2>它决定三件事</h2>
 *
 * <ol>
 *   <li><b>能不能并行</b>：{@code ToolExecutionGuards.canRunBatchInParallel} 只放行只读且幂等的工具。
 *       以前这个判定只吃<b>工具名</b>，于是 {@code exec_command} 无论跑 {@code git status}
 *       还是 {@code format C:} 待遇一样，永远进不了并行批。</li>
 *   <li><b>超时之后能不能安全重试</b>：只读命令被强杀时不可能留下半成品，
 *       所以可以报「超时可重试」；写类命令被强杀则必须报「终态未知」（见 {@code AgentLoop.timeoutToolResult}）。</li>
 *   <li><b>动作日志能不能自动重试</b>：{@code ToolPipeline.executeJournaled} 只对幂等动作放行重试。</li>
 * </ol>
 *
 * <h2>判定口径（保守优先）</h2>
 *
 * <p>任何一处拿不准都返回「不是只读」。漏判只损失一点并发；误判会让一条真会写盘的命令
 * 被当成可安全重试的动作，那是数据损失。具体四道闸：</p>
 * <ol>
 *   <li>整串不能出现重定向与命令替换（{@code >}、{@code >>}、反引号、{@code $(}）；
 *       {@code 2>&1} / {@code 1>&2} 这类句柄合并除外。藏在
 *       {@code bash -c "ls > out"} 引号里的那个 {@code >} 也算 ——
 *       扫描会先展开包装器再扫一遍。</li>
 *   <li>按 {@code &&} {@code ||} {@code |} {@code ;} {@code &} 切段，<b>每一段</b>的基础命令
 *       都必须在只读白名单里（{@code git status && rm -rf x} 的第二段会被抓出来）。
 *       包装器里的命令字符串先展开成段再判，否则 {@code bash -c "rm -rf x && ls"} 会因为
 *       「只看最后一段」而被判成只读。</li>
 *   <li>命令自身的「会变写」开关要单独拒：{@code find -exec/-delete}、{@code sort -o}、
 *       {@code git diff -o}、{@code --output=}。</li>
 *   <li>不会自己结束的命令不算只读：{@code tail -f}、不带次数的 {@code ping}。
 *       它们被算进并行批会把整批拖到超时。</li>
 * </ol>
 */
public final class CommandReadOnlyJudge {

    /** 只读的基础命令白名单（小写，不含路径与扩展名）。 */
    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            // POSIX 文件与目录观察
            "ls", "cat", "head", "tail", "wc", "uniq", "cut", "tr", "file", "stat",
            "du", "df", "tree", "pwd", "basename", "dirname", "realpath", "readlink",
            "md5sum", "sha1sum", "sha256sum", "cksum", "xxd", "od", "strings",
            // POSIX 检索与比较
            "grep", "egrep", "fgrep", "rg", "ag", "find", "fd", "diff", "cmp", "jq", "yq",
            "column", "nl", "join", "comm", "paste", "expand", "unexpand", "rev",
            // 环境与进程观察
            "whoami", "id", "groups", "hostname", "uname", "date", "uptime", "env",
            "printenv", "ps", "netstat", "ss", "ping", "traceroute", "which", "whereis",
            // cd 只改当前 shell 进程的工作目录，而每条命令都是独立进程，落不到磁盘上。
            // 不放进白名单的话，``cd /d repo && git status`` 这种最平常的组合会被判成写类。
            "cd",
            // Windows 观察
            "dir", "type", "findstr", "where", "more", "ver", "vol", "systeminfo",
            "tasklist", "ipconfig", "tracert", "path", "fc", "comp", "openfiles",
            // PowerShell 观察类 cmdlet（wrapper 剥离后才是这些名字）
            "get-childitem", "get-content", "get-item", "get-itemproperty", "get-location",
            "get-command", "get-help", "get-member", "get-process", "get-service", "get-date",
            "get-host", "get-variable", "select-string", "measure-object", "test-path",
            "resolve-path", "resolve-dnsname", "convertfrom-json", "convertto-json",
            "compare-object", "group-object", "sort-object", "select-object", "format-table",
            "format-list", "format-wide", "out-string", "get-filehash", "get-acl"
    );

    /** 需要显式只读开关才放行的命令：不带开关时可能是在改东西。 */
    private static final Set<String> SORT_LIKE = Set.of("sort", "sort-object");

    /** 任何命令出现这些开关都视为写盘。 */
    private static final Set<String> WRITE_FLAGS_ANY = Set.of(
            "--output", "--in-place", "-delete", "-exec", "-execdir", "-ok", "-okdir",
            "-fprint", "-fprint0", "-fls", "-fprintf", "--exec", "--exec-batch", "--pre"
    );

    /**
     * 只在特定命令上算写盘的开关。
     *
     * <p>{@code -o} 必须限定命令：{@code sort -o file} 是写盘，但 {@code grep -o} 是
     * 「只打印匹配部分」，把它当写开关会把最常见的检索命令误判掉。</p>
     */
    private static final Set<String> WRITE_FLAGS_SORT = Set.of("-o", "--output");

    /** 这些开关只会出现在不会结束的命令上。 */
    private static final Set<String> NEVER_TERMINATING = Set.of("-f", "-F", "--follow");

    /** git 的只读子命令。 */
    private static final Set<String> GIT_READ_ONLY_SUBCOMMANDS = Set.of(
            "status", "log", "diff", "show", "rev-parse", "describe", "blame", "ls-files",
            "ls-tree", "ls-remote", "shortlog", "whatchanged", "grep", "cat-file",
            "count-objects", "name-rev", "merge-base", "symbolic-ref", "for-each-ref",
            "check-ignore", "check-attr", "diff-tree", "diff-index", "diff-files",
            "verify-commit", "verify-tag", "show-ref", "rev-list", "var", "version"
    );

    /** git 子命令里必须带只读开关的那几个：不带开关时可能是在创建（如 {@code git branch new}）。 */
    private static final Set<String> GIT_FLAG_GATED_SUBCOMMANDS = Set.of(
            "branch", "tag", "config", "remote", "stash", "worktree", "notes",
            "submodule", "reflog"
    );

    /** git 只读开关。 */
    private static final Set<String> GIT_READ_FLAGS = Set.of(
            "-l", "--list", "-v", "--verbose", "--get", "--get-all", "--get-regexp",
            "--show-origin", "-a", "--all", "-r", "--remotes", "show", "list", "status",
            "summary", "--version", "--help"
    );

    private CommandReadOnlyJudge() {
    }

    /** 这条命令是否只读。 */
    public static boolean isReadOnly(String command) {
        return reasonNotReadOnly(command) == null;
    }

    /**
     * 判定为「非只读」的原因；只读时返回 null。
     *
     * <p>保留原因是为了让日志与单测能说清「为什么不算只读」，而不是只有一个 false。</p>
     */
    public static String reasonNotReadOnly(String command) {
        if (command == null || command.isBlank()) {
            return "空命令";
        }
        String meta = ShellCommandLine.unsafeMetacharacter(command);
        if (meta != null) {
            return "含重定向或命令替换: " + meta;
        }
        List<ShellCommandLine.Segment> segments = ShellCommandLine.segments(command);
        if (segments.isEmpty()) {
            return "无法解析出命令段";
        }
        for (ShellCommandLine.Segment segment : segments) {
            String reason = segmentReason(segment);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private static String segmentReason(ShellCommandLine.Segment segment) {
        String base = segment.baseCommand();
        if (base == null || base.isEmpty()) {
            return "无法识别基础命令";
        }
        if (segment.hasArgument("--version") || segment.hasArgument("--help") || segment.hasArgument("-h")) {
            // --version / --help 不改变任何状态，任何命令带它都是只读的
            return null;
        }
        if ("git".equals(base)) {
            return gitSegmentReason(segment);
        }
        if (SORT_LIKE.contains(base)) {
            // sort 本身只读，但 -o/--output 会写文件
            return hasWriteFlagOrShortOutput(segment) ? base + " 带写盘开关" : null;
        }
        if (!READ_ONLY_COMMANDS.contains(base)) {
            return "命令 " + base + " 不在只读白名单";
        }
        if (hasWriteFlag(segment)) {
            return base + " 带写盘开关";
        }
        if ("tail".equals(base)
                && (segment.hasToken("-f") || segment.hasToken("-F") || segment.hasToken("--follow"))) {
            return "tail -f 不会自行结束";
        }
        if ("ping".equals(base)
                && !segment.hasToken("-c") && !segment.hasToken("-n") && !segment.hasToken("-w")) {
            return "ping 未指定次数，不会自行结束";
        }
        if ("more".equals(base) && NEVER_TERMINATING.stream().anyMatch(segment::hasToken)) {
            return "more 带阻塞开关，不会自行结束";
        }
        return null;
    }

    private static String gitSegmentReason(ShellCommandLine.Segment segment) {
        String sub = subcommandOf(segment);
        if (sub == null) {
            // 只有 git 本身（或仅带全局开关）：不放行，避免 git 全局开关里有写操作
            return "git 未指定子命令";
        }
        if (GIT_READ_ONLY_SUBCOMMANDS.contains(sub)) {
            return hasWriteFlagOrShortOutput(segment) ? "git " + sub + " 带写盘开关" : null;
        }
        if (GIT_FLAG_GATED_SUBCOMMANDS.contains(sub)) {
            boolean hasReadFlag = segment.tokens().stream()
                    .map(t -> t.toLowerCase(Locale.ROOT))
                    .anyMatch(t -> GIT_READ_FLAGS.contains(t));
            if (!hasReadFlag) {
                return "git " + sub + " 未带只读开关，可能是在写";
            }
            return hasWriteFlagOrShortOutput(segment) ? "git " + sub + " 带写盘开关" : null;
        }
        return "git " + sub + " 不是只读子命令";
    }

    /** 取 git 的子命令：跳过全局开关（{@code -C path}、{@code --no-pager} 等）后的第一个非选项 token。 */
    private static String subcommandOf(ShellCommandLine.Segment segment) {
        List<String> tokens = segment.tokens();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("-")) {
                continue;
            }
            return token.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean hasWriteFlag(ShellCommandLine.Segment segment) {
        for (String token : segment.tokens()) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (WRITE_FLAGS_ANY.contains(lower)) {
                return true;
            }
            if (lower.startsWith("--output=") || lower.startsWith("--in-place=")) {
                return true;
            }
        }
        return false;
    }

    /** sort / git 这类命令上的 {@code -o} 才算写盘（grep -o 是只读）。 */
    private static boolean hasWriteFlagOrShortOutput(ShellCommandLine.Segment segment) {
        if (hasWriteFlag(segment)) {
            return true;
        }
        return segment.tokens().stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .anyMatch(WRITE_FLAGS_SORT::contains);
    }
}
