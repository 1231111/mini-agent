package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserSelectParams extends ToolParams {
    @ToolParamSchema(description = "最新表单或快照中的完整下拉框 ref（如 b12.3）", required = true)
    private String ref;
    @ToolParamSchema(description = "要选择的选项 value 或 label 列表；单选框只传一个，多选框可传多个或空列表清空", required = true)
    private List<String> values;
    @ToolParamSchema(description = "选项匹配方式：value（默认）或 label（精确显示名称）")
    private String by;
    @ToolParamSchema(description = "兼容字段，通常省略；仅允许当前聊天ID")
    private String sessionId;
    public String getByOrDefault() { return by == null || by.isBlank() ? "value" : by; }
}
