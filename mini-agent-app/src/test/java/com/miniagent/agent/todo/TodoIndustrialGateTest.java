package com.miniagent.agent.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工业门禁回归：双轨验收、blocked、禁止空文件勾选。
 */
class TodoIndustrialGateTest {

    @TempDir
    Path temp;

    TaskTodoStore store;
    String sid = "test-industrial";

    @BeforeEach
    void setUp() {
        store = new TaskTodoStore(temp.resolve("todos").toString());
    }

    @Test
    void emptyFile_rejectsCompleted() throws Exception {
        Path md = temp.resolve("empty.md");
        Files.writeString(md, "");
        store.set(sid, List.of(Map.of(
                "id", 1,
                "content", "写文档",
                "done_when", "file_exists:" + md.toAbsolutePath()
        )));
        String[] err = new String[1];
        var r = store.update(sid, 1, "completed", null, md.toString(), err);
        assertNull(r);
        assertNotNull(err[0]);
        assertTrue(err[0].contains("空") || err[0].contains("过短") || err[0].contains("语义"));
    }

    @Test
    void mdNeedingImage_withoutImageMarkdown_rejects() throws Exception {
        Path md = temp.resolve("doc.md");
        Files.writeString(md, "# 标题\n\n这里只有文字，没有图片引用。\n再补一些长度避免过短校验。\n");
        store.set(sid, List.of(Map.of(
                "id", 1,
                "content", "生成结构图并替换到文档",
                "done_when", "file_exists:" + md.toAbsolutePath()
        )));
        String[] err = new String[1];
        var r = store.update(sid, 1, "completed", null, md.toString(), err);
        assertNull(r);
        assertTrue(err[0].contains("图片") || err[0].contains("markdown"));
    }

    @Test
    void mdWithImageMarkdown_acceptsAndStoresHash() throws Exception {
        Path md = temp.resolve("ok.md");
        Files.writeString(md, "# 结构\n\n![层级图](/static/images/img_1.png)\n\n说明文字足够长以通过长度校验。\n");
        store.set(sid, List.of(Map.of(
                "id", 1,
                "content", "生成结构图并替换到文档",
                "done_when", "file_exists:" + md.toAbsolutePath()
        )));
        String[] err = new String[1];
        var r = store.update(sid, 1, "completed", null, md.toString(), err);
        assertNotNull(r, err[0]);
        assertEquals(TaskTodoStore.Status.completed, r.get(0).status());
        assertFalse(r.get(0).validationHash().isBlank());
    }

    @Test
    void markBlocked_stopsSuccess() {
        store.set(sid, List.of(
                Map.of("id", 1, "content", "步骤A", "done_when", "note_required"),
                Map.of("id", 2, "content", "步骤B", "done_when", "note_required")
        ));
        assertTrue(store.markBlocked(sid, 1, "工具失败 3 次"));
        assertTrue(store.hasBlocked(sid));
        assertFalse(store.canSucceed(sid));
        assertTrue(store.buildBlockedReport(sid).contains("BLOCKED"));
    }

    @Test
    void upstreamEvidence_includesHash() throws Exception {
        Path md = temp.resolve("a.md");
        Files.writeString(md, "# ok\n\n![x](/static/images/a.png)\n足够长度的正文内容用于验收。\n");
        store.set(sid, List.of(
                Map.of("id", 1, "content", "写图文文档", "done_when", "file_exists:" + md.toAbsolutePath()),
                Map.of("id", 2, "content", "下一步", "done_when", "note_required")
        ));
        assertNotNull(store.update(sid, 1, "completed", null, md.toString(), new String[1]));
        String upstream = store.renderUpstreamEvidence(sid);
        assertTrue(upstream.contains("validation_hash="));
        assertTrue(upstream.contains("#1"));
    }

    @Test
    void semanticValidator_mediaNeedsMarkdown() {
        var fail = TodoSemanticValidator.validate("生图", "media_delivered", "已生成");
        assertFalse(fail.ok());
        var ok = TodoSemanticValidator.validate("生图", "media_delivered",
                "![x](/static/images/img.png)");
        assertTrue(ok.ok());
        assertFalse(ok.contentHash().isBlank());
    }
}
