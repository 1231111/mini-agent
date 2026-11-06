package com.miniagent.agent.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代码诊断反馈回路的契约测试。
 *
 * <h2>钉住的三件事</h2>
 *
 * <ol>
 *   <li><b>噪声必须被过滤</b>：单文件编译没有 classpath，{@code cannot find symbol} 是必然结果。
 *       全量回传会让模型在海量假错里捞那一两条真错 —— 比不给反馈更糟。</li>
 *   <li><b>没有内容时必须沉默</b>：返回「没有错误」这种占位文本会白烧 token，
 *       而且每轮重复出现会让模型逐渐忽略这类系统消息。</li>
 *   <li><b>预算必须真的生效</b>：诊断是每轮的常驻内容，一旦某个文件批量报错，
 *       不截断就会挤掉正常对话的量。</li>
 * </ol>
 */
class DiagnosticFeedbackTest {

    private static final Path JAVA_FILE = Path.of("src/main/java/Example.java");

    /**
     * 收集器只检查真实存在的文件（{@code Files.isRegularFile} 守卫：文件被删了不该去编译）。
     * 所以凡是要走完整 {@code consumeReminder} 的用例，都必须用真实文件。
     */
    @TempDir
    Path tmp;

    private Path realJavaFile(String name) throws IOException {
        Path file = tmp.resolve(name);
        Files.writeString(file, "class A {}", StandardCharsets.UTF_8);
        return file;
    }

    // ==================== javac 输出解析 ====================

    @Test
    void javac语法错保留() {
        String raw = """
                src/main/java/Example.java:12: error: ';' expected
                    int x = 1
                             ^
                1 error
                """;
        List<Diagnostic> found = new JavacSyntaxProvider().parse(JAVA_FILE, raw);

        assertEquals(1, found.size());
        assertEquals(12, found.get(0).line());
        assertEquals("Error", found.get(0).severity());
        assertTrue(found.get(0).message().contains("';' expected"));
    }

    @Test
    void javac依赖缺失噪声被全部过滤掉() {
        String raw = """
                src/main/java/Example.java:3: error: package org.springframework does not exist
                import org.springframework.stereotype.Component;
                src/main/java/Example.java:9: error: cannot find symbol
                    private UserService service;
                    ^
                  symbol:   class UserService
                3 errors
                """;
        List<Diagnostic> found = new JavacSyntaxProvider().parse(JAVA_FILE, raw);

        assertTrue(found.isEmpty(),
                "全是依赖缺失，应当一条都不回传（否则模型会被两百行 cannot find symbol 淹没）：" + found);
    }

    @Test
    void javac语法错与依赖噪声混排时只留语法错() {
        String raw = """
                src/main/java/Example.java:3: error: package org.springframework does not exist
                src/main/java/Example.java:7: error: '}' expected
                src/main/java/Example.java:9: error: cannot find symbol
                """;
        List<Diagnostic> found = new JavacSyntaxProvider().parse(JAVA_FILE, raw);

        assertEquals(1, found.size(), "只应留下那一条语法错：" + found);
        assertEquals(7, found.get(0).line());
    }

    @Test
    void javac空输出返回空() {
        assertTrue(new JavacSyntaxProvider().parse(JAVA_FILE, "").isEmpty());
        assertTrue(new JavacSyntaxProvider().parse(JAVA_FILE, "Note: some warnings\n1 warning\n").isEmpty());
    }

    // ==================== python 输出解析 ====================

    @Test
    void python语法错误提取行号与消息() {
        String raw = """
                  File "script.py", line 5
                    def f(
                          ^
                SyntaxError: '(' was never closed
                """;
        List<Diagnostic> found = new PythonSyntaxProvider().parse(Path.of("script.py"), raw);

        assertEquals(1, found.size());
        assertEquals(5, found.get(0).line());
        assertTrue(found.get(0).message().contains("never closed"), found.get(0).message());
    }

    @Test
    void python无错时不产生诊断() {
        assertTrue(new PythonSyntaxProvider().parse(Path.of("ok.py"), "").isEmpty());
    }

    // ==================== 数量上限 ====================

    @Test
    void 超过上限时截断并注明剩余数量() {
        StringBuilder raw = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            raw.append("src/main/java/Example.java:").append(i).append(": error: bad thing ").append(i).append('\n');
        }
        List<Diagnostic> found = new JavacSyntaxProvider().parse(JAVA_FILE, raw.toString());

