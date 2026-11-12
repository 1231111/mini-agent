package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * browser_click 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserClickParams extends ToolParams {

    @ToolParamSchema(description = "最新快照完整 ref（如 b12.3）或明确文本/选择器，含义由 by 决定", required = true)
    private String ref;

    @ToolParamSchema(description = "定位策略：ref=仅编号；text=精确文本；role=button=名称；css=CSS选择器；aria=aria-label")
    private String by;

    @ToolParamSchema(description = "兼容字段，通常省略；仅允许当前聊天ID，不能指定其他会话")
    private String sessionId;

    /**
     * 获取定位策略，默认"ref"
     */
    public String getByOrDefault() {
        return by != null && !by.isEmpty() ? by : "ref";
    }

    /**
     * 获取会话ID，默认"default"
     */
    public String getSessionIdOrDefault() {
        return sessionId != null && !sessionId.isEmpty() ? sessionId : "default";
    }
}