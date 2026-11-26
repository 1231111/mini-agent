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

    @ToolParamSchema(description = "严格点分隔 Java 包名，如 com.miniagent.agent.task；不接受路径或绝对路径", required = true)
    private String packageName;

    @ToolParamSchema(description = "每个文件最大字符数，硬上限4000；单次工具总响应硬上限12000字符", defaultValue = "4000")
    private Integer maxCharsPerFile;

    /**
     * 获取每个文件最大字符数，默认8000
     */
    public int getMaxCharsPerFileOrDefault() {
        return maxCharsPerFile != null ? maxCharsPerFile : 4000;
    }
}
