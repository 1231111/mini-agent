package com.miniagent.agent.core;

/**
 * 本轮 {@link LoopTurnPolicy} 的线程上下文。
 * 虚拟线程不继承 ThreadLocal，工具并行路径须显式拷贝。
 */
public final class LoopTurnContext {

    private static final ThreadLocal<LoopTurnPolicy> TL = new ThreadLocal<>();

    private LoopTurnContext() {}

    public static void set(LoopTurnPolicy policy) { TL.set(policy); }

    public static LoopTurnPolicy current() {
        LoopTurnPolicy p = TL.get();
        return p != null ? p : LoopTurnPolicy.NONE;
    }

    public static void clear() { TL.remove(); }
}
