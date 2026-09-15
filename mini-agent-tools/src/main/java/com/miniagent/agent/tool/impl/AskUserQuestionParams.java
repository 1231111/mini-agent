package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * ask_user_question 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AskUserQuestionParams extends ToolParams {

    @ToolParamSchema(description = "要问用户的问题", required = true)
    private String question;

    @ToolParamSchema(description = "选项列表（JSON数组字符串）")
    private String options;

    @ToolParamSchema(description = "是否允许多选", defaultValue = "false")
    private Boolean multiSelect;

    /**
     * 是否允许多选，默认false
     */
    public boolean isMultiSelectOrDefault() {
        return multiSelect != null ? multiSelect : false;
    }
}