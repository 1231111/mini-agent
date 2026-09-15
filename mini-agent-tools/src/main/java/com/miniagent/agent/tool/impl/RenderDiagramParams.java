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

    @ToolParamSchema(description = "输出路径，如 architecture.png；默认 diagram.png",
            defaultValue = "diagram.png")
    private String path;
}
