package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * render_diagram 工具参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RenderDiagramParams extends ToolParams {

    @ToolParamSchema(description = "Mermaid 源码，或已有 .mmd/.svg 的相对路径", required = true)
    private String source;

    @ToolParamSchema(description = "输出路径，如 architecture.svg；默认 diagram.png",
            defaultValue = "diagram.png")
    private String path;

    /**
     * PNG 原始像素的放大倍数（对应 mermaid-cli 的 {@code -s}）。
     *
     * <p>不传时用 {@link com.miniagent.agent.tool.RenderDiagramTool#DEFAULT_PNG_SCALE}。
     * 只对 .png 生效 —— SVG 是矢量的，缩放不改变文件内容。</p>
     */
    @ToolParamSchema(description = "PNG 放大倍数 1-4，默认 3。图特别大导致渲染超时时降到 1-2",
            defaultValue = "3")
    private Integer scale;
}
