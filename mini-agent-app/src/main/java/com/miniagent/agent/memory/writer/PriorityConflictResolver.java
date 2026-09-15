package com.miniagent.agent.memory.writer;

import com.miniagent.memory.lifecycle.ConflictResolver;
import com.miniagent.memory.model.MemoryEntry;
import com.miniagent.memory.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于优先级链的冲突解决器。
 *
 * <p>来源优先级：USER_STATED(1.0) &gt; SYSTEM_CONFIG(0.8) &gt; TOOL_OBSERVED(0.7)
 * &gt; AGENT_INFERRED(0.5) &gt; LLM_EXTRACTED(0.3)。
 *
 * <p>判定顺序：
 * <ol>
 *   <li><b>时效跨越</b>：新记忆是观测类、旧记忆是推测类，且旧记忆已超过 staleness 阈值
 *       → 新记忆胜出。理由：推测会随时间失效，观测不会。只看来源等级会导致
 *       "三天前 Agent 的一句推断"压过"刚刚工具实测的结果"。</li>
 *   <li>新记忆来源优先级更高 → 取代。</li>
 *   <li>新记忆来源优先级更低 → 丢弃新记忆。</li>
 *   <li>优先级相同 → 比较 confidence，高者胜出。</li>
 * </ol>
 */
@Component
public class PriorityConflictResolver implements ConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(PriorityConflictResolver.class);

    /** 旧推测记忆超过该时长，才允许被新观测跨级取代（避免同轮内反复翻转）。 */
    @Value("${agent.memory.conflict.stale-hours:24}")
    private long staleHours;

    @Override
    public MemoryEntry resolve(MemoryEntry existing, MemoryEntry incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return null;
        }

        double existPriority = getPriority(existing.getSourceType());
        double incomingPriority = getPriority(incoming.getSourceType());

        // 1. 时效跨越：观测取代过期的推测
        if (incomingPriority <= existPriority && observationBeatsInference(existing, incoming)) {
            log.debug("冲突解决: 时效跨越，观测取代推测 ({} 取代 {}，旧记忆已超过 {}h)",
                incoming.getSourceType(), existing.getSourceType(), effectiveStaleHours());
            return supersede(existing, incoming);
        }

        // 2. 来源优先级更高
        if (incomingPriority > existPriority) {
            log.debug("冲突解决: 新记忆优先级更高 ({} > {})，归档旧记忆 id={}",
                incoming.getSourceType(), existing.getSourceType(), existing.getId());
            return supersede(existing, incoming);
        }

        // 3. 来源优先级更低
        if (incomingPriority < existPriority) {
            log.debug("冲突解决: 旧记忆优先级更高 ({} > {})，丢弃新记忆",
                existing.getSourceType(), incoming.getSourceType());
            return null;
        }

        // 4. 同优先级比 confidence
        if (incoming.getConfidence() > existing.getConfidence()) {
            log.debug("冲突解决: 同优先级，新记忆 confidence 更高 ({} > {})",
                incoming.getConfidence(), existing.getConfidence());
            return supersede(existing, incoming);
        }

        log.debug("冲突解决: 同优先级，旧记忆 confidence 更高或相同，丢弃新记忆");
        return null;
    }

    /** 标记取代关系（父指针 + 版本号），返回应落库的新记忆。 */
    private MemoryEntry supersede(MemoryEntry existing, MemoryEntry incoming) {
        incoming.setParentId(existing.getId());
        incoming.setVersionNum(existing.getVersionNum() + 1);
        return incoming;
    }

    /**
     * 观测是否能跨级取代推测。
     * 观测类：用户陈述 / 系统配置 / 工具实测；推测类：Agent 推断 / LLM 抽取（含未知来源）。
     */
    private boolean observationBeatsInference(MemoryEntry existing, MemoryEntry incoming) {
        if (!isObservation(incoming.getSourceType()) || !isInference(existing.getSourceType())) {
            return false;
        }
        long ageMs = incoming.getCreatedAt() - existing.getCreatedAt();
        return ageMs > effectiveStaleHours() * 60L * 60L * 1000L;
    }

    private boolean isObservation(SourceType type) {
        return type == SourceType.USER_STATED
            || type == SourceType.SYSTEM_CONFIG
            || type == SourceType.TOOL_OBSERVED;
    }

    private boolean isInference(SourceType type) {
        return type == SourceType.AGENT_INFERRED
            || type == SourceType.LLM_EXTRACTED
            || type == null;
    }

    /** @Value 未生效时（如单测直接 new）退回默认 24h，避免阈值退化为 0。 */
    private long effectiveStaleHours() {
        return staleHours > 0 ? staleHours : 24L;
    }

    private double getPriority(SourceType sourceType) {
        if (sourceType == null) {
            return SourceType.LLM_EXTRACTED.priority();
        }
        return sourceType.priority();
    }
}
