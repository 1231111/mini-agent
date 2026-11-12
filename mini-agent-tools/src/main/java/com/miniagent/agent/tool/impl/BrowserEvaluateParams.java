package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * browser_evaluate 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserEvaluateParams extends ToolParams {

    @ToolParamSchema(description = "JavaScript代码", required = true)
    private String script;

    @ToolParamSchema(description = "兼容字段，通常省略；仅允许当前聊天ID，不能指定其他会话")
    private String sessionId;

    /**
     * 获取会话ID，默认"default"
     */
    public String getSessionIdOrDefault() {
        return sessionId != null && !sessionId.isEmpty() ? sessionId : "default";
    }
}