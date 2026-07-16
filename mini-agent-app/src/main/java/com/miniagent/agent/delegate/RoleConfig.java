package com.miniagent.agent.delegate;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 角色配置模型
 * 定义一个Agent角色的属性
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleConfig {

    /** 角色ID（如 tester, developer, pm, designer） */
    private String id;

    /** 角色名称（如 测试工程师） */
    private String name;

    /** 角色描述 */
    private String description;

    /** 系统提示词 */
    private String systemPrompt;

    /** 允许使用的工具列表 */
    private List<String> allowedTools;
}
