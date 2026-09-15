package com.miniagent.agent.tool.impl;

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

    /**
     * 是否递归，默认false
     */
    public boolean isRecursiveOrDefault() {
        return recursive != null ? recursive : false;
    }
}