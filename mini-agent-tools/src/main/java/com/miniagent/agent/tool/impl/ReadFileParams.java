package com.miniagent.agent.tool.impl;

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

    @ToolParamSchema(description = "起始行号（默认1）")
    private Integer offset;

    @ToolParamSchema(description = "最大行数（默认200）")
    private Integer limit;

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
}