package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * write_file 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WriteFileParams extends ToolParams {

    @ToolParamSchema(description = "文件路径", required = true)
    private String path;

    @ToolParamSchema(description = "文件内容（本次要写入/追加的片段）", required = true)
    private String content;

    @ToolParamSchema(description = "写入模式：overwrite=覆盖（默认）；append=追加")
    private String mode;

    /**
     * 是否为追加模式
     */
    public boolean isAppendMode() {
        return "append".equalsIgnoreCase(mode);
    }
}