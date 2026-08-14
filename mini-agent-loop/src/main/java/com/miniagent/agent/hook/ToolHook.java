package com.miniagent.agent.hook;

/**
 * PreToolUse / PostToolUse 可插拔钩子（对齐 Claude Code toolHooks）。
 * 实现为 Spring Bean 即可自动加入 {@link ToolHookChain}。
 */
public interface ToolHook {

    String name();

    default int order() {
        return 100;
    }

    default ToolPreDecision before(ToolHookContext context) {
        return ToolPreDecision.proceed(context.argumentsJson());
    }

    /** 可改写/追加工具结果文本 */
    default String after(ToolHookContext context, String result) {
        return result;
    }
}
