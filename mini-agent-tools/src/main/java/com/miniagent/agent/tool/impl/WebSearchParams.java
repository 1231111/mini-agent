package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * web_search 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WebSearchParams extends ToolParams {

    @ToolParamSchema(description = "搜索关键词", required = true)
    private String query;

    @ToolParamSchema(description = "返回结果数量（默认5，最大10）", defaultValue = "5")
    private Integer limit;

    /**
     * 获取结果数量限制，默认5，最大10
     */
    public int getLimitOrDefault() {
        if (limit == null) {
            return 5;
        }
        return Math.min(limit, 10);
    }
}