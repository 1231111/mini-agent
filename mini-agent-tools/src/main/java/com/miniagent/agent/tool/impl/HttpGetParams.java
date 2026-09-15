package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * http_get 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HttpGetParams extends ToolParams {

    @ToolParamSchema(description = "目标 URL", required = true)
    private String url;
}