package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class InspectImageParams extends ToolParams {
    @ToolParamSchema(description = "当前 workspace 内 PNG/JPEG 图片的相对路径；不接受 workspace 外路径或符号链接", required = true)
    private String path;
}
