package com.miniagent.agent.hook;

import com.miniagent.agent.delegate.SubagentContext;
import org.springframework.stereotype.Component;

/**
 * 子 Agent 内禁止再派发 delegate_task（双保险；工具面已剔除）。
 */
@Component
public class DenyNestedDelegateToolHook implements ToolHook {

    @Override
    public String name() {
        return "deny_nested_delegate";
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public ToolPreDecision before(ToolHookContext context) {
        if (context.subagent() || SubagentContext.isActive()) {
            if ("delegate_task".equals(context.toolName())) {
                return ToolPreDecision.deny(
                        "{\"error\":\"子 Agent 禁止嵌套 delegate_task，请把结果摘要返回主 Agent。\"}");
            }
        }
        return ToolPreDecision.proceed(context.argumentsJson());
    }
}
