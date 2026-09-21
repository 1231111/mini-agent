package com.miniagent.agent.context;

import com.miniagent.agent.task.TaskSignals;
import com.miniagent.memory.model.MemoryReadPolicy;

/**
 * 本轮上下文加载策略。字段名即含义，由 {@link com.miniagent.agent.task.TaskSignals}
 * 里命中的事实直接决定，中间不再经一个类别中转。
 *
 * <p>具体条数由 {@link ContextLoader} 按配置与 {@link ContextReferenceDecision} 覆盖；
 * 指代场景下历史为相关度精捞上限，而非盲目最近 N 条。</p>
 *
 * <p>历史条数这里只放兜底值：问答轮给一个小正数，其余给 -1（不裁剪）。
 * 原先问答轮的兜底值是 0，配合「意图等于问答」的判定，会把历史整段清空，
 * 导致跨轮追问答不上来。裁剪历史本身是可恢复的（模型只是少看到几条），
 * 但砍到 0 会让它连上一轮在做什么都不知道，这个下限必须由配置守住。</p>
 *
 * <p>{@code injectMidterm} 在三套策略里都是 false，这是重构前就有的状态，
 * 不是漏改：运行日志（scripts/reports/probe-context-memory.txt）显示旧实现在
 * {@code intent=NEW_TASK} / {@code intent=FILE_DELIVERY} 两轮里
 * {@code injectMidterm} 同样是 false，即旧策略表里那个 true 从未生效。
 * 事实层面的原因也一致 —— 写 MIDTERM 的 {@code MemoryStore.updateMidtermMemory}
 * 在主代码里没有任何调用方，这条通路已经停产，读它没有内容。
 * 真要恢复注入，得先有生产者，不是把这个开关翻过来。</p>
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

    /** 问答且无任何动手信号：不挂任务清单、不给技能表，用户偏好只带摘要。 */
    private static final ContextLoadPolicy LIGHT = new ContextLoadPolicy(
            -1, false, false, false, true, 200, false, false, false);

    /** 续任务：把挂起的任务清单捞回来，其余全开。 */
    private static final ContextLoadPolicy CONTINUE = new ContextLoadPolicy(
            -1, true, false, true, true, 0, true, false, true);

    /**
     * 动手轮：释放上一任务的活动计划，避免新请求被旧清单劫持。
     *
     * <p>未完成 → 挂起，用户说「继续」时 {@code resumeSuspendedTodo} 会捞回。
     * 已完成/已取消 → 归档清空，不再注入、不再推 UI。等确认的计划不走这里
     * （{@code ContextLoader} 在 awaiting_confirm 时跳过）。</p>
     */
    private static final ContextLoadPolicy ACTION = new ContextLoadPolicy(
            -1, true, false, true, true, 0, true, true, false);

    public static ContextLoadPolicy forSignals(TaskSignals signals) {
        TaskSignals s = signals == null ? TaskSignals.NONE : signals;
        if (s.lightTurn()) {
            return LIGHT;
        }
        if (s.continueTask()) {
            return CONTINUE;
        }
        return ACTION;
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
     * 工作记忆随 Todo 注入（续任务/动手轮）；长期/用户/中期与 Blob 开关对齐。
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
