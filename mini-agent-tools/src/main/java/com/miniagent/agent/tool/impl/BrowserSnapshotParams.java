package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * browser_snapshot 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserSnapshotParams extends ToolParams {

    @ToolParamSchema(description = "是否获取完整深度快照", defaultValue = "false")
    private Boolean full;

    @ToolParamSchema(description = "浏览器会话ID")
    private String sessionId;

    /**
     * 是否获取完整快照，默认false
     */
    public boolean isFullOrDefault() {
        return full != null ? full : false;
    }

    /**
     * 获取会话ID，默认"default"
     */
    public String getSessionIdOrDefault() {
        return sessionId != null && !sessionId.isEmpty() ? sessionId : "default";
    }
}