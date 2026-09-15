package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent_environment 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentEnvironmentParams extends ToolParams {

    @ToolParamSchema(description = "是否包含Git仓库信息", defaultValue = "false")
    private Boolean includeGit;

    /**
     * 是否包含Git信息，默认false
     */
    public boolean isIncludeGitOrDefault() {
        return includeGit != null ? includeGit : false;
    }
}