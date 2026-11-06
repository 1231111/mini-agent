package com.miniagent.agent.tool;

import com.miniagent.agent.tool.impl.WriteFileParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code write_file} 覆盖一致性契约。
 *
 * <h2>被钉住的那条数据损坏路径</h2>
 *
 * <p>改造前 {@code write_file(mode=overwrite)} 无条件覆盖，唯一拦截是「短文本盖大文件」。
 * 于是：模型 read_file 拿到内容 → 中间人（用户手动编辑 / git 操作 / 其他进程）改了同一个文件
 * → 模型基于旧内容写回 → <b>别人的改动被静默回滚，没有任何报错</b>。</p>
 *
 * <p>这里用「先 read_file，再绕过工具直接改文件」复现"中间人"，然后要求覆盖被拒。
 * 之所以必须绕过工具直接改：走 {@code write_file} 会更新读观察账本，那就测不出问题了。</p>
 */
class WriteConsistencyTest {

    @TempDir
    Path tempRoot;

    private BuiltinTools tools;

    @BeforeEach
    void setUp() {
        // 生效工作区决定 PathGuard 的允许根，也决定相对路径解析到哪。
        WorkspaceContext.setRootOverride(tempRoot);
        WorkspaceContext.clearTaskOverride();
        tools = new BuiltinTools();
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clearAll();
    }

    // ==================== 调用封装：这三个方法都是 private，走反射 ====================

