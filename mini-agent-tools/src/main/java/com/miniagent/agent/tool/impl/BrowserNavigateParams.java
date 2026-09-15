package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * browser_navigate 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserNavigateParams extends ToolParams {

    @ToolParamSchema(description = "要打开的网页地址", required = true)
    private String url;

    @ToolParamSchema(description = "浏览器会话ID（可选，默认default）")
    private String sessionId;

    /**
     * 获取会话ID，默认"default"
     */
    public String getSessionIdOrDefault() {
        return sessionId != null && !sessionId.isEmpty() ? sessionId : "default";
    }
}