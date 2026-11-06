package com.miniagent.agent.coding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TypeScript 语法检查：调用项目本地的 {@code node_modules/.bin/tsc}。
 *
 * <h2>为什么不用 {@code npx tsc}</h2>
 *
 * <p>{@code npx} 在本地没装 tsc 时会<b>联网下载</b>。一个语法检查器顺手触发网络安装，
 * 在离网机器上表现为「卡到超时」，在有网机器上是「悄悄改了用户环境」。
 * 所以只在项目里已经存在 {@code tsc} 时才启用，没有就跳过 —— 诊断是增益，不该引入副作用。</p>
 *
 * <h2>为什么 {@link #available()} 恒为 true</h2>
 *
 * <p>「有没有 tsc」是按<b>项目</b>回答的问题，不是按机器。接口里的 {@code available()} 无参，
 * 回答不了；真正的判定放在 {@link #check} 里按文件所在目录向上找。
 * 这里返回 true 不是偷懒 —— 返回 false 会让调用方直接跳过这个 provider，
 * 连带把「这个项目其实装了 tsc」也一起跳掉。</p>
 *
 * <h2>为什么只留 TS1xxx</h2>
 *
 * <p>{@code tsc} 的错误码有语义：{@code TS1xxx} 是语法/解析错误，{@code TS2xxx} 是类型检查。
 * 没有 {@code tsconfig} 时，缺 {@code lib}、缺 {@code @types} 会产生成片 TS2xxx，
 * 和「代码写错」混在一起分不出来。只报 TS1xxx，与 {@link JavacSyntaxProvider} 的口径一致。</p>
 */
@Slf4j
@Component
public class TypeScriptSyntaxProvider extends ProcessDiagnosticProvider {

    /** 形如 {@code src/foo.ts(12,5): error TS1005: ';' expected.} */
    private static final Pattern DIAG_LINE =
            Pattern.compile("^(.*\\.(?:ts|tsx))\\((\\d+),(\\d+)\\):\\s*error\\s+(TS\\d+):\\s*(.*)$");

    @Override
    public String id() {
        return "typescript";
    }

    @Override
    public boolean supports(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ts") || name.endsWith(".tsx");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<Diagnostic> check(Path file, Duration budget) {
        // 按文件所在项目重判：monorepo 里 tsc 常装在仓库根，需要向上找。
        if (locateTsc(file) == null) {
            return List.of();
        }
        return super.check(file, budget);
    }

    /** 从文件所在目录向上找 {@code node_modules/.bin/tsc}，最多上溯 8 层。 */
    static Path locateTsc(Path file) {
        if (file == null) {
            return null;
        }
        Path dir = file.toAbsolutePath().getParent();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String bin = windows ? "tsc.cmd" : "tsc";
        for (int depth = 0; dir != null && depth < 8; depth++) {
            Path candidate = dir.resolve("node_modules").resolve(".bin").resolve(bin);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    @Override
    protected List<String> command(Path file, Path scratchDir) {
        Path tsc = locateTsc(file);
        if (tsc == null) {
            throw new IllegalStateException("项目内没有 node_modules/.bin/tsc");
        }
        return List.of(
                tsc.toAbsolutePath().toString(),
                "--noEmit",
                "--pretty", "false",
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
            String code = m.group(4);
            if (!code.startsWith("TS1")) {
                continue;
            }
            String message = m.group(5);
            if (isDependencyNoise(message)) {
                continue;
            }
            int lineNo;
            try {
                lineNo = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                lineNo = 0;
            }
            all.add(new Diagnostic(file, lineNo, "Error", code + ": " + message));
        }
        return cap(all);
    }
}
