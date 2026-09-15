package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * browser_scroll 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BrowserScrollParams extends ToolParams {

    @ToolParamSchema(description = "滚动方向：down/up/left/right", required = true)
    private String direction;

    @ToolParamSchema(description = "滚动距离（像素）", defaultValue = "500")
    private Integer distance;

    @ToolParamSchema(description = "浏览器会话ID")
    private String sessionId;

    /**
     * 获取滚动距离，默认500
     */
    public int getDistanceOrDefault() {
        return distance != null ? distance : 500;
    }

    /**
     * 获取会话ID，默认"default"
     */
    public String getSessionIdOrDefault() {
        return sessionId != null && !sessionId.isEmpty() ? sessionId : "default";
    }
}