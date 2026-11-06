package com.miniagent.agent.coding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 语法检查：起 {@code javac} 跑单文件编译，只留语法错。
 *
 * <h2>为什么只留语法错</h2>
 *
 * <p>单文件编译没有 classpath，任何 {@code import org.springframework...} 都会报
 * {@code package does not exist}。这类报错和「代码写错」在输出里长得一模一样，
 * 全量回传等于让模型在海量噪声里捞那一两条真错。</p>
 *
 * <p>所以口径是：<b>只报能确定是语法问题的</b>（缺分号、括号不匹配、非法字符、
 * 修饰符顺序错误等），依赖缺失一律丢弃。注入文案会写明这是语法级检查，
 * 不能让模型以为类型也验过了。</p>
 */
@Slf4j
@Component
public class JavacSyntaxProvider extends ProcessDiagnosticProvider {

    private static final Pattern DIAG_LINE =
            Pattern.compile("^(.*\\.java):(\\d+):\\s*(error|warning):\\s*(.*)$");

    /** {@code javac} 是否可用。{@code null} = 还没探过。探测结果要缓存，不能每个文件探一次。 */
    private volatile Boolean available;

    @Override
    public String id() {
        return "java";
    }

    @Override
    public boolean supports(Path file) {
        return file != null && file.getFileName() != null
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
    }

    @Override
    public boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean probed = probeJavac();
        available = probed;
        if (!probed) {
            // 桌面端是 jlink 出来的精简 JRE，jdk.compiler 本来就可能不在。
            // 这条日志是「为什么没有 Java 诊断」的唯一线索，别删。
            log.info("javac 不可用，Java 语法检查已跳过（内置 JRE 可能不含 jdk.compiler）");
        }
        return probed;
    }

    private static boolean probeJavac() {
        String home = System.getProperty("java.home");
        if (home != null) {
            boolean windows = System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("win");
            Path candidate = Path.of(home, "bin", windows ? "javac.exe" : "javac");
            if (Files.isRegularFile(candidate)) {
                return true;
            }
        }
        try {
            Process p = new ProcessBuilder(executable(), "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String executable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows ? "javac.exe" : "javac";
    }

    @Override
    protected List<String> command(Path file, Path scratchDir) {
        return List.of(
                executable(),
                // 强制英文诊断输出。
                // 实测（JDK 21 + 中文 Windows）：不加这两个参数时 javac 输出是
                // 「Broken.java:3: 错误: 需要';'」，而解析器认的是 "error:"，结果是
                // 一条诊断都抓不到、却也不报错 —— 功能静默失效。这不是环境巧合，
                // 中文系统上这是默认行为。
                "-J-Duser.language=en",
                "-J-Duser.country=US",
                // 输出流固定 UTF-8：否则中文路径会按 GBK 写进临时文件，读回来是乱码。
                "-J-Dfile.encoding=UTF-8",
                // 不跑注解处理器：单文件编译没有依赖，跑注解处理器只会多出一堆无关报错。
                "-proc:none",
                // 编译产物进临时目录，绝不能落在源码旁边。
                "-d", scratchDir.toAbsolutePath().toString(),
                file.toAbsolutePath().toString());
    }

    @Override
    protected List<Diagnostic> parse(Path file, String raw) {
        List<Diagnostic> all = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            Matcher m = DIAG_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String message = m.group(4);
            if (isDependencyNoise(message)) {
                continue;
            }
            int lineNo;
            try {
                lineNo = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                lineNo = 0;
            }
            String severity = "warning".equals(m.group(3)) ? "Warning" : "Error";
            all.add(new Diagnostic(file, lineNo, severity, message));
        }
        return cap(all);
    }
}
