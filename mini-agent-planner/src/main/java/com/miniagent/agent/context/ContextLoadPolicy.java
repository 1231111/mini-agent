package com.miniagent.agent.context;

import com.miniagent.memory.model.MemoryReadPolicy;

/**
 * 本轮上下文加载策略。
 *
 * <p>这里<b>不再决定「要不要挂起/恢复任务清单」</b>。清单归属于任务，由
 * {@code TaskTodoStore} 与 {@code TaskScopeRegistry} 管；一旦本类也参与搬迁，
 * 就必须回答「这一轮算不算换任务」，而那是一个只能靠猜的问题 ——
 * 之前的实现用「会话里有没有被挂起的清单」当判据，条件一旦成立就恒成立，
 * 于是每一轮都把旧清单捞回来，新任务被上一个任务接管。</p>
 *
 * <p>现在本类只回答一个问题：<b>这一轮要加载多少历史、开哪些记忆通道</b>。
 * 任务边界与清单归属不在这里，见 {@link ContextLoader#load}。</p>
 *
 * <p>历史条数这里只放兜底值，且不允许是 0：0 会让 {@code selectHistory} 直接返回空，
 * 模型看不到上一轮在做什么，跨轮追问必然答错。这个下限必须由策略本身守住。</p>
 *
 * <p>MIDTERM 已退出运行时读写链路。旧文件与 blob 字段仅由 {@code MemoryStore}
 * 用于历史数据兼容导入，不参与当前提示词构建。</p>
 */
public record ContextLoadPolicy(
        int historyMaxMessages,
        boolean injectTodo,
        boolean injectMemory,
        boolean injectUser,
        int userMaxChars,
        boolean injectSkills
) {

    /**
     * 默认策略：不裁剪历史（-1），四个注入通道全开。
     *
     * <p>之前有 CONTINUE / ACTION 两个常量，区别只在「挂起还是恢复清单」；
     * 那两个动作已经删掉，剩下的取值完全相同，所以合并成一个。</p>
     */
    private static final ContextLoadPolicy DEFAULTS = new ContextLoadPolicy(
            -1, true, true, true, 0, true);

    public static ContextLoadPolicy defaults() {
        return DEFAULTS;
    }

    public ContextLoadPolicy withHistoryMaxMessages(int n) {
        return new ContextLoadPolicy(n, injectTodo, injectMemory, injectUser,
                userMaxChars, injectSkills);
    }

    public ContextLoadPolicy withInjectMemory(boolean v) {
        return new ContextLoadPolicy(historyMaxMessages, injectTodo, v, injectUser,
                userMaxChars, injectSkills);
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
                userMaxChars);
    }
}
