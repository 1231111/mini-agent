package com.miniagent.agent.tool;

/**
 * 工具调用的结构化终态。
 *
 * <p>三种终态不是一个布尔：{@link #SUCCESS} / 各种失败 / {@link #AWAITING_USER}。
 * 最后一种表示「工具压根没执行，在等用户批准或回答」—— 它既不是成功也不是失败，
 * 混进失败会让上游注入「请换策略」的提示，把模型从正确路径上赶走。</p>
 */
public enum ToolStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED,
    /** 未执行：等用户批准（授权弹窗）或回答问题。不算失败，也不算成功。 */
    AWAITING_USER,
    UNKNOWN
}
