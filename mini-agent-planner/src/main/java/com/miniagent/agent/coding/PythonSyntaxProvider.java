package com.miniagent.agent.coding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python 语法检查：用 {@code ast.parse} 纯解析，不产生任何文件。
 *
 * <h2>为什么不用 {@code python -m py_compile}</h2>
 *
 * <p>{@code py_compile} 会把字节码写进源码目录的 {@code __pycache__}。
 * agent 每改一次文件就在用户项目里留一个缓存目录，这是不该有的副作用。
 * {@code ast.parse} 只解析不落盘，拿到的错误信息（{@code SyntaxError: expected ':'}）完全相同。</p>
 */
@Slf4j
@Component
public class PythonSyntaxProvider extends ProcessDiagnosticProvider {

    private static final Pattern LINE_HINT = Pattern.compile("line (\\d+)");
    private static final Pattern ERROR_HEAD = Pattern.compile("^\\s*([A-Za-z]*Error|IndentationError)\\s*:\\s*(.*)$");

    /** 探测到的解释器命令；{@code null} 表示还没探过，空串表示探过但没有。 */
    private volatile String interpreter;

    @Override
    public String id() {
        return "python";
    }

    @Override
    public boolean supports(Path file) {
        return file != null && file.getFileName() != null
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".py");
    }

    @Override
    public boolean available() {
        String cached = interpreter;
        if (cached == null) {
            cached = probeInterpreter();
            interpreter = cached;
            if (cached.isEmpty()) {
                log.info("未找到 python/python3，Python 语法检查已跳过");
            }
        }
        return !cached.isEmpty();
    }

    private static String probeInterpreter() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> candidates = windows ? List.of("python", "py", "python3")
                : List.of("python3", "python");
        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            } catch (Exception ignored) {
                // 试下一个候选
            }
        }
        return "";
    }

    @Override
    protected List<String> command(Path file, Path scratchDir) {
        String py = interpreter;
        if (py == null || py.isEmpty()) {
            throw new IllegalStateException("没有可用的 python 解释器");
        }
        return List.of(py, "-c",
                "import ast,sys;ast.parse(open(sys.argv[1],encoding='utf-8').read())",
                file.toAbsolutePath().toString());
    }

    @Override
    protected List<Diagnostic> parse(Path file, String raw) {
        List<Diagnostic> all = new ArrayList<>();
        int lineNo = 0;
        for (String line : raw.split("\\R")) {
            Matcher hint = LINE_HINT.matcher(line);
            if (hint.find()) {
                try {
                    lineNo = Integer.parseInt(hint.group(1));
                } catch (NumberFormatException ignored) {
                    lineNo = 0;
                }
            }
            Matcher head = ERROR_HEAD.matcher(line);
            if (!head.matches()) {
                continue;
            }
            String message = head.group(1) + ": " + head.group(2);
            if (isDependencyNoise(message)) {
                continue;
            }
            all.add(new Diagnostic(file, lineNo, "Error", message));
        }
        return cap(all);
    }
}
