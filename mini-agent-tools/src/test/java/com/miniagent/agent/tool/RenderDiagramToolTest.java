package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code render_diagram} 的清晰度契约。
 *
 * <p>2026-10-27 用户反馈「生成的架构图不清晰、点开放大更糊」。根因是 PNG 一直以
 * mermaid-cli 的默认 {@code -s 1} 出图：40 个节点的架构图固有尺寸只有 520×3001，
 * 每个中文字符落在 3-4px 上。下面这些断言钉住修复，避免以后有人重构命令行时又把它拿掉。</p>
 */
class RenderDiagramToolTest {

    @Test
    void defaultScaleIsThree() {
        assertEquals(3, RenderDiagramTool.resolveScale(null),
                "不传 scale 必须走 3 倍，否则 PNG 又回到 520px 宽");
    }

    /** 0 和负数会被 mermaid-cli 当成非法值，必须夹到合法区间而不是原样传下去。 */
    @Test
    void scaleIsClampedToSupportedRange() {
        assertEquals(1, RenderDiagramTool.resolveScale(0));
        assertEquals(1, RenderDiagramTool.resolveScale(-5));
        assertEquals(4, RenderDiagramTool.resolveScale(99));
        assertEquals(2, RenderDiagramTool.resolveScale(2));
    }

    @Test
    void pngCommandCarriesScale() {
        List<String> cmd = RenderDiagramTool.mmdcCommand(
                "npx", Path.of("in.mmd"), Path.of("out.png"), 3);
        int at = cmd.indexOf("-s");
        assertTrue(at >= 0, "PNG 命令行缺少 -s：" + cmd);
        assertEquals("3", cmd.get(at + 1), "scale 没跟在 -s 后面：" + cmd);
    }

    /** SVG 是矢量，放大倍数对文件内容没有意义，传了只是让 mermaid-cli 多走一次截图。 */
    @Test
    void scaleIsNotPassedForSvg() {
        List<String> cmd = RenderDiagramTool.mmdcCommand(
                "npx", Path.of("in.mmd"), Path.of("out.svg"), 3);
        assertFalse(cmd.contains("-s"), "SVG 不该带 -s：" + cmd);
        assertTrue(cmd.stream().anyMatch(a -> a.endsWith("out.svg")), "输出路径丢了：" + cmd);
    }

    /**
     * 尺寸必须是真实读出来的，不是从参数回填的 —— 520×3001 就是事故现场的尺寸。
     */
    @Test
    void pixelFieldsReportsRealSize(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("probe.png");
        ImageIO.write(new BufferedImage(520, 3001, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        assertEquals(",\"width\":520,\"height\":3001", RenderDiagramTool.pixelFields(png));
    }

    @Test
    void pixelFieldsSkipsVectorOutput(@TempDir Path dir) throws Exception {
        Path svg = dir.resolve("probe.svg");
        Files.writeString(svg, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>", StandardCharsets.UTF_8);
        assertEquals("", RenderDiagramTool.pixelFields(svg), "矢量图没有像素尺寸，不该拼这一段");
    }

    /** 不是图片的文件不能把渲染结果搞成异常：读不到尺寸时静默省略字段。 */
    @Test
    void pixelFieldsToleratesBrokenFile(@TempDir Path dir) throws Exception {
        Path fake = dir.resolve("broken.png");
        Files.write(fake, new byte[] {1, 2, 3});
        assertEquals("", RenderDiagramTool.pixelFields(fake));
    }

    /**
     * mermaid-cli 真实写出来的形态：{@code width="100%"} + viewBox + max-width 内联样式。
     *
     * <p>这种 SVG 作为 img 加载时 Chrome 给 26×150（实测），必须补成 viewBox 尺寸。</p>
     */
    @Test
    void pinsPercentWidthSvgToViewBoxSize() {
        String raw = "<svg id=\"my-svg\" width=\"100%\" xmlns=\"http://www.w3.org/2000/svg\" "
                + "class=\"flowchart\" style=\"max-width: 519.438px; background-color: white;\" "
                + "viewBox=\"4 4 519.4375 3001\"><g/></svg>";
        String pinned = RenderDiagramTool.pinSvgSize(raw);
        assertTrue(pinned.contains("width=\"519.4375\""), "没补上宽度：" + pinned);
        assertTrue(pinned.contains("height=\"3001\""), "没补上高度：" + pinned);
        assertFalse(pinned.contains("width=\"100%\""), "百分比宽度还在：" + pinned);
        assertTrue(pinned.contains("style=\"max-width"), "不该动 style：" + pinned);
        assertTrue(pinned.endsWith("<g/></svg>"), "图形内容被截断了：" + pinned);
    }

    /** 调用方已指定固定像素尺寸就服从它，覆盖属于越界。 */
    @Test
    void leavesExplicitPixelSizeAlone() {
        String raw = "<svg width=\"800\" height=\"600\" viewBox=\"0 0 100 100\"><g/></svg>";
        assertEquals(raw, RenderDiagramTool.pinSvgSize(raw));
    }

    @Test
    void leavesSvgWithoutViewBoxAlone() {
        String raw = "<svg width=\"100%\" height=\"100%\"><g/></svg>";
        assertEquals(raw, RenderDiagramTool.pinSvgSize(raw));
    }

    /** viewBox 里有 0 或非数字时无从推断尺寸，保持原样而不是写出 width="0"。 */
    @Test
    void ignoresUnusableViewBox() {
        String zero = "<svg width=\"100%\" viewBox=\"0 0 0 3001\"><g/></svg>";
        assertEquals(zero, RenderDiagramTool.pinSvgSize(zero));
        String junk = "<svg width=\"100%\" viewBox=\"a b c d\"><g/></svg>";
        assertEquals(junk, RenderDiagramTool.pinSvgSize(junk));
    }
}
