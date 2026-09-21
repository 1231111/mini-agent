package com.miniagent.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 命令行切分：把一条 shell 命令拆成顶层段，并解析出每段真正在跑的那个可执行文件。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>退出码的含义、以及「这条命令是否只读」，都取决于<b>最后真正执行的那个程序</b>，
 * 而不是整串文本。{@code findstr foo log.txt | more} 的退出码来自 {@code more} 而不是 {@code findstr}；
 * {@code cd /tmp && ls} 里有两个程序，只读性必须逐个段判定。</p>
 *
 * <h2>包装器要展开，不能只剥离</h2>
 *
 * <p>{@code cmd /c dir}、{@code bash -c "grep x f | wc -l"}、{@code pwsh -Command "Get-ChildItem"} 里，
 * 包装器后面的那一整串<b>本身又是一条命令行</b>。只把 {@code cmd} 剥掉、把后面的串当普通参数，
 * 会同时错两件事：基础命令取成包装器自己（只读白名单永远命中不了），
 * 以及引号里的 {@code |} / {@code &&} 躲过切分 ——
 * {@code bash -c "rm -rf x && ls"} 会被看成「一段，基础命令 ls」，于是被判成只读。
 * 所以这里递归展开包装器内的命令字符串，让真正的段暴露出来。</p>
 *
 * <p>切分规则刻意保守：解析不确定时宁可把它当成「复杂命令」（调用方按最严处理），也不猜。</p>
 */
public final class ShellCommandLine {

    /** 段之间的分隔符，按长度优先匹配（{@code &&} 必须先于 {@code &}）。 */
    private static final String[] SEPARATORS = {"&&", "||", "|", ";", "&", "\n", "\r"};

    /**
     * 需要剥离的包装器：它们只是转发到真正的命令，本身不改变语义。
     * 不剥离的话 {@code timeout 30 git status} 的基础命令会变成 {@code timeout}，只读判定直接失效。
     */
    private static final Set<String> POSIX_WRAPPERS = Set.of(
            "timeout", "nice", "env", "nohup", "command", "bash", "sh", "zsh", "dash");
    /** Windows / PowerShell 的调用包装器。 */
    private static final Set<String> WINDOWS_WRAPPERS = Set.of("cmd", "powershell", "pwsh");

    /**
     * 「后面跟的是命令字符串」的开关。
     *
     * <p>这些开关的取值是一条新命令行，要递归展开；这与 {@link #WRAPPER_OPTIONS_WITH_VALUE}
     * 里那些「取值只是普通参数」的开关处置方式相反。</p>
     */
    private static final Set<String> COMMAND_STRING_OPTIONS = Set.of(
            "/c", "/k", "-c", "-command", "--command");

    /** 包装器的开关参数：需要连同其取值一起跳过。 */
    private static final Set<String> WRAPPER_OPTIONS_WITH_VALUE = Set.of(
            "/c", "/k", "-c", "-command", "--command",
            "-file", "-executionpolicy", "-noprofile",
            "-n", "-u", "-s", "--user", "--unset", "-i", "--ignore-environment");

    /** 包装器嵌套展开的深度上限：防止 {@code cmd /c cmd /c ...} 这类构造把解析拖死。 */
    private static final int MAX_WRAPPER_DEPTH = 3;

    /**
     * 一个顶层命令段。
     *
     * <p>{@code tokens} 已经从<b>基础命令</b>开始：{@code timeout 30 git status} 的 tokens 是
     * {@code [git, status]}，包装器与前置环境变量赋值都已被剥掉。
     * 保留前缀会让「取第一个非选项 token 当子命令」这类逻辑取到 {@code 30}，
     * 于是 {@code timeout 30 git status} 会被判成「git 30 不是只读子命令」。</p>
     */
    public record Segment(String raw, String baseCommand, List<String> tokens) {
        /** 段内是否出现某个 token（小写比较）。 */
        public boolean hasToken(String token) {
            String t = token.toLowerCase(Locale.ROOT);
            return tokens.stream().anyMatch(x -> x.toLowerCase(Locale.ROOT).equals(t));
        }

        /** 段内是否出现某个参数（用于 {@code -delete} 这类会改变只读性的开关）。 */
        public boolean hasArgument(String arg) {
            String a = arg.toLowerCase(Locale.ROOT);
            return tokens.stream().anyMatch(x -> x.toLowerCase(Locale.ROOT).equals(a));
        }
    }

    private ShellCommandLine() {
    }