    private String readFile(String path, int offset, int limit) throws Exception {
        Method m = BuiltinTools.class.getDeclaredMethod("readFile", String.class, int.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(tools, path, offset, limit);
    }

    private String readWhole(String path) throws Exception {
        // limit 给足，保证走到 EOF 而不是被行数上限截断 —— 否则会被判定成 partial。
        return readFile(path, 1, 100_000);
    }

    private String writeFile(String path, String content, boolean append, boolean force) throws Exception {
        Method m = BuiltinTools.class.getDeclaredMethod(
                "writeFile", String.class, String.class, boolean.class, boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(tools, path, content, append, force);
    }

    private String editFile(String path, String oldStr, String newStr) throws Exception {
        Method m = BuiltinTools.class.getDeclaredMethod(
                "editFile", String.class, String.class, String.class, boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(tools, path, oldStr, newStr, false);
    }

    private static void assertConflict(String result, String expectFragment) {
        assertNotNull(result);
        assertTrue(result.contains("\"errorCode\":\"CONFLICT\""),
                "应当返回 CONFLICT，实际为：" + result);
        assertTrue(result.contains(expectFragment),
                "错误文案应包含「" + expectFragment + "」，实际为：" + result);
    }

    // ==================== 用例 ====================

    @Test
    void 覆盖未读取过的已有文件被拒() throws Exception {
        Files.writeString(tempRoot.resolve("plan.md"), "用户手写的规划", StandardCharsets.UTF_8);

        String result = writeFile("plan.md", "模型凭想象写的内容", false, false);

        assertConflict(result, "未读取过");
        // 文件必须原样保留 —— 拒绝之后不能有任何部分写入。
        assertEquals("用户手写的规划",
                Files.readString(tempRoot.resolve("plan.md"), StandardCharsets.UTF_8));
    }

    @Test
    void 读取后覆盖同一版本允许() throws Exception {
        Files.writeString(tempRoot.resolve("notes.md"), "原始内容", StandardCharsets.UTF_8);

        String read = readWhole("notes.md");
        assertTrue(read.contains("原始内容"), "读取结果应包含原文，实际：" + read);

        String result = writeFile("notes.md", "模型基于原文重写", false, false);

        assertTrue(result.contains("\"success\":true"), "读后写应放行，实际：" + result);
        assertEquals("模型基于原文重写",
                Files.readString(tempRoot.resolve("notes.md"), StandardCharsets.UTF_8));
    }

    @Test
    void 读取后文件被外部修改则拒绝覆盖() throws Exception {
        Path target = tempRoot.resolve("config.yml");
        Files.writeString(target, "timeout: 30", StandardCharsets.UTF_8);

        readWhole("config.yml");

        // 中间人：用户手动编辑 / git checkout / 另一个进程。绕过工具，账本感知不到。
        Files.writeString(target, "timeout: 30\nretries: 3", StandardCharsets.UTF_8);

        String result = writeFile("config.yml", "timeout: 60", false, false);

        assertConflict(result, "已被修改");
        assertEquals("timeout: 30\nretries: 3",
                Files.readString(target, StandardCharsets.UTF_8),
                "覆盖被拒后文件必须保持中间人写入的版本");
    }

    @Test
    void 只读了片段不允许整文件覆盖() throws Exception {
        Path target = tempRoot.resolve("big.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("line ").append(i).append('\n');
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);

        readFile("big.txt", 1, 10);   // 只看前 10 行

        String result = writeFile("big.txt", "整文件替换", false, false);

        assertConflict(result, "只读取了它的一部分");
    }

    @Test
    void 从中间行开始读也算片段() throws Exception {
        Path target = tempRoot.resolve("mid.txt");
        Files.writeString(target, "a\nb\nc\nd\n", StandardCharsets.UTF_8);

        readFile("mid.txt", 3, 100);  // 从第 3 行读到结尾，但不是从头

        String result = writeFile("mid.txt", "整文件替换", false, false);

        assertConflict(result, "只读取了它的一部分");
    }

    @Test
    void force可显式覆盖未读文件() throws Exception {
        Path target = tempRoot.resolve("forced.md");
        Files.writeString(target, "旧内容", StandardCharsets.UTF_8);

        String result = writeFile("forced.md", "用户明确要求覆盖", false, true);

        assertTrue(result.contains("\"success\":true"), "force=true 应放行，实际：" + result);
        assertEquals("用户明确要求覆盖", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void 连续多次编辑不被自己的写入误拦() throws Exception {
        Path target = tempRoot.resolve("multi.java");
        Files.writeString(target, "class A {\n  int x = 1;\n  int y = 2;\n}\n", StandardCharsets.UTF_8);

        readWhole("multi.java");

        String first = editFile("multi.java", "int x = 1;", "int x = 10;");
        assertTrue(first.contains("\"success\":true"), "第一次编辑应成功，实际：" + first);

        // 第二次编辑必须仍然放行：第一次 edit 更新了账本，而不是让观察过期。
        String second = editFile("multi.java", "int y = 2;", "int y = 20;");
        assertTrue(second.contains("\"success\":true"),
                "连续第二次编辑被自己的上一次写入拦住了，实际：" + second);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("int x = 10;") && content.contains("int y = 20;"),
                "两处编辑都应落盘，实际：" + content);
    }

    @Test
    void 锚点被外部改掉后编辑失败() throws Exception {
        Path target = tempRoot.resolve("anchor.txt");
        Files.writeString(target, "alpha\nbeta\n", StandardCharsets.UTF_8);
        readWhole("anchor.txt");

        Files.writeString(target, "alpha\ngamma\n", StandardCharsets.UTF_8);

        String result = editFile("anchor.txt", "beta", "BETA");
        assertTrue(result.contains("\"error\""), "锚点已不存在，应当失败，实际：" + result);
        assertEquals("alpha\ngamma\n", Files.readString(target, StandardCharsets.UTF_8));
    }

    /**
     * {@code edit_file} 不做整文件校验，但它的<b>损失面为零</b>：
     * 它只替换锚点那一段，不重发整个文件，所以锚点之外的外部改动不可能被它覆盖掉。
     *
     * <p>这条用例把这个边界钉住 —— 外部加了一行，模型改另一处，外部那行必须还在。
     * 如果哪天有人给 {@code edit_file} 改成「读全文 → 模型回传全文 → 整文件写回」，
     * 这条会立刻红，正好拦住那次退化成 {@code write_file} 的改动。</p>
     */
    @Test
    void 编辑只改锚点不丢外部改动() throws Exception {
        Path target = tempRoot.resolve("partial.txt");
        Files.writeString(target, "keep\ntarget\n", StandardCharsets.UTF_8);
        readWhole("partial.txt");

        // 外部在锚点之外新增一行
        Files.writeString(target, "keep\nexternal addition\ntarget\n", StandardCharsets.UTF_8);

        String result = editFile("partial.txt", "target", "TARGET");
        assertTrue(result.contains("\"success\":true"), "锚点仍在，应放行，实际：" + result);

        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertEquals("keep\nexternal addition\nTARGET\n", content,
                "外部新增的那一行必须保留 —— edit_file 不重发整文件，没有覆盖它的路径");
    }

    @Test
    void 清缓存同时清掉读观察() throws Exception {
        Path target = tempRoot.resolve("reset.md");
        Files.writeString(target, "v1", StandardCharsets.UTF_8);
        readWhole("reset.md");

        // 新用户消息开始时的清理：上一轮看到的内容不再可信。
        tools.clearToolCache();

        String result = writeFile("reset.md", "v2", false, false);
        assertConflict(result, "未读取过");
    }

    /**
     * {@code force} 必须真的出现在给模型看的参数 schema 里。
     *
     * <p>把它从 {@link WriteFileParams} 挪走（比如挪进
     * {@code ToolParams} 基类、或去掉 {@code @ToolParamSchema}）会让它从 schema 里消失：
     * 工具内部照样读得到值，但模型根本不知道有这个参数 —— 于是被拦住之后它<b>无路可走</b>，
     * 只能反复重试同一个写入。这类「实现有、schema 无」的错，只有从这里能拦住。</p>
     */
    @Test
    void force必须出现在参数schema里() {
        var schema = ToolParams.generateSchema(WriteFileParams.class);

        assertTrue(schema.containsKey("force"), "write_file 的 schema 里没有 force，模型无法得知这个逃生门");
        @SuppressWarnings("unchecked")
        var def = (java.util.Map<String, Object>) schema.get("force");
        assertEquals("boolean", def.get("type"), "force 的类型必须是 boolean");
        assertTrue(String.valueOf(def.get("description")).contains("覆盖"),
                "force 的说明里要讲清它是「明确覆盖」，否则模型会习惯性带上它："
                        + def.get("description"));
    }
}
