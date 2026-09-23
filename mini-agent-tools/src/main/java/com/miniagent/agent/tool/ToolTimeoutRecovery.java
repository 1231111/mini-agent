package com.miniagent.agent.tool;

/**
 * 工具在外层闸门超时后的处置策略。
 *
 * <h2>为什么要有这个枚举</h2>
 *
 * <p>改造前这件事写死在 {@code AgentLoop.timeoutToolResult} 的 if-else 里：
 * 每接一个长耗时工具就要在那儿加一个分支，漏掉就掉进兜底的 {@code ToolResult.unknown}
 * —— 而 {@code OUTCOME_UNKNOWN} 会<b>中止整轮任务</b>，一次慢查询就能把整条多步任务打死。
 * 现在把它变成工具自己声明的契约，AgentLoop 只按声明分派。</p>
 *
 * <h2>四种处置的分界线</h2>
 *
 * <p>判定依据只有一个：<b>超时之后，副作用的终态能不能便宜地确定下来。</b>
 * 能确定就别中止，不能确定才中止。</p>
 */
public enum ToolTimeoutRecovery {

    /**
     * 超时即可安全重试，不需要任何核验。
     *
     * <p>适用：只读工具、幂等工具。重跑一次结果一样，不会有重复副作用。</p>
     */
    RETRY,

    /**
     * 超时后任务仍在远端跑，用续查令牌恢复，<b>绝不重新提交</b>。
     *
     * <p>适用：云端异步任务（{@code song_generate}）。超时不等于副作用去向不明 ——
     * 任务还在云端，拿同一个 {@code taskId} 查一次就能确定终态。重新提交会再花一次钱、
     * 还会多出一首歌。</p>
     */
    RESUME,

    /**
     * 超时后副作用可核验：再观察一次就知道真实终态，核验后再决定重试还是换策略。
     *
     * <p>适用：浏览器操作（作用在活着的页面上，再拍一次 {@code browser_snapshot}
     * 就知道点没点上）、本地进程（跑一条只读命令就能查残留）。不判「终态未知」，
     * 但必须先核验 —— 核验步骤写在 {@link ToolExecutionProfile#recoveryHint()} 里。</p>
     */
    VERIFY,

    /**
     * 等待用户回答，超时只是防止拦截失效，不算失败。
     *
     * <p>适用：{@code ask_user_question}。真正的等待由 AgentLoop 让出；
     * 超时说明用户一直没答，转为「等待用户」状态而不是报错。</p>
     */
    AWAIT_USER,

    /**
     * 超时后副作用去向不明 —— 只能中止整轮任务。
     *
     * <p>兜底策略：写类工具且无法核验终态时用它。这是最保守的处置，
     * 因为 {@code OUTCOME_UNKNOWN} 会直接停掉整个 Agent 循环。
     * <b>新工具尽量别用它</b> —— 能落到 RESUME / VERIFY 就别用 ABORT。</p>
     */
    ABORT
}
