package com.miniagent.agent.context;

import com.miniagent.memory.model.MemoryReadPolicy;

/**
 * 本轮上下文加载策略。
 *
 * <p>判据只有一个运行时事实：这个会话里是否还有被挂起的 todo 清单。
 * 有 → 续跑，把清单捞回来；没有 → 释放上一任务的清单，按新任务加载。
 * 不再根据用户消息里命中了哪些词来决定。</p>
 *
 * <p>具体条数由 {@link ContextLoader} 按配置与 {@link ContextReferenceDecision} 覆盖；
 * 指代场景下历史为相关度精捞上限，而非盲目最近 N 条。</p>
 *
 * <p>历史条数这里只放兜底值，且不允许是 0：0 会让 {@code selectHistory} 直接返回空，
 * 模型看不到上一轮在做什么，跨轮追问必然答错。这个下限必须由策略本身守住。</p>
 *
 * <p>{@code injectMidterm} 恒为 false：写 MIDTERM 的
 * {@code MemoryStore.updateMidtermMemory} 在主代码里没有任何调用方，
 * 这条通路已经停产，读它没有内容。真要恢复注入，得先有生产者，
 * 不是把这个开关翻过来。</p>
 */
public record ContextLoadPolicy(
        int historyMaxMessages,
        boolean injectTodo,
        boolean injectMidterm,
        boolean injectMemory,
        boolean injectUser,
        int userMaxChars,
        boolean injectSkills,
        boolean suspendActiveTodo,
        boolean resumeSuspendedTodo
) {

    /** 续跑：把挂起的任务清单捞回来，其余全开。 */
    private static final ContextLoadPolicy CONTINUE = new ContextLoadPolicy(
            -1, true, false, true, true, 0, true, false, true);

    /**
     * 新任务：释放上一任务的活动计划，避免新请求被旧清单劫持。
     *
     * <p>未完成 → 挂起，下轮若判定为续跑会捞回；已完成/已取消 → 归档清空，
     * 不再注入、不再推 UI。等确认的计划不走这里
     * （{@code ContextLoader} 在 awaiting_confirm 时跳过）。</p>
     */
    private static final ContextLoadPolicy ACTION = new ContextLoadPolicy(
            -1, true, false, true, true, 0, true, true, false);

    public static ContextLoadPolicy forSuspendedTodo(boolean hasSuspendedTodo) {
        return hasSuspendedTodo ? CONTINUE : ACTION;
    }

    public ContextLoadPolicy withHistoryMaxMessages(int n) {
        return new ContextLoadPolicy(n, injectTodo, injectMidterm, injectMemory, injectUser,
                userMaxChars, injectSkills, suspendActiveTodo, resumeSuspendedTodo);
    }

    public ContextLoadPolicy withInjectMemory(boolean v) {
        return new ContextLoadPolicy(historyMaxMessages, injectTodo, injectMidterm, v, injectUser,
                userMaxChars, injectSkills, suspendActiveTodo, resumeSuspendedTodo);
    }

    /**
     * 工作记忆随 Todo 注入；长期/用户/中期与 Blob 开关对齐。
     * 结构化检索必须用这份 policy，禁止另开旁路。
     */
    public MemoryReadPolicy memoryPolicy() {
        return new MemoryReadPolicy(
                injectTodo,
                injectMemory,
                injectUser,
                injectMidterm,
                userMaxChars);
    }
}
