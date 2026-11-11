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

    @ToolParamSchema(description = "发布日期起始日（含），ISO格式 YYYY-MM-DD；用户问今日/昨天等相对日期时，必须按上下文时区换算后传入")
    private String dateFrom;

    @ToolParamSchema(description = "发布日期结束日（含），ISO格式 YYYY-MM-DD；单日查询时与 dateFrom 相同")
    private String dateTo;

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
