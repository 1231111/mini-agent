package com.miniagent.agent.scope;

/**
 * 任务作用域：一次「用户交付目标」的身份。
 *
 * <h2>为什么要这个类型</h2>
 *
 * <p>会话（session）和任务（task）是两个不同粒度，但代码里一直只用 sessionId 做 key。
 * 于是任务清单、规划图、压缩摘要这三套<b>任务级</b>状态全部挂在 session 上，
 * 同一会话里做第二个任务时，第一个任务的状态还在原地等着被读到 ——
 * 「历史任务污染当前任务」不是某一处写错，是 key 选错的必然后果。</p>
 *
 * <p>这个类型的职责只有一件：给任务一个身份，让任务级状态有正确的 key。
 * 它不做意图理解，也不存任何业务状态。</p>
 *
 * <h2>key 的形状与兼容性</h2>
 *
 * <p>{@code taskId == 0} 时 {@link #scopeKey()} <b>就等于 sessionId 本身</b>，
 * 不加后缀。这样做的原因有两条：</p>
 * <ul>
 *   <li>历史数据（按 sessionId 落的盘、写的库）就是 0 号任务的槽位，不用迁移、不用改 DDL；</li>
 *   <li>「一个会话只有一个任务」这条最常见路径上，key 与旧行为逐字节相同，
 *       新机制在这条路径上是零风险的。</li>
 * </ul>
 * <p>只有真正发生了任务切换（{@code taskId ≥ 1}）才会出现 {@code sessionId#1} 这种新 key。</p>
 *
 * <h2>为什么隔离用 key 而不是「清理调用」</h2>
 *
 * <p>旧做法是「在合适的时机调用各处的 clear」。这要求每一个写入状态的模块都被记得清，
 * 漏一个就出问题 —— 实测 {@code PlannerStateStore.clear} 与
 * {@code ContextCompressor.clearSession} 都写了却零调用。换成 key 之后，
 * 新任务天然查不到旧状态，<b>不需要任何模块记得清</b>。</p>
 *
 * @param sessionId 会话标识。它是长期身份，一个会话里可以有多个任务。
 * @param taskId    任务序号，同一会话内单调递增。**它才是任务级状态的隔离依据。**
 * @param openedAt  本任务开启时刻（epoch millis），用于可观测性。
 * @param reason    开启原因，写日志与轨迹用，便于排查「为什么这里换了任务」。
 */
public record TaskScope(String sessionId, long taskId, long openedAt, String reason) {

    /**
     * 作用域键的分隔符。
     *
     * <p>取值要求：不会出现在 sessionId 里。sessionId 由前端传或系统生成（UUID 前缀），
     * 实测不含 {@code #}。</p>
     */
    private static final String SEP = "#";

    /** 会话首次被观察到的默认作用域。 */
    public static TaskScope initial(String sessionId, String reason) {
        return new TaskScope(sessionId, 0L, System.currentTimeMillis(), reason);
    }

    /**
     * 任务级状态的统一 key。
     *
     * <p>凡是「属于某一次交付」的状态（规划图、压缩摘要、执行租约……），
     * 一律用它做 key；凡是「属于整个会话」的状态（对话历史、用户长期记忆），
     * 继续用 {@link #sessionId()}。这个划分是这套设计的核心。</p>
     */
    public String scopeKey() {
        return taskId == 0L ? sessionId : sessionId + SEP + taskId;
    }
}
