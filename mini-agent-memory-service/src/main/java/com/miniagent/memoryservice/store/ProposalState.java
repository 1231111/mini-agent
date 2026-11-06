package com.miniagent.memoryservice.store;

import java.util.EnumSet;
import java.util.Set;

/**
 * 提案状态（设计文档 §6.3）。前六个是「未终结」：占用 {@code open_item_id}，同一条目同时只能有一个。
 * MONITORING 也算未终结 —— 监控期内再叠加新改动，回滚时就分不清是谁的问题。
 * 文档里的瞬时态 PROMOTED 不落库：晋升与进入监控在同一事务里完成。
 */
public enum ProposalState {
    DETECTED, GENERATING, EVALUATING, APPROVAL_PENDING, EXPERIMENTING, MONITORING,
    /** SHADOW 模式：离线评估做完、结论记录在案，不上线。 */
    SHADOW_RECORDED,
    STABLE, ROLLED_BACK, REJECTED, REJECTED_STATIC, EVAL_FAILED, RETIRED, SUPERSEDED,
    /** 系统原因结束（作业多次失败进死信、未配置进化模型等），不是对候选的判断。 */
    FAILED;

    public static final Set<ProposalState> OPEN =
            EnumSet.of(DETECTED, GENERATING, EVALUATING, APPROVAL_PENDING, EXPERIMENTING, MONITORING);

    /** 进入冷却期的结局：同一问题短期内不再重复提案（复发触发不受冷却限制）。 */
    public static final Set<ProposalState> COOLDOWN = EnumSet.of(ROLLED_BACK, REJECTED,
            REJECTED_STATIC, EVAL_FAILED, SHADOW_RECORDED, FAILED);

    public boolean open() {
        return OPEN.contains(this);
    }
}
