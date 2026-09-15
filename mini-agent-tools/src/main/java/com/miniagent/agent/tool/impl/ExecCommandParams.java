package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * exec_command 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExecCommandParams extends ToolParams {

    @ToolParamSchema(description = "要执行的命令", required = true)
    private String command;
}