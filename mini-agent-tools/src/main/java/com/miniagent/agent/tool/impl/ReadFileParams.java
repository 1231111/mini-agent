package com.miniagent.agent.tool.impl;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * read_file 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReadFileParams extends ToolParams {

    @ToolParamSchema(description = "文件路径", required = true)
    private String path;

    @ToolParamSchema(description = "起始行号（从1开始，默认1）")
    private Integer offset;

    @ToolParamSchema(description = "最大行数（默认200，允许范围1到200）；单次读取正文总量上限为8000字符")
    private Integer limit;

    @JsonAlias("char_offset")
    @ToolParamSchema(name = "char_offset", description = "行内起始Unicode字符偏移（从0开始）。超长行按截断标记中的 next_char_offset 续读；total_chars 可能为 unknown")
    private Integer charOffset;

    @JsonAlias("char_limit")
    @ToolParamSchema(name = "char_limit", description = "行内读取Unicode字符数（默认8000，范围1到8000）。使用 char_offset 时请设 limit=1")
    private Integer charLimit;

    /**
     * 获取偏移量，默认1
     */
    public int getOffsetOrDefault() {
        return offset != null ? offset : 1;
    }

    /**
     * 获取限制数量，默认200
     */
    public int getLimitOrDefault() {
        return limit != null ? limit : 200;
    }

    public int getCharOffsetOrDefault() {
        return charOffset != null ? charOffset : 0;
    }

    public int getCharLimitOrDefault() {
        return charLimit != null ? charLimit : 8000;
    }

    public boolean hasCharRange() {
        return charOffset != null || charLimit != null;
    }
}
