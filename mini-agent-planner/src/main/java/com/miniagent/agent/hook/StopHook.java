package com.miniagent.agent.hook;

/**
 * 可插拔收尾钩子（Claude Code Stop / SubagentStop 的服务端适配）。
 * 实现为 Spring Bean 即可自动加入 {@link StopHookChain}。
 */
public interface StopHook {

    /** 钩子名（日志/遥测） */
    String name();

    /** 数值越小越先执行；默认 100 */
    default int order() {
        return 100;
    }

    StopDecision evaluate(StopContext context);
}
