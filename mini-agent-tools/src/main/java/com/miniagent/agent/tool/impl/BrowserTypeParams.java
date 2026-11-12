package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * browser_type 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserTypeParams extends ToolParams {

    @ToolParamSchema(description = "要输入的文字", required = true)
    private String text;

    @ToolParamSchema(description = "输入框的最新快照完整 ref（如 b12.3）或唯一 placeholder/label")
    private String ref;

    @ToolParamSchema(description = "定位策略")
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