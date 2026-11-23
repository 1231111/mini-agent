package com.miniagent.agent.tool;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the encoding used for a child process' captured output. */
final class ProcessOutputEncoding {

    private static final Pattern CODE_PAGE_PATTERN = Pattern.compile("(?<!\\d)(\\d{3,5})(?!\\d)");
    private static final Set<String> UTF8_COMMANDS = Set.of(
            "node", "npm", "npx", "deno", "bun", "python", "python3", "py",
            "java", "javac", "mvn", "mvnw", "git", "rg", "grep");
    private static volatile int cachedWindowsCodePage;
    private static volatile boolean windowsCodePageResolved;

    private ProcessOutputEncoding() { }

    static Charset resolve(String requestedEncoding, String command, boolean windows) {
        return resolve(requestedEncoding, command, windows,
                windows ? detectWindowsConsoleCodePage() : -1);
    }

    static Charset resolve(String requestedEncoding, String command, boolean windows, int windowsCodePage) {
        String requested = requestedEncoding == null ? "" : requestedEncoding.trim();
        if (!requested.isEmpty() && !requested.equalsIgnoreCase("auto")) {
            return explicitCharset(requested);
        }
        if (!windows || usesUtf8Output(command)) return StandardCharsets.UTF_8;
        return charsetForWindowsCodePage(windowsCodePage);
    }

    static int parseWindowsCodePage(String output) {
        if (output == null || output.isBlank()) return -1;
        Matcher matcher = CODE_PAGE_PATTERN.matcher(output);
        int codePage = -1;
        while (matcher.find()) {
            try {
                int candidate = Integer.parseInt(matcher.group(1));
                if (candidate >= 100 && candidate <= 99_999) codePage = candidate;
            } catch (NumberFormatException ignored) {
                // Keep scanning. The command output may include unrelated localized text.
            }
        }
        return codePage;
    }

    static Charset charsetForWindowsCodePage(int codePage) {
        try {
            return switch (codePage) {
                case 65001 -> StandardCharsets.UTF_8;
                case 936 -> Charset.forName("GBK");
                case 54936 -> Charset.forName("GB18030");
                case 950 -> Charset.forName("Big5");
                case 932 -> Charset.forName("windows-31j");
                case 949 -> Charset.forName("x-windows-949");
                case 874 -> Charset.forName("x-IBM874");
                case 437, 720, 737, 775, 850, 852, 855, 857, 858, 860, 861, 862, 863, 864,
                        865, 866, 869 -> Charset.forName("IBM" + codePage);
                default -> codePage >= 1250 && codePage <= 1258
                        ? Charset.forName("windows-" + codePage) : Charset.defaultCharset();
            };
        } catch (RuntimeException unsupportedCodePage) {
            return Charset.defaultCharset();
        }
    }

    static Charset explicitCharset(String requestedEncoding) {
        String normalized = requestedEncoding.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        String canonical = switch (normalized) {
            case "utf8", "utf-8" -> "UTF-8";
            case "gbk", "cp936", "windows-936" -> "GBK";
            case "gb18030", "cp54936", "windows-54936" -> "GB18030";
            case "big5", "cp950", "windows-950" -> "Big5";
            case "shift-jis", "sjis", "cp932", "windows-932" -> "windows-31j";
            case "windows-1252", "cp1252" -> "windows-1252";
            default -> throw new IllegalArgumentException(
                    "不支持的输出编码；可选 auto、UTF-8、GBK、GB18030、Big5、Shift-JIS 或 windows-1252");
        };
        return Charset.forName(canonical);
    }

    private static boolean usesUtf8Output(String command) {
        if (command == null || command.isBlank()) return false;
        try {
            ShellCommandLine.Segment first = ShellCommandLine.segments(command).stream().findFirst().orElse(null);
            if (first == null) return false;
            String base = first.baseCommand().toLowerCase(Locale.ROOT);
            int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
            if (slash >= 0) base = base.substring(slash + 1);
            int extension = base.lastIndexOf('.');
            if (extension > 0) base = base.substring(0, extension);
            return UTF8_COMMANDS.contains(base);
        } catch (RuntimeException invalidCommand) {
            return false;
        }
    }

    private static int detectWindowsConsoleCodePage() {
        if (windowsCodePageResolved) return cachedWindowsCodePage;
        synchronized (ProcessOutputEncoding.class) {
            if (windowsCodePageResolved) return cachedWindowsCodePage;
            cachedWindowsCodePage = probeWindowsConsoleCodePage();
            windowsCodePageResolved = true;
            return cachedWindowsCodePage;
        }
    }

    private static int probeWindowsConsoleCodePage() {
        Process process = null;
        try {
            ProcessBuilder probe = new ProcessBuilder("cmd.exe", "/d", "/c", "chcp")
                    .redirectErrorStream(true);
            ProcessEnv.sanitize(probe);
            process = probe.start();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            // The code page number is ASCII regardless of the localized chcp message.
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            return parseWindowsCodePage(output);
        } catch (IOException failure) {
            return -1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return -1;
        }
    }
}
