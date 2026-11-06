package com.miniagent.memoryservice.eval;

/** 回放用规划模型与裁判模型。任一未配置时不领取评估作业。 */
public record EvalModels(MeteredChatModel planner, MeteredChatModel judge) {
    public boolean ready() {
        return planner != null && judge != null;
    }
}
