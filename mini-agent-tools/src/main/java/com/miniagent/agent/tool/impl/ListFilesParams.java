package com.miniagent.agent.tool.impl;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * list_files 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ListFilesParams extends ToolParams {

    @ToolParamSchema(description = "目录路径", required = true)
    private String path;

    @ToolParamSchema(description = "是否递归列出", defaultValue = "false")
    private Boolean recursive;

    @JsonAlias("include_metadata")
    @ToolParamSchema(name = "include_metadata", description = "是否返回文件类型、字节数和最后修改时间等结构化元数据", defaultValue = "false")
    private Boolean includeMetadata;

    @ToolParamSchema(description = "最多返回的条目数，范围1到200；超出时会明确标记 partial", defaultValue = "200")
    private Integer limit;

    /**
     * 是否递归，默认false
     */
    public boolean isRecursiveOrDefault() {
        return recursive != null ? recursive : false;
    }

    public boolean isIncludeMetadataOrDefault() {
        return includeMetadata != null && includeMetadata;
    }

    public int getLimitOrDefault() {
        return limit == null ? 200 : limit;
    }
}