    /**
     * 按顶层分隔符切分，并把包装器内的命令字符串展开成真正的段。
     *
     * <p>引号内部的字符不参与切分（{@code echo "a;b"} 只有一段）——
     * 但 {@code cmd /c "a;b"} 里的 {@code a;b} 是一条命令字符串，会被展开成两段。</p>
     */
    public static List<Segment> segments(String command) {
        List<Segment> out = new ArrayList<>();
        expandInto(command, 0, out);
        return out;
    }

    private static void expandInto(String command, int depth, List<Segment> out) {
        if (command == null || command.isBlank() || depth > MAX_WRAPPER_DEPTH) {
            return;
        }
        for (String raw : splitTopLevel(command)) {
            Segment segment = parseSegment(raw);
            if (segment == null) {
                continue;
            }
            String inner = innerCommandString(raw);
            if (inner.isEmpty()) {
                out.add(segment);
            } else {
                expandInto(inner, depth + 1, out);
            }
        }
    }

    /**
     * 取包装器后面的命令字符串；不是「包装器 + 命令字符串开关」的形态就返回空串。
     *
     * <p>{@code cmd /c dir} → {@code dir}；{@code bash -c "ls | wc -l"} → {@code ls | wc -l}。
     * 引号在分词阶段已被去掉，所以这里直接按空格拼回剩余 token 即可。</p>
     */
    private static String innerCommandString(String raw) {
        List<String> tokens = tokenize(raw);
        if (tokens.size() < 3) {
            return "";
        }
        String head = stripPathAndExtension(tokens.get(0).toLowerCase(Locale.ROOT));
        if (!WINDOWS_WRAPPERS.contains(head) && !POSIX_WRAPPERS.contains(head)) {
            return "";
        }
        for (int i = 1; i < tokens.size(); i++) {
            if (!COMMAND_STRING_OPTIONS.contains(tokens.get(i).toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (i + 1 >= tokens.size()) {
                return "";
            }
            return String.join(" ", tokens.subList(i + 1, tokens.size()));
        }
        return "";
    }

    /**
     * 扫描重定向与命令替换（引号内的字符不算）。
     *
     * <p>{@code 2>&1} / {@code 1>&2} / {@code 2>nul} 是句柄合并与丢弃，不产生文件写入，放行。</p>
     *
     * <p>必须连包装器内的命令字符串一起扫：{@code bash -c "ls > out" } 的重定向躲在引号里，
     * 只扫外层文本会漏掉，那条命令就会被判成只读。</p>
     */
    public static String unsafeMetacharacter(String command) {
        return scanMetacharacter(command, 0);
    }

    private static String scanMetacharacter(String command, int depth) {
        if (command == null || command.isBlank() || depth > MAX_WRAPPER_DEPTH) {
            return null;
        }
        String found = scanQuotedAware(command);
        if (found != null) {
            return found;
        }
        for (String raw : splitTopLevel(command)) {
            Segment segment = parseSegment(raw);
            if (segment == null) {
                continue;
            }
            String inner = innerCommandString(raw);
            if (!inner.isEmpty()) {
                String nested = scanMetacharacter(inner, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String scanQuotedAware(String command) {
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '`') {
                return "`";
            }
            if (c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(') {
                return "$(";
            }
            if (c == '<' && i + 1 < command.length() && command.charAt(i + 1) == '<') {
                return "<<";
            }
            if (c == '>') {
                String tail = command.substring(i).toLowerCase(Locale.ROOT);
                if (tail.startsWith(">&") || tail.startsWith(">nul") || tail.startsWith("> nul")) {
                    continue;
                }
                return ">";
            }
        }
        return null;
    }

    /** 最后一个非空段；管道与 {@code &&} 的退出码都由它决定。 */
    public static Segment lastSegment(String command) {
        List<Segment> all = segments(command);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** 该命令是否是一个「简单命令」（单段，没有管道/链式/重定向）。 */
    public static boolean isSimple(String command) {
        return segments(command).size() == 1;
    }

    private static List<String> splitTopLevel(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
                continue;
            }
            String sep = matchSeparator(command, i);
            if (sep != null) {
                parts.add(current.toString());
                current.setLength(0);
                i += sep.length() - 1;
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private static String matchSeparator(String command, int at) {
        for (String sep : SEPARATORS) {
            if (!command.startsWith(sep, at)) {
                continue;
            }
            if ("&".equals(sep) && isRedirectAmpersand(command, at)) {
                continue;
            }
            return sep;
        }
        return null;
    }

    /**
     * {@code &} 是不是重定向的一部分（{@code 2>&1}、{@code &>file}）。
     *
     * <p>这类 {@code &} 一旦被当成后台运行符，{@code ls 2>&1} 会被切成 {@code ls 2>} 和 {@code 1}，
     * 第二段的基础命令变成 {@code 1} —— 最平常的错误流合并于是被判成「不是只读」。</p>
     */
    private static boolean isRedirectAmpersand(String command, int at) {
        boolean prevIsAngle = at > 0 && command.charAt(at - 1) == '>';
        boolean nextIsAngle = at + 1 < command.length() && command.charAt(at + 1) == '>';
        return prevIsAngle || nextIsAngle;
    }

    private static Segment parseSegment(String raw) {
        List<String> tokens = tokenize(raw);
        if (tokens.isEmpty()) {
            return null;
        }
        int base = baseIndexIn(tokens);
        List<String> commandTokens = base < 0 ? tokens : tokens.subList(base, tokens.size());
        return new Segment(raw.trim(), stripPathAndExtension(commandTokens.get(0)),
                List.copyOf(commandTokens));
    }

    /**
     * 基础命令在 token 序列里的下标：跳过变量赋值与包装器（及其参数）。
     * 全是被跳过的内容（如光秃秃一个 {@code cmd}）时返回 -1。
     */
    static int baseIndexIn(List<String> tokens) {
        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            String lower = token.toLowerCase(Locale.ROOT);
            if (isEnvAssignment(token)) {
                i++;
                continue;
            }
            if (wrapperName(lower) == null) {
                return i;
            }
            i = skipWrapperOptions(tokens, i + 1);
        }
        return -1;
    }

    /**
     * 跳过包装器的参数与取值（{@code timeout 30}、{@code -c "..."}、{@code /c}、{@code -n 10}），
     * 停在真正的命令名上。
     */
    private static int skipWrapperOptions(List<String> tokens, int from) {
        int i = from;
        while (i < tokens.size()) {
            String next = tokens.get(i);
            String nextLower = next.toLowerCase(Locale.ROOT);
            boolean isOption = next.startsWith("-") || next.startsWith("/");
            if (isOption && WRAPPER_OPTIONS_WITH_VALUE.contains(nextLower)) {
                i += 2;
                continue;
            }
            if (isOption) {
                i++;
                continue;
            }
            // timeout 的时长、env 的 VAR=val 这类非选项取值
            if (isEnvAssignment(next) || looksLikeDuration(next)) {
                i++;
                continue;
            }
            break;
        }
        return i;
    }

    private static String wrapperName(String lowerToken) {
        String bare = stripPathAndExtension(lowerToken);
        if (POSIX_WRAPPERS.contains(bare) || WINDOWS_WRAPPERS.contains(bare)) {
            return bare;
        }
        return null;
    }

    /** POSIX 前置变量赋值：{@code FOO=bar cmd}。 */
    private static boolean isEnvAssignment(String token) {
        int eq = token.indexOf('=');
        if (eq <= 0) {
            return false;
        }
        String name = token.substring(0, eq);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '_';
            if (!ok) {
                return false;
            }
        }
        return Character.isLetter(name.charAt(0)) || name.charAt(0) == '_';
    }

    /** {@code timeout} 的时长写法：纯数字（可带 s/m/h 后缀）。 */
    private static boolean looksLikeDuration(String token) {
        String t = token;
        if (t.length() > 1 && (t.endsWith("s") || t.endsWith("m") || t.endsWith("h"))) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.isEmpty() || t.length() > 6) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i)) && t.charAt(i) != '.') {
                return false;
            }
        }
        return true;
    }

    /** 去掉目录前缀与 Windows 扩展名：{@code /usr/bin/grep}、{@code C:\\tools\\rg.exe} → {@code grep} / {@code rg}。 */
    static String stripPathAndExtension(String token) {
        String t = token.trim();
        if (t.isEmpty()) {
            return t;
        }
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0 && slash < t.length() - 1) {
            t = t.substring(slash + 1);
        }
        String lower = t.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".exe", ".cmd", ".bat", ".ps1", ".com"}) {
            if (lower.endsWith(ext) && lower.length() > ext.length()) {
                return t.substring(0, t.length() - ext.length()).toLowerCase(Locale.ROOT);
            }
        }
        return lower;
    }

    /**
     * 分词：空白分隔，双引号与单引号内的空白不切分，引号本身不保留。
     */
    static List<String> tokenize(String raw) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\'') {
                if (quote == 0) {
                    quote = c;
                    started = true;
                    continue;
                }
                if (quote == c) {
                    quote = 0;
                    continue;
                }
            }
            if (quote == 0 && Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
                continue;
            }
            started = true;
            current.append(c);
        }
        if (started) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
