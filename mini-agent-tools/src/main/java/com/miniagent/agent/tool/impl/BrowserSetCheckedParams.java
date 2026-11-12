package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserSetCheckedParams extends ToolParams {
    @ToolParamSchema(description = "最新表单或快照中的完整复选框/单选框 ref", required = true)
    private String ref;
    @ToolParamSchema(description = "明确的目标勾选状态：true勾选，false取消；radio只能通过选同组其他项取消", required = true)
    private Boolean checked;
    @ToolParamSchema(description = "兼容字段，通常省略；仅允许当前聊天ID")
    private String sessionId;
}
