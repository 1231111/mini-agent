package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * browser_press 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserPressParams extends ToolParams {

    @ToolParamSchema(description = "键盘按键（Enter, Tab, Escape, ArrowDown 等）", required = true)
    private String key;

    @ToolParamSchema(description = "浏览器会话ID")
    private String sessionId;

    /**
     * 获取会话ID，默认"default"
     */
    public String getSessionIdOrDefault() {
        return sessionId != null && !sessionId.isEmpty() ? sessionId : "default";
    }
}