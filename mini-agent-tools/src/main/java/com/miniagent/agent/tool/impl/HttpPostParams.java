package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * http_post 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HttpPostParams extends ToolParams {

    @ToolParamSchema(description = "目标 URL", required = true)
    private String url;

    @ToolParamSchema(description = "请求体，默认空")
    private String body;

    @ToolParamSchema(description = "Content-Type，默认 application/json; charset=utf-8")
    private String contentType;

    /**
     * 获取Content-Type，默认 application/json; charset=utf-8
     */
    public String getContentTypeOrDefault() {
        return contentType != null && !contentType.isEmpty() ? contentType : "application/json; charset=utf-8";
    }
}