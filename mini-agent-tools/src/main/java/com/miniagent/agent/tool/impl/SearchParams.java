package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * search_code 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SearchParams extends ToolParams {

    @ToolParamSchema(description = "RE2 正则表达式（最多512字符，不支持回溯引用/前后向断言）", required = true)
    private String pattern;

    @ToolParamSchema(description = "搜索目录或文件，默认项目根目录")
    private String path;

    @ToolParamSchema(description = "文件名过滤，如 *.java、*.{ts,tsx}")
    private String glob;

    @ToolParamSchema(description = "最大匹配条数，默认100，允许1到100；返回结果同时受上下文字数预算限制", defaultValue = "100")
    private Integer maxResults;

    /**
     * 获取最大结果数，默认100
     */
    public int getMaxResultsOrDefault() {
        return maxResults != null ? maxResults : 100;
    }
}
