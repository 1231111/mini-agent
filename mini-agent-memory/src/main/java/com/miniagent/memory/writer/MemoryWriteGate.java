package com.miniagent.memory.writer;

import com.miniagent.memory.model.AgentEvent;
import com.miniagent.memory.model.GateDecision;
import com.miniagent.memory.model.MemoryEntry;

/**
 * 记忆写入闸门：在重要度评估**之前**判断这条内容是否有资格进入长期记忆。
 *
 * <p>与 {@link ImportanceEvaluator} 的分工：
 * <ul>
 *   <li>闸门回答"**能不能记**"——判据是内容性质（可否推导、是否临时、是否密钥），一票否决。</li>
 *   <li>打分器回答"**值不值得记**"——判据是价值大小，连续分数，配合阈值。</li>
 * </ul>
 * 两者顺序不能颠倒：先算分再拒写，等于让打分器替闸门做决定。
 *
 * <p><b>设计取向：宁可漏拒，不可误拒。</b>
 * 闸门是一票否决，误杀一条真记忆不可恢复，放行一条噪声还能靠遗忘策略回收。
 * 因此实现里的每条规则都必须使用**高置信判据**，不接受"感觉像"的模糊匹配。
 */
public interface MemoryWriteGate {

    /**
     * 裁决一条候选记忆。
     *
     * @param event     产生该记忆的原始事件，可为 null
     * @param candidate 已构建但尚未落库的候选记忆
     * @return 裁决结果；{@code isAllowed()==false} 时调用方必须丢弃
     */
    GateDecision evaluate(AgentEvent event, MemoryEntry candidate);
}
