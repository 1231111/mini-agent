package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Explicit reset is separate from the read-only-compatible browser_close. */
@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserClearSessionParams extends ToolParams {
    @ToolParamSchema(description = "兼容字段，通常省略；仅允许当前聊天ID，不能指定其他会话")
    private String sessionId;
}
