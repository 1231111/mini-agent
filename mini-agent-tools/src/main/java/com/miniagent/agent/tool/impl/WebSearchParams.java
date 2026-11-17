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

    @ToolParamSchema(description = "网页发布日期起始日（含），YYYY-MM-DD；仅用户限定新闻/网页的发布时间时传入，相对日期按上下文时区换算。电影上线、航班出发等事件日期放 query，不以此过滤网页")
    private String dateFrom;

    @ToolParamSchema(description = "网页发布日期结束日（含），YYYY-MM-DD；仅用户限定新闻/网页的发布时间时传入，单日与 dateFrom 相同。不是电影上线或航班出发等事件日期")
    private String dateTo;

    @ToolParamSchema(description = "是否一并检索图片候选（默认 false）。查询电影海报、音乐封面等需要真实图片时设 true；data.images 是独立候选，须核对作品、版本及图片描述，不能按顺序匹配网页结果", defaultValue = "false")
    private Boolean includeImages;

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
