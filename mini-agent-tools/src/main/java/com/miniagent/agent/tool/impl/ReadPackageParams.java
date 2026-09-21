package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * read_package 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReadPackageParams extends ToolParams {

    @ToolParamSchema(description = "Java包名，如 com.miniagent.agent.task", required = true)
    private String packageName;

    @ToolParamSchema(description = "每个文件最大字符数（默认8000）", defaultValue = "8000")
    private Integer maxCharsPerFile;

    /**
     * 获取每个文件最大字符数，默认8000
     */
    public int getMaxCharsPerFileOrDefault() {
        return maxCharsPerFile != null ? maxCharsPerFile : 8000;
    }
}