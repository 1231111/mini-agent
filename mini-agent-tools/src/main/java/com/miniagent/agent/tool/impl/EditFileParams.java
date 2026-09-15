package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * edit_file 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EditFileParams extends ToolParams {

    @ToolParamSchema(description = "要编辑的文件路径", required = true)
    private String path;

    @ToolParamSchema(description = "被替换的原文（逐字符一致，含缩进）", required = true)
    private String oldString;

    @ToolParamSchema(description = "替换后的新内容；删除则传空串", required = true)
    private String newString;

    @ToolParamSchema(description = "替换所有匹配（默认 false，只替换唯一一处）", defaultValue = "false")
    private Boolean replaceAll;

    /**
     * 是否替换所有匹配，默认false
     */
    public boolean isReplaceAllOrDefault() {
        return replaceAll != null ? replaceAll : false;
    }
}