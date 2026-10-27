package com.miniagent.agent.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具结果里的 {@code url} 字段：模型靠它才知道图怎么显示。
 *
 * <p>挡的回归：{@code write_file} 只回 {@code path}（{@code C:/.../workspace/x.svg}），
 * 模型就自己编一个相对路径写进 markdown；工作区没有静态资源映射，前端也不认，
 * 结果是"文件生成了但对话里什么都没有"（2026-10-22 实证）。</p>
 */
class WorkspaceMediaUrlTest {

    @TempDir
    Path workspace;

    @AfterEach
    void clearOverride() {
        WorkspaceContext.clearAll();
    }

    @Test
    void imageAtWorkspaceRootGetsAUrl() {
        WorkspaceContext.setRootOverride(workspace);

        assertEquals("/api/workspace-media/cyberpunk_city_night.svg",
                BuiltinTools.workspaceMediaUrl(workspace.resolve("cyberpunk_city_night.svg")));
        assertEquals("/api/workspace-media/cover.png",
                BuiltinTools.workspaceMediaUrl(workspace.resolve("cover.png")));
    }

    @Test
    void nonImageAndNestedFilesGetNoUrl() {
        WorkspaceContext.setRootOverride(workspace);

        assertNull(BuiltinTools.workspaceMediaUrl(workspace.resolve("design.md")),
                "文本文件不进对话区，不该给看图 URL");
        assertNull(BuiltinTools.workspaceMediaUrl(workspace.resolve("sub/inner.svg")),
                "子目录文件端点读不到，给了 URL 只会让模型拼错");
        assertNull(BuiltinTools.workspaceMediaUrl(workspace.getParent().resolve("outside.svg")));
    }

    /** 中文文件名要百分号编码：直接塞进 URL 会被浏览器截断在第一个非 ASCII 字节。 */
    @Test
    void chineseFilenameIsPercentEncoded() {
        WorkspaceContext.setRootOverride(workspace);

        String url = BuiltinTools.workspaceMediaUrl(workspace.resolve("秋日不打烊-封面.svg"));

        assertNotNull(url);
        assertTrue(url.startsWith("/api/workspace-media/%E7%A7%8B"), "首字符必须已编码: " + url);
        assertTrue(url.endsWith(".svg"));
        assertTrue(url.chars().allMatch(c -> c < 0x80), "URL 里不能残留原始中文: " + url);
    }

    /** 没有子 Agent 覆盖时走默认工作区根，同样要给 URL（主会话的常规路径）。 */
    @Test
    void defaultWorkspaceRootAlsoProducesAUrl() {
        WorkspaceContext.clearAll();

        assertEquals("/api/workspace-media/x.svg",
                BuiltinTools.workspaceMediaUrl(BuiltinTools.effectiveWorkspaceRoot().resolve("x.svg")));
    }

    /**
     * 会话绑定了自己的工作目录时，URL 必须带上会话标识。
     *
     * <p>否则端点只会去 {@code {dataDir}/workspace} 下找文件 —— 文件明明写在用户选的项目目录里，
     * 页面却是裂图，而且报错信息（404）看起来像"图片没生成"。</p>
     */
    @Test
    void overriddenRootAppendsSessionId() {
        WorkspaceContext.setRootOverride(workspace);
        WorkspaceContext.setSessionOverride("s_abc123");

        assertEquals("/api/workspace-media/cover.png?sid=s_abc123",
                BuiltinTools.workspaceMediaUrl(workspace.resolve("cover.png")));
    }

    /** 没有覆盖工作区时不加参数：保持既有 URL 形态，避免给所有媒体地址添噪。 */
    @Test
    void defaultRootDoesNotAppendSessionId() {
        WorkspaceContext.clearAll();
        WorkspaceContext.setSessionOverride("s_abc123");

        assertEquals("/api/workspace-media/x.svg",
                BuiltinTools.workspaceMediaUrl(BuiltinTools.effectiveWorkspaceRoot().resolve("x.svg")));
    }

    /** 会话 id 里的特殊字符要编码，不能让模型拼出的地址被截断。 */
    @Test
    void sessionIdIsPercentEncoded() {
        WorkspaceContext.setRootOverride(workspace);
        WorkspaceContext.setSessionOverride("s a&b");

        String url = BuiltinTools.workspaceMediaUrl(workspace.resolve("cover.png"));

        assertEquals("/api/workspace-media/cover.png?sid=s%20a%26b", url);
    }
}
