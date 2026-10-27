package com.miniagent.web;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code /api/workspace-media/{filename}} 的放行边界。
 *
 * <p>挡的三个回归：</p>
 * <ol>
 *   <li>模型把图写成 {@code workspace/xxx.svg} 后对话里永远只有裂图 —— 工作区没有静态资源映射，
 *       这个端点是唯一回显通道。</li>
 *   <li>把端点做成"能读工作区任意文件"——工作区里有源码、笔记、配置，只能放开图片这一小类。</li>
 *   <li>用 {@code ../} 或子目录越出工作区根 —— 判据是"父目录必须就是根"，不是字符串前缀比对。</li>
 * </ol>
 */
class WorkspaceMediaAccessTest {

    @TempDir
    Path workspace;

    private Path write(String name, String content) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent() == null ? workspace : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** 核心回归：写成 svg 的图必须能被解析出来，并按 image/svg+xml 下发。 */
    @Test
    void svgAtWorkspaceRootIsServableAsImage() throws IOException {
        Path file = write("cyberpunk_city_night.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");

        Path resolved = MiniAgentChatPageController.resolveWorkspaceMedia(workspace, "cyberpunk_city_night.svg");

        assertNotNull(resolved, "工作区根目录下的 svg 必须能回显，否则降级出图这条路是死的");
        assertEquals(file.toAbsolutePath().normalize(), resolved);
        assertEquals("image/svg+xml",
                MiniAgentChatPageController.approvedInlineMediaType("cyberpunk_city_night.svg", resolved));
    }

    /**
     * svg 不看 {@code Files.probeContentType}：不同 Windows 上 .svg 的注册表映射可能是
     * text/plain 或 application/xml，信探测结果就等于在没装 SVG 关联的机器上随机 415。
     */
    @Test
    void svgTypeIgnoresProbeMismatch() throws IOException {
        Path file = write("plain.svg", "this is definitely not xml");

        assertEquals("image/svg+xml",
                MiniAgentChatPageController.approvedInlineMediaType("plain.svg", file));
    }

    /** 非图片一律不内联：工作区里的 md / java / 配置文本不能借图片端点读出来。 */
    @Test
    void nonImageFilesAreNotInlineable() throws IOException {
        assertNull(MiniAgentChatPageController.approvedInlineMediaType("notes.md", write("notes.md", "# hi")));
        assertNull(MiniAgentChatPageController.approvedInlineMediaType("App.java", write("App.java", "class App{}")));
    }

    /** 子目录不算：端点是扁平的，只有根目录下的文件才回显。 */
    @Test
    void subdirectoryFilesAreRejected() throws IOException {
        write("sub/inner.svg", "<svg/>");

        assertNull(MiniAgentChatPageController.resolveWorkspaceMedia(workspace, "sub/inner.svg"));
    }

    /** 越界写法全部挡住：父目录不等于根目录就 404，隐藏文件也不放出来。 */
    @Test
    void traversalAndHiddenFilesAreRejected() {
        assertNull(MiniAgentChatPageController.resolveWorkspaceMedia(workspace, "../secret.svg"));
        assertNull(MiniAgentChatPageController.resolveWorkspaceMedia(workspace, "..\\secret.svg"));
        assertNull(MiniAgentChatPageController.resolveWorkspaceMedia(workspace, ".env.svg"));
        assertNull(MiniAgentChatPageController.resolveWorkspaceMedia(workspace, "a/b.svg"));
        assertNull(MiniAgentChatPageController.resolveWorkspaceMedia(workspace, ""));
    }

    /** 文件不存在就是 404，不能让上游误以为"类型不合法"。 */
    @Test
    void missingFileResolvesToNull() {
        assertNull(MiniAgentChatPageController.resolveWorkspaceMedia(workspace, "nope.svg"));
    }

    /**
     * 根目录下的符号链接若指向根外面，必须挡住：链接文件的父目录仍然是根，
     * 只有比 realpath 的父目录才看得出来。Windows 建符号链接需要开发者模式/管理员，
     * 建不出来就跳过这条用例（不是把断言放宽）。
     */
    @Test
    void symlinkEscapingTheRootIsRejected() throws IOException {
        Path outside = Files.createTempFile("outside-", ".svg");
        Files.writeString(outside, "<svg/>", StandardCharsets.UTF_8);
        Path link = workspace.resolve("link.svg");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "当前环境不允许创建符号链接: " + e.getMessage());
        }

        assertNull(MiniAgentChatPageController.resolveWorkspaceMedia(workspace, "link.svg"),
                "链接指向工作区外时不能放行");
    }

    /** 中文文件名必须能用：模型常按内容起名（workspace 里现存 {@code 秋日不打烊-封面.svg}）。 */
    @Test
    void chineseFilenameIsAccepted() throws IOException {
        write("秋日不打烊-封面.svg", "<svg/>");

        Path resolved = MiniAgentChatPageController.resolveWorkspaceMedia(workspace, "秋日不打烊-封面.svg");

        assertNotNull(resolved);
        assertEquals("image/svg+xml",
                MiniAgentChatPageController.approvedInlineMediaType("秋日不打烊-封面.svg", resolved));
    }

    /** 原有类型不能被这次改动弄坏。 */
    @Test
    void existingMediaTypesStillWork() throws IOException {
        assertEquals("image/png", MiniAgentChatPageController.approvedInlineMediaType("a.png", write("a.png", "x")));
        assertEquals("audio/mpeg", MiniAgentChatPageController.approvedInlineMediaType("a.mp3", write("a.mp3", "x")));
        assertEquals("video/mp4", MiniAgentChatPageController.approvedInlineMediaType("a.mp4", write("a.mp4", "x")));
        assertNull(MiniAgentChatPageController.approvedInlineMediaType("a.svg.bak", write("a.svg.bak", "x")));
    }
}
