package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data @EqualsAndHashCode(callSuper = true)
public class BrowserFormInspectParams extends ToolParams {
    @ToolParamSchema(description = "兼容字段，通常省略；仅允许当前聊天ID")
    private String sessionId;
}