        // 20 条保留 + 1 条汇总
        assertEquals(ProcessDiagnosticProvider.MAX_DIAGNOSTICS + 1, found.size());
        Diagnostic last = found.get(found.size() - 1);
        assertTrue(last.message().contains("另有 10 条"), last.message());
    }

    // ==================== 收集器 ====================

    @Test
    void 没有待检查文件时返回null() {
        DiagnosticCollector collector = collectorWith(new StubProvider("stub", List.of()));

        assertNull(collector.consumeReminder(),
                "空队列必须返回 null —— 调用方靠 null 决定「不注入任何内容」");
    }

    @Test
    void 关掉开关后不检查也不注入() throws IOException {
        Path file = realJavaFile("Example.java");
        CodingDiagnosticsProperties props = new CodingDiagnosticsProperties();
        props.setEnabled(false);
        DiagnosticCollector collector = new DiagnosticCollector(props,
                List.of(new StubProvider("stub",
                        List.of(new Diagnostic(file, 1, "Error", "boom")))));

        collector.registerChanged(file);
        assertEquals(0, collector.pendingCount(), "关闭后不应登记");
        assertNull(collector.consumeReminder());
    }

    @Test
    void 检查通过时返回null而不是提示没有错误() throws IOException {
        DiagnosticCollector collector = collectorWith(new StubProvider("stub", List.of()));
        collector.registerChanged(realJavaFile("Example.java"));

        assertNull(collector.consumeReminder(),
                "没有发现问题时必须沉默；返回「未发现错误」会白烧 token 并训练模型忽略这类消息");
    }

    @Test
    void 发现问题时注入文本包含定位与关键限定词() throws IOException {
        Path file = realJavaFile("Example.java");
        DiagnosticCollector collector = collectorWith(new StubProvider("stub",
                List.of(new Diagnostic(file, 12, "Error", "';' expected"))));
        collector.registerChanged(file);

        String reminder = collector.consumeReminder();
        assertNotNull(reminder);
        assertTrue(reminder.contains("Example.java:12"), "必须带文件名与行号，否则模型找不到地方：" + reminder);
        assertTrue(reminder.contains("只查语法"), "必须写明只做了语法检查，否则模型会误以为类型也验过了：" + reminder);
    }

    @Test
    void 预算超限时截断并说明还有多少未展示() throws IOException {
        Path file = realJavaFile("Example.java");
        CodingDiagnosticsProperties props = new CodingDiagnosticsProperties();
        props.setMaxBudgetChars(300);
        // 只改预算时必须同时声明 provider 列表：providerFor 按 id 在配置白名单里查，
        // 默认列表是 java/typescript/python，测试替身不在里面就会被整体跳过。
        props.setProviders(List.of("stub"));
        DiagnosticCollector collector = new DiagnosticCollector(props,
                List.of(new StubProvider("stub", manyDiagnostics(file, 50))));

        collector.registerChanged(file);
        String reminder = collector.consumeReminder();

        assertNotNull(reminder);
        assertTrue(reminder.contains("未展示"), "超预算必须说明还有内容没展示：" + reminder);
        assertTrue(reminder.length() < 1200, "注入文本必须受预算约束，实际长度 " + reminder.length());
    }

    @Test
    void 非源码扩展名被跳过() throws IOException {
        DiagnosticCollector collector = collectorWith(new StubProvider("stub",
                List.of(new Diagnostic(JAVA_FILE, 1, "Error", "boom"))));
        collector.registerChanged(tmp.resolve("README.md"));

        assertNull(collector.consumeReminder(), "没有 provider 支持 .md，应当不产生任何注入");
    }

    @Test
    void 同一文件多次登记只检查一次() throws IOException {
        CountingProvider counting = new CountingProvider();
        DiagnosticCollector collector = collectorWith(counting);
        Path file = realJavaFile("Example.java");

        collector.registerChanged(file);
        collector.registerChanged(file);
        collector.registerChanged(file);
        assertEquals(1, collector.pendingCount(), "连续编辑同一文件只应排一次检查");

        collector.consumeReminder();
        assertEquals(1, counting.calls, "只应真正检查一次（检查的是轮末最终状态）");
    }

    @Test
    void clear后不再检查() throws IOException {
        Path file = realJavaFile("Example.java");
        DiagnosticCollector collector = collectorWith(new StubProvider("stub",
                List.of(new Diagnostic(file, 1, "Error", "boom"))));
        collector.registerChanged(file);
        collector.clear();

        assertNull(collector.consumeReminder());
    }

    @Test
    void 文件已被删除时跳过检查() {
        DiagnosticCollector collector = collectorWith(new StubProvider("stub",
                List.of(new Diagnostic(JAVA_FILE, 1, "Error", "boom"))));
        collector.registerChanged(tmp.resolve("gone.java"));

        assertNull(collector.consumeReminder(),
                "文件已经不在了就不该去编译它 —— 那只会在日志里留一堆无意义的失败");
    }

    // ==================== 真跑一次编译器 ====================

    /**
     * 端到端：真的起 {@code javac} 检查一个有语法错误的文件。
     *
     * <p>只测 {@code parse} 是不够的 —— 命令拼装、工作目录、输出重定向、超时任意一处错了，
     * {@code parse} 单测全绿而实际功能为零。这条用例覆盖那条真实路径。</p>
     *
     * <p>环境没有 {@code javac} 时跳过（桌面端精简 JRE 就是这种情况），
     * 用 {@code assumeTrue} 而不是断言：缺编译器不是被测代码的缺陷。</p>
     */
    @Test
    void 真起javac能抓到语法错误() throws IOException {
        JavacSyntaxProvider provider = new JavacSyntaxProvider();
        org.junit.jupiter.api.Assumptions.assumeTrue(provider.available(),
                "当前环境没有 javac，跳过真实编译验证");

        Path broken = tmp.resolve("Broken.java");
        Files.writeString(broken, """
                public class Broken {
                    public void run() {
                        int x = 1
                    }
                }
                """, StandardCharsets.UTF_8);

        List<Diagnostic> found = provider.check(broken, Duration.ofSeconds(20));

        assertFalse(found.isEmpty(), "缺一个分号，javac 必须报出来；返回空说明命令拼装或解析有问题");
        assertTrue(found.get(0).line() > 0, "行号必须能定位到出问题的位置：" + found.get(0));
        assertTrue(found.stream().anyMatch(d -> d.message().contains("';'")),
                "期望是「缺少分号」类语法错，实际：" + found);
    }

    /**
     * 反向用例：语法正确的文件不能产生诊断。
     *
     * <p>没有这条，一个「永远返回至少一条」的实现也能通过上面那条测试。</p>
     */
    @Test
    void 真起javac对正确代码返回空() throws IOException {
        JavacSyntaxProvider provider = new JavacSyntaxProvider();
        org.junit.jupiter.api.Assumptions.assumeTrue(provider.available(),
                "当前环境没有 javac，跳过真实编译验证");

        Path ok = tmp.resolve("Ok.java");
        Files.writeString(ok, """
                public class Ok {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """, StandardCharsets.UTF_8);

        assertTrue(provider.check(ok, Duration.ofSeconds(20)).isEmpty(),
                "正确代码不应产生任何诊断");
    }

    /**
     * 关键行为：只缺依赖、代码本身没语法错时，必须返回空而不是刷一屏 {@code cannot find symbol}。
     *
     * <p>这是整个过滤设计的核心场景 —— 改一个依赖 Spring 的文件时，单文件编译必然找不到那些类。</p>
     */
    @Test
    void 真起javac对仅缺依赖的文件返回空() throws IOException {
        JavacSyntaxProvider provider = new JavacSyntaxProvider();
        org.junit.jupiter.api.Assumptions.assumeTrue(provider.available(),
                "当前环境没有 javac，跳过真实编译验证");

        Path dep = tmp.resolve("Dep.java");
        Files.writeString(dep, """
                import org.springframework.stereotype.Component;
                import com.example.NotOnClasspath;

                public class Dep {
                    private NotOnClasspath field;
                }
                """, StandardCharsets.UTF_8);

        List<Diagnostic> found = provider.check(dep, Duration.ofSeconds(20));

        assertTrue(found.isEmpty(),
                "语法没错、只缺依赖，应当一条都不回传（否则模型被噪声淹没）：" + found);
    }

    // ==================== 测试替身 ====================

    private static DiagnosticCollector collectorWith(DiagnosticProvider provider) {
        CodingDiagnosticsProperties props = new CodingDiagnosticsProperties();
        props.setProviders(List.of(provider.id()));
        return new DiagnosticCollector(props, List.of(provider));
    }

    private static List<Diagnostic> manyDiagnostics(Path file, int n) {
        return java.util.stream.IntStream.rangeClosed(1, n)
                .mapToObj(i -> new Diagnostic(file, i, "Error",
                        "something went wrong on line " + i + " with a fairly long message to eat budget"))
                .toList();
    }

    private record StubProvider(String id, List<Diagnostic> result) implements DiagnosticProvider {

        @Override
        public boolean supports(Path file) {
            return file != null && file.toString().endsWith(".java");
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<Diagnostic> check(Path file, Duration budget) {
            return result;
        }
    }

    private static final class CountingProvider implements DiagnosticProvider {
        int calls;

        @Override
        public String id() {
            return "counting";
        }

        @Override
        public boolean supports(Path file) {
            return true;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<Diagnostic> check(Path file, Duration budget) {
            calls++;
            return List.of();
        }
    }
}
