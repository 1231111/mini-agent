package com.miniagent.agent.tool;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies a narrow class of JavaScript test-harness failures without treating them as
 * successful product checks. This only annotates the original non-zero command result.
 */
public final class SelfCheckScriptDiagnostic {
    private static final Pattern JS_EXCEPTION = Pattern.compile(
            "(?m)^\\s*(ReferenceError|SyntaxError):(?:\\s|$)");
    private static final Pattern JS_STACK_FRAME = Pattern.compile(
            "(?m)^\\s*at\\s+(?:.*?\\s+\\()?(.+?\\.(?:js|mjs|cjs)):(\\d+):(\\d+)\\)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_CHECK_NAME = Pattern.compile(
            "(?i)(?:^test(?:[_-].*)?|.*\\.(?:test|spec))\\.(?:js|mjs|cjs)$");
    private static final Pattern FAIL_LINE = Pattern.compile("(?m)^[\\t ]*\\[FAIL\\](?:[\\t ]|$)");
    private SelfCheckScriptDiagnostic() {}

    public enum Category { TEST_HARNESS_ERROR, TEST_ASSERTION_FAILURE }

    public record Diagnostic(Category category, String errorType, String scriptName, int line, int column) {
        public String asToolNote() {
            if (category == Category.TEST_ASSERTION_FAILURE) {
                return "[TEST_ASSERTION_FAILURE] workspace 自检脚本 " + scriptName
                        + " 输出了独立 [FAIL] 断言；这是阻断性失败，不代表产品错误，也不代表验收通过。"
                        + "先将断言条件与实际 DOM 和原验收标准逐项核对，再决定修检查脚本还是产品；"
                        + "必须用独立证据验收，不能只因脚本报告有误就忽略失败。";
            }
            String location = line > 0 ? " 第 " + line + " 行" + (column > 0 ? "、列 " + column : "") : "";
            return "[TEST_HARNESS_ERROR] JavaScript 自检脚本 " + scriptName + location + " 出现 "
                    + errorType + "。命令仍然失败，不能据此认定产品代码有错或验收通过；"
                    + "先检查并修复这个自检脚本，再重新运行验证。不要仅凭此错误修改被测页面。";
        }
    }

    /**
     * Runtime diagnostics require the first user-code stack frame to point to the explicitly
     * invoked workspace-local test script. Assertion diagnostics require a direct Node invocation
     * and an independent [FAIL] line; they remain blocking and do not attribute the defect.
     */
    public static Optional<Diagnostic> classify(String command, int exitCode, String output,
                                                Path workingDirectory) {
        if (exitCode == 0 || command == null || output == null || workingDirectory == null) {
            return Optional.empty();
        }
        TestScript invokedScript = invokedTestScript(command, workingDirectory);
        if (invokedScript == null) return Optional.empty();
        Matcher exception = JS_EXCEPTION.matcher(output);
        if (exception.find()) {
            String errorType = exception.group(1);
            Matcher frames = JS_STACK_FRAME.matcher(output);
            while (frames.find()) {
                String rawPath = frames.group(1).trim();
                String normalizedForInternalCheck = rawPath.replace('\\', '/').toLowerCase(Locale.ROOT);
                if (normalizedForInternalCheck.startsWith("node:")
                        || normalizedForInternalCheck.contains("/node_modules/")) continue;
                Path frame = resolve(rawPath, workingDirectory);
                if (frame == null || !frame.equals(invokedScript.path())) {
                    // Do not skip a user-code frame in order to classify a later harness frame.
                    return Optional.empty();
                }
                return Optional.of(new Diagnostic(Category.TEST_HARNESS_ERROR, errorType,
                        invokedScript.name(), parseInt(frames.group(2)), parseInt(frames.group(3))));
            }
            return Optional.empty();
        }
        if (!FAIL_LINE.matcher(output).find()) return Optional.empty();
        return Optional.of(new Diagnostic(Category.TEST_ASSERTION_FAILURE, "", invokedScript.name(), 0, 0));
    }

    private static TestScript invokedTestScript(String command, Path workingDirectory) {
        List<ShellCommandLine.Segment> segments = ShellCommandLine.segments(command);
        if (segments.size() != 1 || !List.of("node", "nodejs").contains(segments.getFirst().baseCommand())) {
            return null;
        }
        List<String> tokens = segments.getFirst().tokens();
        int scriptIndex = tokens.size() > 1 && "--test".equals(tokens.get(1)) ? 2 : 1;
        if (scriptIndex >= tokens.size() || !SELF_CHECK_NAME.matcher(fileName(tokens.get(scriptIndex))).matches()) {
            // Require the script to be an explicit Node entry point. In particular, never
            // infer a script from text embedded in `node -e` or an unrelated argument.
            return null;
        }
        TestScript found = null;
        for (int i = scriptIndex; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String name = fileName(token);
            if (!SELF_CHECK_NAME.matcher(name).matches()) continue;
            if (found != null) return null;
            Path script = resolve(token, workingDirectory);
            Path root = workingDirectory.toAbsolutePath().normalize();
            if (script == null || !script.startsWith(root) || Files.isSymbolicLink(script)
                    || !Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)
                    || !PathGuard.isAllowed(script)) return null;
            found = new TestScript(name, script);
        }
        return found;
    }

    private record TestScript(String name, Path path) {}

    private static Path resolve(String rawPath, Path workingDirectory) {
        try {
            String value = rawPath.startsWith("file://") ? rawPath.substring("file://".length()) : rawPath;
            Path candidate = Path.of(value);
            if (!candidate.isAbsolute()) candidate = workingDirectory.resolve(candidate);
            return candidate.toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String fileName(String value) {
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
