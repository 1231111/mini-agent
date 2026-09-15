package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * web_extract 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WebExtractParams extends ToolParams {

    @ToolParamSchema(description = "要抓取的网页 URL", required = true)
    private String url;
}